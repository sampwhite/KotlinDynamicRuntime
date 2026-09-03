package com.dynamicruntime.webapp

import com.dynamicruntime.common.endpoint.EP
import com.dynamicruntime.common.home.HMENU
import com.dynamicruntime.common.operator.OPS
import com.dynamicruntime.common.operator.TCI
import com.dynamicruntime.common.operator.TCS
import com.dynamicruntime.common.schema.PSTAT
import com.dynamicruntime.common.schema.PRES
import com.dynamicruntime.common.schema.SchType
import com.dynamicruntime.common.util.toJsonListOrEmpty
import com.dynamicruntime.common.util.toJsonMapOrEmpty
import com.dynamicruntime.common.util.toJsonStr
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.launch
import react.ChildrenBuilder
import react.FC
import react.Props
import react.dom.html.ReactHTML.a
import react.dom.html.ReactHTML.button
import react.dom.html.ReactHTML.div
import react.dom.html.ReactHTML.h1
import react.dom.html.ReactHTML.p
import react.dom.html.ReactHTML.li
import react.dom.html.ReactHTML.pre
import react.dom.html.ReactHTML.span
import react.dom.html.ReactHTML.table
import react.dom.html.ReactHTML.tbody
import react.dom.html.ReactHTML.td
import react.dom.html.ReactHTML.th
import react.dom.html.ReactHTML.thead
import react.dom.html.ReactHTML.tr
import react.dom.html.ReactHTML.ul
import react.useEffect
import react.useEffectOnce
import react.useRef
import react.useState
import web.cssom.ClassName

/** The current wall-clock time as a short local string, for the "as of" freshness stamp. */
private fun nowTimeString(): String = js("new Date().toLocaleTimeString()") as String

/** Coroutine scope for the page's suspend fetches. */
private val operatorScope = MainScope()

/**
 * A generic operator diagnostic page for a **list** endpoint (issue #540): it fetches the endpoint's output
 * schema and its response, then renders the item list as a table driven entirely by the schema's presentation
 * hints ([SchemaForm]'s read-only path). Nothing here knows the shape of any particular endpoint -- an endpoint
 * "looks bespoke" only because it declared `presentation` hints beside its schema, so a renamed or added field
 * follows automatically rather than needing an edit here.
 *
 * The verdict-first summary (issue #540's UX) is likewise generic: it is computed from whichever column the
 * schema marks `presentation: status`, so any list endpoint with a status column gets "all OK / N need
 * attention" for free.
 *
 * The two fetches are split on purpose. The **schema** is static -- it does not change between refreshes -- so
 * it is fetched once on mount; the **data** is volatile, so it is re-fetched on every refresh generation and
 * stamps when it last loaded (a stale operator screen is a wrong answer). Splitting them also lets the first
 * load run both in parallel rather than the schema gating the data.
 */
external interface OperatorListPageProps : Props {
    var method: String
    var path: String
    var title: String
    var description: String?
}

