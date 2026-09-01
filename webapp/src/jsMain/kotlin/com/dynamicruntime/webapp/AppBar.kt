package com.dynamicruntime.webapp

import com.dynamicruntime.common.app.EnvAuthOp
import com.dynamicruntime.common.home.HACT
import com.dynamicruntime.common.uiblock.UiCall
import com.dynamicruntime.common.uiblock.UiRoute
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.launch
import react.ChildrenBuilder
import react.FC
import react.Props
import react.dom.html.ReactHTML.a
import react.dom.html.ReactHTML.button
import react.dom.html.ReactHTML.div
import react.dom.html.ReactHTML.header
import react.dom.html.ReactHTML.img
import react.dom.html.ReactHTML.span
import react.useEffect
import react.useRef
import react.useState
import web.cssom.ClassName

private val appBarScope = MainScope()

/** What the app bar says about identity when nobody is signed in (issue #276). */
const val signedOutLabel = "Signed out"

/**
 * What the app bar should say about who is signed in -- or **null to say nothing yet** (issue #276).
 *
 * Three states, and the third is the one that makes this a function rather than an `if`. The shell renders
 * before `/home/ui/config` returns, so until it does the answer is *unknown*, not "signed out": claiming
 * signed-out on the first paint would be wrong for a moment and would flicker for a signed-in user on every
 * load. [loaded] is what separates "we asked and nobody is" from "we have not asked".
 *
 * A signed-in caller is announced by their [displayName] -- the business name for an entity account, the
 * personal name otherwise, a distinction the caller resolves via `UserProfile.displayName`. A name is itself
 * the statement that somebody is signed in. When they have none, the label falls back to [signedInFallback]
 * rather than to silence -- silence is the state this whole function exists to stop being ambiguous.
 *
 * Pure, and covered under `jsNodeTest`.
 */
fun identityLabel(loaded: Boolean, isLoggedIn: Boolean, displayName: String?): String? = when {
    !loaded -> null
    !isLoggedIn -> signedOutLabel
    else -> displayName?.trim()?.ifEmpty { null } ?: signedInFallback
}

/** Shown in place of a name for a signed-in caller who has none -- still says *signed in*, which is the point. */
const val signedInFallback = "Signed in"

/**
 * What the app bar's brand area should show -- an explicit **three-state** rather than inferring from an absent
 * value (issue #469). Same reasoning as [identityLabel] one element to the left: a blank wordmark is *ambiguous*
 * between "still loading" and "the fetch failed", and #456 reintroduced exactly that ambiguity by moving the
 * wordmark into a `home.brand` fragment fetched over two round trips.
 */
sealed class ShellBrand {
    /** Neither the config nor its copy has arrived; the mark carries the bar (with a delayed progress cue). */
    object Loading : ShellBrand()
    /** Loaded. [label] is the wordmark, or null when the deployment names no brand -- then the mark stands alone. */
    class Ready(val label: String?) : ShellBrand()
    /** The copy could not be loaded (and a stale ref did not recover); mark alone, the body reports the outage. */
    object Failed : ShellBrand()
}

/**
 * The brand state to move to after a load attempt (issue #469), or **null to keep the current one**.
 *
 * A success shows the brand -- or the mark alone when it is blank, a deployment that names none. A failure
 * downgrades to [ShellBrand.Failed] only when nothing has loaded yet (`everLoaded` false); once a wordmark has shown, a
 * transient re-fetch failure keeps it -- "the previous wordmark beats no wordmark", which is what lets a refresh
 * never flicker. The initial [ShellBrand.Loading] is the `useState` default, never re-entered here. Pure, covered under
 * `jsNodeTest`.
 */
fun brandAfterLoad(loaded: Boolean, brand: String?, everLoaded: Boolean): ShellBrand? = when {
    loaded -> ShellBrand.Ready(brand?.trim()?.ifEmpty { null })
    everLoaded -> null
    else -> ShellBrand.Failed
}

