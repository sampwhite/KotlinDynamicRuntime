package com.dynamicruntime.kdn

import com.dynamicruntime.common.context.ENV
import com.dynamicruntime.common.context.KdrCxt
import com.dynamicruntime.common.endpoint.EP
import com.dynamicruntime.common.endpoint.clientPath
import com.dynamicruntime.common.gedra.ClientAudience
import com.dynamicruntime.common.gedra.ClientDef
import com.dynamicruntime.common.gedra.ClientUsageType
import com.dynamicruntime.common.gedra.GDF
import com.dynamicruntime.common.gedra.GE
import com.dynamicruntime.common.gedra.GED
import com.dynamicruntime.common.gedra.GEP
import com.dynamicruntime.common.gedra.GPF
import com.dynamicruntime.common.gedra.GT
import com.dynamicruntime.common.gedra.GedraConfigReload
import com.dynamicruntime.common.gedra.GedraConfigService
import com.dynamicruntime.common.gedra.GedraDataType
import com.dynamicruntime.common.gedra.GedraEditAction
import com.dynamicruntime.common.gedra.gedraConfig
import com.dynamicruntime.common.gedra.workflow.CDF
import com.dynamicruntime.common.gedra.workflow.FSV
import com.dynamicruntime.common.gedra.workflow.WFC
import com.dynamicruntime.common.gedra.workflow.WFD
import com.dynamicruntime.common.gedra.workflow.WSF
import com.dynamicruntime.common.gedra.workflow.WVF
import com.dynamicruntime.common.gedra.workflow.WfEntry
import com.dynamicruntime.common.gedra.workflow.WfSaveKind
import com.dynamicruntime.common.http.request.ROLE
import com.dynamicruntime.common.naming.clientNamespace
import com.dynamicruntime.common.user.TestUser
import com.dynamicruntime.common.util.toJsonListOfMaps
import com.dynamicruntime.common.util.toJsonListOrEmpty
import com.dynamicruntime.common.util.toJsonMapOrEmpty
import com.dynamicruntime.common.util.toOptStr
import io.kotest.core.spec.style.StringSpec
import io.kotest.core.spec.style.stringSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldContainAll
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldNotContain
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain

/**
 * The core details view and edit-save on the common workflow path (issue #658), over real HTTP. A per-test dynamic
 * client with a core details workflow requiring one trait (`detail`): the view resolves the core details workflow
 * against an existing form by `gedraId` -- seeding each field with its stored value and reporting real completeness --
 * and the `edit` save folds new data into the form and recomputes its core details state live, no restart.
 *
 * Also, since issue #1739, the tests that were `CoreDetailsStateDerivationTest`, each kept whole below.
 */
class CoreDetailsEditTest : StringSpec({
    include(coreDetailsEditTests)
    include(coreDetailsStateDerivationTests)
})

