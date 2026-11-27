package com.dynamicruntime.kdn.claimsdemo

import com.dynamicruntime.common.cfact.CFACTS
import com.dynamicruntime.common.content.DOCORDER
import com.dynamicruntime.common.content.MarkdownDocDef
import com.dynamicruntime.common.context.ENV
import com.dynamicruntime.common.context.KdrCxt
import com.dynamicruntime.common.context.KdrCxtBase
import com.dynamicruntime.common.context.LiteCxt
import com.dynamicruntime.common.counter.CTR
import com.dynamicruntime.common.endpoint.SchModule
import com.dynamicruntime.common.endpoint.schemaModule
import com.dynamicruntime.common.filekind.FKIND
import com.dynamicruntime.common.gedra.ClientAudience
import com.dynamicruntime.common.gedra.ClientDef
import com.dynamicruntime.common.gedra.ClientJoinRole
import com.dynamicruntime.common.gedra.ClientUsageType
import com.dynamicruntime.common.gedra.GedraConfig
import com.dynamicruntime.common.gedra.GedraConfigService
import com.dynamicruntime.common.gedra.GedraDataType
import com.dynamicruntime.common.gedra.ListingDef
import com.dynamicruntime.common.gedra.WorkflowLayout
import com.dynamicruntime.common.gedra.gedraConfig
import com.dynamicruntime.common.gedra.report.RMETA
import com.dynamicruntime.common.gedra.report.RUSR
import com.dynamicruntime.common.gedra.report.RWF
import com.dynamicruntime.common.gedra.report.ReportCombine
import com.dynamicruntime.common.gedra.report.ReportSource
import com.dynamicruntime.common.gedra.workflow.CDF
import com.dynamicruntime.common.gedra.workflow.CTAK
import com.dynamicruntime.common.gedra.workflow.PFO
import com.dynamicruntime.common.gedra.workflow.RCF
import com.dynamicruntime.common.gedra.workflow.WFC
import com.dynamicruntime.common.gedra.workflow.WSC
import com.dynamicruntime.common.gedra.workflow.WfEntry
import com.dynamicruntime.common.gedra.workflow.WfSaveKind
import com.dynamicruntime.common.gedra.workflow.ctaFilterByValue
import com.dynamicruntime.common.gedra.workflow.prefillFromOwner
import com.dynamicruntime.common.gedra.workflow.userHasLabel
import com.dynamicruntime.common.home.HFLD
import com.dynamicruntime.common.home.HFRAG
import com.dynamicruntime.common.home.HMENU
import com.dynamicruntime.common.home.menuItem
import com.dynamicruntime.common.http.request.ROLE
import com.dynamicruntime.common.naming.clientNamespace
import com.dynamicruntime.common.schema.SCT
import com.dynamicruntime.common.schema.computeValue
import com.dynamicruntime.common.simulation.SimulationReport
import com.dynamicruntime.common.simulation.Simulations
import com.dynamicruntime.common.simulation.simulationEndpoint
import com.dynamicruntime.common.test.SIM
import com.dynamicruntime.common.user.PERSONA
import com.dynamicruntime.common.util.toOptStr

/*
 * The claims demo (issue #1897): a small insurer's claims, from the first report of an incident to an approved outcome.
 * It shows three things the other demos do not:
 *
 * - a **counter that issues as the form is saved**, with a prefix that changes each day: a report being filed need not
 *   show its number, and an abandoned one uses none up;
 * - **approval limits by role**: a value computed from the amount decides who the approval waits on;
 * - a decision that **cannot change once it is made**, but by an administrator who says why.
 *
 * The documentation in `docs/claims-demo/` shows the JSON of each bundle and walks through it, in the conference demo's
 * style, and `ClaimsDemoDocsTest` holds the two to each other: a change here that changes a bundle fails that test until
 * the page is changed with it. A trait is appended, never inserted, since the pages address each by its place.
 *
 * Provisioned as stored bundles, by the `claims-demo` simulation on a test instance and as the node starts on a
 * reference deployment ([ClaimsDemo.provisionAtBootKey]).
 */

/** The demo's names: the client, its bundles, and what its pages and its tests address. */
@Suppress("ConstPropertyName")
object ClaimsDemo {
    const val client = "claimsdemo"
    const val simulationName = "claims-demo"

    /** The bundle holding the client itself, its traits, its counter, and its forms list. */
    const val baseConfig = "base"

