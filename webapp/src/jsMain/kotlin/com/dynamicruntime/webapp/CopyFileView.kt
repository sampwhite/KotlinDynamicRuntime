package com.dynamicruntime.webapp

import com.dynamicruntime.common.gedra.COV
import com.dynamicruntime.common.gedra.CPY
import com.dynamicruntime.common.gedra.EDM
import com.dynamicruntime.common.gedra.GCI
import com.dynamicruntime.common.util.toJsonListOfMaps
import com.dynamicruntime.common.util.toOptStr

/*
 * The copy editor's file view (issue #1062), its pure half: every key of a fragment file laid out with the value the
 * client reads, findable by its words as well as its address, and edited as a set of pending changes saved together.
 * Covered by `CopyFileViewTest`; the component is `CopyFileEditor`.
 */

/** Where a piece of copy lives: file, namespace, key. */
data class CopyAddress(val fileId: String, val namespace: String, val key: String) {
    /** As the page names a key within its file: `namespace.key`. */
    val nsKey: String get() = "$namespace.$key"
}

/**
 * One key as the file view draws it: the value the client reads, and -- when the client's configuration sets it --
 * that override, from the overrides report, which knows who set it and what it replaced.
 */
class CopyKeyRow(
    val address: CopyAddress,
    val audience: String,
    /** What the client reads; for an orphan, the value stored that nothing reads. */
    val value: String,
    val override: CopyOverrideView?,
    /** Where the application shows the file (issue #933); null for one it does not show. */
    val shownOn: String?,
) {
    val orphan: Boolean get() = override?.orphan == true
}

/**
 * Every key the client may override, each joined with its override, in the keys listing's order -- then each orphan
 * (a key the client's configuration sets that no shipped file declares, so the keys listing does not have it)
 * appended to its file. Pure, and covered under `jsNodeTest`.
 */
fun copyKeyRows(keys: List<CopyKeyView>, overrides: List<CopyOverrideView>): List<CopyKeyRow> {
    val byAddress = overrides.associateBy { CopyAddress(it.fileId, it.namespace, it.key) }
    val rows = keys.map { k ->
        val at = CopyAddress(k.fileId, k.namespace, k.key)
        CopyKeyRow(at, k.audience, k.value, byAddress[at], k.shownOn)
    }
    val listed = rows.map { it.address }.toSet()
    val orphans = overrides.filter { CopyAddress(it.fileId, it.namespace, it.key) !in listed }.map {
        CopyKeyRow(CopyAddress(it.fileId, it.namespace, it.key), it.audience, it.value.orEmpty(), it, it.shownOn)
    }
    if (orphans.isEmpty()) return rows
    // Each orphan after the last key of its file, so it is read with the rest of the file; one of a file with no
    // shipped keys at all closes the list.
    val out = rows.toMutableList()
    for (orphan in orphans) {
        val last = out.indexOfLast { it.address.fileId == orphan.address.fileId }
        if (last < 0) out.add(orphan) else out.add(last + 1, orphan)
    }
    return out
}

/** A pending change to one key: a [value] to set, or -- null -- a reset of the client's stored value. */
class PendingCopy(val value: String?) {
    val reset: Boolean get() = value == null
}

/** One namespace of a file as the view draws it. */
class CopyNamespaceGroup(val namespace: String, val rows: List<CopyKeyRow>)

/** One file as the view draws it, namespace by namespace. */
class CopyFileGroup(val fileId: String, val shownOn: String?, val namespaces: List<CopyNamespaceGroup>)

/** What the view's controls ask for: the file chosen, the words typed, and whether only the changed keys show. */
class CopyViewQuery(val fileId: String?, val filter: String = "", val onlyChanged: Boolean = false)

/**
 * Whether [row] answers [filter]: its `namespace.key` or the value it reads contains the words, ignoring case -- the
 * value being how an administrator usually knows a key, from what the page says. A blank filter matches everything.
 * Pure, and covered under `jsNodeTest`.
 */
fun copyRowMatches(row: CopyKeyRow, filter: String): Boolean {
    val words = filter.trim()
    if (words.isEmpty()) return true
    return row.address.nsKey.contains(words, ignoreCase = true) || row.value.contains(words, ignoreCase = true)
}

