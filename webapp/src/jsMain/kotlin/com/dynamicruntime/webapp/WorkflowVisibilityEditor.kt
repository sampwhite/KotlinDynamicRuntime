package com.dynamicruntime.webapp

import com.dynamicruntime.common.gedra.workflow.WFP
import com.dynamicruntime.common.gedra.workflow.WfDef
import com.dynamicruntime.common.gedra.workflow.WfVisibility
import com.dynamicruntime.common.gedra.workflow.WfVisibilityMode
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.launch
import react.ChildrenBuilder
import react.FC
import react.Fragment
import react.Key
import react.Props
import react.create
import react.dom.html.ReactHTML.code
import react.dom.html.ReactHTML.div
import react.dom.html.ReactHTML.h4
import react.dom.html.ReactHTML.li
import react.dom.html.ReactHTML.p
import react.dom.html.ReactHTML.ul
import react.useState
import web.cssom.ClassName

private val visibilityScope = MainScope()

external interface WorkflowVisibilityEditorProps : Props {
    var clientId: String
    /** The workflow whose visibility is set, as it runs. */
    var workflow: WfDef
    /** Its parameters as the page's client or sandbox runs them, with their earlier settings. */
    var params: WorkflowParamsView
    /** The user labels the client suggests. */
    var labelSuggestions: List<String>
    /** The client's time zone, which a setting's time is shown in. */
    var zone: String
    var formsOpenable: Boolean
    var onSaved: (String) -> Unit
    var onCancel: () -> Unit
    var onChanged: () -> Unit
}

/**
 * Who sees a workflow (issue #1384): **Everyone**, or its client's administrators and the people holding one of the
 * labels chosen -- the user labels an administrator puts on people on the Users page. A controlled rollout is a
 * sequence of ordinary saves here: administrators only, then a pilot's label, then more labels, then everyone.
 *
 * **Visibility is a parameter**, as the windows are (`WorkflowWindowsEditor`): a save takes effect at once in the page's
 * client or sandbox, with no draft and no publish, on a workflow of any kind, whoever declared it. Where the choice in
 * force comes from is said under the title ([visibilitySourceText]), with a way back to the fallback while the scope
 * makes its own ([visibilityFallbackLabel]). Restricting a workflow forms are engaged with, out of their owners' sight,
 * is refused with the impact report, whose **Save anyway** acknowledges it. The earlier choices expand beneath Save,
 * each with **Use these values**, which fills the editor.
 */
