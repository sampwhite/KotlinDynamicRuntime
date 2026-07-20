package com.dynamicruntime.webapp

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
 * The persistent top app bar: a brand on the left, and a hamburger menu on the right. The menu is *composed*
 * (issue #81): the bar owns the container and the fixed items (the endpoint catalog), while the **account**
 * items come from the `auth` widget-group's UI-config — Log in / Register when signed out, "Signed in as …" /
 * Log out when signed in. It re-reads the auth config on every hash navigation, so signing in or out updates
 * the menu. Copy comes from the `auth` Markdown fragment file.
 */
val AppBar = FC<Props> {
    var open by useState(false)
    var config by useState<AuthConfig?>(null)
    var copy by useState(Copy.empty)
    val generation = useRefreshGeneration()
    val bump = useRefreshBump()

    // Re-read the account menu's auth config on every refresh generation -- mount, navigation, and any state
    // mutation (notably sign-in / sign-out). The menu degrades to signed-out defaults if the config can't load.
    useEffect(generation) {
        appBarScope.launch {
            val c = runCatching { AuthApi.fetchConfig() }.getOrNull() ?: return@launch
            config = c
            copy = runCatching { fetchCopy(c.fragment) }.getOrDefault(copy)
        }
    }

    /** The bar's copy all sits in the auth fragment's `menu` namespace, so the namespace is implied here. */
    fun t(key: String, dflt: String): String = copy.t("menu", key, dflt)

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

                    // Fixed chrome item.
                    menuLink("#page=catalog", "Endpoint catalog") { open = false }

                    // Account items, supplied by the auth config.
                    val user = config?.user
                    if (user != null && user.isLoggedIn) {
                        span {
                            className = ClassName("app-menu-label")
                            // Markdown, so the copy can set the name apart; MarkdownInline renders a span,
                            // which (unlike a div) is valid inside this one.
                            MarkdownInline {
                                source = t("signedInAs", $$"Signed in as **${user.publicName}**")
                                    .evalTemplate(mapOf("user" to mapOf("publicName" to (user.publicName ?: "your account"))))
                            }
                        }
                        menuLink("#page=profile", t("profile", "Profile")) { open = false }
                        button {
                            className = ClassName("app-menu-item")
                            onClick = { logout() }
                            +t("logout", "Log out")
                        }
                    } else {
                        menuLink("#page=login", t("login", "Log in")) { open = false }
                        if (config?.features?.registration == true) {
                            menuLink("#page=register", t("register", "Register")) { open = false }
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
