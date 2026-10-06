package com.dynamicruntime.webapp

import com.dynamicruntime.common.gedra.ACEP
import com.dynamicruntime.common.gedra.CFEP
import com.dynamicruntime.common.gedra.IMP
import com.dynamicruntime.common.gedra.ImpactKind
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The client page's publish impact dialog (issue #935): a report read off the wire or a refused publish, the sentences
 * the dialog shows for it, and the request "Publish anyway" sends.
 */
class ImpactReportTest {
    private val wire = mapOf(
        IMP.client to "acme", IMP.name to "traits", IMP.version to 4, IMP.scanned to 12, IMP.tooLarge to false,
        IMP.findings to listOf(
            mapOf(IMP.kind to ImpactKind.traitGone.name, IMP.traitId to "extra", IMP.count to 7, IMP.sampleIds to listOf("gd.fd.acme.a", "gd.fd.acme.b")),
            mapOf(IMP.kind to ImpactKind.stateStranded.name, IMP.workflowId to "review", IMP.taskId to "check", IMP.count to 1, IMP.sampleIds to listOf("gd.fd.acme.c")),
        ),
    )

    @Test
    fun aReportIsReadOffTheWire() {
        val report = parseImpactReport(wire)
        assertEquals("acme", report.client)
        assertEquals(4, report.version)
        assertEquals(12, report.scanned)
        assertTrue(report.blocks)
        val gone = report.findings.first()
        assertEquals(ImpactKind.traitGone.name, gone.kind)
        assertEquals(7, gone.count)
        assertEquals(listOf("gd.fd.acme.a", "gd.fd.acme.b"), gone.sampleIds)
        assertEquals("check", report.findings[1].taskId)
    }

    @Test
    fun aRefusedPublishCarriesItsReportAndNoOtherErrorDoes() {
        val refused = ApiError(
            "Publishing configuration 'traits' would affect data.", fromFragment = false, status = 400,
            errorCode = IMP.refusedCode, traceId = null, extraData = mapOf(IMP.report to wire),
        )
        assertEquals("traits", impactOf(refused)?.name)
        assertNull(impactOf(ApiError("Nope.", fromFragment = false, status = 400, errorCode = null, traceId = null)))
        assertNull(impactOf(IllegalStateException("not an API error")))
    }

    @Test
    fun eachFindingReadsAsASentenceNamingWhatHappens() {
        val report = parseImpactReport(wire)
        assertEquals(
            "7 forms hold entries of trait extra, which the client would no longer support.",
            impactFindingText(report.findings[0]),
        )
        // One form takes the singular.
        assertEquals(
            "1 form is at task check of workflow review, which the workflow would no longer define.",
            impactFindingText(report.findings[1]),
        )
        val invalid = ImpactFindingView(ImpactKind.dataInvalid.name, "memo", null, null, 2, emptyList())
        assertTrue(impactFindingText(invalid).contains("could not be edited"))
        val workflow = ImpactFindingView(ImpactKind.workflowGone.name, null, "aside", null, 3, emptyList())
        assertEquals("3 forms take part in workflow aside, which would no longer exist.", impactFindingText(workflow))
    }

    @Test
    fun theSummarySaysTooLargeNothingOrWhatFollows() {
        val empty = parseImpactReport(wire + (IMP.findings to emptyList<Any?>()))
        assertFalse(empty.blocks)
        assertEquals(
            "Publishing traits would leave none of the client's 12 stored forms broken: no trait, field, workflow or " +
                "task they rely on goes away.",
            impactSummary(empty),
        )
        val large = parseImpactReport(wire + mapOf(IMP.findings to emptyList<Any?>(), IMP.tooLarge to true))
        assertTrue(large.blocks)
        assertTrue(impactSummary(large).contains("unknown"))
        assertTrue(impactSummary(parseImpactReport(wire)).endsWith("already stores:"))
        // A caller other than publish names its own change (issue #1040).
        assertTrue(impactSummary(parseImpactReport(wire), "Removing hotel").startsWith("Removing hotel would affect forms"))
        assertTrue(impactSummary(large, "Removing hotel").contains("what removing hotel would do"))
    }

    @Test
    fun theFormsLinkToTheRawEditorWhereThisSessionCanReadThem() {
        assertEquals("#page=$pageEditForm&${HP.gedra}=gd.fd.acme.a", impactFormHref("gd.fd.acme.a"))
        // A sandbox's session is a user of the sandbox, and cannot read its parent's forms; across clients, it can.
        assertFalse(impactFormsOpenable(acrossClients = false, onSandboxPage = true))
        assertTrue(impactFormsOpenable(acrossClients = true, onSandboxPage = true))
        assertTrue(impactFormsOpenable(acrossClients = false, onSandboxPage = false))
    }

    @Test
    fun publishAnywaySendsTheAcknowledgement() {
        val anyway = publishBundleRequest("acme", "traits", acrossClients = false, acknowledgeImpact = true)
        assertEquals(CFEP.bundlePublish, anyway.path)
        assertEquals(true, anyway.body[IMP.acknowledgeImpact])
        assertFalse(publishBundleRequest("acme", "traits", acrossClients = false, acknowledgeImpact = false).body.containsKey(IMP.acknowledgeImpact))
        // Across clients, a sandbox's page publishes its parent's, by name.
        val fromSandbox = publishBundleRequest("acme:sandbox", "traits", acrossClients = true, acknowledgeImpact = true)
        assertEquals(ACEP.bundlePublish, fromSandbox.path)
        assertEquals("acme", fromSandbox.body[CFEP.client])
        assertEquals(true, fromSandbox.body[IMP.acknowledgeImpact])
    }
}
