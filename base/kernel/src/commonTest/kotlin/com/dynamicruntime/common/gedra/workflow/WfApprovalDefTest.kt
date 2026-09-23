package com.dynamicruntime.common.gedra.workflow

import com.dynamicruntime.common.context.KdrCxtBase
import com.dynamicruntime.common.context.LiteCxt
import com.dynamicruntime.common.exception.KdrException
import com.dynamicruntime.common.uiblock.UIB
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

/**
 * An approval task as definition data (issue #787): declared by the builder, parsed, round-tripped, and refused
 * where it has no coherent reading -- traits or saves beside the approval, or an approval outside a normal
 * workflow. Recording and evaluating approvals is `base:common`'s.
 */
class WfApprovalDefTest {
    private val cxt: KdrCxtBase = LiteCxt()

    private fun normalWithApproval(extra: WfTaskBuilder.() -> Unit = {}) = WfDefBuilder("audit", WfEntry.normal).apply {
        task("record", "Record") { trait("a"); save("s", "S", WfSaveKind.edit) }
        task("approve", "Approve") {
            approval("auditApproved", "Approve the audit once you have read it.", "Approve")
            extra()
        }
    }.build()

    @Test
    fun anApprovalTaskParsesAndRoundTrips() {
        val def = parseWfDef(cxt, normalWithApproval())
        val approval = def.task("approve")?.approval
        assertEquals("auditApproved", approval?.cfact)
        assertEquals("Approve", approval?.button)
        // An ordinary task is not an approval task.
        assertNull(def.task("record")?.approval)

        val again = parseWfDef(cxt, def.toJsonMap()).task("approve")?.approval
        assertEquals(listOf(approval?.cfact, approval?.prompt, approval?.button), listOf(again?.cfact, again?.prompt, again?.button))
    }

    @Test
    fun anApprovalTaskCollectsNothingAndSavesNothing() {
        assertFailsWith<KdrException> { parseWfDef(cxt, normalWithApproval { trait("a") }) }
        assertFailsWith<KdrException> { parseWfDef(cxt, normalWithApproval { save("s", "S", WfSaveKind.edit) }) }
    }

    @Test
    fun onlyANormalWorkflowHasApprovalTasks() {
        val raw = WfDefBuilder("review", WfEntry.survey).apply {
            task("approve", "Approve") { approval("x", "P", "B") }
        }.build()
        assertFailsWith<KdrException> { parseWfDef(cxt, raw) }
    }

    @Test
    fun anApprovalNamesACfactAndCarriesItsCopy() {
        // A blank cfact would be an approval that emits nothing.
        assertFailsWith<KdrException> {
            parseWfDef(cxt, WfDefBuilder("audit", WfEntry.normal).apply {
                task("approve", "Approve") { approval(" ", "P", "B") }
            }.build())
        }
        // Stored config missing the button's copy is refused by the schema, not drawn as an unlabelled button.
        val raw = WfDefBuilder("audit", WfEntry.normal).apply {
            task("approve", "Approve") { approval("x", "P", "B") }
        }.build()
        val tasks = (raw[WFD.tasks] as List<*>).map { (it as Map<*, *>).entries.associate { e -> e.key.toString() to e.value } }
        val stripped = raw + (WFD.tasks to tasks.map { t ->
            t + (WFD.approval to (t[WFD.approval] as Map<*, *>).filterKeys { it != WFD.button })
        })
        assertFailsWith<KdrException> { parseWfDef(cxt, stripped) }
    }

    @Test
    fun anApprovalTasksFactsFollowItsApprovalNotItsTraits() {
        val task = parseWfDef(cxt, normalWithApproval()).task("approve")!!
        // No traits, so presence alone would call it complete; until approved it is not.
        assertEquals(setOf(WFC.taskAvailable, WFC.isCta), WfTaskFacts.of(task, emptyList(), isCta = true))
        // Approved: complete, and carrying its own approval cfact for a display selector to name (issue #788).
        assertEquals(setOf(WFC.taskAvailable, WFC.taskComplete, "auditApproved"), WfTaskFacts.of(task, emptyList(), approved = true))
    }

    @Test
    fun aTaskDisplayIsASelectorThatRoundTrips() {
        val def = parseWfDef(cxt, normalWithApproval {
            display {
                whenCfacts("auditApproved") { text("Approved.") }
                whenCfacts("~${WFC.isCta}") { text("Not yet."); disabled = true }
                whenCfacts(WFC.reviewer) { defaultRendering() }
                otherwise { text("Wait.") }
            }
        })
        val display = def.task("approve")?.display
        // The UiBlock selector shape, branches in declaration order, the last unguarded.
        val branches = display?.get(UIB.select) as List<*>
        assertEquals(4, branches.size)
        assertEquals(mapOf(UIB.cfactExpression to "~${WFC.isCta}", WDSP.mode to WDSP.textMode, WDSP.text to "Not yet.", WDSP.disabled to true), branches[1])
        assertEquals(mapOf(WDSP.mode to WDSP.textMode, WDSP.text to "Wait."), branches[3])
        assertEquals(display, parseWfDef(cxt, def.toJsonMap()).task("approve")?.display)
    }
}
