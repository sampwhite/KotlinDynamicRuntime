package com.dynamicruntime.kdn.confdemo

import com.dynamicruntime.common.content.MarkdownDocDef
import com.dynamicruntime.common.context.ENV
import com.dynamicruntime.common.context.KdrCxt
import com.dynamicruntime.common.context.KdrCxtBase
import com.dynamicruntime.common.context.LiteCxt
import com.dynamicruntime.common.endpoint.SchModule
import com.dynamicruntime.common.endpoint.schemaModule
import com.dynamicruntime.common.gedra.ClientAudience
import com.dynamicruntime.common.gedra.ClientDef
import com.dynamicruntime.common.gedra.ClientUsageType
import com.dynamicruntime.common.gedra.GT
import com.dynamicruntime.common.gedra.report.RMETA
import com.dynamicruntime.common.gedra.report.RUSR
import com.dynamicruntime.common.gedra.report.RWF
import com.dynamicruntime.common.gedra.report.ReportCombine
import com.dynamicruntime.common.gedra.report.ReportSource
import com.dynamicruntime.common.gedra.GedraDataType
import com.dynamicruntime.common.gedra.traitDataTypeName
import com.dynamicruntime.common.gedra.GedraConfig
import com.dynamicruntime.common.gedra.gedraConfig
import com.dynamicruntime.common.cfact.CFACTS
import com.dynamicruntime.common.gedra.workflow.SVY
import com.dynamicruntime.common.gedra.workflow.WFC
import com.dynamicruntime.common.gedra.workflow.WSC
import com.dynamicruntime.common.gedra.workflow.WfEntry
import com.dynamicruntime.common.gedra.workflow.WfSaveKind
import com.dynamicruntime.common.gedra.workflow.computeCFactsFromData
import com.dynamicruntime.common.gedra.workflow.userHasLabel
import com.dynamicruntime.common.home.HFLD
import com.dynamicruntime.common.home.HMENU
import com.dynamicruntime.common.home.menuItem
import com.dynamicruntime.common.http.request.ROLE
import com.dynamicruntime.common.naming.clientNamespace
import com.dynamicruntime.common.schema.SCH
import com.dynamicruntime.common.schema.SCT
import com.dynamicruntime.common.schema.SchLayoutBuilder
import com.dynamicruntime.common.schema.SchLayoutChoice
import com.dynamicruntime.common.schema.SchLayoutMode
import com.dynamicruntime.common.simulation.SimulationReport
import com.dynamicruntime.common.simulation.Simulations
import com.dynamicruntime.common.simulation.simulationEndpoint
import com.dynamicruntime.common.test.SIM
import com.dynamicruntime.common.util.toOptStr
import com.dynamicruntime.common.gedra.ClientJoinRole
import com.dynamicruntime.common.user.PERSONA

/*
 * The conference demo (issue #1134): a small conference's call for talks, built from configuration alone, and the
 * subject of the developer documentation in `docs/demo-client/`. The documentation shows the JSON of each bundle here
 * and walks through it, and `ConfDemoDocsTest` holds the two to each other -- so a change here that changes a bundle
 * fails that test until the page is changed with it. Write the page first, then the definition that produces it: the
 * JSON is shaped for a reader.
 *
 * It is defined in data, as a customer's client is: written as stored bundles by whatever provisions it -- the
 * `conf-demo` simulation on a test instance, or a test in-process -- rather than compiled into a component, so what a
 * reader reads back from a running node is what the pages show.
 *
 * This is also the fullest worked example of a client definition in the DSL; the `kdr-test-clients` skill points here.
 */

/** The demo's names: the client, its bundles, and what the documentation and its test address. */
@Suppress("ConstPropertyName")
object ConfDemo {
    const val client = "confdemo"

    /** The simulation that provisions it: `/fixture/simulate/conf-demo`. */
    const val simulationName = "conf-demo"

    /** The bundle holding the client's definition: who the client is, and the shared traits it takes. */
    const val baseConfig = "base"

    /** The bundle holding the creation workflow -- one bundle per workflow, so each is read and written on its own. */
    const val createTalkConfig = "createTalk"

    /** The bundle holding the survey (issue #1138). */
    const val talkSurveyConfig = "talkSurvey"

    /** The three normal workflows' bundles (issue #1139). */
    const val abstractConfig = "abstract"
    const val reviewConfig = "review"
    const val logisticsConfig = "logistics"
    const val reportsConfig = "reports"

