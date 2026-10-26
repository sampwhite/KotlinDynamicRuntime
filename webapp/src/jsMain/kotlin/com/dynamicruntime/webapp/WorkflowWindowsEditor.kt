package com.dynamicruntime.webapp

import com.dynamicruntime.common.gedra.workflow.WFP
import com.dynamicruntime.common.gedra.workflow.WfDef
import com.dynamicruntime.common.gedra.workflow.WfWindows
import com.dynamicruntime.common.util.formatDate
import com.dynamicruntime.common.util.parseDateOrNull
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
import react.dom.html.ReactHTML.span
import react.dom.html.ReactHTML.ul
import react.useState
import web.cssom.ClassName
import kotlin.time.Clock
import kotlin.time.Instant

private val windowsScope = MainScope()

external interface WorkflowWindowsEditorProps : Props {
    var clientId: String
    /** The normal workflow whose windows are set, as it runs. */
    var workflow: WfDef
    /** Its parameters as the page's client or sandbox runs them (issue #1356), with their earlier settings. */
    var params: WorkflowParamsView
    /** The client's time zone, which every bound is entered and shown in. */
    var zone: String
    var formsOpenable: Boolean
    var onSaved: (String) -> Unit
    var onCancel: () -> Unit
    var onChanged: () -> Unit
}

/**
 * A normal workflow's time windows (issue #1121): **Available** (its lifetime), **Tracked** (relevancy) and **New
 * forms may join** (engagement), each a start and an end entered as a date and a time in the client's zone. A missing
 * bound says what it falls back to ([boundFallbackText]). Beneath them, a timeline of the three bands
 * ([timelineLayout]), where the workflow stands now ([windowPhaseText]), and where it would stand at a moment picked
 * by **Try a date** -- the kernel's `phaseAt`, in the browser, with no clock moved and nothing saved.
 *
 * **Windows are parameters** (issue #1356): a save takes effect at once in the page's client or sandbox, with no draft
 * and no publish ([windowsEffectText]), on any normal workflow, whoever declared it. One that would, now, freeze or hide
 * forms engaged with the workflow is refused with the impact report, whose **Save anyway** acknowledges it. Where the
 * windows in force come from is said under the title ([windowsSourceText]): set here by someone, its client's on a
 * sandbox, or the source code's defaults (issue #1386). While the page's scope sets its own, a way back to the fallback
 * is offered beside Save ([windowsFallbackLabel]) -- a save that unsets them, judged and recorded like any other. The
 * earlier settings expand in place beneath Save, each with **Use these values**, which fills the editor -- the person
 * still saves it, as any change.
 */
