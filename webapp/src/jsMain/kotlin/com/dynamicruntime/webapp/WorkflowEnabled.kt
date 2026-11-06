package com.dynamicruntime.webapp

import com.dynamicruntime.common.gedra.CLD
import com.dynamicruntime.common.gedra.IMP
import com.dynamicruntime.common.gedra.isSandboxClient
import com.dynamicruntime.common.gedra.workflow.WFD
import com.dynamicruntime.common.gedra.workflow.WFP
import com.dynamicruntime.common.gedra.workflow.WfParamsFrom
import kotlin.time.Instant

/**
 * Whether a normal workflow is turned on, on the client page (issue #1635; `WorkflowEnabledEditor`): the choice in words,
 * where it comes from, the way back to the fallback, and the save. The switch and how it resolves are the kernel's
 * (`WfParams.enabled`, `WfParamsLayers`), so the page says what the backend runs.
 *
 * Turned off, a workflow is as if not configured: its forms keep what they recorded, and nothing shows it. That is how a
 * client stops using a workflow it does not want -- its code's, or its template's, included -- rather than deleting it.
 *
 * Everything here is pure, and covered under `jsNodeTest`.
 */

/** The two choices, in the editor's words. */
fun enabledLabel(on: Boolean): String = if (on) "On" else "Off: as if not configured"

/** What the editor says under the choice, so turning a workflow off is never mistaken for deleting it. */
fun enabledExplained(on: Boolean): String =
    if (on) {
        "The workflow runs for this client, within its time windows."
    } else {
        "Nothing shows the workflow: no form joins it, and the forms that did no longer show their part in it. What they " +
            "recorded is kept, and turning it on again brings it all back."
    }

/**
 * Where the choice in force comes from, as the editor says it under its title: set here, with when and by whom
 * ([settingByline]); its client's, on a sandbox following it; the code's default; or nobody's, for a workflow nobody
 * turned off.
 */
fun enabledSourceText(params: WorkflowParamsView, zone: String): String = when (params.enabledFrom) {
    WfParamsFrom.parent ->
        "Its client's choice, which this sandbox follows until it makes its own." +
            (params.parent?.let { settingByline(it, zone) }?.let { " $it." } ?: "")
    WfParamsFrom.source -> "The code's default, which this client follows until it makes its own."
    null -> if (params.ownEnabled) params.own?.let { settingByline(it, zone) }?.let { "$it." } ?: "Set here."
    else "Nobody has turned it off, so it is on."
}

/**
 * The editor's way back to the fallback, labeled by where the choice would then come from: offered only while the scope
 * makes its own choice and a fallback would make one. Null when there is none to offer -- a client's own "on" set back
 * to nothing is the same as none.
 */
fun enabledFallbackLabel(params: WorkflowParamsView): String? = if (!params.ownEnabled) null else when (params.enabledFallbackFrom) {
    WfParamsFrom.parent -> "Use its client's choice"
    WfParamsFrom.source -> "Use the code's default"
    null -> null
}

/**
 * A switch save's request (issue #1635): [on] as the scope's own, or, for null, unset so it follows the fallback again;
 * and an acknowledgement. Only the switch is sent: the windows and who sees the workflow are left as they are.
 */
fun enabledSaveRequest(clientId: String, workflowId: String, on: Boolean?, acknowledge: Boolean): Map<String, Any?> = buildMap {
    put(CLD.client, clientId)
    put(WFD.workflowId, workflowId)
    if (on == null) put(WFP.unset, listOf(WFP.enabled)) else put(WFP.params, mapOf(WFP.enabled to on))
    if (acknowledge) put(IMP.acknowledgeImpact, true)
}

/** How a switch save takes effect, said beside Save: at once, in the client or sandbox [clientId] names. */
fun enabledEffectText(clientId: String): String =
    if (isSandboxClient(clientId)) {
        "Saving takes effect at once in this sandbox, and never reaches its client: a publish carries no parameters."
    } else {
        "Saving takes effect at once for this client's people, with no draft and no publish."
    }

/**
 * What the workflows table says of [workflow] now (issue #1635): "Off", with where that comes from when it is not the
 * scope's own -- a workflow turned off is not running, whatever its windows say -- else where its time windows put it
 * ([windowPhaseText]), and a dash for a workflow that is not normal, which has neither.
 */
fun workflowNowText(workflow: ClientWorkflowView, now: Instant, zone: String): String = when {
    !workflow.enabledOffered -> "—"
    !workflow.enabled -> when (workflow.params?.enabledFrom) {
        WfParamsFrom.parent -> "Off: its client's choice"
        WfParamsFrom.source -> "Off: the code's default"
        null -> "Off"
    }
    else -> windowPhaseText(workflow.windows, now, zone)
}

/** What a save said happened, for the page's note. Null [on] is the way back to the fallback. */
fun enabledSavedNote(workflowId: String, on: Boolean?): String = when (on) {
    null -> "Whether $workflowId is on follows the fallback again, at once."
    true -> "$workflowId is turned on. In force at once."
    false -> "$workflowId is turned off: nothing shows it, and what its forms recorded is kept. In force at once."
}
