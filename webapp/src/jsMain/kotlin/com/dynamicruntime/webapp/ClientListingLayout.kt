package com.dynamicruntime.webapp

import com.dynamicruntime.common.gedra.WorkflowLayout
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.launch
import react.FC
import react.Props
import react.dom.html.ReactHTML.div
import react.dom.html.ReactHTML.p
import react.dom.html.ReactHTML.span
import react.useEffect
import react.useRef
import react.useState
import web.cssom.ClassName

private val layoutScope = MainScope()

external interface ClientListingLayoutProps : Props {
    var clientId: String
    /** Whether a save is a draft, as the page last read the client ([savesAsDraft]): said in the note, and sent with it. */
    var savesDraft: Boolean
    /** What a save calls once it has taken effect: the page re-reads. */
    var onChanged: () -> Unit
}

/**
 * How a client's forms listing draws each row's workflows, on its page (issue #1599): **Workflows in the forms list**, a
 * choice of the layouts that saves at once, with where the setting is stored. Offered for every client, a client
 * defined in code included, since a stored listing setting stands over the code's. Read from the listing columns'
 * catalog (`/clientAdmin/client/usages`), which says what the client runs, on mount and on every refresh generation.
 */
val ClientListingLayout = FC<ClientListingLayoutProps> { props ->
    val generation = useRefreshGeneration()
    var catalog by useState<UsageCatalogView?>(null)
    var loadError by useState<DisplayError?>(null)
    var saving by useState(false)
    var saveError by useState<DisplayError?>(null)
    var note by useState<String?>(null)
    // Which client a read was started for: an answer for a client the page has moved on from lands nowhere.
    val latest = useRef(0)

    useEffect(props.clientId) {
        latest.current = (latest.current ?: 0) + 1
        catalog = null
        note = null
    }
    useEffect(props.clientId, generation) {
        val token = latest.current
        layoutScope.launch {
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
        loadError?.let { errorText("Couldn't load the workflow layout.", it) }
        return@FC
    }

    fun setLayout(layout: WorkflowLayout) {
        val token = latest.current
        saving = true
        saveError = null
        note = null
        layoutScope.launch {
            try {
                val (configName, mode) = ClientsApi.setUsageLayout(usageLayoutRequest(props.clientId, layout, expectedMode(props.savesDraft)))
                if (latest.current != token) return@launch
                note = savedNote("Set the workflows to “${workflowLayoutLabel(layout)}” in $configName.", mode)
                props.onChanged()
            } catch (e: Throwable) {
                if (latest.current == token) saveError = saveFailure(e, props.onChanged)
            } finally {
                if (latest.current == token) saving = false
            }
        }
    }
    div {
        className = ClassName("row")
        span { +"Workflows in the forms list:" }
        Select {
            value = shown.workflowLayout.name
            options = WorkflowLayout.entries.map { l ->
                val o: dynamic = js("({})")
                o.label = workflowLayoutLabel(l)
                o.value = l.name
                o
            }.toTypedArray()
            disabled = saving
            style = js("({ minWidth: 260 })")
            onChange = { v -> WorkflowLayout.entries.firstOrNull { it.name == v }?.let { setLayout(it) } }
        }
        span {
            className = ClassName("type-hint")
            +(shown.layoutConfigName?.let { "Stored in $it." } ?: "As its code says, or the default.")
        }
    }
    saveError?.let { errorText("Couldn't set the workflow layout.", it) }
    note?.let {
        p {
            className = ClassName("type-hint")
            +it
        }
    }
}
