package com.dynamicruntime.webapp

import com.dynamicruntime.common.gedra.GE
import com.dynamicruntime.common.gedra.workflow.WCOL
import com.dynamicruntime.common.gedra.workflow.WFD
import com.dynamicruntime.common.gedra.workflow.WFS
import com.dynamicruntime.common.gedra.workflow.WfColumnCategory
import com.dynamicruntime.common.gedra.workflow.TraitLockCopy
import com.dynamicruntime.common.gedra.workflow.WfEntry
import com.dynamicruntime.common.gedra.workflow.WfPhase
import com.dynamicruntime.common.gedra.workflow.WfWaitsOn
import com.dynamicruntime.common.gedra.workflow.WfWeight
import com.dynamicruntime.common.gedra.workflow.CTAK
import com.dynamicruntime.common.gedra.workflow.CTKQ
import com.dynamicruntime.common.gedra.workflow.FormWorkflow
import com.dynamicruntime.common.gedra.workflow.SWF
import com.dynamicruntime.common.home.HFRAG
import kotlin.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The forms list's workflow column on the page side (issues #791, #1598): reading the listing's summary, joining a row's
 * workflows to it by client and id, weighing them, their verbs and reasons and fixed order, where each link goes, the
 * fold and counts a crowded or narrow cell shows -- and the workflow page's note on why a workflow cannot be worked on.
 */
class WorkflowColumnViewTest {
    private fun summaryEntry(client: String, id: String, phase: WfPhase = WfPhase.engageable) = mapOf(
        WCOL.client to client, WFD.workflowId to id, WFD.label to "Label of $id", WCOL.phase to phase.name,
        WCOL.explanations to mapOf("coreDetailsDone" to "Finish the core details workflow first."), WCOL.lastTask to "last",
    )

    private val summary = parseWorkflowSummary(
        mapOf(
            WCOL.workflows to listOf(
                summaryEntry("acme", "audit"),
                summaryEntry("acme", "followUp"),
                summaryEntry("globex", "audit"),
                // A phase this page does not know is dropped rather than guessed at.
                summaryEntry("acme", "odd") + (WCOL.phase to "someFuturePhase"),
            ),
        ),
    )

    private fun state(id: String, vararg fields: Pair<String, Any?>) =
        mapOf(GE.traitId to WFS.workflowState, GE.data to mapOf(WFD.workflowId to id, *fields))

    private fun engaged(id: String) =
        mapOf(GE.traitId to WFS.workflowEngagement, GE.data to mapOf(WFD.workflowId to id, WFS.engaged to true))

    private fun form(
        client: String,
        states: List<Map<String, Any?>>,
        steps: List<String> = emptyList(),
        reasons: List<Map<String, Any?>> = emptyList(),
    ) = FormSummary(
        gedraId = "g1", displayValues = emptyList(), traitLabels = emptyList(), createdAt = null, client = client, states = states,
        waitingSteps = steps.map { mapOf(WFD.workflowId to it, SWF.actionText to "Do $it") }, waitingReasons = reasons,
    )

    private fun reason(id: String, waitsOn: WfWaitsOn, label: String? = null) =
        mapOf(WFD.workflowId to id, WCOL.waitsOn to waitsOn.name) + (label?.let { mapOf(WFS.ctaFilterLabel to it) } ?: emptyMap())

    private val now = Instant.parse("2026-11-07T20:00:00Z")

    @Test
    fun theSummaryParsesAndARowJoinsItsOwnClientsEntries() {
        assertEquals(listOf("acme/audit", "acme/followUp", "globex/audit"), summary.map { "${it.client}/${it.workflowId}" })
        val states = listOf(
            state("audit", WFS.eligible to false, WFS.eligibilityFailures to listOf(mapOf(WFD.id to "coreDetailsDone"))),
            state("followUp", WFS.ctaTask to "note"),
            engaged("followUp"),
        )
        val cell = workflowCellOf(form("acme", states), summary)
        assertEquals(listOf("followUp" to WfWeight.waiting, "audit" to WfWeight.settled), cell.map { it.entry.workflowId to it.weight })
        // The engaged one opens on its current task, to look at, since it is not the viewer's turn; the ineligible one
        // explains itself instead.
        assertEquals("note", cell[0].linkTask)
        assertTrue(!cell[0].linkEdits)
        assertEquals(listOf("Finish the core details workflow first."), cell[1].reasons)
        // Another client's form with the same workflow id joins that client's entry, never acme's.
        assertEquals("Label of audit", workflowCellOf(form("globex", states), summary).single().entry.label)
        // A client the summary does not name shows nothing.
        assertTrue(workflowCellOf(form("initech", states), summary).isEmpty())
    }

