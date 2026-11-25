package com.dynamicruntime.webapp

import kotlinx.coroutines.awaitCancellation
import react.createContext
import react.use
import react.useEffect
import react.useMemo
import react.useState

/*
 * Unsaved edits across a page's editors (issue #1922). A long page of panels holds several editors at once -- the
 * Clients page a workflow's, a trait's, the definition's, the copy's -- and one scrolled out of sight or left for a
 * moment can hold work nobody remembers. So a page keeps one register ([PendingEdits]), each editor with changes puts
 * itself in it ([usePendingEdit]), and the page shows and guards what is there: the side menu marks the panel and
 * section and lists the edits, each panel holding one says so beside its heading, a bar at the window's foot says so
 * where the menu is not shown, and leaving the page asks first ([PendingEditsGuard]).
 *
 * The pure half -- what the bar and the prompt say -- is covered under `jsNodeTest`.
 */

/**
 * One editor's unsaved edit (issue #1922): its [key], what it is in words ([label], "Workflow newForm"), the panel and
 * section it sits in ([panelId], [sectionId]: what the page marks), and the element to scroll to ([anchorId]).
 */
data class PendingEdit(val key: String, val label: String, val panelId: String?, val sectionId: String?, val anchorId: String)

/** Where an editor sits on its page (issue #1922): its panel, and the section of it, as `panel()` and a section say. */
data class PendingPlace(val panelId: String, val sectionId: String? = null)

/** A page's unsaved edits (issue #1922), each editor's at most once, in the order they began. */
class PendingEdits {
    private val edits = LinkedHashMap<String, PendingEdit>()

    /** The page's: draws it again, since its markers, its bar, and its guard follow what is here. */
    var onChange: () -> Unit = {}

    /** [edit] in, or replaced -- its label or place may change while it is open. */
    fun set(edit: PendingEdit) {
        if (edits[edit.key] == edit) return
        edits[edit.key] = edit
        onChange()
    }

    /** The edit under [key] out: saved, dropped, or its editor gone. */
    fun clear(key: String) {
        if (edits.remove(key) != null) onChange()
    }

    /** Every unsaved edit, in the order they began. */
    val all: List<PendingEdit> get() = edits.values.toList()

    fun any(): Boolean = edits.isNotEmpty()

    /** Whether an editor in panel [panelId] has unsaved edits. */
    fun inPanel(panelId: String): Boolean = edits.values.any { it.panelId == panelId }

    /** Whether an editor in section [sectionId] has unsaved edits. */
    fun inSection(sectionId: String): Boolean = edits.values.any { it.sectionId == sectionId }
}

/** The page's unsaved edits, for an editor to put itself in; null outside a page that keeps them. */
val PendingEditsContext = createContext<PendingEdits?>(null)

/** Where an editor drawn here sits: `panel()` provides its panel, and a section of it adds the section. */
val PendingPlaceContext = createContext<PendingPlace?>(null)

/** A page's register of unsaved edits (issue #1922): one for the page's life, drawing the page again when it changes. */
fun usePendingEdits(): PendingEdits {
    val edits = useMemo(*emptyArray<Any>()) { PendingEdits() }
    val bump = useState(0).component2()
    edits.onChange = { bump { it + 1 } }
    return edits
}

/** A sequence for the element ids an editor's unsaved edit is scrolled to by. */
private var pendingSeq = 0

/**
 * An editor's part in its page's unsaved edits (issue #1922): while [dirty], it is in the page's register as [label],
 * placed where the editor is drawn ([PendingPlaceContext]); saved, dropped, or gone, it is out. Returns the element id
 * the editor puts on its root, which "go to it" scrolls to. Outside a page that keeps a register, it does nothing.
 */
fun usePendingEdit(label: String, dirty: Boolean): String {
    val edits = use(PendingEditsContext)
    val place = use(PendingPlaceContext)
    val key = useState { "pending-edit-${++pendingSeq}" }.component1()
    useEffect(dirty, label, place?.panelId, place?.sectionId, edits) {
        if (edits == null || !dirty) return@useEffect
        edits.set(PendingEdit(key, label, place?.panelId, place?.sectionId, key))
        try {
            awaitCancellation()
        } finally {
            edits.clear(key)
        }
    }
    return key
}

/** How many unsaved changes there are, in words: "1 unsaved change", "3 unsaved changes". */
fun pendingCountText(count: Int): String = if (count == 1) "1 unsaved change" else "$count unsaved changes"

/** What leaving the page asks while [edits] are unsaved: what they are, and whether to leave anyway. */
fun pendingLeavePrompt(edits: List<PendingEdit>): String =
    "You have unsaved changes on this page: ${edits.joinToString(", ") { it.label }}. Leave the page and lose them?"
