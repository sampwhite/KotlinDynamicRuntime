package com.dynamicruntime.sample.gedra

import com.dynamicruntime.common.endpoint.EI
import com.dynamicruntime.common.endpoint.EP
import com.dynamicruntime.common.endpoint.clientPath
import com.dynamicruntime.common.gedra.GEP
import com.dynamicruntime.common.gedra.traitDataTypeName
import com.dynamicruntime.common.gedra.workflow.WVF
import com.dynamicruntime.common.schema.SCH
import com.dynamicruntime.common.schema.SL
import com.dynamicruntime.common.user.TestUser
import com.dynamicruntime.common.util.toJsonListOfMaps
import com.dynamicruntime.common.util.toJsonMapOrEmpty
import com.dynamicruntime.kdn.Startup
import com.dynamicruntime.sample.SampleComponent
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.shouldBe

/**
 * The `g-layout` closure delivered out-of-band on both friendly surfaces (issue #585), over real HTTP: the
 * endpoint catalog (what the off-workflow forms fetch) and the workflow view. The questionnaire's layout names
 * every field; acme's overlay drops `notes`, so acme receives the layout **pruned** to what it kept while a
 * global caller receives it whole -- and on neither surface does the served schema carry the keyword.
 */
class LayoutDeliveryTest : StringSpec({
    val cxt = Startup.mkTestBootCxt(
        "layoutDelivery", "layoutDeliveryTest", mapOf("KDR_LOAD_SAMPLE" to "true"), additionalComponents = listOf(SampleComponent()),
    )
    val acme = TestUser.create(cxt, "layout@acme.test", userClient = SC.acme)
    val everyone = TestUser.create(cxt, "layout@public.test")

    val questionnaire = "${ST.namespace}.${traitDataTypeName(ST.questionnaireEntry)}"
    val allFields = listOf(ST.topic, ST.notes, ST.hasIssue, ST.explanation)
    val acmeFields = listOf(ST.topic, ST.hasIssue, ST.explanation)

    fun catalog(user: TestUser): Map<String, Any?> = user.getData("/schema/endpoints", mapOf(EP.limit to 1000))

    fun fieldNames(layouts: Any?, type: String): List<String?> =
        layouts.toJsonMapOrEmpty()[type].toJsonMapOrEmpty()[SL.schemaFields].toJsonListOfMaps().map { it[SL.field] as? String }

    "the catalog carries the questionnaire's layout beside a \$defs that does not" {
        val c = catalog(everyone)
        val defs = c[SCH.dDefs].toJsonMapOrEmpty()
        defs.keys shouldContain questionnaire
        defs[questionnaire].toJsonMapOrEmpty().containsKey(SCH.layout) shouldBe false
        fieldNames(c[EI.layouts], questionnaire) shouldBe allFields
        // A type in the closure with no layout has no entry -- the map is not one-per-type.
        c[EI.layouts].toJsonMapOrEmpty().keys.size shouldBe 1
    }

    "acme's catalog carries the inherited layout pruned to the properties its overlay kept" {
        val c = catalog(acme)
        fieldNames(c[EI.layouts], questionnaire) shouldBe acmeFields
        c[SCH.dDefs].toJsonMapOrEmpty()[questionnaire].toJsonMapOrEmpty().containsKey(SCH.layout) shouldBe false
    }

    "the workflow view carries the same closure, over exactly the types it references" {
        val v = acme.getData(clientPath(GEP.workflowView, SC.acme))
        v[WVF.found] shouldBe true
        fieldNames(v[WVF.layouts], questionnaire) shouldBe acmeFields
        // The other trait the workflow collects declares no layout: absent, not an empty block.
        v[WVF.layouts].toJsonMapOrEmpty().keys shouldBe setOf(questionnaire)
        v[SCH.dDefs].toJsonMapOrEmpty()[questionnaire].toJsonMapOrEmpty().containsKey(SCH.layout) shouldBe false
    }
})
