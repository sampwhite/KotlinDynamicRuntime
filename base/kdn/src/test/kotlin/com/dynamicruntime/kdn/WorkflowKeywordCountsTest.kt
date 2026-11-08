package com.dynamicruntime.kdn

import com.dynamicruntime.common.context.ENV
import com.dynamicruntime.common.endpoint.EP
import com.dynamicruntime.common.endpoint.clientPath
import com.dynamicruntime.common.exception.EXC
import com.dynamicruntime.common.gedra.ClientAudience
import com.dynamicruntime.common.gedra.ClientDef
import com.dynamicruntime.common.gedra.ClientUsageType
import com.dynamicruntime.common.gedra.GDF
import com.dynamicruntime.common.gedra.GE
import com.dynamicruntime.common.gedra.GEP
import com.dynamicruntime.common.gedra.GedraConfigReload
import com.dynamicruntime.common.gedra.GedraConfigService
import com.dynamicruntime.common.gedra.GedraDataType
import com.dynamicruntime.common.gedra.gedraConfig
import com.dynamicruntime.common.gedra.report.RCR
import com.dynamicruntime.common.gedra.report.RMETA
import com.dynamicruntime.common.gedra.report.RRUN
import com.dynamicruntime.common.gedra.report.RWF
import com.dynamicruntime.common.gedra.workflow.CTAK
import com.dynamicruntime.common.gedra.workflow.CTKF
import com.dynamicruntime.common.gedra.workflow.CTKQ
import com.dynamicruntime.common.gedra.workflow.RCF
import com.dynamicruntime.common.gedra.workflow.WAGG
import com.dynamicruntime.common.gedra.workflow.WFD
import com.dynamicruntime.common.gedra.workflow.WfColumnCategory
import com.dynamicruntime.common.gedra.workflow.WfEntry
import com.dynamicruntime.common.gedra.workflow.WfSaveKind
import com.dynamicruntime.common.gedra.workflow.ctaFilterByValue
import com.dynamicruntime.common.gedra.workflow.userHasLabel
import com.dynamicruntime.common.http.request.ROLE
import com.dynamicruntime.common.naming.clientNamespace
import com.dynamicruntime.common.user.ADF
import com.dynamicruntime.common.user.TestUser
import com.dynamicruntime.common.user.UADEP
import com.dynamicruntime.common.util.toJsonListOfMaps
import com.dynamicruntime.common.util.toJsonMapOrEmpty
import com.dynamicruntime.common.util.toOptStr
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.shouldBe

/**
 * Search keywords in the workflow summary and in reports (issue #1600): the workflow pages count, per workflow, the
 * forms whose call to action carries each keyword -- anyone's and the caller's -- and each count drills to exactly those
 * forms; a report reads a workflow's keyword and whom it waits on, and which keywords a form's workflows carry.
 *
 * Over a client of its own: `regional`, an approval whose reviewer is chosen by the site's region (a CTA filter with a
 * label); `legal`, an approval for the legal reviewers (a filter with no label); and `intake`, a task only the owner
 * saves, carrying no keyword.
 * The reviewer reviews the Northwest region and is no legal reviewer.
 */
