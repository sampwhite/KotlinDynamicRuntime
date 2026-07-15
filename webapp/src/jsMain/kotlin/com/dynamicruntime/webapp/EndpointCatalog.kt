package com.dynamicruntime.webapp

import com.dynamicruntime.common.schema.SchFailure
import com.dynamicruntime.common.schema.SchType
import com.dynamicruntime.common.schema.coerceAndValidate
import com.dynamicruntime.common.util.jsonMap
import com.dynamicruntime.common.util.toJsonStr
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.launch
import react.ChildrenBuilder
import react.FC
import react.Props
import react.dom.html.ReactHTML.div
import react.dom.html.ReactHTML.h1
import react.dom.html.ReactHTML.h2
import react.dom.html.ReactHTML.p
import react.dom.html.ReactHTML.pre
import react.useEffect
import react.useEffectOnce
import react.useRef
import react.useState
import web.cssom.ClassName

/** Coroutine scope for firing suspend catalog calls from React effects. */
private val catalogScope = MainScope()

/**
 * The display engine's endpoint browser, in two "pages": the catalog (an [EndpointTable] of every registered
 * endpoint) and, once a row is selected, that endpoint's page — its identity, description, an interactive
 * input form (validate + run + rendered response), and a link that reveals the output schema. Navigation is a
 * simple view swap on the selection; a "Back to catalog" link clears it.
 */
val EndpointCatalog = FC<Props> {
    var catalog by useState<Catalog?>(null)
    var selected by useState<EndpointInfo?>(null)
    var values by useState<Map<String, Any?>>(emptyMap())
    var editable by useState(true)
    var showOutput by useState(false)
    var failures by useState<List<SchFailure>?>(null)
    var coerced by useState<String?>(null)
    var response by useState<Map<String, Any?>?>(null)
    var runError by useState<String?>(null)
    var running by useState(false)
    var error by useState<String?>(null)
    // True once the initial URL-hash restore has run; until then the URL is not written back (so the
    // mount-time sync effect can't clobber a hash we are about to read).
    var restored by useState(false)

    // The latest catalog, readable from the once-registered hashchange listener below (which would otherwise
    // close over the null catalog of the first render).
    val catalogRef = useRef<Catalog>(null)
    catalogRef.current = catalog

    useEffectOnce {
        catalogScope.launch {
            try {
                val fetched = SchemaCatalogApi.fetchCatalog()
                catalog = fetched
                error = null
                // Restore the selected endpoint (and any entered values) from the URL hash, so a refresh or a
                // shared link lands back on the same endpoint with the same input.
                readHash()?.let { hs ->
                    fetched.endpoints.firstOrNull { it.method == hs.method && it.path == hs.path }?.let { ep ->
                        selected = ep
                        values = hs.values
                    }
                }
            } catch (e: Throwable) {
                error = "Catalog fetch failed — is `./gradlew :sample:run` running? (${e.message})"
            } finally {
                restored = true
            }
        }
    }

    // Keep the URL hash in sync with the current selection and input values (replaceState: no history spam,
    // no hashchange loop). Gated on `restored` so it never overwrites the hash before it has been read.
    useEffect(selected, values, restored) {
        if (restored) {
            writeHash(selected, values)
        }
    }

    // React to hash changes made from OUTSIDE this component -- the app-bar menu ("Endpoint catalog" drops the
    // `m=`/`p=` params) and the back/forward buttons -- by re-deriving the selection from the hash. Our own
    // navigation uses replaceState, which does not fire hashchange, so this never fights the sync effect above.
    // The catalog is read through a ref because this listener is registered once and would otherwise capture
    // the (still null) catalog from the first render.
    useEffectOnce {
        onHashChange {
            val loaded = catalogRef.current
            if (loaded != null) {
                val target = readHash()
                selected = target?.let { hs ->
                    loaded.endpoints.firstOrNull { it.method == hs.method && it.path == hs.path }
                }
                values = target?.values ?: emptyMap()
                failures = null
                coerced = null
                response = null
                runError = null
            }
        }
    }

    val current = selected
    val cat = catalog

    if (current == null || cat == null) {
        // ---- Catalog page: the endpoint table ----
        div {
            className = ClassName("card wide")
            h1 { +"Endpoint catalog" }
            p {
                className = ClassName("subtitle")
                +"Every registered endpoint, discovered from the runtime's /schema/endpoints catalog. Select one to view and run it."
            }
            when {
                error != null -> p {
                    className = ClassName("todo-error")
                    +error!!
                }
                cat == null -> p {
                    className = ClassName("subtitle")
                    +"Loading…"
                }
                else -> EndpointTable {
                    endpoints = cat.endpoints
                    onSelect = { ep ->
                        selected = ep
                        values = emptyMap()
                        editable = true
                        showOutput = false
                        failures = null
                        coerced = null
                        response = null
                        runError = null
                    }
                }
            }
        }
    } else {
        // ---- Endpoint page ----
        val inputType = cat.inputType(current)

        // Validates the entered values with the kernel; returns the coerced payload when there are no failures.
        fun validate(): Map<String, Any?>? {
            val result = coerceAndValidate(inputType, values)
            failures = result.failures
            coerced = result.value.toJsonStr()
            return if (result.failures.isEmpty()) result.value.asMap() else null
        }

        div {
            className = ClassName("card wide")

            div {
                className = ClassName("row")
                Button {
                    type = "link"
                    onClick = { selected = null }
                    +"← Back to catalog"
                }
            }

            h1 { +"${current.method} ${current.path}" }
            current.description?.let {
                p {
                    className = ClassName("subtitle")
                    +it
                }
            }

            // Separate link to reveal the output schema (structure only).
            div {
                className = ClassName("row")
                Button {
                    type = "link"
                    onClick = { showOutput = !showOutput }
                    +if (showOutput) "Hide output schema" else "View output schema"
                }
            }
            if (showOutput) {
                h2 { +"Output schema" }
                SchemaOutline { type = cat.outputType(current) }
            }

            h2 { +"Input parameters" }
            div {
                className = ClassName("row")
                Button {
                    size = "small"
                    onClick = { editable = !editable }
                    +if (editable) "Switch to read-only" else "Switch to edit"
                }
            }
            SchemaForm {
                type = inputType
                this.values = values
                this.editable = editable
                onChange = { values = it }
            }

            div {
                className = ClassName("row")
                if (editable) {
                    Button {
                        onClick = { validate() }
                        +"Validate"
                    }
                }
                Button {
                    type = "primary"
                    loading = running
                    onClick = {
                        // Validate first; only send when the coerced payload has no failures (they're shown).
                        val payload = validate()
                        if (payload != null) {
                            running = true
                            response = null
                            runError = null
                            catalogScope.launch {
                                try {
                                    response = SchemaCatalogApi.invoke(current, payload)
                                } catch (e: Throwable) {
                                    runError = e.message
                                } finally {
                                    running = false
                                }
                            }
                        }
                    }
                    +"Run"
                }
            }

            failures?.let { fs ->
                if (fs.isEmpty()) {
                    p {
                        className = ClassName("form-ok")
                        +"✓ Valid against the endpoint's input schema."
                    }
                } else {
                    h2 { +"Validation failures" }
                    fs.forEach { f ->
                        p {
                            className = ClassName("todo-error")
                            val choices = f.options?.joinToString(", ") { it.value }?.let { " (valid: $it)" } ?: ""
                            +"${f.path.ifEmpty { "(root)" }}: ${f.message}$choices"
                        }
                    }
                }
            }

            coerced?.let {
                h2 { +"Coerced request" }
                pre {
                    className = ClassName("code")
                    +it
                }
            }

            runError?.let {
                p {
                    className = ClassName("todo-error")
                    +"Request failed: $it"
                }
            }

            response?.let { resp ->
                h2 { +"Response" }
                renderResponse(current.kind, cat.payloadType(current), resp)
                h2 { +"Raw response" }
                pre {
                    className = ClassName("code")
                    +resp.toJsonStr()
                }
            }

            // A second back link at the bottom, so a long response page doesn't force a scroll back up.
            div {
                className = ClassName("row")
                Button {
                    type = "link"
                    onClick = { selected = null }
                    +"← Back to catalog"
                }
            }
        }
    }
}

