package com.dynamicruntime.sample.gedra

import com.dynamicruntime.common.endpoint.clientPath
import com.dynamicruntime.common.exception.EXC
import com.dynamicruntime.common.gedra.GDF
import com.dynamicruntime.common.gedra.GE
import com.dynamicruntime.common.gedra.GEP
import com.dynamicruntime.common.gedra.GT
import com.dynamicruntime.common.gedra.workflow.WSF
import com.dynamicruntime.common.user.TestUser
import com.dynamicruntime.common.util.toJsonListOfMaps
import com.dynamicruntime.common.util.toJsonMapOrEmpty
import com.dynamicruntime.common.util.toOptStr
import com.dynamicruntime.kdn.Startup
import com.dynamicruntime.sample.SampleComponent
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain

/**
 * The workflow save endpoint and its gate over real HTTP (issue #535). globex's creation workflow collects
 * `name`; a satisfied save creates the form, stamps the workflow reference, and answers with the stored row,
 * while the two kinds of "no" stay apart: an unfinished form is a *result* naming what is missing, a mistake
 * is a loud 400.
 */
class WorkflowSaveTest : StringSpec({
    val cxt = Startup.mkTestBootCxt(
        "wfSave", "wfSaveTest", mapOf("KDR_LOAD_SAMPLE" to "true"), additionalComponents = listOf(SampleComponent()),
    )
    val globex = TestUser.create(cxt, "save@globex.test", userClient = SC.globex)
    val savePath = clientPath(GEP.workflowSave, SC.globex)

    fun save(body: Map<String, Any?>): Map<String, Any?> = globex.postData(savePath, body)

    fun nameEntry(name: String) = mapOf(GE.traitId to GT.name, GE.data to mapOf(GT.name to name))

    fun base(entries: List<Map<String, Any?>>): Map<String, Any?> = mapOf(
        GDF.workflowId to SW.createForm, GDF.taskId to SW.identify, GDF.saveId to SW.create, GDF.entries to entries,
    )

    "a satisfied save creates the form, stamps the creation workflow, and answers with the stored row" {
        val res = save(base(listOf(nameEntry("My form"))))
        res[WSF.saved] shouldBe true
        val item = res[WSF.item].toJsonMapOrEmpty()
        val id = item[GDF.gedraId].toOptStr().shouldNotBeNull()
        id.contains(".${SC.globex}.") shouldBe true
        item[GDF.entries].toJsonListOfMaps().single()[GE.data].toJsonMapOrEmpty()[GT.name] shouldBe "My form"
        // The workflow reference is stamped and derived onto the wire (g-derived), so a read-back carries it.
        (item[GDF.creationWorkflowId] as String) shouldContain "gc.cd.${SC.globex}."
        (item[GDF.creationWorkflowId] as String) shouldContain "#${SW.createForm}"

        // And it survives a fresh read of the stored form.
        val reread = globex.getItem(clientPath(GEP.formDoc, SC.globex), mapOf(GDF.gedraId to id))
        (reread[GDF.creationWorkflowId] as String) shouldContain "#${SW.createForm}"
    }

    "an incomplete save is a result naming the unmet required traits, not an error" {
        val res = save(base(emptyList()))
        res[WSF.saved] shouldBe false
        (res[WSF.unmetTraits] as List<*>).map { it.toString() } shouldContainExactly listOf("name")
    }

    "an entry naming a trait the task does not collect is a loud 400" {
        globex.expectError(
            EXC.badInput, savePath,
            base(listOf(mapOf(GE.traitId to "expenseReport", GE.data to emptyMap<String, Any?>()))),
        )
    }

    "an unknown task is a 400" {
        globex.expectError(EXC.badInput, savePath, base(listOf(nameEntry("x"))) + (GDF.taskId to "nope"))
    }

    "an unknown save is a 400" {
        globex.expectError(EXC.badInput, savePath, base(listOf(nameEntry("x"))) + (GDF.saveId to "nope"))
    }

    "an unknown workflow is a 404" {
        globex.expectError(EXC.notFound, savePath, base(listOf(nameEntry("x"))) + (GDF.workflowId to "nope"))
    }
})