val OperatorListPage = FC<OperatorListPageProps> { props ->
    // The element type carrying the hints, resolved once from the static schema. Null once schema has loaded
    // means the caller cannot see this endpoint (empty catalog) or the parser could not resolve the payload.
    var itemType by useState<SchType?>(null)
    var schemaLoaded by useState(false)
    var items by useState<List<Any?>?>(null)
    var error by useState<DisplayError?>(null)
    var asOf by useState<String?>(null)
    val generation = useRefreshGeneration()
    // Monotonic token so an out-of-order data response is dropped rather than overwriting newer data (below).
    val latestDataRun = useRef(0)

    // The output schema (parsed by the shared kernel parser), fetched once. An **empty** catalog is not an
    // error here: it means the caller cannot see this endpoint, and the data fetch below surfaces that refusal
    // honestly -- inventing a "no schema" throw here would have reported it as a connectivity failure instead.
    useEffectOnce {
        operatorScope.launch {
            try {
                val catalog = SchemaCatalogApi.fetchEndpoint(props.method, props.path)
                itemType = catalog.endpoints.firstOrNull()?.let { catalog.payloadType(it) }
            } catch (e: Throwable) {
                error = userFacingError(e)
            }
            schemaLoaded = true
        }
    }

    // The response rows, re-fetched on every refresh generation. A list endpoint puts its items at the top
    // level of the envelope.
    useEffect(generation) {
        // `operatorScope` is a module-global scope, so a launch outlives this effect. A superseded (out-of-order)
        // response must not overwrite a newer one nor stamp it with a newer "as of": each run claims a token,
        // and a result applies only while its token is still the latest.
        val token = (latestDataRun.current ?: 0) + 1
        latestDataRun.current = token
        operatorScope.launch {
            try {
                val loaded = Http.getApi(props.path)[EP.items].toJsonListOrEmpty()
                if (latestDataRun.current == token) {
                    items = loaded
                    asOf = nowTimeString()
                    error = null
                }
            } catch (e: Throwable) {
                if (latestDataRun.current == token) error = userFacingError(e)
            }
        }
    }

    div {
        className = ClassName("card wide")
        h1 { +props.title }
        props.description?.let { desc ->
            p { className = ClassName("subtitle"); +desc }
        }
        val rows = items
        when {
            error != null -> errorText("Couldn't load ${props.title.lowercase()}.", error!!)
            !schemaLoaded || rows == null -> p { className = ClassName("subtitle"); +"Loading…" }
            else -> {
                operatorVerdict(itemType, rows)?.let { verdict ->
                    p { className = ClassName("op-verdict"); +verdict }
                }
                asOf?.let { p { className = ClassName("op-asof"); +"as of $it" } }
                if (itemType != null) {
                    schemaTable(itemType!!, rows)
                } else {
                    // No parsed element type -- render the raw rows rather than nothing, so a schema the parser
                    // could not resolve still shows its data (and the failure is visible, not silent).
                    pre { className = ClassName("code json-value"); +rows.toJsonStr() }
                }
            }
        }
    }
}

/**
 * The verdict line for a status-bearing list (issue #540), or null when the list has no `presentation: status`
 * column. Pure, so `jsNodeTest` covers it: it reads the status column's name off the schema, counts the rows by
 * their [PSTAT] value, and leads with whether anything needs attention -- which [PSTAT.needsAttention] decides
 * from the one kernel severity ordering, so this page and the schema's own status vocabulary cannot drift.
 */
fun operatorVerdict(itemType: SchType?, items: List<Any?>): String? {
    val statusField = itemType?.properties?.values
        ?.firstOrNull { it.valueType.presentation == PRES.status }?.name
        ?: return null
    val attention = items.count { row ->
        val status = row.toJsonMapOrEmpty()[statusField] as? String
        status != null && PSTAT.needsAttention(status)
    }
    val total = items.size
    return when {
        total == 0 -> "Nothing to report."
        attention == 0 -> "All $total OK."
        else -> "$attention of $total need attention."
    }
}

/**
 * A generic operator diagnostic page for an endpoint whose output is a **single free-form object** (issue
 * #540) -- system/info is the case: a nested diagnostic map the endpoint deliberately declares no schema for.
 * It fetches the object and renders it value-driven ([objectView]); an endpoint that *does* declare fields and
 * presentation hints uses the schema-driven [OperatorListPage] / SchemaForm path instead.
 *
 * Re-fetched on the refresh generation and stamped, for the same reason the list page is: operator data is
 * volatile. A caller who cannot see the endpoint gets the endpoint's own refusal, reported not blanked.
 */
external interface OperatorObjectPageProps : Props {
    var path: String
    var title: String
    var description: String?
}

val OperatorObjectPage = FC<OperatorObjectPageProps> { props ->
    var obj by useState<Map<String, Any?>?>(null)
    var error by useState<DisplayError?>(null)
    var asOf by useState<String?>(null)
    val generation = useRefreshGeneration()
    // Monotonic token so an out-of-order response is dropped rather than overwriting newer data (below).
    val latestRun = useRef(0)

    useEffect(generation) {
        // `operatorScope` is a module-global scope, so a launch outlives this effect. A superseded (out-of-order)
        // response must not overwrite a newer one nor stamp it with a newer "as of": each run claims a token,
        // and a result applies only while its token is still the latest.
        val token = (latestRun.current ?: 0) + 1
        latestRun.current = token
        operatorScope.launch {
            try {
                // A general endpoint wraps its object under `results` (a list endpoint uses `items`); read that,
                // so the view shows the diagnostic map and not the envelope's own bookkeeping fields.
                val loaded = Http.getApi(props.path)[EP.results].toJsonMapOrEmpty()
                if (latestRun.current == token) {
                    obj = loaded
                    asOf = nowTimeString()
                    error = null
                }
            } catch (e: Throwable) {
                if (latestRun.current == token) error = userFacingError(e)
            }
        }
    }

    div {
        className = ClassName("card wide")
        h1 { +props.title }
        props.description?.let { desc ->
            p { className = ClassName("subtitle"); +desc }
        }
        val current = obj
        when {
            error != null -> errorText("Couldn't load ${props.title.lowercase()}.", error!!)
            current == null -> p { className = ClassName("subtitle"); +"Loading…" }
            else -> {
                asOf?.let { p { className = ClassName("op-asof"); +"as of $it" } }
                objectView(current)
            }
        }
    }
}

