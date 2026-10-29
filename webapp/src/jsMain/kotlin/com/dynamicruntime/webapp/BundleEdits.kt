package com.dynamicruntime.webapp

import com.dynamicruntime.common.gedra.ACEP
import com.dynamicruntime.common.gedra.CCT
import com.dynamicruntime.common.gedra.CFEP
import com.dynamicruntime.common.gedra.GE
import com.dynamicruntime.common.gedra.GED
import com.dynamicruntime.common.gedra.GedraEditAction
import com.dynamicruntime.common.gedra.PORT
import com.dynamicruntime.common.gedra.addressesEntry
import com.dynamicruntime.common.gedra.canonicalKey
import com.dynamicruntime.common.gedra.fromPortable
import com.dynamicruntime.common.gedra.sandboxOf
import com.dynamicruntime.common.gedra.sandboxParentOf
import com.dynamicruntime.common.gedra.toPortable
import com.dynamicruntime.common.gedra.workflow.WFD
import com.dynamicruntime.common.naming.OWNR
import com.dynamicruntime.common.schema.SCH
import com.dynamicruntime.common.schema.SchOpts
import com.dynamicruntime.common.schema.SchType
import com.dynamicruntime.common.schema.parseSchemaTypes
import com.dynamicruntime.common.schema.validate
import com.dynamicruntime.common.util.Parsed
import com.dynamicruntime.common.util.jsonMapResult
import com.dynamicruntime.common.util.toJsonListOfMaps
import com.dynamicruntime.common.util.toJsonMapOrEmpty
import com.dynamicruntime.common.util.toJsonStr
import com.dynamicruntime.common.util.toOptStr

/*
 * The bundle editor's editing, its pure half (issue #1460): the edits a page holds until it saves them, what is wrong
 * with each as it is typed, and the one patch that saves them all. Pure, and covered under `jsTest`.
 */

/**
 * Who may edit on the bundle editor page, and what it says when they may not: a [note], or -- for a client edited from
 * its sandbox -- the [sandbox] whose page the page leads to.
 */
class BundleEditGate(val editable: Boolean, val note: String?, val sandbox: String? = null)

/**
 * Whether the page edits [row]'s configuration: a client without a sandbox edits its own, and a sandbox's page edits
 * its parent's, as drafts it runs. A client with a sandbox is edited from the sandbox, as it is published from it, and
 * the page leads there; a client that takes nothing stored on this node is not edited at all. Nothing is offered until
 * the row is known.
 */
fun bundleEditGate(row: ClientOverview?): BundleEditGate = when {
    row == null -> BundleEditGate(false, null)
    row.staticHere -> BundleEditGate(false, "This client takes no stored configuration on this server, so there is nothing to edit here.")
    row.hasSandbox && row.sandboxOf == null -> BundleEditGate(false, null, sandboxOf(row.clientId))
    else -> BundleEditGate(true, null)
}

/**
 * One entry the page holds an edit of: where it is ([bundle], [slot]), what it was when read ([original], null for a
 * new entry, and [stamp], its `updatedAt`), the JSON as it now stands ([text]), and whether it is to be [removed].
 * The text is in the form the page shows -- [portable] or as stored -- for [client], the configuration's owner.
 */
class EntryDraft(
    val bundle: String,
    val slot: String,
    val original: Map<String, Any?>?,
    val stamp: Any?,
    val text: String,
    val removed: Boolean = false,
    val client: String = "",
    val portable: Boolean = false,
) {
    /**
     * Whether saving would change anything: a removal, a new entry, or text other than the entry as read, shown in the
     * same form -- so opening an entry in portable form is no edit.
     */
    val isEdit: Boolean get() = removed || original == null || text != entryText(entryShown(original, slot, client, portable))

    fun withText(text: String) = EntryDraft(bundle, slot, original, stamp, text, removed, client, portable)
    fun withRemoved(removed: Boolean) = EntryDraft(bundle, slot, original, stamp, text, removed, client, portable)
}

/**
 * Every edit the page holds, until it saves or discards them: the entry drafts by id ([existingEntryId], or a new
 * entry's from [nextNew]), in the order they were made, and the bundles added by name and not written yet -- a bundle
 * is created when the save writes its first entry.
 */
