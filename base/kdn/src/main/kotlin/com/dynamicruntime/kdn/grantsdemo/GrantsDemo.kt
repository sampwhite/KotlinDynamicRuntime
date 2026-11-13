package com.dynamicruntime.kdn.grantsdemo

import com.dynamicruntime.common.cfact.CFACTS
import com.dynamicruntime.common.content.DOCORDER
import com.dynamicruntime.common.content.MarkdownDocDef
import com.dynamicruntime.common.context.ENV
import com.dynamicruntime.common.context.KdrCxt
import com.dynamicruntime.common.context.KdrCxtBase
import com.dynamicruntime.common.context.LiteCxt
import com.dynamicruntime.common.endpoint.SchModule
import com.dynamicruntime.common.endpoint.schemaModule
import com.dynamicruntime.common.gedra.ClientAudience
import com.dynamicruntime.common.gedra.ClientDef
import com.dynamicruntime.common.gedra.ClientJoinRole
import com.dynamicruntime.common.gedra.ClientUsageType
import com.dynamicruntime.common.gedra.GT
import com.dynamicruntime.common.gedra.GedraConfig
import com.dynamicruntime.common.gedra.GedraConfigService
import com.dynamicruntime.common.gedra.GedraDataType
import com.dynamicruntime.common.gedra.gedraConfig
import com.dynamicruntime.common.gedra.report.RMETA
import com.dynamicruntime.common.gedra.report.RWF
import com.dynamicruntime.common.gedra.report.ReportCombine
import com.dynamicruntime.common.gedra.report.ReportSource
import com.dynamicruntime.common.gedra.workflow.CTAK
import com.dynamicruntime.common.gedra.workflow.CtaKeywordCopy
import com.dynamicruntime.common.gedra.workflow.CTKF
import com.dynamicruntime.common.gedra.workflow.WFC
import com.dynamicruntime.common.gedra.workflow.WfEntry
import com.dynamicruntime.common.gedra.workflow.WfSaveKind
import com.dynamicruntime.common.gedra.workflow.userHasLabel
import com.dynamicruntime.common.home.HFRAG
import com.dynamicruntime.common.home.HMENU
import com.dynamicruntime.common.http.request.ROLE
import com.dynamicruntime.common.mail.MCOPY
import com.dynamicruntime.common.naming.clientNamespace
import com.dynamicruntime.common.schema.SCT
import com.dynamicruntime.common.simulation.SimulationReport
import com.dynamicruntime.common.simulation.Simulations
import com.dynamicruntime.common.simulation.simulationEndpoint
import com.dynamicruntime.common.test.SIM
import com.dynamicruntime.common.user.AFRAG
import com.dynamicruntime.common.user.PERSONA
import com.dynamicruntime.common.util.toOptStr

/*
 * The grants demo (issue #1799): a small community fund's grant program, in the fund's own words. It changes every piece
 * of copy a client is likely to reword -- each key whose copy note names where a person sees it -- and nothing else, so
 * it is the worked example of a client's wording, and the documentation in `docs/grants-demo/` shows it theme by theme.
 * `GrantsDemoDocsTest` holds the two to each other and to the notes: a key that gains a location fails it until the demo
 * rewords it and a page shows it, and a page that shows anything else fails it too.
 *
 * Its workflows are only what its words need something to name: a form to make, a step with a list, and a review. How a
 * client's workflows are written is the conference demo's subject, in `docs/demo-client/`.
 *
 * Joinable on a reference deployment, as an applicant or a reviewer, and never as an administrator: a visitor who joins
 * sees the fund's words, and cannot change them for everyone else. A visitor who wants to try rewording makes a client of
 * their own.
 *
 * Theme: #CUSTX.
 */

/** The demo's names: the client, its bundles, and what its pages and its test address. */
@Suppress("ConstPropertyName")
object GrantsDemo {
    const val client = "grantsdemo"
    const val simulationName = "grants-demo"

    /** The bundle holding the client itself, its traits, and its forms list's columns. */
    const val baseConfig = "base"
    const val workflowsConfig = "workflows"
    const val reportsConfig = "reports"

