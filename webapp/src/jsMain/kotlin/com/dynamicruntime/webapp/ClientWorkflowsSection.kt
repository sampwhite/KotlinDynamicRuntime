package com.dynamicruntime.webapp

import com.dynamicruntime.common.gedra.workflow.WfDef
import com.dynamicruntime.common.gedra.workflow.WfEntry
import com.dynamicruntime.common.gedra.workflow.WfTemplateSlots
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
import react.dom.html.ReactHTML.h3
import react.dom.html.ReactHTML.h4
import react.dom.html.ReactHTML.li
import react.dom.html.ReactHTML.p
import react.dom.html.ReactHTML.span
import react.dom.html.ReactHTML.table
import react.dom.html.ReactHTML.tbody
import react.dom.html.ReactHTML.td
import react.dom.html.ReactHTML.th
import react.dom.html.ReactHTML.thead
import react.dom.html.ReactHTML.tr
import react.dom.html.ReactHTML.ul
import react.useEffect
import react.useRef
import react.useState
import web.cssom.ClassName
import kotlin.time.Clock

private val workflowsScope = MainScope()

external interface ClientWorkflowsSectionProps : Props {
    var clientId: String
    /** Whether a save is a draft, as the page last read the client ([savesAsDraft]): said beside Save, and sent with it. */
    var savesDraft: Boolean
    /** Whether the impact report's forms can be opened from this session ([impactFormsOpenable]). */
    var formsOpenable: Boolean
    /**
     * Whether the client's workflows may be made, edited and removed here: its definition is in stored configuration
     * that this page edits. False for a client declared in source code, whose workflows are shown with their time
     * windows alone -- parameters, not configuration, so set here whoever declared the workflow (issue #1386).
     */
    var configEditable: Boolean
    /**
     * The client copy's note for a new workflow's maker (`clients` file, `workflows.newWorkflowNote`, issue #1384): how a
     * workflow made restricted is opened to people. Empty as shipped; a deployment flavor says it.
     */
    var newWorkflowNote: String?
    /** What a save calls once it has taken effect: the page re-reads. */
    var onChanged: () -> Unit
}

/** What the windows editor is open on: the workflow, after this prefix (issue #1121). */
private const val windowsPrefix = "windows:"

private fun windowsKey(workflowId: String) = windowsPrefix + workflowId

/** What the visibility editor is open on: the workflow, after this prefix (issue #1384). */
private const val visibilityPrefix = "visibility:"

private fun visibilityKey(workflowId: String) = visibilityPrefix + workflowId

/** What the slot editor is open on: a new workflow from a template, or a workflow made from one. */
private fun openKey(templateId: String?, workflowId: String?) = workflowId?.let { "edit:$it" } ?: "new:$templateId"

/**
 * A client's workflows, on its page (issue #1120): the client's own -- each made from a template with **Edit…**, or
 * why its slots cannot be changed here -- and the deployment's templates, each with **Use…**, or greyed with why a
 * workflow cannot be made from it now (a client has one creation and one survey; a plan limits workflows and their
 * tasks). Read from `/clientAdmin/client/workflows` on mount and on every refresh generation.
 *
 * The slot editor ([WorkflowSlotEditor]) chooses only what a template leaves to the client -- the workflow's name, and
 * per data-entry task its label and traits -- and explains the rest in words ([workflowFixedParts]). A save takes
 * effect as the page's other editors' do; what is stored is the workflow's overlay of the template, which the backend
 * reads over the template, so a fix to the template reaches the workflow.
 *
 * Every workflow has **Visibility…** (issue #1384) -- who sees it -- and each normal one **Time windows…**: its
 * parameters, set here whoever declared it, each opening its editor in a row of its own beneath the workflow's. For a
 * client whose configuration is not edited here ([ClientWorkflowsSectionProps.configEditable] false) -- one declared
 * in source code -- only the workflows are listed, with those two.
 */
