package com.dynamicruntime.common.gedra

import com.dynamicruntime.common.content.MarkdownFragmentService
import com.dynamicruntime.common.context.KdrCxt
import com.dynamicruntime.common.exception.EXC
import com.dynamicruntime.common.exception.KdrException
import com.dynamicruntime.common.util.toJsonMapOrEmpty
import com.dynamicruntime.common.util.toOptStr

/**
 * Editing a client's copy (issue #918): the write behind `POST /clientAdmin/client/copy/set` and `/reset`, one key each,
 * and `/apply`, several keys of one file in one save (issue #1062) -- the first two are a batch of one.
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
 * ### Only the keys changed, under the lock
 *
 * A `fragmentDef` entry holds a file's whole content map, so a change of some keys is a merge **inside** that map,
 * done by `GedraConfigService.patchConfig` under the client's config lock over the revision as it stands there --
 * never from a map the caller read earlier. So the file's other keys, and a key another administrator changed a
 * moment ago, are kept. A batch lands in **one** config ([apply]), so it is one publish and takes effect at once.
 *
 * ### Live at once, or a draft
 *
 * A client with a Shadow Sandbox (issue #930) -- always published-only -- saves a **draft** instead: the change is
 * written and the client reloaded, not published, so the sandbox shows it and publishing is the explicit step; and
 * a save named for the sandbox lands in its parent's configuration ([ClientStoredEdit.EditTarget]). For any other
 * client:
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
                keys.map { (key, value) -> CopyKey(fileId, ns, key, effective.audience.name, value, effective.shownOnFor(client)) }
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
        /** How the save took effect, an [EDM] value (issue #930): live, or a draft its sandbox runs. */
        val mode: String = EDM.live,
    )

    /** One change of a batch ([apply]): a [value] to set, or -- null -- a reset of the client's stored value. */
    class Change(val namespace: String, val key: String, val value: String?)

    /** What one key of a batch now reads for the client, and whether a stored value still sets it. */
    class Applied(val namespace: String, val key: String, val value: String?, val stored: Boolean)

    /** What a batch did: the config it landed in, and what each key changed now reads. */
    class ApplyResult(
        val configName: String,
        val keys: List<Applied>,
        val buildId: String?,
        val issues: List<GedraConfigIssue>,
        val mode: String,
    ) {
        /** The one key of a single set or reset, as [Result]. */
        fun only(): Result = keys.single().let { Result(configName, it.value, it.stored, buildId, issues, mode) }
    }

    /** Sets [key]'s [value] for [client] -- see the class note -- and makes it take effect: live, or a draft. */
    fun set(cxt: KdrCxt, client: String, fileId: String, namespace: String, key: String, value: String): Result =
        apply(cxt, client, fileId, listOf(Change(namespace, key, value))).only()

    /**
     * Removes [client]'s stored value for [key] and makes that take effect, live or as a draft. A 400 when no stored
     * layer sets it: a value from the client's source config, or the shipped copy, is not something data can take
     * away.
     */
    fun reset(cxt: KdrCxt, client: String, fileId: String, namespace: String, key: String): Result =
        apply(cxt, client, fileId, listOf(Change(namespace, key, null))).only()

    /**
     * Sets and resets several keys of one file for [client] in one save (issue #1062), so they take effect together:
     * one patch, one trial, one publish and reload -- or one draft -- and, when the publish is refused, one undo
     * putting every key back. Each key is judged as [set] and [reset] judge one: a set must name a shipped key, a reset
     * a key a stored value sets, and a key may be changed once.
     *
     * The keys land in one stored config: the one already setting any of them, else the first overlaying the file,
     * else [CPY.copyConfigName] -- so a new key joins the batch's other keys. Keys that **two** stored configs hold
     * are refused, naming both: saving them would publish each config on its own, which is not one save.
     */
    fun apply(cxt: KdrCxt, client: String, fileId: String, changes: List<Change>): ApplyResult {
        if (changes.isEmpty()) throw KdrException.mkInput("No changes to apply to the fragment file '$fileId'.")
        changes.groupBy { it.namespace to it.key }.entries.firstOrNull { it.value.size > 1 }?.let { (at, _) ->
            throw KdrException.mkInput("'$fileId: ${at.first}.${at.second}' is changed more than once; a save changes each key once.")
        }
        for (c in changes) if (c.value != null) requireShippedKey(cxt, fileId, c.namespace, c.key)
        // A sandbox's edit lands in its parent's configuration, and a client with a sandbox saves drafts (issue #930).
        val target = ClientStoredEdit.target(cxt, client, "copyEdit")
        val bound = target.bound
        val owner = target.client
        val svc = GedraConfigService.get(bound)
        val onFile = configsOverlaying(bound, owner, fileId)
        val held = changes.associateWith { c -> onFile.firstOrNull { storedValue(it, fileId, c.namespace, c.key) != null } }
        held.entries.firstOrNull { it.key.value == null && it.value == null }?.key?.let { c ->
            throw KdrException.mkInput(
                "No stored value sets '$fileId: ${c.namespace}.${c.key}' for client '$owner'; what it reads comes from " +
                    "source code or the shipped copy, which a reset cannot remove.",
            )
        }
        val holders = held.values.filterNotNull().distinctBy { it.configId }
        if (holders.size > 1) {
            throw KdrException.mkInput(
                "The keys of '$fileId' being changed are held by more than one stored configuration of client '$owner' " +
                    "(${holders.joinToString(", ") { "'${it.configId.baseId}'" }}), and saving them together would " +
                    "publish each on its own. Save the keys of one configuration at a time.",
            )
        }
        val edits = changes.associate { (it.namespace to it.key) to it.value }
        val holder = holders.singleOrNull() ?: onFile.firstOrNull() ?: ClientStoredEdit.editConfig(bound, owner)
        if (holder == null) {
            // The first edit of a file no stored config overlays, with no `copy` config yet: created with just these
            // keys -- all sets, since a reset here has nothing stored to remove and was refused above.
            val content = LinkedHashMap<String, MutableMap<String, String>>()
            for (c in changes) content.getOrPut(c.namespace) { linkedMapOf() }[c.key] = c.value.orEmpty()
            val written = ClientStoredEdit.createEditConfig(bound, owner) { fragmentOverlay(fileId, content) }
            return goLive(cxt, target, written, fileId, changes, undo = { patchKeys(svc, bound, written, fileId, edits.mapValues { null }) })
        }
        ClientStoredEdit.requireNoForeignDraft(target, holder, "a copy edit")
        val before = changes.associate { (it.namespace to it.key) to storedValue(holder, fileId, it.namespace, it.key) }
        val written = patchKeys(svc, bound, holder, fileId, edits)
        return goLive(cxt, target, written, fileId, changes, undo = { patchKeys(svc, bound, written, fileId, before) })
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

    /** The stored configs of [client]'s whose `fragmentDef` overlays [fileId], in listing order. */
    private fun configsOverlaying(bound: KdrCxt, client: String, fileId: String): List<GedraConfigRow> =
        GedraConfigService.get(bound).listConfigs(bound).filter { it.client == client && fragmentEntry(it.entriesBySlot(), fileId) != null }

    private fun fragmentEntry(slots: Map<String, List<Map<String, Any?>>>, fileId: String): Map<String, Any?>? =
        slots[CCT.fragmentDef]?.firstOrNull { it[CCT.fileId].toOptStr() == fileId }

    /** The value [row]'s entry for [fileId] holds for [key], or null when it does not set the key. */
    private fun storedValue(row: GedraConfigRow, fileId: String, namespace: String, key: String): String? =
        fragmentEntry(row.entriesBySlot(), fileId)?.get(CCT.content).toJsonMapOrEmpty()[namespace].toJsonMapOrEmpty()[key].toOptStr()

    /**
     * Patches [row] so its `fragmentDef` entry for [fileId] holds each of [edits]' keys at its value -- or, for a null
     * value, no longer holds the key; an entry left with no keys is dropped, so the config does not carry an empty
     * overlay. The merge runs inside `patchConfig`, over the revision as it stands under the lock.
     */
    private fun patchKeys(
        svc: GedraConfigService,
        bound: KdrCxt,
        row: GedraConfigRow,
        fileId: String,
        edits: Map<Pair<String, String>, String?>,
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
        for ((address, value) in edits) {
            val (namespace, key) = address
            val nsKeys = LinkedHashMap(content[namespace].orEmpty())
            if (value != null) nsKeys[key] = value else nsKeys.remove(key)
            if (nsKeys.isEmpty()) content.remove(namespace) else content[namespace] = nsKeys
        }
        val entry = linkedMapOf<String, Any?>(CCT.fileId to fileId, CCT.content to content)
        ClientStoredEdit.storeEntry(out, CCT.fragmentDef, entries, at, entry, emptied = content.isEmpty())
        out
    }

    /**
     * Makes [written] take effect ([ClientStoredEdit.takeEffect]: live, or a draft) and reads back what the people of
     * the client it shows for now read of each changed key -- the client's when live, its sandbox's for a draft.
     */
    private fun goLive(
        cxt: KdrCxt,
        target: ClientStoredEdit.EditTarget,
        written: GedraConfigRow,
        fileId: String,
        changes: List<Change>,
        undo: () -> Unit,
    ): ApplyResult {
        val reload = ClientStoredEdit.takeEffect(cxt, target, written, undo)
        val effective = MarkdownFragmentService.get(cxt).effectiveFragmentsFor(cxt, fileId, target.readsAs)
        return ApplyResult(
            configName = written.configId.baseId,
            keys = changes.map {
                Applied(
                    it.namespace, it.key,
                    value = effective?.content?.get(it.namespace)?.get(it.key),
                    // From the row just written, which is the one that decides it.
                    stored = storedValue(written, fileId, it.namespace, it.key) != null,
                )
            },
            buildId = effective?.buildId,
            issues = reload.issues,
            mode = target.mode,
        )
    }
}
