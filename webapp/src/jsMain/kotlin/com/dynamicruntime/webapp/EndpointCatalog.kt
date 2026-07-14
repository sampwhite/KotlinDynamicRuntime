package com.dynamicruntime.webapp

import com.dynamicruntime.common.schema.SchFailure
import com.dynamicruntime.common.schema.SchType
import com.dynamicruntime.common.schema.coerceAndValidate
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
import react.dom.html.ReactHTML.span
import react.useEffectOnce
import react.useState
import web.cssom.ClassName

/** Coroutine scope for firing suspend catalog calls from React effects. */
private val catalogScope = MainScope()

/**
 * The display engine's endpoint browser. It fetches the runtime's `/schema/endpoints` catalog and, when an
 * endpoint is selected, renders its input schema as an editable or read-only [SchemaForm] driven by the shared
 * kernel's parsed `SchType`. "Validate" runs the kernel's `coerceAndValidate` (the exact backend logic),
 * surfacing failures and the coerced payload; "Run" validates, executes the endpoint with that coerced payload,
 * and renders the response through the SAME engine (read-only) over the endpoint's output schema.
 */
val EndpointCatalog = FC<Props> {
    var catalog by useState<Catalog?>(null)
    var selected by useState<EndpointInfo?>(null)
    var values by useState<Map<String, Any?>>(emptyMap())
    var editable by useState(true)
    var failures by useState<List<SchFailure>?>(null)
    var coerced by useState<String?>(null)
    var response by useState<Map<String, Any?>?>(null)
    var runError by useState<String?>(null)
    var running by useState(false)
    var error by useState<String?>(null)

    useEffectOnce {
        catalogScope.launch {
            try {
                catalog = SchemaCatalogApi.fetchCatalog()
                error = null
            } catch (e: Throwable) {
                error = "Catalog fetch failed — is `./gradlew :sample:run` running? (${e.message})"
            }
        }
    }

    div {
        className = ClassName("card")

        h1 { +"Endpoint catalog" }
        p {
            className = ClassName("subtitle")
            +"Every registered endpoint, discovered from the runtime's /schema/endpoints catalog. Select one to render, validate, and run it."
        }

        catalog?.endpoints?.forEach { ep ->
            div {
                className = ClassName("row")
                Button {
                    size = "small"
                    type = if (selected === ep) "primary" else "default"
                    onClick = {
                        selected = ep
                        values = emptyMap()
                        failures = null
                        coerced = null
                        response = null
                        runError = null
                    }
                    +ep.method
                }
                span {
                    className = ClassName("todo-title")
                    +ep.path
                }
            }
        }

        error?.let {
            p {
                className = ClassName("todo-error")
                +it
            }
        }
    }

    // Detail card: the selected endpoint's input schema (editable/read-only), validation, and the run + response.
    val current = selected
    val cat = catalog
    if (current != null && cat != null) {
        val inputType = cat.inputType(current)

        // Validates the entered values with the kernel; returns the coerced payload when there are no failures.
        fun validate(): Map<String, Any?>? {
            val result = coerceAndValidate(inputType, values)
            failures = result.failures
            coerced = result.value.toJsonStr()
            return if (result.failures.isEmpty()) result.value.asMap() else null
        }

        div {
            className = ClassName("card")
            h1 { +"${current.method} ${current.path}" }
            current.description?.let {
                p {
                    className = ClassName("subtitle")
                    +it
                }
            }

            div {
                className = ClassName("row")
                Button {
                    size = "small"
                    onClick = { editable = !editable }
                    +if (editable) "Switch to read-only" else "Switch to edit"
                }
            }

            h2 { +"Input parameters" }
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
