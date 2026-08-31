package com.dynamicruntime.webapp

// The antd `theme` export, aliased: inside the ConfigProvider builder block, `theme` is its prop.
import com.dynamicruntime.common.app.APP
import com.dynamicruntime.common.home.HMENU
import com.dynamicruntime.webapp.theme as antdTheme
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.launch
import react.FC
import react.Key
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
    // Whether the debug routes resolve (issue #227). Held as *state* for the same reason the interval above is:
    // the app config arrives asynchronously, and the module cache changing does not re-render anything. Reading
    // `appConfig()` straight from the router would answer "no" once, before the first fetch, and never be asked
    // again -- the debug page would simply never appear. Assigning here re-renders exactly once, when the answer
    // actually changes.
    var debugAllowed by useState(false)

    // The env-auth pair for the bar (issue #360). Held here, beside the other values derived from the app
    // config, because that config arrives asynchronously and its module cache re-renders nothing on its own.
    var envAuthSuppressible by useState(false)
    var envAuthActing by useState(false)
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
            debugAllowed = appConfig().allowDebugPages
            envAuthSuppressible = appConfig().envAuthSuppressible
            envAuthActing = appConfig().isEnvAuthed
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

            // The backstop (issue #223). The page boundary further down is the one that normally catches, and
            // it is better: it keeps the navigation alive. This one exists for what that cannot see -- the
            // shell itself, the app bar, the banner below -- so that NOTHING renders a blank page, and so no
            // future chrome added up here has to remember to be guarded. React runs the innermost boundary
            // that matches, so adding this changes nothing about how a page failure behaves.
            //
            // It is deliberately NOT keyed: there is no navigation left to reset it on, which is exactly why
            // its fallback offers a reload instead of telling you to go elsewhere.
            ErrorBoundary {
                fallback = ShellErrorFallback
                onError = ::reportRenderFailure

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
                AppBar {
                    this.envAuthSuppressible = envAuthSuppressible
                    this.envAuthActing = envAuthActing
                }
                div {
                    className = ClassName("app-content")
                    // The boundary wraps the page, NOT the root, so a render failure costs the page and not the
                    // navigation above it -- someone (or a test) can click away from a broken screen instead of
                    // being stranded on it (issue #223).
                    //
                    // Keyed on the page for a reason that is easy to miss: React never resets a boundary on its
                    // own, so without this the fallback would survive the navigation it invites you to make, and
                    // every later page would show the earlier page's failure. The key remounts it on a page
                    // change, which is exactly when the failure stops being relevant.
                    ErrorBoundary {
                        key = page.unsafeCast<Key>()
                        fallback = ErrorFallback
                        onError = ::reportRenderFailure
                        when (page) {
                            pageCatalog -> EndpointCatalog {}
                            pageLogin -> AuthFlow { mode = pageLogin }
                            pageRegister -> AuthFlow { mode = pageRegister }
                            pageProfile -> Profile {}
                            pageUsers -> Users {}
                            pageEnv -> EnvReferencePage {}
                            pageCfacts -> CFactReferencePage {}
                            pageNewForm -> NewFormPage {}
                            pageForms -> FormsPage {}
                            pageEditForm -> EditFormPage {}
                            // Resolved here rather than in `currentPage()` because the answer depends on the
                            // app config, which arrives asynchronously -- see `debugAllowed` above. Where the
                            // flag is off, this falls through to Home, so the route does not exist rather than
                            // being refused: nothing should acknowledge that a way to break the app is there
                            // (issue #227).
                            pageDebug -> if (debugAllowed) DebugPage {} else Home {}
                            else -> Home {}
                        }
                    }
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

// The operator environment-variable reference (issue #371). Same route policy as Users: present
// unconditionally, offered in the menu only to operators, and the endpoint refuses a caller who reaches it
// without the role -- reported honestly by the page rather than hidden.
private const val pageEnv = HMENU.pageEnv

// The cfact reference (issue #488). Same route policy as the environment reference: present unconditionally,
// offered in the menu only to a client-scoped operator or admin (and suppressible by a client), and the
// endpoint refuses a caller who reaches it without the role -- reported honestly by the page rather than hidden.
private const val pageCfacts = HMENU.pageCfacts

// The create-a-form page (issue #408). Login-gated on the backend (the `gedra` section), but the route exists
// unconditionally like the others -- the menu, built server-side, is what decides whether it is offered.
private const val pageNewForm = HMENU.pageNewForm

// The list/view page for the caller's form documents (issue #408); same login gating and route policy.
private const val pageForms = HMENU.pageForms
// The edit-a-form page (issue #417); its id (`pageEditForm`) lives with the page in EditFormPage.kt, since the
// view's Edit button names the same route. Reached from the view rather than the top nav; the section gates it.

// The debug area (issue #227). Present in the router unconditionally; whether it *renders* is gated on the
// deployment's `allowDebugPages`, checked at render time where the config is known.
private const val pageDebug = "debug"

/**
 * Resolves the page from the hash: `page=catalog` (or an endpoint deep-link carrying `m=`) shows the catalog,
 * `page=login`/`page=register` the auth flow, `page=profile` the profile page, anything else home.
 */
private fun currentPage(): String {
    val params = hashParams()
    return when {
        params[HP.page] == pageCatalog || params.containsKey(HP.method) -> pageCatalog
        params[HP.page] == pageLogin -> pageLogin
        params[HP.page] == pageRegister -> pageRegister
        params[HP.page] == pageProfile -> pageProfile
        params[HP.page] == pageUsers -> pageUsers
        params[HP.page] == pageEnv -> pageEnv
        params[HP.page] == pageCfacts -> pageCfacts
        params[HP.page] == pageNewForm -> pageNewForm
        params[HP.page] == pageForms -> pageForms
        params[HP.page] == pageEditForm -> pageEditForm
        params[HP.page] == pageDebug -> pageDebug
        else -> "home"
    }
}
