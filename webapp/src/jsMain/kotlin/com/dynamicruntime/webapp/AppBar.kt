package com.dynamicruntime.webapp

import com.dynamicruntime.common.home.HACT
import com.dynamicruntime.common.util.evalTemplate
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
    var open by useState(false)
    var config by useState<HomeConfig?>(null)
    val generation = useRefreshGeneration()
    val bump = useRefreshBump()

    // Re-read the shell config on every refresh generation -- mount, navigation, and any state mutation
    // (notably sign-in / sign-out). The menu stays as it was if the config cannot be loaded.
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

    header {
        className = ClassName("app-bar")
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
        div {
            className = ClassName("app-bar-right")
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

                    // Who is signed in, above their items. Shown only when signed in: the anonymous profile
                    // has no name worth announcing.
                    val user = config?.user
                    if (user != null && user.isLoggedIn) {
                        span {
                            className = ClassName("app-menu-label")
                            // Markdown, so the copy can set the name apart; MarkdownInline renders a span,
                            // which (unlike a div) is valid inside this one.
                            MarkdownInline {
                                source = $$"Signed in as **${user.publicName}**"
                                    .evalTemplate(mapOf("user" to mapOf("publicName" to (user.publicName ?: "your account"))))
                            }
                        }
                    }

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
