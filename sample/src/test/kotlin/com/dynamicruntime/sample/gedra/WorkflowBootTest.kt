package com.dynamicruntime.sample.gedra

import com.dynamicruntime.common.gedra.GT
import com.dynamicruntime.common.gedra.workflow.WFC
import com.dynamicruntime.common.gedra.workflow.WFD
import com.dynamicruntime.common.gedra.workflow.WfEntry
import com.dynamicruntime.common.gedra.workflow.WorkflowService
import com.dynamicruntime.common.startup.SchemaService
import com.dynamicruntime.kdn.Startup
import com.dynamicruntime.sample.SampleComponent
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainAll
import io.kotest.matchers.maps.shouldContainKey
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain

/**
 * The workflow wiring on a real boot (issue #533): the two sample creation workflows arrive through the
 * component, pass the checks against the real clients, schema and fragment registry, and land in the right
 * scopes. The refusals themselves are covered over plain data in `WorkflowRegistryTest`; this is the smoke
 * that the pieces are connected.
 */
class WorkflowBootTest : StringSpec({
    // The sample component is loaded only in developer environments, so a unit boot has to force it on --
    // the same way every other sample test reaches its own fixtures.
    val cxt = Startup.mkTestBootCxt(
        "wfBoot", "wfBootTest", mapOf("KDR_LOAD_SAMPLE" to "true"), additionalComponents = listOf(SampleComponent()),
    )
    val service = WorkflowService.get(cxt)

    "the boot built the registries with no problems" {
        service.issues.shouldBeEmpty()
        // Nothing global declares a creation workflow; each sample client declares its own.
        service.forClient(null).creation.shouldBeNull()
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
    }

    "globex sees the name-only creation workflow with literal labels" {
        val globex = service.forClient(SC.globex).creation.shouldNotBeNull()
        globex.def.tasks.single().requiredTraitIds shouldBe listOf(GT.name)
        globex.def.tasks.single().label shouldBe "Name the form"
        globex.def.showTaskList shouldBe false
    }

    "the definition schema and the two workflow cfacts are published" {
        val schema = SchemaService.get(cxt)
        schema.storeFor(null).types shouldContainKey "${WFD.namespace}.${WFD.defType}"
        schema.cfactsFor(null).names shouldContainAll listOf(WFC.taskComplete, WFC.taskAvailable)
    }
})
