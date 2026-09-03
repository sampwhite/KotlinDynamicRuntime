package com.dynamicruntime.webapp

import com.dynamicruntime.common.endpoint.EP
import com.dynamicruntime.common.gedra.GEP
import com.dynamicruntime.common.util.toJsonMapOrEmpty

/**
 * The workflow endpoints the create page uses (issue #536), on the caller's own client. Both are gedra-section
 * endpoints whose handlers read `cxt.client`, so the **shared** path answers for the caller's own client and
 * the page needs no client id.
 */
object WorkflowApi {
    /**
     * The client's creation workflow, resolved and self-contained ([WorkflowCreation]), or **null** when the
     * client has none (`found=false`) — the page's signal to fall back to the trait picker.
     */
    suspend fun fetchCreationView(): WorkflowCreation? =
        parseWorkflowView(Http.getApi(GEP.workflowView)[EP.results].toJsonMapOrEmpty())

    /** Posts a task's collected entries; the outcome is either a refusal naming what is missing, or the form. */
    suspend fun save(body: Map<String, Any?>): WorkflowSaveOutcome =
        parseSaveOutcome(Http.sendApi("POST", GEP.workflowSave, body)[EP.results].toJsonMapOrEmpty())
}