    // One bundle per workflow, so each is read and written on its own, and the report's.
    const val reportConfig = "reportIncident"
    const val detailsConfig = "incidentDetails"
    const val evidenceConfig = "evidence"
    const val settlementConfig = "settlement"
    const val registerConfig = "register"

    const val reportWorkflow = "reportIncident"
    const val describeTask = "describe"
    const val fileSave = "file"

    const val detailsWorkflow = "incidentDetails"
    const val reviseTask = "revise"
    const val saveDetails = "saveDetails"

    const val evidenceWorkflow = "evidence"
    const val attachTask = "attach"
    const val saveEvidence = "saveEvidence"

    const val settlementWorkflow = "settlement"
    const val assessTask = "assess"
    const val saveAssessment = "saveAssessment"
    const val approveTask = "approveOutcome"

    /** The client's one cfact: the claim's outcome is approved, so the claim is settled. */
    const val settled = "settled"
    const val cfactGroup = "claimsDemo"

    /** The label an adjuster carries, which the settlement's tasks ask for. */
    const val adjusterLabel = "adjuster"

    // The roles a visitor joins the demo as.
    const val policyholderRole = "policyholder"
    const val adjusterRole = "adjuster"
    const val managerRole = "manager"

    // The client's traits, and their fields.
    const val incident = "incident"
    const val incidentEntry = "IncidentEntry"
    const val kind = "kind"
    const val kindProperty = "property"
    const val occurredAt = "occurredAt"
    const val place = "place"
    const val whatHappened = "whatHappened"

    const val reporter = "reporter"
    const val reporterEntry = "ReporterEntry"
    const val reporterName = "name"
    const val involvement = "involvement"
    const val involvementWitness = "witness"

    /** The incident's number: a trait whose one field takes its value from the counter as the report is saved. */
    const val filing = "filing"
    const val filingEntry = "FilingEntry"
    const val incidentNumber = "incidentNumber"
    const val incidentCounter = "incident"
    const val counterDigits = 3

    const val evidence = "evidence"
    const val evidenceEntry = "EvidenceEntry"
    const val photos = "photos"
    const val maxPhotos = 4
    const val photoMaxBytes = 5L * 1024 * 1024
    const val document = "document"
    const val evidenceNotes = "notes"

    const val assessment = "assessment"
    const val assessmentEntry = "AssessmentEntry"
    const val outcome = "outcome"
    const val outcomePay = "pay"
    const val outcomeDecline = "decline"
    const val amount = "amount"
    const val reasons = "reasons"
    const val overLimit = "overLimit"

    /** The most an adjuster approves, in whole dollars: a larger payment waits on a claims manager. */
    const val adjusterLimit = 5000
    const val maxAmount = 250_000

    const val claimsRegister = "claimsRegister"

    /** Where the documentation lives in the repository, and the prefix its pages take as doc ids. */
    const val docsDir = "docs/claims-demo"
    const val docIdPrefix = "claims-demo-"

    /**
     * The instance-config key that has the demo provisioned as the node starts, by [ClaimsDemoBootService]. The
     * `reference` deployment flavor sets it, so a visitor there finds it to join.
     */
    const val provisionAtBootKey = "claimsDemoProvisionAtBoot"
}

/**
 * The demo's documentation: the opening page is listed on the Documents page, after the grants demo's; the pages it leads
 * to are linked from it. The build copies `docs/claims-demo/<name>.md` onto the classpath as `claims-demo-<name>.md`, so
 * the ids here follow the file names.
 */
fun claimsDemoDocs(): List<MarkdownDocDef> = listOf(
    claimsDemoDoc("README", "Claims demo", "A worked example of a claims process: incidents numbered as they are filed, " +
        "evidence, approval limits by role, and a register for the auditors.", listed = true),
    claimsDemoDoc("client", "Claims demo: client", "The demo's client definition, its roles, and its forms list."),
    claimsDemoDoc("reporting", "Claims demo: reporting an incident", "The demo's creation workflow, and the two traits it asks for."),
    claimsDemoDoc("numbering", "Claims demo: numbering incidents", "The counter that numbers each incident as its report is " +
        "filed, with a prefix that changes each day, and why it does so then."),
    claimsDemoDoc("evidence", "Claims demo: evidence", "A workflow that collects files: photographs and a document."),
    claimsDemoDoc("settlement", "Claims demo: settlement", "An assessment, approval limits by role, and a decision that cannot " +
        "change once it is made."),
    claimsDemoDoc("register", "Claims demo: the claims register", "The demo's report: every claim, what was decided, and who approved it."),
)