/**
 * A read-only view of a free-form JSON object (issue #540): nested objects become titled sections, a list of
 * objects a generic table (its columns the union of the rows' keys), a list of scalars a joined line, and a
 * scalar a key/value row. Value-driven precisely because there is no schema to follow -- the counterpart to
 * SchemaForm's schema-driven read-only path, used where the endpoint declared no fields.
 *
 * [depth] guards the recursion over external data (a convention here): past a shallow cap the sub-tree is
 * shown as raw JSON rather than recursing further, which no real diagnostic map reaches.
 */
private fun ChildrenBuilder.objectView(obj: Map<String, Any?>, depth: Int = 0) {
    if (depth > 8) {
        pre { className = ClassName("code json-value"); +obj.toJsonStr() }
        return
    }
    for ((key, value) in obj) {
        when {
            value is Map<*, *> -> {
                p { className = ClassName("op-section-label"); +humanizeFieldName(key) }
                div { className = ClassName("nested"); objectView(value.toJsonMapOrEmpty(), depth + 1) }
            }
            value is List<*> && value.any { it is Map<*, *> } -> {
                p { className = ClassName("op-section-label"); +humanizeFieldName(key) }
                div { className = ClassName("nested"); genericTable(value) }
            }
            else -> div {
                className = ClassName("op-kv")
                span { className = ClassName("op-kv-key"); +humanizeFieldName(key) }
                span { className = ClassName("op-kv-val"); +scalarText(value) }
            }
        }
    }
}

/** A schemaless list of objects as a table: the columns are the union of the rows' keys, in first-seen order. */
private fun ChildrenBuilder.genericTable(elements: List<*>) {
    val rows = elements.map { it.toJsonMapOrEmpty() }
    if (rows.isEmpty()) {
        p { className = ClassName("type-hint"); +"(none)" }
        return
    }
    val columns = LinkedHashSet<String>().apply { rows.forEach { addAll(it.keys) } }.toList()
    // Scrolls inside its own box rather than pushing past the card, like the schema-driven table (issue #540).
    div {
        className = ClassName("op-table-scroll")
        table {
            className = ClassName("op-table")
            thead { tr { for (col in columns) th { +humanizeFieldName(col) } } }
            tbody { for (row in rows) tr { for (col in columns) td { +scalarText(row[col]) } } }
        }
    }
}

/** One value as read-only text: a nested structure as compact JSON, anything else as its plain string. */
private fun scalarText(value: Any?): String = when (value) {
    null -> ""
    is Map<*, *>, is List<*> -> value.toJsonStr()
    else -> value.toString()
}

/**
 * The Operator landing page (issue #540), mirroring the Debug index: a discoverable listing of the operator
 * diagnostics with a one-line explanation of each, reached from the "Overview" entry of the Operator menu
 * group. The menu also offers each tool directly (operators use them repeatedly); this page is the explainer.
 */