    /** The fragment file `kdn` ships for the demo's copy (issue #1139). */
    const val fragmentFile = "confDemo"

    const val createTalkWorkflow = "createTalk"
    const val nameTalkTask = "nameTalk"
    const val createSave = "create"

    const val talkSurveyWorkflow = "talkSurvey"
    const val describeTask = "describe"
    const val saveAbout = "saveAbout"

    const val abstractWorkflow = "abstract"
    const val writeTask = "write"
    const val saveAbstract = "saveAbstract"

    const val reviewWorkflow = "review"
    const val recordReviewTask = "recordReview"
    const val saveReview = "saveReview"
    const val acceptTask = "accept"

    const val logisticsWorkflow = "logistics"
    const val planTask = "plan"
    const val savePlan = "savePlan"

    // The reports (issue #1140), and the column ids the pages refer to.
    const val submissionsByTrack = "submissionsByTrack"
    const val questionnaireSummary = "questionnaireSummary"
    const val trackColumn = "track"
    const val minutesColumn = "minutes"
    const val audienceColumn = "audience"
    const val recordingColumn = "recording"

    /** The client's own cfacts: a talk's track is the workshop track; the committee accepted the talk. */
    const val isWorkshop = "isWorkshop"
    const val talkAccepted = "talkAccepted"
    const val cfactGroup = "confDemo"

    /** The label an administrator carries to review talks. */
    const val committeeLabel = "committee"

    // The roles a visitor joins the demo as (issue #1248).
    const val speakerRole = "speaker"
    const val organizerRole = "organizer"
    const val committeeRole = "committee"

    /** A committee member's user is an administrator told apart from an organizer by this suffix (at most 8 characters). */
    const val committeeSuffix = "review"

    // The client's own traits, and their fields.
    const val session = "session"
    const val sessionEntry = "SessionEntry"
    const val track = "track"
    const val workshopTrack = "workshop"

    const val questionnaire = "questionnaire"
    const val questionnaireEntry = "QuestionnaireEntry"
    const val firstTalk = "firstTalk"
    const val audienceLevel = "audienceLevel"
    const val takeaway = "takeaway"
    const val recordingOk = "recordingOk"
    const val needs = "needs"

    const val speakerType = "Speaker"
    const val abstract = "abstract"
    const val abstractEntry = "AbstractEntry"
    const val summary = "summary"
    const val durationMinutes = "durationMinutes"
    const val topics = "topics"
    const val coSpeakers = "coSpeakers"
    const val programNote = "programNote"

    const val review = "review"
    const val reviewEntry = "ReviewEntry"
    const val score = "score"
    const val notes = "notes"

    const val logistics = "logistics"
    const val logisticsEntry = "LogisticsEntry"
    const val room = "room"
    const val needsLaptops = "needsLaptops"
    const val setup = "setup"

    /** Where the documentation lives in the repository, and the prefix its pages take as doc ids. */
    const val docsDir = "docs/demo-client"
    const val docIdPrefix = "demo-client-"

    /**
     * The instance-config key that has the demo provisioned as the node starts (issue #1221), by
     * [ConfDemoBootService]. The `reference` deployment flavor sets it.
     */
    const val provisionAtBootKey = "confDemoProvisionAtBoot"

    /** The demo's workflows, the ones a workflow page shows help for (issue #1221). */
    val workflows = listOf(createTalkWorkflow, talkSurveyWorkflow, abstractWorkflow, reviewWorkflow, logisticsWorkflow)
}

/**
 * The demo's documentation (issue #1136): the opening page is listed on the home page's Documents list; the pages it
 * leads to are linked from it and not listed. The build copies `docs/demo-client/<name>.md` onto the classpath as
 * `demo-client-<name>.md`, so the ids here follow the file names.
 */