    // The words, one bundle per theme, each the subject of one page.
    const val siteWordsConfig = "siteWords"
    const val homeWordsConfig = "homeWords"
    const val formsWordsConfig = "formsWords"
    const val mailWordsConfig = "mailWords"

    /** The bundles holding the demo's words, in the order its pages show them. */
    val wordsConfigs = listOf(siteWordsConfig, homeWordsConfig, formsWordsConfig, mailWordsConfig)

    const val applyWorkflow = "apply"
    const val nameTask = "name"
    const val createSave = "create"

    const val budgetWorkflow = "budget"
    const val listTask = "list"
    const val saveBudget = "saveBudget"

    const val reviewWorkflow = "review"
    const val scoreTask = "score"
    const val saveScore = "saveScore"

    /** The label a reviewer carries, which the review's task asks for. */
    const val reviewerLabel = "reviewer"

    const val applicantRole = "applicant"
    const val reviewerRole = "reviewer"

    const val request = "request"
    const val requestEntry = "RequestEntry"
    const val amount = "amount"

    const val budget = "budget"
    const val budgetEntry = "BudgetEntry"
    const val lines = "lines"
    const val budgetLineType = "BudgetLine"

    const val scoreTrait = "score"
    const val scoreEntry = "ScoreEntry"
    const val scoreField = "score"
    const val notes = "notes"

    const val applicationsReport = "applications"

    /** Where the documentation lives in the repository, and the prefix its pages take as doc ids. */
    const val docsDir = "docs/grants-demo"
    const val docIdPrefix = "grants-demo-"

    /**
     * The instance-config key that has the demo provisioned as the node starts, by [GrantsDemoBootService]. The
     * `reference` deployment flavor sets it, so a visitor there finds it to join.
     */
    const val provisionAtBootKey = "grantsDemoProvisionAtBoot"
}

/**
 * The demo's documentation: the opening page is listed on the Documents page, after the conference demo's; the pages it
 * leads to are linked from it. The build copies `docs/grants-demo/<name>.md` onto the classpath as
 * `grants-demo-<name>.md`, so the ids here follow the file names.
 */
fun grantsDemoDocs(): List<MarkdownDocDef> = listOf(
    grantsDemoDoc("README", "Grants demo", "A worked example of a client in its own words: every piece of copy a client is " +
        "likely to reword, reworded, theme by theme.", listed = true),
    grantsDemoDoc("site-name", "Grants demo: the site's name", "The wordmark in the app bar, and the title on the browser tab."),
    grantsDemoDoc("home-page", "Grants demo: the home page", "The home page's title and introduction."),
    grantsDemoDoc("process-words", "Grants demo: the words of the process", "What the forms list, a workflow's page, a form, " +
        "and a report call things."),
    grantsDemoDoc("mails", "Grants demo: the mails", "The mails the fund's people are sent, and how every mail closes and looks."),
)

private fun grantsDemoDoc(name: String, label: String, description: String, listed: Boolean = false) =
    MarkdownDocDef(
        GrantsDemo.docIdPrefix + name, label, "${GrantsDemo.docsDir}/$name.md", description, listed,
        order = if (listed) DOCORDER.grantsDemo else 0,
    )

