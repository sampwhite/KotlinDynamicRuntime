package com.dynamicruntime.webapp

import com.dynamicruntime.common.assistant.ABLK
import com.dynamicruntime.common.assistant.ACNV
import com.dynamicruntime.common.assistant.ASST
import com.dynamicruntime.common.assistant.ATOOL
import com.dynamicruntime.common.endpoint.EP
import com.dynamicruntime.common.gedra.CCT
import com.dynamicruntime.common.gedra.CFEP
import com.dynamicruntime.common.gedra.COV
import com.dynamicruntime.common.gedra.CPY
import com.dynamicruntime.common.gedra.CUS
import com.dynamicruntime.common.gedra.CLD
import com.dynamicruntime.common.gedra.CIT
import com.dynamicruntime.common.gedra.report.RDEF
import com.dynamicruntime.common.gedra.workflow.WFD
import com.dynamicruntime.common.gedra.DSV
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
@Suppress("BooleanLiteralArgument")
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
        assertFalse(assertIs<TranscriptItem.Said>(items[5]).fromPerson)
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
        // The reads of the client (issue #1762) that name what they read.
        fun read(name: String, input: Map<String, Any?> = emptyMap()) = lookedAt(ToolCall("r", name, input, "{}", isError = false))
        assertEquals("the copy files", read(ATOOL.readCopy))
        assertEquals("copy `home`", read(ATOOL.readCopy, mapOf(ATOOL.file to "home")))
        assertEquals("copy `home.fieldBounds`", read(ATOOL.readCopy, mapOf(ATOOL.file to "home", ATOOL.namespace to "fieldBounds")))
        assertEquals("where `abstract` comes from", read(ATOOL.readDefinition, mapOf(DSV.slot to "kdr:traitDef", DSV.key to "abstract")))
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
    fun aWordingOrMenuWriteIsLabeledByWhatItChangedAndListsTheBundleItLandedIn() {
        // Issue #1763: the copy and menu editors' saves, as tools.
        val title = mapOf(COV.fileId to "home", COV.namespaceField to "home", COV.key to "title")
        fun call(name: String, input: Map<String, Any?>, result: String = "{\"${COV.configName}\":\"copy\"}", isError: Boolean = false) =
            ToolCall("w", name, input, result, isError)
        assertEquals("Drafted copy `home: home.title`", toolsLabel(listOf(call(ATOOL.setCopy, title))))
        assertEquals("Drafted the platform's wording for copy `home: home.title`", toolsLabel(listOf(call(ATOOL.resetCopy, title))))
        assertEquals(
            "Drafted copy in `home` (2 keys)",
            toolsLabel(listOf(call(ATOOL.applyCopy, mapOf(COV.fileId to "home", CPY.changes to listOf(emptyMap<String, Any?>(), emptyMap()))))),
        )
        assertEquals("Drafted menu item `docs`", toolsLabel(listOf(call(ATOOL.setMenuItem, mapOf(COV.itemId to "docs")))))
        assertEquals("Drafted the shipped menu item `docs`", toolsLabel(listOf(call(ATOOL.resetMenuItem, mapOf(COV.itemId to "docs")))))
        assertEquals("Not drafted: menu item `docs`", toolsLabel(listOf(call(ATOOL.setMenuItem, mapOf(COV.itemId to "docs"), "No.", true))))

        // The bundle each save landed in is a draft, once however often it is written; a refused one is none.
        val written = listOf(
            person(1, text("Reword the title, and hide Documents.")),
            model(2, use("a", ATOOL.setCopy, title + (COV.value to "Hi")), use("b", ATOOL.setMenuItem, mapOf(COV.itemId to "docs"))),
            person(3, result("a", "{\"${COV.configName}\":\"copy\"}"), result("b", "{\"${COV.configName}\":\"copy\"}")),
            model(4, use("c", ATOOL.resetCopy, title)),
            person(5, result("c", "Nothing to put back.", isError = true)),
        )
        assertEquals(listOf("`copy`"), draftsOf(written).map { it.title })
    }

    @Test
    fun aDefinitionOrFormsListWriteIsCheckedThenDrafted() {
        // Issue #1764: a dry run is a check, not a draft; the save after it is.
        val column = mapOf(CUS.column to mapOf(CCT.traitId to "memo", CCT.label to "Memo"))
        fun call(name: String, input: Map<String, Any?>) = ToolCall("w", name, input, "{\"${COV.configName}\":\"main\"}", false)
        assertEquals(
            "Checked a change to the client's definition",
            toolsLabel(listOf(call(ATOOL.setClientDefinition, mapOf(CFEP.dryRun to true)))),
        )
        assertEquals("Drafted the client's definition", toolsLabel(listOf(call(ATOOL.setClientDefinition, mapOf(CFEP.dryRun to false)))))
        assertEquals("Drafted forms list column `Memo`", toolsLabel(listOf(call(ATOOL.saveFormsListColumn, column))))
        assertEquals(
            "Checked a change to the removal of forms list column `Memo`",
            toolsLabel(listOf(call(ATOOL.removeFormsListColumn, mapOf(CCT.label to "Memo", CFEP.dryRun to true)))),
        )
        assertEquals("Drafted the forms list's layout", toolsLabel(listOf(call(ATOOL.setFormsListLayout, emptyMap()))))
        val checkedOnly = listOf(
            person(1, text("Rename it.")),
            model(2, use("a", ATOOL.setClientDefinition, mapOf(CFEP.dryRun to true))),
            person(3, result("a", "{\"${COV.configName}\":\"main\",\"${CFEP.dryRun}\":true}")),
        )
        assertEquals(emptyList(), draftsOf(checkedOnly))
        val saved = checkedOnly + listOf(
            model(4, use("b", ATOOL.setClientDefinition, mapOf(CFEP.dryRun to false))),
            person(5, result("b", "{\"${COV.configName}\":\"main\"}")),
        )
        assertEquals(listOf("`main`"), draftsOf(saved).map { it.title })
    }

    @Test
    fun aTemplateWorkflowTraitOrReportWriteIsLabeledAndOnlyADraftIsListed() {
        // Issue #1766: a workflow's parameters are the sandbox's own, no draft, so they name no bundle and list none.
        fun call(name: String, input: Map<String, Any?>, result: String = "{\"${COV.configName}\":\"reports\"}") =
            ToolCall("c", name, input, result, false)
        assertEquals("Drafted report `Memos`", toolsLabel(listOf(call(ATOOL.createReport, mapOf(RDEF.label to "Memos")))))
        assertEquals(
            "Checked a change to trait `Safety`",
            toolsLabel(listOf(call(ATOOL.designTrait, mapOf(CIT.design to mapOf(CIT.label to "Safety"), CFEP.dryRun to true)))),
        )
        val params = call(ATOOL.setWorkflowParams, mapOf(WFD.workflowId to "review"), result = "{\"client\":\"x:sandbox\"}")
        assertEquals("Set the sandbox's parameters of `review`", toolsLabel(listOf(params)))
        val made = listOf(
            person(1, text("Add a report, and turn review off here.")),
            model(
                2, use("a", ATOOL.createReport, mapOf(RDEF.label to "Memos")),
                use("b", ATOOL.setWorkflowParams, mapOf(WFD.workflowId to "review")),
            ),
            person(3, result("a", "{\"${COV.configName}\":\"reports\"}"), result("b", "{\"client\":\"x:sandbox\"}")),
        )
        assertEquals(listOf("`reports`"), draftsOf(made).map { it.title })
    }

    @Test
    fun aDesignViewEditIsLabeledByWhatItChanges() {
        // Issue #1765: by the workflow or field it changes; a dry run of the fields a form shows is a check.
        fun call(name: String, input: Map<String, Any?>) = ToolCall("d", name, input, "{\"${COV.configName}\":\"flows\"}", false)
        assertEquals("Drafted a label in `review`", toolsLabel(listOf(call(ATOOL.setWorkflowLabel, mapOf(DSV.workflowId to "review")))))
        assertEquals(
            "Drafted the copy of `text` in `review`",
            toolsLabel(listOf(call(ATOOL.setFieldCopy, mapOf(DSV.workflowId to "review", DSV.field to "text")))),
        )
        assertEquals(
            "Checked a change to the fields `review` shows",
            toolsLabel(listOf(call(ATOOL.setShownFields, mapOf(DSV.workflowId to "review", CFEP.dryRun to true)))),
        )
        assertEquals("Drafted the shared copy of `text`", toolsLabel(listOf(call(ATOOL.setSharedField, mapOf(DSV.field to "text")))))
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
    fun thePagePollsLaterAfterEachFailedPoll() {
        assertEquals(listOf(1_500, 3_000, 6_000, 12_000, 12_000), (0..4).map { pollDelayMs(it) })
    }

    @Test
    fun theAllowanceSaysTheShareUsedOrThatItIsUsedUpAndWhenItStartsAgainInTheClientsZone() {
        val part = AllowanceView(0.123, spent = false, resetsAt = "2026-12-01T00:00:00.000Z")
        assertEquals(12, allowancePercent(part))
        assertEquals("12% of this month's allowance is used. It starts again Dec 1, 2026, 12:00 AM GMT.", allowanceText(part, "UTC"))
        // West of UTC the month's use starts again on the last afternoon of the month before.
        val spent = AllowanceView(1.7, spent = true, resetsAt = "2027-01-01T00:00:00.000Z")
        assertEquals(100, allowancePercent(spent))
        assertEquals(
            "This month's allowance is used up, so the assistant cannot be sent anything until it starts again, " +
                "Dec 31, 2026, 6:00 PM CT. An operator can raise it.",
            allowanceText(spent, "America/Chicago"),
        )
        assertEquals(0, sharePercent(Double.NaN))
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
        assertEquals("$1,500.00", dollarsText(1500.0))
    }

    @Test
    fun aBudgetTypedInDollarsIsReadAsAnAmountOrNotAtAll() {
        assertEquals(25.0, budgetDollarsOf("25"))
        assertEquals(25.0, budgetDollarsOf(" $25 "))
        assertEquals(1000.5, budgetDollarsOf("1,000.50"))
        for (notAnAmount in listOf("", "NaN", "0x10", "-5", "1e3", "Infinity", "$", "12.")) {
            assertNull(budgetDollarsOf(notAnAmount), notAnAmount)
        }
    }

    @Test
    fun aPersonIsOfferedSomewhereToStart() {
        // What a new form asks for is offered only where it is the assistant's to change.
        assertTrue(assistantExamples(definedInCode = false).any { it.contains("new form") })
        assertTrue(assistantExamples(definedInCode = true).none { it.contains("new form") })
        val none = emptyList<ConversationSummary>()
        assertTrue(showsExamples(null, none, "", null))
        // Not once they have a conversation, have one open, have begun typing, or cannot send.
        assertTrue(!showsExamples(null, listOf(ConversationSummary("c1", "Hi", false, false, null)), "", null))
        assertTrue(!showsExamples("c1", none, "", null))
        assertTrue(!showsExamples(null, none, "Add", null))
        assertTrue(!showsExamples(null, none, "", "This month's allowance is used up."))
        // Nor before the list has been read.
        assertTrue(!showsExamples(null, null, "", null))
    }

    @Test
    fun aTabComesBackToTheConversationItHadOpenWhileItIsStillTheirs() {
        val listed = listOf(ConversationSummary("c1", "Hi", false, false, null), ConversationSummary("c2", "Later", false, true, null))
        assertEquals("c1", restoredConversation("c1", listed))
        // A read-only one is still theirs to read.
        assertEquals("c2", restoredConversation("c2", listed))
        // Nothing remembered, a new conversation remembered, or one no longer in the list: a new conversation.
        assertNull(restoredConversation(null, listed))
        assertNull(restoredConversation("", listed))
        assertNull(restoredConversation("gone", listed))
        // Each client keeps its own.
        assertTrue(assistantConversationKey("free_shop1") != assistantConversationKey("free_shop2"))
    }

    @Test
    fun thePageShowsTheFaceItsStatusCallsFor() {
        fun status(on: Boolean = false, offered: Boolean = false, inSandbox: Boolean = false) = parseAssistantStatus(
            mapOf(ASST.client to "free_shop1", ACNV.on to on, ACNV.offered to offered, ACNV.inSandbox to inSandbox),
        )
        assertEquals(AssistantView.loading, assistantViewOf(null, failed = false))
        assertEquals(AssistantView.failed, assistantViewOf(null, failed = true))
        assertEquals(AssistantView.on, assistantViewOf(status(on = true), failed = false))
        // On wins over offered, which a status never says together but a page must not mistake.
        assertEquals(AssistantView.on, assistantViewOf(status(on = true, offered = true), failed = false))
        assertEquals(AssistantView.switchOn, assistantViewOf(status(offered = true), failed = false))
        // In the sandbox it is switched on from the client itself.
        assertEquals(AssistantView.switchOnFromClient, assistantViewOf(status(offered = true, inSandbox = true), failed = false))
        assertEquals(AssistantView.off, assistantViewOf(status(), failed = false))
        // A status read already failed again keeps the one the page has.
        assertEquals(AssistantView.on, assistantViewOf(status(on = true), failed = true))
    }

    @Test
    fun theWireIsReadAsThePageNeedsIt() {
        val status = parseAssistantStatus(
            mapOf(
                ASST.client to "acme", ACNV.on to true, ACNV.hasSandbox to false, ACNV.definedInCode to true, ACNV.inSandbox to false,
                ACNV.usedShare to 0.5, ACNV.spent to false, ACNV.resetsAt to "2026-12-01T00:00:00.000Z", ASST.dataNotice to "Sent.",
            ),
        )
        assertEquals("acme", status.client)
        assertTrue(status.on && status.definedInCode && !status.hasSandbox)
        assertEquals(0.5, status.allowance.usedShare)
        // Offered to switch on only when the status says so (issue #1568), and known by its name where it has one.
        assertTrue(!status.offered)
        assertEquals("acme", status.clientName)
        val shop = parseAssistantStatus(mapOf(ASST.client to "free_shop1", ACNV.clientName to "Shop", ACNV.offered to true))
        assertTrue(shop.offered)
        assertEquals("Shop", shop.clientName)

        val read = parseConversationRead(
            mapOf(
                ACNV.conversation to mapOf(ACNV.conversationId to "c1", ACNV.title to "Hi", ACNV.running to true, ACNV.readOnly to false),
                ACNV.messages to listOf(
                    mapOf(ACNV.seq to 1, ACNV.turnId to "t1", ACNV.role to ABLK.user, ACNV.content to listOf(text("Hi"))),
                ),
                ACNV.turns to listOf(mapOf(ACNV.turnId to "t1", ACNV.text to "Hi", ACNV.status to ACNV.statusRunning)),
            ),
        )
        assertEquals("c1", read.summary.conversationId)
        assertTrue(read.summary.running)
        assertEquals(1L, read.messages.single().seq)
        assertEquals("Hi", read.messages.single().blocks.single()[ABLK.text])
        assertEquals(ACNV.statusRunning, read.turns.single().status)

        val clients = parseAssistantClients(
            mapOf(
                EP.items to listOf(
                    mapOf(
                        ASST.client to "acme", ASST.enabled to true, ASST.monthlyBudgetDollars to 20.0, ASST.budgetIsDefault to true,
                        ASST.usedDollars to 1.25, ASST.calls to 4, ASST.cacheHitRatio to 0.5,
                    ),
                ),
                EP.summary to mapOf(ASST.available to false, ASST.reason to "No key.", ASST.defaultMonthlyBudgetDollars to 20.0),
            ),
        )
        assertFalse(clients.status.available)
        assertEquals("No key.", clients.status.reason)
        clients.clients.single().let {
            assertTrue(it.enabled && it.budgetIsDefault)
            assertEquals(4L, it.calls)
            assertNull(it.lastUsedAt)
            assertEquals(0.5, it.cacheHitRatio)
        }
    }
}