    @Test
    fun aFinishedWorkflowOpensOnItsLastTaskAndAnEligibleOneOnTheWorkflow() {
        val states = listOf(state("audit", WFS.tasksDone to true), engaged("audit"), state("followUp", WFS.eligible to true))
        val cell = workflowCellOf(form("acme", states), summary)
        assertEquals(listOf(WfWeight.available, WfWeight.settled), cell.map { it.weight })
        assertEquals(listOf("Start", null), cell.map { it.verb })
        assertNull(cell[0].linkTask)
        assertEquals("last", cell[1].linkTask)
    }

    /** A client with five workflows, declared in this order, for the weighted cell (issue #1598). */
    private val five = parseWorkflowSummary(
        mapOf(
            WCOL.workflows to listOf(
                summaryEntry("acme", "intake"),
                summaryEntry("acme", "regional"),
                summaryEntry("acme", "legal"),
                summaryEntry("acme", "archive", WfPhase.lifetimeOnly) + (WCOL.closedAt to "2026-10-30T18:00:00Z"),
                summaryEntry("acme", "survey"),
            ),
        ),
    )

    private val reviewWords = listOf(CtaKeywordWords(CTAK.review, "Review", "Needs review", "Review", "Needs review", "Needs Review"))

    @Test
    fun eachLinkSaysWhatItAsksOfTheViewerAndAWaitingOneWhy() {
        val states = listOf(
            state("survey", WFS.eligible to true),
            state("archive", WFS.ctaTask to "file"),
            state("legal", WFS.ctaTask to "approve", WFS.ctaKeyword to CTAK.review),
            state("regional", WFS.ctaTask to "approve", WFS.ctaKeyword to CTAK.review),
            state("intake", WFS.ctaTask to "describe"),
            engaged("archive"), engaged("legal"), engaged("regional"), engaged("intake"),
        )
        val reasons = listOf(reason("legal", WfWaitsOn.filter, "Waiting on a legal reviewer"))
        val row = form("acme", states, steps = listOf("regional", "intake"), reasons = reasons)
        val cell = workflowCellOf(row, five, reviewWords, now = now)
        // The viewer's turn, then what they could start, then what waits -- each kind as the client declared them, never
        // as the state entries arrived.
        assertEquals(
            listOf(
                Triple("intake", WfWeight.yourTurn, "Do intake"),
                Triple("regional", WfWeight.yourTurn, "Review"),
                Triple("survey", WfWeight.available, "Start"),
                Triple("legal", WfWeight.waiting, null),
                Triple("archive", WfWeight.waiting, null),
            ),
            cell.map { Triple(it.entry.workflowId, it.weight, it.verb) },
        )
        // A review the viewer may give takes the keyword's verb; a step with no keyword says what it asks in words.
        // Every muted link says why: the filter's own label, or, frozen, when it closed.
        assertEquals(listOf(null, null, null, "Waiting on a legal reviewer", "Closed 2026-10-30"), cell.map { it.reason })
        // Only the viewer's turn opens ready to edit.
        assertEquals(listOf(true, true, false, false, false), cell.map { it.linkEdits })
    }

    @Test
    fun aFinishedWorkflowComesBeforeOneTheFormIsNotEligibleFor() {
        val states = listOf(
            state("intake", WFS.tasksDone to true), engaged("intake"),
            state("regional", WFS.eligible to false),
            state("legal", WFS.eligible to true),
            state("survey", WFS.ctaTask to "ask"), engaged("survey"),
        )
        val cell = workflowCellOf(form("acme", states), five, now = now)
        assertEquals(
            listOf(
                "legal" to WorkflowCellKind.available, "survey" to WorkflowCellKind.waiting,
                "intake" to WorkflowCellKind.finished, "regional" to WorkflowCellKind.notAvailable,
            ),
            cell.map { it.entry.workflowId to it.kind },
        )
    }