private fun claimsDemoDoc(name: String, label: String, description: String, listed: Boolean = false) =
    MarkdownDocDef(
        ClaimsDemo.docIdPrefix + name, label, "${ClaimsDemo.docsDir}/$name.md", description, listed,
        order = if (listed) DOCORDER.claimsDemo else 0,
    )

/** The `base` bundle: the client, the traits its forms hold, the counter that numbers them, and its forms list. */
fun claimsDemoBase(cxt: KdrCxtBase = LiteCxt(), client: String = ClaimsDemo.client): GedraConfig =
    gedraConfig(cxt, ClaimsDemo.baseConfig, clientNamespace(client), client) {
        defineClient(
            ClientDef(
                clientId = client,
                name = "Claims demo",
                // Shown to visitors on the home page's demo card and the Join page, so it says what the demo is.
                description = "A small insurer's claims: policyholders report incidents, each numbered as it is filed, " +
                    "adjusters assess them, and a claims manager approves the larger payments.",
                usageType = ClientUsageType.demo,
                audience = ClientAudience.internal,
                enabledEnvironments = setOf(ENV.unit, ENV.local, ENV.dev),
                userLabels = listOf(ClaimsDemo.adjusterLabel),
                userLabelNotes = mapOf(ClaimsDemo.adjusterLabel to "Give this to the people who assess claims."),
                joinRoles = listOf(
                    ClientJoinRole(
                        name = ClaimsDemo.policyholderRole,
                        label = "Policyholder",
                        persona = PERSONA.member,
                        summary = "Report an incident, see the number it is given, and follow the claim.",
                    ),
                    ClientJoinRole(
                        name = ClaimsDemo.adjusterRole,
                        label = "Adjuster",
                        // An editor: it sees every claim, and administers nothing.
                        persona = PERSONA.editor,
                        userLabels = listOf(ClaimsDemo.adjusterLabel),
                        summary = "Assess the claims other visitors make, and approve payments up to $5,000.",
                    ),
                    ClientJoinRole(
                        name = ClaimsDemo.managerRole,
                        label = "Claims manager",
                        persona = PERSONA.admin,
                        summary = "See every claim, approve the payments over $5,000, and run the claims register.",
                    ),
                ),
            ),
        )
        cfact(ClaimsDemo.settled, ClaimsDemo.cfactGroup, "True once the claim's outcome is approved.")

        // The workflow pages are off in the shipped menu; a client with normal workflows turns them on (issue #792). A form
        // is a claim to the demo's people, and every file is evidence, so the forms and files items say so: the forms list
        // is headed by its item's label.
        uiBlockOverlay(HMENU.block) {
            items(HFLD.menu) {
                menuItem(HMENU.workflows, cfactExpression = "${CFACTS.loggedIn},${CFACTS.app}")
                menuItem(HMENU.forms, label = "My claims")
                menuItem(HMENU.clientForms, label = "Claims")
                menuItem(HMENU.files, label = "My evidence")
                menuItem(HMENU.clientFiles, label = "Evidence")
            }
        }

        trait(ClaimsDemo.incidentEntry, ClaimsDemo.incident, setOf(GedraDataType.formDoc), "What happened, when, and where.") {
            title = "Incident"
            property(ClaimsDemo.kind, "Choose the closest.", required = true) {
                title = "What kind of incident was it?"
                option("vehicle", "Vehicle")
                option(ClaimsDemo.kindProperty, "Property")
                option("injury", "Injury")
                option("other", "Something else")
            }
            property(ClaimsDemo.occurredAt, "If you are not sure, give the time you think it was.", required = true) {
                title = "When did it happen?"
                dateTime()
            }
            property(ClaimsDemo.place, "An address, or the nearest crossing.", required = true) {
                title = "Where did it happen?"
                trimmed()
            }
            property(ClaimsDemo.whatHappened, "In your own words.", required = true) {
                title = "What happened?"
                longText = true
                minLength = 20
                maxLength = 4000
                trimmed()
                errors { belowMinimum($$"Say a little more: an adjuster needs at least ${min} characters to go on.") }
            }
        }
        trait(ClaimsDemo.reporterEntry, ClaimsDemo.reporter, setOf(GedraDataType.formDoc), "Who reports the incident.") {
            title = "Reported by"
            property(ClaimsDemo.reporterName, "The name an adjuster asks for.", required = true) {
                title = "Your name"
                trimmed()
            }
            property(ClaimsDemo.involvement, "Your part in it.", required = true) {
                title = "How were you involved?"
                option("insured", "It happened to me, or to something of mine")
                option(ClaimsDemo.involvementWitness, "I saw it happen")
                option("representative", "I am reporting it for someone else")
            }
        }
        // The incident's number: a field the counter below fills as the report is saved, collected by the creation workflow.
        trait(
            ClaimsDemo.filingEntry, ClaimsDemo.filing, setOf(GedraDataType.formDoc),
            "The incident's number, given as its report is filed.",
        ) {
            title = "Filing"
            property(ClaimsDemo.incidentNumber, "The incident's number, given by the insurer's counter.", required = true) {
                title = "Incident number"
                counter = ClaimsDemo.incidentCounter
            }
        }
        trait(ClaimsDemo.evidenceEntry, ClaimsDemo.evidence, setOf(GedraDataType.formDoc), "What shows what happened.") {
            title = "Evidence"
            property(ClaimsDemo.photos, "Of the damage, or of the place.") {
                title = "Photographs"
                type = SCT.array
                maxItems = ClaimsDemo.maxPhotos
                items { dataFile(FKIND.png, FKIND.jpeg, FKIND.webp, maxSize = ClaimsDemo.photoMaxBytes) }
            }
            property(ClaimsDemo.document, "A police report, an estimate, or a receipt.") {
                title = "Document"
                dataFile(FKIND.pdf)
            }
            property(ClaimsDemo.evidenceNotes, "For whoever assesses the claim.") {
                title = "What do these show?"
                longText = true
            }
        }
        trait(ClaimsDemo.assessmentEntry, ClaimsDemo.assessment, setOf(GedraDataType.formDoc), "What the adjuster concludes.") {
            title = "Assessment"
            property(ClaimsDemo.outcome, "What the adjuster recommends.", required = true) {
                title = "Outcome"
                option(ClaimsDemo.outcomePay, "Pay the claim")
                option(ClaimsDemo.outcomeDecline, "Decline the claim")
            }
            property(ClaimsDemo.amount, "The amount to pay, in whole dollars.") {
                title = "Amount to pay (dollars)"
                type = SCT.integer
                minimum = 1
                maximum = ClaimsDemo.maxAmount
            }
            property(ClaimsDemo.reasons, "Why, for whoever approves it and for the claim's record.", required = true) {
                title = "Reasons"
                longText = true
                trimmed()
            }
            // Computed as the assessment is saved, and never typed. The settlement's approval reads it.
            property(ClaimsDemo.overLimit, "Whether the amount is over what an adjuster may approve.") {
                title = "Over the adjusters' limit"
                type = SCT.boolean
                derived = true
            }
            presentWhen(ClaimsDemo.amount, on = ClaimsDemo.outcome, value = ClaimsDemo.outcomePay)
            onWrite(computeValue(ClaimsDemo.overLimit, "(${ClaimsDemo.amount} ?: 0) > ${ClaimsDemo.adjusterLimit}"))
        }

        // Numbers the incidents as their reports are saved, starting over each day.
        counter(
            ClaimsDemo.incidentCounter, $$"INC-${compactDay}-", digits = ClaimsDemo.counterDigits, reset = CTR.resetDaily,
            description = "Numbers the incidents, day by day, in the order their reports are filed.",
            issue = CTR.issueOnSave,
        )

        // The forms list names a claim by its incident's number, which a person on the phone can read out. Not narrow: with
        // its day, a number is as long as a name.
        traitUsage(ClaimsDemo.filing, "Incident", $$"${incidentNumber}", substring = true, columnId = ClaimsDemo.incidentNumber)
        traitUsage(ClaimsDemo.incident, "Kind", $$"${kind}", columnId = ClaimsDemo.kind)
        traitUsage(ClaimsDemo.incident, "Where", $$"${place}", substring = true, columnId = ClaimsDemo.place)
        listing(ListingDef(workflowLayout = WorkflowLayout.combined))

        // Its home page's own words (issue #1692): what the demo is, and where each role starts. Then the forms list's
        // words that name a form, as a claim.
        fragmentOverlay(HFRAG.home) {
            namespace(HFRAG.homeNs) {
                key(HFRAG.title, "Claims demo")
                key(
                    HFRAG.intro,
                    "The **Claims demo** is a small insurer's claims. A policyholder reports an incident, which is given a " +
                        "number as the report is filed, and adds photographs and a document as evidence. An adjuster " +
                        "assesses the claim and approves a payment of up to $5,000, while a larger one waits for a claims " +
                        "manager. The demo is shared with everyone who joins it: as an adjuster or a claims " +
                        "manager, you see the claims every visitor makes, and they see yours.\n\n" +
                        "As a policyholder, open **My claims** from the menu, and choose **Create a form** to report an " +
                        "incident. As an adjuster, this page lists the claims you can assess under **Waiting on you**, and " +
                        "as a claims manager, so does each payment over $5,000, for you to approve. " +
                        "[The demo's pages](/docs/claims-demo/README.md) show the configuration behind each step.",
                )
            }
            namespace(HFRAG.formsNs) {
                key(
                    HFRAG.explanation,
                    "Each row is one **claim**: an incident a policyholder reported, and everything added to it since. Its " +
                        "number, its kind, and where it happened lead the row, and **Contains** lists its parts.\n\n" +
                        "A claim goes through **steps**, such as its evidence and its settlement. Each is something somebody " +
                        "does, and the **Workflows** column shows where the claim stands in each.",
                )
            }
            namespace(HFRAG.workflowCellNs) {
                key(HFRAG.wcWaitingOnOwner, "With the policyholder")
                key(HFRAG.wcNotStarted, "This claim has not started this step. Start it to work on it.")
            }
        }
    }

