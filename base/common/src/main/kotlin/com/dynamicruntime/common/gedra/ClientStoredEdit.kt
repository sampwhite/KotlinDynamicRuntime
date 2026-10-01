package com.dynamicruntime.common.gedra

import com.dynamicruntime.common.context.KdrCxt
import com.dynamicruntime.common.exception.KdrException
import com.dynamicruntime.common.naming.clientNamespace

/**
 * What the administrators' editors of a client's presentation share (issues #918, #919): the stored config their
 * edits land in when no other holds the thing edited, the rule against writing into somebody's draft, and the
 * publish-reload-undo that makes an edit live. The copy editor ([ClientCopyEdit]) and the menu editor
 * ([ClientMenuEdit]) each decide *which* config holds their key or item and how to patch it; this is the rest.
 */
object ClientStoredEdit {
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
     * only drafts are the editors' (a publish that was refused). [what] names the edit in the refusal.
     */
    fun requireNoForeignDraft(holder: GedraConfigRow, what: String) {
        if (holder.isPublished || holder.configId.baseId == CPY.copyConfigName) return
        throw KdrException.mkInput(
            "Configuration '${holder.configId.baseId}' of client '${holder.client}' has unpublished changes, which " +
                "$what would publish with it. Publish or revert that configuration first.",
        )
    }

    /**
     * Publishes [written]'s config, reloads [client] on this node and announces it to peers, returning the reload.
     * A publish the trial refuses runs [undo] -- the change put back as it was -- before the refusal is rethrown, so
     * the change is not left in a draft that the next edit would publish.
     */
    fun publishAndReload(cxt: KdrCxt, bound: KdrCxt, client: String, written: GedraConfigRow, undo: () -> Unit): ConfigReloadResult {
        try {
            GedraConfigService.get(bound).publish(bound, written.configId, trial = true)
        } catch (e: KdrException) {
            undo()
            throw e
        }
        val reload = GedraConfigReload.reloadClient(cxt, client)
        ClientSyncService.get(cxt).announceReload(cxt, reload)
        return reload
    }
}