/**
 * Whether a failed fragment fetch is a **stale build id** rather than a genuine outage (issue #469): a rolling
 * deploy leaves every open browser holding a ref no node recognizes, which 404s -- the ordinary case, not an
 * edge one. Distinguishable by the status alone, which is what lets the recovery ([fetchCopyWithRetry]) be
 * automatic. Pure, covered under `jsNodeTest`.
 */
fun isStaleFragment(status: Int?): Boolean = status == 404

/**
 * The two env-auth facts the bar draws (issue #360): whether the control should exist at all, and what it
 * currently says.
 *
 * Passed in rather than read from [appConfig] here, because that cache is filled **asynchronously** and
 * nothing re-renders when it lands -- a component reading it in an effect keyed on the refresh generation runs
 * before the fetch resolves and then never looks again. `App` already owns config-derived state for exactly
 * this reason (`debugAllowed`, `idleBumpIntervalMs`); these join it rather than inventing a second way.
 */
external interface AppBarProps : Props {
    /** Whether env auth exists on this channel -- decides whether the control is shown at all. */
    var envAuthSuppressible: Boolean
    /** Whether the session is currently *acting* env-authed -- decides what the control says. */
    var envAuthActing: Boolean
    /** Whether the session is in the debug state (issue #517) -- the third state the control cycles to. */
    var envAuthDebug: Boolean
}

/**
 * The persistent top app bar: a brand on the left, and a hamburger menu on the right.
 *
 * **The menu is data.** Its items come whole from the home/shell UI-config (`state.menu`), which the backend
 * composes for the *current caller* -- so what a user may reach is decided once, on the side that knows. The
 * bar renders the list it is handed, in order, and adds nothing of its own: an item this user may not have is
 * simply absent from the response. That is what lets an entry like user administration appear for an
 * administrator and for nobody else without the frontend knowing anything about roles.
 *
 * Each item carries one [MenuItem.action]: a route to navigate to, or a call to run (issue #483) -- today
 * only logging out, which cannot be a link because it is a request plus a redirect.
 *
 * It re-reads the config on every refresh generation, so signing in or out (or being granted a capability)
 * redraws the menu.
 */