/** The `reportIncident` bundle: the workflow that runs when a policyholder reports an incident. */
fun claimsDemoReport(cxt: KdrCxtBase = LiteCxt(), client: String = ClaimsDemo.client): GedraConfig =
    gedraConfig(cxt, ClaimsDemo.reportConfig, clientNamespace(client), client) {
        workflow(ClaimsDemo.reportWorkflow, WfEntry.creation) {
            comment = "The first notice of an incident. It also collects the incident's number, which the server fills as " +
                "the report is saved."
            label = "Report an incident"
            task(ClaimsDemo.describeTask, "What happened") {
                trait(ClaimsDemo.incident)
                trait(ClaimsDemo.reporter)
                // Filled by the server, never typed, and not required, so a program filing a report need not send it.
                trait(ClaimsDemo.filing, required = false)
                save(ClaimsDemo.fileSave, "File the report", WfSaveKind.create)
                function(prefillFromOwner {
                    userAttribute = PFO.name
                    targetTrait = ClaimsDemo.reporter
                    targetValuePath = ClaimsDemo.reporterName
                })
            }
        }
    }

/** The `incidentDetails` bundle: the core details workflow, which a report is complete by, and is corrected through. */
fun claimsDemoDetails(cxt: KdrCxtBase = LiteCxt(), client: String = ClaimsDemo.client): GedraConfig =
    gedraConfig(cxt, ClaimsDemo.detailsConfig, clientNamespace(client), client) {
        workflow(ClaimsDemo.detailsWorkflow, WfEntry.coreDetails) {
            comment = "What every claim needs. The report asks for all of it, so a claim is complete from the start, unless a " +
                "program made it without. Its number comes first, so a claim's own page opens with it."
            label = "Incident details"
            task(ClaimsDemo.reviseTask, "What happened") {
                // First, so a claim's own page opens with its number.
                trait(ClaimsDemo.filing, required = false)
                trait(ClaimsDemo.incident)
                trait(ClaimsDemo.reporter)
                save(ClaimsDemo.saveDetails, "Save", WfSaveKind.edit)
            }
        }
    }

