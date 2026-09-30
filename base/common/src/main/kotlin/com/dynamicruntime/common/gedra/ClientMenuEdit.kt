package com.dynamicruntime.common.gedra

import com.dynamicruntime.common.cfact.CFACT
import com.dynamicruntime.common.context.KdrCxt
import com.dynamicruntime.common.exception.EXC
import com.dynamicruntime.common.exception.KdrException
import com.dynamicruntime.common.home.HFLD
import com.dynamicruntime.common.home.HMENU
import com.dynamicruntime.common.uiblock.UIB
import com.dynamicruntime.common.uiblock.UiBlockService
import com.dynamicruntime.common.util.toJsonListOfMaps
import com.dynamicruntime.common.util.toJsonMapOrEmpty
import com.dynamicruntime.common.util.toOptStr

/**
 * Editing one item of a client's home menu (issue #919): the write behind `POST /clientAdmin/client/menu/set` and
 * `/reset`. The first cut: **rename** (the item's label), **hide** (its condition becomes `#never`, the documented
 * way an overlay withdraws an item), **show** (a condition the shipped menu already draws for -- a client picks an
 * audience the menu knows, it does not write an expression) and **reset** (the client's changes to the item
 * removed). Not adding, reordering, or a free condition.
 *
 * **Presentation, not permission.** Hiding an item withdraws its offer; showing one puts it on offer. Neither
 * changes what anyone may reach: the section gate still decides, and a shown item's page refuses a caller who lacks
 * access exactly as before.
 *
 * ### Where an edit lands, and how
 *
 * As a copy edit's does ([ClientCopyEdit], [ClientStoredEdit]): in the client's stored config whose `uiBlockDef`
 * entry for the home menu already touches the item, else one that overlays the menu at all, else the editors' own
 * `copy` config, created on the first edit; never into a config with a draft of its own. The item is merged by its
 * `id` into that entry's `menu` array inside `patchConfig`, under the client's config lock, so the entry's other
 * items -- and another administrator's change a moment ago -- are kept. Only `label` and `cfactExpression` are ever
 * written; a reset removes the client's item from the array, whatever it held.
 *
 * Trial-checked, published, reloaded and announced in one call, as a copy edit is; the publish's refusal undoes
 * the write.
 */
object ClientMenuEdit {
    /** One home-menu item as a client sees it, with what the shipped menu says and what the client changed. */
    class MenuItem(
        val itemId: String,
        val parentId: String?,
        val baseLabel: String?,
        val label: String?,
        val baseCondition: String?,
        val condition: String?,
        /** Whether the client's own layers set the label or the condition. */
        val stored: Boolean,
    )

    /**
     * The home menu's items **for [client]**, in the menu's order: the shipped label and condition, the client's, and
     * whether the client's stored configuration changed the item. Every item the block holds, before any caller's
     * cfacts are applied -- an editor lists what can be changed, not what one person sees.
     */
    fun itemsFor(cxt: KdrCxt, client: String): List<MenuItem> {
        val blocks = UiBlockService.get(cxt)
        val base = menuItems(blocks.merged(cxt, HMENU.block, null).content).associateBy { it[HFLD.id].toOptStr() }
        val effective = menuItems(blocks.merged(cxt, HMENU.block, client).content)
        val stored = storedItems(cxt, client)
        return effective.mapNotNull { item ->
            val id = item[HFLD.id].toOptStr() ?: return@mapNotNull null
            val shipped = base[id]
            MenuItem(
                itemId = id,
                parentId = item[UIB.parentId].toOptStr(),
                baseLabel = shipped?.get(HFLD.label).toOptStr(),
                label = item[HFLD.label].toOptStr(),
                baseCondition = shipped?.get(UIB.cfactExpression).toOptStr(),
                condition = item[UIB.cfactExpression].toOptStr(),
                stored = stored[id]?.let { it.containsKey(HFLD.label) || it.containsKey(UIB.cfactExpression) } ?: false,
            )
        }
    }

    /** What a set or reset did: the config it landed in, and the item as the client's people now get it. */
    class Result(val configName: String, val label: String?, val condition: String?, val stored: Boolean, val issues: List<GedraConfigIssue>)