val AppBar = FC<AppBarProps> { props ->
    // The one conditional fault that cannot live in a dedicated component (issue #227): proving the *backstop*
    // boundary catches means breaking the chrome, and the chrome is what renders outside the page boundary.
    // Gated on the deployment's allowDebugPages, so on a real deployment this line can never fire.
    if (shouldFailShell()) {
        error("Deliberate shell fault from the debug page (issue #227).")
    }
    var open by useState(false)
    var config by useState<HomeConfig?>(null)
    // The brand's three-state, held directly (issue #469): the effect knows which of loading/ready/failed
    // happened, so it writes the state rather than the bar inferring it from an empty [copy] -- which is
    // ambiguous, since a deployment that names no brand is *also* empty.
    var brandState by useState<ShellBrand>(ShellBrand.Loading)
    // Refs, not state: [everLoaded] is monotonic (a wordmark, once shown, is kept through a transient refresh
    // failure), and [genRef] lets a slow, superseded generation's late result bow out instead of clobbering a
    // newer one. Neither is read for rendering, so neither needs to trigger one.
    val everLoaded = useRef(false)
    val genRef = useRef(0)
    val generation = useRefreshGeneration()
    val bump = useRefreshBump()

    // Re-read the shell config on every refresh generation -- mount, navigation, and any state mutation
    // (notably sign-in / sign-out). The menu stays as it was if the config could not be loaded.
    useEffect(generation) {
        genRef.current = generation
        appBarScope.launch {
            val cfg = runCatching { HomeApi.fetchConfig() }.getOrNull()
            if (genRef.current != generation) return@launch // a newer generation is already in charge
            if (cfg == null) {
                // Config failed: Failed on a first load, but keep any wordmark already shown on a refresh.
                brandAfterLoad(loaded = false, brand = null, everLoaded = everLoaded.current == true)
                    ?.let { brandState = it }
                return@launch
            }
            config = cfg
            // The wordmark is a client's to change (issue #456), so it is re-read when the caller changes, not
            // only on mount. A stale build id (a rolling deploy) recovers silently via the shared retry.
            val loaded = runCatching {
                fetchCopyWithRetry(cfg.fragment) { runCatching { HomeApi.fetchConfig().fragment }.getOrNull() }
            }
            if (genRef.current != generation) return@launch
            val copy = loaded.getOrNull()
            if (copy != null) {
                everLoaded.current = true
            } else {
                // Never swallow (webapp/CLAUDE.md): the chrome does not *show* an outage, but a Failed that
                // renders nothing must at least reach the console, or a browser test cannot assert its absence.
                loaded.exceptionOrNull()?.let {
                    console.error("$errorLogPrefix could not load app-bar copy: ${it.message}")
                }
            }
            brandAfterLoad(copy != null, copy?.opt("home", "brand"), everLoaded.current == true)
                ?.let { brandState = it }
        }
    }

    fun logoutAction() {
        open = false
        appBarScope.launch {
            runCatching { AuthApi.logout() }
            navigateHash(emptyList())
            // Bump so the menu (and every config consumer) re-reads even when we were already home -- setting
            // the same hash fires no hashchange, which is why the direct re-read used to be needed here.
            bump()
        }
    }

    // Env logout (issue #486): clear the perimeter cookie, then leave the app for where the edge says to land.
    // Both URLs come from the call's arguments -- the edge supplies them because only it knows them -- and both
    // arrive already guarded same-origin by `FrontendActions` (issue #498). Unlike `logout`, this is a
    // full-window navigation, not a hash change: the caller is now anonymous, and the destination is served by
    // the edge rather than being a route within this app.
    fun envLogoutAction(args: List<String>) {
        open = false
        val logoutPath = args.getOrNull(0) ?: return
        val landingUrl = args.getOrNull(1) ?: return
        appBarScope.launch {
            runCatching { Http.getApi(logoutPath) }
            leaveAppTo(landingUrl)
        }
    }

    // Built after the functions they call: a local `val` cannot forward-reference a local `fun`.
    val frontendActions = FrontendActions(
        logout = { logoutAction() },
        envLogout = { args -> envLogoutAction(args) },
        // openPath leaves the SPA for a same-origin server path (issue #493). The path is already guarded
        // same-origin by FrontendActions; here it is just the navigation. Closing the menu first, as the
        // other actions do.
        openPath = { path ->
            open = false
            leaveAppTo(path)
        },
    )

    // The bar takes on the elevated-privilege look while the caller holds administrative rights. It reads the
    // same `canManageUsers` capability the menu does, so the cue and the Users item can never disagree -- and
    // when that capability narrows, the cue narrows with it.
    val elevated = config?.canManageUsers == true

    // A *delayed* loading flag so a fast load never flashes the cue (issue #469).
    val brandLoadingShown = useDelayedFlag(brandState is ShellBrand.Loading)

    header {
        className = ClassName(if (elevated) "app-bar admin" else "app-bar")
        a {
            className = ClassName("app-bar-brand")
            href = "#"
            img {
                className = ClassName("app-bar-logo")
                src = brandMarkUrl
                // Decorative: the wordmark beside it already carries the name, so alt text here would only
                // make a screen reader announce the brand twice.
                alt = ""
            }
            // Copy, not a literal (issue #456): the same `home.brand` the hero renders, so a client that
            // overlays it is renamed in both places at once and neither knows the client exists. The mark
            // (above) always carries the bar; what follows it is the wordmark's three-state (issue #469).
            //
            // A literal fallback ("KDR") is deliberately avoided -- it would be *wrong* for a moment for anybody
            // whose brand differs, and flicker on every load, the same reasoning as `identityLabel`.
            when (val b = brandState) {
                // Loaded: the wordmark, or nothing when the deployment names no brand (the mark stands alone).
                is ShellBrand.Ready -> b.label?.let { +it }
                // Failed: mark alone. The chrome is the wrong place to explain an outage -- the page body does,
                // through its error boundary; a blank bar here reads as "loading", which this exists to stop.
                ShellBrand.Failed -> {}
                // Loading: a quiet cue beside the mark, but only after a short hold so a fast load never flashes it.
                ShellBrand.Loading -> if (brandLoadingShown) {
                    span {
                        className = ClassName("app-bar-brand-loading")
                        +"…"
                    }
                }
            }
        }
        div {
            className = ClassName("app-bar-right")

            // Session status sits together on the right, beside the identity it qualifies, rather than
            // floating between the brand and the menu: the bar is `space-between`, so a loose badge in the
            // middle drifts with the window width. Read left to right, the cluster is the session's
            // attributes (env, admin) and then who it belongs to.

            // The env-auth badge, which is also the control that turns it off (issue #360). Shown whenever env
            // auth is AVAILABLE, never on the effective state -- suppress it and this is the one affordance
            // that must survive, or there is no way back.
            //
            // A button rather than a span, unlike the badges around it: this one does something, and something
            // a keyboard user must be able to reach. It names the *state*, not the action, so the bar reads as
            // a description of where you are rather than a row of commands.
            if (props.envAuthSuppressible) {
                // The click keeps its established meaning (issue #360): the suppress/restore toggle -- acting ->
                // off ("browse as an ordinary user"), off -> on. It does **not** enter debug, so an operator's
                // muscle memory cannot turn on the widened debug surface by accident; debug is reached
                // deliberately from the Debug menu (issue #517, slice 2). The label still shows the third state
                // when it is on, so the bar reads as where you are.
                val acting = props.envAuthActing
                button {
                    className = ClassName(
                        "bar-badge env-badge" + when {
                            !acting -> " off"
                            props.envAuthDebug -> " debug"
                            else -> ""
                        },
                    )
                    title = when {
                        !acting -> "Environment access is suppressed for this session. Click to restore it."
                        props.envAuthDebug ->
                            "Debug behaviors are on. Click to browse as an ordinary user (which also turns debug off)."
                        else ->
                            "You reached this deployment through an authenticated environment. Click to browse as an ordinary user."
                    }
                    onClick = {
                        appBarScope.launch {
                            setEnvAuthOp(if (acting) EnvAuthOp.suppress else EnvAuthOp.restore)
                            bump()
                        }
                    }
                    +when {
                        !acting -> "Env off"
                        props.envAuthDebug -> "Env debug"
                        else -> "Env"
                    }
                }
            }
            // Spelled out, not just colored: a hue on its own tells a colourblind user nothing, and this is
            // the cue that says "the actions available to you right now are privileged". Shares the .bar-badge
            // chip look with the env badge so the two read as siblings rather than two unrelated stickers.
            if (elevated) {
                span {
                    className = ClassName("bar-badge admin-badge")
                    +"Admin"
                }
            }
            // A quiet marker that this is the readable build (issue #230), deliberately NOT a .bar-badge chip:
            // it reports which bundle is loaded, not a fact about the session. Can never appear on a real
            // deployment, which ships the minified bundle where the check below is false by construction.
            if (isReadableBuild) {
                span {
                    className = ClassName("build-badge")
                    title = "Readable (development) web-app bundle — larger, but crashes name the Kotlin that failed."
                    +"readable build"
                }
            }
            // Identity in the bar itself, not only inside the menu (issue #276). Being signed out is a fact
            // worth stating: rendering nothing for it reads exactly like a config that has not arrived, so a
            // user cannot tell "I am signed out" from "this has not loaded". Null while genuinely unknown.
            identityLabel(config != null, config?.user?.isLoggedIn == true, config?.user?.displayName)?.let { label ->
                span {
                    className = ClassName(if (label == signedOutLabel) "identity-badge signed-out" else "identity-badge")
                    +label
                }
            }
            button {
                className = ClassName("hamburger")
                onClick = { open = !open }
                +"☰"
            }
            if (open) {
                div {
                    className = ClassName("app-menu-overlay")
                    onClick = { open = false }
                }
                div {
                    className = ClassName("app-menu")

                    // No "signed in as" label here any more (issue #276). Identity moved to the bar, which is
                    // visible whether or not the menu is open -- and the bar is still on screen while it is,
                    // so a label here only repeated the same string a few pixels away. The menu is for
                    // actions; saying who you are is the bar's job.

                    // The items themselves, exactly as the backend composed them for this caller.
                    for (menuItem in config?.menu.orEmpty()) {
                        // A route is a link and a call is a button, which is the whole of the dispatch: the
                        // backend already decided *whether* this caller sees the item, and the shape of the
                        // action says how to render it (issue #483).
                        when (val action = menuItem.action) {
                            is UiRoute -> menuLink("#page=${action.page}", menuItem.label) { open = false }
                            is UiCall -> button {
                                className = ClassName("app-menu-item")
                                // An `openPath` call leaves the SPA for a server path (issue #493). The cue is
                                // derived from the action kind, not a field on the item -- leaving the app is
                                // inherent to what `openPath` does. The ↗ glyph below is decorative; the
                                // accessible name states the departure in words, and says "leaves this app"
                                // rather than the reflexive "opens in a new tab", which would be a lie -- this
                                // is a same-window full-page navigation.
                                val leavesApp = action.def.name == HACT.openPath.name
                                if (leavesApp) {
                                    asDynamic()["aria-label"] = "${menuItem.label} (leaves this app)"
                                }
                                onClick = {
                                    open = false
                                    // An unimplemented name closes the menu and does nothing else, which is
                                    // what the startup check exists to stop ever reaching a person.
                                    frontendActions.run(action.def.name, action.args)
                                }
                                +menuItem.label
                                if (leavesApp) {
                                    span {
                                        className = ClassName("app-menu-external")
                                        asDynamic()["aria-hidden"] = true
                                        +" ↗"
                                    }
                                }
                            }
                            null -> Unit
                        }
                    }
                }
            }
        }
    }
}

