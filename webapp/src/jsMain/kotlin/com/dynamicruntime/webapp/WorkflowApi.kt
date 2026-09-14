package com.dynamicruntime.webapp

import com.dynamicruntime.common.endpoint.EP
import com.dynamicruntime.common.gedra.GDF
import com.dynamicruntime.common.gedra.GEP
import com.dynamicruntime.common.util.toJsonMapOrEmpty

/**
 * The workflow endpoints the create and survey-edit pages use (issue #536, #659), on the caller's own client.
 * Both are gedra-section endpoints whose handlers read `cxt.client`, so the **shared** path answers for the
 * caller's own client and the page needs no client id.
 */
object WorkflowApi {
    /**
     * The client's creation workflow, resolved and self-contained ([WorkflowView]), or **null** when the client
     * has none (`found=false`) — the page's signal to fall back to the trait picker.
     */
    suspend fun fetchCreationView(): WorkflowView? =
        parseWorkflowView(Http.getApi(GEP.workflowView)[EP.results].toJsonMapOrEmpty())

    /**
     * The client's **survey** resolved against the form [gedraId] (issue #659), each task seeded from the form's
     * current entries; **null** when the client has no survey (`found=false`). The `gedraId` query arg is what
     * makes the endpoint resolve the survey rather than the creation workflow.
     */
    suspend fun fetchSurveyView(gedraId: String): WorkflowView? =
        parseWorkflowView(
            Http.getApi(GEP.workflowView + queryString(mapOf(GDF.gedraId to gedraId)))[EP.results].toJsonMapOrEmpty(),
        )

    /** Posts a task's collected entries; the outcome is either a refusal naming what is missing, or the form. */
    suspend fun save(body: Map<String, Any?>): WorkflowSaveOutcome =
        parseSaveOutcome(Http.sendApi("POST", GEP.workflowSave, body)[EP.results].toJsonMapOrEmpty())
}