fun confDemoDocs(): List<MarkdownDocDef> = listOf(
    confDemoDoc("README", "Conference demo", "A worked example of a client built from configuration: its JSON, piece by piece.", listed = true),
    confDemoDoc("client", "Conference demo: client", "The demo's client definition."),
    confDemoDoc("create-talk", "Conference demo: creating a talk", "The demo's creation workflow."),
    confDemoDoc("talk-survey", "Conference demo: talk survey", "The demo's survey, its first traits, and the columns of its forms list."),
    confDemoDoc("abstract", "Conference demo: the abstract", "The demo's first normal workflow: a trait with a list, a multi-select, trimming, and custom errors."),
    confDemoDoc("committee-review", "Conference demo: committee review", "A workflow gated on the survey, with a field only administrators see and a review task."),
    confDemoDoc("workshop-logistics", "Conference demo: workshop logistics", "A workflow gated on a computed fact, with a layout that narrows and relabels, and copy from a file."),
    confDemoDoc("reports", "Conference demo: reports", "The demo's two reports: a roster of the talks by track, and a summary of the questionnaire."),
    confDemoDoc("bundles", "Conference demo: every bundle", "The full JSON of every bundle the demo writes, in one place."),
    confDemoDoc("reference", "Conference demo: reference", "What the demo's pages have in common: comments, choices, field layouts, report paths."),
)

private fun confDemoDoc(name: String, label: String, description: String, listed: Boolean = false) =
    MarkdownDocDef(ConfDemo.docIdPrefix + name, label, "${ConfDemo.docsDir}/$name.md", description, listed)

/**
 * The `base` bundle: the client itself, and the shared trait its forms are named by. [client] is [ConfDemo.client],
 * or that plus a suffix for a fresh copy; the documentation shows the unsuffixed one.
 */
