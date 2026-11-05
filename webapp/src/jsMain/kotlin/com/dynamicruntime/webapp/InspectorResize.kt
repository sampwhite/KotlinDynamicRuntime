package com.dynamicruntime.webapp

import kotlinx.browser.document
import kotlinx.browser.window
import kotlinx.coroutines.awaitCancellation
import react.FC
import react.Props
import react.dom.html.ReactHTML.div
import react.useEffectOnce
import react.useRef
import react.useState
import web.cssom.ClassName

/*
 * Design View's inspector can be dragged wider (issue #1617). Its width is the CSS variable `--dv-panel-width` on the
 * page root, which both the panel and the room the page makes for it (`body.dv-open`) read, so setting it once moves
 * both. The stylesheet holds what is shown to the window (`--dv-panel-shown`), so a window narrowed afterwards
 * shrinks the panel with it, and widened again shows the width chosen.
 */

/** The Design View inspector's sizes and names (issue #1617). */
@Suppress("ConstPropertyName")
object DVI {
    /** The stylesheet's width for the inspector, `--dv-panel-width` in `app.css`: change the two together. */
    const val defaultWidth = 380

    /** The narrowest the inspector is dragged to; `--dv-panel-shown` in `app.css` repeats it. */
    const val minWidth = 320

    /** What the inspector leaves the form beside it, however wide; `--dv-panel-shown` in `app.css` repeats it. */
    const val formRoom = 480

    /** How far one arrow key press moves the inspector's edge. */
    const val keyStep = 24

    /** The CSS variable holding the inspector's width. */
    const val widthVar = "--dv-panel-width"

    /** Where the browser remembers the width a person dragged the inspector to. */
    const val widthKey = "kdr.designView.panelWidth"

    /** The body class held while the inspector is being dragged: no text selection, and the resize cursor throughout. */
    const val resizingClass = "dv-resizing"

    /** The most rows a JSON block in the inspector starts at, before it is dragged taller. */
    const val jsonRows = 16

    /** What a storage report calls the remembered width. */
    const val widthWhat = "the Design View inspector's width"
}

/**
 * The inspector's width for [wanted] pixels in a window [viewport] pixels wide: at least [DVI.minWidth], and at most
 * what leaves the form [DVI.formRoom] -- never under the minimum, however narrow the window. Pure, and covered under
 * `jsNodeTest`.
 */
fun inspectorWidthFor(wanted: Int, viewport: Int): Int =
    wanted.coerceIn(DVI.minWidth, maxOf(DVI.minWidth, viewport - DVI.formRoom))

/** Sets the inspector's width, or with null goes back to the stylesheet's. */
fun applyInspectorWidth(width: Int?) {
    val style = document.documentElement?.asDynamic()?.style ?: return
    if (width == null) style.removeProperty(DVI.widthVar) else style.setProperty(DVI.widthVar, "${width}px")
}

/** The width this browser remembers, or null when it remembers none (or something that is not a width). */
fun storedInspectorWidth(): Int? =
    localStorageGet(DVI.widthKey, DVI.widthWhat)?.toIntOrNull()?.takeIf { it > 0 }?.let { maxOf(it, DVI.minWidth) }

/** How many grips are mounted: more than one only for the moment a remounted inspector overlaps its last instance. */
private var mountedGrips = 0

/** A drag under way: where the pointer went down, the width then, and the width it has set since, if it moved. */
class InspectorDrag(val startX: Double, val startWidth: Int) {
    var moved: Int? = null
}

/**
 * The grip on the inspector's left edge (issue #1617). Dragged, it moves the edge by as far as the pointer moves --
 * so where on the grip it was taken, and the page's scrollbar, change nothing, and a click without a move changes
 * nothing -- and remembers the width when the drag ends, however it ends. The arrow keys move it by [DVI.keyStep]
 * while it has focus; a double-click goes back to the stylesheet's width and forgets the remembered one. Mounted with
 * the inspector, it applies the remembered width as chosen (the stylesheet holds it to the window), and unmounted --
 * Design View turned off -- it takes it away, so the page's own layout is untouched.
 */