    @Test
    fun aHighlightedStartThatWaitsOnTheViewerIsTheirTurnAndItsLinkEngages() {
        // Issue #1662: the row's waiting steps name the eligible workflow's first task, as a Start.
        val states = listOf(state("survey", WFS.eligible to true), state("legal", WFS.eligible to true))
        val row = form("acme", states).let {
            FormSummary(
                gedraId = it.gedraId, displayValues = emptyList(), traitLabels = emptyList(), createdAt = null, client = "acme",
                states = states,
                waitingSteps = listOf(mapOf(WFD.workflowId to "survey", WFS.ctaTask to "ask", SWF.actionText to "Ask", SWF.startsWorkflow to true)),
            )
        }
        val cell = workflowCellOf(row, five, now = now)
        val start = cell.first()
        assertEquals("survey" to WfWeight.yourTurn, start.entry.workflowId to start.weight)
        // It says "Start", opens its first task ready to edit, and engages the form on the way.
        assertEquals("Start", start.verb)
        assertEquals("ask", start.linkTask)
        assertTrue(start.linkEdits && start.linkEngages)
        assertEquals(
            listOf(HP.page to pageCoreDetailsEdit, HP.from to "forms", HP.gedra to "g1", HP.workflow to "survey",
                HP.task to "ask", HP.edit to "1", HP.engage to "1"),
            workflowPageRoute("g1", "survey", start.linkTask, start.linkEdits, engage = start.linkEngages),
        )
        // The other eligible workflow is only available, and engages nothing on the way.
        val other = cell.single { it.entry.workflowId == "legal" }
        assertEquals(WfWeight.available, other.weight)
        assertTrue(!other.linkEngages)
    }

    @Test
    fun aHighlightedStartThatWaitsOnSomeoneElseIsDrawnWaitingWithItsReason() {
        // Issue #1690: the summary says the workflow highlights its Start; the row has no step of it for the viewer, and
        // a reason saying whom its first task waits on.
        val highlighted = parseWorkflowSummary(
            mapOf(WCOL.workflows to listOf(summaryEntry("acme", "review") + (WCOL.highlightStart to true), summaryEntry("acme", "plain"))),
        )
        assertTrue(highlighted.first().highlightStart)
        val states = listOf(state("review", WFS.eligible to true), state("plain", WFS.eligible to true))
        val cell = workflowCellOf(form("acme", states, reasons = listOf(reason("review", WfWaitsOn.others))), highlighted, now = now)
        val review = cell.single { it.entry.workflowId == "review" }
        // Muted with its reason, as a step of an engaged workflow waiting on someone else; no verb, and its link opens
        // the workflow's page without engaging the form.
        assertEquals(WorkflowCellKind.waiting, review.kind)
        assertEquals(WorkflowCellWords().waitingOnOthers, review.reason)
        assertEquals(null, review.verb)
        assertTrue(!review.linkEngages && review.linkTask == null)
        // A workflow that does not highlight its Start stays available.
        assertEquals(WfWeight.available, cell.single { it.entry.workflowId == "plain" }.weight)
    }

    @Test
    fun aRowsLinksKeepTheirOrderAsOtherPeopleActOnTheForm() {
        fun cellAfter(vararg mine: String, shuffled: Boolean) = workflowCellOf(
            form(
                "acme",
                listOf(
                    state("intake", WFS.ctaTask to "describe"), state("regional", WFS.ctaTask to "approve"),
                    state("legal", WFS.ctaTask to "approve"), engaged("intake"), engaged("regional"), engaged("legal"),
                ).let { if (shuffled) it.reversed() else it },
                steps = mine.toList(),
            ),
            five,
        ).map { it.entry.workflowId }
        // Whoever acted last, the order within a weight is the declared one, wherever the recompute put the entries.
        assertEquals(listOf("intake", "regional", "legal"), cellAfter(shuffled = false))
        assertEquals(listOf("intake", "regional", "legal"), cellAfter(shuffled = true))
        // A workflow that becomes the viewer's turn moves up a weight, and the rest keep their order.
        assertEquals(listOf("legal", "intake", "regional"), cellAfter("legal", shuffled = true))
    }

