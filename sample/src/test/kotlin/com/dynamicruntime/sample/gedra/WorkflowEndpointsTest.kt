package com.dynamicruntime.sample.gedra

import com.dynamicruntime.common.endpoint.EI
import com.dynamicruntime.common.cfact.CFACTS
import com.dynamicruntime.common.content.UIC
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
import com.dynamicruntime.common.gedra.workflow.WAGG
import com.dynamicruntime.common.gedra.workflow.WCOL
import com.dynamicruntime.common.gedra.workflow.WFC
import com.dynamicruntime.common.gedra.workflow.WFD
import com.dynamicruntime.common.gedra.workflow.WFT
import com.dynamicruntime.common.gedra.workflow.WSF
import com.dynamicruntime.common.gedra.workflow.WVF
import com.dynamicruntime.common.gedra.workflow.WfColumnCategory
import com.dynamicruntime.common.gedra.workflow.WfEntry
import com.dynamicruntime.common.gedra.workflow.WfEventType
import com.dynamicruntime.common.gedra.workflow.WfPhase
import com.dynamicruntime.common.gedra.workflow.WfSaveKind
import com.dynamicruntime.common.gedra.workflow.WorkflowService
import com.dynamicruntime.common.gedra.workflow.saveWorkflow
import com.dynamicruntime.common.home.HEP
import com.dynamicruntime.common.home.HFLD
import com.dynamicruntime.common.home.HMENU
import com.dynamicruntime.common.http.request.ROLE
import com.dynamicruntime.common.schema.SCH
import com.dynamicruntime.common.startup.SchemaService
import com.dynamicruntime.common.user.ADF
import com.dynamicruntime.common.user.TestUser
import com.dynamicruntime.common.user.UADEP
import com.dynamicruntime.common.util.toJsonListOfMaps
import com.dynamicruntime.common.util.toJsonMapOrEmpty
import com.dynamicruntime.common.util.toOptStr
import com.dynamicruntime.kdn.Startup
import com.dynamicruntime.sample.SampleComponent
import com.dynamicruntime.sample.SampleTestInstances
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.core.spec.style.stringSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldContainAll
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.collections.shouldNotContain
import io.kotest.matchers.maps.shouldContainKey
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain

/**
 * The workflow endpoints over the sample's clients (issue #1736 gathered these): what boots, saving a task, the
 * workflow view, its pages, and a workflow's `visibleWhen`. Each part below is one of the specs that used to be a file
 * of its own, kept whole, on the shared sample instance. Approvals stay in `WorkflowApprovalTest`, and a workflow's
 * state in `FormStateTest`.
 */
class WorkflowEndpointsTest : StringSpec({
    include(workflowBootTests)
    include(workflowSaveTests)
    include(workflowTaskSaveTests)
    include(workflowViewTests)
    include(workflowPagesTests)
    include(workflowVisibleWhenTests)
})

/**
 * The workflow wiring on a real boot (issue #533): the two sample creation workflows arrive through the
 * component, pass the checks against the real clients, schema, and fragment registry, and land in the right
 * scopes. The refusals themselves are covered over plain data in `WorkflowRegistryTest`; this is the smoke
 * that the pieces are connected.
 */
