package com.dynamicruntime.kdn

import com.dynamicruntime.common.context.ENV
import com.dynamicruntime.common.context.ReadScope
import com.dynamicruntime.common.gedra.CFEP
import com.dynamicruntime.common.gedra.CLD
import com.dynamicruntime.common.gedra.ClientAudience
import com.dynamicruntime.common.gedra.ClientDef
import com.dynamicruntime.common.gedra.ClientUsageType
import com.dynamicruntime.common.gedra.FSR
import com.dynamicruntime.common.gedra.FSRA
import com.dynamicruntime.common.gedra.GDF
import com.dynamicruntime.common.gedra.GE
import com.dynamicruntime.common.gedra.GEP
import com.dynamicruntime.common.gedra.GedraConfigReload
import com.dynamicruntime.common.gedra.GedraConfigService
import com.dynamicruntime.common.gedra.GedraConfigType
import com.dynamicruntime.common.gedra.GedraDataService
import com.dynamicruntime.common.gedra.GedraDataType
import com.dynamicruntime.common.gedra.GedraId
import com.dynamicruntime.common.gedra.autoRecalcSkipped
import com.dynamicruntime.common.gedra.formStatesAutoRecalcLimit
import com.dynamicruntime.common.gedra.gedraConfig
import com.dynamicruntime.common.gedra.gedraConfigToEntries
import com.dynamicruntime.common.gedra.sandboxOf
import com.dynamicruntime.common.gedra.workflow.WFD
import com.dynamicruntime.common.gedra.workflow.WFP
import com.dynamicruntime.common.gedra.workflow.WFS
import com.dynamicruntime.common.gedra.workflow.WfEntry
import com.dynamicruntime.common.gedra.workflow.WfSaveKind
import com.dynamicruntime.common.gedra.workflow.WorkflowService
import com.dynamicruntime.common.http.request.ROLE
import com.dynamicruntime.common.naming.clientNamespace
import com.dynamicruntime.common.user.TestUser
import com.dynamicruntime.common.user.UADEP
import com.dynamicruntime.common.util.toJsonMapOrEmpty
import com.dynamicruntime.common.util.toOptStr
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldNotContain
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe

/**
 * Recalculating a small client's form states automatically after a change to its configuration (issue #1741): an edit
 * that takes effect -- here the bundle editor's write, and a workflow's parameters -- brings the forms it reaches up to
 * date at once when the client holds no more forms than the deployment's limit, and says what it did in its result.
 *
 * Its own instance, with the limit at `limit`, so a client over it needs only a few forms. A client per case.
 */
