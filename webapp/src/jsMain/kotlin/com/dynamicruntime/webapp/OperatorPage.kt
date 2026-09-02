package com.dynamicruntime.webapp

import com.dynamicruntime.common.endpoint.EP
import com.dynamicruntime.common.schema.PSTAT
import com.dynamicruntime.common.schema.PRES
import com.dynamicruntime.common.schema.SchType
import com.dynamicruntime.common.util.toJsonListOrEmpty
import com.dynamicruntime.common.util.toJsonMapOrEmpty
import com.dynamicruntime.common.util.toJsonStr
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.launch
import react.FC
import react.Props
import react.dom.html.ReactHTML.div
import react.dom.html.ReactHTML.h1
import react.dom.html.ReactHTML.p
import react.dom.html.ReactHTML.pre
import react.useEffect
import react.useEffectOnce
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
        operatorScope.launch {
            try {
                items = Http.getApi(props.path)[EP.items].toJsonListOrEmpty()
                asOf = nowTimeString()
                error = null
            } catch (e: Throwable) {
                error = userFacingError(e)
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