private val workflowBootTests = stringSpec {
    // The sample component is loaded only in developer environments, so a unit boot has to force it on --
    // the same way every other sample test reaches its own fixtures.
    val cxt = SampleTestInstances.default("wfBoot")
    val service = WorkflowService.get(cxt)

    "the boot built the registries with no problems" {
        service.issues.shouldBeEmpty()
        // The global creation workflow is core's template (issue #1120), which no client runs: each sample client
        // declares its own.
        service.forClient(null).creation.shouldNotBeNull().def.workflowId shouldBe WFT.createForm
    }

    "acme sees its richer creation workflow, labels pulled from the backend fragment file" {
        val acme = service.forClient(SC.acme).creation.shouldNotBeNull()
        acme.def.entry shouldBe WfEntry.creation
        acme.ref.text shouldContain "gc.cd.${SC.acme}."
        acme.ref.workflowId shouldBe SW.createForm
        val task = acme.def.tasks.single()
        task.requiredTraitIds shouldBe listOf(ST.expenseReport)
        task.displayOrder shouldBe listOf(ST.questionnaire, ST.expenseReport)
        task.label shouldContain "@t("
        task.saves.single().label shouldContain "@t("
        // The workflow's own label (issue #719) rides the same file and the same boot check.
        acme.def.label shouldContain "@t("
    }

    "globex sees the name-only creation workflow with literal labels" {
        val globex = service.forClient(SC.globex).creation.shouldNotBeNull()
        globex.def.tasks.single().requiredTraitIds shouldBe listOf(GT.name)
        globex.def.tasks.single().label shouldBe "Name the form"
        globex.def.showTaskList shouldBe false
        // No workflow label declared: the page titles itself.
        globex.def.label shouldBe ""
    }

    "acme also sees a core details workflow -- two edit-save tasks, beside its creation workflow" {
        val coreDetails = service.forClient(SC.acme).coreDetails.shouldNotBeNull()
        coreDetails.def.entry shouldBe WfEntry.coreDetails
        coreDetails.ref.workflowId shouldBe SW.reviewForm
        coreDetails.def.showTaskList shouldBe true
        coreDetails.def.tasks.map { it.id } shouldBe listOf(SW.details, SW.profile)
        coreDetails.def.tasks.flatMap { it.saves }.map { it.kind }.toSet() shouldBe setOf(WfSaveKind.edit)
        // The profile task carries the two supplied-default functions (issue #711): name and email prefilled from the
        // owner. Both resolve at boot, so the core details workflow's prefill wiring is checked here, not only live.
        val profile = coreDetails.def.tasks.single { it.id == SW.profile }
        profile.resolvedFunctions.filter { it.event == WfEventType.prefillData } shouldHaveSize 2
        // Its labels ride the same backend fragment file, so the pull resolved at boot like creation's.
        coreDetails.def.tasks.first().label shouldContain "@t("
        coreDetails.def.label shouldContain "@t("
        // Two single-instance kinds coexist in one scope: the creation workflow is still resolvable.
        service.forClient(SC.acme).creation.shouldNotBeNull().ref.workflowId shouldBe SW.createForm
        // globex declares no core details workflow, and none is global, so it inherits none.
        service.forClient(SC.globex).coreDetails.shouldBeNull()
    }

    "the definition schema and the two workflow cfacts are published" {
        val schema = SchemaService.get(cxt)
        schema.storeFor(null).types shouldContainKey "${WFD.namespace}.${WFD.defType}"
        schema.cfactsFor(null).names shouldContainAll listOf(WFC.taskComplete, WFC.taskAvailable)
    }
}

/**
 * The workflow save endpoint and its gate over real HTTP (issue #535). globex's creation workflow collects
 * `name`; a satisfied save creates the form, stamps the workflow reference, and answers with the stored row,
 * while the two kinds of "no" stay apart: an unfinished form is a *result* naming what is missing, a mistake
 * is a loud 400.
 */
