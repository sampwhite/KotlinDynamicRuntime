package com.dynamicruntime.kdn

import com.dynamicruntime.common.context.ENV
import com.dynamicruntime.common.endpoint.EP
import com.dynamicruntime.common.gedra.ClientAudience
import com.dynamicruntime.common.gedra.ClientDef
import com.dynamicruntime.common.gedra.ClientUsageType
import com.dynamicruntime.common.gedra.GDF
import com.dynamicruntime.common.gedra.GE
import com.dynamicruntime.common.gedra.GEP
import com.dynamicruntime.common.gedra.GT
import com.dynamicruntime.common.gedra.GedraConfigReload
import com.dynamicruntime.common.gedra.GedraConfigService
import com.dynamicruntime.common.gedra.GedraDataType
import com.dynamicruntime.common.gedra.gedraConfig
import com.dynamicruntime.common.gedra.workflow.CTAK
import com.dynamicruntime.common.gedra.workflow.CTKF
import com.dynamicruntime.common.gedra.workflow.WFD
import com.dynamicruntime.common.gedra.workflow.WFS
import com.dynamicruntime.common.gedra.workflow.WSC
import com.dynamicruntime.common.gedra.workflow.WVF
import com.dynamicruntime.common.gedra.workflow.WfEntry
import com.dynamicruntime.common.gedra.workflow.WfSaveKind
import com.dynamicruntime.common.home.HFRAG
import com.dynamicruntime.common.naming.clientNamespace
import com.dynamicruntime.common.user.TestUser
import com.dynamicruntime.common.util.toJsonListOfMaps
import com.dynamicruntime.common.util.toJsonListOfStrings
import com.dynamicruntime.common.util.toJsonMapOrEmpty
import com.dynamicruntime.common.util.toOptStr
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldNotContain
import io.kotest.matchers.shouldBe

/**
 * The `review` search keyword replaces Needs Review (issue #1596): a form needs review while an engaged workflow's call
 * to action carries the keyword, the workflow has `kdr:ctaReview` meanwhile, and a keyword's words are a client's to
 * reword.
 *
 * Over a client of its own, whose `check` workflow is two data tasks: `look`, which carries `review`, then `file`, which
 * carries nothing. Its copy overlay rewords the keyword's verb and status text.
 */
