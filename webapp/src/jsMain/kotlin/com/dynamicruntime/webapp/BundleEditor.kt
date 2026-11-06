package com.dynamicruntime.webapp

import com.dynamicruntime.common.gedra.ACEP
import com.dynamicruntime.common.gedra.CCT
import com.dynamicruntime.common.gedra.CFEP
import com.dynamicruntime.common.gedra.addressesEntry
import com.dynamicruntime.common.gedra.sandboxParentOf
import com.dynamicruntime.common.gedra.slotsToPortable
import com.dynamicruntime.common.gedra.toPortable
import com.dynamicruntime.common.schema.SCH
import com.dynamicruntime.common.util.toJsonListOfMaps
import com.dynamicruntime.common.util.toJsonListOrEmpty
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
    /** The configuration's namespace, as stored: `client.<id>`, or beneath it. */
    val namespace: String = "",
)

/**
 * One configuration: its [latest] revision, and its [published] one beside it when the latest is a draft over it.
 * [stamps] is each latest entry's `updatedAt` by slot, in the slot's order: what an edit of it says it read (issue #1460).
 */
class EditorBundle(
    val name: String,
    val latest: EditorRevision,
    val published: EditorRevision?,
    val stamps: Map<String, List<Any?>> = emptyMap(),
)

/**
 * The editor read: the client's configurations, the fields each slot's entries are keyed by, and -- for checking an
 * edit (issue #1460) -- each slot's entry type ([slotTypes]) with the schema types it needs ([defs]).
 */
class BundleEditorView(
    val client: String,
    val configs: List<EditorBundle>,
    val slotKeys: Map<String, List<String>>,
    val slotTypes: Map<String, String> = emptyMap(),
    val defs: Map<String, Any?> = emptyMap(),
    /**
     * The client's code layer, as declared (issues #1616, #1646): its own code's configurations, then its template's
     * copies, each shown merged with the stored bundle of its name.
     */
    val codeConfigs: List<CodeBundle> = emptyList(),
    /** Each of the client's entries that replaces one of its code's, whole (issues #1616, #1646). */
    val replacements: List<CodeReplacementView> = emptyList(),
)

/**
 * One configuration of a client's code layer (issues #1616, #1646): declared by the client's own code, or -- [template]
 * set -- a copy of its template's code, whole as declared. Never edited itself: changing an entry of it writes an
 * override, an entry marked `"overrides": "code"` in the stored bundle of the same name.
 */
class CodeBundle(val name: String, val template: String?, val namespace: String, val slots: Map<String, List<Map<String, Any?>>>) {
    /** The bundle as the page's slot listing reads one: a single revision, nothing beside it. */
    fun asEditorBundle(): EditorBundle = EditorBundle(name, EditorRevision(0, true, slots, 0, namespace), null)
}

/**
 * An entry of the client's that replaces one of its code's whole (issues #1616, #1646): in configuration [name], slot
 * [slot], with key fields [key] (empty for the columns of the forms list, which replace as one set). What it replaces is
 * [codeEntries], in the code bundle [codeName] -- the client's own code's, or a copy of its [template]'s. [marked] says
 * the entry carries `"overrides": "code"`: a stored one without it is refused at a write.
 */
class CodeReplacementView(
    val name: String,
    val slot: String,
    val key: Map<String, Any?>,
    val codeName: String,
    val template: String?,
    val codeEntries: List<Map<String, Any?>>,
    val marked: Boolean,
)

/** One replacement as the editor read, or a patch's dry run, answers it. */
fun parseReplacement(raw: Map<String, Any?>): CodeReplacementView = CodeReplacementView(
    name = raw[CFEP.name].toOptStr().orEmpty(),
    slot = raw[CFEP.slot].toOptStr().orEmpty(),
    key = raw[CFEP.key].toJsonMapOrEmpty(),
    codeName = raw[CFEP.codeName].toOptStr().orEmpty(),
    template = raw[CFEP.template].toOptStr()?.takeIf { raw[CFEP.origin] == CFEP.originTemplate },
    codeEntries = raw[CFEP.codeEntries].toJsonListOfMaps(),
    marked = raw[CFEP.marked] == true,
)

