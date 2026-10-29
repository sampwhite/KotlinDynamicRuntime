package com.dynamicruntime.webapp

import kotlinx.browser.document
import react.ChildrenBuilder
import react.FC
import react.Key
import react.Props
import react.PropsWithChildren
import react.dom.html.ReactHTML.a
import react.dom.html.ReactHTML.details
import react.dom.html.ReactHTML.div
import react.dom.html.ReactHTML.nav
import react.dom.html.ReactHTML.p
import react.dom.html.ReactHTML.section
import react.dom.html.ReactHTML.span
import react.dom.html.ReactHTML.summary
import react.useEffect
import react.useState
import web.cssom.ClassName
import web.dom.ElementId

/*
 * The building blocks of a page's structure (issue #1477), shared so every page reads the same way: see "Page
 * structure: panels, drafts, and live actions" in the webapp's CLAUDE.md for the rules they serve.
 */

/** The words the building blocks draw, the same on every page. */
@Suppress("ConstPropertyName")
object PNL {
    /** Beside a draft panel's heading while it holds edits nobody has saved. */
    const val unsaved = "Unsaved changes"

    /** Under a live panel's heading: nothing in it waits for a save. */
    const val liveNote = "Changes here take effect immediately."

    /** The summary of a [MoreOptions] disclosure, before what is set in it. */
    const val moreOptions = "More options"
}

/**
 * What a panel holds, which decides how its edits take effect (issue #1477). A closed operational set: each is a
 * different rule for the controls inside it.
 */
@Suppress("EnumEntryName")
enum class PanelKind {
    /** Read, or a section holding editors of its own that each follow the rules (the client page's panels). */
    view,

    /** Edits wait for the panel's one save; its heading says so while any are unsaved. */
    draft,

    /** Each control acts at once; a line under the heading says so. */
    live,

    /** The last panel, holding what cannot be undone, set apart in the danger color. */
    danger,
}

/**
 * A panel of a page (issues #1397, #1477): a keyed `section` set apart from the others, headed by [heading] with its
 * "What is this?" [explanation] (none when blank), its element id [id] what a [panelLink] leads to. A [PanelKind.draft]
 * panel marks its heading while [dirty]; a [PanelKind.live] one says under it that its changes take effect at once.
 */
fun ChildrenBuilder.panel(
    id: String,
    heading: String,
    explanation: String = "",
    kind: PanelKind = PanelKind.view,
    dirty: Boolean = false,
    body: ChildrenBuilder.() -> Unit,
) {
    section {
        key = id.unsafeCast<Key>()
        this.id = ElementId(id)
        className = ClassName(panelClasses(kind))
        WhatIsThis {
            this.heading = heading
            text = explanation
            marker = PNL.unsaved.takeIf { kind == PanelKind.draft && dirty }
            subline = PNL.liveNote.takeIf { kind == PanelKind.live }
        }
        body()
    }
}

/** A panel's CSS classes for its [kind]. Pure, covered under `jsNodeTest`. */
fun panelClasses(kind: PanelKind): String = when (kind) {
    PanelKind.danger -> "panel panel-danger"
    else -> "panel"
}

/**
 * A link to a panel of the page (issue #1397), scrolled to rather than navigated to: the address is the page's, and a
 * hash would be read as one. To the panel's top, which its `scroll-margin-top` keeps clear of the fixed app bar.
 */
fun ChildrenBuilder.panelLink(id: String, text: String) {
    a {
        className = ClassName("wf-cell-link")
        href = "#"
        onClick = { e ->
            e.preventDefault()
            document.getElementById(id)?.asDynamic()?.scrollIntoView(js("({ block: 'start', behavior: 'smooth' })"))
        }
        +text
    }
}

/** The row of links at a page's top, one per panel -- [panels] as `id to heading` -- for a page long enough to want them. */
fun ChildrenBuilder.panelLinks(panels: List<Pair<String, String>>) {
    nav {
        className = ClassName("panel-links")
        panels.forEach { (id, title) ->
            span {
                key = id.unsafeCast<Key>()
                panelLink(id, title)
            }
        }
    }
}

external interface DisclosureProps : PropsWithChildren {
    /** The line the closed disclosure shows, which opens it. */
    var summaryText: String

    /** Open to start, as one just added is (issue #1460); closed when unset. */
    var initiallyOpen: Boolean?

    /**
     * Opens it whenever this turns true (issue #1477), and never closes it: what is in force inside must not be out of
     * sight, but a reader who opened it keeps it open.
     */
    var openWhen: Boolean?
}

/**
 * A section of the page shown on request (issue #1397), its contents drawn only once opened: the menu's editor,
 * whose table is the page's longest and rarely changed, reads nothing until somebody asks for it. Closed to start
 * unless [DisclosureProps.initiallyOpen], and opened by [DisclosureProps.openWhen] as [MoreOptions] uses it.
 */
val Disclosure = FC<DisclosureProps> { props ->
    var expanded by useState(props.initiallyOpen == true || props.openWhen == true)
    useEffect(props.openWhen) {
        if (props.openWhen == true) expanded = true
    }
    details {
        className = ClassName("disclosure")
        this.open = expanded
        onToggle = { e -> expanded = e.currentTarget.asDynamic().open == true }
        summary { +props.summaryText }
        if (expanded) +props.children
    }
}

external interface MoreOptionsProps : PropsWithChildren {
    /**
     * The record the options belong to (a user's id, say). Another record starts the disclosure afresh -- open when its
     * own options are set -- rather than keeping how the reader left the last one, which could hide a setting in effect.
     */
    var recordKey: String

    /**
     * What is set inside, each said shortly ("Editor", "labels: reviewer"); empty when everything is at its default.
     * It is the summary line, and it opens the disclosure: one list, so the two cannot disagree.
     */
    var inForce: List<String>
}