    @Test
    fun aWaitingLinkSaysWhomItWaitsOnOrWhenItsWindowOpens() {
        val words = WorkflowCellWords()
        val open = five.first { it.workflowId == "intake" }
        assertEquals("Waiting for approval", waitingReasonOf(open, reason("intake", WfWaitsOn.approval), words, now))
        assertEquals("Waiting on the owner", waitingReasonOf(open, reason("intake", WfWaitsOn.owner), words, now))
        assertEquals("Waiting on someone else", waitingReasonOf(open, reason("intake", WfWaitsOn.others), words, now))
        // A filter with no label of its own still says someone else's turn.
        assertEquals("Waiting on someone else", waitingReasonOf(open, reason("intake", WfWaitsOn.filter), words, now))
        // A call to action not computed yet just waits.
        assertEquals("Waiting", waitingReasonOf(open, null, words, now))
        // Frozen before its window opens, it says when, as the table writes a moment.
        val opens =
            WorkflowSummaryEntry("acme", "later", "Later", WfPhase.lifetimeOnly, emptyMap(), "last", opensAt = "2026-11-07T23:00:00Z")
        assertEquals("Opens in 3h", waitingReasonOf(opens, null, words, now))
        val undated = WorkflowSummaryEntry("acme", "gone", "Gone", WfPhase.lifetimeOnly, emptyMap(), "last")
        assertEquals("Closed", waitingReasonOf(undated, null, words, now))
        // A client's words win, each one it does not give staying as shipped.
        val reworded = WorkflowCellWords.of(Copy(mapOf(HFRAG.workflowCellNs to mapOf(HFRAG.wcOpens to $$"Back ${at}"))))
        assertEquals("Back in 3h", waitingReasonOf(opens, null, reworded, now))
        assertEquals("Waiting", reworded.waiting)
    }

    @Test
    fun aCrowdedCellFoldsPastFourButNeverTheViewersTurn() {
        val entry = summary.first()
        fun item(weight: WfWeight, category: WfColumnCategory = WfColumnCategory.engaged) =
            WorkflowCellItem(entry, FormWorkflow("x", category, null, emptyList()), weight)
        val yourTurn = item(WfWeight.yourTurn)
        val available = item(WfWeight.available, WfColumnCategory.eligible)
        val ineligible = item(WfWeight.settled, WfColumnCategory.ineligible)
        fun fold(vararg items: WorkflowCellItem) = workflowCellFold(items.toList()).let { (d, f) -> d.size to f.size }
        // Two of the viewer's turn, two to start, two not eligible: the turns and one more drawn, three folded.
        val six = listOf(yourTurn, yourTurn, available, available, ineligible, ineligible)
        workflowCellFold(six).let { (drawn, folded) ->
            assertEquals(listOf(WorkflowCellKind.yourTurn, WorkflowCellKind.yourTurn, WorkflowCellKind.available), drawn.map { it.kind })
            assertEquals(listOf(WorkflowCellKind.available, WorkflowCellKind.notAvailable, WorkflowCellKind.notAvailable),
                folded.map { it.kind })
        }
        // Four are all drawn, rather than three and a "+1"; five draw three.
        assertEquals(4 to 0, fold(available, available, ineligible, ineligible))
        assertEquals(3 to 2, fold(available, available, available, ineligible, ineligible))
        // The viewer's turn is drawn however many there are.
        assertEquals(4 to 1, fold(yourTurn, yourTurn, yourTurn, yourTurn, ineligible))
    }

    @Test
    fun aNarrowCellCountsEachKindAndEveryWordIsTheClients() {
        val entry = summary.first()
        fun item(weight: WfWeight, category: WfColumnCategory = WfColumnCategory.engaged) =
            WorkflowCellItem(entry, FormWorkflow("x", category, null, emptyList()), weight)
        val items = listOf(
            item(WfWeight.yourTurn), item(WfWeight.available, WfColumnCategory.eligible), item(WfWeight.waiting),
            item(WfWeight.waiting), item(WfWeight.settled, WfColumnCategory.finished),
            item(WfWeight.settled, WfColumnCategory.ineligible),
        )
        // Never "settled": a settled link is finished or not available, and the counts say which.
        assertEquals("1 your turn · 1 available · 2 waiting · 1 finished · 1 not available", workflowCellCounts(items))
        assertEquals("", workflowCellCounts(emptyList()))
        val shipped = WorkflowCellWords()
        assertEquals("+3 more", shipped.moreText(3))
        assertEquals("Not available", shipped.group(WorkflowCellKind.notAvailable))
        assertEquals("Why Audit is not available", shipped.whyNotAvailableText("Audit"))
        // A client rewords any of them; the rest stay as shipped.
        val reworded = WorkflowCellWords.of(
            Copy(
                mapOf(
                    HFRAG.workflowCellNs to mapOf(
                        HFRAG.wcWaitingCount to $$"${n} pending", HFRAG.wcNotAvailableGroup to "Closed to this form",
                        HFRAG.wcMore to $$"${n} others",
                    ),
                ),
            ),
        )
        assertEquals("1 your turn · 1 available · 2 pending · 1 finished · 1 not available", workflowCellCounts(items, reworded))
        assertEquals("Closed to this form", reworded.group(WorkflowCellKind.notAvailable))
        assertEquals("Finished", reworded.group(WorkflowCellKind.finished))
        assertEquals("3 others", reworded.moreText(3))
        assertEquals("More workflows", reworded.moreTitle)
    }

