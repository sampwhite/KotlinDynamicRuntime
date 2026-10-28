package com.dynamicruntime.kdn

import com.dynamicruntime.common.context.ENV
import com.dynamicruntime.common.context.KdrCxt
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
import com.dynamicruntime.common.util.toOptInstant
import com.dynamicruntime.common.util.toOptStr
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldNotBeEmpty
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain

/**
 * The config patch's dry run and its staleness check (issue #1434). A dry run runs the patch -- its checks, its edits,
 * and the trial -- on the revisions it builds, and stores none of them, answering what it would write and what that
 * would do to the client's data; a patch the save would refuse is refused the same way. An edit may say what its caller read -- an entry's
 * `updatedAt`, or that it had none -- and the patch is refused (409) if that has changed, naming every such entry.
 *
 * One booted instance; a client per case, its configuration in three bundles -- `main` (the definition), `traits`, and
 * `flows` (the `review` workflow over them) -- as `MultiBundlePatchTest` builds them.
 */
class ConfigPatchDryRunTest : StringSpec({
    val cxt = TestInstances.default("configPatchDryRun1434")

    fun write(on: KdrCxt, client: String, name: String, build: GedraConfigBuilder.() -> Unit) =
        GedraConfigService.get(on).writeConfig(
            on.mkSubContext("setup", client).also { it.userId = 14340L }, gedraConfig(on, name, clientNamespace(client), client, build = build),
        )

    /** Defines [client] in three bundles, on its latest configuration, and returns its administrator. */
    fun defineClient(client: String, on: KdrCxt = cxt): TestUser {
        fun write(name: String, build: GedraConfigBuilder.() -> Unit) = write(on, client, name, build)
        write("main") {
            defineClient(
                ClientDef(
                    clientId = client, name = client, usageType = ClientUsageType.dev, audience = ClientAudience.internal,
                    enabledEnvironments = setOf(ENV.unit, ENV.local),
                ),
            )
        }
        write("traits") {
            trait("MemoEntry", "memo", setOf(GedraDataType.formDoc), "A memo.") { property("text", "What it says.") }
            trait("SignoffEntry", "signoff", setOf(GedraDataType.formDoc), "Who signed it off.") { property("by", "Who.") }
        }
        write("flows") {
            workflow("review", WfEntry.normal) {
                task("draft", "Draft it") { trait("memo"); save("saveMemo", "Save", WfSaveKind.edit) }
                task("check", "Sign it off") { trait("signoff"); save("saveSignoff", "Save", WfSaveKind.edit) }
            }
        }
        GedraConfigReload.reloadClient(on, client)
        return TestUser.create(on, "chief@$client.test", level = ROLE.admin, userClient = client)
    }

    fun edit(slot: String, action: GedraEditAction, data: Map<String, Any?>, vararg read: Pair<String, Any?>) =
        mapOf(CFEP.slot to slot, GED.action to action.name, GE.data to data) + read

    fun patch(vararg configs: Pair<String, List<Map<String, Any?>>>, dryRun: Boolean = false) =
        mapOf(CFEP.configs to configs.map { (name, edits) -> mapOf(CFEP.name to name, CFEP.edits to edits) }, CFEP.dryRun to dryRun)

    fun stored(admin: TestUser, name: String, slot: String): List<Map<String, Any?>> =
        admin.getItem(CFEP.bundle, mapOf(CFEP.name to name))[CFEP.slots].toJsonMapOrEmpty()[slot].toJsonListOfMaps()

    /** [name]'s stored entry in [slot] whose [field] is [value], with its own stamps. */
    fun entry(admin: TestUser, name: String, slot: String, field: String, value: String): Map<String, Any?> =
        admin.getItems(CFEP.slotEntries, mapOf(CFEP.name to name))
            .single { it[CFEP.slot] == slot && it[GE.data].toJsonMapOrEmpty()[field] == value }

    /** A workflow entry of its own, so `flows` keeps one when `review` goes. */
    fun placeholder(id: String) = mapOf(
        CCT.workflowId to id,
        CCT.definition to mapOf(
            WFD.workflowId to id, WFD.entry to WfEntry.normal.name, WFD.label to "Later",
            WFD.tasks to listOf(
                mapOf(
                    WFD.id to "note", WFD.label to "Note it", WFD.traits to listOf(mapOf(WFD.traitId to "memo")),
                    WFD.saves to listOf(mapOf(WFD.id to "saveNote", WFD.label to "Save", WFD.kind to WfSaveKind.edit.name)),
                ),
            ),
        ),
    )

    "a dry run answers what the patch would write, stores nothing, and answers what the save then stores" {
        val admin = defineClient("dryok1434")
        val before = stored(admin, "traits", CCT.traitDef)
        val summaryBefore = admin.getItems(CFEP.bundles).single { it[CFEP.name] == "traits" }
        val memo = before.single { it[CCT.traitId] == "memo" }
        val renamed = memo + (CCT.description to "A short memo.")
        val body = patch("traits" to listOf(edit(CCT.traitDef, GedraEditAction.addOrReplace, renamed)))

        val answer = admin.postData(CFEP.bundlePatch, body + (CFEP.dryRun to true))
        answer[CFEP.dryRun] shouldBe true
        val would = answer[CFEP.configs].toJsonListOfMaps().single()
        would[CFEP.slots].toJsonMapOrEmpty()[CCT.traitDef].toJsonListOfMaps()
            .single { it[CCT.traitId] == "memo" }[CCT.description] shouldBe "A short memo."
        answer[CFEP.impact].toJsonMapOrEmpty()[IMP.findings].toJsonListOfMaps().shouldBeEmpty()

        // Nothing stored: the bundle is as it was, at the same version, last written when it was.
        stored(admin, "traits", CCT.traitDef) shouldBe before
        admin.getItems(CFEP.bundles).single { it[CFEP.name] == "traits" }.let {
            it[CFEP.version] shouldBe summaryBefore[CFEP.version]
            it[CFEP.updatedAt] shouldBe summaryBefore[CFEP.updatedAt]
        }

        // And it is what the save then stores: the dry run built the revision the save builds.
        val saved = admin.postData(CFEP.bundlePatch, body)[CFEP.configs].toJsonListOfMaps().single()
        saved[CFEP.slots] shouldBe would[CFEP.slots]
        saved[CFEP.version] shouldBe would[CFEP.version]
    }

    "a dry run of an edit to a published configuration answers the next revision, and leaves the published one" {
        val client = "drynext1434"
        val admin = defineClient(client)
        GedraConfigService.get(cxt).publish(
            cxt.mkSubContext("setup", client).also { it.userId = 14340L }, listOf(GedraId.of(GedraConfigType.configDoc, client, "traits")),
        )
        val memo = stored(admin, "traits", CCT.traitDef).single { it[CCT.traitId] == "memo" }
        val answer = admin.postData(
            CFEP.bundlePatch,
            patch("traits" to listOf(edit(CCT.traitDef, GedraEditAction.addOrReplace, memo + (CCT.description to "Next.")))) + (CFEP.dryRun to true),
        )
        answer[CFEP.configs].toJsonListOfMaps().single()[CFEP.version] shouldBe 2L
        admin.getItems(CFEP.bundles).single { it[CFEP.name] == "traits" }.let {
            it[CFEP.version] shouldBe 1L
            it[CFEP.published] shouldBe true
        }
    }

    "a dry run says what the patch would do to the forms the client stores" {
        val client = "dryimpact1434"
        val admin = defineClient(client)
        val user = TestUser.create(cxt, "u@$client.test", userClient = client)
        val gid = user.postItem(GEP.formDocCreate, mapOf(GDF.entries to emptyList<Any?>()))[GDF.gedraId].toOptStr()!!
        user.postData(GEP.workflowEngage, mapOf(GDF.gedraId to gid, WFD.workflowId to "review"))

        // Taking `review` away leaves the form engaged in a workflow it no longer has.
        val answer = admin.postData(
            CFEP.bundlePatch,
            patch("flows" to listOf(edit(CCT.workflowDef, GedraEditAction.deleteOrNoOp, mapOf(CCT.workflowId to "review"))), dryRun = true),
        )
        answer[CFEP.impact].toJsonMapOrEmpty()[IMP.findings].toJsonListOfMaps().shouldNotBeEmpty()
        stored(admin, "flows", CCT.workflowDef).map { it[CCT.workflowId] } shouldContainExactly listOf("review")
    }

    "a dry run the save would refuse is refused the same way, with the trial's problems as data" {
        val admin = defineClient("dryrefused1434")
        // A second `review`, in a configuration of its own: the client would declare it twice.
        val review = stored(admin, "flows", CCT.workflowDef).single()
        val refused = admin.expectError(
            EXC.badInput, CFEP.bundlePatch,
            patch("moved" to listOf(edit(CCT.workflowDef, GedraEditAction.addOrReplace, review)), dryRun = true),
        )
        refused[EP.errorMessage].toOptStr()!! shouldContain "would find"
        refused[EP.extraData].toJsonMapOrEmpty()[CFEP.issues].toJsonListOfMaps().shouldNotBeEmpty()
        admin.getItems(CFEP.bundles).map { it[CFEP.name] }.contains("moved") shouldBe false
    }

    "an edit of an entry as its caller read it is stored; one changed since is refused, naming every such entry" {
        val admin = defineClient("stale1434")
        val memo = entry(admin, "traits", CCT.traitDef, CCT.traitId, "memo")
        val review = entry(admin, "flows", CCT.workflowDef, CCT.workflowId, "review")
        val memoRead = memo[GE.updatedAt]
        val reviewRead = review[GE.updatedAt]
        val memoData = memo[GE.data].toJsonMapOrEmpty()

        // As read: stored. No waiting needed anywhere here: a write stamps an entry it changes at least a millisecond
        // past its stamp before, whatever the clock says.
        admin.postData(
            CFEP.bundlePatch,
            patch("traits" to listOf(edit(CCT.traitDef, GedraEditAction.addOrReplace, memoData + (CCT.description to "First."), CFEP.expectUpdatedAt to memoRead))),
        )

        // Both entries again, as the earlier read had them: `memo` has changed since, and a `review` edit made
        // elsewhere moves its stamp too.
        val reviewData = review[GE.data].toJsonMapOrEmpty()
        val relabeled = reviewData + (CCT.definition to reviewData[CCT.definition].toJsonMapOrEmpty() + (WFD.label to "Reviewed"))
        admin.postData(CFEP.bundlePatch, patch("flows" to listOf(edit(CCT.workflowDef, GedraEditAction.addOrReplace, relabeled))))
        val refused = admin.expectError(
            EXC.conflict, CFEP.bundlePatch,
            patch(
                "traits" to listOf(edit(CCT.traitDef, GedraEditAction.addOrReplace, memoData + (CCT.description to "Second."), CFEP.expectUpdatedAt to memoRead)),
                "flows" to listOf(edit(CCT.workflowDef, GedraEditAction.addOrReplace, reviewData, CFEP.expectUpdatedAt to reviewRead)),
            ),
        )
        refused[EP.errorCode] shouldBe CFEP.staleCode
        refused[EP.extraData].toJsonMapOrEmpty()[CFEP.conflicts].toJsonListOfMaps().map { it[CFEP.name] to it[CFEP.key].toJsonMapOrEmpty() } shouldContainExactly listOf(
            "traits" to mapOf(CCT.traitId to "memo"),
            "flows" to mapOf(CCT.workflowId to "review"),
        )
        // Nothing of the refused patch was stored.
        stored(admin, "traits", CCT.traitDef).single { it[CCT.traitId] == "memo" }[CCT.description] shouldBe "First."
    }

    "with the clock frozen, a changed entry's stamp still moves, in place and in a new revision" {
        // Its own instance: freezing the clock is instance-wide.
        val frozen = Startup.mkTestBootCxt("configPatchFrozen1434", "configPatchFrozen1434")
        frozen.instanceConfig.clock.freeze()
        val client = "frozen1434"
        val admin = defineClient(client, on = frozen)
        fun memo() = entry(admin, "traits", CCT.traitDef, CCT.traitId, "memo")
        fun memoEdit(description: String, read: Any?) = patch(
            "traits" to listOf(
                edit(CCT.traitDef, GedraEditAction.addOrReplace, memo()[GE.data].toJsonMapOrEmpty() + (CCT.description to description), CFEP.expectUpdatedAt to read),
            ),
        )

        // In place: the unpublished revision is rewritten, a millisecond past its last stamp.
        val first = memo()[GE.updatedAt]
        admin.postData(CFEP.bundlePatch, memoEdit("In place.", first))
        val second = memo()[GE.updatedAt]
        (second.toOptInstant()!! > first.toOptInstant()!!) shouldBe true

        // In a new revision: published, the next edit starts revision 2 -- still past the revision before it, though
        // the clock reads what it read when the entry was last written.
        GedraConfigService.get(frozen).publish(
            frozen.mkSubContext("setup", client).also { it.userId = 14340L }, listOf(GedraId.of(GedraConfigType.configDoc, client, "traits")),
        )
        admin.postData(CFEP.bundlePatch, memoEdit("New revision.", second))
        (memo()[GE.updatedAt].toOptInstant()!! > second.toOptInstant()!!) shouldBe true
        // So an edit made against the older read is refused.
        admin.expectError(EXC.conflict, CFEP.bundlePatch, memoEdit("Stale.", second))[EP.errorCode] shouldBe CFEP.staleCode
    }

    "an entry thought new that now exists is stale, and a delete of one already gone is not" {
        val admin = defineClient("stalenew1434")
        val memo = stored(admin, "traits", CCT.traitDef).single { it[CCT.traitId] == "memo" }
        val refused = admin.expectError(
            EXC.conflict, CFEP.bundlePatch,
            patch("traits" to listOf(edit(CCT.traitDef, GedraEditAction.addOrReplace, memo, CFEP.expectNew to true))),
        )
        refused[EP.extraData].toJsonMapOrEmpty()[CFEP.conflicts].toJsonListOfMaps().single()[CFEP.expectNew] shouldBe true

        val read = entry(admin, "flows", CCT.workflowDef, CCT.workflowId, "review")[GE.updatedAt]
        val gone = edit(CCT.workflowDef, GedraEditAction.deleteOrNoOp, mapOf(CCT.workflowId to "review"), CFEP.expectUpdatedAt to read)
        admin.postData(CFEP.bundlePatch, patch("flows" to listOf(gone, edit(CCT.workflowDef, GedraEditAction.addOrReplace, placeholder("later")))))
        // Deleted already: deleting it again, as read before, is what was asked.
        admin.postData(CFEP.bundlePatch, patch("flows" to listOf(gone)))
        stored(admin, "flows", CCT.workflowDef).map { it[CCT.workflowId] } shouldContainExactly listOf("later")
    }

    "an edit that says what it read in a way that cannot be judged is refused" {
        val admin = defineClient("staleinput1434")
        val memo = stored(admin, "traits", CCT.traitDef).single { it[CCT.traitId] == "memo" }
        admin.expectError(
            EXC.badInput, CFEP.bundlePatch,
            patch("traits" to listOf(edit(CCT.traitDef, GedraEditAction.addOrReplace, memo, CFEP.expectUpdatedAt to "yesterday"))),
        )
        admin.expectError(
            EXC.badInput, CFEP.bundlePatch,
            patch("traits" to listOf(edit(CCT.traitDef, GedraEditAction.addOrReplace, memo, CFEP.expectUpdatedAt to "2026-10-28T12:00:00Z", CFEP.expectNew to true))),
        )
    }
})