val ClientWorkflowsSection = FC<ClientWorkflowsSectionProps> { props ->
    val generation = useRefreshGeneration()
    var workflowCatalog by useState<WorkflowCatalogView?>(null)
    var loadError by useState<DisplayError?>(null)
    var open by useState<String?>(null)
    var note by useState<String?>(null)
    // A removal (issue #1346): the workflow whose confirmation is open, the one being removed, and a refusal's report.
    var confirming by useState<String?>(null)
    var removing by useState<String?>(null)
    // What a refused removal was of, with its report, so Remove anyway removes that workflow.
    var removeImpact by useState<Pair<String, ImpactReportView>?>(null)
    var removeError by useState<DisplayError?>(null)
    // Which client a read was started for: an answer for a client the page has moved on from lands nowhere.
    val latest = useRef(0)

    useEffect(props.clientId) {
        latest.current = (latest.current ?: 0) + 1
        workflowCatalog = null
        open = null
        note = null
    }
    useEffect(props.clientId, generation) {
        val token = latest.current
        workflowsScope.launch {
            try {
                val read = ClientsApi.workflowCatalog(props.clientId)
                if (latest.current == token) {
                    workflowCatalog = read
                    loadError = null
                }
            } catch (e: Throwable) {
                if (latest.current == token) loadError = userFacingError(e)
            }
        }
    }

    p {
        className = ClassName("subtitle")
        +(if (props.configEditable) {
            "The workflows this client's people use, made from the deployment's templates. A template fixes a workflow's " +
                "steps and rules; the client chooses its name, and for each step that asks for information, what it is " +
                "called and which traits it collects."
        } else {
            "The workflows this client's people use. They are not changed here, but who sees each one, and its time " +
                "windows, are: they are its parameters, not its definition."
        })
    }
    val shown = workflowCatalog
    if (shown == null) {
        loadError?.let { errorText("Couldn't load the workflows.", it) }
            ?: p {
                className = ClassName("subtitle")
                +"Loading…"
            }
        return@FC
    }
    loadError?.let { errorText("Couldn't refresh the workflows; showing what was loaded.", it) }

    fun remove(workflowId: String, acknowledge: Boolean) {
        val token = latest.current
        removing = workflowId
        removeError = null
        note = null
        workflowsScope.launch {
            try {
                val result = ClientsApi.removeWorkflow(workflowRemoveRequest(props.clientId, workflowId, expectedMode(props.savesDraft), acknowledge))
                if (latest.current != token) return@launch
                removeImpact = null
                val said = savedNote("Removed workflow ${result.workflowId} from ${result.configName}.", result.mode)
                note = listOfNotNull(said, draftImpactText(result.draftImpact)).joinToString(" ")
                props.onChanged()
            } catch (e: Throwable) {
                if (latest.current != token) return@launch
                val report = impactOf(e)
                if (report != null) removeImpact = workflowId to report else removeError = saveFailure(e, props.onChanged)
            } finally {
                if (latest.current == token) removing = null
            }
        }
    }
    removeError?.let { errorText("Couldn't remove the workflow.", it) }
    removeImpact?.let { (workflowId, report) ->
        Modal {
            this.open = true
            title = "Some stored forms take part in this workflow"
            onCancel = { removeImpact = null }
            footer = Fragment.create {
                Button {
                    onClick = { removeImpact = null }
                    +"Cancel"
                }
                Button {
                    type = "primary"
                    danger = true
                    this.loading = removing != null
                    onClick = { remove(workflowId, acknowledge = true) }
                    +"Remove anyway"
                }
            }
            impactReportBody(report, props.formsOpenable, doing = "Removing this workflow")
        }
    }
    // The client's zone, which its workflows' windows are shown and entered in; and the moment they are judged at.
    val zone = entryZone(shown.timeZone ?: DisplayZone.current)
    val now = Clock.System.now()
    note?.let {
        p {
            className = ClassName("type-hint")
            +it
        }
    }

    // The workflow whose time windows are open: drawn in a row of its own beneath that workflow's (issue #1386), so
    // the editor appears where it was asked for.
    val windowsOf = open?.takeIf { it.startsWith(windowsPrefix) }?.removePrefix(windowsPrefix)
    val visibilityOf = open?.takeIf { it.startsWith(visibilityPrefix) }?.removePrefix(visibilityPrefix)
    val rowEditorOpen = windowsOf != null || visibilityOf != null

    if (shown.workflows.isEmpty()) {
        p {
            className = ClassName("subtitle")
            +"This client has no workflows of its own yet."
        }
    } else {
        div {
            className = ClassName(if (rowEditorOpen) "op-table-scroll detail-open" else "op-table-scroll")
            table {
                className = ClassName("op-table")
                thead {
                    tr {
                        th { +"Workflow" }
                        th { +"Kind" }
                        th { +"Made from" }
                        th { +"Steps" }
                        th { +"Now" }
                        th { +"Seen by" }
                        th { +"" }
                    }
                }
                tbody {
                    shown.workflows.forEach { w ->
                        tr {
                            key = w.def.workflowId.unsafeCast<Key>()
                            td {
                                +w.name
                                div {
                                    className = ClassName("type-hint")
                                    code { +w.def.workflowId }
                                }
                            }
                            td { +workflowEntryText(w.def.entry) }
                            td { +(w.def.fromTemplate?.let { shown.template(it)?.let { t -> workflowName(t.def) } ?: it } ?: "—") }
                            td { +w.taskLabels.joinToString(", ") }
                            // Where a normal workflow stands in its time windows now (issue #1121); the others have none.
                            td { +(if (w.def.entry == WfEntry.normal) windowPhaseText(w.windows, now, zone) else "—") }
                            // Who sees it (issue #1384): a restricted one stands out, since its people may not.
                            td {
                                if (w.visibility.restricted) className = ClassName("wf-restricted")
                                +visibilityText(w.visibility)
                            }
                            td {
                                span {
                                    className = ClassName("row-actions")
                                    if (!props.configEditable) {
                                        // Only the windows below: the workflow itself is changed where it is declared.
                                    } else if (w.notEditable == null) {
                                        Button {
                                            type = "link"
                                            size = "small"
                                            disabled = open != null
                                            onClick = { open = openKey(w.def.fromTemplate, w.def.workflowId); note = null }
                                            +"Edit…"
                                        }
                                    } else {
                                        span {
                                            className = ClassName("type-hint")
                                            title = w.notEditable
                                            +"Not from a template"
                                        }
                                    }
                                    if (props.configEditable && workflowRemovable(w)) {
                                        Popconfirm {
                                            title = workflowRemoveQuestion(w.def, props.clientId, props.savesDraft)
                                            description = workflowRemoveConsequence(props.savesDraft)
                                            okText = "Remove"
                                            cancelText = "Cancel"
                                            placement = "bottomLeft"
                                            this.open = confirming == w.def.workflowId
                                            onOpenChange = { opened -> confirming = if (opened) w.def.workflowId else null }
                                            onConfirm = {
                                                confirming = null
                                                remove(w.def.workflowId, acknowledge = false)
                                            }
                                            Button {
                                                type = "link"
                                                size = "small"
                                                danger = true
                                                this.loading = removing == w.def.workflowId
                                                disabled = open != null || removing != null
                                                +"Remove"
                                            }
                                        }
                                    }
                                    Button {
                                        type = "link"
                                        size = "small"
                                        disabled = open != null
                                        onClick = { open = visibilityKey(w.def.workflowId); note = null }
                                        +"Visibility…"
                                    }
                                    if (w.windowsOffered) {
                                        Button {
                                            type = "link"
                                            size = "small"
                                            disabled = open != null
                                            onClick = { open = windowsKey(w.def.workflowId); note = null }
                                            +"Time windows…"
                                        }
                                    }
                                }
                            }
                        }
                        if (windowsOf == w.def.workflowId && w.windowsOffered) {
                            tr {
                                key = "${w.def.workflowId}-windows".unsafeCast<Key>()
                                td {
                                    asDynamic()["colSpan"] = 7
                                    WorkflowWindowsEditor {
                                        this.key = "windows-$windowsOf".unsafeCast<Key>()
                                        this.clientId = props.clientId
                                        this.workflow = w.def
                                        this.params = w.params ?: WorkflowParamsView.none
                                        this.zone = zone
                                        formsOpenable = props.formsOpenable
                                        onSaved = { said ->
                                            open = null
                                            note = said
                                            props.onChanged()
                                        }
                                        onCancel = { open = null }
                                        onChanged = props.onChanged
                                    }
                                }
                            }
                        }
                        if (visibilityOf == w.def.workflowId) {
                            tr {
                                key = "${w.def.workflowId}-visibility".unsafeCast<Key>()
                                td {
                                    asDynamic()["colSpan"] = 7
                                    WorkflowVisibilityEditor {
                                        this.key = "visibility-$visibilityOf".unsafeCast<Key>()
                                        this.clientId = props.clientId
                                        this.workflow = w.def
                                        this.params = w.params ?: WorkflowParamsView.none
                                        labelSuggestions = shown.userLabels
                                        this.zone = zone
                                        formsOpenable = props.formsOpenable
                                        onSaved = { said ->
                                            open = null
                                            note = said
                                            props.onChanged()
                                        }
                                        onCancel = { open = null }
                                        onChanged = props.onChanged
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    if (props.configEditable) h3 { +"Make a workflow from a template" }
    if (props.configEditable) div {
        className = ClassName("op-table-scroll")
        table {
            className = ClassName("op-table")
            thead {
                tr {
                    th { +"Template" }
                    th { +"Kind" }
                    th { +"Steps" }
                    th { +"" }
                }
            }
            tbody {
                shown.templates.forEach { t ->
                    tr {
                        key = t.def.workflowId.unsafeCast<Key>()
                        td {
                            +workflowName(t.def)
                            t.def.comment?.let {
                                div {
                                    className = ClassName("type-hint")
                                    +it
                                }
                            }
                        }
                        td { +workflowEntryText(t.def.entry) }
                        td { +t.def.tasks.joinToString(", ") { it.label } }
                        td {
                            val refusal = t.refusal
                            if (refusal == null) {
                                Button {
                                    size = "small"
                                    disabled = open != null
                                    onClick = { open = openKey(t.def.workflowId, null); note = null }
                                    +"Use…"
                                }
                            } else {
                                span {
                                    className = ClassName("type-hint")
                                    +refusal
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    // The editor is drawn in its workflow's row above; a workflow gone from under it -- removed by another page -- is
    // said here, with a way to close it.
    val rowGone = (windowsOf != null && shown.workflows.none { it.def.workflowId == windowsOf && it.windowsOffered }) ||
        (visibilityOf != null && shown.workflows.none { it.def.workflowId == visibilityOf })
    if (rowGone) {
        p {
            className = ClassName("type-hint")
            +"That workflow is gone."
            Button {
                type = "link"
                size = "small"
                onClick = { open = null }
                +"Close"
            }
        }
    }
    open?.takeIf { !rowEditorOpen }?.let { key ->
        val editing = key.removePrefix("edit:").takeIf { key.startsWith("edit:") }
        val workflow = editing?.let { id -> shown.workflows.firstOrNull { it.def.workflowId == id }?.def }
        val templateId = workflow?.fromTemplate ?: key.removePrefix("new:")
        val template = shown.template(templateId)?.def
        if (template != null && (editing == null || workflow != null)) {
            WorkflowSlotEditor {
                // Keyed by what it opened on, so a draft for one never carries into another.
                this.key = key.unsafeCast<Key>()
                this.clientId = props.clientId
                this.workflowCatalog = shown
                this.template = template
                this.workflow = workflow
                this.savesDraft = props.savesDraft
                formsOpenable = props.formsOpenable
                newWorkflowNote = props.newWorkflowNote
                onSaved = { said ->
                    open = null
                    note = said
                    props.onChanged()
                }
                onCancel = { open = null }
                onChanged = props.onChanged
            }
        } else {
            // What it opened on is gone from the catalog -- the workflow, or its template -- so there is nothing to edit.
            p {
                className = ClassName("type-hint")
                +(if (template == null) "Its template, '$templateId', is not offered by this deployment now." else "That workflow is gone.")
                Button {
                    type = "link"
                    size = "small"
                    onClick = { open = null }
                    +"Close"
                }
            }
        }
    }
}

external interface WorkflowSlotEditorProps : Props {
    var clientId: String
    var workflowCatalog: WorkflowCatalogView
    /** The template the workflow is, or will be, made from. */
    var template: WfDef
    /** The workflow whose slots are changed, as it runs; null for a new one. */
    var workflow: WfDef?
    var savesDraft: Boolean
    var formsOpenable: Boolean
    /** The client copy's note for a new workflow's maker; see [ClientWorkflowsSectionProps.newWorkflowNote]. */
    var newWorkflowNote: String?
    var onSaved: (String) -> Unit
    var onCancel: () -> Unit
    var onChanged: () -> Unit
}

/**
 * The slots of one workflow made from a template (issue #1120): its name, and per data-entry task its label and the
 * traits it collects -- each required or not. What the template fixes is said beneath, in words, from the workflow the
 * slots make ([workflowPreview], the kernel's). Save waits until the slots are whole ([workflowSlotProblems]); a live
 * change that affects stored forms is refused with the impact report, whose **Save anyway** acknowledges it.
 *
 * A new workflow is made with **who sees it** (issue #1384), administrators only to start, so nobody meets it before
 * its maker chose; the backend writes that before the workflow exists. Changing it later is the row's **Visibility…**.
 */
val WorkflowSlotEditor = FC<WorkflowSlotEditorProps> { props ->
    val base = props.workflow ?: props.template
    val baseline = props.workflow?.let { workflowSlots(it) }
    var draft by useState(baseline ?: newWorkflowSlots(props.template))
    var visibility by useState(WfVisibility.adminsOnly)
    var busy by useState(false)
    var saveError by useState<DisplayError?>(null)
    var impact by useState<ImpactReportView?>(null)
    // What a save refused over its impact was sending, so Save anyway resends that and not the draft.
    var pending by useState<WfTemplateSlots?>(null)
    val problems = workflowSlotProblems(props.template, draft)
    val changed = workflowSlotsChanged(draft, baseline)
    val preview = workflowPreview(base, draft)

    fun save(slots: WfTemplateSlots, acknowledge: Boolean) {
        pending = slots
        busy = true
        saveError = null
        workflowsScope.launch {
            try {
                val request = workflowSaveRequest(props.clientId, slots, expectedMode(props.savesDraft), acknowledge, visibility)
                val result = ClientsApi.saveWorkflow(request)
                impact = null
                val said = savedNote("Saved workflow ${result.slots.workflowId} to ${result.configName}.", result.mode)
                props.onSaved(listOfNotNull(said, draftImpactText(result.draftImpact)).joinToString(" "))
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
            if (props.workflow == null) {
                +"New workflow from "
                code { +props.template.workflowId }
            } else {
                +"Workflow "
                code { +base.workflowId }
                +", made from "
                code { +props.template.workflowId }
            }
        }
        choiceRow("Name") {
            Input {
                value = draft.label
                placeholder = "What the workflow is called"
                disabled = busy
                style = js("({ maxWidth: 360 })")
                onChange = { e -> draft = draft.withLabel(e.target.value as String) }
            }
        }
        draft.tasks.forEach { task ->
            div {
                key = task.taskId.unsafeCast<Key>()
                className = ClassName("trait-field")
                choiceRow("Step") {
                    Input {
                        value = task.label
                        placeholder = "What the step is called"
                        disabled = busy
                        style = js("({ maxWidth: 320 })")
                        onChange = { e -> draft = draft.withTaskLabel(task.taskId, e.target.value as String) }
                    }
                }
                choiceRow("Collects") {
                    Select {
                        mode = "multiple"
                        value = task.traits.map { it.traitId }.toTypedArray()
                        options = choiceOptions(traitChoices(props.workflowCatalog, task))
                        disabled = busy
                        style = js("({ minWidth: 320 })")
                        onChange = { v -> draft = draft.withTaskTraits(task.taskId, (v as Array<*>).mapNotNull { it?.toString() }) }
                    }
                }
                if (task.traits.isNotEmpty()) {
                    div {
                        className = ClassName("type-hint")
                        +"Required to finish the step: "
                        task.traits.forEach { trait ->
                            span {
                                key = trait.traitId.unsafeCast<Key>()
                                Checkbox {
                                    checked = trait.required
                                    disabled = busy
                                    onChange = { e -> draft = draft.withTraitRequired(task.taskId, trait.traitId, e.target.checked == true) }
                                    +trait.traitId
                                }
                            }
                        }
                    }
                }
            }
        }
        if (props.workflow == null) {
            choiceRow("Seen by") {
                Select {
                    value = visibility.mode.name
                    options = choiceOptions(WfVisibilityMode.entries.map { visibilityModeLabel(it) to it.name })
                    disabled = busy
                    style = js("({ minWidth: 320 })")
                    onChange = { v -> WfVisibilityMode.entries.firstOrNull { it.name == v.toString() }?.let { visibility = visibility.withMode(it) } }
                }
            }
            visibilityLabelsRow(visibility, props.workflowCatalog.userLabels, busy) { visibility = it }
            p {
                className = ClassName("type-hint")
                +"${visibilityExplained(visibility)} ${newWorkflowVisibilityNote(props.savesDraft)}"
            }
            props.newWorkflowNote?.takeIf { it.isNotBlank() }?.let { text ->
                div {
                    className = ClassName("type-hint")
                    Markdown { source = text }
                }
            }
        }
        fixedParts(preview ?: base)
        if (problems.isNotEmpty()) {
            ul {
                className = ClassName("wf-reasons")
                problems.forEachIndexed { i, text ->
                    li {
                        key = i.toString().unsafeCast<Key>()
                        +text
                    }
                }
            }
        }
        p {
            className = ClassName("subtitle")
            +saveEffectText(props.savesDraft)
        }
        p {
            Button {
                type = "primary"
                this.loading = busy
                disabled = !changed || problems.isNotEmpty()
                onClick = { save(draft, acknowledge = false) }
                +(if (props.workflow == null) "Make the workflow" else "Save")
            }
            Button {
                type = "link"
                disabled = busy
                onClick = { props.onCancel() }
                +"Cancel"
            }
        }
        saveError?.let { errorText("Couldn't save the workflow.", it) }
    }
    Modal {
        open = impact != null
        title = "Some stored forms hold data this change affects"
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
                onClick = { save(pending ?: draft, acknowledge = true) }
                +"Save anyway"
            }
        }
        impact?.let { impactReportBody(it, props.formsOpenable, doing = "Changing this workflow") }
    }
}

/** What the template fixes, in words, under the slots it leaves to the client. */
private fun ChildrenBuilder.fixedParts(def: WfDef) {
    h4 { +"What the template fixes" }
    ul {
        className = ClassName("wf-reasons")
        workflowFixedParts(def).forEachIndexed { i, text ->
            li {
                key = i.toString().unsafeCast<Key>()
                +text
            }
        }
    }
}
