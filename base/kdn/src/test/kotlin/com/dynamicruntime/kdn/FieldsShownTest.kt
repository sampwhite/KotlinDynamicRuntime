package com.dynamicruntime.kdn

import com.dynamicruntime.common.context.ENV
import com.dynamicruntime.common.endpoint.EP
import com.dynamicruntime.common.endpoint.clientPath
import com.dynamicruntime.common.exception.EXC
import com.dynamicruntime.common.gedra.ClientAudience
import com.dynamicruntime.common.gedra.ClientDef
import com.dynamicruntime.common.gedra.ClientUsageType
import com.dynamicruntime.common.gedra.DSV
import com.dynamicruntime.common.gedra.GDF
import com.dynamicruntime.common.gedra.GE
import com.dynamicruntime.common.gedra.GEP
import com.dynamicruntime.common.gedra.GedraConfigReload
import com.dynamicruntime.common.gedra.GedraConfigService
import com.dynamicruntime.common.gedra.GedraConfigType
import com.dynamicruntime.common.gedra.GedraDataType
import com.dynamicruntime.common.gedra.GedraId
import com.dynamicruntime.common.gedra.gedraConfig
import com.dynamicruntime.common.gedra.traitDataTypeName
import com.dynamicruntime.common.gedra.workflow.WFD
import com.dynamicruntime.common.gedra.workflow.WSF
import com.dynamicruntime.common.gedra.workflow.WfEntry
import com.dynamicruntime.common.gedra.workflow.WfSaveKind
import com.dynamicruntime.common.http.request.ROLE
import com.dynamicruntime.common.naming.clientNamespace
import com.dynamicruntime.common.schema.SCH
import com.dynamicruntime.common.schema.SL
import com.dynamicruntime.common.schema.SLM
import com.dynamicruntime.common.user.TestUser
import com.dynamicruntime.common.util.toJsonListOfMaps
import com.dynamicruntime.common.util.toJsonMapOrEmpty
import com.dynamicruntime.common.util.toOptStr
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain

/**
 * A workflow's form showing only the fields its layout lists (issue #1071): its edit save writes those fields over the
 * stored entry and leaves the rest -- so workflows filling different parts of one questionnaire keep each other's
 * answers -- refuses a field it does not show, and still has the merged entry validated whole. Design View sets and
 * removes the list. Over a client of its own: a questionnaire trait, a creation workflow, and two workflows -- a survey
 * and a normal one -- each listing different questions.
 */
