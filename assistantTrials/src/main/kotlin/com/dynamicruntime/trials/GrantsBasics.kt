package com.dynamicruntime.trials

import com.dynamicruntime.common.gedra.report.RDEF
import com.dynamicruntime.common.gedra.workflow.WFD
import com.dynamicruntime.common.home.HCARD
import com.dynamicruntime.common.home.HFRAG
import com.dynamicruntime.common.util.toJsonListOfMaps
import com.dynamicruntime.common.util.toJsonMapOrEmpty
import com.dynamicruntime.common.util.toJsonStr
import com.dynamicruntime.common.user.AFRAG
import com.dynamicruntime.common.user.MAILNS
import com.dynamicruntime.common.util.toOptStr

/**
 * The first trial (issue #1859): a small grant program built from a blank self-serve client -- a New form collecting a
 * few typed fields, a review workflow with an approval, the program's own words on Home and in the invitation mail, a
 * Home card to apply from, and a report. Together they cover most of what the making-changes guide lists.
 */
object GrantsBasics {
    /** The program's name, which the copy checks look for. */
    private const val program = "Riverbend"

    private val amountTerms = listOf("amount", "budget", "dollar", "request", "fund", "asked")
    private val dateTerms = listOf("start", "date", "kickoff", "begin")
    private val categoryTerms = listOf("category", "area", "focus", "type", "field")
    private val descriptionTerms = listOf("description", "narrative", "summary", "about", "project", "purpose")

    private val explicit = TrialVariant(
        "explicit", "Each request in the documentation's own words, saying exactly what is wanted.",
        listOf(
            TrialPrompt(
                "We run the Riverbend Community Grants program. Change the New form so that an applicant gives: the amount " +
                    "requested in dollars (a number), the project's start date (a date), a category chosen from Arts, " +
                    "Environment, Education, and Health, and a description of the project, which may run to several " +
                    "paragraphs. Keep the form's name.",
            ),
            TrialPrompt(
                "Add a workflow called Review that a submitted application goes through: one approval task, in which a " +
                    "reviewer who is not the applicant approves the application. The approval should show the applicant's " +
                    "answers.",
            ),
            TrialPrompt(
                "Reword Home's introduction, the line under its title, to say: Apply for a Riverbend Community Grant, and " +
                    "follow your application through review. Also change the invitation mail's subject so it names " +
                    "Riverbend Community Grants.",
            ),
            TrialPrompt("Add a card to Home that takes an applicant straight to starting a new application."),
            TrialPrompt(
                "Make a report of the applications grouped by category, showing how many there are in each and the total " +
                    "amount requested.",
            ),
        ),
    )

    private val loose = TrialVariant(
        "loose", "Each request as a person might say it in passing, leaving the assistant to work out what is meant.",
        listOf(
            TrialPrompt(
                "We give out small community grants as Riverbend Community Grants. I want people to apply saying how much " +
                    "they need, when the project starts, what area it's in, and what it's about.",
            ),
            TrialPrompt("Applications should get looked at by someone before they're approved."),
            TrialPrompt("Make the site and its emails feel like they're ours: Riverbend Community Grants."),
            TrialPrompt("People should be able to find where to apply from the home page."),
            TrialPrompt("I'd like to see totals by area."),
        ),
    )

    private val ownWords = TrialVariant(
        "ownWords", "Each request in the organization's own terms (intake sheet, submission, sign-off, landing page, tile).",
        listOf(
            TrialPrompt(
                "Our intake sheet for Riverbend Community Grants needs: dollars asked for, kickoff date, a focus area (Arts, " +
                    "Environment, Education, or Health), and a project narrative that can be long.",
            ),
            TrialPrompt("Each submission needs sign-off from a panelist, never the submitter, before it is accepted."),
            TrialPrompt(
                "On the landing page, the tagline should read: Apply for a Riverbend Community Grant, and follow your " +
                    "submission through sign-off. The welcome email's subject line should say Riverbend Community Grants.",
            ),
            TrialPrompt("Put a tile on the landing page that takes submitters straight to a new intake sheet."),
            TrialPrompt("I want a breakdown of submissions per focus area, with the dollars asked for added up."),
        ),
    )

    val trial = Trial(
        "grantsBasics",
        "A small grant program from a blank client: a New form with typed fields, a review with an approval, the program's " +
            "own words on Home and in the invitation mail, a Home card to apply from, and a report by category.",
        "Riverbend Grants",
        listOf(explicit, loose, ownWords),
        listOf(
            Expectation("The New form asks for an amount, as a number") { r ->
                Checks.newFormField(r, "a number", amountTerms) { it.isNumber }
            },
            Expectation("The New form asks for a start date, as a date") { r ->
                Checks.newFormField(r, "a date", dateTerms) { it.isDate }
            },
            Expectation("The New form asks for a category, as a choice of the four") { r ->
                Checks.newFormField(r, "a choice of four", categoryTerms) { it.options.size >= 4 }
            },
            Expectation("The New form asks for a description, as long text") { r ->
                Checks.newFormField(r, "long text", descriptionTerms) { it.longText }
            },
            Expectation("The core details workflow collects what the New form does") { r -> Checks.coreDetailsMatchesNewForm(r) },
            Expectation("A review workflow with an approval task") { r ->
                val workflows = r.view.normalWorkflows
                val approving = workflows.firstOrNull { wf -> wf[WFD.tasks].toJsonListOfMaps().any { it[WFD.approval] != null } }
                when {
                    approving != null -> {
                        val approval = approving[WFD.tasks].toJsonListOfMaps().first { it[WFD.approval] != null }[WFD.approval]
                            .toJsonMapOrEmpty()
                        val who = approval[WFD.approveWhen].toOptStr()?.let { "approved when $it" }
                            ?: "approved by a reviewer who does not own the form"
                        Outcome.met("'${approving[WFD.label] ?: approving[WFD.workflowId]}', $who.")
                    }
                    workflows.isNotEmpty() -> Outcome.varied(
                        "No approval task in ${workflows.joinToString { "'${it[WFD.label] ?: it[WFD.workflowId]}'" }}.",
                    )
                    else -> Outcome.notMet("No workflow beyond the New form and core details.")
                }
            },
            Expectation("Home's introduction names the program") { r -> Checks.copyMentions(r, HFRAG.home, null, program) },
            Expectation("The invitation mail names the program") { r -> Checks.copyMentions(r, AFRAG.mail, MAILNS.invitation, program) },
            Expectation("A Home card to start an application") { r ->
                val cards = r.view.homeCards.filter { it[HCARD.title] != null }
                if (cards.isEmpty()) {
                    Outcome.notMet("No card added or reworded on Home." + r.view.homeCards.takeIf { it.isNotEmpty() }
                        ?.let { " Its overlay: ${it.joinToString { c -> cardSummary(c) }}." }.orEmpty())
                } else {
                    Outcome.met(cards.joinToString("; ") { cardSummary(it) })
                }
            },
            Expectation("A report by category, counting and totaling the amount") { r ->
                val reports = r.view.reports
                val good = reports.firstOrNull { report ->
                    val text = report.toJsonStr(compact = true).lowercase()
                    val sums = report[RDEF.columns].toJsonListOfMaps().any { it[RDEF.rollup] == "sum" || it[RDEF.combine] == "sum" }
                    categoryTerms.any { text.contains(it) } && sums
                }
                when {
                    good != null -> Outcome.met(reportSummary(good))
                    reports.isNotEmpty() -> Outcome.varied(reports.joinToString("; ") { reportSummary(it) })
                    else -> Outcome.notMet("No report.")
                }
            },
        ),
    )

}
