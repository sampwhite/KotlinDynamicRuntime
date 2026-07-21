package com.dynamicruntime.webapp

// The antd `theme` export, aliased: inside the ConfigProvider builder block, `theme` is its prop.
import com.dynamicruntime.common.app.APP
import com.dynamicruntime.common.home.HMENU
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
    // How often the idle bump fires (issue #146), served by the app config and re-read on every generation. Held
    // as *state* (not just the module cache), so a deployment that changes it re-arms the timer -- see useIdleBump.
    var idleBumpIntervalMs by useState(APP.defaultIdleBumpIntervalMs)
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
        appScope.launch {
            AppApi.load()
            // Pick up a reconfigured interval; a change re-keys useIdleBump, which retires the old timer.
            idleBumpIntervalMs = appConfig().idleBumpIntervalMs
        }
    }

    // A periodic tick (and a bump on returning to the app) so a tab left open notices things that change
    // without any user action -- a timed-out session reverting to the anonymous menu, a newer version deployed
    // (issue #146). It just drives the same generation bump; a hidden tab stays silent (see useIdleBump).
    useIdleBump(idleBumpIntervalMs) { setRefresh { it + 1 } }

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
                    pageUsers -> Users {}
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
// The user-administration page. Reachable only when the shell's menu offers it (the backend decides), but the
// route exists unconditionally: the page itself reports honestly when the caller lacks the capability.
private const val pageUsers = HMENU.pageUsers

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
        params["page"] == pageUsers -> pageUsers
        else -> "home"
    }
}
