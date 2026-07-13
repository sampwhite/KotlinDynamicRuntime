package com.dynamicruntime.webapp

import kotlinx.coroutines.MainScope
import kotlinx.coroutines.launch
import react.FC
import react.Props
import react.dom.html.ReactHTML.div
import react.dom.html.ReactHTML.h1
import react.dom.html.ReactHTML.h2
import react.dom.html.ReactHTML.p
import react.dom.html.ReactHTML.span
import react.useEffectOnce
import react.useState
import web.cssom.ClassName

/** Coroutine scope for firing suspend catalog calls from React effects. */
private val catalogScope = MainScope()

/**
 * The display engine's endpoint browser. It fetches the runtime's `/schema/endpoints` catalog (Phase 1) and,
 * when an endpoint is selected, renders its input schema as a read-only [SchemaForm] (Phase 2) — proving the
 * generic field dispatch across required/optional params, booleans, strings, dates, choice lists, and nested
 * map schemas. Editing and execution follow in later phases.
 */
val EndpointCatalog = FC<Props> {
    var catalog by useState<Catalog?>(null)
    var selected by useState<EndpointInfo?>(null)
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
            +"Every registered endpoint, discovered from the runtime's /schema/endpoints catalog. Select one to render its input schema."
        }

        catalog?.endpoints?.forEach { ep ->
            div {
                className = ClassName("row")
                Button {
                    size = "small"
                    type = if (selected === ep) "primary" else "default"
                    onClick = { selected = ep }
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

    // Detail card: the selected endpoint's input schema rendered read-only.
    val current = selected
    val defs = catalog?.defs
    if (current != null && defs != null) {
        div {
            className = ClassName("card")
            h1 { +"${current.method} ${current.path}" }
            current.description?.let {
                p {
                    className = ClassName("subtitle")
                    +it
                }
            }
            h2 { +"Input parameters" }
            SchemaForm {
                fields = toFields(current.inputSchema, defs)
                values = emptyMap()
            }
        }
    }
}