/** The `base` bundle: the client, the traits its forms hold, and its forms list's columns. */
fun grantsDemoBase(cxt: KdrCxtBase = LiteCxt(), client: String = GrantsDemo.client): GedraConfig =
    gedraConfig(cxt, GrantsDemo.baseConfig, clientNamespace(client), client) {
        defineClient(
            ClientDef(
                clientId = client,
                name = "Grants demo",
                // Shown to visitors on the home page's demo card and the Join page, so it says what the demo is.
                description = "A small community fund's grant program, in the fund's own words: its home page, its forms " +
                    "list, and its mails all speak the fund's language rather than the platform's.",
                usageType = ClientUsageType.demo,
                audience = ClientAudience.internal,
                enabledEnvironments = setOf(ENV.unit, ENV.local, ENV.dev),
                includedTraits = listOf(GT.name),
                userLabels = listOf(GrantsDemo.reviewerLabel),
                userLabelNotes = mapOf(GrantsDemo.reviewerLabel to "Give this to the people who score applications."),
                // No administrator's role: a visitor sees the fund's words, and cannot change them for everyone else.
                joinRoles = listOf(
                    ClientJoinRole(
                        name = GrantsDemo.applicantRole,
                        label = "Applicant",
                        persona = PERSONA.member,
                        summary = "Apply for a grant, and see the forms list, the steps, and the mails in the fund's words.",
                    ),
                    ClientJoinRole(
                        name = GrantsDemo.reviewerRole,
                        label = "Reviewer",
                        // An editor: it sees every application, and administers nothing.
                        persona = PERSONA.editor,
                        userLabels = listOf(GrantsDemo.reviewerLabel),
                        summary = "Score the applications other visitors make.",
                    ),
                ),
            ),
        )

        trait(GrantsDemo.requestEntry, GrantsDemo.request, setOf(GedraDataType.formDoc), "What an application asks the fund for.") {
            title = "Request"
            property(GrantsDemo.amount, "The amount asked for, in whole dollars.", required = true) {
                title = "Amount requested (dollars)"
                type = SCT.integer
                minimum = 100
                maximum = 5000
            }
        }
        type(GrantsDemo.budgetLineType) {
            type = SCT.kObject
            property("item", "What the money pays for.", required = true) { title = "Item" }
            property("cost", "What it costs, in whole dollars.") {
                title = "Cost (dollars)"
                type = SCT.integer
            }
        }
        trait(GrantsDemo.budgetEntry, GrantsDemo.budget, setOf(GedraDataType.formDoc), "What the grant pays for.") {
            title = "Budget"
            property(GrantsDemo.lines, "One line per thing the grant pays for.") {
                title = "Budget lines"
                type = SCT.array
                items { ref(GrantsDemo.budgetLineType) }
            }
        }
        trait(GrantsDemo.scoreEntry, GrantsDemo.scoreTrait, setOf(GedraDataType.formDoc), "A reviewer's score of the application.") {
            title = "Score"
            property(GrantsDemo.scoreField, "The score, from 1 to 5.") {
                title = "Score"
                type = SCT.integer
                minimum = 1
                maximum = 5
            }
            property(GrantsDemo.notes, "What the reviewer tells the applicant.") {
                title = "Notes for the applicant"
                longText = true
            }
        }

        traitUsage(GT.name, "Project", $$"${name}", substring = true)
        traitUsage(GrantsDemo.request, "Amount", $$"${amount}")
    }

/** The `workflows` bundle: making an application, its budget, and a reviewer's score. */
fun grantsDemoWorkflows(cxt: KdrCxtBase = LiteCxt(), client: String = GrantsDemo.client): GedraConfig =
    gedraConfig(cxt, GrantsDemo.workflowsConfig, clientNamespace(client), client) {
        workflow(GrantsDemo.applyWorkflow, WfEntry.creation) {
            label = "Start an application"
            task(GrantsDemo.nameTask, "Name your project") {
                trait(GT.name)
                trait(GrantsDemo.request)
                save(GrantsDemo.createSave, "Create the application", WfSaveKind.create)
            }
        }
        workflow(GrantsDemo.budgetWorkflow, WfEntry.normal) {
            label = "Budget"
            task(GrantsDemo.listTask, "List what the grant pays for") {
                trait(GrantsDemo.budget)
                save(GrantsDemo.saveBudget, "Save the budget", WfSaveKind.edit)
            }
        }
        workflow(GrantsDemo.reviewWorkflow, WfEntry.normal) {
            label = "Review"
            // Its Begin is highlighted for a reviewer, who may act on its task.
            highlightStart = true
            task(GrantsDemo.scoreTask, "Score the application") {
                keyword(CTAK.review)
                trait(GrantsDemo.scoreTrait)
                save(GrantsDemo.saveScore, "Save the score", WfSaveKind.edit)
                function(userHasLabel { label = GrantsDemo.reviewerLabel })
                saveWhen(WFC.reviewer)
            }
            lock(GrantsDemo.scoreTrait, writableVia = GrantsDemo.scoreTask, overrideWhen = CFACTS.hasAdminLevel)
        }
    }

