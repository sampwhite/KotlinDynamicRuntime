package com.dynamicruntime.sample.gedra

import com.dynamicruntime.common.gedra.traitDataTypeName
import com.dynamicruntime.common.startup.SchemaService
import com.dynamicruntime.kdn.Startup
import com.dynamicruntime.sample.SampleComponent
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe

/**
 * The sample's free-text fields are long text (issue #1591): the schema marks them `g-longText`, so every form draws
 * them as a box of several lines, while a one-line field beside them stays one line.
 */
class SampleLongTextTest : StringSpec({
    val cxt = Startup.mkTestBootCxt(
        "sampleLongText", "sampleLongTextTest", mapOf("KDR_LOAD_SAMPLE" to "true"), additionalComponents = listOf(SampleComponent()),
    )

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
