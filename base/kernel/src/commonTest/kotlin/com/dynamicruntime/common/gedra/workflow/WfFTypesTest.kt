package com.dynamicruntime.common.gedra.workflow

import com.dynamicruntime.common.context.LiteCxt
import com.dynamicruntime.common.exception.KdrException
import com.dynamicruntime.common.gedra.FTypeDef
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue

/** What a task that collects its form's type's traits collects (issue #1903). */
class WfFTypesTest {
    private val cxt = LiteCxt()

    private fun type(id: String, vararg traits: WfTraitRef) = FTypeDef(id, id, traits = traits.toList())

    private val lead = type("lead", WfTraitRef("ask"), WfTraitRef("contact"))
    private val video =
        type("video", WfTraitRef("contact", required = false), WfTraitRef("clip"), WfTraitRef("notes"), WfTraitRef("summary"))
    private val memo = type("memo")

    /** Two tasks: one listing two traits and collecting the type's, one listing a trait of its own. */
    private fun details(entry: WfEntry = WfEntry.coreDetails, secondToo: Boolean = false) = WfDefBuilder("details", entry).apply {
        comment = "Kept."
        task("basics", "Basics") {
            trait("ftype")
            trait("summary", required = false)
            ftypeTraits = true
            save("save", "Save", WfSaveKind.edit)
            layout(listOf("summary"))
        }
        task("notes", "Notes") {
            trait("notes", required = false)
            ftypeTraits = secondToo
            save("saveNotes", "Save", WfSaveKind.edit)
        }
    }.build()

    private fun WfDef.collected(type: FTypeDef?) = forFtype(type).tasks.map { t -> t.traits.map { it.traitId to it.required } }

    @Test
    fun aTypesTraitsFollowTheTasksOwnWithoutThoseAnotherTaskLists() {
        val def = parseWfDef(cxt, details())
        val notes = listOf("notes" to false)
        assertEquals(listOf(listOf("ftype" to true, "summary" to false), notes), def.collected(null))
        assertEquals(listOf(listOf("ftype" to true, "summary" to false), notes), def.collected(memo))
        assertEquals(listOf(listOf("ftype" to true, "summary" to false, "ask" to true, "contact" to true), notes), def.collected(lead))
        // The other task keeps its own trait, as it says; the one this task lists is required when the type says so.
        assertEquals(listOf(listOf("ftype" to true, "summary" to true, "contact" to false, "clip" to true), notes), def.collected(video))
        assertEquals(listOf("ftype", "summary", "clip"), def.forFtype(video).tasks.first().requiredTraitIds)
        // The layout's order still leads, and everything else follows.
        assertEquals(listOf("summary", "ftype", "ask", "contact"), def.forFtype(lead).tasks.first().displayOrder)
    }

    @Test
    fun whatNothingChangesIsTheDefinitionItself() {
        val def = parseWfDef(cxt, details())
        assertSame(def, def.forFtype(null))
        val plain = parseWfDef(cxt, WfDefBuilder("plain", WfEntry.creation).apply { task("t", "T") { trait("a"); save("s", "S") } }.build())
        assertFalse(plain.collectsFtype)
        assertSame(plain, plain.forFtype(lead))
        assertEquals(listOf("a"), plain.traitIdsOverFtypes(listOf(lead, video)))
    }

    @Test
    fun theResolvedDefinitionIsOtherwiseTheSame() {
        val def = parseWfDef(cxt, details())
        val resolved = def.forFtype(memo)
        assertEquals(def.toJsonMap(), resolved.toJsonMap())
        assertEquals("Kept.", resolved.comment)
        // What was resolved onto it and its tasks comes along.
        val fn = object : WfFunction {
            override val fn = "x"
            override val event = WfEventType.prefillData
            override val priority = 1
        }
        def.resolvedFunctions = listOf(fn)
        def.tasks.first().resolvedFunctions = listOf(fn)
        assertEquals(listOf<WfFunction>(fn), def.forFtype(lead).resolvedFunctions)
        assertEquals(listOf<WfFunction>(fn), def.forFtype(lead).tasks.first().resolvedFunctions)
    }

    @Test
    fun withoutAFormItIsEveryTypesTraits() {
        val def = parseWfDef(cxt, details())
        val types = listOf(lead, video, memo)
        assertEquals(listOf("ftype", "summary", "ask", "contact", "clip"), def.traitIdsOverFtypes(def.tasks.first(), types))
        assertEquals(listOf("ftype", "summary", "ask", "contact", "clip", "notes"), def.traitIdsOverFtypes(types))
    }

    @Test
    fun theKeyRoundTripsAndIsRefusedWhereItDoesNotBelong() {
        val def = parseWfDef(cxt, details())
        assertEquals(true, def.tasks.first().ftypeTraits)
        assertEquals(def.toJsonMap(), parseWfDef(cxt, def.toJsonMap()).toJsonMap())
        fun refusal(raw: Map<String, Any?>) = assertFailsWith<KdrException> { parseWfDef(cxt, raw) }.message.orEmpty()
        assertTrue(refusal(details(WfEntry.normal)).contains("A normal workflow lists its traits"))
        assertTrue(refusal(details(secondToo = true)).contains("at most one does"))
    }

    @Test
    fun aWorkflowMadeFromATemplateTurnsItOnAndOff() {
        fun template(on: Boolean) = parseWfDef(
            cxt,
            WfDefBuilder("kdr:details", WfEntry.coreDetails).apply {
                label = "Details"
                task("basics", "Basics") { trait("name"); ftypeTraits = on; save("save", "Save", WfSaveKind.edit) }
            }.build(),
        )
        for (was in listOf(false, true)) {
            val base = template(was)
            fun slots(traits: List<WfTraitRef>, ftypeTraits: Boolean?) =
                WfTemplateSlots(null, base.workflowId, "Mine", listOf(WfTaskSlot("basics", "Basics", traits, ftypeTraits)))
            val name = listOf(WfTraitRef("name"))
            for ((asked, now) in listOf(true to true, false to false, null to was)) {
                val made = withTemplateSlots(cxt, base, slots(name, asked), "mine")
                assertEquals(now, made.tasks.single().ftypeTraits, "$was then $asked")
                // Stored as an overlay, and read back the same.
                val overlay = workflowTemplateOverlay(base, made)
                assertEquals(now, expandWorkflowTemplate(cxt, base, overlay).tasks.single().ftypeTraits, "$was then $asked, stored")
            }
            // One that collects its form's type's traits may list none of its own.
            assertEquals(emptyList(), templateSlotProblems(base, slots(emptyList(), true)))
            assertEquals(1, templateSlotProblems(base, slots(emptyList(), false)).size)
            val made = withTemplateSlots(cxt, base, slots(emptyList(), true), "mine")
            assertEquals(0, expandWorkflowTemplate(cxt, base, workflowTemplateOverlay(base, made)).tasks.single().traits.size)
        }
        // The wire says it only where it is said.
        val said = WfTemplateSlots(null, "kdr:details", "Mine", listOf(WfTaskSlot("basics", "B", listOf(WfTraitRef("name")), true)))
        assertEquals(true, templateSlotsFromInfo(said.toInfo()).tasks.single().ftypeTraits)
        val unsaid = WfTemplateSlots(null, "kdr:details", "Mine", listOf(WfTaskSlot("basics", "B", listOf(WfTraitRef("name")))))
        assertEquals(null, templateSlotsFromInfo(unsaid.toInfo()).tasks.single().ftypeTraits)
    }
}
