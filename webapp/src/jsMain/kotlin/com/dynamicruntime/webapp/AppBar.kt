package com.dynamicruntime.webapp

import com.dynamicruntime.common.home.HACT
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
 * A signed-in caller is announced by their [publicName], because a name is itself the statement that somebody
 * is signed in. When they have none, the label falls back to [signedInFallback] rather than to silence --
 * silence is the state this whole function exists to stop being ambiguous.
 *
 * Pure, and covered under `jsNodeTest`.
 */
fun identityLabel(loaded: Boolean, isLoggedIn: Boolean, publicName: String?): String? = when {
    !loaded -> null
    !isLoggedIn -> signedOutLabel
    else -> publicName?.trim()?.ifEmpty { null } ?: signedInFallback
}

/** Shown in place of a name for a signed-in caller who has none -- still says *signed in*, which is the point. */
const val signedInFallback = "Signed in"

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
val AppBar = FC<Props> {
    // The one conditional fault that cannot live in a dedicated component (issue #227): proving the *backstop*
    // boundary catches means breaking the chrome, and the chrome is what renders outside the page boundary.
    // Gated on the deployment's allowDebugPages, so on a real deployment this line can never fire.
    if (shouldFailShell()) {
        error("Deliberate shell fault from the debug page (issue #227).")
    }
    var open by useState(false)
    var config by useState<HomeConfig?>(null)
    val generation = useRefreshGeneration()
    val bump = useRefreshBump()

    // Re-read the shell config on every refresh generation -- mount, navigation, and any state mutation
    // (notably sign-in / sign-out). The menu stays as it was if the config could not be loaded.
    useEffect(generation) {
        appBarScope.launch {
            runCatching { HomeApi.fetchConfig() }.getOrNull()?.let { config = it }
        }
    }

    fun logout() {
        open = false
        appBarScope.launch {
            runCatching { AuthApi.logout() }
            navigateHash(emptyList())
            // Bump so the menu (and every config consumer) re-reads even when we were already home -- setting
            // the same hash fires no hashchange, which is why the direct re-read used to be needed here.
            bump()
        }
    }

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
                // Decorative: the wordmark beside it already says "KDR", so alt text here would only make a
                // screen reader announce the brand twice.
                alt = ""
            }
            +"KDR"
        }
        if (elevated) {
            // Spelled out, not just colored: a hue on its own tells a colourblind user nothing, and this is
            // the cue that says "the actions available to you right now are privileged".
            span {
                className = ClassName("admin-badge")
                +"Admin"
            }
        }
        // A quiet marker that this is the readable build (issue #230). It can never appear on a real
        // deployment: that ships the minified bundle, where the check below is false by construction.
        if (isReadableBuild) {
            span {
                className = ClassName("build-badge")
                title = "Readable (development) web-app bundle — larger, but crashes name the Kotlin that failed."
                +"readable build"
            }
        }
        div {
            className = ClassName("app-bar-right")
            // Identity in the bar itself, not only inside the menu (issue #276). Being signed out is a fact
            // worth stating: rendering nothing for it reads exactly like a config that has not arrived, so a
            // user cannot tell "I am signed out" from "this has not loaded". Null while genuinely unknown.
            identityLabel(config != null, config?.user?.isLoggedIn == true, config?.user?.publicName)?.let { label ->
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
                        when {
                            menuItem.action == HACT.logout -> button {
                                className = ClassName("app-menu-item")
                                onClick = { logout() }
                                +menuItem.label
                            }
                            menuItem.page != null -> menuLink("#page=${menuItem.page}", menuItem.label) {
                                open = false
                            }
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
