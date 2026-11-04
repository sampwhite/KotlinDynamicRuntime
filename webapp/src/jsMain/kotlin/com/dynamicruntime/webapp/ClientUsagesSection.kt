package com.dynamicruntime.webapp

import com.dynamicruntime.common.gedra.UsageKind
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.launch
import react.FC
import react.Key
import react.Props
import react.create
import react.dom.html.ReactHTML.code
import react.dom.html.ReactHTML.div
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

private val usagesScope = MainScope()

external interface ClientUsagesSectionProps : Props {
    var clientId: String
    /** Whether a save is a draft, as the page last read the client ([savesAsDraft]): said beside Save, and sent with it. */
    var savesDraft: Boolean
    /** What a save calls once it has taken effect: the page re-reads. */
    var onChanged: () -> Unit
}

/** What the editor is open on: a new column. A column being changed is open on its key ([UsageColumnView.key]). */
private const val newColumn = "new"

/**
 * A client's listing columns, on its page (issue #1491): a table of the columns its forms listing shows -- each with
 * its heading, its trait, what it shows, and how it reads -- with **Edit…** and **Remove** on each the client stores,
 * or why it cannot be changed here; and **Add column** beneath. Read from `/clientAdmin/client/usages` on mount and on
 * every refresh generation.
 *
 * The editor ([UsageColumnEditor]) opens in a row of its own beneath the column it changes, and beneath the table for
 * a new one. A save takes effect as the page's other editors' do; the backend decides where the column is stored.
 */