/** The `reports` bundle: the applications, with where each stands in its review. */
fun grantsDemoReports(cxt: KdrCxtBase = LiteCxt(), client: String = GrantsDemo.client): GedraConfig =
    gedraConfig(cxt, GrantsDemo.reportsConfig, clientNamespace(client), client) {
        val form = ReportSource.form.name
        report(GrantsDemo.applicationsReport, "Applications") {
            description = "Every application, with what it asks for and where its review stands."
            column("project", "Project", "$form.${GT.name}.${GT.nameField}")
            column("amount", "Amount requested", "$form.${GrantsDemo.request}.${GrantsDemo.amount}", rollup = ReportCombine.sum)
            column("status", "Status", "${ReportSource.meta.name}.${RMETA.formStatus}")
            column("review", "Review", "${ReportSource.workflow.name}.${GrantsDemo.reviewWorkflow}.${RWF.ctaKeyword}")
            column("score", "Score", "$form.${GrantsDemo.scoreTrait}.${GrantsDemo.scoreField}")
        }
    }

/** The `siteWords` bundle: what the fund calls its site, in the app bar and on the browser tab. */
fun grantsDemoSiteWords(cxt: KdrCxtBase = LiteCxt(), client: String = GrantsDemo.client): GedraConfig =
    gedraConfig(cxt, GrantsDemo.siteWordsConfig, clientNamespace(client), client) {
        fragmentOverlay(HFRAG.home) {
            namespace(HFRAG.homeNs) { key(HFRAG.brand, "Riverbend Fund") }
            namespace(HFRAG.siteNs) { key(HFRAG.title, "Riverbend Community Fund grants") }
        }
    }

/** The `homeWords` bundle: the home page's title, and its introduction. */
fun grantsDemoHomeWords(cxt: KdrCxtBase = LiteCxt(), client: String = GrantsDemo.client): GedraConfig =
    gedraConfig(cxt, GrantsDemo.homeWordsConfig, clientNamespace(client), client) {
        fragmentOverlay(HFRAG.home) {
            namespace(HFRAG.homeNs) {
                key(HFRAG.title, "Riverbend Community Fund")
                key(
                    HFRAG.intro,
                    "The **Riverbend Community Fund** gives small grants to neighborhood projects: a garden, a mural, a " +
                        "repair café. Ask for up to $5,000, say what it pays for, and the fund's reviewers score your " +
                        "application.\n\n" +
                        "To apply, open your forms from the menu and choose **Create a form**. Each application then shows " +
                        "its next step to **Begin**. Reviewers find the applications waiting for them under **To score**.",
                )
            }
        }
    }

/** The `formsWords` bundle: what the forms list, a workflow's page, a form, and a report call things. */
fun grantsDemoFormsWords(cxt: KdrCxtBase = LiteCxt(), client: String = GrantsDemo.client): GedraConfig =
    gedraConfig(cxt, GrantsDemo.formsWordsConfig, clientNamespace(client), client) {
        fragmentOverlay(HFRAG.home) {
            namespace(HFRAG.formsNs) {
                key(HFRAG.noWorkflows, "No steps yet")
                key(HFRAG.waitingToggle, "Needs you")
                key(
                    HFRAG.explanation,
                    "Each row is one **application**: a request for a grant, and everything entered for it. **Contains** " +
                        "lists its parts.\n\n" +
                        "An application goes through **steps**: its budget, and a reviewer's score. Each is something " +
                        "somebody does, and the column beside the application shows where it stands in each.",
                )
            }
            namespace(HFRAG.workflowCellNs) {
                key(HFRAG.wcStart, "Begin")
                key(HFRAG.wcContinue, "Pick up")
                key(HFRAG.wcWaiting, "Waiting its turn")
                key(HFRAG.wcWaitingOnOwner, "With the applicant")
                key(HFRAG.wcWaitingOnOthers, "With someone else")
                key(HFRAG.wcWaitingForApproval, "Awaiting the fund's approval")
                key(HFRAG.wcYourTurnGroup, "Yours to do")
                key(HFRAG.wcAvailableGroup, "Ready to begin")
                key(HFRAG.wcWaitingGroup, "With others")
                key(HFRAG.wcFinishedGroup, "Done")
                key(HFRAG.wcYourTurnCount, $$"${n} yours to do")
                key(HFRAG.wcAvailableCount, $$"${n} ready to begin")
                key(HFRAG.wcWaitingCount, $$"${n} with others")
                key(HFRAG.wcFinishedCount, $$"${n} done")
                key(HFRAG.wcNotStarted, "This application has not begun this step. Begin it to work on it.")
            }
            namespace(HFRAG.formEntriesNs) { key(HFRAG.formEntryLabel, $$"Line ${n}") }
            namespace(CtaKeywordCopy.homeNamespace(CTAK.review)) {
                key(CTKF.verb, "Score")
                key(CTKF.filterLabel, "Needs a score")
                key(CTKF.columnHeader, "To score")
                key(CTKF.reportLabel, "Needs a score")
                key(CTKF.statusText, "Needs a Score")
            }
        }
    }

