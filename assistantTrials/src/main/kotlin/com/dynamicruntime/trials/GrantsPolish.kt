package com.dynamicruntime.trials

import com.dynamicruntime.common.assistant.ATOOL
import com.dynamicruntime.common.cfact.CFACTS
import com.dynamicruntime.common.counter.CTR
import com.dynamicruntime.common.gedra.CCT
import com.dynamicruntime.common.gedra.workflow.RCF
import com.dynamicruntime.common.gedra.workflow.WFC
import com.dynamicruntime.common.gedra.workflow.WFD
import com.dynamicruntime.common.schema.SCH
import com.dynamicruntime.common.schema.SL
import com.dynamicruntime.common.util.toJsonListOfMaps
import com.dynamicruntime.common.util.toOptLong
import com.dynamicruntime.common.util.toOptStr

/**
 * The second trial (issue #1916): changes to a small grant program that already has its New form, core details, review,
 * Home card, and report, written as its [Trial.start] -- what [GrantsBasics]' explicit variant made in a run that met
 * every expectation. Its prompts reach what the first trial never does: the forms list's columns, Design View's copy
 * and headings, a change to a trait the assistant did not just make, a step of the review only a reviewer saves with
 * the lock that goes with it, and a counter. The change to earlier work and the lock matter most: both are where a
 * client's own use will press, and both can be wrong in silence.
 */
@Suppress("ConstPropertyName")
object GrantsPolish {
    private val scoreTerms = listOf("score", "rating")
    private val noteTerms = listOf("note", "comment", "feedback", "remark")

    /** The categories the start has, which adding one must keep. */
    private val startCategories = setOf("arts", "environment", "education", "health")

    /** What says the most a grant gives, however it is written. */
    private val upTo = Regex("5,?000|5k\\b", RegexOption.IGNORE_CASE)
    private const val heading = "your project"

    private val designTools = setOf(ATOOL.setFieldCopy, ATOOL.setSharedField, ATOOL.setHeading, ATOOL.setSharedHeading)
    private val traitTools = setOf(ATOOL.designTrait, ATOOL.setSharedField)

    // The prompts, by index: each check of how a request was met reads the calls of its own.
    private const val columnsPrompt = 0
    private const val wordsPrompt = 1
    private const val traitPrompt = 2
    private const val numberPrompt = 4

    private val explicit = TrialVariant(
        "explicit", "Each request in the documentation's own words, saying exactly what is wanted.",
        listOf(
            TrialPrompt("Show the amount requested and the category as columns in the forms list."),
            TrialPrompt(
                "On the New form, say under the amount requested that we give up to $5,000, and give the part of the form " +
                    "it is in the heading Your project.",
            ),
            TrialPrompt("Add Housing to the categories, and stop requiring the project's start date.", newConversation = false),
            TrialPrompt(
                "Before the approval in the Review workflow, have the reviewer give the application a score from 1 to 5 and " +
                    "write a note, which the applicant can read but not change.",
            ),
            TrialPrompt("Number the applications like GR-0042, and show the number in the forms list."),
        ),
    )

    private val loose = TrialVariant(
        "loose", "Each request as a person might say it in passing, leaving the assistant to work out what is meant.",
        listOf(
            TrialPrompt("In the list of applications I'd like to see how much each one asks for and what area it's in."),
            TrialPrompt(
                "People keep asking for more than we can give. Can the form tell them we give up to $5,000, right by the " +
                    "amount? And call that section Your project.",
            ),
            TrialPrompt(
                "Oh, and we fund housing now too. And the start date shouldn't be mandatory anymore.", newConversation = false,
            ),
            TrialPrompt(
                "Reviewers should score each application from 1 to 5 and leave a note for the applicant before approving " +
                    "it. Applicants can see the note, but obviously can't edit it.",
            ),
            TrialPrompt("Give each application a reference number like GR-0042 that we can see in the list."),
        ),
    )

    private fun amount(view: ConfigView): FieldView? = view.fields.firstOrNull { it.mentions(GrantsBasics.amountTerms) && it.isNumber }