    /**
     * Changes [itemId] for [client] -- [label] renames it, [visibility] hides or shows it (with [condition], for a
     * show, one the shipped menu draws for) -- and makes that live. At least one of the two must be asked for.
     */
    fun set(cxt: KdrCxt, client: String, itemId: String, label: String?, visibility: String?, condition: String?): Result {
        val fields = LinkedHashMap<String, Any?>()
        if (label != null) {
            if (label.isBlank()) throw KdrException.mkInput("A menu item's label cannot be blank.")
            fields[HFLD.label] = label.trim()
        }
        when (visibility) {
            null -> {}
            MNU.hide -> fields[UIB.cfactExpression] = CFACT.neverName
            MNU.show -> {
                val chosen = condition?.trim()?.ifEmpty { null }
                    ?: throw KdrException.mkInput("Showing an item needs the '${MNU.condition}' it is shown under.")
                if (chosen != CFACT.alwaysName && chosen !in shippedConditions(cxt)) {
                    throw KdrException.mkInput(
                        "'$chosen' is not a condition the shipped menu draws for. A client may show an item to an " +
                            "audience the menu already knows: ${(shippedConditions(cxt) + CFACT.alwaysName).joinToString(", ")}.",
                    )
                }
                fields[UIB.cfactExpression] = chosen
            }
            else -> throw KdrException.mkInput("'${MNU.visibility}' is '${MNU.hide}' or '${MNU.show}', not '$visibility'.")
        }
        if (fields.isEmpty()) throw KdrException.mkInput("A menu edit renames the item, hides it, or shows it; this one does none.")
        requireShippedItem(cxt, itemId)

        val bound = cxt.mkSubContext("menuEdit", client)
        val svc = GedraConfigService.get(bound)
        val holder = holderOf(bound, client, itemId) ?: ClientStoredEdit.editConfig(bound, client)
        if (holder == null) {
            val written = ClientStoredEdit.createEditConfig(bound, client) {
                uiBlockOverlay(HMENU.block, mapOf(HFLD.menu to listOf(linkedMapOf<String, Any?>(HFLD.id to itemId) + fields)))
            }
            return goLive(cxt, bound, client, written, itemId, undo = { patchItem(svc, bound, written, itemId, null) })
        }
        ClientStoredEdit.requireNoForeignDraft(holder, "a menu edit")
        val before = storedItem(holder, itemId)
        val written = patchItem(svc, bound, holder, itemId, fields)
        return goLive(cxt, bound, client, written, itemId, undo = { patchItem(svc, bound, written, itemId, before, replace = true) })
    }

    /**
     * Removes [client]'s stored changes to [itemId] and makes that live. A 400 when no stored config touches the item:
     * what the client's source config or the shipped menu says is not something data can take away.
     */
    fun reset(cxt: KdrCxt, client: String, itemId: String): Result {
        val bound = cxt.mkSubContext("menuEdit", client)
        val svc = GedraConfigService.get(bound)
        val holder = holderOf(bound, client, itemId)?.takeIf { storedItem(it, itemId) != null }
            ?: throw KdrException.mkInput(
                "No stored configuration changes the menu item '$itemId' for client '$client'; what it shows comes " +
                    "from source code or the shipped menu, which a reset cannot remove.",
            )
        ClientStoredEdit.requireNoForeignDraft(holder, "a menu edit")
        val before = storedItem(holder, itemId)
        val written = patchItem(svc, bound, holder, itemId, null)
        return goLive(cxt, bound, client, written, itemId, undo = { patchItem(svc, bound, written, itemId, before, replace = true) })
    }

    /** The conditions the shipped home menu draws for -- the audiences a client may show an item to. */
    fun shippedConditions(cxt: KdrCxt): List<String> =
        menuItems(UiBlockService.get(cxt).merged(cxt, HMENU.block, null).content)
            .mapNotNull { it[UIB.cfactExpression].toOptStr() }.filter { it != CFACT.neverName }.distinct()

    private fun menuItems(content: Map<String, Any?>): List<Map<String, Any?>> = content[HFLD.menu].toJsonListOfMaps()

    /** Refuses an item the shipped menu does not have: an overlay of it would add an item, which this editor does not do. */
    private fun requireShippedItem(cxt: KdrCxt, itemId: String) {
        val known = menuItems(UiBlockService.get(cxt).merged(cxt, HMENU.block, null).content).any { it[HFLD.id].toOptStr() == itemId }
        if (!known) throw KdrException("The home menu has no item '$itemId'.", code = EXC.notFound)
    }