/** The `mailWords` bundle: the mails the fund's people are sent, and how every mail closes and looks. */
fun grantsDemoMailWords(cxt: KdrCxtBase = LiteCxt(), client: String = GrantsDemo.client): GedraConfig =
    gedraConfig(cxt, GrantsDemo.mailWordsConfig, clientNamespace(client), client) {
        fragmentOverlay(AFRAG.mail) {
            namespace(MCOPY.common) {
                key(MCOPY.fromName, "Riverbend Community Fund")
                key(MCOPY.footer, "From the grants team at the Riverbend Community Fund.")
                key(MCOPY.sentFrom, $$"Sent from the fund's grant site, ${siteHost}.")
                key(MCOPY.htmlStyle, "font-family: Georgia, serif; font-size: 16px; line-height: 1.6; color: #1f3b2d;")
            }
            namespace(MCOPY.verifyCode) {
                key(MCOPY.subject, "Your Riverbend sign-in code")
                key(
                    MCOPY.body,
                    $$"Your verification code is [${code}]{.code}.\n\n" +
                        "Enter it on the Riverbend grant site to sign in. It expires in fifteen minutes.",
                )
            }
            namespace(MCOPY.verifyCodePassword) {
                key(MCOPY.subject, "Your Riverbend password code")
                key(
                    MCOPY.body,
                    $$"Your verification code is [${code}]{.code}.\n\n" +
                        "Enter it to set or change your password for the Riverbend grant site. It expires in fifteen minutes.",
                )
            }
            namespace(MCOPY.invitation) {
                key(MCOPY.subject, "Your account with the Riverbend Community Fund")
                key(
                    MCOPY.body,
                    $$"The Riverbend Community Fund has made you an account on its grant site, for [${address}]{.value}, as " +
                        $$"[${personaLabel}]{.value}.\n\n" +
                        $$"Accept it and sign in here: ${url}\n\n" +
                        "If the link does not work for you, go to the login page, choose \"Claim an account created for " +
                        "you\", and enter:\n\n" +
                        $$"- Email: [${address}]{.value}\n" +
                        $$"- Client ID: [${client}]{.value}\n" +
                        $$"- Persona: [${personaLabel}]{.value}\n\n" +
                        $$"A code will then be sent to [${address}]{.value}.\n\n" +
                        "The link expires in seven days.",
                )
            }
            namespace(MCOPY.signInReminder) {
                key(MCOPY.subject, "Signing in to the Riverbend grant site")
                key(
                    MCOPY.body,
                    $$"You have an account for [${address}]{.value} on the Riverbend Community Fund's grant site, as " +
                        $$"[${personaLabel}]{.value}.\n\n" +
                        $$"Sign in here: ${url}\n\n" +
                        "Use this address, with your password if you set one, or a code sent to it.",
                )
            }
            namespace(MCOPY.claimCode) {
                key(MCOPY.subject, "Claiming your Riverbend account")
                key(
                    MCOPY.body,
                    $$"Your verification code for claiming the account [${address}]{.value} at the Riverbend Community Fund " +
                        $$"as [${personaLabel}]{.value} is [${code}]{.code}.\n\n" +
                        "Enter it on the page where you asked for it. It expires in fifteen minutes.",
                )
            }
            namespace(MCOPY.claimNoMatchInClient) {
                key(MCOPY.subject, "Claiming your Riverbend account")
                key(
                    MCOPY.body,
                    "We could not find a Riverbend account matching what was entered on the claim page:\n\n" +
                        $$"- Email: [${address}]{.value}\n" +
                        $$"- Client ID: [${client}]{.value}\n" +
                        $$"- Persona: [${personaLabel}]{.value}\n\n" +
                        "This address does have an account with the fund. Check the persona (and persona suffix) against " +
                        "what your invitation gave you.",
                )
            }
        }
    }

