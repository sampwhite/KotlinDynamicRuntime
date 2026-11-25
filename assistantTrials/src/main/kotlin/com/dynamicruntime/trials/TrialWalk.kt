package com.dynamicruntime.trials

import com.dynamicruntime.common.content.UIC
import com.dynamicruntime.common.context.UPF
import com.dynamicruntime.common.endpoint.clientPath
import com.dynamicruntime.common.gedra.CFEP
import com.dynamicruntime.common.gedra.ClientCreate
import com.dynamicruntime.common.gedra.GDF
import com.dynamicruntime.common.gedra.GE
import com.dynamicruntime.common.gedra.GEP
import com.dynamicruntime.common.gedra.report.RDEF
import com.dynamicruntime.common.gedra.report.RRUN
import com.dynamicruntime.common.gedra.report.ReportCombine
import com.dynamicruntime.common.gedra.workflow.SWF
import com.dynamicruntime.common.gedra.workflow.ULH
import com.dynamicruntime.common.gedra.workflow.WFD
import com.dynamicruntime.common.gedra.workflow.WFS
import com.dynamicruntime.common.gedra.workflow.WSF
import com.dynamicruntime.common.gedra.workflow.WVF
import com.dynamicruntime.common.gedra.workflow.WfSaveKind
import com.dynamicruntime.common.home.HCARD
import com.dynamicruntime.common.home.HEP
import com.dynamicruntime.common.home.HFLD
import com.dynamicruntime.common.home.HMENU
import com.dynamicruntime.common.http.request.ROLE
import com.dynamicruntime.common.uiblock.UIB
import com.dynamicruntime.common.user.ADF
import com.dynamicruntime.common.user.AEP
import com.dynamicruntime.common.user.UADEP
import com.dynamicruntime.common.util.toJsonListOfMaps
import com.dynamicruntime.common.util.toJsonListOfStrings
import com.dynamicruntime.common.util.toJsonMapOrEmpty
import com.dynamicruntime.common.util.toOptDoubleOrNull
import com.dynamicruntime.common.util.toOptLong
import com.dynamicruntime.common.util.toOptStr
import kotlin.math.abs

/**
 * Something a walk does with a configured client, and what its expectation in the report is called ([title]). Each name
 * matches its value.
 */
@Suppress("EnumEntryName")
enum class WalkStep(val title: String) {
    /** An applicant makes a form through the creation workflow's own form. */
    form("Walked: an applicant makes a form through the New form"),

    /** The form waits on a reviewer before anyone has put it in the review, and they start it. */
    start("Walked: the form waits on a reviewer, who starts the review"),

    /** Every step of the review is given, each by whoever it waits on. */
    review("Walked: each step of the review is given by whoever it waits on"),

    /** The report counts the form, and its totals rise by what the form asked for. */
    report("Walked: the report counts the form, and totals its amount"),

    /** Home, as the applicant is served it, opens the New form. */
    home("Walked: an applicant's Home opens the New form"),
    ;

    val expectation: Expectation get() = Expectation(title) { r -> r.walk?.outcomes?.get(this) ?: Outcome.info("Not walked.") }
}

/** Somebody a walk made in the client's sandbox: what they are in it, and their address, for whoever looks afterward. */
class WalkPerson(val role: String, val email: String)

/** What a walk did: who it made, and how each step of it came out. */
class WalkRecord(val people: List<WalkPerson>, val outcomes: Map<WalkStep, Outcome>) {
    fun toJsonMap(): Map<String, Any?> = mapOf(
        "people" to people.map { mapOf("role" to it.role, "email" to it.email) },
        "outcomes" to outcomes.map { (step, o) -> mapOf("step" to step.name, "kind" to o.kind.name, "detail" to o.detail) },
    )

    companion object {
        /** The record [map] holds; a step or an outcome there is no longer is left out. */
        fun fromJsonMap(map: Map<String, Any?>): WalkRecord = WalkRecord(
            map["people"].toJsonListOfMaps().map { WalkPerson(it["role"].toOptStr().orEmpty(), it["email"].toOptStr().orEmpty()) },
            buildMap {
                for (o in map["outcomes"].toJsonListOfMaps()) {
                    val step = WalkStep.entries.firstOrNull { it.name == o["step"] } ?: continue
                    val kind = OutcomeKind.entries.firstOrNull { it.name == o["kind"] } ?: continue
                    put(step, Outcome(kind, o["detail"].toOptStr().orEmpty()))
                }
            },
        )
    }
}