class ReviewKeywordTest : StringSpec({
    val cxt = TestInstances.default("reviewKeyword1596")
    val client = "reviewkw1596"

    val config = gedraConfig(cxt, "${client}cfg", clientNamespace(client), client) {
        defineClient(
            ClientDef(
                clientId = client, name = client, usageType = ClientUsageType.dev, audience = ClientAudience.internal,
                enabledEnvironments = setOf(ENV.unit, ENV.local),
            ),
        )
        trait("LookEntry", "look", setOf(GedraDataType.formDoc), "What was looked at.") { property("text", "The notes.") }
        trait("FileEntry", "file", setOf(GedraDataType.formDoc), "Where it was filed.") { property("text", "The place.") }
        workflow("check", WfEntry.normal) {
            task("look", "Look it over") {
                keyword(CTAK.review)
                trait("look")
                save("saveLook", "Save", WfSaveKind.edit)
            }
            task("file", "File it") {
                trait("file")
                save("saveFile", "Save", WfSaveKind.edit)
            }
        }
        fragmentOverlay(HFRAG.home) {
            namespace("ctaReview") {
                key(CTKF.verb, "Vet")
                key(CTKF.statusText, "Awaiting vetting")
            }
        }
    }
    GedraConfigService.get(cxt).writeConfig(cxt.mkSubContext("setup", client).also { it.userId = 15960L }, config)
    GedraConfigReload.reloadClient(cxt, client)

    val owner = TestUser.create(cxt, "owner@$client.test", userClient = client)

    fun formFacts(states: List<Map<String, Any?>>): List<String> =
        states.filter { it[GE.traitId] == GT.cfacts }.flatMap { it[GE.data].toJsonMapOrEmpty()[GT.facts].toJsonListOfStrings() }

    fun checkState(states: List<Map<String, Any?>>): Map<String, Any?> = states
        .single { it[GE.traitId] == WFS.workflowState && it[GE.data].toJsonMapOrEmpty()[WFD.workflowId] == "check" }[GE.data]
        .toJsonMapOrEmpty()

    /** The form's states as the listing reads them. */
    fun statesOf(gid: String): List<Map<String, Any?>> =
        owner.getItems(GEP.formDocs, mapOf(GDF.withStates to true)).single { it[GDF.gedraId] == gid }[GDF.states].toJsonListOfMaps()

    fun viewFacts(gid: String): List<String> =
        owner.getData(GEP.workflowView, mapOf(GDF.gedraId to gid, WFD.workflowId to "check"))[WFD.tasks]
            .toJsonListOfMaps().first()[WVF.facts].toJsonListOfStrings()

    "a form needs review while the call to action carries review, and stops once it moves on" {
        val gid = owner.postItem(GEP.formDocCreate, mapOf(GDF.entries to emptyList<Any>()))[GDF.gedraId].toOptStr()!!
        owner.postData(GEP.workflowEngage, mapOf(GDF.gedraId to gid, WFD.workflowId to "check"))
        val engaged = statesOf(gid)
        // The status on the form, the keyword's fact in the workflow's own, and both reach the page.
        formFacts(engaged) shouldContain WSC.needsReview
        checkState(engaged).let {
            it[WFS.ctaKeyword] shouldBe CTAK.review
            it[WFS.cfacts].toJsonListOfStrings() shouldContain CTAK.reviewCfact
        }
        viewFacts(gid) shouldContain CTAK.reviewCfact

        // The review is done: the call to action is `file`, which carries nothing, and both facts go.
        owner.postData(
            GEP.workflowSave,
            mapOf(
                WFD.workflowId to "check", GDF.taskId to "look", GDF.saveId to "saveLook", GDF.gedraId to gid,
                GDF.entries to listOf(mapOf(GE.traitId to "look", GE.data to mapOf("text" to "Seen."))),
            ),
        )
        val moved = statesOf(gid)
        formFacts(moved) shouldNotContain WSC.needsReview
        checkState(moved).let {
            (WFS.ctaKeyword in it) shouldBe false
            it[WFS.cfacts].toJsonListOfStrings() shouldNotContain CTAK.reviewCfact
        }
        viewFacts(gid) shouldNotContain CTAK.reviewCfact
    }

    "a client's overlay rewords the keyword, and the listing delivers its words" {
        val words = owner.client.sendJsonGetRequest(GEP.formDocs, emptyMap())[EP.summary].toJsonMapOrEmpty()[GDF.ctaKeywords]
            .toJsonListOfMaps().single { it[CTKF.keyword] == CTAK.review }
        words[CTKF.verb] shouldBe "Vet"
        words[CTKF.statusText] shouldBe "Awaiting vetting"
        // What the overlay leaves alone is the platform's.
        words[CTKF.filterLabel] shouldBe "Needs review"
        words[CTKF.columnHeader] shouldBe "Review"
    }

    "the cross-client view says a row's words as its own client says them" {
        owner.postItem(GEP.formDocCreate, mapOf(GDF.entries to emptyList<Any>()))
        // An administrator of every client, in a client of its own, lists this client's rows.
        val admin = TestUser.createFullAdmin(cxt, "admin@$client.test")
        val summary = admin.client.sendJsonGetRequest(GEP.formDocs, mapOf(GDF.client to client))[EP.summary].toJsonMapOrEmpty()
        fun statusText(words: Any?): Any? = words.toJsonListOfMaps().single { it[CTKF.keyword] == CTAK.review }[CTKF.statusText]
        // The admin's own client's words for the page, and this client's for its rows.
        statusText(summary[GDF.ctaKeywords]) shouldBe "Needs Review"
        statusText(summary[GDF.ctaKeywordsByClient].toJsonMapOrEmpty()[client]) shouldBe "Awaiting vetting"
    }
})
