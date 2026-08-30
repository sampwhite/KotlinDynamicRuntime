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
    fun aFragmentCycleIsBoundedRatherThanOverflowing() {
        // A computed cycle the static checker cannot see: `a` pulls `a`. The include-depth cap stops it.
        val r = resolverOf("a" to $$"""${@t("a")}""")
        assertEquals(
            ScriptError.fragmentCycleTooDeep,
            errorCode { $$"""${@t("a")}""".evalTemplate(emptyMap(), resolver = r) },
        )
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
