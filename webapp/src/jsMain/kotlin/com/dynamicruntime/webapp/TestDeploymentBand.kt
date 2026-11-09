package com.dynamicruntime.webapp

import com.dynamicruntime.common.app.APP
import react.FC
import react.Props
import react.dom.html.ReactHTML.div
import web.cssom.ClassName
import web.dom.document

/*
 * The band a public test deployment draws on every page (issue #1150): the deployment is reachable by the public, and
 * for testing and development only, so every caller -- env auth or not -- is told so on every page, as its mails tell
 * them at the foot of each one. Its words are the deployment's copy (issue #1697), served by the app config with the
 * flag that draws it, so a reference site can say it as a site that invites visitors would.
 */

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

/** What [TestDeploymentBand] takes: its [text], the app config's `testDeploymentBand`. */
external interface TestDeploymentBandProps : Props {
    var text: String?
}

/** The band itself: one line, fixed along the bottom edge (`.test-deployment-band`). */
val TestDeploymentBand = FC<TestDeploymentBandProps> { props ->
    div {
        className = ClassName("test-deployment-band")
        +(props.text?.ifBlank { null } ?: APP.defaultTestDeploymentBand)
    }
}
