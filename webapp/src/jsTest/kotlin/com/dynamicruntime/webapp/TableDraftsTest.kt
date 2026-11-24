package com.dynamicruntime.webapp

import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.promise
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Saving a form's grids with it (issue #1885): where a grid's new file goes, and how a page's grids are flushed. */
@OptIn(DelicateCoroutinesApi::class)
class TableDraftsTest {
    @Test
    fun aValueIsSetAtItsPathCopyingWhatIsOnTheWay() {
        val values = mapOf(
            "gedraId" to "gd.fd.c.1",
            "edits" to listOf(mapOf("traitId" to "claim", "data" to mapOf("note" to "trip")), mapOf("traitId" to "other")),
        )
        val set = withValueAt(values, "edits[0].data.lines", "gd.df.c.2")
        assertEquals(mapOf("note" to "trip", "lines" to "gd.df.c.2"), (set["edits"] as List<*>)[0].let { (it as Map<*, *>)["data"] })
        assertEquals(mapOf("traitId" to "other"), (set["edits"] as List<*>)[1])
        // The original is not changed.
        assertNull(((values["edits"] as List<*>)[0] as Map<*, *>)["data"].let { (it as Map<*, *>)["lines"] })
        // What is not there is made.
        assertEquals(mapOf("entries" to listOf(null, mapOf("data" to mapOf("lines" to "f")))), withValueAt(emptyMap(), "entries[1].data.lines", "f"))
        assertEquals(mapOf("lines" to "f"), withValueAt(emptyMap(), "lines", "f"))
    }

    @Test
    fun updatesApplyOnlyInTheirScope() {
        val updates = listOf(
            TableUpdate(TableDraftKey("claim", "lines"), "f1"),
            TableUpdate(TableDraftKey("other", "rows"), "f2"),
        )
        assertEquals(mapOf("note" to "x", "lines" to "f1"), applyTableUpdates(mapOf("note" to "x"), updates, "claim"))
        assertEquals(mapOf("rows" to "f2"), applyTableUpdates(emptyMap(), updates, "other"))
        assertEquals(emptyMap(), applyTableUpdates(emptyMap(), updates))
    }

    private fun draft(log: MutableList<String>, name: String, outcome: TableFlush, dirty: Boolean = true) = TableDraft(
        dirty = { dirty },
        flush = {
            log.add("flush $name")
            outcome
        },
        reset = { log.add("reset $name") },
    )

    @Test
    fun aFlushSavesEachGridInOrderAndStopsAtAProblem() = GlobalScope.promise {
        val log = mutableListOf<String>()
        var changes = 0
        val drafts = TableDrafts().apply { onChange = { changes++ } }
        drafts.register(TableDraftKey("", "a"), draft(log, "a", TableFlush.Saved("fa")))
        drafts.register(TableDraftKey("", "b"), draft(log, "b", TableFlush.Unchanged, dirty = false))
        drafts.register(TableDraftKey("", "c"), draft(log, "c", TableFlush.Refused("C: Row 1, Amount: bad")))
        drafts.register(TableDraftKey("", "d"), draft(log, "d", TableFlush.Saved("fd")))
        assertEquals(4, changes)
        val outcome = drafts.flushAll()
        assertEquals(listOf("flush a", "flush b", "flush c"), log)
        assertEquals(listOf(TableUpdate(TableDraftKey("", "a"), "fa")), outcome.updates)
        assertEquals("C: Row 1, Amount: bad", outcome.refusal)
        drafts.unregister(TableDraftKey("", "c"))
        log.clear()
        val again = drafts.flushAll()
        assertNull(again.refusal)
        assertEquals(listOf("fa", "fd"), again.updates.map { it.fileId })
    }

    @Test
    fun scopesLimitAFlushAResetAndTheUnsavedQuestion() = GlobalScope.promise {
        val log = mutableListOf<String>()
        val drafts = TableDrafts()
        drafts.register(TableDraftKey("claim", "lines"), draft(log, "claim", TableFlush.Saved("f1")))
        drafts.register(TableDraftKey("other", "rows"), draft(log, "other", TableFlush.Saved("f2"), dirty = false))
        assertEquals(listOf("f1"), drafts.flushAll(setOf("claim")).updates.map { it.fileId })
        assertTrue(drafts.anyDirty())
        assertTrue(drafts.anyDirty(setOf("claim")))
        assertFalse(drafts.anyDirty(setOf("other")))
        drafts.resetAll(setOf("other"))
        assertEquals(listOf("flush claim", "reset other"), log)
    }
}
