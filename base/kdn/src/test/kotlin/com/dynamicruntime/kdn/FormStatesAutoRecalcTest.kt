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
 * Its own instance, with the limit at [limit], so a client over it needs only a few forms. A client per case.
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