/**
 * Renders an endpoint's response payload through the read-only [SchemaForm], unwrapping the protocol envelope
 * by [kind]: a `general` result (`results`) and an `item` are single objects; a `list` (`items`) renders each
 * element. [payloadType] is the resolved element/object type; when it is null (an untyped payload) the payload
 * falls back to formatted JSON.
 */
private fun ChildrenBuilder.renderResponse(kind: String, payloadType: SchType?, response: Map<String, Any?>) {
    when (kind) {
        EKind.list -> {
            val items = response[EK.items].asList()
            if (items.isEmpty()) {
                p {
                    className = ClassName("type-hint")
                    +"(no items)"
                }
            }
            items.forEachIndexed { i, item ->
                div {
                    className = ClassName("nested")
                    p {
                        className = ClassName("type-hint")
                        +"[$i]"
                    }
                    renderPayload(payloadType, item.asMap())
                }
            }
        }
        EKind.item -> renderPayload(payloadType, response[EK.item].asMap())
        else -> renderPayload(payloadType, response[EK.results].asMap())
    }
}

/** Renders one payload object read-only via [SchemaForm], or as formatted JSON when its type is unknown. */
private fun ChildrenBuilder.renderPayload(type: SchType?, data: Map<String, Any?>) {
    if (type != null) {
        SchemaForm {
            this.type = type
            values = data
            editable = false
            onChange = {}
        }
    } else {
        pre {
            className = ClassName("code")
            +data.toJsonStr()
        }
    }
}

/** Null-tolerant view of a value as a `Map`. */
@Suppress("UNCHECKED_CAST")
private fun Any?.asMap(): Map<String, Any?> = (this as? Map<String, Any?>) ?: emptyMap()

/** Null-tolerant view of a value as a `List`. */
private fun Any?.asList(): List<Any?> = (this as? List<*>) ?: emptyList()

// --- URL-hash routing -------------------------------------------------------------------------------------
// Within the catalog page, the selected endpoint (method + path) and the entered input values (as JSON) live
// in the URL hash (under `page=catalog`), so a refresh or a shared link restores the same endpoint page and
// the same input. Shared hash helpers live in HashRoute.kt.

/** The endpoint identity and input values decoded from the URL hash. */
private class HashState(val method: String, val path: String, val values: Map<String, Any?>)

/** Parses the current URL hash into a [HashState], or null when it names no endpoint. */
private fun readHash(): HashState? {
    val params = hashParams()
    val method = params["m"] ?: return null
    val path = params["p"] ?: return null
    val values = params["v"]?.let { runCatching { it.jsonMap() }.getOrNull() } ?: emptyMap()
    return HashState(method, path, values)
}

/** Writes the current selection + input values into the URL hash. Stays on `page=catalog`; adds the endpoint
 *  (and its values) when one is selected. */
private fun writeHash(endpoint: EndpointInfo?, values: Map<String, Any?>) {
    val params = mutableListOf("page" to "catalog")
    if (endpoint != null) {
        params.add("m" to endpoint.method)
        params.add("p" to endpoint.path)
        if (values.isNotEmpty()) {
            params.add("v" to values.toJsonStr(compact = true))
        }
    }
    replaceHash(params)
}
