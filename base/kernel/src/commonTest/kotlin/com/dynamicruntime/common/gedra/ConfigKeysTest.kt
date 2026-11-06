package com.dynamicruntime.common.gedra

import com.dynamicruntime.common.context.LiteCxt
import com.dynamicruntime.common.gedra.workflow.WfEntry
import com.dynamicruntime.common.gedra.workflow.WfSaveKind
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * The keys one configuration's definition stands over another's by (issues #945, #1634), and the cut they make: the one
 * rule a client's own configuration stands over its template's copies by, and a stored entry marked to stand over code.
 */
class ConfigKeysTest {
    private val cxt = LiteCxt()

    private val code = gedraConfig(cxt, "base", "client.acme", "acme") {
        trait("NoteEntry", "note", setOf(GedraDataType.formDoc), "A note.") { property("text", "Text.") }
        trait("ScoreEntry", "score", setOf(GedraDataType.formDoc), "A score.") { property("stars", "Stars.") }
        cfact("reviewed", "Review", "It was reviewed.")
        workflow("make", WfEntry.creation) { task("n", "Name") { trait("note"); save("c", "Create") } }
        workflow("review", WfEntry.normal) { task("r", "Review") { trait("score"); save("s", "Save", WfSaveKind.edit) } }
        workflowParams("review") { lifetime(end = "2099-01-01T00:00:00Z") }
        traitUsage("note", "Note", $$"${text}")
    }

    @Test
    fun aConfigurationHoldsItsDefinitionsByKeyAndATraitsGeneratedTypes() {
        val keys = ConfigKeys.of(code)
        assertEquals(setOf("note", "score"), keys.traits)
        assertTrue(keys.types.containsAll(setOf("client.acme.NoteEntry", "client.acme.NoteData")))
        assertEquals(mapOf("make" to WfEntry.creation, "review" to WfEntry.normal), keys.workflows)
        assertEquals(setOf(WfEntry.creation), keys.singletonKinds)
        assertTrue(keys.usages)
        assertEquals(setOf("reviewed"), keys.cfacts)
    }

    @Test
    fun whatStandsOverIsTheSameKeyOrACreationWorkflowOfAnyIdAndColumnsOverColumns() {
        val mine = ConfigKeys(
            traits = setOf("note", "mine"),
            workflows = mapOf("myMake" to WfEntry.creation, "other" to WfEntry.normal),
            usages = true,
        )
        val over = mine.over(ConfigKeys.of(code))
        assertEquals(setOf("note"), over.traits)
        // A creation workflow stands over the code's of its kind, whatever its id; a normal one only by its id.
        assertEquals(mapOf("myMake" to WfEntry.creation), over.workflows)
        assertTrue(over.usages)
        // What is left is what has nothing under it.
        assertEquals(setOf("mine"), (mine - over).traits)
        assertEquals(setOf("other"), (mine - over).workflows.keys)
    }

    @Test
    fun theCutTakesOutWhatIsStoodOverWholeAndTheDefaultsOfAWorkflowItLoses() {
        val cut = code.cutBy(ConfigKeys(traits = setOf("note"), workflows = mapOf("review" to WfEntry.normal, "mine" to WfEntry.creation)))
        assertEquals(setOf("score"), cut.traits.keys)
        // The creation workflow goes by its kind, the normal one by its id, and the normal one's default goes with it.
        assertEquals(emptySet(), cut.workflows.keys)
        assertEquals(emptySet(), cut.workflowParams.keys)
        // Columns and cfacts stay unless they are stood over; the rest of the configuration is as it was.
        assertEquals(1, cut.usages.size)
        assertEquals(code.gedraId, cut.gedraId)
        assertEquals(code.namespace, cut.namespace)
        assertEquals(emptyList(), code.cutBy(ConfigKeys(usages = true)).usages)
        // Nothing to cut is the configuration itself.
        assertSame(code, code.cutBy(ConfigKeys.none))
    }

    @Test
    fun aConfigurationMarksWhatItStandsOverByName() {
        val marked = gedraConfig(cxt, "over", "client.acme.over", "acme") {
            trait("MineEntry", "note", setOf(GedraDataType.formDoc), "Mine.") { property("memo", "Memo.") }
            workflow("myMake", WfEntry.creation) { task("n", "Name") { trait("note"); save("c", "Create") } }
            overridesCode("note", "myMake", "nothing")
        }.overridesCode
        assertEquals(setOf("note"), marked.traits)
        // The trait's generated types follow it.
        assertEquals(setOf("client.acme.over.MineEntry", "client.acme.over.MineData"), marked.types)
        assertEquals(mapOf("myMake" to WfEntry.creation), marked.workflows)
        // A name the configuration does not declare marks nothing.
        assertEquals(ConfigKeys.none, gedraConfig(cxt, "x", "client.acme.x", "acme") { overridesCode("absent") }.overridesCode)
    }
}
