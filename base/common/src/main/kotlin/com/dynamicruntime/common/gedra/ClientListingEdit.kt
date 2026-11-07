package com.dynamicruntime.common.gedra

import com.dynamicruntime.common.context.KdrCxt

/**
 * Setting a client's listing-wide settings from the Clients page (issue #1599): the write behind
 * `POST /clientAdmin/client/usages/layout`, beside the listing columns ([ClientUsageEdit]) it is shown with.
 *
 * ### Where an edit lands
 *
 * In the stored configuration already holding the client's `listingDef` entry; else the editors' own configuration
 * ([ClientStoredEdit.ensureEditConfig]), where the copy and menu editors' changes land -- a presentation setting, as
 * theirs are, and never caught behind somebody's unpublished change to the client's definition. Unlike a definition, a
 * stored listing setting stands over the code's (`GedraConfigCollector.listingFor`), so a client defined in code may set
 * one here. Patched under the client's config lock over the revision as it stands there, so another administrator's
 * change a moment ago is kept.
 *
 * ### Live at once, or a draft
 *
 * Every editor's rule ([ClientStoredEdit], issue #930): published and reloaded for a client without a sandbox, refused
 * while that configuration carries somebody's unpublished changes; a draft its sandbox shows for a client with one.
 */
object ClientListingEdit {
    /** What a save did: the configuration it landed in, the layout as stored, and how it took effect. */
    class Result(val configName: String, val layout: WorkflowLayout, val issues: List<GedraConfigIssue>, val mode: String)

    /** The stored configuration of the bound client's holding its listing settings, latest revision, or null when none does. */
    fun holder(bound: KdrCxt): GedraConfigRow? =
        GedraConfigService.get(bound).listConfigs(bound).firstOrNull { it.entriesBySlot()[CCT.listingDef]?.isNotEmpty() == true }

    /**
     * Sets [client]'s [layout] and makes it take effect, live or as a draft. [expected] is how the caller told its user
     * the save would take effect, held to by [ClientStoredEdit.target].
     */
    fun setLayout(cxt: KdrCxt, client: String, layout: WorkflowLayout, expected: String? = null): Result {
        // From a sandbox, its parent's: the sandbox holds no configuration, and a save is a draft of the parent's (#1358).
        val owner = sandboxParentOf(client) ?: client
        val target = ClientStoredEdit.target(cxt, client, "listingEdit", expected)
        val bound = target.bound
        val holder = holder(bound) ?: ClientStoredEdit.ensureEditConfig(bound, owner)
        ClientStoredEdit.requireNoForeignDraft(target, holder, "a listing setting")
        val svc = GedraConfigService.get(bound)
        fun patch(row: GedraConfigRow, entry: Map<String, Any?>?) = svc.patchConfigs(bound, trial = true) {
            edit(row.configId) { slots ->
                // Every other field of the entry kept, so a setting a later release adds is not lost to this one.
                if (entry == null) slots - CCT.listingDef else slots + (CCT.listingDef to listOf(entry))
            }
        }.single()
        val before = holder.entriesBySlot()[CCT.listingDef]?.firstOrNull()
        val written = patch(holder, LinkedHashMap(before.orEmpty()).apply { put(CCT.workflowLayout, layout.name) })
        val reload = ClientStoredEdit.takeEffect(
            cxt, target, written,
            undo = { patch(written, before) },
            restorePublished = holder.isPublished,
        )
        return Result(written.configId.baseId, layout, reload.issues, target.mode)
    }
}
