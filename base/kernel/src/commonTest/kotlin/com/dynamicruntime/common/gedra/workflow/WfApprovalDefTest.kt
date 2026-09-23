package com.dynamicruntime.common.gedra.workflow

import com.dynamicruntime.common.context.KdrCxtBase
import com.dynamicruntime.common.context.LiteCxt
import com.dynamicruntime.common.exception.KdrException
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
}
