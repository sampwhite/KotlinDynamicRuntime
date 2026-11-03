package com.dynamicruntime.webapp

import com.dynamicruntime.common.assistant.ABLK
import com.dynamicruntime.common.assistant.ACNV
import com.dynamicruntime.common.assistant.ATOOL
import com.dynamicruntime.common.gedra.CCT
import com.dynamicruntime.common.gedra.CFEP
import com.dynamicruntime.common.gedra.CLD
import com.dynamicruntime.common.gedra.GDF
import com.dynamicruntime.common.gedra.GE
import com.dynamicruntime.common.gedra.GED
import com.dynamicruntime.common.gedra.GedraEditAction
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The workflow assistant page's pure logic (issue #1527): a conversation's messages read into the transcript, each line of
 * tool calls labeled, the drafts a conversation made, how a turn's end is told, when the page polls, and the allowance.
 */
class AssistantTranscriptTest {
    private fun text(t: String) = mapOf(ABLK.type to ABLK.text, ABLK.text to t)
    private fun thinking(t: String) = mapOf(ABLK.type to ABLK.thinking, ABLK.thinking to t)
    private fun use(id: String, name: String, input: Map<String, Any?> = emptyMap()) =
        mapOf(ABLK.type to ABLK.toolUse, ABLK.id to id, ABLK.name to name, ABLK.input to input)
    private fun result(id: String, content: String, isError: Boolean = false) = buildMap {
        put(ABLK.type, ABLK.toolResult)
        put(ABLK.toolUseId, id)
        put(ABLK.content, content)
        if (isError) put(ABLK.isError, true)
    }

    private fun person(seq: Long, vararg blocks: Map<String, Any?>) = StoredMessage(seq, ABLK.user, blocks.toList())
    private fun model(seq: Long, vararg blocks: Map<String, Any?>) = StoredMessage(seq, ABLK.assistant, blocks.toList())

    private fun patch(name: String, dryRun: Boolean, workflowId: String? = "rsvp", action: GedraEditAction = GedraEditAction.addOrReplace) =
        mapOf(
            CFEP.configs to listOf(
                mapOf(
                    CFEP.name to name,
                    CFEP.edits to listOf(
                        mapOf(CFEP.slot to CCT.workflowDef, GED.action to action.name, GE.data to mapOf(CCT.workflowId to workflowId)),
                    ),
                ),
            ),
            CFEP.dryRun to dryRun,
        )

    private val conversation = listOf(
        person(1, text("Add an RSVP workflow.")),
        model(
            2, thinking("Looking at what exists."), use("a", ATOOL.listWorkflows), use("b", ATOOL.readConfig, mapOf(CFEP.name to "flows")),
        ),
        person(3, result("a", "{}"), result("b", "{}")),
        model(4, use("c", ATOOL.patchConfig, patch("flows", dryRun = true))),
        person(5, result("c", "{\"impact\":{}}")),
        model(6, use("d", ATOOL.patchConfig, patch("flows", dryRun = false))),
        person(7, result("d", "{\"configs\":[{\"name\":\"flows\",\"version\":3}]}")),
        model(8, text("I drafted **rsvp**."), thinking("")),
    )

    @Test
    fun theTranscriptJoinsEachResultToItsCallAndGroupsAnAnswersCalls() {
        val items = transcriptItems(conversation)
        assertEquals(6, items.size)
        assertIs<TranscriptItem.Said>(items[0]).let {
            assertTrue(it.fromPerson)
            assertEquals("Add an RSVP workflow.", it.text)
        }
        assertEquals("Looking at what exists.", assertIs<TranscriptItem.Note>(items[1]).text)
        assertIs<TranscriptItem.Tools>(items[2]).let {
            assertEquals(listOf("a", "b"), it.calls.map { c -> c.id })
            assertEquals("{}", it.calls[1].result)
            assertEquals("Looked at: workflows, bundle `flows`", it.label)
        }
        assertEquals("Checked a change to `flows`", assertIs<TranscriptItem.Tools>(items[3]).label)
        assertEquals("Drafted `flows` (v3)", assertIs<TranscriptItem.Tools>(items[4]).label)
        // The model's text is its own; an unsummarized note shows nothing; tool results are never said by the person.
        assertIs<TranscriptItem.Said>(items[5]).let { assertFalse(it.fromPerson) }
    }

    @Test
    fun aLabelSaysWhatIsStillRunningAndWhatWasRefused() {
        val running = transcriptItems(listOf(person(1, text("Go.")), model(2, use("a", ATOOL.listTraits), use("b", ATOOL.readCfacts))))
        assertEquals("Looked at: traits…, cfacts…", assertIs<TranscriptItem.Tools>(running[1]).label)
        val refused = transcriptItems(
            listOf(
                person(1, text("Go.")),
                model(2, use("a", ATOOL.patchConfig, patch("flows", dryRun = false)), use("b", ATOOL.viewWorkflow)),
                person(3, result("a", "Not written: dry run first.", isError = true), result("b", "{}")),
            ),
        )
        assertEquals("Looked at: the creation workflow; Not drafted: `flows`", assertIs<TranscriptItem.Tools>(refused[1]).label)
        assertEquals(
            "workflow `rsvp`",
            lookedAt(ToolCall("x", ATOOL.viewWorkflow, mapOf(GDF.workflowId to "rsvp"), "{}", isError = false)),
        )
        assertEquals("Drafted the included traits", toolsLabel(listOf(ToolCall("t", ATOOL.setIncludedTraits, emptyMap(), "{}", false))))
    }

