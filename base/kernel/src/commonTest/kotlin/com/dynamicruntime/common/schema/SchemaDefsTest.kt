package com.dynamicruntime.common.schema

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The recursive `$defs` closure ([collectDefClosure]) -- the hunt that makes a workflow view self-contained
 * (issue #534). In `commonTest`, so the exact walk runs on the JVM and under Kotlin/JS.
 */
class SchemaDefsTest {
    // A -> B (via a property) -> C (via array items); D is unrelated; C also appears in a nested anyOf branch.
    private val defs: Map<String, Any?> = mapOf(
        "ns.A" to mapOf(
            "type" to "object",
            "properties" to mapOf("b" to mapOf(SCH.dRef to "#/${SCH.dDefs}/ns.B")),
            "anyOf" to listOf(mapOf(SCH.dRef to "#/${SCH.dDefs}/ns.C")),
        ),
        "ns.B" to mapOf("type" to "array", "items" to mapOf(SCH.dRef to "#/${SCH.dDefs}/ns.C")),
        "ns.C" to mapOf("type" to "string"),
        "ns.D" to mapOf("type" to "integer"),
    )

    @Test
    fun closureFollowsRefsAtAnyDepthAndStopsAtTheReachableSet() {
        val closure = collectDefClosure(listOf("ns.A"), defs)
        assertEquals(setOf("ns.A", "ns.B", "ns.C"), closure.keys)   // D is not reachable, so not included
        assertEquals(defs["ns.C"], closure["ns.C"])                 // bodies are carried verbatim
    }

    @Test
    fun aSeedAbsentFromTheBagIsSkippedNotFaulted() {
        // A dangling ref is a boot-time concern, not this walk's; it collects what exists and moves on.
        val closure = collectDefClosure(listOf("ns.A", "ns.missing"), defs)
        assertEquals(setOf("ns.A", "ns.B", "ns.C"), closure.keys)
    }

    @Test
    fun aCycleTerminates() {
        val cyclic = mapOf(
            "ns.X" to mapOf("properties" to mapOf("y" to mapOf(SCH.dRef to "#/${SCH.dDefs}/ns.Y"))),
            "ns.Y" to mapOf("properties" to mapOf("x" to mapOf(SCH.dRef to "#/${SCH.dDefs}/ns.X"))),
        )
        assertEquals(setOf("ns.X", "ns.Y"), collectDefClosure(listOf("ns.X"), cyclic).keys)
    }

    @Test
    fun refNameReadsALocalDefsPointerAndRejectsAnythingElse() {
        assertEquals("ns.A", refName("#/${SCH.dDefs}/ns.A"))
        assertNull(refName("https://example.com/schema"))
        assertNull(refName("#/properties/x"))
    }

    @Test
    fun emptySeedsGiveAnEmptyClosure() {
        assertTrue(collectDefClosure(emptyList(), defs).isEmpty())
    }

    // A discriminator's `defaultMapping` is a bare ref string, which a walk looking only for `$ref` never sees
    // (issue #813). The closure must carry the default branch -- or the union it ships cannot be parsed, which is
    // what the frontend does with a workflow view's `$defs`. Parsed here, so it is checked on both runtimes.
    @Test
    fun theClosureCarriesAUnionsDefaultBranchAndParses() {
        fun branch(kind: String) = mapOf(
            SCH.type to SCT.kObject,
            SCH.properties to mapOf("kind" to mapOf(SCH.type to SCT.string, SCH.const to kind)),
        )
        val union = mapOf(
            "u.Holder" to mapOf(
                SCH.type to SCT.kObject,
                SCH.properties to mapOf("entry" to mapOf(SCH.dRef to "#/${SCH.dDefs}/u.Entry")),
            ),
            "u.Entry" to mapOf(
                SCH.oneOf to listOf(mapOf(SCH.dRef to "#/${SCH.dDefs}/u.Known")),
                SCH.discriminator to mapOf(
                    SCH.propertyName to "kind",
                    SCH.defaultMapping to "#/${SCH.dDefs}/u.Opaque",
                ),
            ),
            "u.Known" to branch("known"),
            "u.Opaque" to branch("opaque"),
            "u.Unrelated" to mapOf(SCH.type to SCT.string),
        )
        val closure = collectDefClosure(listOf("u.Holder"), union)
        assertEquals(setOf("u.Holder", "u.Entry", "u.Known", "u.Opaque"), closure.keys)
        assertTrue("u.Holder" in parseSchemaTypes(closure))
    }

    // One rule for what a reference names -- the parser's: a pointer names its type, and anything else is taken as
    // the name as written. So a bare name the parser would resolve is carried too.
    @Test
    fun aReferenceResolvesAsTheParserResolvesIt() {
        val bare = mapOf(
            "b.A" to mapOf(SCH.properties to mapOf("b" to mapOf(SCH.dRef to "b.B"))),
            "b.B" to mapOf(SCH.type to SCT.string),
        )
        assertEquals(setOf("b.A", "b.B"), collectDefClosure(listOf("b.A"), bare).keys)
        // A non-local URI names nothing in the bag, so nothing is carried for it.
        val remote = mapOf("r.A" to mapOf(SCH.properties to mapOf("x" to mapOf(SCH.dRef to "https://example.com/s"))))
        assertEquals(setOf("r.A"), collectDefClosure(listOf("r.A"), remote).keys)
    }
}
