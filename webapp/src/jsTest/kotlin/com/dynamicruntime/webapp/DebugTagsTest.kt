package com.dynamicruntime.webapp

import com.dynamicruntime.common.app.APP
import com.dynamicruntime.common.app.DBGK
import com.dynamicruntime.common.app.DBGT
import kotlin.test.Test
import kotlin.test.assertEquals

/** The app bar's debug-tag picker (issue #1334): reading the app config's words, and appending a picked one. */
class DebugTagsTest {

    @Test
    fun appendsAWordAfterACommaAndSkipsOneAlreadyThere() {
        assertEquals("captureLogs", withDebugTag("", "captureLogs"))
        assertEquals("explainAccess,captureLogs", withDebugTag("explainAccess", "captureLogs"))
        // Already there: the words unchanged, rejoined as the backend reads them.
        assertEquals("explainAccess,captureLogs", withDebugTag(" explainAccess , captureLogs ", "captureLogs"))
        // A trailing comma or a blank between commas is not a word.
        assertEquals("logSql,captureLogs", withDebugTag("logSql,", "captureLogs"))
        assertEquals(listOf("a", "b"), debugTagWords("a,, b ,"))
    }

    @Test
    fun readsTheAppConfigsWordsAndDropsOneWithNoName() {
        val raw = listOf(
            mapOf(DBGT.name to "logSql", DBGT.description to "Logs each statement.", DBGT.kind to DBGK.log),
            mapOf(DBGT.name to "", DBGT.description to "No word."),
            mapOf(DBGT.name to "explainScope"),
        )
        assertEquals(
            listOf(
                DebugTagChoice("logSql", "Logs each statement.", DBGK.log),
                DebugTagChoice("explainScope", "", DBGK.explain),
            ),
            debugTagChoicesFrom(raw),
        )
        assertEquals(emptyList(), debugTagChoicesFrom(null))
        assertEquals(
            listOf("logSql"),
            appConfigFrom(UiConfig(FragmentRef("frag", "b1"), emptyMap(), emptyMap(), mapOf(APP.debugTags to raw.take(1))))
                .debugTags.map { it.name },
        )
    }

    @Test
    fun aKindSaysWhatTheWordDoesToTheRequest() {
        assertEquals("explains", debugTagKindLabel(DBGK.explain))
        assertEquals("adds logging", debugTagKindLabel(DBGK.log))
        assertEquals("changes the request", debugTagKindLabel(DBGK.alter))
    }
}