class FieldsShownTest : StringSpec({
    val cxt = TestInstances.default("fieldsShown1071")
    val client = "fieldsshown1071"
    val dataType = "${clientNamespace(client)}.${traitDataTypeName("QuestionsEntry")}"

    fun shows(vararg fields: String) = mapOf(
        SCH.layout to mapOf(SL.mode to SLM.authoritative, SL.schemaFields to fields.map { mapOf(SL.field to it) }),
    )
    /** The client, its questionnaire -- every question optional, [q6Max] the longest answer to the sixth -- and workflows. */
    fun writeClient(q6Max: Long = 20) {
        val config = gedraConfig(cxt, "main", clientNamespace(client), client) {
            defineClient(
                ClientDef(
                    clientId = client, name = client, usageType = ClientUsageType.dev,
                    audience = ClientAudience.internal, enabledEnvironments = setOf(ENV.unit, ENV.local),
                ),
            )
            trait("QuestionsEntry", "questions", setOf(GedraDataType.formDoc), "A long questionnaire.") {
                for (q in listOf("q1", "q2", "q3", "q4", "q5")) property(q, "Question $q.")
                property("q6", "Question q6.") { maxLength = q6Max }
            }
            workflow("start", WfEntry.creation) {
                task("all", "All of it") {
                    trait("questions")
                    save("create", "Create")
                }
            }
            workflow("partA", WfEntry.survey) {
                alterType(dataType, shows("q1", "q2"))
                task("a", "Part A") {
                    trait("questions")
                    save("saveA", "Save part A", WfSaveKind.edit)
                }
            }
            // A scope has one survey workflow, so part B is a normal one -- a form is engaged in it first.
            workflow("partB", WfEntry.normal) {
                alterType(dataType, shows("q3", "q4"))
                task("b", "Part B") {
                    trait("questions")
                    save("saveB", "Save part B", WfSaveKind.edit)
                }
            }
        }
        val setup = cxt.mkSubContext("setup", client)
        GedraConfigService.get(cxt).writeConfig(setup, config)
        // Published, as the Clients page would leave it: a Design View save publishes, so it refuses a config carrying a draft.
        GedraConfigService.get(cxt).publish(setup, GedraId.of(GedraConfigType.configDoc, client, "main"))
        GedraConfigReload.reloadClient(cxt, client)
    }
    writeClient()
    val admin = TestUser.create(cxt, "designer@$client.test", level = ROLE.admin, userClient = client)
    val savePath = clientPath(GEP.workflowSave, client)

    fun save(workflowId: String, taskId: String, saveId: String, data: Map<String, Any?>, gedraId: String? = null) = buildMap {
        put(WFD.workflowId, workflowId)
        put(GDF.taskId, taskId)
        put(GDF.saveId, saveId)
        put(GDF.entries, listOf(mapOf(GE.traitId to "questions", GE.data to data)))
        gedraId?.let { put(GDF.gedraId, it) }
    }
    fun newForm(): String = admin.postData(
        savePath, save("start", "all", "create", (1..6).associate { "q$it" to "answer $it" }),
    )[WSF.item].toJsonMapOrEmpty()[GDF.gedraId].toOptStr()!!.also { id ->
        admin.postData(clientPath(GEP.workflowEngage, client), mapOf(WFD.workflowId to "partB", GDF.gedraId to id))
    }
    fun answers(gedraId: String): Map<String, Any?> =
        admin.getItem(clientPath(GEP.formDoc, client), mapOf(GDF.gedraId to gedraId))[GDF.entries].toJsonListOfMaps()
            .single { it[GE.traitId] == "questions" }[GE.data].toJsonMapOrEmpty()

    "each workflow's save writes the fields its form shows, and leaves the other workflow's answers" {
        val form = newForm()
        // Part A answers q1 and leaves q2 empty: q2 is cleared, and everything part A does not show stays.
        admin.postData(savePath, save("partA", "a", "saveA", mapOf("q1" to "from A"), form))
        answers(form) shouldBe mapOf("q1" to "from A", "q3" to "answer 3", "q4" to "answer 4", "q5" to "answer 5", "q6" to "answer 6")
        // Part B, sending only its own two, keeps part A's answer.
        admin.postData(savePath, save("partB", "b", "saveB", mapOf("q3" to "from B", "q4" to "also B"), form))
        answers(form) shouldBe mapOf("q1" to "from A", "q3" to "from B", "q4" to "also B", "q5" to "answer 5", "q6" to "answer 6")
    }

    "a field the form does not show is refused, and nothing is written" {
        val form = newForm()
        admin.expectError(EXC.badInput, savePath, save("partA", "a", "saveA", mapOf("q1" to "x", "q5" to "not A's"), form))
            .toString() shouldContain "does not show 'q5'"
        answers(form)["q1"] shouldBe "answer 1"
    }

    "the merged entry is still validated whole: a stored answer the schema no longer accepts refuses the save" {
        val form = newForm()
        // q6 narrows below what was stored; part A never shows q6, but its save stores the whole entry.
        writeClient(q6Max = 3)
        try {
            admin.expectError(EXC.badInput, savePath, save("partA", "a", "saveA", mapOf("q1" to "x"), form)).toString() shouldContain "q6"
        } finally {
            writeClient()
        }
    }

    "a creation save is unchanged: it stores what it was given" {
        answers(newForm()).size shouldBe 6
    }

    "Design View sets the fields a workflow's form shows, and stops choosing them" {
        val viewPath = clientPath(GEP.workflowView, client)
        fun block(): Map<String, Any?> =
            admin.getData(viewPath, mapOf(EP.view to DSV.design, WFD.workflowId to "partA", GDF.gedraId to newForm()))[DSV.designBlock].toJsonMapOrEmpty()
        fun set(fields: List<String>?) = admin.postData(
            DSV.shownFieldsEdit,
            buildMap {
                put(DSV.workflowId, "partA")
                put(DSV.typeName, dataType)
                fields?.let { put(DSV.fields, it) }
                put(DSV.basedOn, block()[DSV.basedOn])
            },
        )
        block()[DSV.shownFields].toJsonMapOrEmpty()[dataType] shouldBe listOf("q1", "q2")
        set(listOf("q2", "q5", "q1"))
        block()[DSV.shownFields].toJsonMapOrEmpty()[dataType] shouldBe listOf("q2", "q5", "q1")
        // The save follows: q5 is part A's now.
        val form = newForm()
        admin.postData(savePath, save("partA", "a", "saveA", mapOf("q5" to "now A's"), form))
        answers(form)["q5"] shouldBe "now A's"
        // An empty list is refused; stopping the choice shows every field again.
        admin.expectError(EXC.badInput, DSV.shownFieldsEdit, mapOf(DSV.workflowId to "partA", DSV.typeName to dataType, DSV.fields to emptyList<String>(), DSV.basedOn to block()[DSV.basedOn]))
        set(null)
        block().containsKey(DSV.shownFields) shouldBe false
    }
})
