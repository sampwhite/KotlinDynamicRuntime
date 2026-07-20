package com.dynamicruntime.webapp

// The antd `theme` export, aliased: inside the ConfigProvider builder block, `theme` is its prop.
import com.dynamicruntime.webapp.theme as antdTheme
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.launch
import react.FC
import react.Props
import react.dom.html.ReactHTML.button
import react.dom.html.ReactHTML.div
import react.dom.html.ReactHTML.span
import react.useEffect
import react.useEffectOnce
import react.useState
import web.cssom.ClassName

/** Coroutine scope for the app root's suspend backend calls (the app-level config fetch). */
private val appScope = MainScope()

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
    // The app-wide refresh generation (issue #115): bumped on navigation and by state mutations, it re-triggers
    // every mounted config consumer. The tuple form (not `by`) is used, so the bump is a functional update
    // (`{ it + 1 }`), which the persistent hashchange listener below needs to avoid a stale count.
    val (refresh, setRefresh) = useState(0)
    // A newer web-app version detected on a response (issue #136); drives the reload affordance below. The
    // reaction is non-destructive: we never reload out from under the user, only offer it and reload on a
    // navigation (a safe point) or an explicit click.
    var updateAvailable by useState(false)
    // App is the root component (it never unmounts), so the listener lives for the page's lifetime; no cleanup.
    useEffectOnce {
        onWebAppStale { updateAvailable = true }
        onHashChange {
            page = currentPage()
            // Cross-page navigation is a refresh trigger: bump so every mounted config consumer re-reads.
            setRefresh { it + 1 }
            // A navigation is a safe point to pick up a newer app version if one has been detected (issue #136).
            if (isWebAppStale()) reloadWebApp()
        }
    }

    // The deployment-global app config (issue #120), fetched once at the root and re-fetched on every generation
    // like any other config, so it stays fresh. Cached module-side (see AppApi) for consumers such as the
    // error-display policy (issue #111); nothing here re-renders on it, so no state is kept.
    useEffect(refresh) {
        appScope.launch { AppApi.load() }
    }

    // antd derives its whole palette from tokens, so the dark algorithm is set once here for the whole tree.
    val darkTheme: dynamic = js("({})")
    darkTheme.algorithm = antdTheme.darkAlgorithm

    RefreshContext.Provider {
        value = RefreshBus(refresh) { setRefresh { it + 1 } }
        ConfigProvider {
            theme = darkTheme

            if (updateAvailable) {
                div {
                    className = ClassName("update-banner")
                    span { +"A new version of the app is available." }
                    button {
                        className = ClassName("update-banner-reload")
                        onClick = { reloadWebApp() }
                        +"Reload"
                    }
                }
            }
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