/** This file's own tests, as the class describes them. */
private val coreDetailsEditTests = stringSpec {
    val cxt = TestInstances.default("coreDetailsEdit658")
    val client = "coreDetailsedit658"

    fun asClient(): KdrCxt = cxt.mkSubContext("setup", client).also { it.userId = 9000L }

    val config = gedraConfig(cxt, "${client}cfg", clientNamespace(client), client) {
        defineClient(
            ClientDef(
                clientId = client, name = client, usageType = ClientUsageType.dev,
                audience = ClientAudience.internal, enabledEnvironments = setOf(ENV.unit, ENV.local),
            ),
        )
        trait("DetailEntry", "detail", setOf(GedraDataType.formDoc), "The detail the core details workflow requires.") {
            property("text", "A value.", required = true)
        }
        trait("NoteEntry", "note", setOf(GedraDataType.formDoc), "An aside the core details workflow does not ask for.") {
            property("text", "A value.")
        }
        workflow("reviewForm", WfEntry.coreDetails) {
            // `detail` is required (drives completeness); `note` is an optional field the task also collects, so
            // an edit can touch `note` while leaving the required `detail` unmet.
            task("only", "Review") {
                trait("detail")
                trait("note", required = false)
                save("save", "Save changes", WfSaveKind.edit)
            }
        }
    }
    GedraConfigService.get(cxt).writeConfig(asClient(), config)
    GedraConfigReload.reloadClient(cxt, client)

    val user = TestUser.create(cxt, "u@$client.test", userClient = client)
    val viewPath = clientPath(GEP.workflowView, client)
    val savePath = clientPath(GEP.workflowSave, client)

    fun create(traitId: String, text: String): String =
        user.postItem(
            GEP.formDocCreate,
            mapOf(GDF.entries to listOf(mapOf(GE.traitId to traitId, GE.data to mapOf("text" to text)))),
        )[GDF.gedraId].toOptStr().orEmpty()

    fun editSave(gid: String, text: String): Map<String, Any?> = user.postData(
        savePath,
        mapOf(
            GDF.workflowId to "reviewForm", GDF.taskId to "only", GDF.saveId to "save", GDF.gedraId to gid,
            GDF.entries to listOf(mapOf(GE.traitId to "detail", GE.data to mapOf("text" to text))),
        ),
    )

    fun coreDetailsCompletion(gid: String): Map<String, Any?> {
        val row = user.getItems(GEP.formDocs, mapOf(GDF.withStates to true)).first { it[GDF.gedraId] == gid }
        return row[GDF.states].toJsonListOfMaps().first { it[GE.traitId].toOptStr() == CDF.coreDetailsCompletion }[GE.data]
            .toJsonMapOrEmpty()
    }

    "the core details view resolves against a form by gedraId, seeding each field and reporting real completeness" {
        val gid = create("detail", "v1")
        val v = user.getData(viewPath, mapOf(GDF.gedraId to gid))
        v[WVF.found] shouldBe true
        v[WFD.entry] shouldBe WfEntry.coreDetails.name
        (v[WVF.ref] as String) shouldContain "#reviewForm"

        val task = v[WFD.tasks].toJsonListOfMaps().single()
        // Seeded: the task carries the form's current detail entry, so the page can fill the field with "v1".
        val seeded = task[WVF.entries].toJsonListOfMaps().first { it[GE.traitId].toOptStr() == "detail" }
        seeded[GE.data].toJsonMapOrEmpty()["text"] shouldBe "v1"
        // Completeness is real, not a placeholder: detail is present, so the task reports complete.
        (task[WVF.facts] as List<*>) shouldContain WFC.taskComplete
    }

    "the core details view carries each task's status and names the earliest task needing action (#700)" {
        // Created with only the optional `note`: the required `detail` is missing, so the one task is incomplete
        // -- and valid, since absence is not invalidity -- and it is the task needing action.
        val gid = create("note", "aside")
        val v = user.getData(viewPath, mapOf(GDF.gedraId to gid))
        val status = v[WFD.tasks].toJsonListOfMaps().single()[WVF.status].toJsonMapOrEmpty()
        status[CDF.complete] shouldBe false
        status[CDF.valid] shouldBe true
        status[CDF.missingTraits].toJsonListOrEmpty() shouldContain "detail"
        status[CDF.invalidTraits].toJsonListOrEmpty().shouldBeEmpty()
        status[WVF.problems].toJsonListOrEmpty().shouldBeEmpty()
        v[WVF.focusTask] shouldBe "only"

        // Supplying `detail` completes the task; nothing needs action, so no focus task is named.
        editSave(gid, "answered")[WSF.saved] shouldBe true
        val done = user.getData(viewPath, mapOf(GDF.gedraId to gid))
        done[WFD.tasks].toJsonListOfMaps().single()[WVF.status].toJsonMapOrEmpty()[CDF.complete] shouldBe true
        done.containsKey(WVF.focusTask) shouldBe false
    }

    "a core details edit save answers with the form's id alone; the view read after it shows the save (#827)" {
        val gid = create("note", "aside")
        val res = editSave(gid, "answered")
        // The save signals that the form's state may have moved, as a patch does, and carries none of it.
        res[WSF.saved] shouldBe true
        res[GDF.gedraId] shouldBe gid
        res.containsKey(WSF.item) shouldBe false
        // A literal on purpose: `WSF.view` was retired with the field (#827), and this pins that it stays gone.
        res.containsKey("view") shouldBe false
        // The view -- where a page reads the new state from -- reflects the save: the task is now complete, its seeded
        // entry is the new value, and no task needs action any more.
        val view = user.getData(viewPath, mapOf(GDF.gedraId to gid))
        view[WVF.found] shouldBe true
        val task = view[WFD.tasks].toJsonListOfMaps().single()
        task[WVF.status].toJsonMapOrEmpty()[CDF.complete] shouldBe true
        task[WVF.entries].toJsonListOfMaps().first { it[GE.traitId].toOptStr() == "detail" }[GE.data].toJsonMapOrEmpty()["text"] shouldBe "answered"
        view.containsKey(WVF.focusTask) shouldBe false
    }

    "a schema narrowed after capture makes a task invalid, with the failure's friendly wording (#700)" {
        // Its own client: narrowing the shared client's schema would break the sibling tests' longer values.
        val nClient = "svyinvalid700"
        fun writeN(maxLen: Int?) {
            val config = gedraConfig(cxt, "${nClient}cfg", clientNamespace(nClient), nClient) {
                defineClient(
                    ClientDef(
                        clientId = nClient, name = nClient, usageType = ClientUsageType.dev,
                        audience = ClientAudience.internal, enabledEnvironments = setOf(ENV.unit, ENV.local),
                    ),
                )
                trait("NarrowEntry", "ndetail", setOf(GedraDataType.formDoc), "The detail the core details workflow requires.") {
                    property("text", "A value.", required = true) { if (maxLen != null) maxLength = maxLen }
                }
                workflow("nReview", WfEntry.coreDetails) {
                    task("only", "Review") { trait("ndetail"); save("save", "Save changes", WfSaveKind.edit) }
                }
            }
            GedraConfigService.get(cxt).writeConfig(cxt.mkSubContext("setup", nClient).also { it.userId = 9000L }, config)
            GedraConfigReload.reloadClient(cxt, nClient)
        }

        // Captured under the wide schema: a value longer than the narrowing to come.
        writeN(maxLen = null)
        val nUser = TestUser.create(cxt, "n@$nClient.test", userClient = nClient)
        val gid = nUser.postItem(
            GEP.formDocCreate,
            mapOf(GDF.entries to listOf(mapOf(GE.traitId to "ndetail", GE.data to mapOf("text" to "far too long")))),
        )[GDF.gedraId].toOptStr().orEmpty()

        // Narrow the schema after capture, live: the stored value now fails its content check -- the design's
        // own case for how a form becomes invalid (a create validates, so it never stores one).
        writeN(maxLen = 3)
        val v = nUser.getData(clientPath(GEP.workflowView, nClient), mapOf(GDF.gedraId to gid))
        val status = v[WFD.tasks].toJsonListOfMaps().single()[WVF.status].toJsonMapOrEmpty()
        // Present, so complete; failing on content, so invalid -- absence and invalidity are kept apart.
        status[CDF.complete] shouldBe true
        status[CDF.valid] shouldBe false
        status[CDF.invalidTraits].toJsonListOrEmpty() shouldContain "ndetail"
        val problems = status[WVF.problems].toJsonListOfMaps()
        problems.isNotEmpty() shouldBe true
        // A problem is the kernel's failure wire map (path / code / message) plus the trait it belongs to.
        problems.first()[GE.traitId] shouldBe "ndetail"
        (problems.first()[EP.failureMessage] as String).isNotBlank() shouldBe true
        (problems.first()[EP.failurePath] as String).isNotBlank() shouldBe true
        // Invalid is "needs action" too, so the task is the focus even though it is complete.
        v[WVF.focusTask] shouldBe "only"

        // The listing's status filter (#695) reads the STORED state, which is re-derived on a write and
        // not by a view -- so the form still lists as valid, and no write can change that today: every write
        // re-validates the stored entries, so a patch to any trait of this form is refused ("would leave 1
        // problem in the stored entries") until the long value is fixed. Stored `invalid` is what the design's
        // deferred batch-recompute job produces; the filter's `invalid` value is covered by the shared rule's
        // own tests until then.
        fun listed(status: String): List<Any?> = nUser.getItems(GEP.formDocs,
            mapOf(GDF.formStatus to status)).map { it[GDF.gedraId] }
        listed(FSV.valid) shouldContain gid
        listed(FSV.invalid) shouldNotContain gid
    }

    "the listing filters by core details status over the states cache, and counts what matched (#695)" {
        // One form the core details workflow finds valid, one it finds needing information (its required `detail`
        // absent).
        val done = create("detail", "complete")
        val needs = create("note", "aside only")
        // The whole envelope, since the total (`numAvailable`) rides beside the items rather than under `results`.
        fun page(status: String?): Map<String, Any?> =
            user.client.sendJsonGetRequest(GEP.formDocs, buildMap { status?.let { put(GDF.formStatus, it) } })
        fun ids(page: Map<String, Any?>): List<Any?> = page[EP.items].toJsonListOfMaps().map { it[GDF.gedraId] }

        val valid = page(FSV.valid)
        ids(valid) shouldContain done
        ids(valid) shouldNotContain needs
        val needsInfo = page(FSV.needsInfo)
        ids(needsInfo) shouldContain needs
        ids(needsInfo) shouldNotContain done
        // Applied before paging: the total is the matched set, not the scope's whole set, on each answer.
        valid[EP.numAvailable] shouldBe ids(valid).size
        needsInfo[EP.numAvailable] shouldBe ids(needsInfo).size
        // No filter still lists both.
        ids(page(null)) shouldContainAll listOf(done, needs)
        // A value outside the closed choice is refused rather than silently matching nothing.
        user.expectError(400, GEP.formDocs, args = mapOf(GDF.formStatus to "bogus"))

        // The same through the CACHE path: an ordinary user's scope carries no client, so the listing above took
        // the SQL fallback; a client-scoped admin's scope keys the client+kind index, and the state filter runs
        // there too -- on the raw cached rows, before extraction.
        val clientAdmin = TestUser.create(cxt, "admin695@$client.test", level = ROLE.admin, userClient = client)
        fun adminIds(status: String): List<Any?> = clientAdmin.getItems(GEP.formDocs,
            mapOf(GDF.formStatus to status)).map { it[GDF.gedraId] }
        adminIds(FSV.valid) shouldContain done
        adminIds(FSV.valid) shouldNotContain needs
        adminIds(FSV.needsInfo) shouldContain needs
        adminIds(FSV.needsInfo) shouldNotContain done
    }

    "a core details edit save folds new data into the form" {
        val gid = create("detail", "before")
        editSave(gid, "after")[WSF.saved] shouldBe true
        val detail = user.getItem(GEP.formDoc, mapOf(GDF.gedraId to gid))[GDF.entries].toJsonListOfMaps()
            .first { it[GE.traitId].toOptStr() == "detail" }
        detail[GE.data].toJsonMapOrEmpty()["text"] shouldBe "after"
    }

    "a core details edit supplying the missing trait flips completeness -- recomputed on the edit, no restart" {
        // Created with only a trait outside the core details, so the core details workflow's `detail` is missing: it starts
        // incomplete.
        val gid = create("note", "aside")
        coreDetailsCompletion(gid)[CDF.complete] shouldBe false

        // The edit supplies `detail`; the save recomputes state, and the form is now complete.
        editSave(gid, "answered")[WSF.saved] shouldBe true
        coreDetailsCompletion(gid)[CDF.complete] shouldBe true
        coreDetailsCompletion(gid)[CDF.missingTraits].toJsonListOrEmpty().shouldBeEmpty()
    }

    "a core details edit that leaves a required trait unmet still saves -- no completeness gate on edit" {
        // Created with only the non-required `note`, so the required `detail` is missing: incomplete.
        val gid = create("note", "aside")
        coreDetailsCompletion(gid)[CDF.complete] shouldBe false

        // Edit the optional `note`, not the still-missing required `detail`. Unlike a create (which would refuse
        // with unmetTraits), the edit saves regardless -- the incompleteness is recorded as state, not gated.
        val res = user.postData(
            savePath,
            mapOf(
                GDF.workflowId to "reviewForm", GDF.taskId to "only", GDF.saveId to "save", GDF.gedraId to gid,
                GDF.entries to listOf(mapOf(GE.traitId to "note", GE.data to mapOf("text" to "updated aside"))),
            ),
        )
        res[WSF.saved] shouldBe true
        res.containsKey(WSF.unmetTraits) shouldBe false
        coreDetailsCompletion(gid)[CDF.complete] shouldBe false
        coreDetailsCompletion(gid)[CDF.missingTraits].toJsonListOrEmpty() shouldContain "detail"
    }

    "a raw patch that completes the core details refreshes core details state -- not only the core details save (#675)" {
        // Created without the required `detail`: incomplete.
        val gid = create("note", "aside")
        coreDetailsCompletion(gid)[CDF.complete] shouldBe false

        // Supply `detail` through the GENERIC patch endpoint, not the core details save. The post-write hook recomputes
        // derived state on this path too, so the core details workflow's completeness follows the raw edit -- the gap #675 closes.
        user.postItems(
            GEP.patch,
            mapOf(
                GPF.targets to mapOf(
                    GedraDataType.formDoc.name to listOf(
                        mapOf(
                            GDF.gedraId to gid,
                            GPF.edits to listOf(
                                mapOf(
                                    GED.action to GedraEditAction.addOrReplace.name,
                                    GE.traitId to "detail", GE.data to mapOf("text" to "via raw patch"),
                                ),
                            ),
                        ),
                    ),
                ),
            ),
        )
        coreDetailsCompletion(gid)[CDF.complete] shouldBe true
    }

    "a first state write from a non-owner patch is owned by the form's owner, not the patcher (#687)" {
        val obClient = "ownbind687"
        fun writeOb(withCoreDetails: Boolean) {
            val config = gedraConfig(cxt, "${obClient}cfg", clientNamespace(obClient), obClient) {
                defineClient(
                    ClientDef(
                        clientId = obClient, name = obClient, usageType = ClientUsageType.dev,
                        audience = ClientAudience.internal, enabledEnvironments = setOf(ENV.unit, ENV.local),
                    ),
                )
                trait("ObEntry", "obdetail", setOf(GedraDataType.formDoc), "The detail the core details workflow requires.") {
                    property("text", "A value.", required = true)
                }
                if (withCoreDetails) {
                    workflow("obReview", WfEntry.coreDetails) {
                        task("only", "Review") { trait("obdetail"); save("save", "Save changes", WfSaveKind.edit) }
                    }
                }
            }
            GedraConfigService.get(cxt).writeConfig(cxt.mkSubContext("setup", obClient).also { it.userId = 9000L }, config)
            GedraConfigReload.reloadClient(cxt, obClient)
        }

        // The client starts with no core details workflow, so the form is created with no state row -- the next write INSERTs
        // the first one, the case whose ownership is stamped rather than preserved.
        writeOb(withCoreDetails = false)
        val owner = TestUser.create(cxt, "owner@$obClient.test", userClient = obClient)
        val gid = owner.postItem(
            GEP.formDocCreate,
            mapOf(GDF.entries to listOf(mapOf(GE.traitId to "obdetail", GE.data to mapOf("text" to "v1")))),
        )[GDF.gedraId].toOptStr().orEmpty()

        // Add the core details workflow live, then have a full admin -- a non-owner -- patch the owner's form. That
        // fires the first core details state write, triggered by someone other than the owner.
        writeOb(withCoreDetails = true)
        val admin = TestUser.createFullAdmin(cxt, "admin687@$obClient.test")
        admin.postItems(
            GEP.patch,
            mapOf(
                GPF.targets to mapOf(
                    GedraDataType.formDoc.name to listOf(
                        mapOf(
                            GDF.gedraId to gid,
                            GPF.edits to listOf(
                                mapOf(
                                    GED.action to GedraEditAction.addOrReplace.name,
                                    GE.traitId to "obdetail", GE.data to mapOf("text" to "v2"),
                                ),
                            ),
                        ),
                    ),
                ),
            ),
        )

        // The owner sees their form's core details state -- so the state row is owned by the owner (their scoped read
        // admits it), not by the admin who triggered the write. Were it admin-owned, the owner's own-scoped
        // withStates would not carry it.
        val row = owner.getItems(GEP.formDocs, mapOf(GDF.withStates to true)).first { it[GDF.gedraId] == gid }
        row[GDF.states].toJsonListOfMaps().mapNotNull { it[GE.traitId].toOptStr() } shouldContain CDF.coreDetailsCompletion
    }
}

