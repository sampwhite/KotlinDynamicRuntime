package com.dynamicruntime.common.gedra

import com.dynamicruntime.common.context.KdrCxt
import com.dynamicruntime.common.exception.KdrException
import com.dynamicruntime.common.naming.clientNamespace
import com.dynamicruntime.common.util.toJsonMapOrEmpty

/**
 * What the administrators' editors of a client's presentation share (issues #918, #919): the stored config their
 * edits land in when no other holds the thing edited, the rule against writing into somebody's draft, and the
 * publish-reload-undo that makes an edit live. The copy editor ([ClientCopyEdit]) and the menu editor
 * ([ClientMenuEdit]) each decide *which* config holds their key or item and how to patch it; this is the rest.
 */
object ClientStoredEdit {
    /**
     * Where an editor's save of [client]'s presentation lands, and how it takes effect (issue #930).
     *
     * - **The configuration's owner.** A Shadow Sandbox holds no configuration, so a save named for one lands in its
     *   parent's, made in [bound] -- bound to the parent and acting as the person's user there ([SandboxEdits]).
     * - **Live or draft, by whether the owner has a sandbox.** Without one, a save publishes and goes live at once
     *   (#918's behavior). With one -- from the parent or the sandbox alike -- a save writes the parent's editable
     *   revision and reloads, so the sandbox shows it, and publishing is the explicit step.
     */
    class EditTarget(
        /** The context the configuration is read and written in, bound to [client]. */
        val bound: KdrCxt,
        /** The client whose configuration takes the save: the one named, or a sandbox's parent. */
        val client: String,
        /** Whether a save stays a draft ([EDM.draft]) rather than publishing ([EDM.live]). */
        val draft: Boolean,
    ) {
        /** The result's [CPY.mode]. */
        val mode: String get() = if (draft) EDM.draft else EDM.live

        /** The client whose people read a saved value: the owner when live, its sandbox for a draft. */
        val readsAs: String get() = if (draft) sandboxOf(client) else client
    }

    /** Where a save of [client]'s presentation lands -- see [EditTarget]; [what] names the editor's sub context. */
    fun target(cxt: KdrCxt, client: String, what: String): EditTarget {
        SandboxEdits.parentCxt(cxt, client)?.let { return EditTarget(it, it.client, draft = true) }
        val draft = ClientService.get(cxt).known(client)?.sandbox == true
        return EditTarget(cxt.mkSubContext(what, client), client, draft)
    }

    /**
     * Makes a save take effect: published and live ([publishAndReload]), or -- a draft -- the owner reloaded, which
     * rebuilds its sandbox from the latest revision while a published-only owner keeps running what it published.
     */
    fun takeEffect(cxt: KdrCxt, target: EditTarget, written: GedraConfigRow, undo: () -> Unit, restorePublished: Boolean = false): ConfigReloadResult =
        if (target.draft) SandboxEdits.reloadParent(cxt, target.client)
        else publishAndReload(cxt, target.bound, target.client, written, undo, restorePublished)

    /** The stored config of the bound client's that holds its definition (`clientDef`), or null when none does. */
    fun definitionHolder(bound: KdrCxt): GedraConfigRow? =
        GedraConfigService.get(bound).listConfigs(bound).firstOrNull { it.entriesBySlot()[CCT.clientDef]?.isNotEmpty() == true }

    /**
     * Patches [row]'s client definition entry as [edit] leaves it -- inside `patchConfig`, over the revision as it
     * stands under the lock, so a field another administrator changed a moment ago is kept. The definition editors'
     * shared patch (the sandbox flag, #932; the presentation fields, #1026).
     */
    fun patchDefinition(bound: KdrCxt, row: GedraConfigRow, edit: (LinkedHashMap<String, Any?>) -> Unit): GedraConfigRow =
        GedraConfigService.get(bound).patchConfig(bound, row.configId, trial = true) { current ->
            val out = LinkedHashMap(current)
            val defs = current[CCT.clientDef].orEmpty()
            val def = LinkedHashMap(defs.firstOrNull().toJsonMapOrEmpty())
            edit(def)
            out[CCT.clientDef] = listOf(def) + defs.drop(1)
            out
        }

    /**
     * Puts an editor's patched [entry] back into [slot] of [out]: in place of the one at [at], or appended when there
     * was none -- or, when the patch left it [emptied], removed, so a config does not carry an empty overlay. A slot
     * left with no [entries] is dropped too. The shared tail of both editors' patches, run inside `patchConfig`.
     */
    fun storeEntry(
        out: MutableMap<String, List<Map<String, Any?>>>,
        slot: String,
        entries: MutableList<Map<String, Any?>>,
        at: Int,
        entry: Map<String, Any?>,
        emptied: Boolean,
    ) {
        when {
            emptied && at >= 0 -> entries.removeAt(at)
            at >= 0 -> entries[at] = entry
            else -> entries.add(entry)
        }
        if (entries.isEmpty()) out.remove(slot) else out[slot] = entries
    }

    /** The editors' own config of [client]'s ([CPY.copyConfigName]), or null when none has been created yet. */
    fun editConfig(bound: KdrCxt, client: String): GedraConfigRow? =
        GedraConfigService.get(bound).readLatest(bound, GedraId.of(GedraConfigType.configDoc, client, CPY.copyConfigName))

    /** Creates the editors' own config for [client] with what [build] declares, trial-checked, and returns the row. */
    fun createEditConfig(bound: KdrCxt, client: String, build: GedraConfigBuilder.() -> Unit): GedraConfigRow {
        val config = gedraConfig(bound, CPY.copyConfigName, clientNamespace(client), client, build = build)
        return GedraConfigService.get(bound).writeConfig(bound, config, trial = true)
    }

    /**
     * Refuses to write into a config that has unpublished changes of its own -- an edit publishes the config it lands
     * in, and would take somebody's half-finished draft live with it -- unless it is the editors' own config, whose
     * only drafts are the editors' (a publish that was refused). [what] names the edit in the refusal. Not asked of a
     * [EditTarget.draft] save, which publishes nothing.
     */
    fun requireNoForeignDraft(target: EditTarget, holder: GedraConfigRow, what: String) {
        if (target.draft || holder.isPublished || holder.configId.baseId == CPY.copyConfigName) return
        throw KdrException.mkInput(
            "Configuration '${holder.configId.baseId}' of client '${holder.client}' has unpublished changes, which " +
                "$what would publish with it. Publish or revert that configuration first.",
        )
    }

    /**
     * Publishes [written]'s config, reloads [client] on this node and announces it to peers, returning the reload.
     * A publish the trial refuses runs [undo] -- the change put back as it was -- before the refusal is rethrown, so
     * the change is not left in a draft that the next edit would publish. The undo is itself a further unpublished
     * revision, equal to the published one; with [restorePublished] -- for a config that was published before the
     * edit -- it is published too, so the config is as it was rather than a draft the next edit is refused for.
     */
    fun publishAndReload(cxt: KdrCxt, bound: KdrCxt, client: String, written: GedraConfigRow, undo: () -> Unit, restorePublished: Boolean = false): ConfigReloadResult {
        try {
            GedraConfigService.get(bound).publish(bound, written.configId, trial = true)
        } catch (e: KdrException) {
            undo()
            // Best effort: the undone content is what the client already runs, so its publish should pass; if it does
            // not, the refusal to report is the edit's.
            if (restorePublished) runCatching { GedraConfigService.get(bound).publish(bound, written.configId, trial = true) }
            throw e
        }
        val reload = GedraConfigReload.reloadClient(cxt, client)
        ClientSyncService.get(cxt).announceReload(cxt, reload)
        return reload
    }
}