/**
 * A draft panel's less common fields (issue #1477, rule 5): closed while every one is at its default, open -- and its
 * summary naming them -- once any is not, so a setting in effect is never out of sight. Each option inside carries a
 * one-line hint beginning "Use this when".
 */
val MoreOptions = FC<MoreOptionsProps> { props ->
    Disclosure {
        key = props.recordKey.unsafeCast<Key>()
        summaryText = moreOptionsSummary(props.inForce)
        openWhen = moreOptionsOpens(props.inForce)
        +props.children
    }
}

/** A [MoreOptions] summary: `More options`, or `More options · Editor, labels: reviewer`. Pure, under `jsNodeTest`. */
fun moreOptionsSummary(inForce: List<String>): String =
    if (inForce.isEmpty()) PNL.moreOptions else "${PNL.moreOptions} · ${inForce.joinToString(", ")}"

/** Whether a [MoreOptions] opens itself: when anything inside is set. Pure, covered under `jsNodeTest`. */
fun moreOptionsOpens(inForce: List<String>): Boolean = inForce.isNotEmpty()

external interface SaveBarProps : Props {
    /** What is pending, said before the buttons ("2 keys changed."); none for a footer whose panel says it. */
    var pendingText: String?

    /** The primary verb, naming what it saves ("Save user", "Save name"). Never "OK". */
    var saveText: String

    /**
     * The way back: "Cancel" (the default) or "Discard". Link-styled: it drops the draft and leaves the save, as a back
     * link leaves a page.
     */
    var discardText: String?

    /** How a save takes effect, in the style of `saveEffectText`, said with the buttons. */
    var effectText: String?

    var saving: Boolean

    /**
     * Whether there is anything to save. Save is disabled unless this is true, so a footer that forgets it shows a Save
     * that cannot be pressed -- noticed at once -- rather than one pressable before anything changed.
     */
    var canSave: Boolean?

    /** The save's failure, drawn here, where the save was asked for. */
    var error: DisplayError?

    /** Leads the failure ("Couldn't save the copy."). */
    var errorLead: String?

    /**
     * In view at the window's bottom while the page scrolls -- for a draft too long for its footer to stay in sight, as
     * a copy file is. Default false.
     */
    var sticky: Boolean?

    var onSave: () -> Unit
    var onDiscard: () -> Unit
}

/**
 * A draft's one save (issue #1477, rule 2): its primary verb, the way back, how the save takes effect, and its
 * failure. A footer at the bottom of the panel it covers; [SaveBarProps.sticky] for a draft spanning several panels,
 * as the copy editor's does.
 */
val SaveBar = FC<SaveBarProps> { props ->
    div {
        className = ClassName(if (props.sticky == true) "save-bar save-bar-sticky" else "save-bar")
        props.pendingText?.let { span { +it } }
        Button {
            type = "primary"
            loading = props.saving
            disabled = props.canSave != true
            onClick = { props.onSave() }
            +props.saveText
        }
        Button {
            type = "link"
            disabled = props.saving
            onClick = { props.onDiscard() }
            +(props.discardText ?: "Cancel")
        }
        props.effectText?.let {
            p {
                className = ClassName("type-hint")
                +it
            }
        }
        props.error?.let { error -> props.errorLead?.let { errorText(it, error) } ?: errorText(error) }
    }
}

/**
 * A panel's own result line (issue #1477, rule 3): its failure, led by [errorLead] when given, else its [note] -- drawn
 * inside the panel whose action produced it, not in one line shared by the page.
 */
fun ChildrenBuilder.panelNote(error: DisplayError?, note: String?, errorLead: String? = null) {
    if (error != null) {
        if (errorLead != null) errorText(errorLead, error) else errorText(error)
    } else {
        note?.let {
            p {
                className = ClassName("form-ok")
                +it
            }
        }
    }
}

external interface DangerConfirmProps : Props {
    /**
     * The record it acts on (a user's id, say). Armed for one record, it is not armed for the next: a page that swaps
     * the record in place shows the first press again, never a confirm left waiting by the last one.
     */
    var recordKey: String

    /** The danger button that arms it, ending in an ellipsis since it asks first ("Delete user…"). */
    var actionText: String

    /** The question asked once armed, naming where it acts (`deleteTargetText`, issue #1049). */
    var question: String

    /** The button that does it ("Confirm permanent delete"). */
    var confirmText: String

    /** The way back ("Keep user"). */
    var keepText: String

    var busy: Boolean?

    var onConfirm: () -> Unit
}

/**
 * The one confirmation for what cannot be undone (issue #1477, rule 6): a danger button that, pressed, is replaced in
 * place by the question, the danger confirm, and the way back -- two deliberate presses, the second beside the words
 * saying what it does. Armed only for the record it was pressed for ([DangerConfirmProps.recordKey]).
 */
val DangerConfirm = FC<DangerConfirmProps> { props ->
    // Which record it was armed for, so another record reads as unarmed from its first render -- not after an effect
    // has caught up, a frame in which a click would delete the wrong one.
    var armedFor by useState<String?>(null)
    val armed = armedFor == props.recordKey
    div {
        className = ClassName("row")
        if (!armed) {
            Button {
                danger = true
                disabled = props.busy == true
                onClick = { armedFor = props.recordKey }
                +props.actionText
            }
        } else {
            span {
                className = ClassName("subtitle")
                +props.question
            }
            Button {
                danger = true
                loading = props.busy == true
                onClick = { props.onConfirm() }
                +props.confirmText
            }
            Button {
                type = "link"
                disabled = props.busy == true
                onClick = { armedFor = null }
                +props.keepText
            }
        }
    }
}
