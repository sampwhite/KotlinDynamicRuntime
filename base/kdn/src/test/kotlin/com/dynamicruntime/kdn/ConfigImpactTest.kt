package com.dynamicruntime.kdn

import com.dynamicruntime.common.context.ENV
import com.dynamicruntime.common.endpoint.EP
import com.dynamicruntime.common.exception.EXC
import com.dynamicruntime.common.gedra.CFEP
import com.dynamicruntime.common.gedra.ConfigImpact
import com.dynamicruntime.common.gedra.ClientAudience
import com.dynamicruntime.common.gedra.ClientDef
import com.dynamicruntime.common.gedra.ClientUsageType
import com.dynamicruntime.common.gedra.GDF
import com.dynamicruntime.common.gedra.GE
import com.dynamicruntime.common.gedra.GEP
import com.dynamicruntime.common.gedra.GedraConfigBuilder
import com.dynamicruntime.common.gedra.GedraConfigReload
import com.dynamicruntime.common.gedra.GedraConfigService
import com.dynamicruntime.common.gedra.GedraConfigType
import com.dynamicruntime.common.gedra.GedraDataType
import com.dynamicruntime.common.gedra.GedraId
import com.dynamicruntime.common.gedra.IMP
import com.dynamicruntime.common.gedra.ImpactKind
import com.dynamicruntime.common.gedra.gedraConfig
import com.dynamicruntime.common.gedra.sandboxOf
import com.dynamicruntime.common.gedra.workflow.WFD
import com.dynamicruntime.common.gedra.workflow.WfEntry
import com.dynamicruntime.common.gedra.workflow.WfSaveKind
import com.dynamicruntime.common.http.request.ROLE
import com.dynamicruntime.common.naming.clientNamespace
import com.dynamicruntime.common.simulation.ImpactDemo
import com.dynamicruntime.common.simulation.provisionImpactDemo
import com.dynamicruntime.common.user.AEP
import com.dynamicruntime.common.user.TestUser
import com.dynamicruntime.common.util.toJsonListOfMaps
import com.dynamicruntime.common.util.toJsonListOfStrings
import com.dynamicruntime.common.util.toJsonMapOrEmpty
import com.dynamicruntime.common.util.toOptStr
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain

/**
 * The publish impact report (issue #935): what publishing one stored configuration would do to the data its client
 * already stores. A dropped trait in use, a narrowed one, a removed workflow and a task a form's state names are each
 * reported with their rows; a harmless change reports nothing; a publish with something to report is refused unless
 * acknowledged; from a sandbox, the report is about the parent's data; and it covers only the configuration named.
 *
 * One booted instance; a published-only client per case (a sandbox makes it so), its configuration in three bundles
 * so a case changes one: `main` (the definition), `traits`, and `flows` (a workflow over them).
 */