private val workflowSaveTests = stringSpec {
    val cxt = SampleTestInstances.default("wfSave")
    val globex = TestUser.create(cxt, "save@globex.test", userClient = SC.globex)
    val savePath = clientPath(GEP.workflowSave, SC.globex)

    fun save(body: Map<String, Any?>): Map<String, Any?> = globex.postData(savePath, body)

    fun nameEntry(name: String) = mapOf(GE.traitId to GT.name, GE.data to mapOf(GT.nameField to name))

    fun base(entries: List<Map<String, Any?>>): Map<String, Any?> = mapOf(
        GDF.workflowId to SW.createForm, GDF.taskId to SW.identify, GDF.saveId to SW.create, GDF.entries to entries,
    )

    "a satisfied save creates the form, stamps the creation workflow, and answers with the stored row" {
        val res = save(base(listOf(nameEntry("My form"))))
        res[WSF.saved] shouldBe true
        val item = res[WSF.item].toJsonMapOrEmpty()
        val id = item[GDF.gedraId].toOptStr().shouldNotBeNull()
        id.contains(".${SC.globex}.") shouldBe true
        item[GDF.entries].toJsonListOfMaps().single()[GE.data].toJsonMapOrEmpty()[GT.nameField] shouldBe "My form"
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
        (res[WSF.unmetTraits] as List<*>).map { it.toString() } shouldContainExactly listOf(GT.name)
    }

    "an entry naming a trait the task does not collect is a loud 400" {
        globex.expectError(
            EXC.badInput, savePath,
            base(listOf(mapOf(GE.traitId to ST.expenseReport, GE.data to emptyMap<String, Any?>()))),
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
        val declared = WorkflowService.get(cxt).forClient(SC.acme).coreDetails!!
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
}

/**
 * Who may save a normal workflow's task, and when (issue #856): a task's rule for who may save it is enforced by the
 * save endpoint and reported by the view, so the two agree; and a task is saved only on a form engaged with its
 * workflow.
 *
 * Acme's "Record the audit" is the reviewers' (`saveWhen(kdr:reviewer)` beside a `userHasLabel`); its site follow-up's
 * tasks say nothing, so anyone who can see the form may save them -- once the form is in the follow-up.
 */
private val workflowTaskSaveTests = stringSpec {
    val cxt = SampleTestInstances.default("wfTaskSave856")
    val create = clientPath(GEP.formDocCreate, SC.acme)
    val engage = clientPath(GEP.workflowEngage, SC.acme)
    val save = clientPath(GEP.workflowSave, SC.acme)
    val view = clientPath(GEP.workflowView, SC.acme)

    val owner = TestUser.create(cxt, "save-owner@acme.test", userClient = SC.acme)
    val labeller = TestUser.create(cxt, "save-labeller@acme.test", userClient = SC.acme, level = ROLE.admin)
    val reviewer = TestUser.create(cxt, "save-reviewer@acme.test", userClient = SC.acme, level = ROLE.admin)
    labeller.postData(UADEP.userSetLabels, mapOf(ADF.userId to reviewer.userId, ADF.labels to listOf(SC.reviewerLabel)))
    // Whoever makes follow-up visits: acme's `siteLead` label, which the follow-up's record is restricted to.
    val siteLead = TestUser.create(cxt, "save-sitelead@acme.test", userClient = SC.acme, level = ROLE.admin)
    labeller.postData(UADEP.userSetLabels, mapOf(ADF.userId to siteLead.userId, ADF.labels to listOf(SC.siteLeadLabel)))

    fun newForm(): String = owner.postItem(
        create,
        mapOf(
            GDF.entries to listOf(
                mapOf(GE.traitId to ST.expenseReport, GE.data to mapOf(ST.year to 2026)),
                mapOf(GE.traitId to SC.userInfo, GE.data to mapOf(SC.userName to "A Person")),
                mapOf(GE.traitId to SC.siteAudit, GE.data to mapOf(SC.auditor to "A Person", SC.findings to "seen")),
            ),
        ),
    )[GDF.gedraId].toOptStr()!!

    fun auditSave(gid: String, findings: String) = mapOf(
        WFD.workflowId to SW.auditReview, GDF.taskId to SW.recordAudit, GDF.saveId to SW.saveAudit, GDF.gedraId to gid,
        GDF.entries to listOf(mapOf(GE.traitId to SC.siteAudit, GE.data to mapOf(SC.auditor to "The Reviewer", SC.findings to findings))),
    )

    fun contactSave(gid: String) = mapOf(
        WFD.workflowId to SW.siteFollowUp, GDF.taskId to SW.confirmContact, GDF.saveId to SW.saveContact, GDF.gedraId to gid,
        GDF.entries to listOf(mapOf(GE.traitId to SC.userInfo, GE.data to mapOf(SC.userName to "Site Contact"))),
    )

    fun canSave(user: TestUser, gid: String, workflowId: String, taskId: String) =
        user.getData(view, mapOf(WFD.workflowId to workflowId, GDF.gedraId to gid))[WFD.tasks].toJsonListOfMaps()
            .first { it[WFD.id] == taskId }[WVF.canSave]

    "only a reviewer may record the audit, and the view says so to each" {
        val gid = newForm()
        owner.postData(engage, mapOf(GDF.gedraId to gid, WFD.workflowId to SW.auditReview))

        canSave(owner, gid, SW.auditReview, SW.recordAudit) shouldBe false
        canSave(reviewer, gid, SW.auditReview, SW.recordAudit) shouldBe true

        owner.expectError(EXC.notAuthorized, save, auditSave(gid, "open"))["errorMessage"].toOptStr().orEmpty() shouldContain
            "may not save task '${SW.recordAudit}'"
        reviewer.postData(save, auditSave(gid, "open"))[WSF.saved] shouldBe true
    }

    "a task that says nothing is anyone's who can see the form -- the owner confirms the contact" {
        val gid = newForm()
        owner.postData(engage, mapOf(GDF.gedraId to gid, WFD.workflowId to SW.siteFollowUp))
        canSave(owner, gid, SW.siteFollowUp, SW.confirmContact) shouldBe true
        owner.postData(save, contactSave(gid))[WSF.saved] shouldBe true
    }

    "the follow-up is recorded by a site lead, on its own trait, and cannot rewrite the audit" {
        val gid = newForm()
        owner.postData(engage, mapOf(GDF.gedraId to gid, WFD.workflowId to SW.siteFollowUp))
        fun followUpSave(traitId: String, data: Map<String, Any?>) = mapOf(
            WFD.workflowId to SW.siteFollowUp, GDF.taskId to SW.recordFollowUp, GDF.saveId to SW.saveFollowUp,
            GDF.gedraId to gid, GDF.entries to listOf(mapOf(GE.traitId to traitId, GE.data to data)),
        )
        val record = followUpSave(SC.siteFollowUpTrait, mapOf(SC.followUpBy to "Site Lead"))
        // The owner confirms the contact, but the visit's record is the site lead's.
        canSave(owner, gid, SW.siteFollowUp, SW.recordFollowUp) shouldBe false
        canSave(siteLead, gid, SW.siteFollowUp, SW.recordFollowUp) shouldBe true
        owner.expectError(EXC.notAuthorized, save, record)
        siteLead.postData(save, record)[WSF.saved] shouldBe true
        // The audit is not the follow-up's to write: the task does not collect it, whoever asks.
        siteLead.expectError(EXC.badInput, save, followUpSave(SC.siteAudit, mapOf(SC.auditor to "Someone", SC.findings to "open")))
    }

    "a task is saved only on a form engaged with its workflow" {
        val gid = newForm()
        owner.expectError(EXC.badInput, save, contactSave(gid))["errorMessage"].toOptStr().orEmpty() shouldContain
            "not in workflow '${SW.siteFollowUp}'"
        owner.postData(engage, mapOf(GDF.gedraId to gid, WFD.workflowId to SW.siteFollowUp))
        owner.postData(save, contactSave(gid))[WSF.saved] shouldBe true
        // Taken back out: saving stops again.
        owner.postData(engage, mapOf(GDF.gedraId to gid, WFD.workflowId to SW.siteFollowUp, "engaged" to false))
        owner.expectError(EXC.badInput, save, contactSave(gid))
    }
}

/**
 * The resolved workflow view over real HTTP (issue #534): each client's creation workflow, fetched from its
 * own `/gedra/<client>/workflow/view`, resolves through the actual pipeline -- labels through the backend
 * fragment pass, traits into that client's schema. The two sample clients resolve **differently**, which is
 * the whole point of a per-client view, and a caller whose client has no creation workflow gets `found=false`.
 */
private val workflowViewTests = stringSpec {
    val cxt = SampleTestInstances.default("wfView")
    val acme = TestUser.create(cxt, "view@acme.test", userClient = SC.acme)
    val globex = TestUser.create(cxt, "view@globex.test", userClient = SC.globex)
    val everyone = TestUser.create(cxt, "view@public.test")

    fun creationView(user: TestUser, client: String): Map<String, Any?> =
        user.getData(clientPath(GEP.workflowView, client))

    "globex resolves the name-only creation workflow, its literal label passed through untouched" {
        val v = creationView(globex, SC.globex)
        v[WVF.found] shouldBe true
        v[WFD.workflowId] shouldBe SW.createForm
        (v[WVF.ref] as String) shouldContain "gc.cd.${SC.globex}."
        v[WVF.showTaskList] shouldBe false
        val task = v[WFD.tasks].toJsonListOfMaps().single()
        task[WFD.label] shouldBe "Name the form"       // a literal is a template with no blocks
        val traits = task[WFD.traits].toJsonListOfMaps()
        traits.single()[WFD.traitId] shouldBe GT.name
        traits.single()[WFD.required] shouldBe true
        (traits.single()[WVF.schemaRef] as String) shouldContain "NameData"
        val save = task[WFD.saves].toJsonListOfMaps().single()
        save[WFD.label] shouldBe "Create form"
        save[WFD.kind] shouldBe "create"
        // Nothing entered yet: available (placeholder), not complete -- and so the CTA, the view's focus task,
        // which the task says as a fact (issue #785). Its filter rule is no obstacle, as it carries none (issue #1595).
        task[WVF.facts] as List<*> shouldBe listOf(WFC.taskAvailable, WFC.isCta, WFC.passesCtaFilter)
    }

    "acme resolves its richer workflow, labels pulled from the backend fragment file, traits in task-layout order" {
        val v = creationView(acme, SC.acme)
        v[WVF.found] shouldBe true
        val task = v[WFD.tasks].toJsonListOfMaps().single()
        // Pulled from acmeWf.md (%{@t("acmeWf.identify.label")}) rather than a literal.
        task[WFD.label] shouldBe "Answer an issues question and enter your expense report"
        task[WFD.saves].toJsonListOfMaps().single()[WFD.label] shouldBe "Create expense form"
        // The task layout ordered the optional trait first; both carry a ref and their required flag. Today that
        // order is all the task layout does, so the layout itself is not sent (issue #834).
        val traits = task[WFD.traits].toJsonListOfMaps()
        traits.map { it[WFD.traitId] } shouldBe listOf(ST.questionnaire, ST.expenseReport)
        task.containsKey(WFD.layout) shouldBe false
        traits.first { it[WFD.traitId] == ST.expenseReport }[WFD.required] shouldBe true
        traits.first { it[WFD.traitId] == ST.questionnaire }[WFD.required] shouldBe false
        (traits.first()[WVF.schemaRef] as String) shouldContain ST.namespace
    }

    "the two clients resolve the same workflow id differently" {
        val a = creationView(acme, SC.acme)
        val g = creationView(globex, SC.globex)
        val aTask = a[WFD.tasks].toJsonListOfMaps().single()
        val gTask = g[WFD.tasks].toJsonListOfMaps().single()
        aTask[WFD.label] shouldNotBe gTask[WFD.label]
        aTask[WFD.traits].toJsonListOfMaps().map { it[WFD.traitId] } shouldNotBe
            gTask[WFD.traits].toJsonListOfMaps().map { it[WFD.traitId] }
    }

    "a backend fragment label leaves no unresolved block on the wire" {
        val label = creationView(acme, SC.acme)[WFD.tasks].toJsonListOfMaps().single()[WFD.label].toOptStr() ?: ""
        label shouldNotContain "@t("
        label shouldNotContain "%{"
    }

    "a caller whose client has no creation workflow gets found=false" {
        // The shared path, resolved for the caller's own (public) client, which declares no workflow.
        val v = everyone.getData(GEP.workflowView)
        v[WVF.found] shouldBe false
        v.containsKey(WFD.tasks) shouldBe false
    }

    $$"the view carries a self-contained $defs: the trait data types it references, and no more" {
        // globex: the one data type its `name` trait renders against, present in the response's own \$defs so a
        // page resolves the ref without a second fetch. The schemaRef names exactly that key.
        val g = creationView(globex, SC.globex)
        val gDefs = g[SCH.dDefs].toJsonMapOrEmpty()
        gDefs.keys shouldContain "kdr.core.NameData"
        val gRef = g[WFD.tasks].toJsonListOfMaps().single()[WFD.traits].toJsonListOfMaps().single()[WVF.schemaRef] as String
        gDefs.keys.any { gRef.endsWith(it) } shouldBe true

        // acme: exactly the two data types its workflow collects -- and NOT a type it merely supports but its
        // workflow does not touch (SiteVisit / SiteAddress), which is the point of a closure over the catalog.
        val aDefs = creationView(acme, SC.acme)[SCH.dDefs].toJsonMapOrEmpty()
        aDefs.keys shouldContainAll listOf("sample.traits.ExpenseReportData", "sample.traits.QuestionnaireData")
        aDefs.keys.any { it.contains("SiteVisit") || it.contains("SiteAddress") } shouldBe false
    }

    "an unknown workflow id is a 404" {
        globex.expectError(
            com.dynamicruntime.common.exception.EXC.notFound,
            clientPath(GEP.workflowView, SC.globex),
            mapOf(GDF.workflowId to "nope"),
        )
    }
}

/**
 * The workflow pages (issue #792): the aggregate counts each workflow's eligible, engaged and finished forms over
 * everything the caller may see; the forms listing's drill-down opens exactly the forms a count counted; and the
 * menu item is a client's to turn on.
 */
private val workflowPagesTests = stringSpec {
    val cxt = SampleTestInstances.default("wfPages792")
    val create = clientPath(GEP.formDocCreate, SC.acme)
    val engage = clientPath(GEP.workflowEngage, SC.acme)
    val list = clientPath(GEP.formDocs, SC.acme)

    fun newForm(user: TestUser, coreDetailsDone: Boolean): String {
        val audit = listOf(mapOf(GE.traitId to SC.siteAudit, GE.data to mapOf(SC.auditor to "A Person", SC.findings to "seen")))
        val coreDetails = listOf(
            mapOf(GE.traitId to ST.expenseReport, GE.data to mapOf(ST.year to 2026)),
            mapOf(GE.traitId to SC.userInfo, GE.data to mapOf(SC.userName to "A Person")),
        )
        return user.postItem(create,
            mapOf(GDF.entries to audit + if (coreDetailsDone) coreDetails else emptyList()))[GDF.gedraId].toOptStr()!!
    }

    fun aggregate(user: TestUser) = user.getItems(GEP.workflowAggregate).associateBy { it[WFD.workflowId].toOptStr()!! }
    fun counts(entry: Map<String, Any?>?) = listOf(entry?.get(WAGG.eligible), entry?.get(WAGG.engaged), entry?.get(WAGG.finished))
    fun drill(user: TestUser, workflowId: String, state: WfColumnCategory? = null) =
        user.getItems(list, mapOf(WAGG.workflowId to workflowId) + (state?.let { mapOf(WAGG.workflowState to it.name) } ?: emptyMap()))
            .map { it[GDF.gedraId].toOptStr()!! }

    "a caller with no forms still sees the workflows being calculated, with nothing counted" {
        val user = TestUser.create(cxt, "pages-empty@acme.test", userClient = SC.acme)
        val agg = aggregate(user)
        agg.keys shouldContainExactlyInAnyOrder listOf(SW.auditReview, SW.siteFollowUp)
        counts(agg[SW.auditReview]) shouldBe listOf(0L, 0L, 0L)
        agg.getValue(SW.auditReview)[WCOL.client] shouldBe SC.acme
        agg.getValue(SW.auditReview)[WFD.label] shouldBe "Audit review"
        agg.getValue(SW.auditReview)[WCOL.phase] shouldBe WfPhase.engageable.name
    }

    "each count is the forms in that state, and its drill-down opens exactly those forms" {
        val user = TestUser.create(cxt, "pages-counts@acme.test", userClient = SC.acme)
        val ready = newForm(user, coreDetailsDone = true)
        val unready = newForm(user, coreDetailsDone = false)
        val engaged = newForm(user, coreDetailsDone = true)
        user.postData(engage, mapOf(GDF.gedraId to engaged, GDF.workflowId to SW.auditReview))

        val agg = aggregate(user)
        // The unready form is not eligible for the audit, so it counts nowhere. The follow-up takes the other two: the
        // engaged form waits on the review (its call to action carries `review`, issue #1596), which holds the
        // follow-up off.
        counts(agg[SW.auditReview]) shouldBe listOf(1L, 1L, 0L)
        counts(agg[SW.siteFollowUp]) shouldBe listOf(2L, 0L, 0L)

        drill(user, SW.auditReview, WfColumnCategory.eligible) shouldBe listOf(ready)
        drill(user, SW.auditReview, WfColumnCategory.engaged) shouldBe listOf(engaged)
        drill(user, SW.auditReview, WfColumnCategory.finished) shouldBe emptyList()
        drill(user, SW.siteFollowUp, WfColumnCategory.eligible) shouldContainExactlyInAnyOrder listOf(ready, unready)
        // No state: every form whose column shows the workflow -- the ineligible one included.
        drill(user, SW.auditReview) shouldContainExactlyInAnyOrder listOf(ready, unready, engaged)
        // A workflow the client does not have matches nothing, as an unknown search value does.
        drill(user, "noSuchWorkflow") shouldBe emptyList()
    }

    "a cross-client administrator's drill-down resolves the workflow in each form's own client" {
        val owner = TestUser.create(cxt, "pages-cross@acme.test", userClient = SC.acme)
        val engaged = newForm(owner, coreDetailsDone = true)
        owner.postData(engage, mapOf(GDF.gedraId to engaged, GDF.workflowId to SW.auditReview))
        // No client named: the workflow is acme's, not the administrator's own client's, and the row still matches.
        // Narrowed to the owner, since the shared instance holds other specs' engaged forms too (issue #1736).
        val admin = TestUser.createFullAdmin(cxt, "pages-cross-admin@acme.test")
        val args = mapOf(
            WAGG.workflowId to SW.auditReview, WAGG.workflowState to WfColumnCategory.engaged.name, EI.user to owner.userId,
        )
        admin.getItems(GEP.formDocs, args).map { it[GDF.gedraId] } shouldBe listOf(engaged)
    }

    "the drill-down composes with the status filter" {
        val user = TestUser.create(cxt, "pages-compose@acme.test", userClient = SC.acme)
        newForm(user, coreDetailsDone = true)
        val unready = newForm(user, coreDetailsDone = false)
        user.getItems(list, mapOf(WAGG.workflowId to SW.siteFollowUp, "formStatus" to "needsInfo"))
            .map { it[GDF.gedraId] } shouldBe listOf(unready)
    }

    "the menu item is off by default, and acme turns it on" {
        fun menuIds(user: TestUser) =
            user.getData(HEP.homeUiConfig)[UIC.state].toJsonMapOrEmpty()[HFLD.menu].toJsonListOfMaps().map { it[HFLD.id] }
        menuIds(TestUser.create(cxt, "pages-menu@acme.test", userClient = SC.acme)) shouldContain HMENU.workflows
        menuIds(TestUser.create(cxt, "pages-menu@globex.test", userClient = SC.globex)) shouldNotContain HMENU.workflows
    }
}

/**
 * A creation-workflow form honors `g-visibleWhen` (issue #569): the resolved workflow view carries the caller's
 * frontend-delivered cfacts, so the page hides an admin-only field from an ordinary caller exactly as the
 * endpoint form does. The backend never drops the field -- it keeps the `g-visibleWhen` keyword for everyone and
 * lets the page decide -- so this asserts both halves: the cfact present-set differs by caller, and the gated
 * property is served to both.
 *
 * `acme`'s creation workflow collects `expenseReport`, whose data schema carries an admin-only `reviewerNote`
 * (`SampleTraits`), so acme's workflow view is where the two callers diverge.
 */
private val workflowVisibleWhenTests = stringSpec {
    val cxt = SampleTestInstances.default("wfVisibleWhen")
    val admin = TestUser.create(cxt, "wf-admin@acme.test", level = ROLE.admin, userClient = SC.acme)
    val ordinary = TestUser.create(cxt, "wf-user@acme.test", userClient = SC.acme)

    fun view(user: TestUser): Map<String, Any?> = user.getData(clientPath(GEP.workflowView, SC.acme))

    fun cfacts(view: Map<String, Any?>): Map<String, Any?> = view[WVF.cfacts].toJsonMapOrEmpty()

    // The `g-visibleWhen` gate on the admin-only field, read from whichever carried type declares `reviewerNote`
    // in the view's own `$defs` -- present for both callers, since the backend never drops a gated field.
    fun reviewerNoteGate(view: Map<String, Any?>): Any? {
        val type = view[SCH.dDefs].toJsonMapOrEmpty().values.map { it.toJsonMapOrEmpty() }
            .first { it[SCH.properties].toJsonMapOrEmpty().containsKey(ST.reviewerNote) }
        return type[SCH.properties].toJsonMapOrEmpty()[ST.reviewerNote].toJsonMapOrEmpty()[SCH.visibleWhen]
    }

    "the workflow view carries hasAdminLevel present for an admin and absent for an ordinary caller" {
        cfacts(view(admin))[CFACTS.hasAdminLevel] shouldBe true
        cfacts(view(ordinary))[CFACTS.hasAdminLevel] shouldBe false
    }

    "the admin-only field is served to both callers -- only the delivered cfacts differ" {
        // The field and its gate ride in the view's $defs for both; it is the page, against the cfacts above,
        // that shows it to the admin and hides it from the ordinary caller.
        reviewerNoteGate(view(admin)) shouldBe CFACTS.hasAdminLevel
        reviewerNoteGate(view(ordinary)) shouldBe CFACTS.hasAdminLevel
    }
}