fun confDemoBase(cxt: KdrCxtBase = LiteCxt(), client: String = ConfDemo.client): GedraConfig =
    gedraConfig(cxt, ConfDemo.baseConfig, clientNamespace(client), client) {
        defineClient(
            ClientDef(
                clientId = client,
                name = "Conference demo",
                description = "A demo client: a conference's call for talks. Documented in docs/demo-client.",
                usageType = ClientUsageType.demo,
                audience = ClientAudience.internal,
                enabledEnvironments = setOf(ENV.unit, ENV.local, ENV.dev),
                includedTraits = listOf(GT.name),
                // Whoever carries it reviews talks (issue #1139).
                userLabels = listOf(ConfDemo.committeeLabel),
                // The demo's three roles, which a visitor to a reference deployment may join as (issue #1248) -- the
                // speaker, organizer and committee member the walkthrough in docs/demo-client follows.
                joinRoles = listOf(
                    ClientJoinRole(
                        name = ConfDemo.speakerRole,
                        label = "Speaker",
                        persona = PERSONA.member,
                        summary = "Submit a talk, and follow it through the committee's review.",
                    ),
                    ClientJoinRole(
                        name = ConfDemo.organizerRole,
                        label = "Organizer",
                        persona = PERSONA.admin,
                        summary = "See every talk submitted, and run the conference's workflows.",
                    ),
                    ClientJoinRole(
                        name = ConfDemo.committeeRole,
                        label = "Committee member",
                        persona = PERSONA.admin,
                        personaSuffix = ConfDemo.committeeSuffix,
                        userLabels = listOf(ConfDemo.committeeLabel),
                        summary = "Review the talks whose survey is complete, and accept them.",
                    ),
                ),
            ),
        )
        cfact(ConfDemo.isWorkshop, ConfDemo.cfactGroup, "The talk's track is the workshop track; computed by the survey from the session.")
        cfact(ConfDemo.talkAccepted, ConfDemo.cfactGroup, "True once the committee has accepted the talk.")

        // The workflow pages are off in the shipped menu; a client with normal workflows turns them on (issue #792).
        uiBlockOverlay(HMENU.block) {
            items(HFLD.menu) {
                menuItem(HMENU.workflows, cfactExpression = "${CFACTS.loggedIn},${CFACTS.app}")
            }
        }

        // A shared type (issue #1139): declared once, used by the abstract's list of co-speakers.
        type(ConfDemo.speakerType) {
            type = SCT.kObject
            property("name", "The co-speaker's name.", required = true) { title = "Name" }
            property("email", "Where to reach them.") { title = "Email" }
        }

        // The client's own traits (issue #1138). Each workflow page shows the traits that workflow introduces, so a
        // trait is appended here, never inserted: the pages address each by its place in the bundle.
        trait(ConfDemo.sessionEntry, ConfDemo.session, setOf(GedraDataType.formDoc), "Where a talk fits in the program.") {
            property(ConfDemo.track, "The part of the program the talk belongs to.", required = true) {
                title = "Track"
                option("kotlin", "Kotlin")
                option("web", "The web")
                option("data", "Data")
                option(ConfDemo.workshopTrack, "Workshop (hands-on)")
            }
        }
        trait(ConfDemo.questionnaireEntry, ConfDemo.questionnaire, setOf(GedraDataType.formDoc), "Five questions every speaker answers.") {
            // Optional in the data: each workflow that asks a question decides whether its own form requires it.
            property(ConfDemo.firstTalk, "Whether this is the speaker's first talk at a conference.") {
                title = "Is this your first conference talk?"
                type = SCT.boolean
            }
            property(ConfDemo.audienceLevel, "How much the audience needs to know already.") {
                title = "Who is the talk for?"
                option("beginner", "Beginners")
                option("intermediate", "People with some experience")
                option("advanced", "Experts")
            }
            property(ConfDemo.takeaway, "What the audience leaves knowing.") { title = "What will the audience take away?" }
            property(ConfDemo.recordingOk, "Whether the talk may be recorded and published.") {
                title = "May we record the talk?"
                type = SCT.boolean
            }
            property(ConfDemo.needs, "Anything the organizers should know for the day.") { title = "Anything we should know for the day?" }
        }

        trait(ConfDemo.abstractEntry, ConfDemo.abstract, setOf(GedraDataType.formDoc), "What the talk is about.") {
            property(ConfDemo.summary, "The abstract the program will print.", required = true) {
                title = "Summary"
                minLength = 50
                maxLength = 600
                trimmed()
                errors {
                    missingRequired("Every talk needs a summary.")
                    belowMinimum("A summary is at least 50 characters; give the audience a reason to come.")
                    aboveMaximum("Keep the summary under 600 characters; the program has limited space.")
                }
            }
            property(ConfDemo.durationMinutes, "How long the talk runs, in minutes.") {
                title = "Duration (minutes)"
                type = SCT.integer
                minimum = 20
                maximum = 90
            }
            property(ConfDemo.topics, "The topics the talk covers; choose up to three.") {
                title = "Topics"
                type = SCT.array
                uniqueItems = true
                maxItems = 3
                items {
                    option("coroutines", "Coroutines")
                    option("multiplatform", "Multiplatform")
                    option("compose", "Compose")
                    option("testing", "Testing")
                    option("tooling", "Tooling")
                }
            }
            property(ConfDemo.coSpeakers, "Anyone presenting alongside the speaker.") {
                title = "Co-speakers"
                type = SCT.array
                items { ref(ConfDemo.speakerType) }
            }
            // Left off the speaker's form (issue #564): shown only to a caller with an administrator's level.
            property(ConfDemo.programNote, "Scheduling notes for the organizers; the speaker's form leaves this out.") {
                title = "Organizer's note"
                visibleWhen = CFACTS.hasAdminLevel
            }
        }
        trait(ConfDemo.reviewEntry, ConfDemo.review, setOf(GedraDataType.formDoc), "The committee's review of the talk.") {
            property(ConfDemo.score, "The committee's score, from 1 to 5.") {
                title = "Score"
                type = SCT.integer
                minimum = 1
                maximum = 5
            }
            property(ConfDemo.notes, "Feedback the speaker will see.") { title = "Notes for the speaker" }
        }
        trait(ConfDemo.logisticsEntry, ConfDemo.logistics, setOf(GedraDataType.formDoc), "What a workshop needs on the day.") {
            property(ConfDemo.room, "The room the session is in.") {
                title = "Room"
                option("hall", "Main hall")
                option("track1", "Track room 1")
                option("track2", "Track room 2")
                option("labA", "Lab A")
                option("labB", "Lab B")
            }
            property(ConfDemo.needsLaptops, "Whether attendees bring a laptop.") {
                title = "Do attendees need their own laptop?"
                type = SCT.boolean
            }
            property(ConfDemo.setup, "What attendees install before they arrive.") { title = "What should they install beforehand?" }
            presentWhen(ConfDemo.setup, on = ConfDemo.needsLaptops, value = true)
        }

        // The forms listing's columns (issue #1138). A client that declares any replaces the default name column, so
        // the name is declared here too.
        traitUsage(GT.name, "Talk", $$"${name}", substring = true)
        traitUsage(ConfDemo.session, "Track", $$"${track}")
    }

