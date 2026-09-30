package com.dynamicruntime.common.gedra

import com.dynamicruntime.common.content.MarkdownFragmentService
import com.dynamicruntime.common.context.KdrCxt
import com.dynamicruntime.common.exception.EXC
import com.dynamicruntime.common.exception.KdrException
import com.dynamicruntime.common.util.toJsonMapOrEmpty
import com.dynamicruntime.common.util.toOptStr

/**
 * Editing one key of a client's copy (issue #918): the write behind `POST /clientAdmin/client/copy/set` and `/reset`.
 *
 * ### Where an edit lands
 *
 * In the client's **stored** configuration, in a `fragmentDef` entry for the file: the stored config that already
 * sets the **key**, else the one that already overlays the **file**, else a config named [CPY.copyConfigName],
 * created on the first edit. Deterministic, and it keeps one stored config per key: two stored configs setting the
 * same key would be overlaid at the same step, which the merge treats as an authoring mistake rather than a
 * composition. A key the client's **source** config sets is overridden, not replaced: a stored layer is applied after
 * a source one (`overlayPrecedence`), so the edit wins, and a reset removes the stored value and the source one
 * shows again.
 *
 * A config with **unpublished changes** of its own is not written into -- a copy edit publishes the config it lands
 * in, and would take somebody's half-finished draft live with it. The edit is refused, naming the config, so the
 * draft is published or reverted first. The `copy` config is this editor's own, so a draft there is its own doing
 * (a publish that was refused) and is simply completed.
 *
 * ### One key at a time, under the lock
 *
 * A `fragmentDef` entry holds a file's whole content map, so a one-key change is a merge **inside** that map, done
 * by `GedraConfigService.patchConfig` under the client's config lock over the revision as it stands there -- never
 * from a map the caller read earlier. So the file's other keys, and a key another administrator changed a moment
 * ago, are kept.
 *
 * ### Live at once
 *
 * The write is trial-checked (issue #843) -- a `%{...}` in a frontend file, an unresolved `%{@t(...)}` in a backend
 * one, a template that does not parse -- and refused with the findings. What passes is published (a published-only
 * client consumes only published configuration) and the client reloaded on this node and announced to peers, so
 * the next page load reads the new copy under a new build id. The publish has a trial of its own, judged against
 * the client's *published* revisions, and can refuse what the write's trial passed; the write is then undone --
 * the key put back as it was -- so a refused change is not left in a draft for the next edit to publish. The
 * shared half of that -- where an edit lands, the draft rule, publish-reload-undo -- is [ClientStoredEdit], which
 * the menu editor (issue #919) writes through too.
 */
object ClientCopyEdit {
    /** One key the client's people read: its address, who the file is for, and the value this client gets. */
    class CopyKey(
        val fileId: String,
        val namespace: String,
        val key: String,
        val audience: String,
        val value: String,
        /** Where the application shows the file (issue #933); null for one it does not show. */
        val shownOn: String?,
    )

    /**
     * Every key of every fragment file that has a base, with its value **for [client]** -- what an editor offers to
     * override, and the starting text of an edit. A frontend file's are what the content server delivers; a backend
     * file's (`mail`) are never served, which is why an editor needs this rather than the `/st` URL.
     */
    fun keysFor(cxt: KdrCxt, client: String): List<CopyKey> {
        val fragments = MarkdownFragmentService.get(cxt)
        val fileIds = MarkdownFragmentService.registeredFragmentSources(cxt).filter { !it.isOverlay }.map { it.fileId }.distinct()
        return fileIds.flatMap { fileId ->
            val effective = fragments.effectiveFragmentsFor(cxt, fileId, client) ?: return@flatMap emptyList()
            effective.content.flatMap { (ns, keys) ->
                keys.map { (key, value) -> CopyKey(fileId, ns, key, effective.audience.name, value, effective.shownOn) }
            }
        }
    }

    /** What a set or reset did: the config it landed in, and what the client's people now read. */
    class Result(
        val configName: String,
        /** The key's value for the client after the change; null when nothing sets it (a reset of the only layer). */
        val value: String?,
        /** Whether a stored layer still sets the key -- false after a reset that left the source or shipped value. */
        val stored: Boolean,
        val buildId: String?,
        /** The issues the client's configuration has after the reload -- pre-existing ones; the trial refused new ones. */
        val issues: List<GedraConfigIssue>,
    )

    /** Sets [key]'s [value] for [client] -- see the class note -- and makes it live. */
    fun set(cxt: KdrCxt, client: String, fileId: String, namespace: String, key: String, value: String): Result {
        requireShippedKey(cxt, fileId, namespace, key)
        val bound = cxt.mkSubContext("copyEdit", client)
        val svc = GedraConfigService.get(bound)
        val holder = holderOf(bound, client, fileId, namespace, key) ?: ClientStoredEdit.editConfig(bound, client)
        if (holder == null) {
            // The first edit of a file no stored config overlays, with no `copy` config yet: created with just this key.
            val written = ClientStoredEdit.createEditConfig(bound, client) {
                fragmentOverlay(fileId, mapOf(namespace to mapOf(key to value)))
            }
            return goLive(cxt, bound, client, written, fileId, namespace, key, undo = { patchKey(svc, bound, written, fileId, namespace, key, null) })
        }
        ClientStoredEdit.requireNoForeignDraft(holder, "a copy edit")
        val before = storedValue(holder, fileId, namespace, key)
        val written = patchKey(svc, bound, holder, fileId, namespace, key, value)
        return goLive(cxt, bound, client, written, fileId, namespace, key, undo = { patchKey(svc, bound, written, fileId, namespace, key, before) })
    }