class ConfigImpactTest : StringSpec({
    val cxt = TestInstances.default("configImpact935")
    val svc = GedraConfigService.get(cxt)

    /** The `traits` bundle: `memo` (with [memoRequired] when given, a newly required field) and, unless dropped, `extra`. */
    fun GedraConfigBuilder.traits(withExtra: Boolean = true, memoRequired: String? = null) {
        trait("MemoEntry", "memo", setOf(GedraDataType.formDoc), "A memo.") {
            property("text", "What it says.")
            memoRequired?.let { property(it, "A field every memo now needs.", required = true) }
        }
        trait("SignoffEntry", "signoff", setOf(GedraDataType.formDoc), "Who signed it off.") { property("by", "Who.") }
        if (withExtra) trait("ExtraEntry", "extra", setOf(GedraDataType.formDoc), "A note.") { property("note", "A note.") }
    }

    /** The `flows` bundle: `review`, drafting a memo then signing it off (unless [withCheck] is false), and `aside`. */
    fun GedraConfigBuilder.flows(withCheck: Boolean = true, withAside: Boolean = true) {
        workflow("review", WfEntry.normal) {
            task("draft", "Draft it") { trait("memo"); save("saveMemo", "Save", WfSaveKind.edit) }
            if (withCheck) task("check", "Sign it off") { trait("signoff"); save("saveSignoff", "Save", WfSaveKind.edit) }
        }
        if (withAside) {
            workflow("aside", WfEntry.normal) {
                task("note", "Note it") { trait("memo"); save("saveAside", "Save", WfSaveKind.edit) }
            }
        }
    }

    fun write(client: String, name: String, build: GedraConfigBuilder.() -> Unit) =
        svc.writeConfig(cxt.mkSubContext("setup", client).also { it.userId = 9350L }, gedraConfig(cxt, name, clientNamespace(client), client, build = build))

    fun publish(client: String, name: String) =
        svc.publish(cxt.mkSubContext("setup", client).also { it.userId = 9350L }, GedraId.of(GedraConfigType.configDoc, client, name))

    /** Defines [client] -- with a sandbox, so published-only -- publishes its three bundles, and returns an admin of it. */
    fun defineClient(client: String): TestUser {
        write(client, "main") {
            defineClient(
                ClientDef(
                    clientId = client, name = client, usageType = ClientUsageType.dev, audience = ClientAudience.internal,
                    enabledEnvironments = setOf(ENV.unit, ENV.local), sandbox = true,
                ),
            )
        }
        write(client, "traits") { traits() }
        write(client, "flows") { flows() }
        listOf("main", "traits", "flows").forEach { publish(client, it) }
        GedraConfigReload.reloadClient(cxt, client)
        return TestUser.create(cxt, "chief@$client.test", level = ROLE.admin, userClient = client)
    }

    fun entry(traitId: String, vararg data: Pair<String, Any?>) = mapOf(GE.traitId to traitId, GE.data to data.toMap())

    /** A form [user] creates holding [entries]; returns its gedra id. */
    fun form(user: TestUser, vararg entries: Map<String, Any?>): String =
        user.postItem(GEP.formDocCreate, mapOf(GDF.entries to entries.toList()))[GDF.gedraId].toOptStr()!!

    fun report(user: TestUser, name: String): Map<String, Any?> = user.getItem(CFEP.bundleImpact, mapOf(CFEP.name to name))

    fun findings(report: Map<String, Any?>): List<Map<String, Any?>> = report[IMP.findings].toJsonListOfMaps()

    "a candidate that drops a trait in use reports the forms carrying it" {
        val client = "impgone"
        val admin = defineClient(client)
        val user = TestUser.create(cxt, "u@$client.test", userClient = client)
        val withExtra = listOf(form(user, entry("extra", "note" to "a")), form(user, entry("extra", "note" to "b"), entry("memo", "text" to "m")))
        form(user, entry("memo", "text" to "only a memo"))

        write(client, "traits") { traits(withExtra = false) }
        val result = report(admin, "traits")
        result[IMP.client] shouldBe client
        result[IMP.scanned] shouldBe 3
        val gone = findings(result).single()
        gone[IMP.kind] shouldBe ImpactKind.traitGone.name
        gone[IMP.traitId] shouldBe "extra"
        gone[IMP.count] shouldBe 2
        gone[IMP.sampleIds].toJsonListOfStrings() shouldContainExactly withExtra.sorted()
    }

    "narrowing a trait reports the forms whose entries would no longer validate, and only those" {
        val client = "impnarrow"
        val admin = defineClient(client)
        val user = TestUser.create(cxt, "u@$client.test", userClient = client)
        val memo = form(user, entry("memo", "text" to "m"))
        form(user, entry("extra", "note" to "n"))

        // A newly required field: every stored memo lacks it.
        write(client, "traits") { traits(memoRequired = "owner") }
        val invalid = findings(report(admin, "traits")).single()
        invalid[IMP.kind] shouldBe ImpactKind.dataInvalid.name
        invalid[IMP.traitId] shouldBe "memo"
        invalid[IMP.sampleIds].toJsonListOfStrings() shouldContainExactly listOf(memo)
    }

    "a workflow change that strands a task, or removes a workflow, reports the forms in it" {
        val client = "impflow"
        val admin = defineClient(client)
        val user = TestUser.create(cxt, "u@$client.test", userClient = client)
        val gid = form(user)
        // Engaged in review with the draft saved, so its state names `check` as the task to do next.
        user.postData(GEP.workflowEngage, mapOf(GDF.gedraId to gid, WFD.workflowId to "review"))
        user.postData(
            GEP.workflowSave,
            mapOf(WFD.workflowId to "review", GDF.taskId to "draft", GDF.saveId to "saveMemo", GDF.gedraId to gid, GDF.entries to listOf(entry("memo", "text" to "m"))),
        )
        user.postData(GEP.workflowEngage, mapOf(GDF.gedraId to gid, WFD.workflowId to "aside"))
        user.postData(GEP.formDocRecomputeState, mapOf(GDF.gedraId to gid))

        write(client, "flows") { flows(withCheck = false, withAside = false) }
        val byKind = findings(report(admin, "flows")).associateBy { it[IMP.kind] }
        byKind.keys shouldBe setOf(ImpactKind.stateStranded.name, ImpactKind.workflowGone.name)
        byKind.getValue(ImpactKind.stateStranded.name).let {
            it[IMP.workflowId] shouldBe "review"
            it[IMP.taskId] shouldBe "check"
            it[IMP.sampleIds].toJsonListOfStrings() shouldContainExactly listOf(gid)
        }
        byKind.getValue(ImpactKind.workflowGone.name)[IMP.workflowId] shouldBe "aside"
    }

    "a harmless change reports nothing and publishes without acknowledgement" {
        val client = "impharmless"
        val admin = defineClient(client)
        val user = TestUser.create(cxt, "u@$client.test", userClient = client)
        form(user, entry("memo", "text" to "m"), entry("extra", "note" to "n"))

        // A new optional field, and a new trait.
        write(client, "traits") {
            traits()
            trait("TagEntry", "tag", setOf(GedraDataType.formDoc), "A tag.") { property("label", "The tag.") }
        }
        val result = report(admin, "traits")
        result[IMP.scanned] shouldBe 1
        result[IMP.tooLarge] shouldBe false
        findings(result).shouldBeEmpty()
        admin.postData(CFEP.bundlePublish, mapOf(CFEP.name to "traits"))[CFEP.published] shouldBe true
    }

    "a publish with something to report is refused with the report, and goes through acknowledged" {
        val client = "imprefuse"
        val admin = defineClient(client)
        val user = TestUser.create(cxt, "u@$client.test", userClient = client)
        form(user, entry("extra", "note" to "n"))
        write(client, "traits") { traits(withExtra = false) }

        val refused = admin.expectError(EXC.badInput, CFEP.bundlePublish, data = mapOf(CFEP.name to "traits"))
        refused[EP.errorCode] shouldBe IMP.refusedCode
        refused[EP.errorMessage].toOptStr().orEmpty() shouldContain "trait 'extra' would no longer be supported"
        val carried = refused[EP.extraData].toJsonMapOrEmpty()[IMP.report].toJsonMapOrEmpty()
        findings(carried).single()[IMP.kind] shouldBe ImpactKind.traitGone.name
        // Nothing was published: the draft is still a draft.
        admin.getItems(CFEP.bundles).single { it[CFEP.name] == "traits" }[CFEP.published] shouldBe false

        admin.postData(CFEP.bundlePublish, mapOf(CFEP.name to "traits", IMP.acknowledgeImpact to true))[CFEP.published] shouldBe true
    }

    "from a sandbox, the report is about the parent's data, not the sandbox's" {
        val client = "impsandbox"
        val admin = defineClient(client)
        val user = TestUser.create(cxt, "u@$client.test", userClient = client)
        val parentForm = form(user, entry("extra", "note" to "parent"))
        val sandboxUser = TestUser.create(cxt, "u@$client.test", userClient = sandboxOf(client))
        form(sandboxUser, entry("extra", "note" to "sandbox one"))
        form(sandboxUser, entry("extra", "note" to "sandbox two"))

        write(client, "traits") { traits(withExtra = false) }
        admin.postData(AEP.openSandbox, emptyMap())
        val result = report(admin, "traits")
        result[IMP.client] shouldBe client
        findings(result).single()[IMP.sampleIds].toJsonListOfStrings() shouldContainExactly listOf(parentForm)
    }

    "the report covers only the configuration named, and a client that is not published-only has none" {
        val client = "impnamed"
        val admin = defineClient(client)
        val user = TestUser.create(cxt, "u@$client.test", userClient = client)
        form(user, entry("extra", "note" to "n"))
        write(client, "traits") { traits(withExtra = false) }
        write(client, "flows") { flows() }
        findings(report(admin, "flows")).shouldBeEmpty()
        findings(report(admin, "traits")).single()[IMP.kind] shouldBe ImpactKind.traitGone.name

        // A client on the latest tier already runs every latest revision: publishing changes nothing it runs.
        val onLatest = "implatest"
        write(onLatest, "main") {
            defineClient(
                ClientDef(
                    clientId = onLatest, name = onLatest, usageType = ClientUsageType.dev, audience = ClientAudience.internal,
                    enabledEnvironments = setOf(ENV.unit, ENV.local),
                ),
            )
            traits()
        }
        publish(onLatest, "main")
        GedraConfigReload.reloadClient(cxt, onLatest)
        val latestAdmin = TestUser.create(cxt, "chief@$onLatest.test", level = ROLE.admin, userClient = onLatest)
        form(TestUser.create(cxt, "u@$onLatest.test", userClient = onLatest), entry("extra", "note" to "n"))
        write(onLatest, "main") {
            defineClient(
                ClientDef(
                    clientId = onLatest, name = onLatest, usageType = ClientUsageType.dev, audience = ClientAudience.internal,
                    enabledEnvironments = setOf(ENV.unit, ENV.local),
                ),
            )
            traits(withExtra = false)
        }
        report(latestAdmin, "main").let {
            it[IMP.scanned] shouldBe 0
            findings(it).shouldBeEmpty()
        }
    }
    "the impact-demo simulation leaves a draft whose report finds its forms, and a rerun is safe" {
        val client = "${ImpactDemo.client}t"
        fun demoReport() = ConfigImpact.report(cxt.mkSubContext("check", client), client, ImpactDemo.traits)
        provisionImpactDemo(cxt, "t").clients shouldContainExactly listOf(client, sandboxOf(client))
        val byKind = demoReport().findings.associateBy { it.kind }
        byKind.getValue(ImpactKind.traitGone).let { it.traitId shouldBe ImpactDemo.visit; it.rows.size shouldBe 2 }
        byKind.getValue(ImpactKind.dataInvalid).let { it.traitId shouldBe ImpactDemo.note; it.rows.size shouldBe 2 }

        // A rerun publishes the harmless traits over the draft, adds its forms again, and leaves the draft anew.
        provisionImpactDemo(cxt, "t")
        demoReport().findings.associateBy { it.kind }.getValue(ImpactKind.traitGone).rows.size shouldBe 4
    }
})
