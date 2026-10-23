package com.dynamicruntime.kdn

import com.dynamicruntime.common.context.ENV
import com.dynamicruntime.common.endpoint.EP
import com.dynamicruntime.common.exception.EXC
import com.dynamicruntime.common.gedra.CCT
import com.dynamicruntime.common.gedra.CFEP
import com.dynamicruntime.common.gedra.ClientAudience
import com.dynamicruntime.common.gedra.ClientDef
import com.dynamicruntime.common.gedra.ClientUsageType
import com.dynamicruntime.common.gedra.GDF
import com.dynamicruntime.common.gedra.GE
import com.dynamicruntime.common.gedra.GED
import com.dynamicruntime.common.gedra.GEP
import com.dynamicruntime.common.gedra.GedraConfigBuilder
import com.dynamicruntime.common.gedra.GedraConfigReload
import com.dynamicruntime.common.gedra.GedraConfigService
import com.dynamicruntime.common.gedra.GedraConfigType
import com.dynamicruntime.common.gedra.GedraDataType
import com.dynamicruntime.common.gedra.GedraEditAction
import com.dynamicruntime.common.gedra.GedraId
import com.dynamicruntime.common.gedra.IMP
import com.dynamicruntime.common.gedra.gedraConfig
import com.dynamicruntime.common.gedra.workflow.WFD
import com.dynamicruntime.common.gedra.workflow.WfEntry
import com.dynamicruntime.common.gedra.workflow.WfSaveKind
import com.dynamicruntime.common.http.request.ROLE
import com.dynamicruntime.common.naming.clientNamespace
import com.dynamicruntime.common.user.TestUser
import com.dynamicruntime.common.util.toJsonListOfMaps
import com.dynamicruntime.common.util.toJsonMapOrEmpty
import com.dynamicruntime.common.util.toOptStr
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain

/**
 * One patch, and one publish, across several of a client's configurations (issue #1352). A workflow moved from one
 * configuration to another is the case: done as two separate changes there is always a moment when it is in both or
 * in neither. Added first, the backend refuses it; removed first, a client on its latest configuration runs it at
 * once and its forms lose the workflow. Made as one change, neither moment exists.
 *
 * One booted instance; a client per case, its configuration in three bundles -- `main` (the definition), `traits`,
 * and `flows` (the `review` workflow over them) -- and a form engaged in `review`, so the move has a form to strand.
 * The move puts `review` into `moved`, a configuration that does not exist until the patch creates it.
 */
