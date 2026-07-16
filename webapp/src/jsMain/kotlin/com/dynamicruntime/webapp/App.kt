package com.dynamicruntime.webapp

import react.FC
import react.Props
import react.dom.html.ReactHTML.div
import react.useEffectOnce
import react.useState
import web.cssom.ClassName

/**
 * The application root and top-level router. The persistent [AppBar] sits above every page; the page itself is
 * derived from the URL hash: `page=catalog` (or a deep endpoint link carrying `m=…`) shows the
 * [EndpointCatalog], anything else shows [Home]. It listens for `hashchange` so the app-bar menu and the
 * browser back/forward buttons switch pages. The catalog's in-page navigation (table ⇄ endpoint) uses
 * replaceState and does not fire `hashchange`, so it doesn't disturb this.
 */
val App = FC<Props> {
    var page by useState(currentPage())
    // App is the root component (it never unmounts), so the listener lives for the page's lifetime; no cleanup.
    useEffectOnce {
        onHashChange { page = currentPage() }
    }

    AppBar {}
    div {
        className = ClassName("app-content")
        when (page) {
            pageCatalog -> EndpointCatalog {}
            pageLogin -> AuthFlow { mode = pageLogin }
            pageRegister -> AuthFlow { mode = pageRegister }
            else -> Home {}
        }
    }
}

private const val pageCatalog = "catalog"
private const val pageLogin = "login"
private const val pageRegister = "register"

/**
 * Resolves the page from the hash: `page=catalog` (or an endpoint deep-link carrying `m=`) shows the catalog,
 * `page=login`/`page=register` the auth flow, anything else home.
 */
private fun currentPage(): String {
    val params = hashParams()
    return when {
        params["page"] == pageCatalog || params.containsKey("m") -> pageCatalog
        params["page"] == pageLogin -> pageLogin
        params["page"] == pageRegister -> pageRegister
        else -> "home"
    }
}
