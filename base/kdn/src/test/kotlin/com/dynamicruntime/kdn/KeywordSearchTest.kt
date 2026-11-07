package com.dynamicruntime.kdn

import com.dynamicruntime.common.context.ENV
import com.dynamicruntime.common.context.ReadScope
import com.dynamicruntime.common.context.UserProfile
import com.dynamicruntime.common.endpoint.EP
import com.dynamicruntime.common.endpoint.clientPath
import com.dynamicruntime.common.exception.EXC
import com.dynamicruntime.common.gedra.ClientAudience
import com.dynamicruntime.common.gedra.ClientDef
import com.dynamicruntime.common.gedra.ClientUsageType
import com.dynamicruntime.common.gedra.GDF
import com.dynamicruntime.common.gedra.GE
import com.dynamicruntime.common.gedra.GEP
import com.dynamicruntime.common.gedra.GSORT
import com.dynamicruntime.common.gedra.CUS
import com.dynamicruntime.common.gedra.CPY
import com.dynamicruntime.common.gedra.WorkflowLayout
import com.dynamicruntime.common.gedra.GedraConfigBuilder
import com.dynamicruntime.common.gedra.GedraConfigReload
import com.dynamicruntime.common.gedra.GedraConfigService
import com.dynamicruntime.common.gedra.GedraDataService
import com.dynamicruntime.common.gedra.GedraDataType
import com.dynamicruntime.common.gedra.GedraStateFilter
import com.dynamicruntime.common.gedra.GedraStateRow
import com.dynamicruntime.common.gedra.formDocsQueryDefName
import com.dynamicruntime.common.gedra.sandboxOf
import com.dynamicruntime.common.gedra.gedraConfig
import com.dynamicruntime.common.gedra.workflow.CTAK
import com.dynamicruntime.common.gedra.workflow.CTKQ
import com.dynamicruntime.common.gedra.workflow.RCF
import com.dynamicruntime.common.gedra.workflow.WCOL
import com.dynamicruntime.common.gedra.workflow.WFD
import com.dynamicruntime.common.gedra.workflow.WFS
import com.dynamicruntime.common.gedra.workflow.WSC
import com.dynamicruntime.common.gedra.workflow.WaitingSteps
import com.dynamicruntime.common.gedra.workflow.WfEntry
import com.dynamicruntime.common.gedra.workflow.WfSaveKind
import com.dynamicruntime.common.gedra.workflow.WfWaitsOn
import com.dynamicruntime.common.gedra.workflow.ctaFilterByValue
import com.dynamicruntime.common.gedra.workflow.userHasLabel
import com.dynamicruntime.common.http.request.ROLE
import com.dynamicruntime.common.naming.clientNamespace
import com.dynamicruntime.common.startup.SchemaService
import com.dynamicruntime.common.user.ADF
import com.dynamicruntime.common.user.TestUser
import com.dynamicruntime.common.user.UADEP
import com.dynamicruntime.common.util.toJsonListOfMaps
import com.dynamicruntime.common.util.toJsonMapOrEmpty
import com.dynamicruntime.common.util.toOptStr
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.collections.shouldNotContain
import io.kotest.matchers.shouldBe

/**
 * Searching the forms list by keyword, `mine` or `any` (issue #1597), and the classification it reads: a form is the
 * caller's to review when one of its workflows' calls to action carries `review` and the caller may act on it, and
 * awaiting review whoever may act.
 *
 * Over a client of its own with three workflows: `regional`, an approval whose reviewer is chosen by the site's region;
 * `legal`, an approval for the legal reviewers; and `intake`, a task only the form's owner may save, carrying no
 * keyword. A second client has `intake` alone.
 */