val WorkflowVisibilityEditor = FC<WorkflowVisibilityEditorProps> { props ->
    val baseline = props.params.visibility
    var draft by useState(baseline)
    var historyOpen by useState(false)
    var busy by useState(false)
    var saveError by useState<DisplayError?>(null)
    var impact by useState<ImpactReportView?>(null)
    // What a save refused over its impact was sending, so Save anyway resends that: a choice, or (null) the way back.
    var pending by useState<WfVisibility?>(null)
    var pendingUnset by useState(false)
    val zone = entryZone(props.zone)
    val changed = draft != baseline

    // Null [visibility] unsets it, so it follows the fallback again.
    fun save(visibility: WfVisibility?, acknowledge: Boolean) {
        pending = visibility
        pendingUnset = visibility == null
        busy = true
        saveError = null
        visibilityScope.launch {
            try {
                ClientsApi.saveWorkflowParams(visibilitySaveRequest(props.clientId, props.workflow.workflowId, visibility, acknowledge))
                impact = null
                props.onSaved(
                    if (visibility == null) "Who sees ${props.workflow.workflowId} follows the fallback again, at once."
                    else "${props.workflow.workflowId} is now seen by: ${visibilityText(visibility)}. In force at once.",
                )
            } catch (e: Throwable) {
                val report = impactOf(e)
                if (report != null) impact = report else saveError = saveFailure(e, props.onChanged)
            } finally {
                busy = false
            }
        }
    }

    div {
        className = ClassName("trait-designer")
        h4 {
            +"Who sees "
            code { +props.workflow.workflowId }
        }
        p {
            className = ClassName("type-hint")
            +visibilitySourceText(props.params, zone)
        }
        choiceRow("Seen by") {
            Select {
                value = draft.mode.name
                options = choiceOptions(WfVisibilityMode.entries.map { visibilityModeLabel(it) to it.name })
                disabled = busy
                style = js("({ minWidth: 320 })")
                onChange = { v -> WfVisibilityMode.entries.firstOrNull { it.name == v.toString() }?.let { draft = draft.withMode(it) } }
            }
        }
        visibilityLabelsRow(draft, props.labelSuggestions, busy) { draft = it }
        p {
            className = ClassName("type-hint")
            +visibilityExplained(draft)
        }
        p {
            className = ClassName("subtitle")
            +visibilityEffectText(props.clientId)
        }
        p {
            Button {
                type = "primary"
                this.loading = busy
                disabled = !changed
                onClick = { save(draft, acknowledge = false) }
                +"Save"
            }
            visibilityFallbackLabel(props.params)?.let { label ->
                Button {
                    disabled = busy
                    onClick = { save(null, acknowledge = false) }
                    +label
                }
            }
            Button {
                type = "link"
                disabled = busy
                onClick = { props.onCancel() }
                +"Cancel"
            }
        }
        saveError?.let { errorText("Couldn't save who sees the workflow.", it) }
        val history = props.params.historyOf(WFP.visibility)
        if (history.isNotEmpty()) {
            Button {
                type = "link"
                size = "small"
                onClick = { historyOpen = !historyOpen }
                +(if (historyOpen) "Hide earlier choices" else "Earlier choices (${history.size})")
            }
            if (historyOpen) {
                ul {
                    className = ClassName("wf-history")
                    history.forEachIndexed { i, setting ->
                        li {
                            key = i.toString().unsafeCast<Key>()
                            val earlier = setting.params.visibility
                            div { +(earlier?.let { visibilityText(it) } ?: "Not set here: followed the fallback") }
                            div {
                                className = ClassName("type-hint")
                                +(settingByline(setting, zone) ?: "As first set")
                                if (earlier != null) {
                                    +" "
                                    Button {
                                        type = "link"
                                        size = "small"
                                        disabled = busy
                                        // A pre-fill, never a save: the person reviews it and saves it as any change.
                                        onClick = { draft = earlier }
                                        +"Use these values"
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
    Modal {
        open = impact != null
        title = "Some stored forms would be out of their owners' sight"
        onCancel = { impact = null }
        footer = Fragment.create {
            Button {
                onClick = { impact = null }
                +"Cancel"
            }
            Button {
                type = "primary"
                danger = true
                this.loading = busy
                onClick = { save(if (pendingUnset) null else pending ?: draft, acknowledge = true) }
                +"Save anyway"
            }
        }
        impact?.let { impactReportBody(it, props.formsOpenable, doing = "Changing who sees this workflow") }
    }
}

/**
 * The labels whose holders see a restricted workflow: a tags select offering the client's suggestions and taking any
 * other typed, as the Users page's labels do. Shown for either choice, so labels chosen before opening a workflow to
 * everyone stay in view, and in force when it is restricted again.
 */
internal fun ChildrenBuilder.visibilityLabelsRow(
    visibility: WfVisibility,
    suggestions: List<String>,
    busy: Boolean,
    onChange: (WfVisibility) -> Unit,
) {
    choiceRow("Labels") {
        Select {
            mode = "tags"
            value = visibility.labels.toTypedArray()
            options = choiceOptions(visibilityLabelChoices(suggestions, visibility).map { it to it })
            placeholder = "(none: administrators only)"
            disabled = busy
            style = js("({ minWidth: 320 })")
            this.onChange = { v -> onChange(visibility.withLabels(v.unsafeCast<Array<String>>().toList())) }
        }
    }
}