    /**
     * The New form says, under the amount, that a grant is at most $5,000: in the field's description or hint as the
     * New form has them, its own words where it has any, else every form's. Varied when only core details says it.
     */
    private fun amountSaysUpTo(r: TrialResult): Outcome {
        val view = r.view
        val field = amount(view) ?: return Outcome.notMet("No amount field. Fields: ${view.fieldList()}.")
        val shared = view.sharedLayout(field)
        fun says(workflow: Map<String, Any?>?): Pair<String, Boolean>? {
            val own = view.layoutOf(workflow, field)
            return listOf(SL.description, SL.hint).firstNotNullOfOrNull { key ->
                val ownText = view.entryFor(own, field)?.get(key).toOptStr()
                    ?.let { view.withPulls(it, own?.get(SL.fragmentFileId).toOptStr()) }
                val text = ownText ?: view.entryFor(shared, field)?.get(key).toOptStr()
                    ?.let { view.withPulls(it, shared?.get(SL.fragmentFileId).toOptStr()) }
                    ?: field.schema[SCH.description].toOptStr().takeIf { key == SL.description }
                text?.takeIf { upTo.containsMatchIn(it) }?.let { it to (ownText != null) }
            }
        }
        val onForm = says(view.creation)
        val onDetails = says(view.coreDetails)
        return when {
            onForm != null -> Outcome.met((if (onForm.second) "The New form's own words: " else "Every form's words: ") + onForm.first)
            onDetails != null -> Outcome.varied("Only the core details form says it: ${onDetails.first}")
            else -> Outcome.notMet("Neither the description nor the hint of $field names $5,000 on the New form.")
        }
    }

    /** The New form heads the amount's part "Your project": its own heading for the trait, else every form's. */
    private fun headedYourProject(r: TrialResult): Outcome {
        val view = r.view
        val field = amount(view) ?: return Outcome.notMet("No amount field. Fields: ${view.fieldList()}.")
        fun headingOf(layout: Map<String, Any?>?): String? =
            layout?.get(SL.label).toOptStr()?.let { view.withPulls(it, layout?.get(SL.fragmentFileId).toOptStr()) }
        val shared = headingOf(view.sharedLayout(field))
        val onForm = headingOf(view.layoutOf(view.creation, field)) ?: shared
        val onDetails = headingOf(view.layoutOf(view.coreDetails, field)) ?: shared
        return when {
            onForm?.contains(heading, ignoreCase = true) == true -> Outcome.met("'$onForm'.")
            onDetails?.contains(heading, ignoreCase = true) == true -> Outcome.varied("Only on the core details form: '$onDetails'.")
            else -> Outcome.notMet("The New form heads '${field.traitId}' '${onForm ?: "(no heading)"}'.")
        }
    }

    /** Housing is a category, the start's four are kept, and the New form does not narrow it away. */
    private fun housingAdded(r: TrialResult): Outcome {
        val view = r.view
        val field = view.fields.firstOrNull { it.mentions(GrantsBasics.categoryTerms) && it.options.isNotEmpty() }
            ?: return Outcome.notMet("No category field. Fields: ${view.fieldList()}.")
        val names = field.optionNames
        val lost = startCategories - names
        val narrowed = view.entryFor(view.layoutOf(view.creation, field), field)?.get(SL.choices).toJsonListOfMaps()
            .mapNotNull { it[SL.value].toOptStr() }.takeIf { it.isNotEmpty() }
        return when {
            "housing" !in names -> Outcome.notMet("Its choices: ${names.joinToString()}.")
            lost.isNotEmpty() ->
                Outcome.varied("Housing is added, but ${lost.joinToString()} is gone. Its choices: ${names.joinToString()}.")
            narrowed != null && narrowed.none { it.equals("housing", ignoreCase = true) } ->
                Outcome.varied("Housing is added, but the New form offers only ${narrowed.joinToString()}.")
            else -> Outcome.met("Its choices: ${names.joinToString()}.")
        }
    }

    /** The start date is required neither by its trait nor by a layout of either form or of every form. */
    private fun startDateOptional(r: TrialResult): Outcome {
        val view = r.view
        val field = view.fields.firstOrNull { it.mentions(GrantsBasics.dateTerms) && it.isDate }
            ?: return Outcome.notMet("No start date field: a field made optional is still there. Fields: ${view.fieldList()}.")
        val requiredBy = buildList {
            if (field.required) add("its trait")
            for ((where, layout) in listOf(
                "the New form" to view.layoutOf(view.creation, field), "core details" to view.layoutOf(view.coreDetails, field),
                "every form's layout" to view.sharedLayout(field),
            )) {
                if (view.entryFor(layout, field)?.get(SL.required) == true) add(where)
            }
        }
        return if (requiredBy.isEmpty()) {
            Outcome.met("$field is optional.")
        } else {
            Outcome.notMet("Still required by ${requiredBy.joinToString()}.")
        }
    }

