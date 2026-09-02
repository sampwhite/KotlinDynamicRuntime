package com.dynamicruntime.sample.gedra

import com.dynamicruntime.common.endpoint.clientPath
import com.dynamicruntime.common.gedra.GDF
import com.dynamicruntime.common.gedra.GEP
import com.dynamicruntime.common.gedra.workflow.WFD
import com.dynamicruntime.common.gedra.workflow.WFC
import com.dynamicruntime.common.gedra.workflow.WVF
import com.dynamicruntime.common.user.TestUser
import com.dynamicruntime.common.util.toJsonListOfMaps
import com.dynamicruntime.common.util.toOptStr
import com.dynamicruntime.kdn.Startup
import com.dynamicruntime.sample.SampleComponent
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain

/**
 * The resolved workflow view over real HTTP (issue #534): each client's creation workflow, fetched from its
 * own `/gedra/<client>/workflow/view`, resolves through the actual pipeline -- labels through the backend
 * fragment pass, traits into that client's schema. The two sample clients resolve **differently**, which is
 * the whole point of a per-client view, and a caller whose client has no creation workflow gets `found=false`.
 */
class WorkflowViewTest : StringSpec({
    val cxt = Startup.mkTestBootCxt(
        "wfView", "wfViewTest", mapOf("KDR_LOAD_SAMPLE" to "true"), additionalComponents = listOf(SampleComponent()),
    )
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
        traits.single()[WFD.traitId] shouldBe "name"
        traits.single()[WFD.required] shouldBe true
        (traits.single()[WVF.schemaRef] as String) shouldContain "NameData"
        val save = task[WFD.saves].toJsonListOfMaps().single()
        save[WFD.label] shouldBe "Create form"
        save[WFD.kind] shouldBe "create"
        // Nothing entered yet: available (placeholder), not complete.
        task[WVF.facts] as List<*> shouldBe listOf(WFC.taskAvailable)
    }

    "acme resolves its richer workflow, labels pulled from the backend fragment file, traits in layout order" {
        val v = creationView(acme, SC.acme)
        v[WVF.found] shouldBe true
        val task = v[WFD.tasks].toJsonListOfMaps().single()
        // Pulled from acmeWf.md (%{@t("acmeWf.identify.label")}) rather than a literal.
        task[WFD.label] shouldBe "Describe the expense"
        task[WFD.saves].toJsonListOfMaps().single()[WFD.label] shouldBe "Create the report"
        // The layout ordered the optional trait first; both carry a ref and their required flag.
        val traits = task[WFD.traits].toJsonListOfMaps()
        traits.map { it[WFD.traitId] } shouldBe listOf(ST.questionnaire, ST.expenseReport)
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

    "an unknown workflow id is a 404" {
        globex.expectError(
            com.dynamicruntime.common.exception.EXC.notFound,
            clientPath(GEP.workflowView, SC.globex),
            mapOf(GDF.workflowId to "nope"),
        )
    }
})