    /**
     * Removes [client]'s stored value for [key] and makes that live. A 400 when no stored layer sets it: a value
     * from the client's source config, or the shipped copy, is not something data can take away.
     */
    fun reset(cxt: KdrCxt, client: String, fileId: String, namespace: String, key: String): Result {
        val bound = cxt.mkSubContext("copyEdit", client)
        val svc = GedraConfigService.get(bound)
        val holder = holderOf(bound, client, fileId, namespace, key)?.takeIf { storedValue(it, fileId, namespace, key) != null }
            ?: throw KdrException.mkInput(
                "No stored value sets '$fileId: $namespace.$key' for client '$client'; what it reads comes from source " +
                    "code or the shipped copy, which a reset cannot remove.",
            )
        ClientStoredEdit.requireNoForeignDraft(holder, "a copy edit")
        val before = storedValue(holder, fileId, namespace, key)
        val written = patchKey(svc, bound, holder, fileId, namespace, key, value = null)
        return goLive(cxt, bound, client, written, fileId, namespace, key, undo = { patchKey(svc, bound, written, fileId, namespace, key, before) })
    }

    /** Refuses a key no shipped file declares: an overlay of it would be stored and never read (an orphan). */
    private fun requireShippedKey(cxt: KdrCxt, fileId: String, namespace: String, key: String) {
        val shipped = MarkdownFragmentService.get(cxt).effectiveFragmentsFor(cxt, fileId, null)
            ?: throw KdrException("No fragment file '$fileId' is declared on this node.", code = EXC.notFound)
        if (shipped.content[namespace]?.containsKey(key) != true) {
            throw KdrException(
                "The fragment file '$fileId' declares no key '$namespace.$key'; a value for it would never be read.",
                code = EXC.notFound,
            )
        }
    }

    /**
     * The stored config of [client]'s an edit of [key] belongs in: one whose `fragmentDef` entry for [fileId] already
     * sets the key, else one that overlays the file at all; null when none does.
     */
    private fun holderOf(bound: KdrCxt, client: String, fileId: String, namespace: String, key: String): GedraConfigRow? {
        val onFile = GedraConfigService.get(bound).listConfigs(bound)
            .filter { it.client == client && fragmentEntry(it.entriesBySlot(), fileId) != null }
        return onFile.firstOrNull { storedValue(it, fileId, namespace, key) != null } ?: onFile.firstOrNull()
    }

    private fun fragmentEntry(slots: Map<String, List<Map<String, Any?>>>, fileId: String): Map<String, Any?>? =
        slots[CCT.fragmentDef]?.firstOrNull { it[CCT.fileId].toOptStr() == fileId }

    /** The value [row]'s entry for [fileId] holds for [key], or null when it does not set the key. */
    private fun storedValue(row: GedraConfigRow, fileId: String, namespace: String, key: String): String? =
        fragmentEntry(row.entriesBySlot(), fileId)?.get(CCT.content).toJsonMapOrEmpty()[namespace].toJsonMapOrEmpty()[key].toOptStr()

    /**
     * Patches [row] so its `fragmentDef` entry for [fileId] holds [key] = [value] -- or, with a null value, no longer
     * holds it; an entry left with no keys is dropped, so the config does not carry an empty overlay. The merge runs
     * inside `patchConfig`, over the revision as it stands under the lock.
     */
    private fun patchKey(
        svc: GedraConfigService,
        bound: KdrCxt,
        row: GedraConfigRow,
        fileId: String,
        namespace: String,
        key: String,
        value: String?,
    ): GedraConfigRow = svc.patchConfig(bound, row.configId, trial = true) { current ->
        val out = LinkedHashMap<String, List<Map<String, Any?>>>(current)
        val entries = current[CCT.fragmentDef].orEmpty().toMutableList()
        val at = entries.indexOfFirst { it[CCT.fileId].toOptStr() == fileId }
        val content = LinkedHashMap<String, Map<String, String>>()
        if (at >= 0) {
            entries[at][CCT.content].toJsonMapOrEmpty().forEach { (ns, keys) ->
                content[ns] = keys.toJsonMapOrEmpty().mapValues { it.value.toOptStr().orEmpty() }
            }
        }
        val nsKeys = LinkedHashMap(content[namespace].orEmpty())
        if (value != null) nsKeys[key] = value else nsKeys.remove(key)
        if (nsKeys.isEmpty()) content.remove(namespace) else content[namespace] = nsKeys
        val entry = linkedMapOf<String, Any?>(CCT.fileId to fileId, CCT.content to content)
        when {
            content.isEmpty() && at >= 0 -> entries.removeAt(at)
            at >= 0 -> entries[at] = entry
            else -> entries.add(entry)
        }
        if (entries.isEmpty()) out.remove(CCT.fragmentDef) else out[CCT.fragmentDef] = entries
        out
    }

    /** Makes [written] live (see [ClientStoredEdit.publishAndReload]) and reads back what the client now reads. */
    private fun goLive(
        cxt: KdrCxt,
        bound: KdrCxt,
        client: String,
        written: GedraConfigRow,
        fileId: String,
        namespace: String,
        key: String,
        undo: () -> Unit,
    ): Result {
        val reload = ClientStoredEdit.publishAndReload(cxt, bound, client, written, undo)
        val effective = MarkdownFragmentService.get(cxt).effectiveFragmentsFor(cxt, fileId, client)
        return Result(
            configName = written.configId.baseId,
            value = effective?.content?.get(namespace)?.get(key),
            // From the row just written, which is the one that decides it.
            stored = storedValue(written, fileId, namespace, key) != null,
            buildId = effective?.buildId,
            issues = reload.issues,
        )
    }
}