/** Makes the people a walk acts as. */
fun interface TrialPeople {
    /** Signs [email] in as a user of [client], made when there is none, called [name]; answers their calls. */
    fun become(email: String, client: String, name: String): TrialCaller
}

/**
 * Walks through what a trial configured (issue #1915), in the client's sandbox: as an applicant, as a reviewer set up as an
 * administrator would, and as the client's administrator ([admin]). No call reaches the model, so it costs nothing.
 *
 * A check of the configuration says what the assistant wrote; a walk says what it does. Both of the worst faults the trials
 * found met every check and showed only in use: Home cards that opened nothing (issue #1869), and a reviewer who could see
 * no form (issue #1911).
 *
 * It acts over the calls the pages make, and draws none of them: whether a card's button is drawn is the webapp's, held by
 * its own tests and by [Checks.homeCardStartsForm]. Each step's outcome says what stopped it, and none throws.
 */
class TrialWalk(
    private val admin: TrialCaller,
    private val people: TrialPeople,
    private val sandbox: String,
    private val view: ConfigView,
    private val steps: List<WalkStep>,
    /** The address of the client's administrator, which a made person's is after their role: `reviewer-` and it. */
    private val address: String,
    /** Put in every text a form is filled with; see [TrialForm]. */
    private val tag: String = System.currentTimeMillis().toString(36),
) {
    /** One of those the walk acts as, by [role]. */
    private class Actor(val role: String, val caller: TrialCaller) {
        val called: String get() = "the $role"
    }

    private fun addressOf(role: String) = "$role-$address"

    /** A form as made: its id, and the data of each trait it was given. */
    private class MadeForm(val gedraId: String, val data: Map<String, Map<String, Any?>>)

    fun walk(): WalkRecord {
        val made = mutableListOf<WalkPerson>()
        val outcomes = linkedMapOf<WalkStep, Outcome>()
        fun make(role: String, name: String): Actor {
            val email = addressOf(role)
            return Actor(role, people.become(email, sandbox, name)).also { made.add(WalkPerson(role, email)) }
        }
        try {
            val applicant = make(applicantRole, "Ada Applicant")
            var form: MadeForm? = null
            if (steps.any { it != WalkStep.home }) {
                val before = runCatching { reportTotals() }
                outcomes[WalkStep.form] = judged {
                    makeForm(applicant.caller).let { (made, outcome) ->
                        form = made
                        outcome
                    }
                }
                if (WalkStep.start in steps || WalkStep.review in steps) {
                    val reviewer = make(reviewerRole, "Rae Reviewer")
                    val labels = runCatching { setUpReviewer(reviewer.caller) }
                    val actors = listOf(reviewer, Actor(administratorRole, admin), applicant)
                    outcomes[WalkStep.start] = judged { startReview(form, actors, labels.getOrThrow()) }
                    outcomes[WalkStep.review] = judged { giveReview(form, actors) }
                }
                if (WalkStep.report in steps) outcomes[WalkStep.report] = judged { judgeReports(form, before.getOrThrow()) }
            }
            if (WalkStep.home in steps) outcomes[WalkStep.home] = judged { judgeHome(applicant.caller) }
        } catch (e: Exception) {
            for (step in steps) outcomes.getOrPut(step) { Outcome.notMet("The walk stopped: ${e.message ?: e::class.simpleName}") }
        }
        return WalkRecord(made, outcomes.filterKeys { it in steps })
    }

    /** What [judge] answers; a call refused, or anything else that stops it, is not met, with what did. */
    private fun judged(judge: () -> Outcome): Outcome = try {
        judge()
    } catch (e: Exception) {
        Outcome.notMet("Stopped: ${e.message ?: e::class.simpleName}")
    }

    // --- the form ---

    /** Makes a form as [caller], through the client's creation workflow as they are shown it. */
    private fun makeForm(caller: TrialCaller): Pair<MadeForm?, Outcome> {
        val shown = caller.getData(clientPath(GEP.workflowView, sandbox))
        if (shown[WVF.found] != true) return null to Outcome.notMet("The client has no creation workflow.")
        val workflowId = shown[WFD.workflowId].toOptStr().orEmpty()
        val task = shown[WFD.tasks].toJsonListOfMaps().first()
        val save = task[WFD.saves].toJsonListOfMaps().firstOrNull { it[WFD.kind] == WfSaveKind.create.name }
            ?: return null to Outcome.notMet("The creation workflow '$workflowId' has no save that makes a form.")
        val filler = TrialForm(shown, tag)
        val entries = filler.entries(task)
        val leftOut = filler.skipped.takeIf { it.isNotEmpty() }?.let { " Left out, as fields the walk cannot fill: ${it.joinToString()}." }
            .orEmpty()
        val saved = caller.postData(
            clientPath(GEP.workflowSave, sandbox),
            mapOf(
                GDF.workflowId to workflowId, GDF.taskId to task[WFD.id], GDF.saveId to save[WFD.id], GDF.entries to entries,
            ),
        )
        val gedraId = saved[WSF.item].toJsonMapOrEmpty()[GDF.gedraId].toOptStr()
        if (saved[WSF.saved] != true || gedraId == null) {
            val unmet = saved[WSF.unmetTraits].toJsonListOfStrings()
            return null to Outcome.notMet("The save made no form: it needs ${unmet.joinToString()}.$leftOut")
        }
        val data = entries.associate { it[GE.traitId].toOptStr().orEmpty() to it[GE.data].toJsonMapOrEmpty() }
        val filled = data.flatMap { (traitId, fields) -> fields.keys.map { "$traitId.$it" } }
        return MadeForm(gedraId, data) to Outcome.met("Made `$gedraId`, filling ${filled.joinToString()}.$leftOut")
    }

    // --- the review ---

    /**
     * Gives [reviewer] what an administrator gives a reviewer: the review's label, as the client lists it, and the `editor`
     * role, without which they see no form but their own (issue #1911). Answers the labels.
     */
    private fun setUpReviewer(reviewer: TrialCaller): List<String> {
        val userId = reviewer.getData(AEP.selfInfo)[UPF.userId].toOptLong() ?: error("The reviewer has no user id.")
        val listed = admin.getData(UADEP.userLabelSuggestions)[ADF.labels].toJsonListOfStrings()
        val taken = view.review?.get(WFD.tasks).toJsonListOfMaps().flatMap { it[WFD.functions].toJsonListOfMaps() }
            .filter { it[WFD.fn] == ULH.fn }.mapNotNull { it[ULH.label].toOptStr() }
        val labels = listed.filter { it in taken }
        admin.postData(UADEP.userSetLabels, mapOf(ADF.userId to userId, ADF.labels to labels))
        admin.postData(UADEP.userSetRoles, mapOf(ADF.userId to userId, ADF.roles to listOf(ROLE.user, ROLE.editor)))
        return labels
    }

    private val reviewId: String? get() = view.review?.get(WFD.workflowId).toOptStr()

    /** The review's step on [form] waiting on [actor], as "Waiting on you" lists it; null when none is. */
    private fun waitingOn(actor: Actor, form: MadeForm, workflowId: String): Map<String, Any?>? =
        actor.caller.getItems(clientPath(GEP.formDocs, sandbox), mapOf(GDF.withStates to true, GDF.needsMe to true))
            .firstOrNull { it[GDF.gedraId] == form.gedraId }?.get(GDF.waitingSteps).toJsonListOfMaps()
            .firstOrNull { it[WFD.workflowId] == workflowId }

    private fun viewOf(actor: Actor, form: MadeForm, workflowId: String): Map<String, Any?> = actor.caller.getData(
        clientPath(GEP.workflowView, sandbox), mapOf(GDF.workflowId to workflowId, GDF.gedraId to form.gedraId),
    )

    /**
     * Starts the review on [form] as the reviewer, the first of [actors], once the form is listed under their "Waiting on
     * you". Varied when it waits on someone else, or on nobody and somebody starts it anyway. A review that begins with the
     * applicant's own step is theirs to start.
     */
    private fun startReview(form: MadeForm?, actors: List<Actor>, labels: List<String>): Outcome {
        val workflowId = reviewId ?: return Outcome.notMet("No workflow with an approval task.")
        if (form == null) return Outcome.notMet("There is no form to review.")
        val reviewer = actors.first()
        val has = "The reviewer has ${labels.joinToString { "'$it'" }.ifEmpty { "no label" }} and the editor role."
        val shown = viewOf(reviewer, form, workflowId)
        if (shown[WVF.engaged] == true) return Outcome.varied("The form was in the review already.")
        if (shown[WVF.eligible] != true) {
            return Outcome.notMet("The form may not enter it: ${shown[WVF.ineligibleReasons].toJsonListOfStrings().joinToString(" ")}")
        }
        fun startAs(actor: Actor) = actor.caller.postData(
            clientPath(GEP.workflowEngage, sandbox), mapOf(GDF.gedraId to form.gedraId, GDF.workflowId to workflowId),
        )
        val first = view.review?.get(WFD.tasks).toJsonListOfMaps().first()
        if (first[WFD.approval] == null && first[WFD.saveWhen] == null) {
            startAs(actors.last())
            return Outcome.met("It begins with the applicant's own step, and they started it. $has")
        }
        val pointed = actors.firstOrNull { waitingOn(it, form, workflowId)?.get(SWF.startsWorkflow) == true }
        // Waiting on nobody, it is started all the same, so the rest of the walk can go on.
        startAs(pointed ?: reviewer)
        return when (pointed) {
            null -> Outcome.varied("It waited on nobody; the reviewer started it all the same. $has")
            reviewer -> Outcome.met("It waited on the reviewer, who started it. $has")
            else -> Outcome.varied("It waited on ${pointed.called}, who started it. $has")
        }
    }

    /**
     * Takes the review on [form] to its end: each step by whichever of [actors] it waits on, an approval given and a save
     * filled in. Varied when the applicant could give an approval of their own form.
     */
    private fun giveReview(form: MadeForm?, actors: List<Actor>): Outcome {
        val workflowId = reviewId ?: return Outcome.notMet("No workflow with an approval task.")
        if (form == null) return Outcome.notMet("There is no form to review.")
        val taken = mutableListOf<String>()
        val ownApprovals = mutableListOf<String>()
        fun soFar() = if (taken.isEmpty()) "Nothing was given." else "Given: ${taken.joinToString("; ")}."
        repeat(maxReviewSteps) {
            val standing = viewOf(actors.first(), form, workflowId)
            if (standing[WVF.engaged] != true) return Outcome.notMet("The form is not in the review.")
            val taskId = standing[WVF.focusTask].toOptStr() ?: return if (ownApprovals.isEmpty()) {
                Outcome.met(soFar())
            } else {
                Outcome.varied("The applicant could approve their own form at ${ownApprovals.joinToString()}. ${soFar()}")
            }
            val waiting = actors.filter { waitingOn(it, form, workflowId) != null }
            val actor = waiting.firstOrNull() ?: return Outcome.notMet("'$taskId' waits on none of those walked as. ${soFar()}")
            val shown = viewOf(actor, form, workflowId)
            val task = shown[WFD.tasks].toJsonListOfMaps().first { it[WFD.id] == taskId }
            val said = try {
                takeStep(actor, form, workflowId, shown, task)
            } catch (e: TrialCallRefused) {
                return Outcome.notMet("'$taskId' was refused to ${actor.called}: ${e.message}. ${soFar()}")
            }
            if (task[WVF.approval] != null && waiting.any { it.role == applicantRole }) ownApprovals.add("'$said'")
            taken.add("'$said' by ${actor.called}")
        }
        return Outcome.notMet("It did not end in $maxReviewSteps steps. ${soFar()}")
    }

    /** Gives [task]'s approval step, or saves it filled in, as [actor]; answers what it is called. */
    private fun takeStep(
        actor: Actor, form: MadeForm, workflowId: String, shown: Map<String, Any?>, task: Map<String, Any?>,
    ): String {
        val ids = mapOf(GDF.gedraId to form.gedraId, GDF.workflowId to workflowId, GDF.taskId to task[WFD.id])
        val label = task[WFD.label].toOptStr() ?: task[WFD.id].toOptStr().orEmpty()
        val approval = task[WVF.approval]?.toJsonMapOrEmpty()
        if (approval != null) {
            val stepId = approval[WVF.currentStep].toOptStr()
            actor.caller.postData(clientPath(GEP.workflowApprove, sandbox), ids + listOfNotNull(stepId?.let { GDF.stepId to it }))
            val step = approval[WVF.steps].toJsonListOfMaps().takeIf { it.size > 1 }?.firstOrNull { it[WFD.id] == stepId }
            return step?.let { "$label: ${it[WFD.label] ?: stepId}" } ?: label
        }
        val save = task[WFD.saves].toJsonListOfMaps().firstOrNull() ?: error("Task '$label' offers neither an approval nor a save")
        val entries = TrialForm(shown, tag).entries(task)
        actor.caller.postData(clientPath(GEP.workflowSave, sandbox), ids + mapOf(GDF.saveId to save[WFD.id], GDF.entries to entries))
        return label
    }

    // --- the report ---

    /** One report's grouped run, over all its groups: how many forms, and each totaled column's total. */
    private class Totals(val count: Long, val columns: Map<String, Double>)

    /** Each report's [Totals] now but the starter's, which every new client has. */
    private fun reportTotals(): Map<String, Totals> =
        view.reports.mapNotNull { it[RDEF.reportId].toOptStr() }.filter { it != ClientCreate.starterReport }.associateWith { id ->
            val rows = admin.getItems(UADEP.reportRun, mapOf(RRUN.reportId to id, RRUN.aggregate to true))
            val columns = mutableMapOf<String, Double>()
            for (row in rows) {
                for ((column, value) in row[RRUN.values].toJsonMapOrEmpty()) {
                    value.toOptDoubleOrNull()?.let { columns[column] = (columns[column] ?: 0.0) + it }
                }
            }
            Totals(rows.sumOf { it[RRUN.count].toOptLong() ?: 0 }, columns)
        }

    /**
     * A report counts [form]: its groups hold one form more than [before] the form was made, and each column totaling a
     * field the form gave has risen by what it gave. Held as a rise, so a client with forms already may be walked.
     */
    private fun judgeReports(form: MadeForm?, before: Map<String, Totals>): Outcome {
        if (form == null) return Outcome.notMet("There is no form to count.")
        if (before.isEmpty()) return Outcome.notMet("No report but the starter one.")
        val after = reportTotals()
        val outcomes = view.reports.filter { it[RDEF.reportId].toOptStr() in before }.map { report ->
            val id = report[RDEF.reportId].toOptStr().orEmpty()
            val was = before.getValue(id)
            val now = after.getValue(id)
            val name = "'${report[RDEF.label] ?: id}'"
            // What each totaled column should have risen by: the form's value for the field the column reads.
            val expected = report[RDEF.columns].toJsonListOfMaps().filter { it[RDEF.rollup] == ReportCombine.sum.name }
                .mapNotNull { column ->
                    val field = view.fields.firstOrNull { it.isReadBy(column[RDEF.path].toOptStr().orEmpty()) }
                    val given = field?.let { form.data[it.traitId]?.get(it.name).toOptDoubleOrNull() }
                    given?.let { column[RDEF.columnId].toOptStr().orEmpty() to it }
                }.toMap()
            val rises = expected.mapValues { (column, _) -> (now.columns[column] ?: 0.0) - (was.columns[column] ?: 0.0) }
            val wrong = expected.filter { (column, value) -> abs(rises.getValue(column) - value) > 0.005 }
            when {
                now.count - was.count != 1L -> Outcome.notMet("$name counts ${now.count - was.count} more, not the form made.")
                wrong.isNotEmpty() -> Outcome.notMet(
                    "$name counts the form, but ${wrong.keys.joinToString { "'$it' rose by ${rises[it]}, not ${wrong[it]}" }}.",
                )
                expected.isEmpty() -> Outcome.varied("$name counts the form, and totals no field it gave.")
                else -> Outcome.met("$name counts the form, and ${expected.entries.joinToString { (c, v) -> "'$c' rose by $v" }}.")
            }
        }
        val best = outcomes.minBy { it.kind.ordinal }
        return Outcome(best.kind, outcomes.filter { it.kind == best.kind }.joinToString(" ") { it.detail })
    }

    // --- Home ---

    /** Home as [caller] is served it opens the New form: by a card, or by a menu item. */
    private fun judgeHome(caller: TrialCaller): Outcome {
        val state = caller.getData(HEP.homeUiConfig)[UIC.state].toJsonMapOrEmpty()
        val cards = state[HCARD.cards].toJsonListOfMaps()
        val card = cards.firstOrNull { HMENU.pageNewForm in Checks.cardOpens(it) }
        val menu = state[HFLD.menu].toJsonListOfMaps()
        val item = menu.firstOrNull { it[UIB.action] == HMENU.pageNewForm }
        return when {
            card != null -> Outcome.met("The card '${card[HCARD.title]}' does.")
            item != null -> Outcome.met("The menu item '${item[HFLD.label]}' does.")
            else -> Outcome.notMet(
                "Of the cards ${cards.joinToString { "'${it[HCARD.title] ?: it[HFLD.id]}'" }.ifEmpty { "(none)" }}, " +
                    "and the menu's ${menu.joinToString { "'${it[HFLD.label] ?: it[HFLD.id]}'" }}, none opens it.",
            )
        }
    }

    @Suppress("ConstPropertyName")
    companion object {
        const val applicantRole = "applicant"
        const val reviewerRole = "reviewer"
        const val administratorRole = "administrator"

        /** More steps than a review a trial asks for has: a review that goes on past them is going nowhere. */
        private const val maxReviewSteps = 20
    }
}
