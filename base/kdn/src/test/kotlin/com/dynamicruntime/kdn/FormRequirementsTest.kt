package com.dynamicruntime.kdn

import com.dynamicruntime.common.context.ENV
import com.dynamicruntime.common.endpoint.clientPath
import com.dynamicruntime.common.exception.KdrException
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
import com.dynamicruntime.common.gedra.workflow.WVF
import com.dynamicruntime.common.gedra.workflow.WfEntry
import com.dynamicruntime.common.gedra.workflow.WfSaveKind
import com.dynamicruntime.common.gedra.workflow.WorkflowService
import com.dynamicruntime.common.gedra.workflow.WorkflowTaskStatus
import com.dynamicruntime.common.naming.clientNamespace
import com.dynamicruntime.common.schema.SCH
import com.dynamicruntime.common.schema.SCT
import com.dynamicruntime.common.schema.SL
import com.dynamicruntime.common.user.TestUser
import com.dynamicruntime.common.util.toJsonListOfMaps
import com.dynamicruntime.common.util.toJsonMapOrEmpty
import com.dynamicruntime.common.util.toOptStr
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain

/**
 * Form requirements (issue #1022): a workflow's layout asks for more than the schema -- a field it requires, a shorter
 * list of choices -- on its own save and in its own task status, and nowhere else. One client, one event type, and
 * two workflows over it: `plan`, its creation workflow, asks for the attendees and the rain plan and offers only the
 * park and the office; `review`, a survey, asks for the attendees. A plain form create -- a general data edit --
 * is held to the schema alone, which is how "saved elsewhere" is made here.
 */