/**
 * What the view shows for [query]: the chosen file; or, with words typed, every file's matching keys, so a phrase on
 * screen finds its key wherever it lives; or, with no file chosen, nothing -- except under **Only changed**, which then
 * lists every file's changed keys, the client's customizations at a glance. **Only changed** keeps the keys the
 * client's configuration sets, and the ones with a [pending] change. Files in the order the File choice offers them
 * (the shown ones first, [copyFileChoices]); namespaces and keys as the file declares them. Pure, and covered under
 * `jsNodeTest`.
 */
fun copyFileGroups(rows: List<CopyKeyRow>, query: CopyViewQuery, pending: Map<CopyAddress, PendingCopy> = emptyMap()): List<CopyFileGroup> {
    val acrossFiles = copyViewSpansFiles(query)
    if (!acrossFiles && query.fileId == null) return emptyList()
    val shown = rows.filter { row ->
        (acrossFiles || row.address.fileId == query.fileId) &&
            copyRowMatches(row, query.filter) &&
            (!query.onlyChanged || row.override != null || row.address in pending)
    }
    val shownOn = rows.associate { it.address.fileId to it.shownOn }
    val files = shown.map { it.address.fileId }.distinct().sortedBy { if (shownOn[it] != null) 0 else 1 }
    return files.map { fileId ->
        val inFile = shown.filter { it.address.fileId == fileId }
        CopyFileGroup(
            fileId, shownOn[fileId],
            inFile.map { it.address.namespace }.distinct().map { ns -> CopyNamespaceGroup(ns, inFile.filter { it.address.namespace == ns }) },
        )
    }
}

/**
 * Whether the view lists keys of every file rather than the chosen one's: words typed in Find, or **Only changed**
 * with no file chosen. Each file is then titled, since the File control no longer names it. Pure, and covered under
 * `jsNodeTest`.
 */
fun copyViewSpansFiles(query: CopyViewQuery): Boolean = query.filter.isNotBlank() || (query.fileId == null && query.onlyChanged)

/**
 * How a file's panel counts its keys: all of them -- "62 keys" -- or, when a search or **Only changed** narrows it,
 * how many of them show: "3 of 62 keys". Pure, and covered under `jsNodeTest`.
 */
fun copyFileCountText(group: CopyFileGroup, rows: List<CopyKeyRow>): String {
    val shown = group.namespaces.sumOf { it.rows.size }
    val total = rows.count { it.address.fileId == group.fileId }
    val noun = if (total == 1) "key" else "keys"
    return if (shown == total) "$total $noun" else "$shown of $total $noun"
}

/** The choices of the view's File control, from every row: the files the application shows first (issue #933). */
fun copyViewFiles(rows: List<CopyKeyRow>): List<CopyFileChoice> =
    copyFileChoices(rows.map { CopyKeyView(it.address.fileId, it.address.namespace, it.address.key, it.audience, it.value, it.shownOn) })

/**
 * The file the view shows: the one chosen, while it still has keys -- none at first, so the view opens on a choice to
 * make rather than on a file nobody picked. Pure, and covered under `jsNodeTest`.
 */
fun copyViewOpenFile(rows: List<CopyKeyRow>, chosen: String?): String? = chosen?.takeIf { c -> rows.any { it.address.fileId == c } }

/**
 * The file the [pending] changes belong to -- one at most, since a save is of one file -- or null when there are
 * none. Pure, and covered under `jsNodeTest`.
 */
fun pendingCopyFile(pending: Map<CopyAddress, PendingCopy>): String? = pending.keys.firstOrNull()?.fileId

/**
 * Whether [row] may be edited now: always, unless changes to **another** file are pending -- a save is of one file,
 * so a second file waits until the first is saved or discarded. Pure, and covered under `jsNodeTest`.
 */
fun copyRowEditable(row: CopyKeyRow, pending: Map<CopyAddress, PendingCopy>): Boolean =
    pendingCopyFile(pending).let { it == null || it == row.address.fileId }

/**
 * The pending changes after [row]'s editor holds [draft]: the draft as a pending value, or no pending change at all
 * once it reads as the client's value again, so an edit typed back to where it started is not a change to save.
 * Pure, and covered under `jsNodeTest`.
 */
fun pendingAfterEdit(pending: Map<CopyAddress, PendingCopy>, row: CopyKeyRow, draft: String): Map<CopyAddress, PendingCopy> =
    if (draft == row.value) pending - row.address else pending + (row.address to PendingCopy(draft))

/**
 * Whether [row] offers a reset: the client's **stored** value only -- what source code or the shipped file says is
 * not data's to remove (see [copyRowResettable]) -- and not while a change of it is pending. Pure, and covered under
 * `jsNodeTest`.
 */