val ClientUsagesSection = FC<ClientUsagesSectionProps> { props ->
    val generation = useRefreshGeneration()
    var catalog by useState<UsageCatalogView?>(null)
    var loadError by useState<DisplayError?>(null)
    var open by useState<String?>(null)
    var note by useState<String?>(null)
    var confirming by useState<String?>(null)
    var removing by useState<String?>(null)
    var removeError by useState<DisplayError?>(null)
    // Which client a read was started for: an answer for a client the page has moved on from lands nowhere.
    val latest = useRef(0)

    useEffect(props.clientId) {
        latest.current = (latest.current ?: 0) + 1
        catalog = null
        open = null
        note = null
    }
    useEffect(props.clientId, generation) {
        val token = latest.current
        usagesScope.launch {
            try {
                val read = ClientsApi.usageCatalog(props.clientId)
                if (latest.current == token) {
                    catalog = read
                    loadError = null
                }
            } catch (e: Throwable) {
                if (latest.current == token) loadError = userFacingError(e)
            }
        }
    }

    val shown = catalog
    if (shown == null) {
        loadError?.let { errorText("Couldn't load the listing columns.", it) }
            ?: p {
                className = ClassName("subtitle")
                +"Loading…"
            }
        return@FC
    }
    loadError?.let { errorText("Couldn't refresh the listing columns; showing what was loaded.", it) }
    p {
        className = ClassName("subtitle")
        +usageSectionNote(shown)
    }

    fun remove(column: UsageColumnView) {
        val token = latest.current
        removing = column.key
        removeError = null
        note = null
        usagesScope.launch {
            try {
                val (configName, mode) = ClientsApi.removeUsage(usageRemoveRequest(props.clientId, column, expectedMode(props.savesDraft)))
                if (latest.current != token) return@launch
                note = savedNote("Removed column “${column.usage.label}” from $configName.", mode)
                props.onChanged()
            } catch (e: Throwable) {
                if (latest.current == token) removeError = saveFailure(e, props.onChanged)
            } finally {
                if (latest.current == token) removing = null
            }
        }
    }
    removeError?.let { errorText("Couldn't remove the column.", it) }
    note?.let {
        p {
            className = ClassName("type-hint")
            +it
        }
    }

    fun editor(column: UsageColumnView?) = UsageColumnEditor.create {
        key = (column?.key ?: newColumn).unsafeCast<Key>()
        this.clientId = props.clientId
        this.catalog = shown
        this.column = column
        this.savesDraft = props.savesDraft
        onSaved = { said ->
            open = null
            note = said
            props.onChanged()
        }
        onCancel = { open = null }
        onChanged = props.onChanged
    }

    val editingOpen = open != null && open != newColumn
    div {
        className = ClassName(if (editingOpen) "op-table-scroll detail-open" else "op-table-scroll")
        table {
            className = ClassName("op-table")
            thead {
                tr {
                    th { +"Heading" }
                    th { +"Trait" }
                    th { +"Shows" }
                    th { +"Reads as" }
                    th { +"" }
                }
            }
            tbody {
                shown.columns.forEach { column ->
                    val usage = column.usage
                    tr {
                        key = column.key.unsafeCast<Key>()
                        td {
                            +usage.label
                            usage.columnId?.let { id ->
                                div {
                                    className = ClassName("type-hint")
                                    code { +id }
                                }
                            }
                        }
                        td { code { +usage.traitId } }
                        td { +usageShowsText(usage, shown.trait(usage.traitId)) }
                        td {
                            +usageKindText(column.readsAs)
                            if (usage.substring && column.readsAs == UsageKind.string) {
                                div {
                                    className = ClassName("type-hint")
                                    +"or part of it"
                                }
                            }
                        }
                        td {
                            span {
                                className = ClassName("row-actions")
                                if (usageEditable(column)) {
                                    Button {
                                        type = "link"
                                        size = "small"
                                        disabled = open != null
                                        onClick = { open = column.key; note = null }
                                        +"Edit…"
                                    }
                                    val blocked = usageRemoveBlocked(shown)
                                    if (blocked != null) {
                                        span {
                                            className = ClassName("type-hint")
                                            title = blocked
                                            +"The only column"
                                        }
                                    } else {
                                        Popconfirm {
                                            title = usageRemoveQuestion(column, props.clientId, props.savesDraft)
                                            description = usageRemoveConsequence(column, shown, props.savesDraft)
                                            okText = "Remove"
                                            cancelText = "Cancel"
                                            placement = "bottomLeft"
                                            this.open = confirming == column.key
                                            onOpenChange = { opened -> confirming = if (opened) column.key else null }
                                            onConfirm = {
                                                confirming = null
                                                remove(column)
                                            }
                                            Button {
                                                type = "link"
                                                size = "small"
                                                danger = true
                                                this.loading = removing == column.key
                                                disabled = open != null || removing != null
                                                +"Remove"
                                            }
                                        }
                                    }
                                } else {
                                    span {
                                        className = ClassName("type-hint")
                                        title = column.notEditable
                                        +"Not edited here"
                                    }
                                }
                            }
                            if (column.notRunning) {
                                div {
                                    className = ClassName("type-hint")
                                    +"Configured, but not run: the client's issues say why."
                                }
                            }
                        }
                    }
                    if (open == column.key) {
                        tr {
                            key = "${column.key}-editor".unsafeCast<Key>()
                            td {
                                asDynamic()["colSpan"] = 5
                                +editor(column)
                            }
                        }
                    }
                }
            }
        }
    }
    if (shown.columns.isEmpty()) {
        p {
            className = ClassName("subtitle")
            +"This client's listing shows no columns besides each form's name and dates."
        }
    }
    // A column being changed that is gone from under the editor -- removed by another page -- is said here.
    if (editingOpen && shown.columns.none { it.key == open }) {
        p {
            className = ClassName("type-hint")
            +"That column is gone."
            Button {
                type = "link"
                size = "small"
                onClick = { open = null }
                +"Close"
            }
        }
    }
    if (open == newColumn) {
        +editor(null)
    } else {
        p {
            Button {
                size = "small"
                disabled = open != null || shown.traits.isEmpty()
                onClick = { open = newColumn; note = null }
                +"Add column"
            }
            if (shown.traits.isEmpty()) {
                span {
                    className = ClassName("type-hint")
                    +" The client supports no form trait for a column to read."
                }
            }
        }
    }
}

external interface UsageColumnEditorProps : Props {
    var clientId: String
    var catalog: UsageCatalogView
    /** The column being changed, as the catalog has it; null for a new one. */
    var column: UsageColumnView?
    var savesDraft: Boolean
    var onSaved: (String) -> Unit
    var onCancel: () -> Unit
    var onChanged: () -> Unit
}

/**
 * One listing column, simple first (issue #1491): the trait it reads, its heading, and the field it shows -- which
 * writes `${field}` as its value, and decides how it reads -- with **Search by part of the text** for a text column.
 * **More options** ([MoreOptions]) take a hand-written value, how such a value reads, and a presentation; they open
 * while the column uses any. A column's trait, and its machine name, are fixed once saved. What is wrong with the draft is listed
 * ([usageDraftProblems]), and Save waits for it.
 */
