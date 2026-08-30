package com.dynamicruntime.common.util

import com.dynamicruntime.common.exception.KdrException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * The `@t` fragment construct (issue #505), exercised in `commonTest` so the exact evaluation runs on both the
 * JVM backend and the Kotlin/JS frontend — the whole reason the construct lives in the kernel: the two must
 * resolve a template identically. Uses a fake `Map`-backed [FragmentResolver], since Phase 1 is the construct
 * and the seam, not the platform resolvers.
 *
 * Templates are written as `$$"""..."""` multi-dollar literals, so a single `$` is literal and `${...}` is the
 * template's own block rather than Kotlin interpolation.
 */
class FragmentConstructTest {

    /** A resolver over a fixed map of fragment key -> raw template text. */
    private fun resolverOf(vararg pairs: Pair<String, String>): FragmentResolver {
        val map = pairs.toMap()
        return FragmentResolver { key -> map[key] }
    }

    private fun errorCode(block: () -> Unit): Any? =
        assertFailsWith<KdrException> { block() }.extraData[KdrException.errorCodeKey]

    @Test
    fun noParamsInheritsTheCallersScope() {
        val r = resolverOf("greeting" to $$"""Hi ${user}""")
        // The pulled fragment reads `user`, which it inherits from the caller because no params were given.
        assertEquals("Hi Ada", $$"""${@t("greeting")}""".evalTemplate(mapOf("user" to "Ada"), resolver = r))
    }

    @Test
    fun explicitParamsRunHermetic() {
        val r = resolverOf("greeting" to $$"""Hi ${user}""")
        // The binding is the fragment's whole scope: the ambient `user` is ignored, the bound one is used.
        assertEquals(
            "Hi Bo",
            $$"""${@t("greeting", user: "Bo")}""".evalTemplate(mapOf("user" to "Ada"), resolver = r),
        )
    }

    @Test
    fun hermeticScopeCannotSeeAnUnboundAmbientVariable() {
        // Proof the isolation is real: a hermetic fragment that reads a variable it was not handed throws,
        // even though the caller had it. This is the footgun the hermetic rule closes.
        val r = resolverOf("greeting" to $$"""Hi ${user}""")
        assertEquals(
            ScriptError.missingKey,
            errorCode { $$"""${@t("greeting", other: "x")}""".evalTemplate(mapOf("user" to "Ada"), resolver = r) },
        )
    }

    @Test
    fun theKeyMayBeComputed() {
        val r = resolverOf("one" to "just one", "many" to "lots")
        // `@t(which)` evaluates its argument, so the key comes from the data -- the dynamic-selection case.
        assertEquals("lots", $$"""${@t(which)}""".evalTemplate(mapOf("which" to "many"), resolver = r))
    }

    @Test
    fun theChooserPattern() {
        val r = resolverOf(
            "wf.noItems" to "No items",
            "wf.oneItem" to "One item",
            "wf.manyItems" to $$"""${n} items""",
        )
        fun render(n: Long) =
            $$"""${ n == 0 ? @t("wf.noItems") : n == 1 ? @t("wf.oneItem") : @t("wf.manyItems") }"""
                .evalTemplate(mapOf("n" to n), resolver = r)
        assertEquals("No items", render(0))
        assertEquals("One item", render(1))
        assertEquals("5 items", render(5))
    }

    @Test
    fun aNotFoundFragmentTakesTheElvisDefault() {
        val r = resolverOf("present" to "here")
        // In a `?:` position a not-found fragment is null, so the default applies -- the one default mechanism.
        assertEquals("fallback", $$"""${@t("absent") ?: "fallback"}""".evalTemplate(emptyMap(), resolver = r))
        assertEquals("here", $$"""${@t("present") ?: "fallback"}""".evalTemplate(emptyMap(), resolver = r))
    }

    @Test
    fun aNotFoundFragmentOutsideAGuardIsAnError() {
        val r = resolverOf("present" to "here")
        assertEquals(
            ScriptError.fragmentNotFound,
            errorCode { $$"""${@t("absent")}""".evalTemplate(emptyMap(), resolver = r) },
        )
    }

    @Test
    fun aNonTextKeyIsATypeMismatch() {
        val r = resolverOf("x" to "y")
        assertEquals(
            ScriptError.typeMismatch,
            errorCode { $$"""${@t(42)}""".evalTemplate(emptyMap(), resolver = r) },
        )
    }

    @Test
    fun usingTheConstructWithNoResolverReports() {
        assertEquals(
            ScriptError.noResolver,
            errorCode { $$"""${@t("anything")}""".evalTemplate(emptyMap()) },
        )
    }

    @Test
    fun aFragmentMayPullAnotherFragment() {
        val r = resolverOf(
            "outer" to $$"""[${@t("inner")}]""",
            "inner" to "core",
        )
        assertEquals("[core]", $$"""${@t("outer")}""".evalTemplate(emptyMap(), resolver = r))
    }

    @Test
    fun aDirectCycleIsNamedAsOne() {
        val r = resolverOf("a" to $$"""${@t("a")}""")
        val e = assertFailsWith<KdrException> { $$"""${@t("a")}""".evalTemplate(emptyMap(), resolver = r) }
        assertEquals(ScriptError.fragmentCycle, e.extraData[KdrException.errorCodeKey])
        // The path is the useful part -- it says which fragments form the loop.
        assertTrue(e.message!!.contains("a -> a"), "should name the cycle path, was: ${e.message}")
    }

    @Test
    fun anIndirectCycleIsNamedWithItsWholePath() {
        val r = resolverOf(
            "a" to $$"""${@t("b")}""",
            "b" to $$"""${@t("c")}""",
            "c" to $$"""${@t("a")}""",
        )
        val e = assertFailsWith<KdrException> { $$"""${@t("a")}""".evalTemplate(emptyMap(), resolver = r) }
        assertEquals(ScriptError.fragmentCycle, e.extraData[KdrException.errorCodeKey])
        assertTrue(e.message!!.contains("a -> b -> c -> a"), "should name the path, was: ${e.message}")
    }

    @Test
    fun oneFragmentPulledFromTwoBranchesIsReuseNotACycle() {
        // The reason cycle detection tracks *ancestry* rather than a visited set: `shared` legitimately appears
        // twice, on two different branches. A visited set would refuse this correct template.
        val r = resolverOf(
            "top" to $$"""${@t("left")}+${@t("right")}""",
            "left" to $$"""L${@t("shared")}""",
            "right" to $$"""R${@t("shared")}""",
            "shared" to "S",
        )
        assertEquals("LS+RS", $$"""${@t("top")}""".evalTemplate(emptyMap(), resolver = r))
    }

    @Test
    fun aDeepButAcyclicChainReportsDepthRatherThanACycle() {
        // Distinct fragments all the way down: ancestry can never catch it, so the depth cap does -- and it
        // must not accuse the author of a cycle that is not there.
        val chain = (0..SEXP.maxIncludeDepth + 2).associate { i -> "f$i" to $$"""${@t("f$${i + 1}")}""" }
        val r = FragmentResolver { key -> chain[key] }
        val e = assertFailsWith<KdrException> { $$"""${@t("f0")}""".evalTemplate(emptyMap(), resolver = r) }
        assertEquals(ScriptError.fragmentIncludeTooDeep, e.extraData[KdrException.errorCodeKey])
        assertTrue(e.message!!.contains("rather than a cycle"), "was: ${e.message}")
    }

    @Test
    fun anErrorInsideAFragmentNamesTheFragment() {
        // Its position is an offset into the fragment's own text, so without the name it points at a place in
        // the caller that need not exist. The caller here is 12 characters; the fragment's column is 4.
        val r = resolverOf("g" to $$"""Hi ${who}""")
        val e = assertFailsWith<KdrException> { $$"""${@t("g")}""".evalTemplate(emptyMap(), resolver = r) }
        assertEquals(ScriptError.missingKey, e.extraData[KdrException.errorCodeKey])
        assertEquals("g", e.extraData[KdrException.fragmentKey])
        assertTrue(e.message!!.contains("In fragment 'g'"), "was: ${e.message}")
    }

    @Test
    fun theInnermostFragmentIsTheOneNamed() {
        // A nested failure should point at the fragment to fix, not the outermost one that merely pulled it.
        val r = resolverOf("outer" to $$"""${@t("inner")}""", "inner" to $$"""${who}""")
        val e = assertFailsWith<KdrException> { $$"""${@t("outer")}""".evalTemplate(emptyMap(), resolver = r) }
        assertEquals("inner", e.extraData[KdrException.fragmentKey])
    }

    @Test
    fun aGuardCoversAnAbsenceInsideTheFragment() {
        // Tolerance flows into the pull: the fragment reads `who`, nobody supplied it, so the default applies.
        val r = resolverOf("g" to $$"""Hi ${who}""")
        assertEquals("fallback", $$"""${@t("g") ?: "fallback"}""".evalTemplate(emptyMap(), resolver = r))
        // ...and when the data *is* there, the fragment renders normally.
        assertEquals("Hi Ada", $$"""${@t("g") ?: "fallback"}""".evalTemplate(mapOf("who" to "Ada"), resolver = r))
    }

    @Test
    fun aGuardDoesNotHideABrokenFragment() {
        // A guard says what to do about a missing value, not permission to swallow a defect -- which would go
        // unnoticed everywhere the fragment is used.
        val broken = resolverOf("g" to $$"""${1 +}""")
        assertEquals(
            ScriptError.syntaxError,
            errorCode { $$"""${@t("g") ?: "fallback"}""".evalTemplate(emptyMap(), resolver = broken) },
        )
        val mismatched = resolverOf("g" to $$"""${n * 2}""")
        assertEquals(
            ScriptError.typeMismatch,
            errorCode {
                $$"""${@t("g") ?: "fallback"}""".evalTemplate(mapOf("n" to "text"), resolver = mismatched)
            },
        )
    }

    @Test
    fun aDuplicateBindingNameIsRefused() {
        // One of the two values would silently win, and which is not something a reader should have to work out.
        val r = resolverOf("g" to $$"""[${x}]""")
        assertEquals(
            ScriptError.syntaxError,
            errorCode { $$"""${@t("g", x: "one", x: "two")}""".evalTemplate(emptyMap(), resolver = r) },
        )
    }

    @Test
    fun aBindingNamedForAWordLiteralIsRefused() {
        // Inside the fragment `${null}` is the literal, so such a binding could never be read back.
        val r = resolverOf("g" to "x")
        for (reserved in listOf("null", "true", "false")) {
            val template = $$"""${@t("g", $$reserved: 1)}"""
            // Prove the interpolation built the template intended -- otherwise a syntax error from a
            // mis-constructed string would let this pass for the wrong reason.
            assertTrue(template.contains("$reserved: 1"), "template was: $template")
            val e = assertFailsWith<KdrException> { template.evalTemplate(emptyMap(), resolver = r) }
            assertEquals(ScriptError.syntaxError, e.extraData[KdrException.errorCodeKey])
            assertTrue(
                e.message!!.contains("word literal"),
                "should refuse '$reserved' as a word literal, was: ${e.message}",
            )
        }
    }

    @Test
    fun theConstructIsOptIn() {
        // A template that never writes `@t` behaves exactly as before, resolver or not.
        assertEquals("just text", "just text".evalTemplate(emptyMap()))
        assertEquals("n=3", $$"""n=${count}""".evalTemplate(mapOf("count" to 3L)))
    }

    @Test
    fun theCheckerSeesTheKeyAndBindingPathsAndDoesNotChokeOnTheConstruct() {
        // The static checker (Phase 2's home for reference validation) must at least parse `@t` and collect the
        // data its key and bindings read -- both are read in the caller's scope.
        val analysis = $$"""${@t(which, count: user.count)}""".analyzeTemplate()
        assertEquals(emptyList(), analysis.issues.map { it.code }) // parses cleanly
        assertTrue(analysis.paths.required.contains("which"))
        assertTrue(analysis.paths.required.contains("user.count"))
    }

    @Test
    fun anAtNotFollowedByTheConstructNameIsASyntaxError() {
        assertEquals(
            ScriptError.syntaxError,
            errorCode { $$"""${@x("k")}""".evalTemplate(emptyMap(), resolver = resolverOf("k" to "v")) },
        )
    }

    @Test
    fun theConstructRequiresAKeyArgument() {
        // `@t()` names nothing to pull.
        assertEquals(
            ScriptError.syntaxError,
            errorCode { $$"""${@t()}""".evalTemplate(emptyMap(), resolver = resolverOf("k" to "v")) },
        )
    }
}