    @Test
    fun theSplitLayoutsCellFoldsPastThreeAndItsColumnIsHeadedByTheKeywordInUse() {
        val entry = summary.first()
        fun item(weight: WfWeight, category: WfColumnCategory = WfColumnCategory.engaged) =
            WorkflowCellItem(entry, FormWorkflow("x", category, null, emptyList()), weight)
        val available = item(WfWeight.available, WfColumnCategory.eligible)
        val ineligible = item(WfWeight.settled, WfColumnCategory.ineligible)
        fun fold(vararg items: WorkflowCellItem) =
            workflowCellFold(items.toList(), WorkflowCellFoldAt.split).let { (d, f) -> d.size to f.size }
        // Beside the Your turn column (issue #1599) the cell holds the rest: three drawn, four fold to two and "+2".
        assertEquals(3 to 0, fold(available, available, ineligible))
        assertEquals(2 to 2, fold(available, available, ineligible, ineligible))
        // The column's header: the one keyword in use's, else "Your turn", with the count when there is one.
        val words = WorkflowCellWords()
        assertEquals("To review (4)", yourTurnHeader(listOf(reviewWords.single().withHeader("To review")), words, 4))
        assertEquals("Your turn", yourTurnHeader(emptyList(), words, null))
        val second = CtaKeywordWords("approve", "Approve", "Needs approval", "To approve", "Needs approval", "Needs Approval")
        assertEquals("Your turn (2)", yourTurnHeader(reviewWords + second, words, 2))
        // The keywords in use are those the listing's endpoint takes a parameter for: none on the shared listing.
        assertEquals(listOf(CTAK.review), keywordsInUse(reviewWords + second, setOf(CTAK.review, "q")).map { it.keyword })
        assertTrue(keywordsInUse(reviewWords, setOf("q")).isEmpty())
        // A keyword filter applied says its label and choice, in the client's words.
        assertEquals(listOf("Needs review: Yours"), keywordFilterChips(mapOf(CTAK.review to CTKQ.mine), reviewWords, words))
        val reworded = WorkflowCellWords.of(Copy(mapOf(HFRAG.workflowCellNs to mapOf(HFRAG.wcKeywordAny to "Everyone's"))))
        assertEquals(listOf("Needs review: Everyone's"), keywordFilterChips(mapOf(CTAK.review to CTKQ.any), reviewWords, reworded))
        assertTrue(keywordFilterChips(emptyMap(), reviewWords, words).isEmpty())
    }

    private fun CtaKeywordWords.withHeader(header: String) =
        CtaKeywordWords(keyword, verb, filterLabel, header, reportLabel, statusText)