    /** The client's stored menu items by id, folded over its stored configs in listing order. */
    private fun storedItems(cxt: KdrCxt, client: String): Map<String, Map<String, Any?>> {
        val bound = cxt.mkSubContext("menuItems", client)
        val out = LinkedHashMap<String, Map<String, Any?>>()
        for (row in GedraConfigService.get(bound).listConfigs(bound).filter { it.client == client }) {
            for (item in menuEntry(row.entriesBySlot())?.get(CCT.content).toJsonMapOrEmpty()[HFLD.menu].toJsonListOfMaps()) {
                val id = item[HFLD.id].toOptStr() ?: continue
                out[id] = out[id].orEmpty() + item
            }
        }
        return out
    }

    /** The stored config of [client]'s an edit of [itemId] belongs in: one already touching the item, else one overlaying the menu. */
    private fun holderOf(bound: KdrCxt, client: String, itemId: String): GedraConfigRow? {
        val onMenu = GedraConfigService.get(bound).listConfigs(bound)
            .filter { it.client == client && menuEntry(it.entriesBySlot()) != null }
        return onMenu.firstOrNull { storedItem(it, itemId) != null } ?: onMenu.firstOrNull()
    }

    private fun menuEntry(slots: Map<String, List<Map<String, Any?>>>): Map<String, Any?>? =
        slots[CCT.uiBlockDef]?.firstOrNull { it[CCT.blockId].toOptStr() == HMENU.block }

    /** [row]'s stored item [itemId] in the home menu, or null when it does not touch the item. */
    private fun storedItem(row: GedraConfigRow, itemId: String): Map<String, Any?>? =
        menuEntry(row.entriesBySlot())?.get(CCT.content).toJsonMapOrEmpty()[HFLD.menu].toJsonListOfMaps()
            .firstOrNull { it[HFLD.id].toOptStr() == itemId }

    /**
     * Patches [row] so its home-menu entry's item [itemId] carries [fields] merged over what it held -- or, with
     * [replace], exactly [fields]; with null fields, no item at all. An entry left with no items is dropped, and a slot
     * left with no entries too. The merge runs inside `patchConfig`, over the revision as it stands under the lock.
     */
    private fun patchItem(
        svc: GedraConfigService,
        bound: KdrCxt,
        row: GedraConfigRow,
        itemId: String,
        fields: Map<String, Any?>?,
        replace: Boolean = false,
    ): GedraConfigRow = svc.patchConfig(bound, row.configId, trial = true) { current ->
        val out = LinkedHashMap<String, List<Map<String, Any?>>>(current)
        val entries = current[CCT.uiBlockDef].orEmpty().toMutableList()
        val at = entries.indexOfFirst { it[CCT.blockId].toOptStr() == HMENU.block }
        val content = LinkedHashMap<String, Any?>(if (at >= 0) entries[at][CCT.content].toJsonMapOrEmpty() else emptyMap())
        val items = content[HFLD.menu].toJsonListOfMaps().toMutableList()
        val idx = items.indexOfFirst { it[HFLD.id].toOptStr() == itemId }
        when {
            fields == null -> if (idx >= 0) items.removeAt(idx)
            idx < 0 -> items.add(linkedMapOf<String, Any?>(HFLD.id to itemId) + fields)
            replace -> items[idx] = linkedMapOf<String, Any?>(HFLD.id to itemId) + fields
            else -> items[idx] = items[idx] + fields
        }
        if (items.isEmpty()) content.remove(HFLD.menu) else content[HFLD.menu] = items
        val entry = linkedMapOf<String, Any?>(CCT.blockId to HMENU.block, CCT.content to content)
        when {
            content.isEmpty() && at >= 0 -> entries.removeAt(at)
            at >= 0 -> entries[at] = entry
            else -> entries.add(entry)
        }
        if (entries.isEmpty()) out.remove(CCT.uiBlockDef) else out[CCT.uiBlockDef] = entries
        out
    }

    /** Makes [written] live and reads back the item as the client's people now get it. */
    private fun goLive(cxt: KdrCxt, bound: KdrCxt, client: String, written: GedraConfigRow, itemId: String, undo: () -> Unit): Result {
        val reload = ClientStoredEdit.publishAndReload(cxt, bound, client, written, undo)
        val item = menuItems(UiBlockService.get(cxt).merged(cxt, HMENU.block, client).content).firstOrNull { it[HFLD.id].toOptStr() == itemId }
        return Result(
            configName = written.configId.baseId,
            label = item?.get(HFLD.label).toOptStr(),
            condition = item?.get(UIB.cfactExpression).toOptStr(),
            stored = storedItem(written, itemId)?.let { it.containsKey(HFLD.label) || it.containsKey(UIB.cfactExpression) } ?: false,
            issues = reload.issues,
        )
    }
}