val InspectorResizeHandle = FC<Props> {
    // The width set, or null for the stylesheet's: what the separator reports as its value.
    var width by useState(storedInspectorWidth())
    val drag = useRef<InspectorDrag>(null)

    useEffectOnce {
        mountedGrips += 1
        applyInspectorWidth(width)
        try {
            awaitCancellation()
        } finally {
            // An effect's cleanup is a cancelled coroutine, which can finish after a remounted grip has applied its
            // width: only the last grip to go takes the width away.
            mountedGrips -= 1
            if (mountedGrips == 0) {
                applyInspectorWidth(null)
                document.body?.classList?.remove(DVI.resizingClass)
            }
        }
    }

    fun set(next: Int?) {
        applyInspectorWidth(next)
        width = next
    }

    /** Sets and remembers [next], held to the window. */
    fun settle(next: Int) {
        val held = inspectorWidthFor(next, window.innerWidth)
        set(held)
        localStorageSet(DVI.widthKey, held.toString(), DVI.widthWhat)
    }

    /** Ends a drag, however it ended: remembers the width it set, if it moved at all. */
    fun finish() {
        drag.current?.moved?.let { settle(it) }
        drag.current = null
        document.body?.classList?.remove(DVI.resizingClass)
    }

    div {
        className = ClassName("dv-resize-handle")
        asDynamic()["role"] = "separator"
        asDynamic()["aria-orientation"] = "vertical"
        asDynamic()["aria-label"] = "Inspector width"
        asDynamic()["aria-valuenow"] = inspectorWidthFor(width ?: DVI.defaultWidth, window.innerWidth)
        asDynamic()["aria-valuemin"] = DVI.minWidth
        asDynamic()["aria-valuemax"] = inspectorWidthFor(Int.MAX_VALUE, window.innerWidth)
        title = "Drag to resize; double-click for the default width"
        tabIndex = 0
        onPointerDown = { e ->
            e.preventDefault()
            val target = e.currentTarget.asDynamic()
            // The width shown, which the stylesheet may have held below the one set.
            val shown = (target.parentElement?.offsetWidth as? Number)?.toInt() ?: DVI.defaultWidth
            drag.current = InspectorDrag(e.clientX, shown)
            document.body?.classList?.add(DVI.resizingClass)
            target.setPointerCapture(e.pointerId)
        }
        onPointerMove = { e ->
            drag.current?.let { d ->
                // The edge is on the panel's left: moving left widens it.
                val next = inspectorWidthFor(d.startWidth + (d.startX - e.clientX).toInt(), window.innerWidth)
                if (next != d.startWidth || d.moved != null) {
                    d.moved = next
                    set(next)
                }
            }
        }
        // Fires once a drag ends, by the pointer going up (the capture is released with it) or by the browser
        // cancelling it, so it is where a drag is finished.
        onLostPointerCapture = { finish() }
        // A drag whose capture never took hold has no capture to lose, so it ends with the pointer itself.
        onPointerUp = { e -> if (e.currentTarget.asDynamic().hasPointerCapture(e.pointerId) != true) finish() }
        onPointerCancel = { e -> if (e.currentTarget.asDynamic().hasPointerCapture(e.pointerId) != true) finish() }
        onDoubleClick = {
            set(null)
            localStorageRemove(DVI.widthKey, DVI.widthWhat)
        }
        onKeyDown = { e ->
            // The edge is on the panel's left, so Left widens it and Right narrows it.
            val step = when (e.key.toString()) {
                "ArrowLeft" -> DVI.keyStep
                "ArrowRight" -> -DVI.keyStep
                else -> 0
            }
            if (step != 0) {
                e.preventDefault()
                val shown = (e.currentTarget.asDynamic().parentElement?.offsetWidth as? Number)?.toInt() ?: DVI.defaultWidth
                settle(shown + step)
            }
        }
    }
}

/** The rows a JSON block in the inspector starts at for [text]: its lines, which never wrap, from 2 up to [DVI.jsonRows]. */
fun jsonBlockRowsFor(text: String): Int = fittedRows(text, 2, DVI.jsonRows, charsPerLine = 10_000)
