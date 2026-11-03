package com.dynamicruntime.webapp

import com.dynamicruntime.common.home.HMENU
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.launch
import react.FC
import react.Key
import react.Props
import react.dom.html.ReactHTML.div
import react.dom.html.ReactHTML.h1
import react.dom.html.ReactHTML.p
import react.dom.html.ReactHTML.span
import react.dom.html.ReactHTML.table
import react.dom.html.ReactHTML.tbody
import react.dom.html.ReactHTML.td
import react.dom.html.ReactHTML.th
import react.dom.html.ReactHTML.thead
import react.dom.html.ReactHTML.tr
import react.useEffect
import react.useState
import web.cssom.ClassName
import kotlin.time.Instant

private val operatorAssistantScope = MainScope()

/**
 * The operator's page of the workflow assistant (issue #1527): whether this node can reach the model, and every client
 * with its switch, monthly budget, and this month's use -- what was spent, the calls made, the last turn, and how much
 * of the input the prompt cache served. Each client's switch and budget are set in its row, at once. Switching one on
 * asks first, showing what is sent to Anthropic, since the switch acknowledges it.
 */
val OperatorAssistantPage = FC<Props> {
    val generation = useRefreshGeneration()
    // One clock for the table, as every table reads its times.
    val now = useRelativeClock()
    var read by useState<AssistantClientsRead?>(null)
    var loadError by useState<DisplayError?>(null)
    // Bumped through its setter: two rows' changes finishing together must each count, not both count from one render.
    val (reads, setReads) = useState(0)

    useEffect(generation, reads) {
        operatorAssistantScope.launch {
            try {
                read = AssistantApi.clients()
                loadError = null
            } catch (e: Throwable) {
                loadError = userFacingError(e)
            }
        }
    }

    div {
        className = ClassName("card wide")
        backToListing(HMENU.pageOperator)
        h1 { +"Workflow assistant" }
        p {
            className = ClassName("subtitle")
            +"Which clients may use the workflow assistant, what each may spend in a month, and what each has spent this month."
        }
        val current = read
        when {
            loadError != null -> errorText("Couldn't load the assistant's clients.", loadError!!)
            current == null -> p { className = ClassName("subtitle"); +"Loading…" }
            else -> {
                val node = current.status
                p {
                    className = ClassName("op-verdict")
                    +(
                        if (node.available) {
                            "Available on this node: ${node.provider}, ${node.model}."
                        } else {
                            "Not available on this node${node.reason?.let { ": $it" } ?: "."}"
                        }
                    )
                }
                p {
                    className = ClassName("type-hint")
                    +"A client with no budget of its own may spend ${dollarsText(node.defaultBudgetDollars)} a month. ${PNL.liveNote}"
                }
                div {
                    className = ClassName("op-table-scroll")
                    table {
                        className = ClassName("op-table")
                        thead {
                            tr {
                                th { +"client" }
                                th { +"on" }
                                th { +"monthly budget" }
                                th { +"used this month" }
                                th { +"calls" }
                                th { +"last turn" }
                                th { +"cache hits" }
                            }
                        }
                        tbody {
                            for (line in current.clients) {
                                AssistantClientRow {
                                    key = line.client.unsafeCast<Key>()
                                    this.line = line
                                    dataNotice = node.dataNotice
                                    this.now = now
                                    onChanged = { setReads { it + 1 } }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

external interface AssistantClientRowProps : Props {
    var line: AssistantClientLine
    var dataNotice: String
    var now: Instant
    var onChanged: () -> Unit
}

/** One client's row: its use, its switch, and its budget, each set at once; its own failure under it. */
private val AssistantClientRow = FC<AssistantClientRowProps> { props ->
    val line = props.line
    var budget by useState("")
    var busy by useState(false)
    var error by useState<DisplayError?>(null)

    fun set(block: suspend () -> Unit) {
        busy = true
        error = null
        operatorAssistantScope.launch {
            try {
                block()
                budget = ""
                props.onChanged()
            } catch (e: Throwable) {
                error = userFacingError(e)
            } finally {
                busy = false
            }
        }
    }

    tr {
        td {
            span { className = ClassName("op-identifier"); +line.client }
            line.name?.let { span { className = ClassName("type-hint"); +" $it" } }
        }
        td {
            if (line.enabled) {
                Button {
                    size = "small"
                    disabled = busy
                    onClick = { set { AssistantApi.setClient(line.client, enabled = false) } }
                    +"Switch off"
                }
            } else {
                Popconfirm {
                    title = "Switch the assistant on for ${line.client}?"
                    description = props.dataNotice
                    okText = "Switch on"
                    cancelText = "Cancel"
                    onConfirm = { set { AssistantApi.setClient(line.client, enabled = true, acknowledges = true) } }
                    // A row's control, not the page's one verb: an ordinary button, as Switch off is.
                    Button {
                        size = "small"
                        disabled = busy
                        +"Switch on…"
                    }
                }
            }
        }
        td {
            div {
                className = ClassName("row")
                span { +(dollarsText(line.budgetDollars) + if (line.budgetIsDefault) " (default)" else "") }
                Input {
                    value = budget
                    placeholder = "Dollars"
                    disabled = busy
                    style = js("({ width: 110 })")
                    onChange = { e -> budget = e.target.value as String }
                    onPressEnter = { budgetDollarsOf(budget)?.let { d -> set { AssistantApi.setClient(line.client, budgetDollars = d) } } }
                }
                Button {
                    size = "small"
                    disabled = busy || budgetDollarsOf(budget) == null
                    onClick = { budgetDollarsOf(budget)?.let { d -> set { AssistantApi.setClient(line.client, budgetDollars = d) } } }
                    +"Set budget"
                }
                if (!line.budgetIsDefault) {
                    Button {
                        size = "small"
                        type = "link"
                        disabled = busy
                        onClick = { set { AssistantApi.setClient(line.client, useDefault = true) } }
                        +"Use default"
                    }
                }
            }
            if (budget.isNotBlank() && budgetDollarsOf(budget) == null) {
                p { className = ClassName("type-hint"); +"An amount in dollars, such as 25 or 1,000.50." }
            }
            error?.let { errorText("Couldn't set ${line.client}.", it) }
        }
        td {
            +dollarsText(line.usedDollars)
            if (line.budgetDollars > 0) {
                span { className = ClassName("type-hint"); +" ${sharePercent(line.usedDollars / line.budgetDollars)}%" }
            }
        }
        td { +line.calls.toString() }
        td {
            line.lastUsedAt?.let { RelativeTime { iso = it; now = props.now } } ?: +"—"
        }
        td { +(line.cacheHitRatio?.let { "${(it * 100).toInt()}%" } ?: "—") }
    }
}