class BundleEdits(
    val drafts: Map<String, EntryDraft> = emptyMap(),
    val newBundles: List<String> = emptyList(),
    val nextNew: Int = 1,
) {
    /** How many edits a save would send. */
    val count: Int get() = drafts.values.count { it.isEdit }

    fun with(id: String, draft: EntryDraft) = BundleEdits(drafts + (id to draft), newBundles, nextNew)

    /** Without the draft [id]: its entry back as read, or a new entry dropped. */
    fun without(id: String) = BundleEdits(drafts - id, newBundles, nextNew)

    /**
     * With a new entry of [slot] in [bundle], its text [text] in the page's form ([portable]) for [client]; returns the
     * edits and the new entry's id.
     */
    fun adding(bundle: String, slot: String, text: String, client: String = "", portable: Boolean = false): Pair<BundleEdits, String> {
        val id = "new#$nextNew"
        return BundleEdits(drafts + (id to EntryDraft(bundle, slot, null, null, text, client = client, portable = portable)), newBundles, nextNew + 1) to id
    }

    fun addingBundle(name: String) = BundleEdits(drafts, newBundles + name, nextNew)

    /** Without the bundle [name] added on the page, and its new entries. */
    fun withoutNewBundle(name: String) = BundleEdits(drafts.filterValues { it.bundle != name }, newBundles - name, nextNew)

    /** Without the drafts [ids] -- those a stale save named, say. */
    fun withoutAll(ids: Collection<String>) = BundleEdits(drafts - ids.toSet(), newBundles, nextNew)

    /**
     * Every draft's text rewritten in the other form -- [portable], or as stored -- as the page's setting changes; null
     * when the text of a draft not being removed does not parse, since it cannot be rewritten then ([formSwitchable]); a removal's text
     * is not sent, and stays as it is. The text is laid out afresh, as the editor lays out an entry it opens.
     */
    fun inForm(portable: Boolean): BundleEdits? = BundleEdits(
        drafts.mapValues { (_, d) ->
            val data = d.text.jsonMapResult().valueOrNull()
            val text = when {
                d.slot in PORT.keptSlots -> d.text
                data != null -> entryText(entryShown(entryResolved(data, d.slot, d.client), d.slot, d.client, portable))
                d.removed -> d.text
                else -> return null
            }
            EntryDraft(d.bundle, d.slot, d.original, d.stamp, text, d.removed, d.client, portable)
        },
        newBundles,
        nextNew,
    )
}

/**
 * [data], an entry of [slot] as stored, in the form the page shows it: [portable] -- [client]'s own namespace written
 * `client.@self` (issue #1435) -- or as stored. The client's definition and customers are as stored either way.
 */
fun entryShown(data: Map<String, Any?>, slot: String, client: String, portable: Boolean): Map<String, Any?> =
    if (!portable || slot in PORT.keptSlots) data else toPortable(data, client).toJsonMapOrEmpty()

/** [data] as typed, resolved for [client] as every write resolves it: what is checked, compared, and sent. */
fun entryResolved(data: Map<String, Any?>, slot: String, client: String): Map<String, Any?> =
    if (slot in PORT.keptSlots) data else fromPortable(data, client).toJsonMapOrEmpty()

/** Whether the page's form can be switched: the text of every draft not being removed parses, so it can be rewritten in the other form. */
fun formSwitchable(edits: BundleEdits): Boolean = edits.drafts.values.all { it.removed || it.text.jsonMapResult() is Parsed.Ok }

/** An entry's JSON as the page shows it for editing. */
fun entryText(data: Map<String, Any?>): String = data.toJsonStr()

/**
 * The id of [data], an entry of [slot] in [bundle] as read: by its key fields' values, so it names the same entry
 * after the page reads everything again; by its place ([index]) when it lacks a key value.
 */
fun existingEntryId(bundle: String, slot: String, key: List<String>, data: Map<String, Any?>, index: Int): String {
    val values = key.map { data[it] ?: return "$bundle/$slot/#$index" }
    return "$bundle/$slot/" + values.joinToString(",") { canonicalKey(it) }
}

/** What a new entry of a slot keyed by [key] starts as: its key fields, to be filled in. */
fun newEntryText(key: List<String>): String = entryText(key.associateWith { "" })

/** The name a new bundle may not take, or null when it may: it must be a plain name, and not one the client has. */
fun newBundleNameProblem(name: String, view: BundleEditorView, edits: BundleEdits): String? = when {
    name.isBlank() -> "Give the bundle a name."
    !name.matches(Regex("[A-Za-z][A-Za-z0-9_]*")) -> "A bundle's name is letters, digits and underscores, starting with a letter."
    view.configs.any { it.name == name } || name in edits.newBundles -> "The client already has a bundle named $name."
    else -> null
}

/**
 * The slots [bundle] can be given (issue #1460): those it does not hold yet, in the page's order -- leaving out a
 * single-instance slot another bundle holds, since the client has one of it.
 */