class KeywordSearchTest : StringSpec({
    val cxt = TestInstances.default("keywordSearch1597")
    val client = "kwsearch1597"
    val plain = "kwplain1597"

    fun GedraConfigBuilder.intake() {
        trait("NotesEntry", "notes", setOf(GedraDataType.formDoc), "What was seen.") { property("text", "The notes.") }
        workflow("intake", WfEntry.normal) {
            task("respond", "Respond") {
                trait("notes")
                saveWhen(RCF.isOwner)
                save("saveNotes", "Save", WfSaveKind.edit)
            }
            lock("notes", writableVia = "respond")
        }
    }

    fun store(id: String, sandbox: Boolean = false, more: GedraConfigBuilder.() -> Unit) {
        val config = gedraConfig(cxt, "${id}cfg", clientNamespace(id), id) {
            defineClient(
                ClientDef(
                    clientId = id, name = id, usageType = ClientUsageType.dev, audience = ClientAudience.internal,
                    enabledEnvironments = setOf(ENV.unit, ENV.local),
                    userLabels = listOf("reviewer", "northwest", "south", "legal", "clerk"),
                    domainPrefix = if (sandbox) "${id}pfx" else null, sandbox = sandbox,
                ),
            )
            intake()
            more()
        }
        GedraConfigService.get(cxt).writeConfig(cxt.mkSubContext("setup", id).also { it.userId = 15970L }, config)
        GedraConfigReload.reloadClient(cxt, id)
    }

    store(client) {
        cfact("reviewerNorthwest", "Review", "The viewer reviews the Northwest region's sites.")
        cfact("reviewerSouth", "Review", "The viewer reviews the South region's sites.")
        cfact("reviewerLegal", "Review", "The viewer is a legal reviewer.")
        cfact("regionApproved", "Review", "A regional reviewer approved the site.")
        cfact("legalApproved", "Review", "A legal reviewer approved the site.")
        trait("SiteEntry", "site", setOf(GedraDataType.formDoc), "The site visited.") { property("region", "Its region.") }
        workflow("regional", WfEntry.normal) {
            task("approve", "Approve for the region") {
                approval("regionApproved", "Approve the site?", "Approve")
                function(userHasLabel { label = "reviewer" })
                function(userHasLabel { label = "northwest"; cfact = "reviewerNorthwest" })
                function(userHasLabel { label = "south"; cfact = "reviewerSouth" })
                function(
                    ctaFilterByValue {
                        trait = "site"
                        valuePath = "region"
                        case("northwest", "reviewerNorthwest", "Waiting on a Northwest reviewer")
                        case("south", "reviewerSouth", "Waiting on a South reviewer")
                        otherwise("reviewerNorthwest,reviewerSouth", "Waiting on a reviewer of both regions")
                    },
                )
            }
        }
        workflow("legal", WfEntry.normal) {
            task("approve", "Approve for legal") {
                approval("legalApproved", "Approve the site?", "Approve")
                function(userHasLabel { label = "reviewer" })
                function(userHasLabel { label = "legal"; cfact = "reviewerLegal" })
                function(
                    ctaFilterByValue {
                        trait = "site"
                        valuePath = "region"
                        case("northwest", "reviewerLegal", "Waiting on a legal reviewer")
                        otherwise("reviewerLegal", "Waiting on a legal reviewer")
                    },
                )
            }
        }
    }
    store(plain) {}

    // A client of forms kept to their owner (issue #1433): `kept`, a data task the clerks save; `sign`, an approval any
    // non-owner may give, whose reviewers are the `reviewer` label; and `notice`, a task with nothing to save.
    val kept = "kwkept1597"
    store(kept) {
        cfact("signed", "Review", "Someone signed the form off.")
        cfact("clerk", "Review", "The viewer is a clerk.")
        trait("MemoEntry", "memo", setOf(GedraDataType.formDoc), "A memo.") { property("text", "The memo.") }
        workflow("kept", WfEntry.normal) {
            singleton(WSC.ownerOnly, "#always")
            task("edit", "Edit the memo") {
                trait("memo")
                save("saveMemo", "Save", WfSaveKind.edit)
                saveWhen("clerk")
                function(userHasLabel { label = "clerk"; cfact = "clerk" })
            }
            lock("memo", writableVia = "edit")
        }
        workflow("sign", WfEntry.normal) {
            singleton(WSC.ownerOnly, "#always")
            task("sign", "Sign it off") {
                approval("signed", "Sign the form off?", "Sign", approveWhen = "~${RCF.isOwner}")
                function(userHasLabel { label = "reviewer" })
            }
        }
        workflow("notice", WfEntry.normal) {
            task("read", "Read the memo") { trait("memo") }
        }
        // A save that names nobody, which waits on the form's owner (issue #1598).
        trait("JotEntry", "jot", setOf(GedraDataType.formDoc), "A jot.") { property("text", "The jot.") }
        workflow("jot", WfEntry.normal) {
            task("write", "Write a jot") {
                trait("jot")
                save("saveJot", "Save", WfSaveKind.edit)
            }
        }
    }

    val admin = TestUser.create(cxt, "admin@$client.test", level = ROLE.admin, userClient = client)
    val reviewer = TestUser.create(cxt, "reviewer@$client.test", capabilities = listOf(ROLE.editor), userClient = client)
    admin.postData(UADEP.userSetLabels, mapOf(ADF.userId to reviewer.userId, ADF.labels to listOf("reviewer", "northwest")))
    val owner = TestUser.create(cxt, "owner@$client.test", userClient = client)

    /** A form of [owner]'s at [region], engaged with each of [workflows]. */
    fun form(region: String, vararg workflows: String): String {
        val site = mapOf(GE.traitId to "site", GE.data to mapOf("region" to region))
        val gid = owner.postItem(GEP.formDocCreate, mapOf(GDF.entries to listOf(site)))[GDF.gedraId].toOptStr()!!
        for (workflow in workflows) owner.postData(GEP.workflowEngage, mapOf(GDF.gedraId to gid, WFD.workflowId to workflow))
        return gid
    }

    // Two reviews where the reviewer passes one filter; a review for someone else; and one awaiting nobody's review.
    val both = form("northwest", "regional", "legal")
    val south = form("south", "regional")
    val intakeOnly = form("northwest", "intake")

    /** The workflows a listed row says are the caller's turn: those of its steps waiting on them. */
    fun turnsOf(row: Map<String, Any?>): List<String> = row[GDF.waitingSteps].toJsonListOfMaps().map { it[WFD.workflowId].toOptStr()!! }

    /** The listing on the client's own endpoint, as [user] sees it with [params]: the ids found and how many matched. */
    fun search(user: TestUser, vararg params: Pair<String, Any?>): Pair<List<String>, Int> {
        val answer = user.client.sendJsonGetRequest(clientPath(GEP.formDocs, client), mapOf(*params))
        val ids = answer[EP.items].toJsonListOfMaps().map { it[GDF.gedraId].toOptStr()!! }
        return ids to (answer[EP.numAvailable] as Number).toInt()
    }

    "review=mine is the rows the classification makes the caller's turn for review, and any every row awaiting review" {
        search(reviewer, CTAK.review to CTKQ.mine) shouldBe (listOf(both) to 1)
        search(reviewer, CTAK.review to CTKQ.any).let { (ids, matched) ->
            ids shouldContainExactlyInAnyOrder listOf(both, south)
            matched shouldBe 2
        }
        // The owner may not approve their own form (the default rule), so nothing is theirs to review.
        search(owner, CTAK.review to CTKQ.mine) shouldBe (emptyList<String>() to 0)
        search(owner, CTAK.review to CTKQ.any).second shouldBe 2
        // Paged, the count is still of everything matched.
        search(reviewer, CTAK.review to CTKQ.any, EP.limit to 1).let { (ids, matched) ->
            ids.size shouldBe 1
            matched shouldBe 2
        }
        // A closed choice: an answer that is neither is refused.
        reviewer.expectError(EXC.badInput, clientPath(GEP.formDocs, client), args = mapOf(CTAK.review to "everyone"))
    }

    "each row listed with its states says which of its workflows are the caller's turn, the page's half of the classification" {
        fun yourTurn(user: TestUser): Map<String, List<String>> =
            user.client.sendJsonGetRequest(clientPath(GEP.formDocs, client), mapOf(GDF.withStates to true))[EP.items]
                .toJsonListOfMaps().associate { it[GDF.gedraId].toOptStr()!! to turnsOf(it) }
        // Of the two reviews only the Northwest one is the reviewer's; the owner's turn is the intake.
        yourTurn(reviewer).let {
            it[both] shouldBe listOf("regional")
            it[south] shouldBe emptyList()
            it[intakeOnly] shouldBe emptyList()
        }
        yourTurn(owner).let {
            it[both] shouldBe emptyList()
            it[intakeOnly] shouldBe listOf("intake")
        }
    }

    "on a form kept to its owner a clerk's save waits on them only as a reviewer of its current work, and an approval always" {
        val keptOwner = TestUser.create(cxt, "owner@$kept.test", userClient = kept)
        val keptAdmin = TestUser.create(cxt, "admin@$kept.test", level = ROLE.admin, userClient = kept)
        val clerk = TestUser.create(cxt, "clerk@$kept.test", capabilities = listOf(ROLE.editor), userClient = kept)
        val reviewingClerk = TestUser.create(cxt, "reviewer@$kept.test", capabilities = listOf(ROLE.editor), userClient = kept)
        keptAdmin.postData(UADEP.userSetLabels, mapOf(ADF.userId to clerk.userId, ADF.labels to listOf("clerk")))
        keptAdmin.postData(UADEP.userSetLabels, mapOf(ADF.userId to reviewingClerk.userId, ADF.labels to listOf("clerk", "reviewer")))
        fun keptForm(vararg workflows: String): String {
            val gid = keptOwner.postItem(GEP.formDocCreate, mapOf(GDF.entries to emptyList<Any>()))[GDF.gedraId].toOptStr()!!
            for (workflow in workflows) keptOwner.postData(GEP.workflowEngage, mapOf(GDF.gedraId to gid, WFD.workflowId to workflow))
            return gid
        }
        fun yourTurn(user: TestUser): Map<String, List<String>> =
            user.client.sendJsonGetRequest(clientPath(GEP.formDocs, kept), mapOf(GDF.withStates to true))[EP.items]
                .toJsonListOfMaps().associate { it[GDF.gedraId].toOptStr()!! to turnsOf(it) }
        val alone = keptForm("kept", "notice")
        val signed = keptForm("kept", "sign")
        // The owner is no clerk and may not sign their own form; and a step with nothing to save waits on nobody.
        yourTurn(keptOwner).let {
            it[alone] shouldBe emptyList()
            it[signed] shouldBe emptyList()
        }
        // A clerk reviewing nothing is kept out of the memo, but may still sign: the rule refuses saves, never approvals.
        yourTurn(clerk).let {
            it[alone] shouldBe emptyList()
            it[signed] shouldBe listOf("sign")
        }
        // A clerk who reviews the current work (the sign-off) may save the memo too, as the owner-only rule lets them.
        yourTurn(reviewingClerk).let {
            it[alone] shouldBe emptyList()
            it[signed] shouldContainExactlyInAnyOrder listOf("kept", "sign")
        }
    }

    "each step not waiting on the caller says whom it waits on, its filter's label when the filter keeps the caller out" {
        fun reasons(user: TestUser, listedClient: String = client): Map<String, Map<String, Pair<String?, String?>>> =
            user.client.sendJsonGetRequest(clientPath(GEP.formDocs, listedClient), mapOf(GDF.withStates to true))[EP.items]
                .toJsonListOfMaps().associate { row ->
                    row[GDF.gedraId].toOptStr()!! to row[GDF.waitingReasons].toJsonListOfMaps().associate {
                        it[WFD.workflowId].toOptStr()!! to (it[WCOL.waitsOn].toOptStr() to it[WFS.ctaFilterLabel].toOptStr())
                    }
                }
        val filter = WfWaitsOn.filter.name
        // The reviewer's own turn has no reason; the legal review's filter keeps them out, and says who it waits on.
        reasons(reviewer).let {
            it[both] shouldBe mapOf("legal" to (filter to "Waiting on a legal reviewer"))
            it[south] shouldBe mapOf("regional" to (filter to "Waiting on a South reviewer"))
            // A save its rule gives someone else.
            it[intakeOnly] shouldBe mapOf("intake" to (WfWaitsOn.others.name to null))
        }
        // The owner passes no filter; their own intake waits on them, so it has no reason.
        reasons(owner).let {
            it[both] shouldBe mapOf(
                "regional" to (filter to "Waiting on a Northwest reviewer"),
                "legal" to (filter to "Waiting on a legal reviewer"),
            )
            it[intakeOnly] shouldBe emptyMap()
        }
        // An approval the viewer may not give, a save that names nobody (the owner's), and a step with nothing to take.
        val keptOwner = TestUser.create(cxt, "owner2@$kept.test", userClient = kept)
        val keptAdmin = TestUser.create(cxt, "admin2@$kept.test", level = ROLE.admin, userClient = kept)
        val gid = keptOwner.postItem(GEP.formDocCreate, mapOf(GDF.entries to emptyList<Any>()))[GDF.gedraId].toOptStr()!!
        for (workflow in listOf("sign", "jot", "notice")) {
            keptOwner.postData(GEP.workflowEngage, mapOf(GDF.gedraId to gid, WFD.workflowId to workflow))
        }
        reasons(keptOwner, kept)[gid] shouldBe mapOf("sign" to (WfWaitsOn.approval.name to null))
        // An administrator may sign it off, but the jot is the owner's to write.
        reasons(keptAdmin, kept)[gid] shouldBe mapOf("jot" to (WfWaitsOn.owner.name to null))
    }

    // Issue #1599: the split layout's Your turn column sorts by the caller's turn and is headed by its count; the layout is
    // the client's listing setting, which its page sets.
    "sorting by the caller's turn brings their work to the top, the summary counts it, and the layout is the client's" {
        fun listed(user: TestUser, vararg params: Pair<String, Any?>): Map<String, Any?> =
            user.client.sendJsonGetRequest(clientPath(GEP.formDocs, client), mapOf(*params))
        fun rows(user: TestUser, dir: String) =
            listed(user, GSORT.sort to GSORT.yourTurn, GSORT.sortDir to dir, GDF.withStates to true)[EP.items].toJsonListOfMaps()
        // Most turns first: the reviewer's review, the owner's intake; ascending, a form that is nobody's turn of theirs.
        for (user in listOf(reviewer, owner)) {
            rows(user, GSORT.desc).first().let { turnsOf(it).isNotEmpty() shouldBe true }
            rows(user, GSORT.asc).first().let { turnsOf(it) shouldBe emptyList() }
        }
        rows(reviewer, GSORT.desc).first()[GDF.gedraId] shouldBe both
        // The count is what "waiting on you" lists, whatever the search.
        fun summary(user: TestUser, vararg params: Pair<String, Any?>) =
            listed(user, GDF.withStates to true, GDF.withWorkflowSummary to true, *params)[EP.summary].toJsonMapOrEmpty()
        for (user in listOf(reviewer, owner)) {
            val waitingOnThem = search(user, GDF.needsMe to true).second
            (summary(user)[GDF.yourTurnCount] as Number).toInt() shouldBe waitingOnThem
            (summary(user, CTAK.review to CTKQ.any)[GDF.yourTurnCount] as Number).toInt() shouldBe waitingOnThem
        }
        // Split unless the client says otherwise; its administrator sets it, stored in the editors' own configuration.
        summary(reviewer)[GDF.workflowLayout] shouldBe WorkflowLayout.split.name
        fun catalog() = admin.client.sendJsonGetRequest(UADEP.clientUsages, emptyMap())[EP.item].toJsonMapOrEmpty()
        catalog()[CUS.layoutConfigName] shouldBe null
        admin.postData(UADEP.clientUsageLayout, mapOf(CUS.workflowLayout to WorkflowLayout.combined.name))
        summary(reviewer)[GDF.workflowLayout] shouldBe WorkflowLayout.combined.name
        catalog().let {
            it[CUS.workflowLayout] shouldBe WorkflowLayout.combined.name
            it[CUS.layoutConfigName] shouldBe CPY.copyConfigName
        }
        // A layout the release does not know is refused.
        admin.expectError(EXC.badInput, UADEP.clientUsageLayout, mapOf(CUS.workflowLayout to "perWorkflow"))
        admin.postData(UADEP.clientUsageLayout, mapOf(CUS.workflowLayout to WorkflowLayout.split.name))
        summary(reviewer)[GDF.workflowLayout] shouldBe WorkflowLayout.split.name
    }

    "a sandbox has the keyword parameters its parent's workflows give it" {
        val parent = "kwsandbox1597"
        store(parent, sandbox = true) {
            cfact("signed", "Review", "Someone signed the form off.")
            workflow("sign", WfEntry.normal) {
                task("sign", "Sign it off") {
                    approval("signed", "Sign the form off?", "Sign")
                    function(userHasLabel { label = "reviewer" })
                }
            }
        }
        fun query(id: String) = SchemaService.get(cxt).storeFor(id).types.getValue(formDocsQueryDefName()).properties.keys
        query(parent) shouldContain CTAK.review
        query(sandboxOf(parent)) shouldContain CTAK.review
    }

    "a client whose workflows carry no keyword has no parameter for it" {
        fun query(id: String) = SchemaService.get(cxt).storeFor(id).types.getValue(formDocsQueryDefName()).properties.keys
        query(client) shouldContain CTAK.review
        query(plain) shouldNotContain CTAK.review
        // Nor does the shared listing, being no one client's.
        SchemaService.get(cxt).storeFor(null).types.getValue(formDocsQueryDefName()).properties.keys shouldNotContain CTAK.review
    }

    "a listing hands its state filter every row at once, and owners' identities are read once for them all" {
        // The listing's half: one prepare, with every row in scope, before any is judged.
        val prepared = mutableListOf<Int>()
        @Suppress("CanBeVal") var judged = 0
        val counting = object : GedraStateFilter {
            override fun prepare(rows: List<GedraStateRow>) {
                prepared.add(rows.size)
                judged shouldBe 0
            }

            override fun keeps(row: GedraStateRow, states: List<Map<String, Any?>>): Boolean {
                judged++
                return true
            }
        }
        val page = GedraDataService.get(cxt).listGedras(cxt, GedraDataType.formDoc, ReadScope.ofClient(client), 1, stateFilter = counting)
        prepared shouldBe listOf(page.numAvailable)
        judged shouldBe page.numAvailable

        // The judge's half, in production: the identities of a page's owners are one read, and asking each row's
        // ownership reads nothing more; an owner it was not given is read on its own, and the caller's own never is.
        val production = TestInstances.notTestInstance("keywordSearchProd1597").mkSubContext("caller", client).also {
            it.userProfile = UserProfile(authId = "a@$client.test", userId = 700L, client = client, identityId = "person1")
        }
        val reads = mutableListOf<Collection<Long>>()
        val turns = WaitingSteps(production) { ids ->
            reads.add(ids)
            ids.associateWith { if (it == 701L) "person1" else "person$it" }
        }
        val owners = listOf(700L, 701L, 702L, 703L, 701L, 702L)
        turns.readOwners(owners)
        owners.map { RCF.isOwner in turns.rowFacts(it) } shouldBe listOf(true, true, false, false, true, false)
        reads shouldBe listOf(listOf(701L, 702L, 703L))
        turns.rowFacts(704L)
        reads.drop(1) shouldBe listOf(listOf(704L))
        // On a test instance ownership is a comparison: nothing is read.
        val onTest = WaitingSteps(cxt.mkSubContext("caller", client)) { ids -> reads.add(ids); emptyMap() }
        onTest.readOwners(owners)
        reads.size shouldBe 2
    }
})
