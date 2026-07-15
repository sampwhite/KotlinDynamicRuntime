package com.dynamicruntime.webapp

import react.FC
import react.Props
import react.dom.html.ReactHTML.div
import react.dom.html.ReactHTML.h1
import web.cssom.ClassName

/**
 * The landing page. Intentionally minimal — navigation lives in the [AppBar]'s menu (which will also carry
 * login/logout), so the page body carries no links of its own.
 */
val Home = FC<Props> {
    div {
        className = ClassName("card")
        h1 { +"Welcome" }
    }
}
