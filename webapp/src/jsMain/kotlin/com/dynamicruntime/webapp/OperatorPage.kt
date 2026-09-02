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
 * attention" for free. Re-fetched on the refresh generation, and stamps when it last loaded, since operator
 * data is volatile and a stale screen is a wrong answer.
 */
external interface OperatorListPageProps : Props {
    var method: String
    var path: String
    var title: String
    var description: String?
}

/** What one fetch resolved: the element type (carrying the hints) and the rows. */
private class OperatorList(val itemType: SchType?, val items: List<Any?>)

val OperatorListPage = FC<OperatorListPageProps> { props ->
    var data by useState<OperatorList?>(null)
    var error by useState<DisplayError?>(null)
    var asOf by useState<String?>(null)
    val generation = useRefreshGeneration()

    useEffect(generation) {
        operatorScope.launch {
            try {
                // The output schema (parsed by the shared kernel parser) gives the element type and its hints;
                // the response gives the rows. A list endpoint puts its items at the top level of the envelope.
                val catalog = SchemaCatalogApi.fetchEndpoint(props.method, props.path)
                val ep = catalog.endpoints.firstOrNull()
                    ?: error("No schema for ${props.method} ${props.path}.")
                val itemType = catalog.outputType(ep).properties[EP.items]?.valueType?.itemType
                val items = Http.getApi(props.path)[EP.items].toJsonListOrEmpty()
                data = OperatorList(itemType, items)
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
        val current = data
        when {
            error != null -> errorText("Couldn't load ${props.title.lowercase()}.", error!!)
            current == null -> p { className = ClassName("subtitle"); +"Loading…" }
            else -> {
                operatorVerdict(current.itemType, current.items)?.let { verdict ->
                    p { className = ClassName("op-verdict"); +verdict }
                }
                asOf?.let { p { className = ClassName("op-asof"); +"as of $it" } }
                if (current.itemType != null) {
                    schemaTable(current.itemType, current.items)
                } else {
                    // No parsed element type -- render the raw rows rather than nothing, so a schema the parser
                    // could not resolve still shows its data (and the failure is visible, not silent).
                    pre { className = ClassName("code json-value"); +current.items.toJsonStr() }
                }
            }
        }
    }
}

/**
 * The verdict line for a status-bearing list (issue #540), or null when the list has no `presentation: status`
 * column. Pure, so `jsNodeTest` covers it: it reads the status column's name off the schema, counts the rows by
 * their [PSTAT] value, and leads with whether anything needs attention -- warning and error both count as
 * "attention", ok and info do not.
 */
fun operatorVerdict(itemType: SchType?, items: List<Any?>): String? {
    val statusField = itemType?.properties?.values
        ?.firstOrNull { it.valueType.presentation == PRES.status }?.name
        ?: return null
    val attention = items.count { row ->
        val status = row.toJsonMapOrEmpty()[statusField] as? String
        status == PSTAT.warning || status == PSTAT.error
    }
    val total = items.size
    return when {
        total == 0 -> "Nothing to report."
        attention == 0 -> "All $total OK."
        else -> "$attention of $total need attention."
    }
}