/** A short delay before a loading cue appears, so a fast load never flashes it then removes it (issue #469). */
private const val brandFlashDelayMs = 200

/**
 * True only once [active] has held for [delayMs] -- and immediately false again when it clears (issue #469). The
 * conventional cure for a flashing indicator: a fast resolve never crosses the threshold, so nothing appears.
 */
fun useDelayedFlag(active: Boolean, delayMs: Int = brandFlashDelayMs): Boolean {
    var shown by useState(false)
    val timer = useRef<Int>(null)
    useEffect(active) {
        // Clear any pending timer from the previous run first, so a quick active→inactive→active never leaves
        // two racing. (This codebase does its timer cleanup on the next run rather than via an effect-cleanup
        // callback -- see the debounce in Users.kt.)
        timer.current?.let { clearTimer(it) }
        timer.current = null
        if (!active) {
            shown = false
        } else if (!shown) {
            timer.current = setTimer({ shown = true }, delayMs)
        }
    }
    return shown
}

/** The browser's `setTimeout`/`clearTimeout`, declared locally rather than reaching for a DOM wrapper. */
private fun setTimer(block: () -> Unit, delayMs: Int): Int = js("setTimeout(block, delayMs)") as Int
private fun clearTimer(id: Int) {
    js("clearTimeout(id)")
}

/** One anchor menu item; navigating by hash fires `hashchange`, which the router and this bar react to. */
private fun ChildrenBuilder.menuLink(href: String, label: String, onClick: () -> Unit) {
    a {
        className = ClassName("app-menu-item")
        this.href = href
        this.onClick = { onClick() }
        +label
    }
}
