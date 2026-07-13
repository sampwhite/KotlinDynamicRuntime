package com.dynamicruntime.webapp

import com.dynamicruntime.common.schema.SchFailure
import com.dynamicruntime.common.schema.coerceAndValidate
import com.dynamicruntime.common.util.toJsonStr
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.launch
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
 * kernel's parsed `SchType`. In edit mode, "Validate" runs the kernel's `coerceAndValidate` — the exact logic
 * the backend runs — surfacing failures (with valid choices for bad options) and the coerced request payload.
 * Executing the request against the endpoint follows in Phase 4.
 */
val EndpointCatalog = FC<Props> {
    var catalog by useState<Catalog?>(null)
    var selected by useState<EndpointInfo?>(null)
    var values by useState<Map<String, Any?>>(emptyMap())
    var editable by useState(true)
    var failures by useState<List<SchFailure>?>(null)
    var coerced by useState<String?>(null)
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
            +"Every registered endpoint, discovered from the runtime's /schema/endpoints catalog. Select one to render (and validate) its input schema."
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

    // Detail card: the selected endpoint's input schema, editable or read-only, with kernel validation.
    val current = selected
    val cat = catalog
    if (current != null && cat != null) {
        val inputType = cat.inputType(current)
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

            if (editable) {
                div {
                    className = ClassName("row")
                    Button {
                        type = "primary"
                        onClick = {
                            val result = coerceAndValidate(inputType, values)
                            failures = result.failures
                            coerced = result.value.toJsonStr()
                        }
                        +"Validate"
                    }
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
        }
    }
}
