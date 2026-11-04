package com.dynamicruntime.webapp

import kotlinx.browser.document
import kotlinx.browser.window
import kotlinx.coroutines.awaitCancellation
import react.FC
import react.Props
import react.dom.html.ReactHTML.div
import react.useEffectOnce
import web.cssom.ClassName

/*
 * Design View's inspector can be dragged wider (issue #1617). Its width is the CSS variable `--dv-panel-width` on the
 * page root, which both the panel and the room the page makes for it (`body.dv-open`) read, so setting it once moves
 * both. The stylesheet holds what is shown to the window (`--dv-panel-shown`), so a window narrowed afterwards
 * shrinks the panel with it.
 */

/** The narrowest the inspector is dragged to: its content's facts grid needs about this much. */
const val inspectorMinWidth = 320

/** What the inspector leaves the form beside it, however wide it is dragged. */
const val inspectorFormRoom = 480

/** How far one arrow key press moves the inspector's edge. */
const val inspectorKeyStep = 24

/** The CSS variable holding the inspector's width (see `app.css`). */
const val inspectorWidthVar = "--dv-panel-width"

/** Where the browser remembers the width a person dragged the inspector to. */
const val inspectorWidthKey = "kdr.designView.panelWidth"

/**
 * The inspector's width for [wanted] pixels in a window [viewport] pixels wide: at least [inspectorMinWidth], and at
 * most what leaves the form [inspectorFormRoom] -- never under the minimum, however narrow the window. Pure, and
 * covered under `jsNodeTest`.
 */
fun inspectorWidthFor(wanted: Int, viewport: Int): Int =
    wanted.coerceIn(inspectorMinWidth, maxOf(inspectorMinWidth, viewport - inspectorFormRoom))

/** Sets the inspector's width, or with null goes back to the stylesheet's. */
fun applyInspectorWidth(width: Int?) {
    val style = document.documentElement?.asDynamic()?.style ?: return
    if (width == null) style.removeProperty(inspectorWidthVar) else style.setProperty(inspectorWidthVar, "${width}px")
}

/** The width this browser remembers, or null when it remembers none (or something that is not a width). */
fun storedInspectorWidth(): Int? =
    localStorageGet(inspectorWidthKey, "the Design View inspector's width")?.toIntOrNull()?.takeIf { it > 0 }

/**
 * The grip on the inspector's left edge (issue #1617). Dragged, it sets the width as the pointer moves and remembers
 * it when let go; the arrow keys move it by [inspectorKeyStep] while it has focus; a double-click goes back to the
 * stylesheet's width and forgets the remembered one. Mounted with the inspector, it applies the remembered width, and
 * unmounted -- Design View turned off -- it takes it away, so the page's own layout is untouched.
 */
val InspectorResizeHandle = FC<Props> {
    useEffectOnce {
        applyInspectorWidth(storedInspectorWidth()?.let { inspectorWidthFor(it, window.innerWidth) })
        try {
            awaitCancellation()
        } finally {
            applyInspectorWidth(null)
            document.body?.classList?.remove(resizingClass)
        }
    }

    /** Sets and remembers [wanted] pixels, held to the window. */
    fun settle(wanted: Int) {
        val width = inspectorWidthFor(wanted, window.innerWidth)
        applyInspectorWidth(width)
        localStorageSet(inspectorWidthKey, width.toString(), "the Design View inspector's width")
    }

    div {
        className = ClassName("dv-resize-handle")
        asDynamic()["role"] = "separator"
        asDynamic()["aria-orientation"] = "vertical"
        asDynamic()["aria-label"] = "Drag to resize the inspector"
        title = "Drag to resize; double-click for the default width"
        tabIndex = 0
        onPointerDown = { e ->
            e.preventDefault()
            e.currentTarget.asDynamic().setPointerCapture(e.pointerId)
            document.body?.classList?.add(resizingClass)
        }
        onPointerMove = { e ->
            if (e.currentTarget.asDynamic().hasPointerCapture(e.pointerId) == true) {
                applyInspectorWidth(inspectorWidthFor(window.innerWidth - e.clientX.toInt(), window.innerWidth))
            }
        }
        onPointerUp = { e ->
            val target = e.currentTarget.asDynamic()
            if (target.hasPointerCapture(e.pointerId) == true) {
                target.releasePointerCapture(e.pointerId)
                settle(window.innerWidth - e.clientX.toInt())
            }
            document.body?.classList?.remove(resizingClass)
        }
        onDoubleClick = {
            applyInspectorWidth(null)
            localStorageRemove(inspectorWidthKey, "the Design View inspector's width")
        }
        onKeyDown = { e ->
            // The edge is on the panel's left, so Left widens it and Right narrows it.
            val step = when (e.key.toString()) {
                "ArrowLeft" -> inspectorKeyStep
                "ArrowRight" -> -inspectorKeyStep
                else -> 0
            }
            if (step != 0) {
                e.preventDefault()
                val shown = (e.currentTarget.asDynamic().parentElement?.offsetWidth as? Number)?.toInt() ?: inspectorMinWidth
                settle(shown + step)
            }
        }
    }
}

/** The body class held while the inspector is being dragged: no text selection, and the resize cursor everywhere. */
private const val resizingClass = "dv-resizing"