/** The `evidence` bundle: the files that show what happened, open to every claim. */
fun claimsDemoEvidence(cxt: KdrCxtBase = LiteCxt(), client: String = ClaimsDemo.client): GedraConfig =
    gedraConfig(cxt, ClaimsDemo.evidenceConfig, clientNamespace(client), client) {
        workflow(ClaimsDemo.evidenceWorkflow, WfEntry.normal) {
            comment = "No eligibility tests: evidence can be added to any claim, at any time."
            label = "Evidence"
            task(ClaimsDemo.attachTask, "Add the evidence") {
                trait(ClaimsDemo.evidence)
                save(ClaimsDemo.saveEvidence, "Save the evidence", WfSaveKind.edit)
            }
        }
    }

/** The `settlement` bundle: an adjuster's assessment, and its approval by whoever the amount calls for. */
fun claimsDemoSettlement(cxt: KdrCxtBase = LiteCxt(), client: String = ClaimsDemo.client): GedraConfig =
    gedraConfig(cxt, ClaimsDemo.settlementConfig, clientNamespace(client), client) {
        workflow(ClaimsDemo.settlementWorkflow, WfEntry.normal) {
            comment = "An adjuster assesses the claim, and then its outcome is approved: by an adjuster up to their limit, by " +
                "a claims manager above it."
            label = "Settlement"
            // Its Start is highlighted for an adjuster, who may act on its first task.
            highlightStart = true
            eligibility("reportComplete", CDF.coreDetailsComplete, "The incident's report has to be complete before its claim is assessed.")
            task(ClaimsDemo.assessTask, "Assess the claim") {
                comment = "An adjuster saves it while it is the step to do, so an assessment cannot change under its approval."
                trait(ClaimsDemo.assessment)
                save(ClaimsDemo.saveAssessment, "Save the assessment", WfSaveKind.edit)
                function(userHasLabel { label = ClaimsDemo.adjusterLabel })
                keyword(CTAK.review)
                saveWhen("${WFC.reviewer},${WFC.isCta}")
            }
            task(ClaimsDemo.approveTask, "Approve the outcome") {
                comment = "The computed overLimit decides who it waits on."
                approval(
                    ClaimsDemo.settled,
                    prompt = "Read the claim and its assessment, then approve the outcome.",
                    button = "Approve the outcome",
                    approveWhen = "(${WFC.reviewer}|${CFACTS.hasAdminLevel}),~${RCF.isOwner}",
                    showTraits = listOf(ClaimsDemo.filing, ClaimsDemo.incident, ClaimsDemo.evidence, ClaimsDemo.assessment),
                )
                function(userHasLabel { label = ClaimsDemo.adjusterLabel })
                function(ctaFilterByValue {
                    trait = ClaimsDemo.assessment
                    valuePath = ClaimsDemo.overLimit
                    case("false", WFC.reviewer, "An adjuster approves the outcome, since it pays $5,000 or less.")
                    case("true", CFACTS.hasAdminLevel, "A claims manager approves the outcome, since it pays more than $5,000.")
                    otherwise(CFACTS.hasAdminLevel, "A claims manager approves the outcome.")
                })
                display {
                    whenCfacts(ClaimsDemo.settled) { defaultRendering() }
                    whenCfacts("~${WFC.isCta}") {
                        text("The claim has to be assessed before its outcome can be approved.")
                        disabled = true
                    }
                    whenCfacts("${WFC.reviewer}|${CFACTS.hasAdminLevel}") { defaultRendering() }
                    otherwise { text("The claim has been assessed, and its outcome waits for approval.") }
                }
            }
            singleton(WSC.finished, ClaimsDemo.settled)
            // With the task's save rule: once the assessment is saved, nobody changes it by any path, but an administrator
            // who overrides the lock and says why.
            lock(ClaimsDemo.assessment, writableVia = ClaimsDemo.assessTask, overrideWhen = CFACTS.hasAdminLevel)
        }
    }

