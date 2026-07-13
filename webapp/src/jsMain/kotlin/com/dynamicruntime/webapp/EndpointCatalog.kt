package com.dynamicruntime.webapp

import kotlinx.coroutines.MainScope
import kotlinx.coroutines.launch
import react.FC
import react.Props
import react.dom.html.ReactHTML.div
import react.dom.html.ReactHTML.h1
import react.dom.html.ReactHTML.p
import react.dom.html.ReactHTML.span
import react.useEffectOnce
import react.useState
import web.cssom.ClassName

/** Coroutine scope for firing suspend catalog calls from React effects. */
private val catalogScope = MainScope()

/**
 * Phase 1 of the display engine: fetch the runtime's endpoint catalog (`/schema/endpoints`) and list every
 * registered endpoint (method, path, description). This proves the generic catalog data flow end-to-end and
 * is the seam the schema-driven forms plug into next: selecting an endpoint will render its input schema as an
 * editable/read-only form. Replaces the bespoke Todo client, which hardcoded one endpoint set.
 */
val EndpointCatalog = FC<Props> {
    var endpoints by useState(emptyList<EndpointInfo>())
    var error by useState<String?>(null)

    useEffectOnce {
        catalogScope.launch {
            try {
                endpoints = SchemaCatalogApi.fetchCatalog().endpoints
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
            +"Every registered endpoint, discovered from the runtime's /schema/endpoints catalog and rendered generically."
        }

        endpoints.forEach { ep ->
            div {
                className = ClassName("row")
                span {
                    className = ClassName("count")
                    +ep.method
                }
                span {
                    className = ClassName("todo-title")
                    +ep.path
                }
            }
            ep.description?.let {
                p {
                    className = ClassName("subtitle")
                    +it
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
}
