package com.dynamicruntime.webapp

import com.dynamicruntime.common.gedra.ACEP
import com.dynamicruntime.common.gedra.CCT
import com.dynamicruntime.common.gedra.CFEP
import com.dynamicruntime.common.gedra.addressesEntry
import com.dynamicruntime.common.gedra.sandboxParentOf
import com.dynamicruntime.common.util.toJsonListOfMaps
import com.dynamicruntime.common.util.toJsonListOfStrings
import com.dynamicruntime.common.util.toJsonMapOrEmpty
import com.dynamicruntime.common.util.toOptStr

/*
 * The bundle editor page's pure half (issue #1436): the editor read as the page draws it, and how each entry stands
 * against its published counterpart. Pure, and covered under `jsTest`.
 */

/** One revision of a configuration, as the editor read answers it. */
class EditorRevision(
    val version: Int,
    val published: Boolean,
    val slots: Map<String, List<Map<String, Any?>>>,
    val issueCount: Int,
)

/** One configuration: its [latest] revision, and its [published] one beside it when the latest is a draft over it. */
class EditorBundle(val name: String, val latest: EditorRevision, val published: EditorRevision?)

/** The editor read: the client's configurations, and the fields each slot's entries are keyed by. */
class BundleEditorView(val client: String, val configs: List<EditorBundle>, val slotKeys: Map<String, List<String>>)

/** The editor read's answer as the page draws it. */
fun parseBundleEditor(item: Map<String, Any?>): BundleEditorView {
    fun revision(raw: Any?): EditorRevision? {
        val map = raw.toJsonMapOrEmpty().ifEmpty { return null }
        return EditorRevision(
            version = (map[CFEP.version] as? Number)?.toInt() ?: 0,
            published = map[CFEP.published] == true,
            slots = map[CFEP.slots].toJsonMapOrEmpty().mapValues { it.value.toJsonListOfMaps() },
            issueCount = map[CFEP.issues].toJsonListOfMaps().size,
        )
    }
    return BundleEditorView(
        client = item[CFEP.client].toOptStr().orEmpty(),
        configs = item[CFEP.configs].toJsonListOfMaps().mapNotNull { c ->
            val latest = revision(c[CFEP.revisionLatest]) ?: return@mapNotNull null
            EditorBundle(c[CFEP.name].toOptStr().orEmpty(), latest, revision(c[CFEP.revisionPublished]))
        },
        slotKeys = item[CFEP.slotKeys].toJsonMapOrEmpty().mapValues { it.value.toJsonListOfStrings() },
    )
}

/**
 * The line the closed Publishing section shows (issue #1436): how many of the client's [configs] are unpublished, so
 * closing it hides the table but not whether there is anything to publish.
 */
fun publishingSummaryText(configs: List<ConfigSummaryView>): String {
    val drafts = configs.count { !it.published }
    val total = configs.size
    val what = when {
        total == 0 -> "no configurations"
        drafts == 0 -> if (total == 1) "its one configuration published" else "all $total published"
        else -> "$drafts of $total configuration${if (total == 1) "" else "s"} unpublished"
    }
    return "Show the table — $what"
}

/**
 * [configs] in the order the page lists them: the one holding the client's definition first, then the rest by name.
 * Stable while a person works through the page -- the read answers them most recently written first, so a publish or
 * a save would otherwise move what was just touched to the top.
 */
fun editorBundlesInOrder(configs: List<EditorBundle>): List<EditorBundle> =
    configs.sortedWith(compareBy<EditorBundle> { CCT.clientDef !in it.latest.slots }.thenBy { it.name })

/** The slots in the order a page lists them: the client first, then what it declares, then what it says. */
val editorSlotOrder: List<String> = listOf(
    CCT.clientDef, CCT.customerDef, CCT.traitDef, CCT.schemaDef, CCT.usageDef, CCT.workflowDef, CCT.reportDef,
    CCT.cfactDef, CCT.fragmentDef, CCT.uiBlockDef, CCT.fileRefDef,
)

/** What a person calls [slot]; a slot this page does not know is called by its id. */
fun editorSlotLabel(slot: String): String = when (slot) {
    CCT.clientDef -> "Client definition"
    CCT.customerDef -> "Customers"
    CCT.traitDef -> "Traits"
    CCT.schemaDef -> "Schema types"
    CCT.usageDef -> "Listing columns"
    CCT.workflowDef -> "Workflows"
    CCT.reportDef -> "Reports"
    CCT.cfactDef -> "Cfacts"
    CCT.fragmentDef -> "Copy"
    CCT.uiBlockDef -> "Interface"
    CCT.fileRefDef -> "Files"
    else -> slot
}