fun addableSlots(bundle: String, view: BundleEditorView, edits: BundleEdits): List<String> {
    val held = view.configs.firstOrNull { it.name == bundle }?.latest?.slots?.keys.orEmpty() +
        edits.drafts.values.filter { it.bundle == bundle }.map { it.slot }
    val singleTaken = view.configs.flatMap { c -> c.latest.slots.filterValues { it.isNotEmpty() }.keys } +
        edits.drafts.values.filter { it.original == null }.map { it.slot }
    return (editorSlotOrder + view.slotTypes.keys.sorted()).distinct()
        .filter { it in view.slotTypes && it !in held }
        .filterNot { view.slotKeys[it].orEmpty().isEmpty() && it in singleTaken }
}

/**
 * Each slot's data type -- the `data` property of its entry type -- from the editor read's [BundleEditorView.slotTypes]
 * and `$defs`, parsed once by the kernel's own parser. Empty when the definitions do not parse: then nothing is checked
 * against a schema here, and the save's dry run still is.
 */
fun slotDataTypes(view: BundleEditorView): Map<String, SchType> {
    val types = try {
        parseSchemaTypes(view.defs)
    } catch (e: Exception) {
        console.warn("[kdr] the bundle editor's slot schemas did not parse: ${e.message}")
        return emptyMap()
    }
    return view.slotTypes.mapNotNull { (slot, typeName) -> types[typeName]?.properties?.get(GE.data)?.valueType?.let { slot to it } }.toMap()
}

/** An entry's draft checked: its data, when its text parses to an object, and what is wrong with it, in words. */
class EntryCheck(val data: Map<String, Any?>?, val problems: List<String>) {
    val ok: Boolean get() = problems.isEmpty()
}

/** How the backend's gate validates an entry: a schema body is taken as the object it is, its parse being the trial's. */
private val unparsedBodies = SchOpts(schemaDocumentsUnparsed = true)

/**
 * What is wrong with the draft [id] (issue #1460), as it is typed:
 *  - its JSON must parse to an object, a syntax error said with its line;
 *  - its data is held to its slot's data type ([dataTypes]) by the kernel's validator, each failure by path, leaving to
 *    the save's trial what the backend's gate leaves to it (inside a report's definition, or a template workflow's);
 *  - an existing entry's key fields may not change, and a new entry's key may not be one the slot holds -- in its
 *    bundle or another, unless it is being removed from another (a move) -- or another new entry's.
 * A removal is not checked: what it removes was read.
 */
fun checkEntry(id: String, view: BundleEditorView, edits: BundleEdits, dataTypes: Map<String, SchType>): EntryCheck {
    val draft = edits.drafts[id] ?: return EntryCheck(null, emptyList())
    if (draft.removed) return EntryCheck(draft.original, emptyList())
    // Checked as a write takes it: resolved for the client, whichever form it was typed in.
    val data = when (val parsed = draft.text.jsonMapResult()) {
        is Parsed.Failed -> return EntryCheck(null, parsed.problems.map { p -> p.location?.line?.let { "Line $it: ${p.message}" } ?: p.message })
        is Parsed.Ok -> parsed.value?.let { entryResolved(it, draft.slot, draft.client) }
            ?: return EntryCheck(null, listOf("An entry is a JSON object, not null."))
    }
    if (draft.original != null && data == draft.original) return EntryCheck(data, emptyList())
    val key = view.slotKeys[draft.slot].orEmpty()
    val problems = mutableListOf<String>()
    dataTypes[draft.slot]?.let { type ->
        validate(type, data, unparsedBodies).filterNot { leftToTheTrial(draft.slot, data, it.path) }
            .forEach { problems.add(if (it.path.isEmpty()) it.message else "${it.path}: ${it.message}") }
    }
    problems.addAll(keyProblems(id, draft, data, key, view, edits))
    return EntryCheck(data, problems)
}

/** Whether a failure at [path] is the trial's to judge, as the backend's gate decides it (`ConfigSlotGate`). */
private fun leftToTheTrial(slot: String, data: Map<String, Any?>, path: String): Boolean {
    val inDefinition = path.startsWith("${CCT.definition}.") || path.startsWith("${CCT.definition}[")
    val overlay = slot == CCT.workflowDef && data[CCT.definition].toJsonMapOrEmpty()[WFD.fromTemplate] != null
    return inDefinition && (slot == CCT.reportDef || overlay)
}

private fun keyText(key: List<String>, data: Map<String, Any?>): String = key.joinToString(", ") { "$it ${data[it]}" }

