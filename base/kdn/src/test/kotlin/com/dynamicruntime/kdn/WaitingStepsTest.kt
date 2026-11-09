package com.dynamicruntime.kdn

import com.dynamicruntime.common.context.ENV
import com.dynamicruntime.common.endpoint.EP
import com.dynamicruntime.common.endpoint.clientPath
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
import com.dynamicruntime.common.gedra.workflow.SWF
import com.dynamicruntime.common.gedra.workflow.WCOL
import com.dynamicruntime.common.gedra.workflow.WFC
import com.dynamicruntime.common.gedra.workflow.WFD
import com.dynamicruntime.common.gedra.workflow.WFS
import com.dynamicruntime.common.gedra.workflow.WSC
import com.dynamicruntime.common.gedra.workflow.WfEntry
import com.dynamicruntime.common.gedra.workflow.WfSaveKind
import com.dynamicruntime.common.gedra.workflow.WfWaitsOn
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
import io.kotest.core.spec.style.stringSpec
import io.kotest.matchers.shouldBe

/**
 * What waits on whom (issue #1637): the forms listing's `needsMe`, by the rule the save and approve gates apply, judged
 * from stored state. Over a client of its own: a site review whose `describe` task its owner saves (naming nobody), whose
 * approval waits on a reviewer of the site's region, and whose follow-up is the owner's again.
 *
 * Also, since issue #1739, the tests that were `HighlightedStartTest`, each kept whole below.
 */
class WaitingStepsTest : StringSpec({
    include(waitingStepsTests())
    include(highlightedStartTests())
})