class WorkflowKeywordCountsTest : StringSpec({
    val cxt = TestInstances.default("workflowKeywords1600")
    val client = "wfkw1600"

    val config = gedraConfig(cxt, "${client}cfg", clientNamespace(client), client) {
        defineClient(
            ClientDef(
                clientId = client, name = client, usageType = ClientUsageType.dev, audience = ClientAudience.internal,
                enabledEnvironments = setOf(ENV.unit, ENV.local), userLabels = listOf("reviewer", "northwest", "south", "legal"),
            ),
        )
        cfact("reviewerNorthwest", "Review", "The viewer reviews the Northwest region's sites.")
        cfact("reviewerSouth", "Review", "The viewer reviews the South region's sites.")
        cfact("reviewerLegal", "Review", "The viewer is a legal reviewer.")
        cfact("regionApproved", "Review", "A regional reviewer approved the site.")
        cfact("legalApproved", "Review", "A legal reviewer approved the site.")
        trait("SiteEntry", "site", setOf(GedraDataType.formDoc), "The site visited.") { property("region", "Its region.") }
        trait("NotesEntry", "notes", setOf(GedraDataType.formDoc), "What was seen.") { property("text", "The notes.") }
        workflow("intake", WfEntry.normal) {
            task("respond", "Respond") {
                trait("notes")
                saveWhen(RCF.isOwner)
                save("saveNotes", "Save", WfSaveKind.edit)
            }
            lock("notes", writableVia = "respond")
        }
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
                // A filter with no label: it cannot say whom it waits on.
                function(ctaFilterByValue { trait = "site"; valuePath = "region"; otherwise("reviewerLegal") })
            }
        }
        report("review", "Review") {
            column("regionalKeyword", "Regional keyword", "workflow.regional.${RWF.ctaKeyword}")
            column("regionalTask", "Regional task", "workflow.regional.${RWF.ctaTask}")
            column("waitingOn", "Waiting on", "workflow.regional.${RWF.ctaFilterLabel}")
            column("legalWaitingOn", "Legal waiting on", "workflow.legal.${RWF.ctaFilterLabel}")
            column("intakeKeyword", "Intake keyword", "workflow.intake.${RWF.ctaKeyword}")
            column("keywords", "Calls to action", "meta.${RMETA.ctaKeywords}")
        }
    }
    GedraConfigService.get(cxt).writeConfig(cxt.mkSubContext("setup", client).also { it.userId = 16000L }, config)
    GedraConfigReload.reloadClient(cxt, client)

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

    // A review the reviewer may give, in both workflows; a review for a South reviewer; and a form awaiting no review.
    val both = form("northwest", "regional", "legal")
    val south = form("south", "regional")
    val intakeOnly = form("northwest", "intake")

    /** Each workflow's keyword counts on the workflow pages, as [user] sees them: keyword to (any, mine). */
    fun keywordCounts(user: TestUser): Map<String, Map<String, Pair<Any?, Any?>>> =
        user.getItems(GEP.workflowAggregate).associate { entry ->
            entry[WFD.workflowId].toOptStr()!! to entry[WAGG.keywords].toJsonListOfMaps()
                .associate { it[CTKF.keyword].toOptStr()!! to (it[CTKQ.any] to it[CTKQ.mine]) }
        }

    "the workflow pages count each workflow's keyword for anyone and for the caller, and only where its calls to action carry one" {
        keywordCounts(reviewer) shouldBe mapOf(
            "intake" to emptyMap(),
            "regional" to mapOf(CTAK.review to (2 to 1)),
            // The reviewer is no legal reviewer, so the legal review is someone else's.
            "legal" to mapOf(CTAK.review to (1 to 0)),
        )
        // The owner may not approve their own form, so nothing is theirs; anyone's is about the work, not the viewer.
        keywordCounts(owner)["regional"] shouldBe mapOf(CTAK.review to (2 to 0))
        // And the summary carries the keywords' words, which head the counts.
        reviewer.client.sendJsonGetRequest(GEP.workflowAggregate)[EP.summary].toJsonMapOrEmpty()[GDF.ctaKeywords]
            .toJsonListOfMaps().single().let { it[CTKF.keyword] to it[CTKF.columnHeader] } shouldBe (CTAK.review to "To review")
    }

    /** The forms listing drilled onto [workflowId]'s keyword count, on [path], as [user] sees it. */
    fun drill(user: TestUser, workflowId: String, whose: String?, path: String = GEP.formDocs): List<String> {
        val params = mapOf(
            WAGG.workflowId to workflowId, WAGG.workflowState to WfColumnCategory.engaged.name, WAGG.keyword to CTAK.review,
        ) +
            (whose?.let { mapOf(WAGG.keywordWhose to it) } ?: emptyMap())
        return user.getItems(path, params).map { it[GDF.gedraId].toOptStr()!! }
    }

    "each count drills to exactly the forms it counts, on the shared listing and the client's own" {
        drill(reviewer, "regional", CTKQ.mine) shouldBe listOf(both)
        drill(reviewer, "regional", CTKQ.any) shouldContainExactlyInAnyOrder listOf(both, south)
        // Unsaid is anyone's.
        drill(reviewer, "regional", null) shouldContainExactlyInAnyOrder listOf(both, south)
        drill(reviewer, "legal", CTKQ.mine) shouldBe emptyList()
        drill(reviewer, "legal", CTKQ.any) shouldBe listOf(both)
        // The keyword is asked of the drilled workflow alone: the regional review the reviewer may give does not make
        // the legal one theirs, as the keyword search over every workflow would.
        drill(reviewer, "regional", CTKQ.mine, clientPath(GEP.formDocs, client)) shouldBe listOf(both)
        drill(owner, "regional", CTKQ.mine) shouldBe emptyList()
        drill(reviewer, "intake", CTKQ.any) shouldBe emptyList()
        // A closed choice, as the keyword search's is.
        reviewer.expectError(
            EXC.badInput, GEP.formDocs,
            args = mapOf(WAGG.workflowId to "regional", WAGG.keyword to CTAK.review, WAGG.keywordWhose to "everyone"),
        )
    }

    "a report reads a workflow's keyword, its task, and whom it waits on, and the keywords a form's workflows carry" {
        val run = admin.client.sendJsonGetRequest(UADEP.reportRun, mapOf(RRUN.reportId to "review"))
        val rows = run[EP.items].toJsonListOfMaps().associate { it[RRUN.gedraId].toOptStr()!! to it[RRUN.values].toJsonMapOrEmpty() }
        rows.getValue(both).let {
            it["regionalKeyword"] shouldBe CTAK.review
            it["regionalTask"] shouldBe "approve"
            it["waitingOn"] shouldBe "Waiting on a Northwest reviewer"
            it["intakeKeyword"] shouldBe null
            it["keywords"] shouldBe listOf(CTAK.review)
        }
        rows.getValue(south)["waitingOn"] shouldBe "Waiting on a South reviewer"
        // A filter with no label waits on nobody the report can name.
        rows.getValue(both)["legalWaitingOn"] shouldBe null
        // A form under way with nothing to review carries no keyword, and is waiting on no reviewer.
        rows.getValue(intakeOnly).let {
            it["regionalKeyword"] shouldBe null
            it["waitingOn"] shouldBe null
            it["keywords"] shouldBe null
        }
        // The page shows a keyword by its report label, the client's copy; the run and a download hold the keyword.
        val columns = run[EP.summary].toJsonMapOrEmpty()[RRUN.columns].toJsonListOfMaps().associateBy { it[RRUN.columnId] }
        for (id in listOf("regionalKeyword", "keywords")) {
            columns.getValue(id)[RRUN.choices].toJsonListOfMaps().single().let { it[RRUN.value] to it[RRUN.label] } shouldBe
                (CTAK.review to "Needs review")
        }
    }

    "the Reports page offers the keyword paths for a workflow that uses one, and whom it waits on where it can say" {
        val groups = admin.getItem(UADEP.reportPaths)[RCR.groups].toJsonListOfMaps().associateBy { it[RCR.sourceId] }
        fun paths(sourceId: String?) = groups.getValue(sourceId)[RCR.paths].toJsonListOfMaps().map { it[RRUN.path] }
        paths(null).contains("meta.${RMETA.ctaKeywords}") shouldBe true
        val ctaAttrs = setOf(RWF.ctaTask, RWF.ctaKeyword, RWF.ctaFilterLabel)
        paths("regional").filter { it.toString().substringAfterLast('.') in ctaAttrs } shouldBe
            listOf("workflow.regional.${RWF.ctaTask}", "workflow.regional.${RWF.ctaKeyword}", "workflow.regional.${RWF.ctaFilterLabel}")
        // `legal`'s filter has no label, so it cannot say whom it waits on; `intake` carries no keyword and sets no filter.
        paths("legal").map { it.toString().substringAfterLast('.') }.contains(RWF.ctaFilterLabel) shouldBe false
        paths("intake").map { it.toString().substringAfterLast('.') }.filter { it.startsWith("cta") } shouldBe emptyList()
    }
})
