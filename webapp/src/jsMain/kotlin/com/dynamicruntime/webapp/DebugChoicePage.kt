package com.dynamicruntime.webapp

import com.dynamicruntime.common.home.HMENU
import kotlinx.coroutines.awaitCancellation
import react.ChildrenBuilder
import react.FC
import react.Props
import react.dom.html.ReactHTML.div
import react.dom.html.ReactHTML.h1
import react.dom.html.ReactHTML.h2
import react.dom.html.ReactHTML.p
import react.dom.html.ReactHTML.pre
import react.dom.html.ReactHTML.span
import react.useEffectOnce
import react.useState
import web.cssom.ClassName
import web.dom.ElementId

// The choice-widget debug tool: a minimal, instrumented reproduction of the antd choice controls that browser
// automation has struggled to drive (a plain `Select`, above all -- see `webapp/CLAUDE.md`, "Choice widgets").
// It exists to turn "the value never changed" into a trace: which DOM events actually reached which element,
// whether the popup opened, and whether antd committed a selection. Each control is laid out beside a plain
// readout of its committed value, so an observer can read the state without trusting the widget's own display.

/** The id of the tool's root, which the document-level event trace uses to know what is "inside" the tool. */
private const val choiceRootId = "dbg-choice-root"

/** How many trace lines are kept; older ones scroll off the top. */
private const val maxTraceLines = 80

/**
 * The events traced, in the order a click delivers them. Listened for on the **document** in the capture phase
 * rather than on the tool's own element: a Select's popup is a portal rendered under `body`, so a listener on
 * the tool would never see an option being clicked -- the very thing the trace is for.
 */
private val tracedEventTypes = listOf("pointerdown", "mousedown", "mouseup", "click", "focusin", "keydown")

/** The three choices every case offers. One shared array: antd reads it and never writes it. */
private val demoOptions: Array<dynamic> = listOf("alpha" to "Alpha", "beta" to "Beta", "gamma" to "Gamma")
    .map { (value, label) ->
        val o: dynamic = js("({})")
        o.label = label
        o.value = value
        o
    }.toTypedArray()

/**
 * Whether a DOM event target is something this tool wants traced: inside the element with id [rootId] (the
 * tool's root), or inside any Select popup (which is *not* inside the root, being a portal).
 *
 * The id is a parameter rather than the constant read directly: a `js(...)` block can name a function's
 * parameters, but a Kotlin `const val` has no JS identifier at runtime, and reading one throws a
 * `ReferenceError` from inside the event listener -- observed, on the first run of this page.
 */
private fun isTracedTarget(t: dynamic, rootId: String): Boolean = js(
    """
    !!(t && t.closest && (t.closest('#' + rootId) || t.closest('.ant-select-dropdown')))
    """,
) as Boolean

/**
 * One line naming an event target precisely enough to tell the two kinds of option apart: the tag, its id,
 * its `role`, its `ant-select` classes, and -- the point -- whether it sits in a **zero-size accessibility
 * mirror** (a `role="listbox"` with no height or width, which is what antd's virtual list renders the
 * `role="option"` items into) or in the visible popup.
 */
private fun describeTarget(t: dynamic): String = js(
    // The function's own parameter is `el`, not `t`: a `js(...)` block that declares a name the Kotlin
    // function also uses makes the compiler rename the Kotlin parameter to keep them apart, and the outer
    // `(t)` then names nothing -- a `ReferenceError` at runtime, observed on this page's second run.
    """
    (function (el) {
        if (!el || !el.tagName) return '?';
        var s = el.tagName.toLowerCase();
        if (el.id) s += '#' + el.id;
        var role = el.getAttribute('role');
        if (role) s += '[role=' + role + ']';
        var cls = (typeof el.className === 'string')
            ? el.className.split(' ').filter(function (c) { return c.indexOf('ant-select') === 0; }).slice(0, 2).join(' .')
            : '';
        if (cls) s += ' .' + cls;
        var lb = el.closest('[role=listbox]');
        if (lb && lb.offsetHeight === 0 && lb.offsetWidth === 0) s += '  <-- in the 0x0 a11y mirror';
        else if (el.closest('.ant-select-dropdown')) s += '  <-- in the visible popup';
        return s;
    })(t)
    """,
) as String

