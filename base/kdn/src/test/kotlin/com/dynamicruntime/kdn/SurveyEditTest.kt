package com.dynamicruntime.kdn

import com.dynamicruntime.common.context.ENV
import com.dynamicruntime.common.context.KdrCxt
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
import com.dynamicruntime.common.gedra.workflow.SVY
import com.dynamicruntime.common.gedra.workflow.WFC
import com.dynamicruntime.common.gedra.workflow.WFD
import com.dynamicruntime.common.gedra.workflow.WSF
import com.dynamicruntime.common.gedra.workflow.WVF
import com.dynamicruntime.common.gedra.workflow.WfEntry
import com.dynamicruntime.common.gedra.workflow.WfSaveKind
import com.dynamicruntime.common.user.TestUser
import com.dynamicruntime.common.util.toJsonListOfMaps
import com.dynamicruntime.common.util.toJsonListOrEmpty
import com.dynamicruntime.common.util.toJsonMapOrEmpty
import com.dynamicruntime.common.util.toOptStr
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain

/**
 * The survey view and edit-save on the common workflow path (issue #658), over real HTTP. A per-test dynamic
 * client with a survey requiring one trait (`detail`): the view resolves the survey against an existing form by
 * `gedraId` -- seeding each field with its stored value and reporting real completeness -- and the `edit` save
 * folds new data into the form and recomputes its survey state live, no restart.
 */
class SurveyEditTest : StringSpec({
    val cxt = Startup.mkTestBootCxt("surveyEdit658", "surveyEdit658")
    val client = "surveyedit658"

    fun asClient(): KdrCxt = cxt.mkSubContext("setup", client).also { it.userId = 9000L }

    val config = gedraConfig(cxt, "${client}cfg", "${client}config", client) {
        defineClient(
            ClientDef(
                clientId = client, name = client, usageType = ClientUsageType.dev,
                audience = ClientAudience.internal, enabledEnvironments = setOf(ENV.unit, ENV.local),
            ),
        )
        trait("DetailEntry", "detail", setOf(GedraDataType.formDoc), "The detail the survey requires.") {
            property("text", "A value.", required = true)
        }
        trait("NoteEntry", "note", setOf(GedraDataType.formDoc), "An aside the survey does not ask for.") {
            property("text", "A value.")
        }
        workflow("reviewForm", WfEntry.survey) {
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

    fun surveyCompletion(gid: String): Map<String, Any?> {
        val row = user.getItems(GEP.formDocs, mapOf(GDF.withStates to true)).first { it[GDF.gedraId] == gid }
        return row[GDF.states].toJsonListOfMaps().first { it[GE.traitId].toOptStr() == SVY.surveyCompletion }[GE.data].toJsonMapOrEmpty()
    }

    "the survey view resolves against a form by gedraId, seeding each field and reporting real completeness" {
        val gid = create("detail", "v1")
        val v = user.getData(viewPath, mapOf(GDF.gedraId to gid))
        v[WVF.found] shouldBe true
        v[WFD.entry] shouldBe WfEntry.survey.name
        (v[WVF.ref] as String) shouldContain "#reviewForm"

        val task = v[WFD.tasks].toJsonListOfMaps().single()
        // Seeded: the task carries the form's current detail entry, so the page can fill the field with "v1".
        val seeded = task[WVF.entries].toJsonListOfMaps().first { it[GE.traitId].toOptStr() == "detail" }
        seeded[GE.data].toJsonMapOrEmpty()["text"] shouldBe "v1"
        // Completeness is real, not a placeholder: detail is present, so the task reports complete.
        (task[WVF.facts] as List<*>) shouldContain WFC.taskComplete
    }

    "a survey edit save folds new data into the form, and the returned item reflects it" {
        val gid = create("detail", "before")
        val res = editSave(gid, "after")
        res[WSF.saved] shouldBe true
        val detail = res[WSF.item].toJsonMapOrEmpty()[GDF.entries].toJsonListOfMaps()
            .first { it[GE.traitId].toOptStr() == "detail" }
        detail[GE.data].toJsonMapOrEmpty()["text"] shouldBe "after"
    }

    "a survey edit supplying the missing trait flips completeness -- recomputed on the edit, no restart" {
        // Created with only a non-survey trait, so the survey's `detail` is missing: it starts incomplete.
        val gid = create("note", "aside")
        surveyCompletion(gid)[SVY.complete] shouldBe false

        // The edit supplies `detail`; the save recomputes state, and the form is now complete.
        editSave(gid, "answered")[WSF.saved] shouldBe true
        surveyCompletion(gid)[SVY.complete] shouldBe true
        surveyCompletion(gid)[SVY.missingTraits].toJsonListOrEmpty().shouldBeEmpty()
    }

    "a survey edit that leaves a required trait unmet still saves -- no completeness gate on edit" {
        // Created with only the non-required `note`, so the required `detail` is missing: incomplete.
        val gid = create("note", "aside")
        surveyCompletion(gid)[SVY.complete] shouldBe false

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
        surveyCompletion(gid)[SVY.complete] shouldBe false
        surveyCompletion(gid)[SVY.missingTraits].toJsonListOrEmpty() shouldContain "detail"
    }
})