/** This file's own tests, as the class describes them. */
private fun waitingStepsTests() = stringSpec {
    val cxt = TestInstances.default("waiting1637")
    val client = "waiting1637"

    gedraConfig(cxt, "${client}cfg", clientNamespace(client), client) {
        defineClient(
            ClientDef(
                clientId = client, name = client, usageType = ClientUsageType.dev, audience = ClientAudience.internal,
                enabledEnvironments = setOf(ENV.unit, ENV.local), userLabels = listOf("reviewer", "northwest", "south"),
            ),
        )
        cfact("reviewerNorthwest", "Review", "The viewer reviews the Northwest region's sites.")
        cfact("reviewerSouth", "Review", "The viewer reviews the South region's sites.")
        cfact("visitApproved", "Review", "A reviewer approved the visit.")
        trait("SiteEntry", "site", setOf(GedraDataType.formDoc), "The site visited.") { property("region", "Its region.") }
        trait("NotesEntry", "notes", setOf(GedraDataType.formDoc), "What was seen.") { property("text", "The notes.") }
        trait("FollowUpEntry", "followUp", setOf(GedraDataType.formDoc), "What happens next.") { property("plan", "The plan.") }
        trait("CheckEntry", "check", setOf(GedraDataType.formDoc), "A reviewer's check.") { property("result", "What it found.") }
        trait("JotEntry", "jot", setOf(GedraDataType.formDoc), "A jot.") { property("text", "The jot.") }
        // Finished from the start, with its one task still to do: its cell draws it finished, so it waits on nobody.
        workflow("earlyDone", WfEntry.normal) {
            singleton(WSC.finished, "#always")
            task("jot", "Write a jot") {
                trait("jot")
                save("saveJot", "Save", WfSaveKind.edit)
            }
        }
        // A save that names someone: the check waits on a reviewer, whoever owns the form.
        workflow("siteCheck", WfEntry.normal) {
            label = "Site check"
            task("check", "Check the site") {
                trait("check")
                save("saveCheck", "Save", WfSaveKind.edit)
                saveWhen(WFC.reviewer)
                function(userHasLabel { label = "reviewer" })
            }
            lock("check", writableVia = "check")
        }
        workflow("siteReview", WfEntry.normal) {
            label = "Site review"
            task("describe", "Describe the visit") {
                trait("notes")
                save("saveNotes", "Save", WfSaveKind.edit)
            }
            task("approve", "Approve the visit") {
                approval("visitApproved", "Approve the visit?", "Approve")
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
            task("followUp", "Plan the follow-up") {
                trait("followUp")
                save("savePlan", "Save", WfSaveKind.edit)
            }
        }
    }.also { config ->
        GedraConfigService.get(cxt).writeConfig(cxt.mkSubContext("setup", client).also { it.userId = 16370L }, config)
        GedraConfigReload.reloadClient(cxt, client)
    }

    val admin = TestUser.create(cxt, "admin@$client.test", level = ROLE.admin, userClient = client)

    /** An editor of the client carrying [labels]: one who sees every form of it, and reviews by label. */
    fun reviewer(name: String, vararg labels: String): TestUser =
        TestUser.create(cxt, "$name@$client.test", capabilities = listOf(ROLE.editor), userClient = client).also {
            admin.postData(UADEP.userSetLabels, mapOf(ADF.userId to it.userId, ADF.labels to labels.toList()))
        }

    val northwest = reviewer("northwest", "reviewer", "northwest")
    val south = reviewer("south", "reviewer", "south")

    /** The forms listed as waiting on [user], each its id to the steps it waits on them for (task to action text). */
    fun waitingFor(user: TestUser): Map<String, List<Pair<Any?, Any?>>> =
        user.getItems(GEP.formDocs, mapOf(GDF.needsMe to true)).associate { row ->
            row[GDF.gedraId].toOptStr()!! to row[GDF.waitingSteps].toJsonListOfMaps().map { it[WFS.ctaTask] to it[SWF.actionText] }
        }

    /** A new owner's form at the northwest site, engaged with the site review. */
    fun engagedForm(owner: TestUser): String {
        val gid = owner.postItem(
            GEP.formDocCreate, mapOf(GDF.entries to listOf(mapOf(GE.traitId to "site", GE.data to mapOf("region" to "northwest")))),
        )[GDF.gedraId].toOptStr()!!
        owner.postData(GEP.workflowEngage, mapOf(GDF.gedraId to gid, WFD.workflowId to "siteReview"))
        return gid
    }

    "a workflow that has declared itself finished waits on nobody, though a task of it remains, as its cell draws it finished" {
        val owner = TestUser.create(cxt, "early@$client.test", userClient = client)
        val site = mapOf(GE.traitId to "site", GE.data to mapOf("region" to "northwest"))
        val gid = owner.postItem(GEP.formDocCreate, mapOf(GDF.entries to listOf(site)))[GDF.gedraId].toOptStr()!!
        owner.postData(GEP.workflowEngage, mapOf(GDF.gedraId to gid, WFD.workflowId to "earlyDone"))
        // The jot is the owner's save, and still to do; without the finished fact it would wait on them.
        val row = owner.getItems(GEP.formDocs, mapOf(GDF.withStates to true)).single { it[GDF.gedraId] == gid }
        val state = row[GDF.states].toJsonListOfMaps().filter { it[GE.traitId] == WFS.workflowState }
            .map { it[GE.data].toJsonMapOrEmpty() }.single { it[WFD.workflowId] == "earlyDone" }
        state[WFS.tasksDone] shouldBe false
        state[WFS.ctaTask] shouldBe "jot"
        waitingFor(owner).containsKey(gid) shouldBe false
        row[GDF.waitingSteps].toJsonListOfMaps() shouldBe emptyList()
    }

    "a step waits on the person it is for: the owner's own save, then the region's reviewer, then the owner again" {
        val owner = TestUser.create(cxt, "owner@$client.test", userClient = client)
        val gid = engagedForm(owner)

        // Describing the visit names nobody, so it waits on the form's owner -- not on the administrator, whose gate
        // would admit them, nor on a reviewer.
        waitingFor(owner)[gid] shouldBe listOf("describe" to "Describe the visit")
        waitingFor(admin).containsKey(gid) shouldBe false
        waitingFor(northwest).containsKey(gid) shouldBe false

        owner.postData(
            GEP.workflowSave,
            mapOf(
                WFD.workflowId to "siteReview", GDF.taskId to "describe", GDF.saveId to "saveNotes", GDF.gedraId to gid,
                GDF.entries to listOf(mapOf(GE.traitId to "notes", GE.data to mapOf("text" to "Seen."))),
            ),
        )
        // The approval waits on a Northwest reviewer: not the South one, whom the region's filter leaves out, nor the
        // owner, nor an administrator who is no reviewer.
        waitingFor(northwest)[gid] shouldBe listOf("approve" to "Approve")
        waitingFor(south).containsKey(gid) shouldBe false
        waitingFor(owner).containsKey(gid) shouldBe false
        waitingFor(admin).containsKey(gid) shouldBe false

        northwest.postData(GEP.workflowApprove, mapOf(GDF.gedraId to gid, GDF.workflowId to "siteReview", GDF.taskId to "approve"))
        // The follow-up is the owner's again, and nothing waits on the reviewer.
        waitingFor(owner)[gid] shouldBe listOf("followUp" to "Plan the follow-up")
        waitingFor(northwest).containsKey(gid) shouldBe false
    }

    "a save that names someone waits on them, not on the form's owner" {
        val owner = TestUser.create(cxt, "checked@$client.test", userClient = client)
        val gid = owner.postItem(
            GEP.formDocCreate, mapOf(GDF.entries to listOf(mapOf(GE.traitId to "site", GE.data to mapOf("region" to "south")))),
        )[GDF.gedraId].toOptStr()!!
        owner.postData(GEP.workflowEngage, mapOf(GDF.gedraId to gid, WFD.workflowId to "siteCheck"))
        // Both reviewers, whatever their region -- the check names no region -- and neither the owner nor an
        // administrator who is no reviewer.
        waitingFor(northwest)[gid] shouldBe listOf("check" to "Check the site")
        waitingFor(south)[gid] shouldBe listOf("check" to "Check the site")
        waitingFor(owner).containsKey(gid) shouldBe false
        waitingFor(admin).containsKey(gid) shouldBe false
    }

    "a form in no workflow waits on nobody, and the count is of the forms waiting" {
        val owner = TestUser.create(cxt, "plain@$client.test", userClient = client)
        val idle = owner.postItem(
            GEP.formDocCreate, mapOf(GDF.entries to listOf(mapOf(GE.traitId to "site", GE.data to mapOf("region" to "south")))),
        )[GDF.gedraId].toOptStr()!!
        val engaged = engagedForm(owner)
        waitingFor(owner).keys shouldBe setOf(engaged)
        waitingFor(owner).containsKey(idle) shouldBe false
        // The listing's count is of what matched, so Home can say how many wait.
        val resp = owner.client.sendJsonGetRequest(GEP.formDocs, mapOf(GDF.needsMe to true, EP.limit to 1))
        (resp[EP.numAvailable] as? Number)?.toInt() shouldBe 1
    }
}

/**
 * A workflow's highlighted Start (issue #1662): while a form is eligible for a workflow that highlights it and is not yet
 * in it, its first task waits on whoever could act on it -- so the classification makes it their turn and "waiting on
 * you" lists it -- and on nobody else, who may still engage. Once the form is in, it is the current task as ever.
 *
 * Over a client of its own with two workflows that differ only in that: `check`, which highlights its Start, and `plain`,
 * which does not. Each one's first task is saved by a reviewer (the `reviewer` label) alone, its trait locked to it.
 */
private fun highlightedStartTests() = stringSpec {
    val cxt = TestInstances.default("highlightedStart1662")
    val client = "hlstart1662"

    val config = gedraConfig(cxt, "${client}cfg", clientNamespace(client), client) {
        defineClient(
            ClientDef(
                clientId = client, name = client, usageType = ClientUsageType.dev, audience = ClientAudience.internal,
                enabledEnvironments = setOf(ENV.unit, ENV.local), userLabels = listOf("reviewer"),
            ),
        )
        for ((id, highlight) in listOf("check" to true, "plain" to false)) {
            // Each its own trait, locked to its task, as a task whose save is restricted must be.
            trait("${id}Entry", "${id}Verdict", setOf(GedraDataType.formDoc), "The reviewer's check.") { property("verdict", "The verdict.") }
            workflow(id, WfEntry.normal) {
                highlightStart = highlight
                task("${id}Task", "Check the form") {
                    trait("${id}Verdict")
                    save("${id}Save", "Save", WfSaveKind.edit)
                    saveWhen(WFC.reviewer)
                    function(userHasLabel { label = "reviewer" })
                }
                lock("${id}Verdict", writableVia = "${id}Task")
            }
        }
    }
    GedraConfigService.get(cxt).writeConfig(cxt.mkSubContext("setup", client).also { it.userId = 16620L }, config)
    GedraConfigReload.reloadClient(cxt, client)

    val owner = TestUser.create(cxt, "owner@$client.test", userClient = client)
    val admin = TestUser.create(cxt, "admin@$client.test", level = ROLE.admin, userClient = client)
    val reviewer = TestUser.create(cxt, "reviewer@$client.test", capabilities = listOf(ROLE.editor), userClient = client).also {
        admin.postData(UADEP.userSetLabels, mapOf(ADF.userId to it.userId, ADF.labels to listOf("reviewer")))
    }

    /** The steps waiting on [user] in form [gid], by workflow: its task, and whether it is a Start. */
    fun steps(user: TestUser, gid: String, vararg params: Pair<String, Any?>): Map<String, Pair<String?, Boolean>> =
        user.client.sendJsonGetRequest(clientPath(GEP.formDocs, client), mapOf(GDF.withStates to true, *params))[EP.items]
            .toJsonListOfMaps().firstOrNull { it[GDF.gedraId] == gid }?.get(GDF.waitingSteps).toJsonListOfMaps()
            .associate { it[WFD.workflowId].toOptStr()!! to (it[WFS.ctaTask].toOptStr() to (it[SWF.startsWorkflow] == true)) }

    "a highlighted Start waits on whoever could act on its first task, and is the current task once the form is in" {
        val gid = owner.postItem(GEP.formDocCreate, mapOf(GDF.entries to emptyList<Any>()))[GDF.gedraId].toOptStr()!!
        // The reviewer may save the first task, so the highlighting workflow's Start is theirs; the other's is not.
        steps(reviewer, gid) shouldBe mapOf("check" to ("checkTask" to true))
        // And "waiting on you" lists the form for them, not for the owner, who may not save it.
        steps(reviewer, gid, GDF.needsMe to true) shouldBe mapOf("check" to ("checkTask" to true))
        steps(owner, gid) shouldBe emptyMap()
        steps(owner, gid, GDF.needsMe to true) shouldBe emptyMap()
        // It decides nothing about who may engage: the owner, whose turn it is not, engages it.
        owner.postData(GEP.workflowEngage, mapOf(GDF.gedraId to gid, WFD.workflowId to "check"))
        steps(reviewer, gid) shouldBe mapOf("check" to ("checkTask" to false))
        // A workflow that does not highlight its Start waits on the reviewer only once the form is in.
        owner.postData(GEP.workflowEngage, mapOf(GDF.gedraId to gid, WFD.workflowId to "plain"))
        steps(reviewer, gid) shouldBe mapOf("check" to ("checkTask" to false), "plain" to ("plainTask" to false))
    }

    /** Why each of form [gid]'s steps waits on someone other than [user], by workflow: whom it waits on. */
    fun reasons(user: TestUser, gid: String): Map<String, String?> =
        user.client.sendJsonGetRequest(clientPath(GEP.formDocs, client), mapOf(GDF.withStates to true))[EP.items]
            .toJsonListOfMaps().first { it[GDF.gedraId] == gid }[GDF.waitingReasons].toJsonListOfMaps()
            .associate { it[WFD.workflowId].toOptStr()!! to it[WCOL.waitsOn].toOptStr() }

    "a highlighted Start that waits on someone else is waiting, with the reason its first task gives, to everyone else" {
        val gid = owner.postItem(GEP.formDocCreate, mapOf(GDF.entries to emptyList<Any>()))[GDF.gedraId].toOptStr()!!
        // The owner may not save the first task, so the highlighted Start waits on others -- the reason the page gives
        // when it draws the link waiting (issue #1690). The plain workflow's Start invites nobody, so it has none.
        reasons(owner, gid) shouldBe mapOf("check" to WfWaitsOn.others.name)
        // The reviewer's Start is their step, not a reason.
        reasons(reviewer, gid) shouldBe emptyMap()
        // Who may engage is unchanged: the owner still engages it, and its first task is then the current one, still
        // waiting on the reviewer.
        owner.postData(GEP.workflowEngage, mapOf(GDF.gedraId to gid, WFD.workflowId to "check"))
        reasons(owner, gid) shouldBe mapOf("check" to WfWaitsOn.others.name)
    }
}
