package com.dynamicruntime.sample.gedra

import com.dynamicruntime.common.endpoint.EI
import com.dynamicruntime.common.endpoint.EP
import com.dynamicruntime.common.endpoint.clientPath
import com.dynamicruntime.common.gedra.GEP
import com.dynamicruntime.common.gedra.traitDataTypeName
import com.dynamicruntime.common.gedra.workflow.WVF
import com.dynamicruntime.common.schema.SCH
import com.dynamicruntime.common.schema.SL
import com.dynamicruntime.common.schema.LayoutPullHit
import com.dynamicruntime.common.startup.SchemaService
import com.dynamicruntime.common.user.TestUser
import com.dynamicruntime.common.util.toJsonListOfMaps
import com.dynamicruntime.common.util.toJsonMapOrEmpty
import com.dynamicruntime.kdn.Startup
import com.dynamicruntime.sample.SampleComponent
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain

/**
 * The `g-layout` closure delivered out-of-band on both friendly surfaces (issues #585, #587), over real HTTP:
 * the endpoint catalog (what the off-workflow forms fetch) and the workflow view. The questionnaire's layout
 * names every field (acme's overlay drops `notes`, so acme receives it **pruned**); the expense report's layout
 * carries a `hint` whose `${'$'}{min}` / `${'$'}{max}` ride as a **raw template** (frontend-resolved). On neither
 * surface does the served schema carry the keyword.
 */
class LayoutDeliveryTest : StringSpec({
    val cxt = Startup.mkTestBootCxt(
        "layoutDelivery", "layoutDeliveryTest", mapOf("KDR_LOAD_SAMPLE" to "true"), additionalComponents = listOf(SampleComponent()),
    )
    val acme = TestUser.create(cxt, "layout@acme.test", userClient = SC.acme)
    val everyone = TestUser.create(cxt, "layout@public.test")

    val questionnaire = "${ST.namespace}.${traitDataTypeName(ST.questionnaireEntry)}"
    // Carries a `hint` layout (issue #587) on its `year` field.
    val expenseReport = "${ST.namespace}.${traitDataTypeName(ST.expenseReportEntry)}"
    val allFields = listOf(ST.topic, ST.notes, ST.hasIssue, ST.explanation)
    val acmeFields = listOf(ST.topic, ST.hasIssue, ST.explanation)

    fun catalog(user: TestUser): Map<String, Any?> = user.getData("/schema/endpoints", mapOf(EP.limit to 1000))

    fun fieldNames(layouts: Any?, type: String): List<String?> =
        layouts.toJsonMapOrEmpty()[type].toJsonMapOrEmpty()[SL.schemaFields].toJsonListOfMaps().map { it[SL.field] as? String }

    fun hintOf(layouts: Any?, type: String, field: String): Any? =
        layouts.toJsonMapOrEmpty()[type].toJsonMapOrEmpty()[SL.schemaFields].toJsonListOfMaps()
            .first { it[SL.field] == field }[SL.hint]

    fun descriptionOf(layouts: Any?, type: String, field: String): Any? =
        layouts.toJsonMapOrEmpty()[type].toJsonMapOrEmpty()[SL.schemaFields].toJsonListOfMaps()
            .first { it[SL.field] == field }[SL.description]

    fun headingOf(layouts: Any?, type: String): Any? =
        layouts.toJsonMapOrEmpty()[type].toJsonMapOrEmpty()[SL.label]

    $$"the catalog carries the layouts beside a $defs that does not, the hint as a raw template" {
        val c = catalog(everyone)
        val defs = c[SCH.dDefs].toJsonMapOrEmpty()
        defs.keys shouldContain questionnaire
        defs[questionnaire].toJsonMapOrEmpty().containsKey(SCH.layout) shouldBe false
        fieldNames(c[EI.layouts], questionnaire) shouldBe allFields
        // The expense report's `hint` (issue #587) is delivered as its raw `${min}`/`${max}` template -- the
        // backend does not resolve it (frontend-resolved against the field's bounds).
        hintOf(c[EI.layouts], expenseReport, ST.year) shouldBe $$"Any year from ${min} to ${max}."
        // The questionnaire's `topic` description is a backend fragment pull (issue #605): the delivery has
        // already resolved `%{@t("questionnaire.topicHelp")}` server-side, so the caller sees finished copy and
        // never the pull token.
        val topicDesc = descriptionOf(c[EI.layouts], questionnaire, ST.topic).toString()
        topicDesc shouldContain "pulled from a shared fragment file"
        topicDesc shouldNotContain "@t("
        // The block-level heading (issue #605) is a backend fragment pull too, delivered as resolved Markdown
        // (a `## Questionnaire` header and a line under it), never the raw pull token.
        val heading = headingOf(c[EI.layouts], questionnaire).toString()
        heading shouldContain "## Questionnaire"
        heading shouldContain "Choose a topic"
        heading shouldNotContain "@t("
        // "Absent, not empty": another type in the closure -- one with no layout -- gets no entry.
        val noLayoutType = defs.keys.first { it != questionnaire && it != expenseReport }
        c[EI.layouts].toJsonMapOrEmpty().containsKey(noLayoutType) shouldBe false
    }

    "acme's catalog carries the inherited layout pruned to the properties its overlay kept" {
        val c = catalog(acme)
        fieldNames(c[EI.layouts], questionnaire) shouldBe acmeFields
        c[SCH.dDefs].toJsonMapOrEmpty()[questionnaire].toJsonMapOrEmpty().containsKey(SCH.layout) shouldBe false
    }

    "the workflow view carries the layouts for exactly the traits it collects" {
        val v = acme.getData(clientPath(GEP.workflowView, SC.acme))
        v[WVF.found] shouldBe true
        fieldNames(v[WVF.layouts], questionnaire) shouldBe acmeFields
        hintOf(v[WVF.layouts], expenseReport, ST.year) shouldBe $$"Any year from ${min} to ${max}."
        // Exactly the two collected traits that declare a layout -- no spurious entries.
        v[WVF.layouts].toJsonMapOrEmpty().keys shouldBe setOf(questionnaire, expenseReport)
        v[SCH.dDefs].toJsonMapOrEmpty()[questionnaire].toJsonMapOrEmpty().containsKey(SCH.layout) shouldBe false
    }

    "checkLayoutPulls (issue #620) finds the questionnaire's real backend pulls, and passes them when they resolve" {
        val schema = SchemaService.get(cxt)
        // A resolver that resolves nothing: every literal pull the real layouts carry is reported -- the
        // questionnaire's block heading and its topic description both pull from a backend fragment.
        val allMiss = schema.checkLayoutPulls { _, _, _ -> LayoutPullHit(fileFound = false, backend = false, keyPresent = false) }
        allMiss.any { it.contains("heading") } shouldBe true
        allMiss.any { it.contains("topic") } shouldBe true
        // A resolver that resolves everything: no problems -- what LayoutCheckService sees against the real files,
        // which is why the sample boots at all.
        schema.checkLayoutPulls { _, _, _ -> LayoutPullHit(fileFound = true, backend = true, keyPresent = true) } shouldBe emptyList()
    }
})