fun copyRowOffersReset(row: CopyKeyRow, pending: Map<CopyAddress, PendingCopy>): Boolean =
    row.override?.let { copyRowResettable(it) } == true && row.address !in pending

/**
 * What a key's head says about it, beside its address: a pending change first ("edited, not saved", "reset, not
 * saved"), else what the client's configuration did ("changed by copy (stored)"; an orphan says it is read by
 * nothing); null for a key that reads the shipped copy. Pure, and covered under `jsNodeTest`.
 */
fun copyRowStatus(row: CopyKeyRow, pending: Map<CopyAddress, PendingCopy>): String? {
    pending[row.address]?.let { return if (it.reset) "reset, not saved" else "edited, not saved" }
    val o = row.override ?: return null
    if (o.orphan) return "orphan: no shipped copy declares this key, so nothing reads it"
    return "changed by ${setByText(o.configName, o.origin, o.template)}"
}

/**
 * The value a key's panel shows: a pending edit's draft, so the page reads as it will once saved; for a pending
 * reset, what the client will read afterwards -- its source value when its source config sets one, else the shipped
 * copy. Pure, and covered under `jsNodeTest`.
 */
fun copyRowShownValue(row: CopyKeyRow, pending: Map<CopyAddress, PendingCopy>): String {
    val change = pending[row.address] ?: return row.value
    if (!change.reset) return change.value.orEmpty()
    return row.override?.sourceValue ?: row.override?.baseValue ?: ""
}

/**
 * The request that saves [pending] -- every change to one file -- for [clientId] (issue #1062): the file, and each
 * key set to its value or reset, in the order the file declares them ([order]) so the result lists them that way.
 * Pure, and covered under `jsNodeTest`.
 */
fun copyApplyRequest(clientId: String, pending: Map<CopyAddress, PendingCopy>, order: List<CopyAddress>): Map<String, Any?> {
    val fileId = pendingCopyFile(pending).orEmpty()
    val rank = order.withIndex().associate { it.value to it.index }
    val changes = pending.entries.sortedBy { rank[it.key] ?: Int.MAX_VALUE }.map { (at, change) ->
        linkedMapOf<String, Any?>(COV.namespaceField to at.namespace, COV.key to at.key).also {
            if (change.reset) it[CPY.reset] = true else it[COV.value] = change.value
        }
    }
    return linkedMapOf(COV.client to clientId, COV.fileId to fileId, CPY.changes to changes)
}

/** What a save of a file's changes did (issue #1062): where it landed, how it took effect, and the issues after it. */
class CopyApplyResult(val configName: String, val keyCount: Int, val issues: List<String>, val mode: String = EDM.live)

/** The apply result as a [CopyApplyResult]. Pure, and covered under `jsNodeTest`. */
fun parseCopyApplyResult(results: Map<String, Any?>): CopyApplyResult = CopyApplyResult(
    configName = results[COV.configName].toOptStr().orEmpty(),
    keyCount = results[CPY.keys].toJsonListOfMaps().size,
    issues = results[CPY.issues].toJsonListOfMaps().mapNotNull { it[GCI.message].toOptStr() },
    mode = results[CPY.mode].toOptStr() ?: EDM.live,
)

/** The note after a save: how many keys of which file, where they landed -- and, for a draft, where it shows. */
fun copyAppliedNote(fileId: String, result: CopyApplyResult): String {
    val what = if (result.keyCount == 1) "1 change" else "${result.keyCount} changes"
    return savedNote("Saved $what to $fileId in ${result.configName}.", result.mode)
}

/** How the save bar counts what is pending: "1 unsaved change to home", "3 unsaved changes to mail". */
fun pendingCopyText(pending: Map<CopyAddress, PendingCopy>): String {
    val n = pending.size
    return "${if (n == 1) "1 unsaved change" else "$n unsaved changes"} to ${pendingCopyFile(pending).orEmpty()}"
}

/**
 * The pending keys a refused save's message names. The backend's checks lead each finding with the key it faults
 * (`common.htmlStyle: …`, `home.intro pulls …`), so a key named as a whole word -- not as the start of a longer
 * key -- is one to mark. Pure, and covered under `jsNodeTest`.
 */
fun copyKeysNamedIn(message: String, pending: Collection<CopyAddress>): Set<CopyAddress> = pending.filter { at ->
    Regex("(^|[^A-Za-z0-9_.])" + Regex.escape(at.nsKey) + "([^A-Za-z0-9_]|$)").containsMatchIn(message)
}.toSet()
