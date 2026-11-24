package com.dynamicruntime.webapp

import com.dynamicruntime.common.exception.KdrException
import react.createContext

/**
 * Saving a form's data grids with the form (issue #1885). A grid edits a table field's rows in the page, but the field
 * holds a file's id, and a file is uploaded before a form can name it. So a form surface -- the create page, the raw
 * editor, a workflow's page -- keeps a [TableDrafts], and each grid with unsaved rows registers with it: on Save the
 * page first **flushes** every grid ([TableDrafts.flushAll]), which uploads each changed table as a new CSV file, and
 * then sends the form with each grid's field set to its new file ([applyTableUpdates]). A grid with a problem stops the
 * save and puts the cursor on the bad cell.
 *
 * The pure half -- where a grid's value goes in the form's values, and how outcomes combine -- is covered under
 * `jsNodeTest`.
 *
 * Theme: #DTABL.
 */

/** Where a grid's field is: the [scope] of the values it is in (a workflow page's trait, or "" for a page's one form) and its [path] there. */
data class TableDraftKey(val scope: String, val path: String)

/** What a grid's flush did: nothing to save, a new file saved, or a problem that keeps the form from saving. */
sealed class TableFlush {
    data object Unchanged : TableFlush()

    data class Saved(val fileId: String) : TableFlush()

    data class Refused(val message: String) : TableFlush()
}

/** One grid's part in a save: whether it has unsaved rows, how to flush them, and how to drop them. */
class TableDraft(
    val dirty: () -> Boolean,
    val flush: suspend () -> TableFlush,
    val reset: () -> Unit,
)

/** A field's new file, from a flush: the field ([key]) and the file ([fileId]). */
data class TableUpdate(val key: TableDraftKey, val fileId: String)

/** What flushing a page's grids did: each field's new file, or the first problem, which stops the save. */
class TableFlushOutcome(val updates: List<TableUpdate>, val refusal: String?)

/**
 * A form surface's grids (issue #1885), registered by field. [onChange] is the page's, told when a grid's unsaved state
 * may have changed, so the page's Save and Reset, which consult [anyDirty], are drawn again.
 */
class TableDrafts {
    private val drafts = LinkedHashMap<TableDraftKey, TableDraft>()

    /** The page's: re-renders it, since a grid's unsaved rows count as an unsaved change. */
    var onChange: () -> Unit = {}

    fun register(key: TableDraftKey, draft: TableDraft) {
        drafts[key] = draft
        onChange()
    }

    fun unregister(key: TableDraftKey) {
        if (drafts.remove(key) != null) onChange()
    }

    /** Whether any grid -- or, given [scopes], any grid in one of them -- has rows not yet saved. */
    fun anyDirty(scopes: Set<String>? = null): Boolean =
        drafts.any { (key, draft) -> (scopes == null || key.scope in scopes) && draft.dirty() }

    /**
     * Each grid -- or each in [scopes] -- flushed, in the order they registered: a changed table uploaded as a new file,
     * an unchanged one left as it is. The first grid with a problem stops the rest and is the outcome's [TableFlushOutcome.refusal]; what
     * was uploaded before it is kept, and is saved again on the next Save.
     */
    suspend fun flushAll(scopes: Set<String>? = null): TableFlushOutcome {
        val updates = ArrayList<TableUpdate>()
        for ((key, draft) in drafts.toList()) {
            if (scopes != null && key.scope !in scopes) continue
            when (val done = draft.flush()) {
                TableFlush.Unchanged -> {}
                is TableFlush.Saved -> updates.add(TableUpdate(key, done.fileId))
                is TableFlush.Refused -> return TableFlushOutcome(updates, done.message)
            }
        }
        return TableFlushOutcome(updates, null)
    }

    /** Every grid -- or each in [scopes] -- back to its field's file, its unsaved rows dropped: a page's Reset. */
    fun resetAll(scopes: Set<String>? = null) {
        drafts.forEach { (key, draft) -> if (scopes == null || key.scope in scopes) draft.reset() }
    }
}

/** The page's grids, for a grid to register with; null outside a form surface, where a grid saves nothing. */
val TableDraftsContext = createContext<TableDrafts?>(null)

/** Which of a page's values a grid's field is in: a workflow page's trait; "" for a page with one form. */
val TableDraftsScopeContext = createContext("")

/** [values] -- a form's, of [scope] -- with each of [updates] in that scope set at its field's path. */
fun applyTableUpdates(values: Map<String, Any?>, updates: List<TableUpdate>, scope: String = ""): Map<String, Any?> =
    updates.filter { it.key.scope == scope }.fold(values) { acc, update -> withValueAt(acc, update.key.path, update.fileId) }

/** One step of a path: a property's [name], or a list's [index]. */
private class PathStep(val name: String?, val index: Int?)

/** [path] as a form writes it (`edits[0].data.lines`, `childPath` and `indexPath`) read into its steps. */
private fun pathSteps(path: String): List<PathStep> {
    val steps = ArrayList<PathStep>()
    for (part in path.split('.')) {
        if (part.isEmpty()) continue
        val name = part.substringBefore('[')
        if (name.isNotEmpty()) steps.add(PathStep(name, null))
        var rest = part.substring(name.length)
        while (rest.startsWith("[")) {
            val close = rest.indexOf(']')
            val index = rest.substring(1, close).toIntOrNull()
                ?: throw KdrException("The path '$path' has an index that is not a number.")
            steps.add(PathStep(null, index))
            rest = rest.substring(close + 1)
        }
    }
    return steps
}

/**
 * [root] with [value] at [path], every map and list on the way copied rather than changed. A step into what is not
 * there makes it: a property's map, or a list's element. A walk over the path's steps, not a recursion, so its depth is
 * the path's.
 */
fun withValueAt(root: Map<String, Any?>, path: String, value: Any?): Map<String, Any?> {
    val steps = pathSteps(path)
    if (steps.isEmpty()) return root
    // Down the path: the container at each step, as it is.
    val containers = ArrayList<Any?>(steps.size)
    var at: Any? = root
    for (step in steps) {
        containers.add(at)
        at = if (step.name != null) (at as? Map<*, *>)?.get(step.name) else (at as? List<*>)?.getOrNull(step.index!!)
    }
    // Back up it: each container copied with its child set.
    var child: Any? = value
    for (i in steps.indices.reversed()) {
        val step = steps[i]
        val container = containers[i]
        child = if (step.name != null) {
            val map = LinkedHashMap<String, Any?>()
            (container as? Map<*, *>)?.forEach { (k, v) -> map[k.toString()] = v }
            map[step.name] = child
            map
        } else {
            val list = ((container as? List<*>) ?: emptyList<Any?>()).toMutableList()
            while (list.size <= step.index!!) list.add(null)
            list[step.index] = child
            list
        }
    }
    @Suppress("UNCHECKED_CAST")
    return child as Map<String, Any?>
}

/** A form surface's grids: one [TableDrafts] for the page's life, which draws the page again when a grid's unsaved rows change. */
fun useTableDrafts(): TableDrafts {
    val drafts = react.useMemo(*emptyArray<Any>()) { TableDrafts() }
    val bump = react.useState(0).component2()
    drafts.onChange = { bump { it + 1 } }
    return drafts
}
