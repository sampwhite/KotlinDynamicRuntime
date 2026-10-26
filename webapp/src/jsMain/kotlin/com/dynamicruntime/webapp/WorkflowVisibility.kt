package com.dynamicruntime.webapp

import com.dynamicruntime.common.gedra.CLD
import com.dynamicruntime.common.gedra.IMP
import com.dynamicruntime.common.gedra.isSandboxClient
import com.dynamicruntime.common.gedra.workflow.WFD
import com.dynamicruntime.common.gedra.workflow.WFP
import com.dynamicruntime.common.gedra.workflow.WfParamsFrom
import com.dynamicruntime.common.gedra.workflow.WfVisibility
import com.dynamicruntime.common.gedra.workflow.WfVisibilityMode
import com.dynamicruntime.common.user.userLabelChoices

/**
 * Who sees a workflow, on the client page (issue #1384; `WorkflowVisibilityEditor`, and the choice a new workflow is
 * made with in `WorkflowSlotEditor`): the visibility in words, where it comes from, the way back to the fallback, and
 * the save. The visibility and how it resolves are the kernel's (`WfVisibility`, `WfParamsLayers`), so the page says
 * what the backend runs.
 *
 * Everything here is pure, and covered under `jsNodeTest`.
 */

/** The two choices, in the editor's words. */
fun visibilityModeLabel(mode: WfVisibilityMode): String = when (mode) {
    WfVisibilityMode.everyone -> "Everyone"
    WfVisibilityMode.restricted -> "Administrators, and people with a label"
}

/** [labels] joined as a choice: "pilot", "pilot or east", "pilot, east, or west". */
private fun orList(labels: List<String>): String = when (labels.size) {
    1 -> labels[0]
    2 -> "${labels[0]} or ${labels[1]}"
    else -> labels.dropLast(1).joinToString(", ") + ", or " + labels.last()
}

/** Who [visibility] lets see a workflow, in a phrase: "Everyone", "Administrators only", or with the labels. */
fun visibilityText(visibility: WfVisibility): String = when {
    !visibility.restricted -> "Everyone"
    visibility.labels.isEmpty() -> "Administrators only"
    else -> "Administrators, and anyone labeled ${orList(visibility.labels)}"
}

/**
 * What the editor says under the choice, so a restricted workflow's audience is never a surprise: an administrator
 * always sees it, a label list left empty means administrators only and never everybody, and an everyone keeps the
 * labels for when it is restricted again.
 */
fun visibilityExplained(visibility: WfVisibility): String = when {
    !visibility.restricted && visibility.labels.isNotEmpty() ->
        "Everybody who uses this client sees it. The labels are kept, so restricting it again brings the same people back."
    !visibility.restricted -> "Everybody who uses this client sees it."
    visibility.labels.isEmpty() ->
        "Only the client's administrators see it. Add a label to let the people holding it see it too."
    else -> "The client's administrators see it, and anyone holding one of these labels. Labels are put on people on the Users page."
}

/** The labels the editor offers: the client's suggestions, then any the visibility names that is not among them. */
fun visibilityLabelChoices(suggestions: List<String>, visibility: WfVisibility): List<String> =
    userLabelChoices(suggestions, visibility.labels)

/**
 * A visibility save's request (issue #1384): [visibility] as the scope's own, or, for null, unset so it follows the
 * fallback again; and an acknowledgement. Only the visibility is sent: a workflow's windows are left as they are.
 */
fun visibilitySaveRequest(clientId: String, workflowId: String, visibility: WfVisibility?, acknowledge: Boolean): Map<String, Any?> =
    buildMap {
        put(CLD.client, clientId)
        put(WFD.workflowId, workflowId)
        if (visibility == null) put(WFP.unset, listOf(WFP.visibility)) else put(WFP.params, mapOf(WFP.visibility to visibility.toInfo()))
        if (acknowledge) put(IMP.acknowledgeImpact, true)
    }

/**
 * Where the visibility in force comes from, as the editor says it under its title: set here, with when and by whom
 * ([settingByline]); its client's, on a sandbox following it; or nobody's, for a workflow nobody restricted.
 */
fun visibilitySourceText(params: WorkflowParamsView, zone: String): String = when (params.visibilityFrom) {
    WfParamsFrom.parent ->
        "Its client's choice, which this sandbox follows until it makes its own." +
            (params.parent?.let { settingByline(it, zone) }?.let { " $it." } ?: "")
    WfParamsFrom.source -> "The source code's default, which this client follows until it makes its own."
    null -> if (params.ownVisibility) params.own?.let { settingByline(it, zone) }?.let { "$it." } ?: "Set here."
    else "Nobody has restricted it, so everybody sees it."
}

/**
 * The editor's way back to the fallback, labeled by where the visibility would then come from: offered only while the
 * scope makes its own choice and a fallback would make one. Null when there is no way back to offer -- a client's own
 * choice set back to everyone is the same as none.
 */
fun visibilityFallbackLabel(params: WorkflowParamsView): String? = if (!params.ownVisibility) null else when (params.visibilityFallbackFrom) {
    WfParamsFrom.parent -> "Use its client's choice"
    WfParamsFrom.source -> "Use the source code's default"
    null -> null
}

/** How a visibility save takes effect, said beside Save: at once, in the client or sandbox [clientId] names. */
fun visibilityEffectText(clientId: String): String =
    if (isSandboxClient(clientId)) {
        "Saving takes effect at once in this sandbox, and never reaches its client: a publish carries no visibility."
    } else {
        "Saving takes effect at once for this client's people, with no draft and no publish."
    }

/**
 * What the slot editor says of a new workflow's visibility, beside the choice (issue #1384): made as a [draft], the
 * choice is the sandbox's, and the client's people see it only once its visibility is changed on the client's page.
 */
fun newWorkflowVisibilityNote(draft: Boolean): String =
    if (draft) {
        "This is the sandbox's choice. Once the workflow is published, only the client's administrators see it until " +
            "its visibility is changed on the client's page: a publish never makes a workflow visible."
    } else {
        "Change it later with Visibility… on the workflow's row: widening a pilot, or opening it to everyone."
    }