    @Test
    fun theWorkflowPageSaysWhyItsTasksAreReadOnly() {
        fun view(phase: WfPhase?, engaged: Boolean?, entry: WfEntry = WfEntry.normal, eligible: Boolean? = null) = WorkflowView(
            workflowId = "audit", entry = entry.name, showTaskList = true, tasks = emptyList(), cfacts = emptyMap(),
            phase = phase?.name, engaged = engaged, eligible = eligible,
        )
        assertNull(workflowNote(view(null, null, WfEntry.coreDetails)))
        // The core details view says its data is not all of the form's (issue #1571); no other view says so.
        assertTrue(coreDetailsNote(view(null, null, WfEntry.coreDetails)).orEmpty().contains("Each workflow keeps its own"))
        assertNull(coreDetailsNote(view(WfPhase.relevant, true)))
        // A client's own help for the workflow replaces the platform's line.
        assertNull(coreDetailsNote(WorkflowView(workflowId = "details", entry = WfEntry.coreDetails.name, showTaskList = true,
            tasks = emptyList(), cfacts = emptyMap(), help = "What we ask every applicant.")))
        assertNull(workflowNote(view(WfPhase.relevant, true)))
        // A form that may start the workflow is told so in the cell's words, "Start", not "Engage" (issue #1691).
        val mayStart = view(WfPhase.engageable, false, eligible = true)
        assertEquals("This form has not started this workflow. Start it to work on it.", workflowNote(mayStart))
        assertEquals("Not begun.", workflowNote(mayStart, WorkflowCellWords(notStarted = "Not begun.")))
        assertTrue(workflowNote(view(WfPhase.lifetimeOnly, true)).orEmpty().contains("closed"))
        assertTrue(view(WfPhase.engageable, false, eligible = true).canEngage)
        assertTrue(!view(WfPhase.lifetimeOnly, true).canWork)
        // Engaged and open, but every step with something to save is someone else's (issue #856): no Edit, and no
        // workflow-wide note -- each step says so itself.
        val othersStep = WfTaskView(
            "record", "Record", traits = emptyList(), saves = listOf(WfSaveView("s", "Save", "edit")), canSave = false,
        )
        val othersOnly = WorkflowView(
            workflowId = "audit", entry = WfEntry.normal.name, showTaskList = true, tasks = listOf(othersStep),
            cfacts = emptyMap(), phase = WfPhase.engageable.name, engaged = true,
        )
        assertTrue(!othersOnly.canWork)
        assertNull(workflowNote(othersOnly))
        assertTrue(WorkflowView(
            workflowId = "audit", entry = WfEntry.normal.name, showTaskList = true, tasks = listOf(othersStep.let {
                WfTaskView(it.id, it.label, it.traits, it.saves, canSave = true)
            }),
            cfacts = emptyMap(), phase = WfPhase.engageable.name, engaged = true,
        ).canWork)
        // Kept to its owner (issue #1433): said once for the whole form, of any workflow, and no Engage either.
        fun kept(entry: WfEntry, engaged: Boolean?, eligible: Boolean? = null) = WorkflowView(
            workflowId = "audit", entry = entry.name, showTaskList = true, tasks = emptyList(), cfacts = emptyMap(),
            phase = WfPhase.engageable.name.takeIf { entry == WfEntry.normal }, engaged = engaged, eligible = eligible,
            keptToOwner = true,
        )
        assertEquals(TraitLockCopy.keptToOwner, workflowNote(kept(WfEntry.coreDetails, null)))
        assertEquals(TraitLockCopy.keptToOwner, workflowNote(kept(WfEntry.normal, true)))
        assertTrue(!kept(WfEntry.normal, false, eligible = true).canEngage)
        // Not eligible: no Engage that could only be refused, and the note leads into the reasons.
        val ineligible = view(WfPhase.engageable, false, eligible = false)
        assertTrue(!ineligible.canEngage)
        assertTrue(workflowNote(ineligible).orEmpty().contains("cannot be put into"))
    }

    @Test
    fun onlyAnEngagedWorkflowsLinkOpensInEditMode() {
        assertEquals(
            listOf(HP.page to pageCoreDetailsEdit, HP.from to "forms", HP.gedra to "g1", HP.workflow to "audit",
                HP.task to "record", HP.edit to "1"),
            workflowPageRoute("g1", "audit", "record", edit = true),
        )
        // A finished workflow's last task is there to be looked at; an eligible one opens on the workflow.
        assertEquals(null, workflowPageRoute("g1", "audit", "last", edit = false).toMap()[HP.edit])
        assertEquals(null, workflowPageRoute("g1", "audit", null, edit = true).toMap()[HP.task])
    }

    @Test
    fun engagingOpensTheWorkflowInEditMode() {
        val before = mapOf(HP.page to pageCoreDetailsEdit, HP.from to "forms", HP.gedra to "g1", HP.workflow to "audit")
        assertEquals(before.toList() + (HP.edit to "1"), engagedRoute(before))
        // An address already in edit mode keeps one flag, not two.
        assertEquals(listOf(HP.page to pageCoreDetailsEdit, HP.edit to "1"), engagedRoute(mapOf(HP.page to pageCoreDetailsEdit,
            HP.edit to "1")))
    }

    @Test
    fun theWorkflowKeyIsNavigationNotASearchFilter() {
        assertEquals(mapOf("q" to "x"), formsSearchFromRoute(mapOf(HP.page to "coreDetailsEdit", HP.workflow to "audit", "q" to "x")))
    }
}