/** A committed value as trace text: a string, an array joined, or `(cleared)`. */
private fun valueText(v: dynamic): String = when {
    v == null || v == undefined -> "(cleared)"
    js("Array.isArray(v)") as Boolean -> "[" + (v as Array<*>).joinToString(", ") + "]"
    else -> v.toString()
}

/**
 * The choice-widget reproduction. Five cases, each isolating one variable, with the known-drivable
 * `AutoComplete` as the positive control:
 *
 *  - **single** -- a plain `Select`, the control that has resisted automation;
 *  - **multiple** -- the same in `mode="multiple"`;
 *  - **non-virtual** -- a plain `Select` with `virtual = false`, which moves the `role="option"` items out of
 *    the zero-size accessibility mirror onto the visible, clickable elements;
 *  - **motion off** -- a plain `Select` under a `ConfigProvider` whose `motion` token is false, so its popup
 *    skips antd's frame-driven open animation: in a **hidden** tab `requestAnimationFrame` never fires, and
 *    an animated popup stays frozen at the animation's first frame, 0x0, where nothing in it can be clicked;
 *  - **search** -- a `Select` with `showSearch`, whose control is a real `<input>`;
 *  - **autocomplete** -- an `AutoComplete`, which automation is known to drive.
 *
 * Every Select carries an `id`, so its inner input is `<id>` and its popup's listbox `<id>_list`. The trace
 * below the cases records what reached the page; each case's readout records what antd committed.
 */
