package com.dynamicruntime.sample.gedra

import com.dynamicruntime.common.endpoint.clientPath
import com.dynamicruntime.common.exception.EXC
import com.dynamicruntime.common.exception.KdrException
import com.dynamicruntime.common.gedra.GDF
import com.dynamicruntime.common.gedra.GE
import com.dynamicruntime.common.gedra.GEP
import com.dynamicruntime.common.gedra.GIF
import com.dynamicruntime.common.gedra.GT
import com.dynamicruntime.common.gedra.GedraDataService
import com.dynamicruntime.common.gedra.GedraDataType
import com.dynamicruntime.common.gedra.workflow.WSF
import com.dynamicruntime.common.gedra.workflow.WorkflowService
import com.dynamicruntime.common.gedra.workflow.saveWorkflow
import com.dynamicruntime.common.user.TestUser
import com.dynamicruntime.common.util.toJsonListOfMaps
import com.dynamicruntime.common.util.toJsonMapOrEmpty
import com.dynamicruntime.common.util.toOptStr
import com.dynamicruntime.kdn.Startup
import com.dynamicruntime.sample.SampleComponent
import io.kotest.assertions.throwables.shouldThrow
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

    "a create save naming an existing form is refused, and makes no second form (issue #817)" {
        val existing = save(base(listOf(nameEntry("First"))))[WSF.item].toJsonMapOrEmpty()[GDF.gedraId].toOptStr()!!
        fun count() = globex.getItems(clientPath(GEP.formDocs, SC.globex)).size
        val before = count()
        val refused = globex.expectError(EXC.badInput, savePath, base(listOf(nameEntry("Second"))) + (GDF.gedraId to existing))
        refused["errorMessage"].toOptStr().orEmpty() shouldContain "creates a new form"
        count() shouldBe before
        // Nor is the creation workflow opened against the form: the page would draw it under a "new form" heading.
        globex.expectError(
            EXC.badInput, clientPath(GEP.workflowView, SC.globex),
            args = mapOf(GDF.workflowId to SW.createForm, GDF.gedraId to existing),
        )["errorMessage"].toOptStr().orEmpty() shouldContain "cannot be opened against an existing one"
    }

    "an edit save whose entry carries no data is refused, never stored as {}; an explicit {} is accepted (issue #818)" {
        val acme = TestUser.create(cxt, "save818@acme.test", userClient = SC.acme)
        val gid = acme.postItem(
            clientPath(GEP.formDocCreate, SC.acme),
            mapOf(GDF.entries to listOf(mapOf(GE.traitId to ST.expenseReport, GE.data to mapOf(ST.year to 2026)))),
        )[GDF.gedraId].toOptStr()!!
        val acmeSave = clientPath(GEP.workflowSave, SC.acme)
        fun edit(entry: Map<String, Any?>) = mapOf(
            GDF.workflowId to SW.reviewForm, GDF.taskId to SW.details, GDF.saveId to SW.saveDetails, GDF.gedraId to gid,
            GDF.entries to listOf(entry),
        )
        fun hasQuestionnaire() = acme.getItem(clientPath(GEP.formDoc, SC.acme), mapOf(GDF.gedraId to gid))[GDF.entries]
            .toJsonListOfMaps().any { it[GE.traitId] == ST.questionnaire }
        // Over HTTP the endpoint's schema check refuses it before the save runs.
        acme.expectError(EXC.badInput, acmeSave, edit(mapOf(GE.traitId to ST.questionnaire)))
        hasQuestionnaire() shouldBe false
        // Called from code, past that check, the save refuses it itself -- naming the trait, and pointing at delete.
        val declared = WorkflowService.get(cxt).forClient(SC.acme).survey!!
        shouldThrow<KdrException> {
            saveWorkflow(
                cxt.mkSubContext("save818", SC.acme).also { it.userId = acme.userId }, declared, SW.details, SW.saveDetails,
                listOf(mapOf(GE.traitId to ST.questionnaire)), gid,
            )
        }.message.orEmpty().let {
            it shouldContain "'${ST.questionnaire}' entry carries no data"
            it shouldContain "delete"
        }
        hasQuestionnaire() shouldBe false
        // An explicit {} is data -- "these fields, none of them" -- and is taken.
        acme.postData(acmeSave, edit(mapOf(GE.traitId to ST.questionnaire, GE.data to emptyMap<String, Any?>())))[WSF.saved] shouldBe true
        hasQuestionnaire() shouldBe true
    }

    "no path stores an entry with no data as {} -- import over HTTP, create from code -- and a wrong shape says so" {
        val acme = TestUser.create(cxt, "save818b@acme.test", userClient = SC.acme)
        val acmeImport = clientPath(GEP.formDocImport, SC.acme)
        val noData = mapOf(GIF.data to mapOf(GDF.entries to listOf(mapOf(GE.traitId to ST.questionnaire))))
        // The import's `data` is free-form, so this one reaches the service over HTTP: refused, or dropped and counted.
        acme.expectError(EXC.badInput, acmeImport, noData)["errorMessage"].toOptStr().orEmpty() shouldContain
            "'${ST.questionnaire}' entry carries no data"
        val forgiven = acme.postData(acmeImport, noData + (GIF.forgiveInvalidEntries to true))
        forgiven[GIF.imported].toJsonListOfMaps().isEmpty() shouldBe true
        forgiven[GIF.discarded].toJsonListOfMaps().any {
            it[GIF.category] == GIF.invalidEntry && it[GE.traitId] == ST.questionnaire
        } shouldBe true
        // A create from code, past the endpoint's schema check.
        val asAcme = cxt.mkSubContext("save818b", SC.acme).also { it.userId = acme.userId }
        shouldThrow<KdrException> {
            GedraDataService.get(cxt).createGedra(asAcme, GedraDataType.formDoc, listOf(mapOf(GE.traitId to ST.questionnaire)))
        }.message.orEmpty() shouldContain "carries no data"
        // Data that is not an object is its own mistake, not "no data".
        shouldThrow<KdrException> {
            GedraDataService.get(cxt).createGedra(
                asAcme, GedraDataType.formDoc, listOf(mapOf(GE.traitId to ST.questionnaire, GE.data to "n/a")),
            )
        }.message.orEmpty() shouldContain "is not an object"
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