private fun keyProblems(
    id: String,
    draft: EntryDraft,
    data: Map<String, Any?>,
    key: List<String>,
    view: BundleEditorView,
    edits: BundleEdits,
): List<String> {
    val missing = key.filter { data[it] == null || (data[it] as? String)?.isBlank() == true }
    if (missing.isNotEmpty()) return listOf("Its key ${if (missing.size == 1) "field" else "fields"} ${missing.joinToString(", ")} must be given.")
    val original = draft.original
    if (original != null) {
        return if (addressesEntry(key, original, data)) {
            emptyList()
        } else {
            listOf("Its key (${keyText(key, original)}) cannot change. Remove this entry and add a new one instead.")
        }
    }
    // A new entry: the slot's entries in every bundle, as read, and the other new entries.
    val noun = if (key.isEmpty()) "The client's one ${editorSlotLabel(draft.slot)} entry" else "An entry keyed (${keyText(key, data)})"
    for (bundle in view.configs) {
        bundle.latest.slots[draft.slot].orEmpty().forEachIndexed { i, held ->
            if (!addressesEntry(key, held, data)) return@forEachIndexed
            val removed = edits.drafts[existingEntryId(bundle.name, draft.slot, key, held, i)]?.removed == true
            when {
                !removed -> return listOf("$noun is already in bundle ${bundle.name}.")
                // Removed and added in one bundle: the save would say the entry was there when it was read.
                bundle.name == draft.bundle -> return listOf("$noun is being removed from this bundle: edit that entry instead.")
            }
        }
    }
    val twin = edits.drafts.entries.firstOrNull { (other, d) ->
        other != id && d.original == null && d.slot == draft.slot &&
            (d.text.jsonMapResult().valueOrNull()?.let { addressesEntry(key, entryResolved(it, d.slot, d.client), data) } == true)
    }
    return if (twin != null) listOf("$noun is also being added in bundle ${twin.value.bundle}.") else emptyList()
}

/** The checks of every draft that is an edit, by id. */
fun checkEdits(view: BundleEditorView, edits: BundleEdits, dataTypes: Map<String, SchType>): Map<String, EntryCheck> =
    edits.drafts.filterValues { it.isEdit }.mapValues { (id, _) -> checkEntry(id, view, edits, dataTypes) }

/**
 * The one patch that saves [edits] (issue #1460), every bundle's together: per bundle, its removals, then its changed
 * entries, then its new ones, each saying what it read -- an entry's `updatedAt` as [CFEP.expectUpdatedAt], or
 * [CFEP.expectNew] for a new one -- so a save over somebody else's change is refused rather than made. Bundles in the
 * page's order, those added last. [checks] holds each edit's parsed data; null when any edit fails its checks, or there
 * is none. A save reloads the client; a [dryRun] stores nothing.
 */
fun bundlePatchRequest(
    clientId: String,
    acrossClients: Boolean,
    view: BundleEditorView,
    edits: BundleEdits,
    checks: Map<String, EntryCheck>,
    dryRun: Boolean,
): ApiRequest? {
    val sent = edits.drafts.filter { it.value.isEdit }
    if (sent.isEmpty() || sent.keys.any { checks[it]?.ok != true }) return null
    val bundles = (editorBundlesInOrder(view.configs).map { it.name } + edits.newBundles).filter { name -> sent.values.any { it.bundle == name } }
    val configs = bundles.map { name ->
        val mine = sent.entries.filter { it.value.bundle == name }
        fun edit(draft: EntryDraft, action: GedraEditAction, data: Map<String, Any?>) = buildMap {
            put(CFEP.slot, draft.slot)
            put(GED.action, action.name)
            put(GE.data, data)
            if (draft.original == null) put(CFEP.expectNew, true) else draft.stamp?.let { put(CFEP.expectUpdatedAt, it) }
        }
        val removals = mine.filter { it.value.removed && it.value.original != null }.map { (_, d) ->
            val key = view.slotKeys[d.slot].orEmpty()
            val original = d.original.orEmpty()
            edit(d, GedraEditAction.deleteOrNoOp, if (key.isEmpty()) original else original.filterKeys { it in key })
        }
        val changes = mine.filter { !it.value.removed && it.value.original != null }.map { (id, d) ->
            edit(d, GedraEditAction.addOrReplace, checks[id]?.data.orEmpty())
        }
        val additions = mine.filter { !it.value.removed && it.value.original == null }.map { (id, d) ->
            edit(d, GedraEditAction.addOrReplace, checks[id]?.data.orEmpty())
        }
        mapOf(CFEP.name to name, CFEP.edits to removals + changes + additions)
    }
    val body = buildMap {
        if (acrossClients) put(CFEP.client, sandboxParentOf(clientId) ?: clientId)
        put(CFEP.configs, configs)
        if (dryRun) put(CFEP.dryRun, true) else put(CFEP.reloadField, true)
    }
    return ApiRequest(if (acrossClients) ACEP.bundlePatch else CFEP.bundlePatch, body)
}

