package com.dynamicruntime.webapp

import com.dynamicruntime.common.gedra.workflow.WFP
import com.dynamicruntime.common.gedra.workflow.WfDef
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.launch
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

private val enabledScope = MainScope()

external interface WorkflowEnabledEditorProps : Props {
    var clientId: String
    /** The normal workflow whose switch is set, as it runs. */
    var workflow: WfDef
    /** Its parameters as the page's client or sandbox runs them, with their earlier settings. */
    var params: WorkflowParamsView
    /** The client's time zone, which a setting's time is shown in. */
    var zone: String
    var formsOpenable: Boolean
    var onSaved: (String) -> Unit
    var onCancel: () -> Unit
    var onChanged: () -> Unit
}

/**
 * Whether a normal workflow is turned on (issue #1635): **On**, or **Off**, as if not configured. Turning a workflow off is
 * how a client stops using one it does not want -- one from its code or its template included -- without deleting it:
 * what its forms recorded is kept, and turning it on again brings it all back.
 *
 * **The switch is a parameter**, as the windows and visibility are (`WorkflowWindowsEditor`, `WorkflowVisibilityEditor`):
 * a save takes effect at once in the page's client or sandbox, with no draft and no publish, whoever declared the
 * workflow. Where the choice in force comes from is said under the title ([enabledSourceText]), with a way back to the
 * fallback while the scope makes its own ([enabledFallbackLabel]). Turning off a workflow forms are engaged with hides
 * them, so it is refused with the impact report, whose **Turn off anyway** acknowledges it. The earlier choices expand
 * beneath Save, each with **Use this choice**, which fills the editor.
 */
val WorkflowEnabledEditor = FC<WorkflowEnabledEditorProps> { props ->
    val baseline = props.params.enabled
    var draft by useState(baseline)
    var historyOpen by useState(false)
    var busy by useState(false)
    var saveError by useState<DisplayError?>(null)
    var impact by useState<ImpactReportView?>(null)
    // What a save refused over its impact was sending, so the acknowledgement resends that: a choice, or (null) the way back.
    var pending by useState<Boolean?>(null)
    val zone = entryZone(props.zone)
    val workflowId = props.workflow.workflowId

    // Null [on] unsets it, so it follows the fallback again.
    fun save(on: Boolean?, acknowledge: Boolean) {
        pending = on
        busy = true
        saveError = null
        enabledScope.launch {
            try {
                ClientsApi.saveWorkflowParams(enabledSaveRequest(props.clientId, workflowId, on, acknowledge))
                impact = null
                props.onSaved(enabledSavedNote(workflowId, on))
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
            +"Whether "
            code { +workflowId }
            +" is on"
        }
        p {
            className = ClassName("type-hint")
            +enabledSourceText(props.params, zone)
        }
        choiceRow("Turned") {
            Select {
                value = draft.toString()
                options = choiceOptions(listOf(true, false).map { enabledLabel(it) to it.toString() })
                disabled = busy
                style = js("({ minWidth: 320 })")
                onChange = { v -> draft = v.toString() == "true" }
            }
        }
        p {
            className = ClassName("type-hint")
            +enabledExplained(draft)
        }
        p {
            className = ClassName("subtitle")
            +enabledEffectText(props.clientId)
        }
        p {
            Button {
                type = "primary"
                this.loading = busy
                disabled = draft == baseline
                onClick = { save(draft, acknowledge = false) }
                +"Save"
            }
            enabledFallbackLabel(props.params)?.let { label ->
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
        saveError?.let { errorText("Couldn't save whether the workflow is on.", it) }
        val history = props.params.historyOf(WFP.enabled)
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
                            val earlier = setting.params.enabled
                            div { +(earlier?.let { enabledLabel(it) } ?: "Not set here: followed the fallback") }
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
                                        +"Use this choice"
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
        title = "Some stored forms take part in this workflow"
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
                onClick = { save(pending, acknowledge = true) }
                +"Turn off anyway"
            }
        }
        impact?.let { impactReportBody(it, props.formsOpenable, doing = "Turning this workflow off") }
    }
}