val DebugChoice = FC<Props> {
    // Tuple form: the document listeners are attached once and would otherwise close over the first render's
    // empty trace, so every append is a functional update.
    val (trace, setTrace) = useState<List<String>>(emptyList())
    var single by useState<String?>(null)
    var multiple by useState<List<String>>(emptyList())
    var nonVirtual by useState<String?>(null)
    var noMotion by useState<String?>(null)
    var search by useState<String?>(null)
    var autocomplete by useState<String?>(null)

    fun note(line: String) = setTrace { (it + line).takeLast(maxTraceLines) }

    useEffectOnce {
        val doc: dynamic = js("document")
        val handler: (dynamic) -> Unit = { e ->
            val t = e.target
            if (isTracedTarget(t, choiceRootId)) {
                val key = if (e.type == "keydown") " key=${e.key}" else ""
                note("${e.type}$key -> ${describeTarget(t)}")
            }
        }
        // Capture phase, so the trace sees the event before any handler can stop it.
        tracedEventTypes.forEach { doc.addEventListener(it, handler, true) }
        // In this wrappers version an effect is a coroutine that React **cancels** on unmount -- that is its
        // cleanup mechanism (there is no `cleanup {}` builder). So the tear-down is: suspend until cancelled,
        // and remove the listeners in `finally`, which is what keeps a revisit from tracing every event twice.
        try {
            awaitCancellation()
        } finally {
            tracedEventTypes.forEach { doc.removeEventListener(it, handler, true) }
        }
    }

    div {
        className = ClassName("card wide")
        id = ElementId(choiceRootId)
        backToListing(HMENU.pageDebug)
        h1 { +"Choice widgets" }
        p {
            className = ClassName("subtitle")
            +("A minimal reproduction of the antd choice controls, instrumented. Drive one, then read the trace: " +
                "which events reached which element, whether the popup opened, and what was committed. Each " +
                "case's readout is plain text, independent of the widget's own display.")
        }

        choiceCase("single", "Plain Select", "the control that has resisted automation", single ?: "(none)") {
            Select {
                id = "dbg-select-single"
                options = demoOptions
                value = single
                placeholder = "(choose)"
                allowClear = true
                style = js("({ minWidth: 200 })")
                onOpenChange = { open -> note("single: onOpenChange(open=$open)") }
                onChange = { v -> single = v as? String; note("single: onChange(${valueText(v)})") }
            }
        }

        choiceCase("multiple", "Select, mode=multiple", "several picks", multiple.ifEmpty { listOf("(none)") }.joinToString(", ")) {
            Select {
                id = "dbg-select-multiple"
                mode = "multiple"
                options = demoOptions
                value = multiple.toTypedArray()
                placeholder = "(choose)"
                style = js("({ minWidth: 200 })")
                onOpenChange = { open -> note("multiple: onOpenChange(open=$open)") }
                onChange = { v ->
                    multiple = (v as? Array<*>)?.mapNotNull { it as? String } ?: emptyList()
                    note("multiple: onChange(${valueText(v)})")
                }
            }
        }

        choiceCase(
            "nonvirtual", "Select, virtual=false",
            "the role=\"option\" items on the visible elements instead of in the a11y mirror", nonVirtual ?: "(none)",
        ) {
            Select {
                id = "dbg-select-nonvirtual"
                virtual = false
                options = demoOptions
                value = nonVirtual
                placeholder = "(choose)"
                allowClear = true
                style = js("({ minWidth: 200 })")
                onOpenChange = { open -> note("nonvirtual: onOpenChange(open=$open)") }
                onChange = { v -> nonVirtual = v as? String; note("nonvirtual: onChange(${valueText(v)})") }
            }
        }

        choiceCase(
            "nomotion", "Select, motion off",
            "antd's frame-driven popup animation, which a hidden tab never advances past its 0x0 first frame",
            noMotion ?: "(none)",
        ) {
            // A nested ConfigProvider merges with the app's dark theme; only the `motion` token is changed here.
            ConfigProvider {
                theme = js("({ token: { motion: false } })")
                Select {
                    id = "dbg-select-nomotion"
                    options = demoOptions
                    value = noMotion
                    placeholder = "(choose)"
                    allowClear = true
                    style = js("({ minWidth: 200 })")
                    onOpenChange = { open -> note("nomotion: onOpenChange(open=$open)") }
                    onChange = { v -> noMotion = v as? String; note("nomotion: onChange(${valueText(v)})") }
                }
            }
        }

        choiceCase("search", "Select, showSearch", "a real <input> as the control", search ?: "(none)") {
            Select {
                id = "dbg-select-search"
                showSearch = true
                options = demoOptions
                value = search
                placeholder = "(type or choose)"
                allowClear = true
                style = js("({ minWidth: 200 })")
                onOpenChange = { open -> note("search: onOpenChange(open=$open)") }
                onChange = { v -> search = v as? String; note("search: onChange(${valueText(v)})") }
            }
        }

        choiceCase("autocomplete", "AutoComplete", "the positive control -- known to be drivable", autocomplete ?: "(none)") {
            AutoComplete {
                id = "dbg-autocomplete"
                options = demoOptions
                value = autocomplete
                placeholder = "(type or choose)"
                allowClear = true
                filterOption = false
                style = js("({ minWidth: 200 })")
                onOpenChange = { open -> note("autocomplete: onOpenChange(open=$open)") }
                onChange = { v -> autocomplete = (v as? String)?.ifEmpty { null }; note("autocomplete: onChange(${valueText(v)})") }
            }
        }

        h2 { +"Event trace" }
        p {
            className = ClassName("subtitle")
            +("Document-level, capture phase, so the popup's portal is seen too. Newest at the bottom; the last " +
                "$maxTraceLines lines are kept.")
        }
        Button {
            onClick = { setTrace { emptyList() } }
            +"Clear trace"
        }
        pre {
            className = ClassName("code")
            id = ElementId("dbg-choice-trace")
            +trace.ifEmpty { listOf("(nothing yet)") }.joinToString("\n")
        }
    }
}

/**
 * One case: a heading naming what it isolates, the control, and a plain-text readout of the committed value
 * under a stable id (`dbg-<key>-value`) so the state can be read without trusting the widget.
 */
private fun ChildrenBuilder.choiceCase(
    key: String,
    title: String,
    isolates: String,
    readout: String,
    control: ChildrenBuilder.() -> Unit,
) {
    div {
        id = ElementId("dbg-case-$key")
        h2 { +title }
        p {
            className = ClassName("subtitle")
            +"Isolates: $isolates."
        }
        control()
        p {
            span {
                id = ElementId("dbg-$key-value")
                +"committed: $readout"
            }
        }
    }
}