    @Test
    fun aDraftIsAWriteThatSucceeded() {
        val drafts = draftsOf(conversation)
        assertEquals(1, drafts.size)
        assertEquals("flows", drafts[0].bundle)
        assertEquals(3L, drafts[0].version)
        assertEquals(listOf("rsvp"), drafts[0].workflowIds)
        assertEquals("`flows` (v3)", drafts[0].title)

        // A dry run, a refused write, and a deleted workflow make no draft of a workflow; the traits make their own.
        val more = listOf(
            person(1, text("Go.")),
            model(2, use("a", ATOOL.patchConfig, patch("other", dryRun = true))),
            person(3, result("a", "{}")),
            model(4, use("b", ATOOL.patchConfig, patch("refused", dryRun = false))),
            person(5, result("b", "No.", isError = true)),
            model(6, use("c", ATOOL.patchConfig, patch("gone", dryRun = false, action = GedraEditAction.deleteOrNoOp))),
            // A long answer is cut, and is then not JSON: the draft has no version.
            person(7, result("c", "{\"configs\":[ [Cut: 30000 of 41000 characters shown.]")),
            model(8, use("d", ATOOL.setIncludedTraits, mapOf(CLD.includedTraits to listOf("memo")))),
            person(9, result("d", "{}")),
        )
        val made = draftsOf(more)
        assertEquals(listOf("`gone`", "The included traits"), made.map { it.title })
        assertEquals(emptyList(), made[0].workflowIds)
        assertNull(made[0].version)
    }

    @Test
    fun aTurnsEndIsToldUnlessItRanToItsEnd() {
        assertNull(turnEndText(null))
        assertNull(turnEndText(TurnView("t", "Hi", ACNV.statusDone, null)))
        assertNull(turnEndText(TurnView("t", "Hi", ACNV.statusRunning, null)))
        assertEquals("Stopped, as you asked.", turnEndText(TurnView("t", "Hi", ACNV.statusCanceled, null)))
        assertEquals("Out of steps.", turnEndText(TurnView("t", "Hi", ACNV.statusStopped, "Out of steps.")))
        assertEquals("The assistant stopped before it finished.", turnEndText(TurnView("t", "Hi", ACNV.statusFailed, null)))
    }

    @Test
    fun thePagePollsWhileATurnRunsAndUntilASentOneHasEnded() {
        assertTrue(pollsConversation(running = true, pendingTurnId = null, turns = emptyList()))
        assertFalse(pollsConversation(running = false, pendingTurnId = null, turns = emptyList()))
        // Sent, and not yet seen to begin: polled.
        assertTrue(pollsConversation(running = false, pendingTurnId = "t2", turns = listOf(TurnView("t1", "", ACNV.statusDone, null))))
        assertTrue(pollsConversation(running = false, pendingTurnId = "t2", turns = listOf(TurnView("t2", "", ACNV.statusRunning, null))))
        assertFalse(pollsConversation(running = false, pendingTurnId = "t2", turns = listOf(TurnView("t2", "", ACNV.statusDone, null))))
    }

    @Test
    fun theAllowanceSaysTheShareUsedOrThatItIsUsedUp() {
        val part = AllowanceView(0.123, spent = false, resetsAt = "2026-12-01T00:00:00Z")
        assertEquals(12, allowancePercent(part))
        assertEquals("12% of this month's allowance is used. It starts again on December 1.", allowanceText(part))
        val spent = AllowanceView(1.7, spent = true, resetsAt = "2027-01-01T00:00:00.000Z")
        assertEquals(100, allowancePercent(spent))
        assertTrue(allowanceText(spent).contains("used up"))
        assertTrue(allowanceText(spent).contains("January 1"))
        assertTrue(allowanceText(spent).contains("An operator can raise it."))
    }

    @Test
    fun aMessageSentAgainKeepsItsIdAndAnythingElseGetsANewOne() {
        assertEquals("m1", messageIdFor("m1" to "Hello.", "Hello.") { "m2" })
        assertEquals("m2", messageIdFor("m1" to "Hello.", "Hello there.") { "m2" })
        assertEquals("m2", messageIdFor(null, "Hello.") { "m2" })
    }

    @Test
    fun backtickedNamesAreCodeAndAStoredStringIsOneTextBlock() {
        assertEquals(listOf("Drafted " to false, "rsvp" to true, " (v3)" to false), codeSpans("Drafted `rsvp` (v3)"))
        assertEquals(listOf(mapOf(ABLK.type to ABLK.text, ABLK.text to "Hi")), blocksOf("Hi"))
        assertEquals("$12.30", dollarsText(12.3))
        assertEquals("$0.05", dollarsText(0.049))
    }
}