/** The `createTalk` bundle: the workflow that runs when a speaker starts a submission. */
fun confDemoCreateTalk(cxt: KdrCxtBase = LiteCxt(), client: String = ConfDemo.client): GedraConfig =
    gedraConfig(cxt, ConfDemo.createTalkConfig, clientNamespace(client), client) {
        workflow(ConfDemo.createTalkWorkflow, WfEntry.creation) {
            comment = "Runs once, when a speaker starts a submission. It asks only for the title; the rest comes later."
            label = "Submit a talk"
            task(ConfDemo.nameTalkTask, "Name your talk") {
                comment = "A creation workflow has exactly one task, with exactly one save."
                trait(GT.name)
                save(ConfDemo.createSave, "Create the talk", WfSaveKind.create)
            }
        }
    }

/**
 * The `talkSurvey` bundle (issue #1138): the survey, which collects what every talk needs before it is reviewed. It asks
 * one of the questionnaire's questions; the abstract workflow asks the rest.
 */
fun confDemoTalkSurvey(cxt: KdrCxtBase = LiteCxt(), client: String = ConfDemo.client): GedraConfig =
    gedraConfig(cxt, ConfDemo.talkSurveyConfig, clientNamespace(client), client) {
        workflow(ConfDemo.talkSurveyWorkflow, WfEntry.survey) {
            comment = "The form's survey: what every talk needs before it is reviewed."
            label = "About your talk"
            alterType(
                "${clientNamespace(client)}.${traitDataTypeName(ConfDemo.questionnaireEntry)}",
                mapOf(
                    SCH.layout to SchLayoutBuilder(
                        null,
                        mode = SchLayoutMode.authoritative,
                        comment = "The survey asks one of the five questions. The abstract workflow asks the rest.",
                    ).apply { field(ConfDemo.firstTalk) }.build(),
                ),
            )
            task(ConfDemo.describeTask, "Track and background") {
                trait(ConfDemo.session)
                trait(ConfDemo.questionnaire)
                save(ConfDemo.saveAbout, "Save", WfSaveKind.edit)
            }
            // A fact computed from the data (issue #1139): the logistics workflow is for workshops only, and its
            // eligibility test asks for this fact.
            function(computeCFactsFromData {
                trait = ConfDemo.session
                valuePath = ConfDemo.track
                map(ConfDemo.workshopTrack, ConfDemo.isWorkshop)
            })
        }
    }

/** The `abstract` bundle (issue #1139): the first normal workflow, open to every talk. */
fun confDemoAbstract(cxt: KdrCxtBase = LiteCxt(), client: String = ConfDemo.client): GedraConfig =
    gedraConfig(cxt, ConfDemo.abstractConfig, clientNamespace(client), client) {
        workflow(ConfDemo.abstractWorkflow, WfEntry.normal) {
            comment = "No eligibility tests: any talk can have its abstract written at any time."
            label = "Write the abstract"
            alterType(
                "${clientNamespace(client)}.${traitDataTypeName(ConfDemo.questionnaireEntry)}",
                mapOf(
                    SCH.layout to SchLayoutBuilder(
                        null,
                        mode = SchLayoutMode.authoritative,
                        comment = "The four questions the survey left out.",
                    ).apply {
                        field(ConfDemo.audienceLevel)
                        field(ConfDemo.takeaway)
                        field(ConfDemo.recordingOk)
                        field(ConfDemo.needs)
                    }.build(),
                ),
            )
            task(ConfDemo.writeTask, "The abstract and the rest of the questionnaire") {
                trait(ConfDemo.abstract)
                trait(ConfDemo.questionnaire)
                save(ConfDemo.saveAbstract, "Save the abstract", WfSaveKind.edit)
            }
        }
    }

