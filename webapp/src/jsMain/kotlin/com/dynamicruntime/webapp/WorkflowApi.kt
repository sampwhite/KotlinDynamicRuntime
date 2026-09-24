package com.dynamicruntime.webapp

import com.dynamicruntime.common.endpoint.EP
import com.dynamicruntime.common.endpoint.HttpMethod
import com.dynamicruntime.common.endpoint.clientPath
import com.dynamicruntime.common.gedra.GDF
import com.dynamicruntime.common.gedra.GEP
import com.dynamicruntime.common.gedra.workflow.WFD
import com.dynamicruntime.common.util.toJsonMapOrEmpty

/**
 * The workflow endpoints the create and survey-edit pages use (issue #536, #659). All are gedra-section endpoints
 * whose handlers read `cxt.client`, so the **shared** path answers for the caller's own client -- which is what
 * a creation workflow always is. A survey is resolved against a stored form, and that form may be another
 * client's (an `allClients` admin, issue #714): then the view and its save take that client's copy of the path,
 * `clientPath`, so the survey is that client's and the save is checked under its rules. The page passes the
 * client only when the copy exists (see `clientOfResolvedPath`); null keeps the shared path.
 */
object WorkflowApi {
    /**
     * The client's creation workflow, resolved and self-contained ([WorkflowView]), or **null** when the client
     * has none (`found=false`) — the page's signal to fall back to the trait picker.
     */
    suspend fun fetchCreationView(): WorkflowView? =
        parseWorkflowView(Http.getApi(GEP.workflowView)[EP.results].toJsonMapOrEmpty())

    /**
     * The **survey** resolved against the form [gedraId] (issue #659), each task seeded from the form's current
     * entries; **null** when the client has no survey (`found=false`). The `gedraId` query arg is what makes the
     * endpoint resolve the survey rather than the creation workflow. [client] is the form's client when its copy
     * of the path exists (issue #714), else null for the caller's own.
     */
    suspend fun fetchSurveyView(gedraId: String, client: String? = null): WorkflowView? =
        parseWorkflowView(
            Http.getApi(pathFor(GEP.workflowView, client) + queryString(mapOf(GDF.gedraId to gedraId)))[EP.results]
                .toJsonMapOrEmpty(),
        )

    /**
     * A **normal workflow** [workflowId] resolved against the form [gedraId] (issue #791) -- what the forms list's
     * workflow column opens. Null when the backend has no such workflow for this caller (`found=false`); a workflow
     * the caller may not see is refused with a 404, which the page shows as its load error. [client] as for
     * [fetchSurveyView].
     */
    suspend fun fetchWorkflowView(workflowId: String, gedraId: String, client: String? = null): WorkflowView? =
        parseWorkflowView(
            Http.getApi(pathFor(GEP.workflowView, client) + queryString(mapOf(WFD.workflowId to workflowId, GDF.gedraId to gedraId)))[EP.results]
                .toJsonMapOrEmpty(),
        )

    /** Puts form [gedraId] into normal workflow [workflowId] (issue #791); refused with the reasons when it is not eligible. */
    suspend fun engage(gedraId: String, workflowId: String, client: String? = null) {
        Http.sendApi("POST", pathFor(GEP.workflowEngage, client), mapOf(GDF.gedraId to gedraId, WFD.workflowId to workflowId))
    }

    /**
     * Posts a task's collected entries; the outcome is either a refusal naming what is missing, or the form.
     * [client] as for [fetchSurveyView]: the form's client's copy of the save, so it runs under that client's rules.
     */
    suspend fun save(body: Map<String, Any?>, client: String? = null): WorkflowSaveOutcome =
        parseSaveOutcome(Http.sendApi("POST", pathFor(GEP.workflowSave, client), body)[EP.results].toJsonMapOrEmpty())

    /**
     * The workflows behind form [gedraId]'s Needs Review or Finished chip (issue #789) -- [cfact] names which.
     * Asked of the form's own client's surface, resolved by the backend (a client that varies nothing has only
     * the shared endpoint, bound to the caller's own client), for the reason the survey page resolves its path:
     * an `allClients` admin may be looking at another client's form.
     */
    suspend fun fetchSingletonWorkflows(gedraId: String, cfact: String): List<SingletonWorkflow> {
        val path = fetchFormEndpoint(HttpMethod.GET.name, GEP.formDocSingletonWorkflows, formClientOf(gedraId))
            .endpoints.firstOrNull()?.path ?: GEP.formDocSingletonWorkflows
        val results = Http.getApi(path + queryString(mapOf(GDF.gedraId to gedraId, WFD.cfact to cfact)))[EP.results]
        return parseSingletonWorkflows(results.toJsonMapOrEmpty())
    }

    /** The client's copy of a bare workflow path when a [client] is given, else the shared path. */
    private fun pathFor(barePath: String, client: String?): String =
        if (client == null) barePath else clientPath(barePath, client)
}