class FormRequirementsTest : StringSpec({
    val cxt = Startup.mkTestBootCxt("formReq1022", "formReq1022")
    val client = "formreq1022"
    val eventType = "client.$client.Event"
    fun alteration(vararg entries: Map<String, Any?>): Map<String, Any?> = mapOf(SCH.layout to mapOf(SL.schemaFields to entries.toList()))
    val asksForAttendees = mapOf(SL.field to "attendees", SL.required to true)

    fun config(planChoices: List<String> = listOf("park", "office")) = gedraConfig(cxt, "main", clientNamespace(client), client) {
        defineClient(
            ClientDef(
                clientId = client, name = client, usageType = ClientUsageType.dev,
                audience = ClientAudience.internal, enabledEnvironments = setOf(ENV.unit, ENV.local),
            ),
        )
        type("Event") {
            type = SCT.kObject
            property("title", "What it is.", required = true)
            property("venue", "Where it is held.") {
                option("office", "At the office")
                option("hotel", "A hotel")
                option("park", "A park")
            }
            property("attendees", "How many are expected.") { type = SCT.integer }
            property("rainPlan", "What happens if it rains.")
            // The rain plan is asked for only in the park; elsewhere the schema withdraws it.
            presentWhen("rainPlan", on = "venue", value = "park")
        }
        trait("EventEntry", "event", setOf(GedraDataType.formDoc), dataType = eventType)
        workflow("plan", WfEntry.creation) {
            alterType(
                eventType,
                alteration(
                    asksForAttendees,
                    mapOf(SL.field to "rainPlan", SL.required to true),
                    mapOf(SL.field to "venue", SL.choices to planChoices.map { mapOf(SL.value to it) }),
                ),
            )
            task("describe", "Describe") {
                trait("event")
                save("create", "Create")
            }
        }
        workflow("review", WfEntry.survey) {
            alterType(eventType, alteration(asksForAttendees))
            task("check", "Check") {
                trait("event")
                save("save", "Save", WfSaveKind.edit)
            }
        }
    }
    GedraConfigService.get(cxt).writeConfig(cxt.mkSubContext("setup", client), config())
    GedraConfigReload.reloadClient(cxt, client)
    val user = TestUser.create(cxt, "u@$client.test", userClient = client)

    fun event(vararg data: Pair<String, Any?>) = listOf(mapOf(GE.traitId to "event", GE.data to mapOf("title" to "Offsite", *data)))
    fun saveArgs(workflowId: String, vararg data: Pair<String, Any?>, gedraId: String? = null) = buildMap {
        put(GDF.workflowId, workflowId)
        put(GDF.taskId, if (workflowId == "review") "check" else "describe")
        put(GDF.saveId, if (workflowId == "review") "save" else "create")
        gedraId?.let { put(GDF.gedraId, it) }
        put(GDF.entries, event(*data))
    }
    val savePath = clientPath(GEP.workflowSave, client)
    // Saved elsewhere: a plain form create, which the schema alone judges.
    fun plainForm(vararg data: Pair<String, Any?>): String =
        user.postItem(GEP.formDocCreate, mapOf(GDF.entries to event(*data)))[GDF.gedraId].toOptStr()!!

    "a field the form requires is refused missing on its save, and only there" {
        user.expectError(400, savePath, saveArgs("plan", "venue" to "office")).toString() shouldContain "attendees"
        user.postData(savePath, saveArgs("plan", "venue" to "office", "attendees" to 12))["saved"] shouldBe true
        // The schema does not ask for it, so a general data edit saves without it.
        plainForm("venue" to "office")
    }

    "a choice the form does not offer is refused on its save, and accepted elsewhere" {
        user.expectError(400, savePath, saveArgs("plan", "venue" to "hotel", "attendees" to 12)).toString() shouldContain "not offered on this form"
        plainForm("venue" to "hotel")
    }

    "what the schema withdraws for the answers given, the form does not ask for" {
        // At the office the schema withdraws the rain plan, so the form's requirement waits.
        user.postData(savePath, saveArgs("plan", "venue" to "office", "attendees" to 3))["saved"] shouldBe true
        // In the park the schema asks for it itself.
        user.expectError(400, savePath, saveArgs("plan", "venue" to "park", "attendees" to 3))
        user.postData(savePath, saveArgs("plan", "venue" to "park", "attendees" to 3, "rainPlan" to "The hall"))["saved"] shouldBe true
    }

    "data saved elsewhere that misses a form's requirements leaves that form's task unfinished" {
        val formId = plainForm("venue" to "park", "rainPlan" to "Tents")
        val review = WorkflowService.get(cxt).forClient(client).workflow("review")!!
        val entries = event("venue" to "park", "rainPlan" to "Tents")
        val task = review.def.tasks.single()
        val judged = WorkflowTaskStatus.of(cxt.mkSubContext("judge", client), client, task, entries, declared = review)
        judged.valid shouldBe false
        judged.failures["event"]!!.single().path shouldBe "data.attendees"
        judged.failures["event"]!!.single().formRequirement shouldBe true
        // Judged by the schema alone, the same data is fine.
        WorkflowTaskStatus.of(cxt.mkSubContext("judge", client), client, task, entries).valid shouldBe true

        // The survey's own save is held to its form too, and goes through once the form's answer is there.
        user.expectError(400, savePath, saveArgs("review", "venue" to "park", "rainPlan" to "Tents", gedraId = formId))
        user.postData(savePath, saveArgs("review", "venue" to "park", "rainPlan" to "Tents", "attendees" to 40, gedraId = formId))["saved"] shouldBe true
    }

    "the workflow's page receives its form requirements with its layout" {
        val view = user.getData(clientPath(GEP.workflowView, client), mapOf(GDF.workflowId to "plan"))
        val fields = view[WVF.fieldLayouts].toJsonMapOrEmpty()[eventType].toJsonMapOrEmpty()[SL.schemaFields].toJsonListOfMaps()
        fields.single { it[SL.field] == "attendees" }[SL.required] shouldBe true
        fields.single { it[SL.field] == "venue" }[SL.choices].toJsonListOfMaps().map { it[SL.value] } shouldBe listOf("park", "office")
        // The survey's page asks only for what its own form does.
        val reviewFields = user.getData(clientPath(GEP.workflowView, client), mapOf(GDF.workflowId to "review"))[WVF.fieldLayouts]
            .toJsonMapOrEmpty()[eventType].toJsonMapOrEmpty()[SL.schemaFields].toJsonListOfMaps()
        reviewFields.map { it[SL.field] } shouldBe listOf("attendees")
    }

    "a form that offers a choice the schema does not is refused at load" {
        val refused = shouldThrow<KdrException> {
            GedraConfigService.get(cxt).writeConfig(cxt.mkSubContext("setup", client), config(listOf("park", "garden")), trial = true)
        }
        refused.message.orEmpty() shouldContain "garden"
    }
})