class FormStatesAutoRecalcTest : StringSpec({
    val limit = 3
    val cxt = Startup.mkTestBootCxt("formStatesAuto1741", "formStatesAuto1741", mapOf(FSRA.limitKey to limit))
    val svc = GedraConfigService.get(cxt)

    /** Defines [client], published, with a trait and no workflow yet, and returns its administrator. */
    fun defineClient(client: String, manual: Boolean = false, sandbox: Boolean = false): TestUser {
        val config = gedraConfig(cxt, "main", clientNamespace(client), client) {
            defineClient(
                ClientDef(
                    clientId = client, name = client, usageType = ClientUsageType.dev, audience = ClientAudience.internal,
                    enabledEnvironments = setOf(ENV.unit, ENV.local), manualFormStates = manual, sandbox = sandbox,
                ),
            )
            trait("MemoEntry", "memo", setOf(GedraDataType.formDoc), "A memo.") { property("text", "What it says.") }
        }
        val setup = cxt.mkSubContext("setup", client).also { it.userId = 17410L }
        svc.writeConfig(setup, config)
        svc.publish(setup, listOf(GedraId.of(GedraConfigType.configDoc, client, "main")))
        GedraConfigReload.reloadClient(cxt, client)
        return TestUser.create(cxt, "chief@$client.test", level = ROLE.admin, userClient = client)
    }

    fun forms(client: String, n: Int): List<String> {
        val user = TestUser.create(cxt, "u@$client.test", userClient = client)
        return (1..n).map {
            val entry = mapOf(GE.traitId to "memo", GE.data to mapOf("text" to "m$it"))
            user.postItem(GEP.formDocCreate, mapOf(GDF.entries to listOf(entry)))[GDF.gedraId].toOptStr()!!
        }
    }

    /** Adds the workflow `review` with the bundle editor's write, asking for the reload as the editor does: an edit. */
    fun addWorkflow(admin: TestUser, client: String): Map<String, Any?> {
        val flows = gedraConfig(cxt, "flows", clientNamespace(client), client) {
            workflow("review", WfEntry.normal) {
                task("draft", "Draft it") { trait("memo"); save("saveMemo", "Save", WfSaveKind.edit) }
            }
        }
        return admin.postData(
            CFEP.bundleWrite,
            mapOf(
                CFEP.name to "flows", CFEP.namespaceField to clientNamespace(client),
                CFEP.slots to gedraConfigToEntries(flows), CFEP.reloadField to true,
            ),
        )
    }

    /** The workflows [gid]'s stored state has an entry for. */
    fun storedWorkflows(client: String, gid: String): List<String?> =
        GedraDataService.get(cxt).readState(cxt.mkSubContext("check", client), GedraId.parse(gid), ReadScope.ofClient(client))
            .filter { it[GE.traitId] == WFS.workflowState }
            .map { it[GE.data].toJsonMapOrEmpty()[WFD.workflowId].toOptStr() }

    fun auto(result: Map<String, Any?>): Map<String, Any?> = result[FSR.auto].toJsonMapOrEmpty()

    "a workflow added to a small client reaches its forms at once, and the result says so" {
        val client = "fsaAdd1741"
        val admin = defineClient(client)
        val gids = forms(client, limit)
        val done = auto(addWorkflow(admin, client))
        done[FSR.outcome] shouldBe FSR.completed
        done[FSR.client] shouldBe client
        done[FSR.updated] shouldBe limit.toLong()
        done[FSR.autoLimit] shouldBe limit.toLong()
        gids.forEach { storedWorkflows(client, it) shouldContain "review" }

        // A workflow's parameters are a change too: turning it off is recalculated the same way, every form looked at.
        val params = admin.postData(
            UADEP.clientWorkflowParams,
            mapOf(CLD.client to client, WFD.workflowId to "review", WFP.params to mapOf(WFP.enabled to false)),
        )
        auto(params)[FSR.outcome] shouldBe FSR.completed
        auto(params)[FSR.total] shouldBe limit.toLong()
    }

    "an editor's live save recalculates too, whatever it changed, and finds forms its change left current" {
        val client = "fsaDef1741"
        val admin = defineClient(client)
        forms(client, 2)
        val saved = admin.postData(UADEP.clientDefinitionSet, mapOf(CLD.client to client, CLD.name to "Renamed"))
        auto(saved)[FSR.outcome] shouldBe FSR.completed
        auto(saved)[FSR.client] shouldBe client
        auto(saved)[FSR.updated] shouldBe 0L
        auto(saved)[FSR.current] shouldBe 2L
    }

    "a draft reaches the sandbox's forms, which are the ones recalculated" {
        val client = "fsaDraft1741"
        val admin = defineClient(client, sandbox = true)
        val sandbox = sandboxOf(client)
        val gids = forms(sandbox, 2)
        val drafted = auto(addWorkflow(admin, client))
        drafted[FSR.outcome] shouldBe FSR.completed
        drafted[FSR.client] shouldBe sandbox
        drafted[FSR.updated] shouldBe 2L
        gids.forEach { storedWorkflows(sandbox, it) shouldContain "review" }
    }

    "a client holding more forms than the limit is left for the nightly run, and the result says how many and the limit" {
        val client = "fsaOver1741"
        val admin = defineClient(client)
        val gids = forms(client, limit + 1)
        val skipped = auto(addWorkflow(admin, client))
        skipped[FSR.outcome] shouldBe FSR.tooMany
        skipped[FSR.total] shouldBe (limit + 1).toLong()
        skipped[FSR.autoLimit] shouldBe limit.toLong()
        skipped[FSR.updated].shouldBeNull()
        gids.forEach { storedWorkflows(client, it) shouldNotContain "review" }
    }

    "a client that brings its forms up to date by hand is not recalculated, and Recalculate still does it" {
        val client = "fsaManual1741"
        val admin = defineClient(client, manual = true)
        val gid = forms(client, 1).single()
        auto(addWorkflow(admin, client))[FSR.outcome] shouldBe FSR.manual
        storedWorkflows(client, gid) shouldNotContain "review"
        admin.postData(UADEP.clientFormStatesRecalc, mapOf(CLD.client to client))[FSR.updated] shouldBe 1L
        storedWorkflows(client, gid) shouldContain "review"
    }

    "a client with no forms has nothing to bring up to date" {
        val client = "fsaEmpty1741"
        auto(addWorkflow(defineClient(client), client))[FSR.outcome] shouldBe FSR.noForms
    }

    "a write that asks for no reload changes nothing yet, so recalculates nothing" {
        val client = "fsaNoReload1741"
        val admin = defineClient(client)
        val gid = forms(client, 1).single()
        val flows = gedraConfig(cxt, "flows", clientNamespace(client), client) {
            workflow("review", WfEntry.normal) { task("draft", "Draft it") { trait("memo"); save("saveMemo", "Save", WfSaveKind.edit) } }
        }
        val written = admin.postData(
            CFEP.bundleWrite,
            mapOf(CFEP.name to "flows", CFEP.namespaceField to clientNamespace(client), CFEP.slots to gedraConfigToEntries(flows)),
        )
        written[FSR.auto].shouldBeNull()
        storedWorkflows(client, gid) shouldNotContain "review"
    }

    "a reload reads the client's configurations in the order the boot does, so re-saving one moves no form" {
        // Each bundle's workflow, written in the reverse of their names' order: a reload that read the bundles in the
        // order they were written, where the boot reads them by name, put the workflows -- and so every form's state
        // entries -- in another order, and every form read as out of date.
        val client = "fsaOrder1741"
        val admin = defineClient(client)
        fun write(bundle: String, workflowId: String): Map<String, Any?> {
            val flows = gedraConfig(cxt, bundle, clientNamespace(client), client) {
                workflow(workflowId, WfEntry.normal) {
                    task("draft", "Draft it") { trait("memo"); save("saveMemo", "Save", WfSaveKind.edit) }
                }
            }
            val slots = gedraConfigToEntries(flows)
            return admin.postData(
                CFEP.bundleWrite,
                mapOf(CFEP.name to bundle, CFEP.namespaceField to clientNamespace(client), CFEP.slots to slots, CFEP.reloadField to true),
            )
        }
        write("zFlows", "zReview")
        write("aFlows", "aReview")
        val normal = WorkflowService.get(cxt).forClient(client).workflows.values.filter { it.def.entry == WfEntry.normal }
        normal.map { it.def.workflowId } shouldBe listOf("aReview", "zReview")
        forms(client, 2)
        for ((bundle, workflowId) in listOf("zFlows" to "zReview", "aFlows" to "aReview")) {
            val again = auto(write(bundle, workflowId))
            again[FSR.updated] shouldBe 0L
            again[FSR.current] shouldBe 2L
        }
    }

    "why none runs: the client's choice first, then the deployment's limit, then the forms" {
        autoRecalcSkipped(manual = true, limit = 200, forms = 5)?.get(FSR.outcome) shouldBe FSR.manual
        autoRecalcSkipped(manual = false, limit = 0, forms = 5)?.get(FSR.outcome) shouldBe FSR.off
        autoRecalcSkipped(manual = false, limit = 200, forms = 0)?.get(FSR.outcome) shouldBe FSR.noForms
        autoRecalcSkipped(manual = false, limit = 200, forms = 201)?.get(FSR.outcome) shouldBe FSR.tooMany
        autoRecalcSkipped(manual = false, limit = 200, forms = 200).shouldBeNull()
        // The deployment's limit: this instance's, else 200.
        formStatesAutoRecalcLimit(cxt) shouldBe limit
        formStatesAutoRecalcLimit(TestInstances.default("formStatesAutoDefault1741")) shouldBe FSRA.defaultLimit
    }
})
