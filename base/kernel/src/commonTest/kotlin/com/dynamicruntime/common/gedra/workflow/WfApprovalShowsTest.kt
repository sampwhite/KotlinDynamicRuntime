package com.dynamicruntime.common.gedra.workflow

import com.dynamicruntime.common.context.KdrCxtBase
import com.dynamicruntime.common.context.LiteCxt
import com.dynamicruntime.common.exception.KdrException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse

/**
 * What an approval task shows, read-only, above its prompt (issue #1695): by default the nearest earlier task that collects
 * traits, else the tasks its approval names, or none; parsed and round-tripped, and refused when it names a task the
 * workflow does not have.
 */
class WfApprovalShowsTest {
    private val cxt: KdrCxtBase = LiteCxt()

    /** A review: a task collecting the review, a note collecting nothing, then the approval, [showTasks] as given. */
    private fun def(showTasks: List<String>? = null) = parseWfDef(
        cxt,
        WfDefBuilder("review", WfEntry.normal).apply {
            task("record", "Record the review") { trait("review"); save("s", "Save", WfSaveKind.edit) }
            task("summary", "Summarize it") { trait("summary"); save("t", "Save", WfSaveKind.edit) }
            task("accept", "Accept the talk") { approval("accepted", "Read it, then accept.", "Accept", showTasks = showTasks) }
        }.build(),
    )

    private fun shows(def: WfDef) = def.approvalShows(def.task("accept")!!)

    @Test
    fun byDefaultAnApprovalShowsTheNearestEarlierTaskThatCollectsTraits() {
        val plain = def()
        assertEquals(listOf("summary"), shows(plain))
        // A task that is no approval shows nothing.
        assertEquals(emptyList(), plain.approvalShows(plain.task("record")!!))
        // The default is not written, so a definition that never names one stores as it did.
        assertFalse(WFD.showTasks in plain.toJsonMap()[WFD.tasks].let { tasks ->
            (tasks as List<*>).filterIsInstance<Map<*, *>>().single { it[WFD.id] == "accept" }[WFD.approval] as Map<*, *>
        })
    }

    @Test
    fun anApprovalMayNameTheTasksItShowsOrNone() {
        val named = def(showTasks = listOf("record", "summary"))
        assertEquals(listOf("record", "summary"), shows(named))
        assertEquals(listOf("record", "summary"), shows(parseWfDef(cxt, named.toJsonMap())))
        // An empty list shows none, and round-trips as one rather than as the default.
        val none = def(showTasks = emptyList())
        assertEquals(emptyList(), shows(none))
        assertEquals(emptyList(), shows(parseWfDef(cxt, none.toJsonMap())))
    }

    @Test
    fun anApprovalNamesOnlyOtherTasksOfItsWorkflow() {
        assertFailsWith<KdrException> { def(showTasks = listOf("gone")) }
        assertFailsWith<KdrException> { def(showTasks = listOf("accept")) }
    }
}
