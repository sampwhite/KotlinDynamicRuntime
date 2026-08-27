package com.dynamicruntime.webapp

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
    var envAuthAvailable: Boolean
    /** Whether the session is currently *acting* env-authed -- decides what the control says. */
    var envAuthActing: Boolean
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
 * Each item either navigates to a page ([MenuItem.page]) or runs a client-side action ([MenuItem.action]) --
 * today only logging out, which cannot be a link because it is a request plus a redirect.
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
    var copy by useState(Copy.empty)
    val generation = useRefreshGeneration()
    val bump = useRefreshBump()

    // Re-read the shell config on every refresh generation -- mount, navigation, and any state mutation
    // (notably sign-in / sign-out). The menu stays as it was if the config could not be loaded.
    useEffect(generation) {
        appBarScope.launch {
            runCatching { HomeApi.fetchConfig() }.getOrNull()?.let {
                config = it
                // The copy alongside the config, on the same generation: the wordmark below is a client's to
                // change (issue #456), so it has to be re-read when the caller changes and not only on mount.
                // Kept as-is on a failure -- the previous wordmark beats no wordmark.
                copy = runCatching { fetchCopy(it.fragment) }.getOrNull() ?: copy
            }
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

    // Built after the function it calls: a local `val` cannot forward-reference a local `fun`.
    val frontendActions = FrontendActions(logout = { logoutAction() })

    // The bar takes on the elevated-privilege look while the caller holds administrative rights. It reads the
    // same `canManageUsers` capability the menu does, so the cue and the Users item can never disagree -- and
    // when that capability narrows, the cue narrows with it.
    val elevated = config?.canManageUsers == true


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
            // overlays it is renamed in both places at once and neither knows the client exists.
            //
            // Nothing is drawn until the copy arrives, rather than falling back to a built-in "KDR". Same
            // reasoning as `identityLabel` above: a literal would be *wrong* for a moment for anybody whose
            // brand differs, and would flicker on every load. The mark carries the bar until then, and a
            // deployment that names no brand simply has an unlettered mark.
            copy.opt("home", "brand")?.let { +it }
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
            if (props.envAuthAvailable) {
                button {
                    className = ClassName(if (props.envAuthActing) "bar-badge env-badge" else "bar-badge env-badge off")
                    title = if (props.envAuthActing) {
                        "You reached this deployment through an authenticated environment. Click to browse as an ordinary user."
                    } else {
                        "Environment access is suppressed for this session. Click to restore it."
                    }
                    onClick = {
                        appBarScope.launch {
                            setEnvAuthSuppressed(props.envAuthActing)
                            bump()
                        }
                    }
                    +(if (props.envAuthActing) "Env" else "Env off")
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
                                onClick = {
                                    open = false
                                    // An unimplemented name closes the menu and does nothing else, which is
                                    // what the startup check exists to stop ever reaching a person.
                                    frontendActions.run(action.def.name, action.args)
                                }
                                +menuItem.label
                            }
                            null -> Unit
                        }
                    }
                }
            }
        }
    }
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