/** Every bundle of the demo, in the order they are written: the client first, since the others need it to exist. */
fun grantsDemoConfigs(cxt: KdrCxtBase = LiteCxt(), client: String = GrantsDemo.client): List<GedraConfig> =
    listOf(
        grantsDemoBase(cxt, client), grantsDemoWorkflows(cxt, client), grantsDemoReports(cxt, client),
        grantsDemoSiteWords(cxt, client), grantsDemoHomeWords(cxt, client), grantsDemoFormsWords(cxt, client),
        grantsDemoMailWords(cxt, client),
    )

/**
 * Provisions the demo: each bundle written, published, and made live on every node, then an applicant, a reviewer, and
 * the fund's administrator to sign in as. [suffix] makes a fresh copy, `grantsdemo<suffix>` (see [SIM.suffix]). A rerun
 * rewrites the demo's configuration and keeps its users and forms. With [skipUnchanged], as every start of a reference
 * deployment provisions it, a bundle already published as it is written is left alone, adding no revision.
 */
fun provisionGrantsDemo(cxt: KdrCxt, suffix: String? = null, skipUnchanged: Boolean = false): SimulationReport {
    val client = GrantsDemo.client + Simulations.checkedSuffix(suffix)
    val configs = GedraConfigService.get(cxt)
    val bound = cxt.mkSubContext("grantsDemo", client)
    for (config in grantsDemoConfigs(cxt, client)) {
        if (skipUnchanged && configs.publishedAsIs(bound, config)) continue
        Simulations.provisionConfig(cxt, config)
    }
    val admin = Simulations.provisionUser(cxt, "fund@$client.example", client, ROLE.admin, name = "Morgan Manager")
    val applicant = Simulations.provisionUser(cxt, "applicant@$client.example", client, ROLE.user, name = "Avery Applicant")
    val editor = listOf(ROLE.editor)
    val reviewer = Simulations.provisionUser(
        cxt, "reviewer@$client.example", client, ROLE.user, name = "Robin Reviewer", capabilities = editor,
    )
    Simulations.setLabels(cxt, reviewer, listOf(GrantsDemo.reviewerLabel))
    return SimulationReport(
        clients = listOf(client),
        users = listOf(
            Simulations.reported(applicant, ROLE.user, "an applicant -- makes applications"),
            Simulations.reported(reviewer, ROLE.user, "a reviewer -- an editor carrying the 'reviewer' label, who scores them", editor),
            Simulations.reported(admin, ROLE.admin, "the fund's administrator -- rewords the client, and invites people"),
        ),
        startPage = "page=${HMENU.pageForms}",
        summary = "Client '$client' is ready. Sign in as the applicant to apply, in the fund's words.",
    )
}

/** The demo's simulation, in `kdn`'s own module: a test instance offers it beside `common`'s. */
fun grantsDemoSimulationSchema(cxt: KdrCxt): SchModule = schemaModule(cxt, "kdr.grantsdemo") {
    simulationEndpoint(
        GrantsDemo.simulationName,
        "Provisions the grants demo -- the client the documentation in docs/grants-demo walks through, which rewords every " +
            "piece of copy a client is likely to reword -- and an applicant, a reviewer and an administrator to sign in as. " +
            "Give a suffix for a fresh copy (grantsdemo2). A rerun rewrites that client's configuration; its users and " +
            "forms stay.",
        inputFields = {
            field(SIM.suffix, "Appended to the client id for a fresh copy; lowercase letters and digits. Absent, the demo client itself.")
        },
    ) { c, request -> provisionGrantsDemo(c, request[SIM.suffix].toOptStr()) }
}