val UsageColumnEditor = FC<UsageColumnEditorProps> { props ->
    val catalog = props.catalog
    val column = props.column
    var draft by useState(column?.let(::usageDraftOf) ?: newUsageDraft(catalog))
    var busy by useState(false)
    var saveError by useState<DisplayError?>(null)
    val trait = catalog.trait(draft.traitId)
    val field = usageField(draft.display, trait)
    val readsAs = usageDraftReadsAs(draft, trait)
    val problems = usageDraftProblems(draft, catalog, column)
    val changed = usageDraftChanged(draft, column)

    fun save() {
        busy = true
        saveError = null
        usagesScope.launch {
            try {
                val request = usageSaveRequest(props.clientId, draft, trait, column?.usage?.label, expectedMode(props.savesDraft))
                val result = ClientsApi.saveUsage(request)
                props.onSaved(savedNote("Saved column “${result.usage.label}” to ${result.configName}.", result.mode))
            } catch (e: Throwable) {
                saveError = saveFailure(e, props.onChanged)
            } finally {
                busy = false
            }
        }
    }

    div {
        className = ClassName("trait-designer")
        h4 { +(if (column == null) "New column" else "Column “${column.usage.label}”") }
        choiceRow("Trait") {
            if (column != null) {
                code { +column.usage.traitId }
                span {
                    className = ClassName("type-hint")
                    +" A column's trait is fixed once saved."
                }
            } else {
                Select {
                    value = draft.traitId
                    options = choiceOptions(
                        catalog.traits.map { t -> (t.description?.let { "${t.traitId} — $it" } ?: t.traitId) to t.traitId },
                    )
                    disabled = busy
                    style = js("({ minWidth: 320 })")
                    onChange = { v -> draft = draft.withTrait(v.toString()) }
                }
            }
        }
        choiceRow("Heading") {
            Input {
                value = draft.label
                placeholder = "The column's heading"
                disabled = busy
                style = js("({ maxWidth: 320 })")
                onChange = { e -> draft = draft.copy(label = e.target.value as String) }
            }
        }
        choiceRow("Field") {
            Select {
                value = field?.field
                placeholder = if (draft.display.isBlank()) "The field it shows" else "Written by hand, under More options"
                options = choiceOptions(
                    trait?.fields.orEmpty().map { "${it.label} (${usageKindText(it.readsAs).lowercase()})" to it.field },
                )
                disabled = busy || trait == null
                style = js("({ minWidth: 320 })")
                onChange = { v -> trait?.field(v.toString())?.let { draft = draft.withField(it) } }
            }
        }
        if (draft.display.isNotBlank()) {
            p {
                className = ClassName("type-hint")
                +usageReadsAsText(readsAs, field?.hasChoices == true && readsAs == UsageKind.string)
            }
        }
        if (readsAs == UsageKind.string && draft.display.isNotBlank()) {
            div {
                className = ClassName("row")
                Checkbox {
                    checked = draft.substring
                    disabled = busy
                    onChange = { e -> draft = draft.copy(substring = e.target.checked == true) }
                    +"Search by part of the text"
                }
            }
        }
        column?.usage?.columnId?.let { id ->
            p {
                className = ClassName("type-hint")
                +"Its machine name, "
                code { +id }
                +", names its search parameters and its sort, and stays as it is when the heading changes."
            }
        }
        MoreOptions {
            recordKey = column?.key ?: newColumn
            inForce = usageOptionsInForce(draft, trait)
            choiceRow("Value") {
                Input {
                    value = draft.display
                    placeholder = $$"${field}"
                    disabled = busy
                    style = js("({ maxWidth: 420, fontFamily: 'monospace' })")
                    onChange = { e -> draft = draft.copy(display = e.target.value as String) }
                }
            }
            p {
                className = ClassName("type-hint")
                +($$"Use this when the column shows more than one field, or words beside one: ${first} ${last}. " +
                    "A value written by hand is searched as text, unless you say how it reads.")
            }
            if (field == null) {
                choiceRow("Reads as") {
                    Select {
                        value = draft.kind?.name ?: UsageKind.string.name
                        options = choiceOptions(UsageKind.entries.map { usageKindText(it) to it.name })
                        disabled = busy
                        style = js("({ minWidth: 200 })")
                        onChange = { v ->
                            val kind = UsageKind.entries.firstOrNull { it.name == v.toString() }
                            draft = draft.copy(kind = kind.takeIf { it != UsageKind.string })
                        }
                    }
                }
            }
            choiceRow("Presentation") {
                Input {
                    value = draft.presentation
                    placeholder = $$"${value} min"
                    disabled = busy
                    style = js("({ maxWidth: 420, fontFamily: 'monospace' })")
                    onChange = { e -> draft = draft.copy(presentation = e.target.value as String) }
                }
            }
            p {
                className = ClassName("type-hint")
                +($$"Use this when a cell should read differently from what is searched: ${value} min, or a currency. " +
                    "The column still searches and sorts by its value.")
            }
        }
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
                onClick = { save() }
                +(if (column == null) "Add the column" else "Save")
            }
            Button {
                type = "link"
                disabled = busy
                onClick = { props.onCancel() }
                +"Cancel"
            }
        }
        saveError?.let { errorText("Couldn't save the column.", it) }
    }
}