class MultiBundlePatchTest : StringSpec({
    val cxt = TestInstances.default("multiBundle1352")
    val svc = GedraConfigService.get(cxt)

    fun GedraConfigBuilder.traits() {
        trait("MemoEntry", "memo", setOf(GedraDataType.formDoc), "A memo.") { property("text", "What it says.") }
        trait("SignoffEntry", "signoff", setOf(GedraDataType.formDoc), "Who signed it off.") { property("by", "Who.") }
    }

    fun GedraConfigBuilder.flows() {
        workflow("review", WfEntry.normal) {
            task("draft", "Draft it") { trait("memo"); save("saveMemo", "Save", WfSaveKind.edit) }
            task("check", "Sign it off") { trait("signoff"); save("saveSignoff", "Save", WfSaveKind.edit) }
        }
    }

    fun write(client: String, name: String, build: GedraConfigBuilder.() -> Unit) =
        svc.writeConfig(cxt.mkSubContext("setup", client).also { it.userId = 13520L }, gedraConfig(cxt, name, clientNamespace(client), client, build = build))

    /** Defines [client] in three bundles -- with a sandbox, and so published-only, when [sandbox] -- and returns its admin. */
    fun defineClient(client: String, sandbox: Boolean): TestUser {
        write(client, "main") {
            defineClient(
                ClientDef(
                    clientId = client, name = client, usageType = ClientUsageType.dev, audience = ClientAudience.internal,
                    enabledEnvironments = setOf(ENV.unit, ENV.local), sandbox = sandbox,
                ),
            )
        }
        write(client, "traits") { traits() }
        write(client, "flows") { flows() }
        val setup = cxt.mkSubContext("setup", client).also { it.userId = 13520L }
        svc.publish(setup, listOf("main", "traits", "flows").map { GedraId.of(GedraConfigType.configDoc, client, it) })
        GedraConfigReload.reloadClient(cxt, client)
        return TestUser.create(cxt, "chief@$client.test", level = ROLE.admin, userClient = client)
    }

    /** A form engaged in `review` with its draft saved, so its state names `check` as the task to do next. */
    fun engagedForm(client: String): Pair<TestUser, String> {
        val user = TestUser.create(cxt, "u@$client.test", userClient = client)
        val gid = user.postItem(GEP.formDocCreate, mapOf(GDF.entries to emptyList<Any?>()))[GDF.gedraId].toOptStr()!!
        user.postData(GEP.workflowEngage, mapOf(GDF.gedraId to gid, WFD.workflowId to "review"))
        user.postData(
            GEP.workflowSave,
            mapOf(
                WFD.workflowId to "review", GDF.taskId to "draft", GDF.saveId to "saveMemo", GDF.gedraId to gid,
                GDF.entries to listOf(mapOf(GE.traitId to "memo", GE.data to mapOf("text" to "m"))),
            ),
        )
        return user to gid
    }

    /** The workflow ids a configuration of client's latest revision holds. */
    fun workflowsIn(admin: TestUser, name: String): List<String?> =
        admin.getItem(CFEP.bundle, mapOf(CFEP.name to name))[CFEP.slots].toJsonMapOrEmpty()[CCT.workflowDef]
            .toJsonListOfMaps().map { it[CCT.workflowId].toOptStr() }

    /** `review` as the `flows` configuration stores it: the entry the move carries over. */
    fun reviewEntry(admin: TestUser): Map<String, Any?> =
        admin.getItem(CFEP.bundle, mapOf(CFEP.name to "flows"))[CFEP.slots].toJsonMapOrEmpty()[CCT.workflowDef]
            .toJsonListOfMaps().single { it[CCT.workflowId] == "review" }

    fun slotEdit(action: GedraEditAction, data: Map<String, Any?>) =
        mapOf(CFEP.slot to CCT.workflowDef, GED.action to action.name, GE.data to data)

    /** The patch body moving `review` from `flows` to `moved` as one change. */
    fun moveBody(review: Map<String, Any?>) = mapOf(
        CFEP.configs to listOf(
            mapOf(CFEP.name to "flows", CFEP.edits to listOf(slotEdit(GedraEditAction.deleteOrNoOp, mapOf(CCT.workflowId to "review")))),
            mapOf(CFEP.name to "moved", CFEP.edits to listOf(slotEdit(GedraEditAction.addOrReplace, review))),
        ),
    )

    /** The form can still do its next task, `check`: the workflow it is engaged in still resolves. */
    fun finishCheck(user: TestUser, gid: String) {
        user.postData(
            GEP.workflowSave,
            mapOf(
                WFD.workflowId to "review", GDF.taskId to "check", GDF.saveId to "saveSignoff", GDF.gedraId to gid,
                GDF.entries to listOf(mapOf(GE.traitId to "signoff", GE.data to mapOf("by" to "Pat"))),
            ),
        )
    }

    "a workflow moved between configurations as one patch: both are written, and the client reloads clean" {
        val client = "mbmove"
        val admin = defineClient(client, sandbox = false)
        val (user, gid) = engagedForm(client)
        val review = reviewEntry(admin)

        val written = admin.postData(CFEP.bundlePatch, moveBody(review) + (CFEP.reloadField to true))
        written[CFEP.configs].toJsonListOfMaps().map { it[CFEP.name] } shouldContainExactly listOf("flows", "moved")
        // `flows` gained a revision; `moved` was created by the patch.
        written[CFEP.configs].toJsonListOfMaps().map { it[CFEP.version] } shouldContainExactly listOf(2L, 1L)
        workflowsIn(admin, "flows").shouldBeEmpty()
        workflowsIn(admin, "moved") shouldContainExactly listOf("review")
        written[CFEP.reloadField].toJsonMapOrEmpty()[CFEP.issues].toJsonListOfMaps().shouldBeEmpty()

        finishCheck(user, gid)
    }

    "the same move as two patches: adding first is refused, and removing first strands the forms in the workflow" {
        val client = "mbsteps"
        val admin = defineClient(client, sandbox = false)
        val review = reviewEntry(admin)
        val body = moveBody(review)[CFEP.configs].toJsonListOfMaps()

        // Adding first puts `review` in two configurations at once.
        admin.expectError(EXC.badInput, CFEP.bundlePatch, mapOf(CFEP.configs to listOf(body[1])))
        // Nothing of the refused step was stored: `moved` was never created.
        admin.getItems(CFEP.bundles).map { it[CFEP.name] }.contains("moved") shouldBe false
        workflowsIn(admin, "flows") shouldContainExactly listOf("review")

        // Removing first is accepted, and the client loads clean -- but a client on its latest configuration runs it at
        // once, so a form engaged in `review` has no workflow until the second step lands. Hence one patch.
        val (user, gid) = engagedForm(client)
        val removed = admin.postData(CFEP.bundlePatch, mapOf(CFEP.configs to listOf(body[0])) + (CFEP.reloadField to true))
        removed[CFEP.reloadField].toJsonMapOrEmpty()[CFEP.issues].toJsonListOfMaps().shouldBeEmpty()
        user.expectError(
            EXC.notFound, GEP.workflowSave,
            mapOf(
                WFD.workflowId to "review", GDF.taskId to "check", GDF.saveId to "saveSignoff", GDF.gedraId to gid,
                GDF.entries to listOf(mapOf(GE.traitId to "signoff", GE.data to mapOf("by" to "Pat"))),
            ),
        )
    }

    "on a client with a sandbox the move is two drafts, published together, where either alone is refused" {
        val client = "mbsandbox"
        val admin = defineClient(client, sandbox = true)
        val (user, gid) = engagedForm(client)

        admin.postData(CFEP.bundlePatch, moveBody(reviewEntry(admin)))
        admin.getItems(CFEP.bundles).filter { it[CFEP.name] in setOf("flows", "moved") }.map { it[CFEP.published] } shouldBe listOf(false, false)

        // `flows` alone takes `review` away from the client's people, stranding the form engaged in it.
        admin.expectError(EXC.badInput, CFEP.bundlePublish, mapOf(CFEP.names to listOf("flows")))[EP.errorCode] shouldBe IMP.refusedCode
        // `moved` alone puts `review` in two published configurations.
        admin.expectError(EXC.badInput, CFEP.bundlePublish, mapOf(CFEP.names to listOf("moved")))
        // Together the client runs `review` as before, now from `moved`: nothing to acknowledge.
        // A query string carries the list comma-separated (or as JSON, as the webapp sends it).
        admin.getItem(CFEP.bundleImpact, mapOf(CFEP.names to "flows,moved"))[IMP.findings].toJsonListOfMaps().shouldBeEmpty()
        val published = admin.postData(CFEP.bundlePublish, mapOf(CFEP.names to listOf("flows", "moved"), CFEP.reloadField to true))
        published[CFEP.configs].toJsonListOfMaps().map { it[CFEP.published] } shouldBe listOf(true, true)
        published[CFEP.reloadField].toJsonMapOrEmpty()[CFEP.issues].toJsonListOfMaps().shouldBeEmpty()

        finishCheck(user, gid)
    }

    "a group publish skips a configuration already published, and a group names each configuration once" {
        val client = "mbskip"
        val admin = defineClient(client, sandbox = false)
        admin.postData(
            CFEP.bundlePatch,
            // The same entry again: the latest revision is published, so even an unchanged write starts a draft.
            mapOf(CFEP.configs to listOf(mapOf(CFEP.name to "flows", CFEP.edits to listOf(slotEdit(GedraEditAction.addOrReplace, reviewEntry(admin)))))),
        )
        val published = admin.postData(CFEP.bundlePublish, mapOf(CFEP.names to listOf("traits", "flows")))[CFEP.configs].toJsonListOfMaps()
        // `traits` was already published, and is answered as it is; `flows` is published now.
        published.map { it[CFEP.name] to it[CFEP.version] } shouldBe listOf("traits" to 1L, "flows" to 2L)
        published.map { it[CFEP.published] } shouldBe listOf(true, true)

        admin.expectError(EXC.badInput, CFEP.bundlePublish, mapOf(CFEP.names to listOf("flows", "flows")))
        admin.expectError(
            EXC.badInput, CFEP.bundlePatch,
            mapOf(CFEP.configs to listOf(mapOf(CFEP.name to "flows", CFEP.edits to emptyList<Any?>()))),
        )[EP.errorMessage].toOptStr().orEmpty() shouldContain "at least one edit"
    }
})