/** The `register` bundle: the report an auditor reads, with what was decided on each claim and who approved it. */
fun claimsDemoRegister(cxt: KdrCxtBase = LiteCxt(), client: String = ClaimsDemo.client): GedraConfig =
    gedraConfig(cxt, ClaimsDemo.registerConfig, clientNamespace(client), client) {
        val form = ReportSource.form.name
        val approval = "${ReportSource.workflow.name}.${ClaimsDemo.settlementWorkflow}.${RWF.approval}[${ClaimsDemo.approveTask}]"
        report(ClaimsDemo.claimsRegister, "Claims register") {
            comment = "Per form, a row for each claim. Grouped, how many claims of each kind were paid or declined, and what was paid."
            description = "Every claim, what was decided on it, and when and by whom the decision was approved."
            column("number", "Incident", "$form.${ClaimsDemo.filing}.${ClaimsDemo.incidentNumber}")
            column("filed", "Filed", "${ReportSource.meta.name}.${RMETA.createdAt}")
            column("kind", "Kind", "$form.${ClaimsDemo.incident}.${ClaimsDemo.kind}")
            column("policyholder", "Policyholder", "${ReportSource.user.name}.${RUSR.name}")
            column("status", "Status", "${ReportSource.meta.name}.${RMETA.formStatus}")
            column("outcome", "Outcome", "$form.${ClaimsDemo.assessment}.${ClaimsDemo.outcome}")
            column("amount", "Amount", "$form.${ClaimsDemo.assessment}.${ClaimsDemo.amount}", rollup = ReportCombine.sum)
            column("approvedAt", "Approved", "$approval.${RWF.approvedAt}")
            column("approvedBy", "Approved by (user)", "$approval.${RWF.approvedBy}")
            groupBy = listOf("kind", "outcome")
        }
    }

