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
import react.dom.html.ReactHTML.span
import react.useEffectOnce
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
    var copy by useState<Map<String, Map<String, String>>>(emptyMap())

    useEffectOnce {
        appBarScope.launch {
            try {
                val c = AuthApi.fetchConfig()
                config = c
                copy = AuthApi.fetchFragments(c.fragmentFileId, c.fragmentBuildId)
            } catch (_: Throwable) {
                // The menu degrades to signed-out defaults if the config can't load; not worth surfacing here.
            }
        }
        // Re-read auth state after any cross-page navigation (notably right after sign-in / sign-out).
        onHashChange {
            appBarScope.launch {
                config = runCatching { AuthApi.fetchConfig() }.getOrNull() ?: config
            }
        }
    }

    fun t(key: String, dflt: String): String = copy["menu"]?.get(key) ?: dflt

    fun logout() {
        open = false
        appBarScope.launch {
            runCatching { AuthApi.logout() }
            // Refresh the menu directly: navigating home may be a no-op (already there), firing no hashchange.
            config = runCatching { AuthApi.fetchConfig() }.getOrNull() ?: config
            navigateHash(emptyList())
        }
    }

    header {
        className = ClassName("app-bar")
        a {
            className = ClassName("app-bar-brand")
            href = "#"
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
                            +t("signedInAs", "Signed in as \${user.publicName}")
                                .evalTemplate(mapOf("user" to mapOf("publicName" to (user.publicName ?: "your account"))))
                        }
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