val WorkflowWindowsEditor = FC<WorkflowWindowsEditorProps> { props ->
    val baseline = props.params.windows
    var draft by useState(baseline)
    var historyOpen by useState(false)
    var asOf by useState<Instant?>(null)
    var busy by useState(false)
    var saveError by useState<DisplayError?>(null)
    var impact by useState<ImpactReportView?>(null)
    // What a save refused over its impact was sending, so Save anyway resends that and not the draft: windows of its
    // own, or (with `pendingUnset`) a return to the fallback.
    var pending by useState<WfWindows?>(null)
    var pendingUnset by useState(false)
    val zone = entryZone(props.zone)
    val now = Clock.System.now()
    val problem = draft.problem(props.workflow.workflowId)
    val changed = draft != baseline

    // Null [windows] unsets them, so they follow the fallback again.
    fun save(windows: WfWindows?, acknowledge: Boolean) {
        pending = windows
        pendingUnset = windows == null
        busy = true
        saveError = null
        windowsScope.launch {
            try {
                ClientsApi.saveWorkflowParams(windowsSaveRequest(props.clientId, props.workflow.workflowId, windows, acknowledge))
                impact = null
                props.onSaved(
                    if (windows == null) "The time windows of ${props.workflow.workflowId} follow the fallback again; they are in force now."
                    else "Saved the time windows of ${props.workflow.workflowId}; they are in force now.",
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
            +"Time windows of "
            code { +props.workflow.workflowId }
        }
        p {
            className = ClassName("subtitle")
            +("When the workflow is open, each a start and an end in ${entryZoneNote(zone)}. A window with no start has " +
                "been open all along, one with no end stays open; a narrower window left empty follows the one around it.")
        }
        windowsSourceText(props.params, props.clientId, zone)?.let {
            p {
                className = ClassName("type-hint")
                +it
            }
        }
        WindowBand.entries.forEach { band ->
            div {
                key = band.name.unsafeCast<Key>()
                className = ClassName("wf-window")
                choiceRow(band.label) {
                    boundPicker(draft, band, isStart = true, zone, busy) { draft = it }
                    span {
                        className = ClassName("type-hint")
                        +" until "
                    }
                    boundPicker(draft, band, isStart = false, zone, busy) { draft = it }
                }
                div {
                    className = ClassName("type-hint")
                    +band.explained
                }
            }
        }
        problem?.let {
            ul {
                className = ClassName("wf-reasons")
                li { +it }
            }
        }
        if (problem == null) {
            timeline(draft, now, asOf)
            p {
                +"Now: "
                span {
                    className = ClassName("wf-phase")
                    +windowPhaseText(draft, now, zone)
                }
            }
            choiceRow("Try a date") {
                DatePicker {
                    value = zonedPickerValue(asOf?.formatDate(), zone)
                    showTime = true
                    showNow = false
                    asDynamic()["placeholder"] = "Pick a date and time"
                    onChange = { date, _ -> asOf = zonedPickerIso(date, zone)?.parseDateOrNull() }
                }
            }
            asOf?.let { at ->
                p {
                    +"On ${formatTimestamp(at.formatDate(), zone)}: "
                    span {
                        className = ClassName("wf-phase")
                        +windowPhaseText(draft, at, zone, now)
                    }
                }
            }
        }
        p {
            className = ClassName("subtitle")
            +windowsEffectText(props.clientId)
        }
        p {
            Button {
                type = "primary"
                this.loading = busy
                disabled = !changed || problem != null
                onClick = { save(draft, acknowledge = false) }
                +"Save"
            }
            windowsFallbackLabel(props.params)?.let { label ->
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
        saveError?.let { errorText("Couldn't save the time windows.", it) }
        val history = props.params.historyOf(WFP.windows)
        if (history.isNotEmpty()) {
            Button {
                type = "link"
                size = "small"
                onClick = { historyOpen = !historyOpen }
                +(if (historyOpen) "Hide earlier settings" else "Earlier settings (${history.size})")
            }
            if (historyOpen) {
                ul {
                    className = ClassName("wf-history")
                    history.forEachIndexed { i, setting ->
                        li {
                            key = i.toString().unsafeCast<Key>()
                            val earlier = setting.params.windows
                            div { +(earlier?.let { windowsSummaryText(it, zone) } ?: "Not set here: followed the fallback") }
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
                                        onClick = { draft = earlier; asOf = null }
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
        title = "Some stored forms would be affected now"
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
        impact?.let { impactReportBody(it, props.formsOpenable, doing = "Changing these windows") }
    }
}

/**
 * One bound's picker: a date and a time in [zone], cleared to unset -- a day alone is not a moment, so the picker takes
 * a time with it. Empty, it says what the bound falls back to.
 */
private fun ChildrenBuilder.boundPicker(
    windows: WfWindows,
    band: WindowBand,
    isStart: Boolean,
    zone: String,
    busy: Boolean,
    onChange: (WfWindows) -> Unit,
) {
    val declared = band.declared(windows).let { if (isStart) it.start else it.end }
    DatePicker {
        value = zonedPickerValue(declared?.formatDate(), zone)
        showTime = true
        showNow = false
        asDynamic()["disabled"] = busy
        asDynamic()["placeholder"] = boundFallbackText(windows, band, isStart, zone)
        style = js("({ minWidth: 260 })")
        this.onChange = { date, _ -> onChange(windows.withBound(band, isStart, zonedPickerIso(date, zone)?.parseDateOrNull())) }
    }
}

/** The three bands on one track, with now and the date being tried marked ([timelineLayout]). */
private fun ChildrenBuilder.timeline(windows: WfWindows, now: Instant, asOf: Instant?) {
    val layout = timelineLayout(windows, now, asOf)
    div {
        className = ClassName("wf-timeline")
        layout.bands.forEach { b ->
            div {
                key = b.band.name.unsafeCast<Key>()
                className = ClassName("wf-timeline-row")
                span {
                    className = ClassName("wf-timeline-label")
                    +b.band.label
                }
                div {
                    className = ClassName("wf-timeline-track")
                    div {
                        val ends = listOfNotNull("open-start".takeIf { b.openStart }, "open-end".takeIf { b.openEnd })
                        className = ClassName((listOf("wf-timeline-band", "wf-band-${b.band.name}") + ends).joinToString(" "))
                        val place: dynamic = js("({})")
                        place.left = "${b.left}%"
                        place.width = "${maxOf(b.right - b.left, 0.5)}%"
                        style = place
                    }
                    marker("wf-timeline-now", layout.nowAt, "Now")
                    layout.asOfAt?.let { marker("wf-timeline-asof", it, "The date being tried") }
                }
            }
        }
    }
}

private fun ChildrenBuilder.marker(cls: String, at: Double, label: String) {
    div {
        className = ClassName("wf-timeline-marker $cls")
        title = label
        val place: dynamic = js("({})")
        place.left = "$at%"
        style = place
    }
}
