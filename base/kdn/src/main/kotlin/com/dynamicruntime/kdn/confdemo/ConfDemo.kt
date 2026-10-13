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
import com.dynamicruntime.common.gedra.GedraConfig
import com.dynamicruntime.common.gedra.gedraConfig
import com.dynamicruntime.common.gedra.workflow.WfEntry
import com.dynamicruntime.common.gedra.workflow.WfSaveKind
import com.dynamicruntime.common.home.HMENU
import com.dynamicruntime.common.http.request.ROLE
import com.dynamicruntime.common.naming.clientNamespace
import com.dynamicruntime.common.simulation.SimulationReport
import com.dynamicruntime.common.simulation.Simulations
import com.dynamicruntime.common.simulation.simulationEndpoint
import com.dynamicruntime.common.test.SIM
import com.dynamicruntime.common.util.toOptStr

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

    const val createTalkWorkflow = "createTalk"
    const val nameTalkTask = "nameTalk"
    const val createSave = "create"

    /** Where the documentation lives in the repository, and the prefix its pages take as doc ids. */
    const val docsDir = "docs/demo-client"
    const val docIdPrefix = "demo-client-"
}

/**
 * The demo's documentation (issue #1136): the opening page is listed on the home page's Documents list; the pages it
 * leads to are linked from it and not listed. The build copies `docs/demo-client/<name>.md` onto the classpath as
 * `demo-client-<name>.md`, so the ids here follow the file names.
 */
fun confDemoDocs(): List<MarkdownDocDef> = listOf(
    confDemoDoc("README", "Conference demo", "A worked example of a client built from configuration: its JSON, piece by piece.", listed = true),
    confDemoDoc("create-talk", "Conference demo: creating a talk", "The demo's creation workflow."),
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
            ),
        )
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

/** Every bundle of the demo, in the order they are written: the client first, since the others need it to exist. */
fun confDemoConfigs(cxt: KdrCxtBase = LiteCxt(), client: String = ConfDemo.client): List<GedraConfig> =
    listOf(confDemoBase(cxt, client), confDemoCreateTalk(cxt, client))

/**
 * Provisions the demo (issue #1137): each bundle written, published, and made live on every node, then an organizer and
 * a speaker to sign in as. [suffix] makes a fresh copy, `confdemo<suffix>` (see [SIM.suffix]).
 */
fun provisionConfDemo(cxt: KdrCxt, suffix: String? = null): SimulationReport {
    val client = ConfDemo.client + Simulations.checkedSuffix(suffix)
    for (config in confDemoConfigs(cxt, client)) Simulations.provisionConfig(cxt, config)
    val organizer = Simulations.provisionUser(cxt, "organizer@$client.example", client, ROLE.admin, name = "Olive Organizer")
    val speaker = Simulations.provisionUser(cxt, "speaker@$client.example", client, ROLE.user, name = "Pat Speaker")
    return SimulationReport(
        clients = listOf(client),
        users = listOf(
            Simulations.reported(speaker, ROLE.user, "a speaker -- submits talks"),
            Simulations.reported(organizer, ROLE.admin, "an organizer -- an administrator, who sees every talk"),
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
