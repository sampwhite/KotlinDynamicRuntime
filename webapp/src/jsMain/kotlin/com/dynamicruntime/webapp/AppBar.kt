package com.dynamicruntime.webapp

import react.FC
import react.Props
import react.dom.html.ReactHTML.a
import react.dom.html.ReactHTML.button
import react.dom.html.ReactHTML.div
import react.dom.html.ReactHTML.header
import react.dom.html.ReactHTML.img
import react.useState
import web.cssom.ClassName

/**
 * The persistent top app bar: a brand on the left that links home, and a hamburger menu on the right whose
 * items navigate by hash. Rendered by [App] above every page. The dropdown is a lightweight custom menu (a
 * click-away overlay closes it) — an antd Dropdown would pull in the separate icon package we otherwise avoid.
 */
val AppBar = FC<Props> {
    var open by useState(false)
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
                // Click-away layer: a click anywhere outside the menu closes it.
                div {
                    className = ClassName("app-menu-overlay")
                    onClick = { open = false }
                }
                div {
                    className = ClassName("app-menu")
                    a {
                        className = ClassName("app-menu-item")
                        href = "#page=catalog"
                        onClick = { open = false }
                        +"Endpoint catalog"
                    }
                }
            }
        }
    }
}