    /**
     * Before the change to the trait, the assistant weighed what it does to the forms the client stores: by `read_impact`,
     * or by a dry run of the change, whose answer says.
     */
    private fun impactWeighed(r: TrialResult): Outcome {
        val calls = r.callsOf(traitPrompt)
        val first = calls.indexOfFirst { it.isChange && it.name in traitTools }
        if (first < 0) return Outcome.info("The trait was not changed with ${traitTools.joinToString()}.")
        val weighed = calls.take(first).firstOrNull {
            !it.isError && (it.name == ATOOL.readImpact || (it.name in traitTools && it.dryRun == true))
        }
        return if (weighed != null) Outcome.met("By ${weighed.name}.") else Outcome.notMet("${calls[first].name} wrote it unweighed.")
    }

    /** A task of the review asking for a score, and the fields of the traits it collects. */
    private class Scoring(val at: Int, val task: Map<String, Any?>, fields: List<FieldView>) {
        val score: FieldView = fields.first { it.mentions(scoreTerms) }
        val note: FieldView? = fields.firstOrNull { it !== score && it.mentions(noteTerms) && it.type == "string" && it.options.isEmpty() }
        val label: String get() = "'${task[WFD.label] ?: task[WFD.id]}'"
        val oneToFive: Boolean get() = (score.isNumber && score.minimum == 1.0 && score.maximum == 5.0) || score.options.size == 5
    }

    private fun scoring(view: ConfigView): Scoring? {
        val tasks = view.review?.get(WFD.tasks).toJsonListOfMaps()
        return tasks.withIndex().firstNotNullOfOrNull { (i, task) ->
            val traits = task[WFD.traits].toJsonListOfMaps().mapNotNull { it[WFD.traitId].toOptStr() }.toSet()
            view.fields.filter { it.traitId in traits }.takeIf { fields -> fields.any { it.mentions(scoreTerms) } }
                ?.let { Scoring(i, task, it) }
        }
    }

    private fun scoringStep(r: TrialResult): Outcome {
        val view = r.view
        val review = view.review ?: return Outcome.notMet("No workflow with an approval task.")
        val s = scoring(view) ?: return Outcome.notMet("No task of '${review[WFD.label] ?: review[WFD.workflowId]}' asks for a score.")
        val approvalAt = review[WFD.tasks].toJsonListOfMaps().indexOfFirst { it[WFD.approval] != null }
        val lacks = listOfNotNull(
            "it comes after the approval".takeIf { s.at > approvalAt },
            "the score is ${s.score.kind}, not 1 to 5".takeIf { !s.oneToFive },
            "it asks for no note".takeIf { s.note == null },
        )
        val asks = "${s.label} asks for ${s.score}" + (s.note?.let { " and $it" }.orEmpty())
        return if (lacks.isEmpty()) Outcome.met("$asks.") else Outcome.varied("$asks, but ${lacks.joinToString(", and ")}.")
    }

    /** The scoring task's `saveWhen` admits a reviewer and not the applicant, as its facts read for each. */
    private fun reviewerOnly(r: TrialResult): Outcome {
        val s = scoring(r.view) ?: return Outcome.notMet("No task asks for a score.")
        val saveWhen = s.task[WFD.saveWhen].toOptStr() ?: return Outcome.notMet("${s.label} says nothing of who may save it.")
        return when {
            Checks.holds(saveWhen, setOf(RCF.isOwner, CFACTS.loggedIn)) -> Outcome.notMet("Its saveWhen '$saveWhen' admits the applicant.")
            !Checks.holds(saveWhen, setOf(WFC.reviewer, CFACTS.loggedIn)) -> Outcome.varied("Its saveWhen '$saveWhen' admits no reviewer.")
            else -> Outcome.met("${s.label} is saved when $saveWhen.")
        }
    }

    /** The review locks the scoring task's trait, writable by that task's savers. */
    private fun scoreLocked(r: TrialResult): Outcome {
        val s = scoring(r.view) ?: return Outcome.notMet("No task asks for a score.")
        val locks = r.view.review?.get(WFD.locks).toJsonListOfMaps()
        val on = locks.filter { it[WFD.traitId] == s.score.traitId }
        val lock = on.firstOrNull { it[WFD.writableVia] == s.task[WFD.id] }
        return when {
            lock != null -> Outcome.met(
                "'${s.score.traitId}' is locked when ${lock[WFD.kWhen]}" +
                    lock[WFD.overrideWhen]?.let { ", overridden when $it" }.orEmpty() + ".",
            )
            on.isNotEmpty() ->
                Outcome.varied("'${s.score.traitId}' is locked, writable via ${on.joinToString { "'${it[WFD.writableVia]}'" }}.")
            else -> Outcome.notMet("No lock on '${s.score.traitId}'.")
        }
    }

