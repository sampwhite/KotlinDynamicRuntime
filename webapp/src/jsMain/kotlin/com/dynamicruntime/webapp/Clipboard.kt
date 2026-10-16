package com.dynamicruntime.webapp

import kotlinx.browser.window
import react.FC
import react.Props
import react.dom.html.ReactHTML.span
import react.useEffect
import react.useRef
import react.useState
import web.cssom.ClassName

/**
 * Puts [text] on the clipboard, then calls [done] -- or [refused] when the browser will not: the document not
 * focused, the permission denied, or no clipboard at all (an insecure origin). The one place the app writes to the
 * clipboard, for the code blocks' copy button and [CopyButton] alike.
 */
fun writeToClipboard(text: String, done: () -> Unit, refused: () -> Unit) {
    val clipboard = window.navigator.asDynamic().clipboard
    if (clipboard == null) {
        refused()
        return
    }
    clipboard.writeText(text).then({ _: dynamic -> done() }, { _: dynamic -> refused() })
}

/** What a [CopyButton] says after a click (issue #1190): in words, never by a mark alone. Pure, and covered under `jsNodeTest`. */
fun copyOutcomeText(copied: Boolean?): String? = when (copied) {
    true -> "Copied."
    false -> "Couldn't copy it: select it and copy instead."
    null -> null
}

external interface CopyButtonProps : Props {
    /** What is put on the clipboard. */
    var text: String
    /** What the copy is of, for whoever does not see the button: "Copy the address". */
    var label: String
}

/**
 * A small button beside text a person will want elsewhere (issue #1190) -- an address, an id -- that copies it and
 * says so beside itself for a moment ([copyOutcomeText]), or says it could not. The text it copies stays selectable
 * where it is drawn, which is the way round a refusal.
 */
val CopyButton = FC<CopyButtonProps> { props ->
    var copied by useState<Boolean?>(null)
    val timer = useRef<Int>(null)
    // A new text is a new thing to copy: what was said of the last is not said of it.
    useEffect(props.text) { copied = null }
    useEffect(copied) {
        // Cleared on the next run, as the app's timers are, so a second click restarts the moment it is said for.
        timer.current?.let { window.clearTimeout(it) }
        timer.current = if (copied == true) window.setTimeout({ copied = null }, copiedShownMs) else null
    }
    Button {
        size = "small"
        asDynamic()["title"] = props.label
        asDynamic()["aria-label"] = props.label
        onClick = { writeToClipboard(props.text, done = { copied = true }, refused = { copied = false }) }
        +"Copy"
    }
    // Always in the page, empty until there is something to say: a live region is announced when what it holds
    // changes, and one that arrives already holding its words is not.
    span {
        className = ClassName(if (copied == false) "copy-outcome error-text" else "copy-outcome")
        asDynamic()["aria-live"] = "polite"
        copyOutcomeText(copied)?.let { +it }
    }
}