val OperatorIndex = FC<Props> {
    div {
        className = ClassName("card wide")
        h1 { +"Operator" }
        p {
            className = ClassName("subtitle")
            +("Diagnostics for this running node. Each page reads a live operator endpoint and re-reads it as " +
                "the app refreshes, so it answers about this node right now.")
        }
        ul {
            className = ClassName("operator-index")
            li {
                a { href = "#page=${HMENU.pageEnv}"; +"Environment" }
                +" \u2014 the environment variables this node declares, with each one's resolved value here."
            }
            li {
                a { href = "#page=${HMENU.pageSystemInfo}"; +"System info" }
                +" \u2014 this node's identity, uptime, and JVM statistics."
            }
            li {
                a { href = "#page=${HMENU.pageBootChecks}"; +"Boot checks" }
                +" \u2014 every check this node ran at startup, its mode, and what it found."
            }
            li {
                a { href = "#page=${HMENU.pageDbTables}"; +"Database tables" }
                +" \u2014 every database table registered for this instance."
            }
            li {
                a { href = "#page=${HMENU.pageFragmentsCheck}"; +"Fragments check" }
                +" \u2014 the Markdown fragment files this node carries, and any problems found."
            }
            li {
                a { href = "#page=${HMENU.pageCacheState}"; +"Cache state" }
                +" \u2014 this node's table caches beside the dates every node shares, with a reload action."
            }
        }
    }
}


// --- cache state (issue #540, tier 3) --------------------------------------------------------------------

/** One cached table on this node, joined with the shared row's last-changed date for a side-by-side read. */
class CacheRow(
    val tableName: String,
    val topic: String,
    val numRows: Long,
    val highCounter: Long,
    val lastSeen: String?,
    val pendingReload: Boolean,
    val sharedChanged: String?,
    val needsAttention: Boolean,
)

/**
 * The cache-state page's view of the report: this node's caches (each beside the shared row), the shared-row
 * tables this node holds no cache for, and a count of what wants attention. Pure, so `jsNodeTest` covers the
 * join and the (deliberately light) flagging -- no confident "behind" verdict, since the two dates are a
 * change-announce time and a row `updatedAt`, not strictly comparable.
 */
class CacheStateView(
    val nodeId: String,
    val isDisabled: Boolean,
    val rows: List<CacheRow>,
    val unheldShared: List<Pair<String, String>>,
    val attentionCount: Int,
)

fun cacheStateView(report: Map<String, Any?>): CacheStateView {
    val caches = report[TCS.caches].toJsonListOrEmpty().map { it.toJsonMapOrEmpty() }
    val shared = report[TCS.sharedState].toJsonMapOrEmpty()
    val held = caches.mapNotNull { it[TCI.tableName] as? String }.toSet()
    val rows = caches.map { c ->
        val name = c[TCI.tableName] as? String ?: ""
        val pending = c[TCI.pendingReload] == true
        CacheRow(
            tableName = name,
            topic = c[TCI.topic] as? String ?: "",
            numRows = (c[TCI.numRows] as? Number)?.toLong() ?: 0L,
            highCounter = (c[TCI.highCounter] as? Number)?.toLong() ?: 0L,
            lastSeen = c[TCI.lastSeen] as? String,
            pendingReload = pending,
            sharedChanged = shared[name] as? String,
            // Light flag only: a local write awaiting reload. Not a date comparison -- see the class KDoc.
            needsAttention = pending,
        )
    }
    // Tables the shared row records that this node holds no cache for: another node caches something this one
    // does not, the clearest "this node is not the whole picture" signal.
    val unheld = shared.entries.filter { it.key !in held }.map { it.key to (it.value as? String ?: "") }
        .sortedBy { it.first }
    val attention = rows.count { it.needsAttention } + unheld.size
    return CacheStateView(
        nodeId = report[TCS.nodeId] as? String ?: "",
        isDisabled = report[TCS.isDisabled] == true,
        rows = rows,
        unheldShared = unheld,
        attentionCount = attention,
    )
}

/**
 * The cache-state operator page (issue #540, tier 3): a per-node view of the table caches beside the change
 * dates every node shares, with a reload action per cache and a reload-all. Hand-written rather than
 * schema-driven because it joins two shapes and carries an action -- but the action is a plain operator POST
 * that then bumps the app's refresh generation, so the page re-reads itself.
 */