/** The `review` bundle (issue #1139): the committee's review, open once the survey is complete. */
fun confDemoReview(cxt: KdrCxtBase = LiteCxt(), client: String = ConfDemo.client): GedraConfig =
    gedraConfig(cxt, ConfDemo.reviewConfig, clientNamespace(client), client) {
        workflow(ConfDemo.reviewWorkflow, WfEntry.normal) {
            comment = "The committee reviews a talk once its survey is complete, then accepts it."
            label = "Committee review"
            eligibility("surveyDone", SVY.surveyComplete, "The talk's survey has to be complete before the committee reviews it.", comment = "Reviewers need the track.")
            task(ConfDemo.recordReviewTask, "Record the review") {
                comment = "Only a committee member may save this task; the speaker sees it read-only."
                trait(ConfDemo.review)
                save(ConfDemo.saveReview, "Save the review", WfSaveKind.edit)
                function(userHasLabel { label = ConfDemo.committeeLabel })
                saveWhen(WFC.reviewer)
            }
            task(ConfDemo.acceptTask, "Accept the talk") {
                approval(ConfDemo.talkAccepted, "Read the review, then accept the talk for the program.", "Accept the talk")
                function(userHasLabel { label = ConfDemo.committeeLabel })
                display {
                    whenCfacts(ConfDemo.talkAccepted) { text($$"Accepted by ${approvedByName}.") }
                    whenCfacts("~${WFC.isCta}") {
                        text("The review has to be recorded before the talk can be accepted.")
                        disabled = true
                    }
                    whenCfacts(WFC.reviewer) { defaultRendering() }
                    otherwise { text("A committee member accepts the talk.") }
                }
            }
            singleton(WSC.finished, ConfDemo.talkAccepted)
            // A save rule alone could be bypassed through the raw editor, so the platform insists on a lock to go with
            // it: while the talk is in the review, only the review's savers may change the review, by any path.
            lock(ConfDemo.review, writableVia = ConfDemo.recordReviewTask, overrideWhen = CFACTS.hasAdminLevel)
        }
    }

/** The `logistics` bundle (issue #1139): planning a workshop, open only to a talk on the workshop track. */
fun confDemoLogistics(cxt: KdrCxtBase = LiteCxt(), client: String = ConfDemo.client): GedraConfig =
    gedraConfig(cxt, ConfDemo.logisticsConfig, clientNamespace(client), client) {
        workflow(ConfDemo.logisticsWorkflow, WfEntry.normal) {
            comment = "For workshops only: the eligibility test asks for the fact the survey computes from the track."
            label = "Workshop logistics"
            eligibility("workshopOnly", ConfDemo.isWorkshop, "Only a talk on the workshop track has logistics to plan.")
            alterType(
                "${clientNamespace(client)}.${traitDataTypeName(ConfDemo.logisticsEntry)}",
                mapOf(
                    SCH.layout to SchLayoutBuilder(
                        ConfDemo.fragmentFile,
                        label = "%{@t(\"logistics.heading\")}",
                        mode = SchLayoutMode.authoritative,
                        comment = "Authoritative: the labels and descriptions here replace the trait's, and the room list is narrowed to the labs.",
                    ).apply {
                        field(
                            ConfDemo.room, label = "Lab", description = "%{@t(\"logistics.roomHelp\")}",
                            choices = listOf(SchLayoutChoice("labA", "Lab A (24 seats)"), SchLayoutChoice("labB", "Lab B (40 seats)")),
                            required = true,
                        )
                        field(ConfDemo.needsLaptops, label = "Laptops", description = "Say yes if attendees work on their own machines.")
                        field(ConfDemo.setup, label = "Setup instructions", description = "Sent to attendees a week before.")
                    }.build(),
                ),
            )
            task(ConfDemo.planTask, "Plan the workshop") {
                trait(ConfDemo.logistics)
                save(ConfDemo.savePlan, "Save the plan", WfSaveKind.edit)
            }
        }
    }

/** Every bundle of the demo, in the order they are written: the client first, since the others need it to exist. */
/**
 * The `reports` bundle (issue #1140): the two reports an organizer runs over the talks. Both in one bundle, since a
 * report is not a workflow and the two change together; the page says so.
 */
