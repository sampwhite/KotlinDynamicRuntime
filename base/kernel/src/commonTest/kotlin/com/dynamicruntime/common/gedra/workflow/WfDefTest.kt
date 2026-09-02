package com.dynamicruntime.common.gedra.workflow

import com.dynamicruntime.common.context.KdrCxtBase
import com.dynamicruntime.common.context.LiteCxt
import com.dynamicruntime.common.exception.KdrException
import com.dynamicruntime.common.gedra.GE
import com.dynamicruntime.common.gedra.GedraConfigType
import com.dynamicruntime.common.gedra.GedraId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The workflow definition model (issue #533), in `commonTest` so the exact source runs on the JVM and under
 * Kotlin/JS: a frontend reading a definition and a backend enforcing one must agree on what it says.
 *
 * Three things are covered that a one-task creation fixture never reaches: the **multi-task** model
 * (`showTaskList`, per-task completeness), the **string-encoded** forms a data-authored definition arrives in,
 * and every **structural refusal** -- each of which is a state a working sample cannot be in.
 */
class WfDefTest {
    private val cxt: KdrCxtBase = LiteCxt()

    private fun creation(build: WfTaskBuilder.() -> Unit = { trait("name"); save("create", "Create") }): Map<String, Any?> =
        WfDefBuilder("createForm", WfEntry.creation).apply { task("identify", "Name it", build) }.build()

    private fun entry(traitId: String, data: Any? = mapOf("x" to 1)): Map<String, Any?> =
        mapOf(GE.traitId to traitId, GE.data to data)

    @Test
    fun builderRoundTripsThroughTheSchema() {
        val def = parseWfDef(cxt, creation { trait("name"); trait("expenseReport", required = false); save("create", "Create") })
        assertEquals("createForm", def.workflowId)
        assertEquals(WfEntry.creation, def.entry)
        val task = def.tasks.single()
        assertEquals(listOf("name"), task.requiredTraitIds)
        assertEquals(listOf("name", "expenseReport"), task.displayOrder)
        assertEquals(WfSaveKind.create, task.saves.single().kind)
        assertFalse(def.showTaskList)
    }

    @Test
    fun stringEncodedValuesAreCoercedOnTheWayIn() {
        // Decision 5: a definition from data may spell a boolean, an enum and a list as strings.
        val raw = mapOf(
            WFD.workflowId to "createForm",
            WFD.entry to "creation",
            WFD.tasks to listOf(
                mapOf(
                    WFD.id to "identify", WFD.label to "Name it",
                    WFD.traits to listOf(
                        mapOf(WFD.traitId to "name", WFD.required to "true"),
                        mapOf(WFD.traitId to "notes", WFD.required to "false"),
                    ),
                    WFD.saves to listOf(mapOf(WFD.id to "create", WFD.label to "Create", WFD.kind to "create")),
                    WFD.layout to mapOf(WFD.order to "notes,name", WFD.edit to "inline"),
                ),
            ),
        )
        val def = parseWfDef(cxt, raw)
        val task = def.tasks.single()
        assertEquals(listOf("name"), task.requiredTraitIds)
        assertEquals(listOf("notes", "name"), task.displayOrder)
        assertEquals(WfEditMode.inline, task.layout?.edit)
    }

    @Test
    fun schemaRefusesWhatItCannotRead() {
        // A missing required field and an unknown enum value are schema failures, named by path.
        val noTasks = mapOf(WFD.workflowId to "w", WFD.entry to "creation")
        val e1 = assertFailsWith<KdrException> { parseWfDef(cxt, noTasks) }
        assertTrue(e1.message!!.contains(WFD.tasks), e1.message)
        val badEntry = creation().toMutableMap().also { it[WFD.entry] = "sometimes" }
        val e2 = assertFailsWith<KdrException> { parseWfDef(cxt, badEntry) }
        assertTrue(e2.message!!.contains(WFD.entry), e2.message)
    }

    @Test
    fun creationWorkflowHasExactlyOneTaskAndOneCreateSave() {
        val two = WfDefBuilder("w", WfEntry.creation).apply {
            task("a", "A") { trait("name"); save("s", "S") }
            task("b", "B") { trait("name"); save("s", "S") }
        }.build()
        assertFailsWith<KdrException> { parseWfDef(cxt, two) }
        val noSave = creation { trait("name") }
        assertFailsWith<KdrException> { parseWfDef(cxt, noSave) }
        val twoSaves = creation { trait("name"); save("s1", "One"); save("s2", "Two") }
        assertFailsWith<KdrException> { parseWfDef(cxt, twoSaves) }
    }

    @Test
    fun structuralMistakesAreRefusedWhereWritten() {
        assertFailsWith<KdrException> { WfDef("bad id", WfEntry.creation, listOf(task("t"))) }
        assertFailsWith<KdrException> { WfDef("w", WfEntry.normal, emptyList()) }
        assertFailsWith<KdrException> { WfDef("w", WfEntry.normal, listOf(task("t"), task("t"))) }
        assertFailsWith<KdrException> { WfTask("t", "T", listOf(WfTraitRef("a"), WfTraitRef("a")), emptyList()) }
        assertFailsWith<KdrException> {
            WfTask("t", "T", listOf(WfTraitRef("a")), listOf(WfSave("s", "S", WfSaveKind.create), WfSave("s", "S", WfSaveKind.create)))
        }
        assertFailsWith<KdrException> {
            WfTask("t", "T", listOf(WfTraitRef("a")), emptyList(), WfLayout(listOf("a", "zzz")))
        }
    }

    @Test
    fun multiTaskModelDerivesStatusPerTask() {
        // Not a creation workflow -- the multi-task branch a one-task fixture never reaches.
        val income = task("income", "income")
        val assets = task("assets", "assets", "assetNotes" to false)
        val def = WfDef("survey", WfEntry.survey, listOf(income, assets))
        assertTrue(def.showTaskList)
        assertEquals(assets, def.task("assets"))
        assertNull(def.task("missing"))

        val entries = listOf(entry("income"), entry("assets"))
        assertTrue(WfEngine.taskComplete(income, entries))
        assertTrue(WfEngine.taskComplete(assets, entries)) // the optional trait is not required
        assertEquals(listOf("income"), WfEngine.missingTraits(income.requiredTraitIds, emptyList()))
        // Presence, not content: an empty map is a present entry; a null data is not.
        assertTrue(WfEngine.taskComplete(income, listOf(entry("income", emptyMap<String, Any?>()))))
        assertFalse(WfEngine.taskComplete(income, listOf(entry("income", null))))
    }

    @Test
    fun taskFactsReportCompletenessAndAlwaysAvailability() {
        val t = task("income", "income")
        assertEquals(setOf(WFC.taskAvailable), WfTaskFacts.of(t, emptyList()))
        assertEquals(setOf(WFC.taskAvailable, WFC.taskComplete), WfTaskFacts.of(t, listOf(entry("income"))))
    }

    @Test
    fun wfRefRoundTripsAndRefusesNonReferences() {
        val bundle = GedraId.of(GedraConfigType.configDoc, "acme", "acmeForms", "2")
        val ref = WfRef(bundle, "createForm")
        assertEquals("gc.cd.acme.acmeForms~2#createForm", ref.text)
        assertEquals(ref, WfRef.parse(ref.text))
        assertEquals(ref.hashCode(), WfRef.parse(ref.text).hashCode())
        assertFailsWith<KdrException> { WfRef.parse("gc.cd.acme.acmeForms~2") }
        assertFailsWith<KdrException> { WfRef.parse("#createForm") }
        assertFailsWith<KdrException> { WfRef.parse("gc.cd.acme.acmeForms#") }
        assertFailsWith<KdrException> { WfRef(bundle, "not a name") }
        assertNull(WfRef.parseOrNull(null))
        assertNull(WfRef.parseOrNull("nonsense"))
        assertEquals(ref, WfRef.parseOrNull(ref.text))
    }

    private fun task(id: String, vararg required: String): WfTask =
        WfTask(id, id, required.map { WfTraitRef(it) }, listOf(WfSave("save", "Save", WfSaveKind.create)))

    private fun task(id: String, required: String, optional: Pair<String, Boolean>): WfTask =
        WfTask(
            id, id,
            listOf(WfTraitRef(required), WfTraitRef(optional.first, optional.second)),
            listOf(WfSave("save", "Save", WfSaveKind.create)),
        )
}
