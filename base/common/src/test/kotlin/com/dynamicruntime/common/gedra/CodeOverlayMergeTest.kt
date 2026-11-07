package com.dynamicruntime.common.gedra

import com.dynamicruntime.common.gedra.workflow.WFD
import com.dynamicruntime.common.overlay.OverlayMergeError
import com.dynamicruntime.common.schema.SCH
import com.dynamicruntime.common.schema.SL
import com.dynamicruntime.common.util.toJsonListOfMaps
import com.dynamicruntime.common.util.toJsonMapOrEmpty
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain

/**
 * The merge rules of an overlay of the code (issue #1657): a workflow's tasks and saves targeted by id in the code's
 * order, its tests, rules, and locks added to; its id and kind the code's; a type's properties merged, so the code's
 * additions reach it; and what an overlay restates of a list with no keys, said.
 */
class CodeOverlayMergeTest : StringSpec({
    fun task(id: String, label: String) = mapOf(
        WFD.id to id, WFD.label to label, WFD.traits to listOf(mapOf(WFD.traitId to "note", WFD.required to true)),
        WFD.saves to listOf(mapOf(WFD.id to "s", WFD.label to "Save", WFD.kind to "edit")),
    )
    val code = mapOf(
        WFD.workflowId to "review", WFD.entry to "normal", WFD.label to "Review",
        WFD.tasks to listOf(task("a", "Check"), task("b", "Sign")),
        WFD.eligibility to listOf(mapOf(WFD.id to "open", WFD.test to "true", WFD.explanation to "Always.")),
    )

    "a workflow's diff states only what changed, and merges back to the edit" {
        val edited = code + mapOf(
            WFD.label to "Review, revised",
            WFD.tasks to listOf(task("a", "Check twice"), task("b", "Sign")),
            WFD.eligibility to listOf(
                mapOf(WFD.id to "open", WFD.test to "true", WFD.explanation to "Always."),
                mapOf(WFD.id to "late", WFD.test to "false", WFD.explanation to "Never."),
            ),
        )
        val diff = diffCodeOverlay(CCT.workflowDef, code, edited)
        diff.problems shouldBe emptyList()
        diff.overlay shouldBe mapOf(
            WFD.workflowId to "review",
            WFD.label to "Review, revised",
            WFD.tasks to listOf(mapOf(WFD.id to "a", WFD.label to "Check twice")),
            WFD.eligibility to listOf(mapOf(WFD.id to "late", WFD.test to "false", WFD.explanation to "Never.")),
        )
        mergeCodeOverlay(CCT.workflowDef, code, diff.overlay).value shouldBe edited
    }

    "a task removed, inserted, or moved, or a workflow's kind changed, cannot be stated" {
        fun problems(edited: Map<String, Any?>) = diffCodeOverlay(CCT.workflowDef, code, edited).problems
        problems(code + (WFD.tasks to listOf(task("a", "Check")))).single().message shouldContain "'b' is gone"
        problems(code + (WFD.tasks to listOf(task("b", "Sign"), task("a", "Check")))).single().code shouldBe
            OverlayMergeError.cannotStateChange
        problems(code + (WFD.tasks to listOf(task("a", "Check"), task("b", "Sign"), task("c", "File")))).single().message shouldContain
            "detach the entry from the code"
        problems(code + (WFD.entry to "creation")).single().code shouldBe OverlayMergeError.cannotStateChange
    }

    "an overlay naming a task the code no longer has merges the rest, and says what it dropped" {
        val overlay = mapOf(
            WFD.workflowId to "review", WFD.label to "Mine",
            WFD.tasks to listOf(mapOf(WFD.id to "gone", WFD.label to "Orphan"), mapOf(WFD.id to "b", WFD.label to "Sign now")),
        )
        val merged = mergeCodeOverlay(CCT.workflowDef, code, overlay)
        merged.value[WFD.label] shouldBe "Mine"
        merged.value[WFD.tasks].toJsonListOfMaps().map { it[WFD.label] } shouldContainExactly listOf("Check", "Sign now")
        overlayProblemTexts(merged.problems).single() shouldContain "tasks[gone]: 'gone' matches no entry here"
    }

    "a type's properties merge, so a property the code adds later reaches the overlay" {
        val type = mapOf(
            SCH.type to "object",
            SCH.properties to mapOf("code" to mapOf(SCH.description to "A code."), "size" to mapOf(SCH.description to "A size.")),
            SCH.layout to mapOf(SL.schemaFields to listOf(mapOf(SL.field to "code"), mapOf(SL.field to "size"))),
        )
        val edited = type + (SCH.properties to mapOf("code" to mapOf(SCH.description to "A code, uppercase.")))
        val diff = diffCodeOverlay(CCT.schemaDef, type, edited)
        diff.problems shouldBe emptyList()
        diff.overlay shouldBe mapOf(SCH.properties to mapOf("code" to mapOf(SCH.description to "A code, uppercase."), "size" to null))

        // The code gains a property: the overlay's change and the addition both stand, and the layout keeps no entry
        // for the property the overlay removed.
        val later = type + (SCH.properties to type[SCH.properties].toJsonMapOrEmpty() + ("color" to mapOf(SCH.description to "A color.")))
        val merged = mergeCodeOverlay(CCT.schemaDef, later, diff.overlay).value
        merged[SCH.properties].toJsonMapOrEmpty().keys.toList() shouldContainExactly listOf("code", "color")
        merged[SCH.layout].toJsonMapOrEmpty()[SL.schemaFields].toJsonListOfMaps().map { it[SL.field] } shouldContainExactly listOf("code")
    }

    "what an overlay restates of a list with no keys is said, by path" {
        val overlay = mapOf(
            WFD.tasks to listOf(mapOf(WFD.id to "a", WFD.functions to listOf(mapOf("fn" to "kdr:stamp")))),
            WFD.functions to emptyList<Any?>(),
        )
        restatedCodeLists(CCT.workflowDef, overlay) shouldContainExactly listOf("tasks[a].functions", WFD.functions)
        restatedCodeLists(CCT.schemaDef, mapOf(SCH.required to listOf("code"))) shouldContainExactly listOf(SCH.required)
        restatedCodeLists(CCT.cfactDef, mapOf("x" to listOf(1))) shouldBe emptyList()
    }
})