/**
 * The core details state producer (issue #657): a form records its `derived` core details state on creation, without
 * any interactive workflow step, because `CoreDetailsStateDeriver` runs inside the create transaction. Exercised
 * through a **per-test dynamic client** (the `kdr-test-clients` technique) rather than the sample -- the deriver is
 * production `base/common` code, and a purpose-built client with a core details workflow requiring one trait proves the
 * whole path (deriver -> `writeState` -> states cache -> the `withStates` read) without depending on acme's shapes.
 */
private val coreDetailsStateDerivationTests = stringSpec {
    val cxt = TestInstances.default("coreDetailsState657")
    val client = "coreDetails657"

    // A write is attributed, so bind a sub-context to the client with a userId before writing its config.
    fun asClient(): KdrCxt = cxt.mkSubContext("setup", client).also { it.userId = 9000L }

    // One dynamic client, shared by both scenarios: two formDoc traits and a core details workflow that requires `detail`.
    // Trait ids are unique per instance, so the two scenarios are two forms in one client, not two clients.
    val config = gedraConfig(cxt, "${client}cfg", clientNamespace(client), client) {
        defineClient(
            ClientDef(
                clientId = client, name = client, usageType = ClientUsageType.dev,
                audience = ClientAudience.internal, enabledEnvironments = setOf(ENV.unit, ENV.local),
            ),
        )
        trait("DetailEntry", "detail", setOf(GedraDataType.formDoc), "The detail the core details workflow requires.") {
            property("text", "A value.", required = true)
        }
        trait("NoteEntry", "note", setOf(GedraDataType.formDoc), "An aside the core details workflow does not ask for.") {
            property("text", "A value.")
        }
        workflow("reviewForm", WfEntry.coreDetails) {
            task("only", "Review") { trait("detail"); save("save", "Save changes", WfSaveKind.edit) }
        }
    }
    GedraConfigService.get(cxt).writeConfig(asClient(), config)
    GedraConfigReload.reloadClient(cxt, client)
    val user = TestUser.create(cxt, "u@$client.test", userClient = client)

    fun stateEntry(user: TestUser, gedraId: String, traitId: String): Map<String, Any?> {
        val row = user.getItems(GEP.formDocs, mapOf(GDF.withStates to true)).first { it[GDF.gedraId] == gedraId }
        return row[GDF.states].toJsonListOfMaps().first { it[GE.traitId].toOptStr() == traitId }[GE.data].toJsonMapOrEmpty()
    }

    fun create(user: TestUser, traitId: String, text: String): String =
        user.postItem(
            GEP.formDocCreate,
            mapOf(GDF.entries to listOf(mapOf(GE.traitId to traitId, GE.data to mapOf("text" to text)))),
        )[GDF.gedraId].toOptStr().orEmpty()

    "a form carrying the core details workflow's required trait is complete and valid, and asserts both cfacts" {
        val gid = create(user, "detail", "here")

        val completion = stateEntry(user, gid, CDF.coreDetailsCompletion)
        completion[CDF.complete] shouldBe true
        completion[CDF.valid] shouldBe true
        completion[CDF.missingTraits].toJsonListOrEmpty().shouldBeEmpty()

        // The two facts are asserted into the shared cfacts state, which is what the eligibility bridge reads.
        val facts = stateEntry(user, gid, GT.cfacts)[GT.facts].toJsonListOrEmpty()
        facts shouldContain CDF.coreDetailsComplete
        facts shouldContain CDF.coreDetailsValid
    }

    "a form missing the core details workflow's required trait is incomplete, and names what is missing" {
        // Only a trait outside the core details present, so the core details workflow's `detail` is missing.
        val gid = create(user, "note", "aside")

        val completion = stateEntry(user, gid, CDF.coreDetailsCompletion)
        completion[CDF.complete] shouldBe false
        completion[CDF.missingTraits].toJsonListOrEmpty() shouldContainExactly listOf<Any?>("detail")
        // Present data is still valid (nothing the core details workflow collects is present to be wrong), so
        // coreDetailsValid stands and coreDetailsComplete does not -- absence of the good fact reads as "not ready".
        completion[CDF.valid] shouldBe true
        val facts = stateEntry(user, gid, GT.cfacts)[GT.facts].toJsonListOrEmpty()
        facts shouldContain CDF.coreDetailsValid
        facts shouldNotContain CDF.coreDetailsComplete
    }
}