/** What a save sends, for a developer to send it themselves: the method and the whole path, then the body. */
fun patchCallLine(request: ApiRequest, contextRoot: String): String = "POST $contextRoot${request.path}"

/** An entry a stale save named (issue #1434): its configuration, slot, and key fields, and what happened to it. */
class StaleEntryView(val bundle: String, val slot: String, val key: Map<String, Any?>, val text: String)

/** The entries a save was refused over because they changed since they were read, or null for any other error. */
fun staleEntriesOf(e: Throwable): List<StaleEntryView>? {
    val api = e as? ApiError ?: return null
    if (api.errorCode != CFEP.staleCode) return null
    return api.extraData[CFEP.conflicts].toJsonListOfMaps().map {
        val bundle = it[CFEP.name].toOptStr().orEmpty()
        val slot = it[CFEP.slot].toOptStr().orEmpty()
        val key = it[CFEP.key].toJsonMapOrEmpty()
        val what = when {
            it[CFEP.expectNew] == true -> "was added by somebody else since this page read it"
            it[CFEP.updatedAt] == null -> "was removed since this page read it"
            else -> "was changed since this page read it"
        }
        val named = key.entries.joinToString(", ") { (k, v) -> "$k $v" }.ifEmpty { editorSlotLabel(slot) }
        StaleEntryView(bundle, slot, key, "${editorSlotLabel(slot)} ($named) in bundle $bundle $what.")
    }
}

/** The ids of the drafts [stale] names: the edits to drop before the page reads the entries again. */
fun staleDraftIds(stale: List<StaleEntryView>, view: BundleEditorView, edits: BundleEdits): List<String> =
    edits.drafts.filter { (_, d) ->
        val data = d.original ?: d.text.jsonMapResult().valueOrNull()?.let { entryResolved(it, d.slot, d.client) } ?: return@filter false
        stale.any { s -> s.bundle == d.bundle && s.slot == d.slot && addressesEntry(view.slotKeys[d.slot].orEmpty(), data, s.key) }
    }.keys.toList()

/**
 * The other clients whose namespaces [data] names (issue #1461): JSON pasted from another client's configuration, as
 * it stored it, names `client.<other>` in its type names and `$defs` pointers. Every string and key is looked at;
 * [client]'s own namespace and the portable one (`client.@self`) are not another's.
 */
fun foreignClients(data: Any?, client: String): Set<String> {
    val found = LinkedHashSet<String>()
    val prefix = "${OWNR.clientRoot}${OWNR.namespaceSep}"
    fun look(s: String) {
        val name = s.removePrefix("#/${SCH.dDefs}/")
        if (!name.startsWith(prefix)) return
        val other = name.substring(prefix.length).substringBefore(OWNR.namespaceSep)
        if (other.isNotEmpty() && other != client && other != PORT.self) found.add(other)
    }
    fun walk(value: Any?) {
        when (value) {
            is Map<*, *> -> value.forEach { (k, v) -> (k as? String)?.let { look(it) }; walk(v) }
            is List<*> -> value.forEach { walk(it) }
            is String -> look(value)
        }
    }
    walk(data)
    return found
}

/**
 * [data] with the namespaces of [others] written as the portable one (issue #1461), so JSON pasted from another
 * client is taken as this client's: a save resolves `client.@self` for whichever client it is written to.
 */
fun withClientsAsSelf(data: Map<String, Any?>, others: Collection<String>): Map<String, Any?> =
    others.fold(data) { at, other -> toPortable(at, other).toJsonMapOrEmpty() }

/** What the page says of JSON naming [others]' namespaces, beside its offer to take it as this client's. */
fun foreignClientsText(others: Collection<String>, client: String): String {
    val named = others.joinToString(", ") { "${OWNR.clientRoot}${OWNR.namespaceSep}$it" }
    val whose = if (others.size == 1) "another client's namespace" else "other clients' namespaces"
    return "This JSON names $whose: $named. Pasted from there, it can be taken as $client's, written client.@self."
}

/** What the save bar says of the edits held. */
fun editCountText(count: Int, failing: Int): String {
    val held = when (count) {
        0 -> "No unsaved edits"
        1 -> "1 unsaved edit"
        else -> "$count unsaved edits"
    }
    return if (failing == 0) held else "$held · $failing ${if (failing == 1) "fails its" else "fail their"} checks"
}
