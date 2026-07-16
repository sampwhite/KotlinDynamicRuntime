package com.dynamicruntime.webapp

// The antd `theme` export, aliased: inside the ConfigProvider builder block, `theme` is its prop.
import com.dynamicruntime.webapp.theme as antdTheme
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
 *
 * The whole tree sits inside a [ConfigProvider] on antd's dark algorithm, so antd's controls are themed to
 * match the shell's dark palette rather than rendering their light default inside it (issue #96).
 */
val App = FC<Props> {
    var page by useState(currentPage())
    // App is the root component (it never unmounts), so the listener lives for the page's lifetime; no cleanup.
    useEffectOnce {
        onHashChange { page = currentPage() }
    }

    // antd derives its whole palette from tokens, so the dark algorithm is set once here for the whole tree.
    val darkTheme: dynamic = js("({})")
    darkTheme.algorithm = antdTheme.darkAlgorithm

    ConfigProvider {
        theme = darkTheme

        AppBar {}
        div {
            className = ClassName("app-content")
            when (page) {
                pageCatalog -> EndpointCatalog {}
                pageLogin -> AuthFlow { mode = pageLogin }
                pageRegister -> AuthFlow { mode = pageRegister }
                pageProfile -> Profile {}
                else -> Home {}
            }
        }
    }
}

private const val pageCatalog = "catalog"
private const val pageLogin = "login"
private const val pageRegister = "register"
private const val pageProfile = "profile"

/**
 * Resolves the page from the hash: `page=catalog` (or an endpoint deep-link carrying `m=`) shows the catalog,
 * `page=login`/`page=register` the auth flow, `page=profile` the profile page, anything else home.
 */
private fun currentPage(): String {
    val params = hashParams()
    return when {
        params["page"] == pageCatalog || params.containsKey("m") -> pageCatalog
        params["page"] == pageLogin -> pageLogin
        params["page"] == pageRegister -> pageRegister
        params["page"] == pageProfile -> pageProfile
        else -> "home"
    }
}
