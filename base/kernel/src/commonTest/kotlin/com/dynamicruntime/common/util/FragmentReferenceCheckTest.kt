package com.dynamicruntime.common.util

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Static validation of `@t` references in a fragment file (Phase 2 of issue #505), in `commonTest` so it runs
 * on both targets and cannot drift from the runtime resolution it mirrors. A reference resolves within the
 * caller's own two-tier map, exactly as `${namespace.key}` does, so these tests build the map directly.
 */
class FragmentReferenceCheckTest {

    @Test
    fun resolveFragmentTakesExactlyTwoParts() {
        val m = mapOf("email" to mapOf("subject" to "Hi"))
        assertEquals("Hi", m.resolveFragment("email.subject"))
        assertNull(m.resolveFragment("email"), "one part names no value")
        assertNull(m.resolveFragment("email.subject.extra"), "a third part names a tier the map lacks")
        assertNull(m.resolveFragment("email.missing"))
        assertNull(m.resolveFragment("nope.subject"))
        assertNull(m.resolveFragment(".subject"), "an empty namespace")
        assertNull(m.resolveFragment("email."), "an empty key")
    }

    @Test
    fun analyzeCollectsLiteralReferencesButNotComputedOnes() {
        val a = $$"""${@t("a.b")} ${@t(which)} ${@t("c.d", p: 1)}""".analyzeTemplate()
        assertEquals(listOf("a.b", "c.d"), a.refs.map { it.key })
    }

    @Test
    fun aValidReferenceIsClean() {
        val m = mapOf(
            "wf" to mapOf(
                "chooser" to $$"""${@t("wf.noItems")}""",
                "noItems" to "none",
            ),
        )
        assertEquals(emptyList(), m.checkFragmentReferences())
    }

    @Test
    fun aDanglingReferenceIsReportedWithItsEntryAndKey() {
        val m = mapOf(
            "wf" to mapOf(
                "chooser" to $$"""${@t("wf.gone")}""",
                "noItems" to "none",
            ),
        )
        val issues = m.checkFragmentReferences()
        assertEquals(1, issues.size)
        assertEquals(ScriptError.fragmentNotFound, issues[0].code)
        assertTrue(issues[0].message.contains("wf.chooser"), "names the entry: ${issues[0].message}")
        assertTrue(issues[0].message.contains("wf.gone"), "names the missing key: ${issues[0].message}")
    }

    @Test
    fun aGuardedReferenceToAnAbsentFragmentIsNotReported() {
        // `?:` says what happens when the fragment is not there, so reporting it would refuse content that is
        // deliberately optional -- and on a strict boot, refuse to start. The case that bites hardest is a base
        // file referencing copy only a client overlay supplies.
        val m = mapOf("wf" to mapOf("a" to $$"""${@t("wf.optional") ?: "default"}"""))
        assertEquals(emptyList(), m.checkFragmentReferences())
    }

    @Test
    fun aGuardedReferenceStillCountsAsACycleEdgeWhenItResolves() {
        // Guarded only says what to do when absent. When it *is* there, render time follows it like any other,
        // so the cycle is real and must still be reported.
        val m = mapOf(
            "wf" to mapOf(
                "a" to $$"""${@t("wf.b") ?: "d"}""",
                "b" to $$"""${@t("wf.a")}""",
            ),
        )
        val cycles = m.checkFragmentReferences().filter { it.code == ScriptError.fragmentCycle }
        assertEquals(1, cycles.size, "a resolvable guarded reference is still an edge")
    }

    @Test
    fun aReferenceInATernaryBranchIsStillRequired() {
        // Only the *condition* is a tolerant position; a branch is not -- mirroring collectPaths exactly.
        val m = mapOf("wf" to mapOf("a" to $$"""${flag ? @t("wf.gone") : "x"}"""))
        val issues = m.checkFragmentReferences()
        assertEquals(1, issues.count { it.code == ScriptError.fragmentNotFound })
    }

    @Test
    fun aComputedKeyIsNotValidatedStatically() {
        // `@t(which)` names a fragment only at render time, so there is nothing to resolve here.
        val m = mapOf("wf" to mapOf("chooser" to $$"""${@t(which)}"""))
        assertEquals(emptyList(), m.checkFragmentReferences())
    }

    @Test
    fun aReferenceCycleIsReportedWithItsPath() {
        val m = mapOf(
            "wf" to mapOf(
                "a" to $$"""${@t("wf.b")}""",
                "b" to $$"""${@t("wf.c")}""",
                "c" to $$"""${@t("wf.a")}""",
            ),
        )
        val cycles = m.checkFragmentReferences().filter { it.code == ScriptError.fragmentCycle }
        assertEquals(1, cycles.size)
        assertTrue(cycles[0].message.contains("wf.a -> wf.b -> wf.c -> wf.a"), "was: ${cycles[0].message}")
    }

    @Test
    fun aSelfReferenceIsACycle() {
        val m = mapOf("wf" to mapOf("a" to $$"""${@t("wf.a")}"""))
        val cycles = m.checkFragmentReferences().filter { it.code == ScriptError.fragmentCycle }
        assertEquals(1, cycles.size)
        assertTrue(cycles[0].message.contains("wf.a -> wf.a"))
    }

    @Test
    fun oneFragmentReferencedFromTwoBranchesIsReuseNotACycle() {
        val m = mapOf(
            "f" to mapOf(
                "top" to $$"""${@t("f.left")}+${@t("f.right")}""",
                "left" to $$"""L${@t("f.shared")}""",
                "right" to $$"""R${@t("f.shared")}""",
                "shared" to "S",
            ),
        )
        assertEquals(emptyList(), m.checkFragmentReferences())
    }

    @Test
    fun aDanglingReferenceIsReportedOnceNotAlsoAsACycleEdge() {
        val m = mapOf("f" to mapOf("a" to $$"""${@t("f.a")} ${@t("f.gone")}"""))
        val issues = m.checkFragmentReferences()
        assertEquals(1, issues.count { it.code == ScriptError.fragmentNotFound }, "the dangling ref, once")
        assertEquals(1, issues.count { it.code == ScriptError.fragmentCycle }, "the f.a self-cycle")
    }
}