    /** A counter whose ids read like GR-0042: the prefix `GR-`, and four digits. */
    private fun counterLikeGr(r: TrialResult): Outcome {
        val counters = r.view.counters
        fun sample(c: Map<String, Any?>): String =
            c[CTR.prefix].toOptStr().orEmpty() + "42".padStart(c[CTR.digits].toOptLong()?.toInt() ?: 0, '0')
        val like = counters.firstOrNull { sample(it) == "GR-0042" }
        return when {
            like != null -> Outcome.met("'${like[CTR.counterId]}' gives ids like ${sample(like)}.")
            counters.isNotEmpty() -> Outcome.varied(counters.joinToString { "'${it[CTR.counterId]}' gives ids like ${sample(it)}" } + ".")
            else -> Outcome.notMet("No counter.")
        }
    }

    /** A field takes its ids from one of the client's counters, in a trait the New form collects. */
    private fun numberedOnNewForm(r: TrialResult): Outcome {
        val view = r.view
        val counters = view.counters.mapNotNull { it[CTR.counterId].toOptStr() }.toSet()
        val numbered = view.fields.filter { it.counter != null }
        val onForm = view.collected(view.creation)
        val good = numbered.firstOrNull { it.counter in counters && it.traitId in onForm }
        return when {
            good != null -> Outcome.met("$good, from '${good.counter}'.")
            numbered.any { it.counter in counters } ->
                Outcome.varied("${numbered.first { it.counter in counters }}, but the New form does not collect it.")
            numbered.isNotEmpty() ->
                Outcome.varied("${numbered.first()} names '${numbered.first().counter}', which is no counter of the client's.")
            else -> Outcome.notMet("No field takes its ids from a counter.")
        }
    }

    val trial = Trial(
        "grantsPolish",
        "Changes to a grant program that has a New form, a review, a Home card, and a report: columns in the forms list, the " +
            "New form's words and heading, a category added and a field made optional, a reviewer's score that the applicant " +
            "reads and cannot change, and numbered applications.",
        "Riverbend Grants",
        listOf(explicit, loose),
        listOf(
            Expectation("The forms list shows the amount") { r ->
                Checks.formsListShows(r, "an amount, as a number") { it.mentions(GrantsBasics.amountTerms) && it.isNumber }
            },
            Expectation("The forms list shows the category") { r ->
                Checks.formsListShows(r, "a category, as a choice") { it.mentions(GrantsBasics.categoryTerms) && it.options.isNotEmpty() }
            },
            Expectation("The forms list still shows each form's name") { r -> Checks.keepsNameColumn(r) },
            Expectation("The columns are made with save_forms_list_column") { r ->
                Checks.madeWith(r, columnsPrompt, setOf(ATOOL.saveFormsListColumn), setOf(CCT.usageDef))
            },
            Expectation("The New form says under the amount that a grant is at most $5,000") { r -> amountSaysUpTo(r) },
            Expectation("The New form heads the amount's part \"Your project\"") { r -> headedYourProject(r) },
            Expectation("The words and the heading are made with Design View's tools") { r ->
                Checks.madeWith(r, wordsPrompt, designTools, setOf(CCT.workflowDef, CCT.traitDef))
            },
            Expectation("Housing is a category, and the others are kept") { r -> housingAdded(r) },
            Expectation("The start date is no longer required") { r -> startDateOptional(r) },
            Expectation("The trait is changed with design_trait or set_shared_field, not patched") { r ->
                Checks.madeWith(r, traitPrompt, traitTools, setOf(CCT.traitDef))
            },
            Expectation("The change to the trait is weighed against the stored forms first") { r -> impactWeighed(r) },
            Expectation("A step before the approval asks for a score of 1 to 5 and a note") { r -> scoringStep(r) },
            Expectation("Only a reviewer may save the scoring step") { r -> reviewerOnly(r) },
            Expectation("A lock keeps the score's trait to the scoring step") { r -> scoreLocked(r) },
            Expectation("A counter gives ids like GR-0042") { r -> counterLikeGr(r) },
            Expectation("The New form numbers each application from it") { r -> numberedOnNewForm(r) },
            Expectation("The forms list shows the number") { r ->
                Checks.formsListShows(r, "numbered by a counter") { it.counter != null }
            },
            Expectation("The number's column is made with save_forms_list_column") { r ->
                Checks.madeWith(r, numberPrompt, setOf(ATOOL.saveFormsListColumn), setOf(CCT.usageDef))
            },
        ),
        WalkStep.entries,
        TrialStart.fromResource("/trialStarts/grantsPolish.json"),
    )
}