/** Every bundle of the demo, in the order they are written: the client first, since the others need it to exist. */
fun claimsDemoConfigs(cxt: KdrCxtBase = LiteCxt(), client: String = ClaimsDemo.client): List<GedraConfig> =
    listOf(
        claimsDemoBase(cxt, client), claimsDemoReport(cxt, client), claimsDemoDetails(cxt, client),
        claimsDemoEvidence(cxt, client), claimsDemoSettlement(cxt, client), claimsDemoRegister(cxt, client),
    )

/**
 * Provisions the demo: each bundle written, published, and made live on every node, then a policyholder, an adjuster, and
 * a claims manager to sign in as. [suffix] makes a fresh copy, `claimsdemo<suffix>` (see [SIM.suffix]). A rerun rewrites
 * the demo's configuration and keeps its users and forms. With [skipUnchanged], as every start of a reference deployment
 * provisions it, a bundle already published as it is written is left alone, adding no revision.
 */
fun provisionClaimsDemo(cxt: KdrCxt, suffix: String? = null, skipUnchanged: Boolean = false): SimulationReport {
    val client = ClaimsDemo.client + Simulations.checkedSuffix(suffix)
    val configs = GedraConfigService.get(cxt)
    val bound = cxt.mkSubContext("claimsDemo", client)
    for (config in claimsDemoConfigs(cxt, client)) {
        if (skipUnchanged && configs.publishedAsIs(bound, config)) continue
        Simulations.provisionConfig(cxt, config)
    }
    val manager = Simulations.provisionUser(cxt, "manager@$client.example", client, ROLE.admin, name = "Mo Manager")
    val policyholder = Simulations.provisionUser(cxt, "policyholder@$client.example", client, ROLE.user, name = "Parker Policyholder")
    val editor = listOf(ROLE.editor)
    val adjuster = Simulations.provisionUser(
        cxt, "adjuster@$client.example", client, ROLE.user, name = "Ash Adjuster", capabilities = editor,
    )
    Simulations.setLabels(cxt, adjuster, listOf(ClaimsDemo.adjusterLabel))
    return SimulationReport(
        clients = listOf(client),
        users = listOf(
            Simulations.reported(policyholder, ROLE.user, "a policyholder -- reports incidents"),
            Simulations.reported(
                adjuster, ROLE.user, "an adjuster -- an editor carrying the 'adjuster' label, who assesses claims", editor,
            ),
            Simulations.reported(manager, ROLE.admin, "a claims manager -- an administrator, who approves the larger payments"),
        ),
        startPage = "page=${HMENU.pageNewForm}",
        summary = "Client '$client' is ready. Sign in as the policyholder to report an incident.",
    )
}

/** The demo's simulation, in `kdn`'s own module: a test instance offers it beside `common`'s. */
fun claimsDemoSimulationSchema(cxt: KdrCxt): SchModule = schemaModule(cxt, "kdr.claimsdemo") {
    simulationEndpoint(
        ClaimsDemo.simulationName,
        "Provisions the claims demo -- the client the documentation in docs/claims-demo walks through, whose incidents are " +
            "numbered as their reports are filed and whose payments are approved by whoever the amount calls for -- and a " +
            "policyholder, an adjuster and a claims manager to sign in as. Give a suffix for a fresh copy (claimsdemo2). A " +
            "rerun rewrites that client's configuration; its users and forms stay.",
        inputFields = {
            field(SIM.suffix, "Appended to the client id for a fresh copy; lowercase letters and digits. Absent, the demo client itself.")
        },
    ) { c, request -> provisionClaimsDemo(c, request[SIM.suffix].toOptStr()) }
}