/** How an entry of a draft stands against the published revision beside it. */
@Suppress("EnumEntryName")
enum class EntryChange { added, changed, unchanged, removed }

/** What the page says of an entry's [change]; nothing when there is no published revision to compare with. */
fun entryChangeText(change: EntryChange?): String? = when (change) {
    EntryChange.added -> "added in the draft"
    EntryChange.changed -> "changed in the draft"
    EntryChange.unchanged -> "unchanged"
    EntryChange.removed -> "removed in the draft"
    null -> null
}

/**
 * One entry of a slot: what it is called ([label], by its key), its data in the latest revision and in the published
 * one, and how the two compare -- null [change] when the configuration has no published revision beside its latest.
 */
class EditorEntry(val label: String, val latest: Map<String, Any?>?, val published: Map<String, Any?>?, val change: EntryChange?)

/** One slot of a configuration, with its entries -- those the draft removed after the rest. */
class EditorSlot(val slot: String, val label: String, val entries: List<EditorEntry>)

/** An entry's name, by its [key] fields' values; a single-instance slot's one entry is named by its slot. */
fun entryLabel(slot: String, key: List<String>, data: Map<String, Any?>): String =
    if (key.isEmpty()) editorSlotLabel(slot) else key.joinToString(" · ") { data[it]?.toString() ?: "(no $it)" }

/**
 * [bundle]'s slots as the page lists them ([editorSlotOrder], then any other): every entry of the latest revision,
 * matched by its key to the published revision's when there is one beside it, then the published entries the draft
 * removed. A slot only the published revision has is listed too, every entry of it removed.
 */
fun editorSlots(bundle: EditorBundle, slotKeys: Map<String, List<String>>): List<EditorSlot> {
    val published = bundle.published
    val slots = (bundle.latest.slots.keys + published?.slots?.keys.orEmpty()).distinct()
        .sortedBy { editorSlotOrder.indexOf(it).let { i -> if (i < 0) editorSlotOrder.size else i } }
    return slots.map { slot ->
        val key = slotKeys[slot].orEmpty()
        val latest = bundle.latest.slots[slot].orEmpty()
        val before = published?.slots?.get(slot).orEmpty()
        fun match(entry: Map<String, Any?>, among: List<Map<String, Any?>>) = among.firstOrNull { addressesEntry(key, it, entry) }
        val entries = latest.map { entry ->
            val was = if (published == null) null else match(entry, before)
            val change = when {
                published == null -> null
                was == null -> EntryChange.added
                was == entry -> EntryChange.unchanged
                else -> EntryChange.changed
            }
            EditorEntry(entryLabel(slot, key, entry), entry, was, change)
        } + before.filter { match(it, latest) == null }.map { EditorEntry(entryLabel(slot, key, it), null, it, EntryChange.removed) }
        EditorSlot(slot, editorSlotLabel(slot), entries)
    }
}

/** What a configuration's line says: its version, and whether what it holds is published. */
fun bundleSummaryText(bundle: EditorBundle): String {
    val latest = bundle.latest
    val state = when {
        latest.published -> "published"
        bundle.published != null -> "a draft over published version ${bundle.published.version}"
        else -> "a draft, never published"
    }
    val entries = latest.slots.values.sumOf { it.size }
    val held = "${latest.slots.size} slot${if (latest.slots.size == 1) "" else "s"}, $entries entr${if (entries == 1) "y" else "ies"}"
    val issues = if (latest.issueCount > 0) " · ${latest.issueCount} issue${if (latest.issueCount == 1) "" else "s"}" else ""
    return "${bundle.name} — version ${latest.version}, $state · $held$issues"
}

/** What a slot's line says: what it is called, its id, and how many entries the latest revision holds. */
fun slotSummaryText(slot: EditorSlot): String {
    val held = slot.entries.count { it.latest != null }
    val changed = slot.entries.count { it.change != null && it.change != EntryChange.unchanged }
    val changes = if (changed > 0) " · $changed changed in the draft" else ""
    return "${slot.label} (${slot.slot}) — $held entr${if (held == 1) "y" else "ies"}$changes"
}

/**
 * Which editor read answers for [clientId], as [storedConfigsPath] decides the listing: the full-scope one naming the
 * client -- a sandbox's parent, whose configuration it runs -- or the client-scoped one for the caller's own; null for
 * another client.
 */
fun bundleEditorPath(clientId: String, acrossClients: Boolean, ownClient: String): String? = when {
    acrossClients -> ACEP.editor + queryString(mapOf(CFEP.client to (sandboxParentOf(clientId) ?: clientId)))
    clientId == ownClient -> CFEP.editor
    else -> null
}
