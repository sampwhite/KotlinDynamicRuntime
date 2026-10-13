package com.dynamicruntime.webapp

import react.FC
import react.Props
import react.dom.html.ReactHTML.div
import web.cssom.ClassName
import web.dom.document

/*
 * The band a public test deployment draws on every page (issue #1150): the deployment is reachable by the public, and
 * for testing and development only, so every caller -- env auth or not -- is told so on every page, as its mails tell
 * them at the foot of each one.
 */

/** What the band says. */
const val testDeploymentBandText = "For testing and development only \u2014 data here may be reset at any time."

/**
 * The page root's attribute on a public test deployment (issue #1150). `app.css` keys off it to keep the last of a
 * page's content clear of the band fixed along the bottom edge.
 */
const val testDeploymentRootAttr = "data-test-deployment"

/** Sets or clears [testDeploymentRootAttr] on the page root, as [on] says. */
fun markPageTestDeployment(on: Boolean) {
    val root = document.documentElement
    if (on) root.setAttribute(testDeploymentRootAttr, "") else root.removeAttribute(testDeploymentRootAttr)
}

/** The band itself: one line, fixed along the bottom edge (`.test-deployment-band`). */
val TestDeploymentBand = FC<Props> {
    div {
        className = ClassName("test-deployment-band")
        +testDeploymentBandText
    }
}