val OperatorCacheStatePage = FC<Props> {
    var report by useState<Map<String, Any?>?>(null)
    var error by useState<DisplayError?>(null)
    var asOf by useState<String?>(null)
    // Which target is reloading: a table name, "" for reload-all, or null for none. Disables the buttons.
    var reloading by useState<String?>(null)
    val generation = useRefreshGeneration()
    val bump = useRefreshBump()
    val latestRun = useRef(0)

    useEffect(generation) {
        val token = (latestRun.current ?: 0) + 1
        latestRun.current = token
        operatorScope.launch {
            try {
                val loaded = Http.getApi(OPS.cacheStatePath)[EP.results].toJsonMapOrEmpty()
                if (latestRun.current == token) {
                    report = loaded
                    asOf = nowTimeString()
                    error = null
                }
            } catch (e: Throwable) {
                if (latestRun.current == token) error = userFacingError(e)
            }
        }
    }

    // Force a reload on this node (one table, or all), then bump so the report re-reads on the new generation.
    fun reload(table: String?) {
        reloading = table ?: ""
        operatorScope.launch {
            try {
                val body = if (table != null) mapOf(TCS.table to table) else emptyMap()
                Http.sendApi("POST", OPS.cacheReloadPath, body)
                reloading = null
                bump()
            } catch (e: Throwable) {
                reloading = null
                error = userFacingError(e)
            }
        }
    }

    div {
        className = ClassName("card wide")
        h1 { +"Cache state" }
        p {
            className = ClassName("subtitle")
            +"This node's in-memory table caches, beside the change dates every node shares."
        }
        val current = report
        when {
            error != null -> errorText("Couldn't load cache state.", error!!)
            current == null -> p { className = ClassName("subtitle"); +"Loading…" }
            else -> {
                val view = cacheStateView(current)
                // Cache state is per node -- say which node answered.
                p {
                    className = ClassName("op-node")
                    +"Node "
                    span { className = ClassName("op-identifier"); +view.nodeId }
                }
                asOf?.let { p { className = ClassName("op-asof"); +"as of $it" } }
                when {
                    view.isDisabled ->
                        p { className = ClassName("op-verdict"); +"Table caching is disabled on this node." }
                    view.attentionCount == 0 ->
                        p { className = ClassName("op-verdict"); +"This node is current." }
                    else ->
                        p { className = ClassName("op-verdict"); +"${view.attentionCount} item(s) need attention." }
                }
                div {
                    className = ClassName("row")
                    button {
                        className = ClassName("op-reload")
                        disabled = reloading != null || view.isDisabled
                        onClick = { reload(null) }
                        +(if (reloading == "") "Reloading…" else "Reload all")
                    }
                }
                cacheTable(view.rows, reloading) { t -> reload(t) }
                if (view.unheldShared.isNotEmpty()) {
                    p { className = ClassName("op-section-label"); +"Cached by other nodes, not here" }
                    div {
                        className = ClassName("op-table-scroll")
                        table {
                            className = ClassName("op-table")
                            thead { tr { th { +"table" }; th { +"last changed" } } }
                            tbody {
                                for ((name, date) in view.unheldShared) tr {
                                    td { span { className = ClassName("op-identifier"); +name } }
                                    td { +date }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

/** The caches as a table: each row beside the shared row's last-changed date, with a per-row reload button.
 *  [reloading] disables every button while one is in flight (a table name, "" for all, or null for none). */
private fun ChildrenBuilder.cacheTable(rows: List<CacheRow>, reloading: String?, onReload: (String) -> Unit) {
    if (rows.isEmpty()) {
        p { className = ClassName("type-hint"); +"(no caches on this node)" }
        return
    }
    div {
        className = ClassName("op-table-scroll")
        table {
            className = ClassName("op-table")
            thead {
                tr {
                    th { +"table" }
                    th { +"topic" }
                    th { +"rows" }
                    th { +"counter" }
                    th { +"last seen (here)" }
                    th { +"last changed (shared)" }
                    th { +"" }
                }
            }
            tbody {
                for (row in rows) tr {
                    td { span { className = ClassName("op-identifier"); +row.tableName } }
                    td { +row.topic }
                    td { +row.numRows.toString() }
                    td { +row.highCounter.toString() }
                    td { +(row.lastSeen ?: "—") }
                    td {
                        +(row.sharedChanged ?: "—")
                        if (row.pendingReload) span { className = ClassName("op-status warning"); +"reload pending" }
                    }
                    td {
                        button {
                            className = ClassName("op-reload")
                            disabled = reloading != null
                            onClick = { onReload(row.tableName) }
                            +(if (reloading == row.tableName) "Reloading…" else "Reload")
                        }
                    }
                }
            }
        }
    }
}