/** The editor read's answer as the page draws it. */
fun parseBundleEditor(item: Map<String, Any?>): BundleEditorView {
    fun revision(raw: Any?): EditorRevision? {
        val map = raw.toJsonMapOrEmpty().ifEmpty { return null }
        return EditorRevision(
            version = (map[CFEP.version] as? Number)?.toInt() ?: 0,
            published = map[CFEP.published] == true,
            slots = map[CFEP.slots].toJsonMapOrEmpty().mapValues { it.value.toJsonListOfMaps() },
            issueCount = map[CFEP.issues].toJsonListOfMaps().size,
            namespace = map[CFEP.namespaceField].toOptStr().orEmpty(),
        )
    }
    return BundleEditorView(
        client = item[CFEP.client].toOptStr().orEmpty(),
        configs = item[CFEP.configs].toJsonListOfMaps().mapNotNull { c ->
            val latest = revision(c[CFEP.revisionLatest]) ?: return@mapNotNull null
            EditorBundle(
                c[CFEP.name].toOptStr().orEmpty(), latest, revision(c[CFEP.revisionPublished]),
                c[CFEP.latestStamps].toJsonMapOrEmpty().mapValues { it.value.toJsonListOrEmpty() },
            )
        },
        slotKeys = item[CFEP.slotKeys].toJsonMapOrEmpty().mapValues { it.value.toJsonListOfStrings() },
        slotTypes = item[CFEP.slotTypes].toJsonMapOrEmpty().mapValues { it.value.toOptStr().orEmpty() },
        defs = item[SCH.dDefs].toJsonMapOrEmpty(),
        codeConfigs = item[CFEP.codeConfigs].toJsonListOfMaps().map { c ->
            CodeBundle(
                name = c[CFEP.name].toOptStr().orEmpty(),
                template = c[CFEP.template].toOptStr()?.takeIf { c[CFEP.origin] == CFEP.originTemplate },
                namespace = c[CFEP.namespaceField].toOptStr().orEmpty(),
                slots = c[CFEP.slots].toJsonMapOrEmpty().mapValues { it.value.toJsonListOfMaps() },
            )
        },
        replacements = item[CFEP.replacements].toJsonListOfMaps().map { parseReplacement(it) },
    )
}

/** Where a code bundle's entries come from, in a phrase: "from code", or "from template starterKit". */
fun codeFromText(bundle: CodeBundle): String = bundle.template?.let { "from template $it" } ?: "from code"

/**
 * What a code bundle's line says (issue #1646): where it comes from, what it holds, and how many of its entries the
 * client changes in the stored bundle of its name.
 */
fun codeBundleLineText(bundle: CodeBundle, replacements: List<CodeReplacementView>): String {
    val entries = bundle.slots.values.sumOf { it.size }
    val held = "${bundle.slots.size} slot${if (bundle.slots.size == 1) "" else "s"}, $entries entr${if (entries == 1) "y" else "ies"}"
    val changed = replacements.count { it.codeName == bundle.name && it.name == bundle.name }
    return "${bundle.name} — ${codeFromText(bundle)} · $held" + if (changed == 0) "" else " · $changed changed here"
}

/** What the page says of a code bundle when it is opened: what Edit does, and how a change is undone. */
fun codeBundleNoteText(bundle: CodeBundle): String =
    "These are ${bundle.template?.let { "template $it's code, as this client runs it" } ?: "declared in this client's code"}. " +
        "Edit one to change it for this client: the change is stored in a bundle of the same name, marked to stand over " +
        "the code's entry, which it replaces whole. Revert to code removes the change, and the code's comes back. An " +
        "entry of the code's is never removed; a workflow the client does not want is turned off on its page instead."

/**
 * The replacement standing over [entry], an entry of the code bundle [codeName] in [slot] (issue #1646), or null when
 * nothing does. The columns of the forms list replace as a set: see [slotReplacement].
 */
fun codeReplacementOf(
    replacements: List<CodeReplacementView>,
    codeName: String,
    slot: String,
    entry: Map<String, Any?>,
): CodeReplacementView? =
    replacements.firstOrNull { it.codeName == codeName && it.slot == slot && it.key.isNotEmpty() && entry in it.codeEntries }

