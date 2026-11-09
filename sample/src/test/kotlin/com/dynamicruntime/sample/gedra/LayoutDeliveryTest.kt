package com.dynamicruntime.sample.gedra

import com.dynamicruntime.sample.SampleTestInstances
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
    val cxt = SampleTestInstances.default("layoutDelivery")
    val acme = TestUser.create(cxt, "layout@acme.test", userClient = SC.acme)
    val everyone = TestUser.create(cxt, "layout@public.test")

    val questionnaire = "${ST.namespace}.${traitDataTypeName(ST.questionnaireEntry)}"
    // Carries a `hint` layout (issue #587) on its `year` field.
    val expenseReport = "${ST.namespace}.${traitDataTypeName(ST.expenseReportEntry)}"
    val allFields = listOf(ST.topic, ST.notes, ST.hasIssue, ST.explanation)
    val acmeFields = listOf(ST.topic, ST.hasIssue, ST.explanation)

    fun catalog(user: TestUser): Map<String, Any?> = user.getData("/schema/endpoints", mapOf(EP.limit to 1000))

    fun fieldNames(fieldLayouts: Any?, type: String): List<String?> =
        fieldLayouts.toJsonMapOrEmpty()[type].toJsonMapOrEmpty()[SL.schemaFields].toJsonListOfMaps()
            .map { it[SL.field] as? String }

    fun hintOf(fieldLayouts: Any?, type: String, field: String): Any? =
        fieldLayouts.toJsonMapOrEmpty()[type].toJsonMapOrEmpty()[SL.schemaFields].toJsonListOfMaps()
            .first { it[SL.field] == field }[SL.hint]

    fun descriptionOf(fieldLayouts: Any?, type: String, field: String): Any? =
        fieldLayouts.toJsonMapOrEmpty()[type].toJsonMapOrEmpty()[SL.schemaFields].toJsonListOfMaps()
            .first { it[SL.field] == field }[SL.description]

    fun headingOf(fieldLayouts: Any?, type: String): Any? =
        fieldLayouts.toJsonMapOrEmpty()[type].toJsonMapOrEmpty()[SL.label]

    $$"the catalog carries the layouts beside a $defs that does not, the hint as a raw template" {
        val c = catalog(everyone)
        val defs = c[SCH.dDefs].toJsonMapOrEmpty()
        defs.keys shouldContain questionnaire
        defs[questionnaire].toJsonMapOrEmpty().containsKey(SCH.layout) shouldBe false
        fieldNames(c[EI.fieldLayouts], questionnaire) shouldBe allFields
        // The expense report's `hint` (issue #587) is delivered as its raw `${min}`/`${max}` template -- the
        // backend does not resolve it (frontend-resolved against the field's bounds).
        hintOf(c[EI.fieldLayouts], expenseReport, ST.year) shouldBe $$"Any year from ${min} to ${max}."
        // The questionnaire's `topic` description is a backend fragment pull (issue #605): the delivery has
        // already resolved `%{@t("questionnaire.topicHelp")}` server-side, so the caller sees finished copy and
        // never the pull token.
        val topicDesc = descriptionOf(c[EI.fieldLayouts], questionnaire, ST.topic).toString()
        topicDesc shouldContain "pulled from a shared fragment file"
        topicDesc shouldNotContain "@t("
        // The block's name and introduction (issues #605, #1534) are backend fragment pulls too, delivered resolved,
        // never as the raw pull token.
        headingOf(c[EI.fieldLayouts], questionnaire) shouldBe "Questionnaire"
        val intro = c[EI.fieldLayouts].toJsonMapOrEmpty()[questionnaire].toJsonMapOrEmpty()[SL.description].toString()
        intro shouldContain "Choose a topic"
        intro shouldNotContain "@t("
        // "Absent, not empty": another type in the closure -- one with no layout -- gets no entry.
        val noLayoutType = defs.keys.first { it != questionnaire && it != expenseReport }
        c[EI.fieldLayouts].toJsonMapOrEmpty().containsKey(noLayoutType) shouldBe false
    }

    "acme's catalog carries the inherited layout pruned to the properties its overlay kept" {
        val c = catalog(acme)
        fieldNames(c[EI.fieldLayouts], questionnaire) shouldBe acmeFields
        c[SCH.dDefs].toJsonMapOrEmpty()[questionnaire].toJsonMapOrEmpty().containsKey(SCH.layout) shouldBe false
    }

    "the workflow view carries the layouts for exactly the traits it collects" {
        val v = acme.getData(clientPath(GEP.workflowView, SC.acme))
        v[WVF.found] shouldBe true
        fieldNames(v[WVF.fieldLayouts], questionnaire) shouldBe acmeFields
        hintOf(v[WVF.fieldLayouts], expenseReport, ST.year) shouldBe $$"Any year from ${min} to ${max}."
        // Exactly the two collected traits that declare a layout -- no spurious entries.
        v[WVF.fieldLayouts].toJsonMapOrEmpty().keys shouldBe setOf(questionnaire, expenseReport)
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

    // Long text (issue #1591) is the schema's mark rather than a layout's: the sample's free-text fields carry it, so
    // every form draws them as a box of several lines, while a one-line field beside them stays one line.
    /** Per field of [entryTypeName]'s data type in [namespace], as [client] sees it, whether it is long text. */
    fun longTextOf(client: String?, namespace: String, entryTypeName: String, vararg fields: String): Map<String, Boolean> {
        val type = SchemaService.get(cxt).storeFor(client).types.getValue("$namespace.${traitDataTypeName(entryTypeName)}")
        return fields.associateWith { type.properties.getValue(it).valueType.longText }
    }

    "the global sample traits mark their notes and reasons long text" {
        longTextOf(null, ST.namespace, ST.questionnaireEntry, ST.topic, ST.notes, ST.explanation) shouldBe
            mapOf(ST.topic to false, ST.notes to true, ST.explanation to true)
        longTextOf(null, ST.namespace, ST.approvalEntry, ST.decidedBy, ST.rejectionReason) shouldBe
            mapOf(ST.decidedBy to false, ST.rejectionReason to true)
        longTextOf(null, ST.namespace, ST.yearlyEntry, ST.note) shouldBe mapOf(ST.note to true)
    }

    "acme's site audit and follow-up mark what was found long text" {
        longTextOf(SC.acme, SC.acmeNamespace, SC.siteAuditEntry, SC.auditor, SC.findings) shouldBe
            mapOf(SC.auditor to false, SC.findings to true)
        longTextOf(SC.acme, SC.acmeNamespace, SC.siteFollowUpEntry, SC.followUpBy, SC.followUpOutcome) shouldBe
            mapOf(SC.followUpBy to false, SC.followUpOutcome to true)
    }
})