fun confDemoReports(cxt: KdrCxtBase = LiteCxt(), client: String = ConfDemo.client): GedraConfig =
    gedraConfig(cxt, ConfDemo.reportsConfig, clientNamespace(client), client) {
        val form = ReportSource.form.name
        report(ConfDemo.submissionsByTrack, "Submissions by track") {
            comment = "Per form, a roster of the talks. Grouped, how many talks each track has and the minutes they add up to."
            description = "Every talk with its track, its speaker, and where it stands. Grouped by track, how many talks there are and how long they run in all."
            column(ConfDemo.trackColumn, "Track", "$form.${ConfDemo.session}.${ConfDemo.track}")
            column("talk", "Talk", "$form.${GT.name}.${GT.nameField}")
            column("speaker", "Speaker", "${ReportSource.user.name}.${RUSR.name}")
            column("status", "Status", "${ReportSource.meta.name}.${RMETA.formStatus}")
            column("review", "Review", "${ReportSource.workflow.name}.${ConfDemo.reviewWorkflow}.${RWF.category}")
            column(ConfDemo.minutesColumn, "Minutes", "$form.${ConfDemo.abstract}.${ConfDemo.durationMinutes}", rollup = ReportCombine.sum)
            groupBy = listOf(ConfDemo.trackColumn)
        }
        report(ConfDemo.questionnaireSummary, "Questionnaire summary") {
            comment = "Grouped by who the talk is for and whether it may be recorded: how many talks, what they promise, and how many have needs for the day."
            description = "The questionnaire across every talk: who each talk is for, whether it may be recorded, what the audience takes away, and what the organizers should know."
            val questionnaire = "$form.${ConfDemo.questionnaire}"
            column(ConfDemo.audienceColumn, "Audience", "$questionnaire.${ConfDemo.audienceLevel}")
            column(ConfDemo.recordingColumn, "May be recorded", "$questionnaire.${ConfDemo.recordingOk}")
            column("firstTalk", "First talk", "$questionnaire.${ConfDemo.firstTalk}")
            column("takeaway", "Takeaway", "$questionnaire.${ConfDemo.takeaway}", rollup = ReportCombine.list)
            column("needs", "Needs for the day", "$questionnaire.${ConfDemo.needs}", rollup = ReportCombine.count)
            groupBy = listOf(ConfDemo.audienceColumn, ConfDemo.recordingColumn)
            excludeEmpty = listOf(ConfDemo.audienceColumn)
        }
    }

fun confDemoConfigs(cxt: KdrCxtBase = LiteCxt(), client: String = ConfDemo.client): List<GedraConfig> =
    listOf(
        confDemoBase(cxt, client), confDemoCreateTalk(cxt, client), confDemoTalkSurvey(cxt, client),
        confDemoAbstract(cxt, client), confDemoReview(cxt, client), confDemoLogistics(cxt, client),
        confDemoReports(cxt, client),
    )

/**
 * Provisions the demo (issue #1137): each bundle written, published, and made live on every node, then an organizer and
 * a speaker to sign in as. [suffix] makes a fresh copy, `confdemo<suffix>` (see [SIM.suffix]).
 */
fun provisionConfDemo(cxt: KdrCxt, suffix: String? = null): SimulationReport {
    val client = ConfDemo.client + Simulations.checkedSuffix(suffix)
    for (config in confDemoConfigs(cxt, client)) Simulations.provisionConfig(cxt, config)
    val organizer = Simulations.provisionUser(cxt, "organizer@$client.example", client, ROLE.admin, name = "Olive Organizer")
    val speaker = Simulations.provisionUser(cxt, "speaker@$client.example", client, ROLE.user, name = "Pat Speaker")
    // Reviews talks (issue #1139): an administrator carrying the client's `committee` label.
    val reviewer = Simulations.provisionUser(cxt, "committee@$client.example", client, ROLE.admin, name = "Casey Committee")
    Simulations.setLabels(cxt, reviewer, listOf(ConfDemo.committeeLabel))
    return SimulationReport(
        clients = listOf(client),
        users = listOf(
            Simulations.reported(speaker, ROLE.user, "a speaker -- submits talks"),
            Simulations.reported(organizer, ROLE.admin, "an organizer -- an administrator, who sees every talk"),
            Simulations.reported(reviewer, ROLE.admin, "a committee member -- an administrator carrying the 'committee' label, who reviews and accepts talks"),
        ),
        startPage = "page=${HMENU.pageNewForm}",
        summary = "Client '$client' is ready. Sign in as the speaker to submit a talk.",
    )
}

/** The demo's simulation (issue #1137), in `kdn`'s own module: a test instance offers it beside `common`'s. */
fun confDemoSimulationSchema(cxt: KdrCxt): SchModule = schemaModule(cxt, "kdr.confdemo") {
    simulationEndpoint(
        ConfDemo.simulationName,
        "Provisions the conference demo -- the client the developer documentation in docs/demo-client walks through -- " +
            "and a speaker and an organizer to sign in as. Give a suffix for a fresh copy (confdemo2), so you can change " +
            "it without changing the one the documentation describes. A rerun rewrites that client's configuration; its " +
            "users and forms stay.",
        inputFields = {
            field(SIM.suffix, "Appended to the client id for a fresh copy; lowercase letters and digits. Absent, the demo client itself.")
        },
    ) { c, request -> provisionConfDemo(c, request[SIM.suffix].toOptStr()) }
}