/**
 * [entry], an entry of a code bundle, as the stored entry that changes it starts (issue #1646): the code's own, marked
 * to stand over it.
 */
fun overrideOf(entry: Map<String, Any?>): Map<String, Any?> = entry + (CCT.overrides to CCT.overridesCodeValue)

/**
 * Whether [data] -- a new entry's, as parsed -- is a change of [codeEntry], keyed by [keyFields] (issue #1646): the same
 * key, so the code's entry is shown as being changed rather than beside it.
 */
fun changesCodeEntry(keyFields: List<String>, data: Map<String, Any?>?, codeEntry: Map<String, Any?>): Boolean =
    data != null && keyFields.isNotEmpty() && addressesEntry(keyFields, keyFields.associateWith { codeEntry[it] }, data)

/**
 * What a stored entry's line adds for the code it stands over (issue #1646): changed here, in the bundle named like the
 * code's; a replacement of it, in another; and that it needs the mark, when it has none.
 */
fun replacesText(r: CodeReplacementView, bundle: String): String = when {
    !r.marked -> "matches ${r.codeFrom}'s entry, without the mark"
    bundle == r.codeName -> "changed here"
    else -> "replaces ${r.codeFrom}'s, in ${r.codeName}"
}

/** Whose code [this] replaces, in a phrase: "the code", or "template starterKit". */
private val CodeReplacementView.codeFrom: String get() = template?.let { "template $it" } ?: "the code"

/**
 * The replacement [entry] is, in [bundle] and [slot], keyed by [keyFields]; null when it replaces nothing. The columns of
 * the forms list replace as a set, so none of theirs is matched here: see [slotReplacement].
 */
fun replacementOf(
    replacements: List<CodeReplacementView>,
    bundle: String,
    slot: String,
    keyFields: List<String>,
    entry: Map<String, Any?>,
): CodeReplacementView? {
    if (keyFields.isEmpty()) return null
    return replacements.firstOrNull {
        it.name == bundle && it.slot == slot && it.key.isNotEmpty() && addressesEntry(keyFields, it.key, entry)
    }
}

/** The replacement a whole [slot] of [bundle] is -- the columns of the forms list -- or null. */
fun slotReplacement(replacements: List<CodeReplacementView>, bundle: String, slot: String): CodeReplacementView? =
    replacements.firstOrNull { it.name == bundle && it.slot == slot && it.key.isEmpty() }

/** The heading over the code's version of what [r] replaces. */
fun replacedHeadingText(r: CodeReplacementView): String =
    "${r.codeFrom.replaceFirstChar { it.uppercase() }}'s, in its ${r.codeName} bundle, which this entry replaces"

/** What a slot that replaces the code's as a set says: the columns of the forms list. */
fun slotReplacementText(r: CodeReplacementView): String {
    val n = r.codeEntries.size
    return "These replace all of ${r.codeFrom}'s ${editorSlotLabel(r.slot).lowercase()} " +
        "($n, in its ${r.codeName} bundle): a client that declares any declares the whole set."
}

/**
 * What a save's dry run says of [replacing], the code entries the edits would newly replace (issues #1616, #1646), each
 * by its slot and key, so the save asks with that in view.
 */
fun replacingText(replacing: List<CodeReplacementView>): List<String> = replacing.map { r ->
    val what = if (r.key.isEmpty()) "all of ${r.codeFrom}'s ${editorSlotLabel(r.slot).lowercase()}"
    else "${editorSlotLabel(r.slot)} ${r.key.values.joinToString(" · ")}"
    "$what, from ${r.codeFrom}'s ${r.codeName} bundle, in ${r.name}"
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

/**
 * [bundle]'s latest revision in portable form, for [client] -- the configuration's owner -- as a bundle write takes it:
 * its name, its namespace, and its slots, with the client's own namespace written `client.@self` (issue #1435), so it
 * can be written as it is into another client, which every write resolves it for. The client's definition stays as
 * stored ([slotsToPortable]).
 */
fun portableBundle(bundle: EditorBundle, client: String): Map<String, Any?> = linkedMapOf(
    CFEP.name to bundle.name,
    CFEP.namespaceField to toPortable(bundle.latest.namespace, client),
    CFEP.slots to slotsToPortable(bundle.latest.slots, client),
)

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
