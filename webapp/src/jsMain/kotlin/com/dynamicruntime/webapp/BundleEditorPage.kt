package com.dynamicruntime.webapp

import com.dynamicruntime.common.endpoint.EP
import com.dynamicruntime.common.gedra.CCT
import com.dynamicruntime.common.gedra.CFEP
import com.dynamicruntime.common.gedra.PORT
import com.dynamicruntime.common.gedra.codeOverlayShown
import com.dynamicruntime.common.gedra.codeOverlayStale
import com.dynamicruntime.common.gedra.codeOverlaySlots
import com.dynamicruntime.common.gedra.isCodeOverlay
import com.dynamicruntime.common.home.PFRAG
import com.dynamicruntime.common.util.jsonMapResult
import com.dynamicruntime.common.util.toJsonListOfMaps
import com.dynamicruntime.common.util.toJsonMapOrEmpty
import com.dynamicruntime.common.util.toJsonStr
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.launch
import react.ChildrenBuilder
import react.FC
import react.Key
import react.Props
import react.dom.html.ReactHTML.a
import react.dom.html.ReactHTML.div
import react.dom.html.ReactHTML.h1
import react.dom.html.ReactHTML.li
import react.dom.html.ReactHTML.p
import react.dom.html.ReactHTML.span
import react.dom.html.ReactHTML.ul
import react.useEffect
import react.useMemo
import react.useRef
import react.useState
import web.cssom.ClassName

/** The bundle editor page's route (issue #1436), reached from a client's page: `page=bundleEditor&client=<id>`. */
const val pageBundleEditor = "bundleEditor"

/** Where a client's bundle editor is. */
fun bundleEditorHref(clientId: String): String = routeHref(listOf(HP.page to pageBundleEditor, HP.client to clientId))

private val bundleEditorScope = MainScope()

/**
 * The bundle editor (issues #1436, #1460): every configuration a client stores, on one page. Each configuration opens
 * to its slots, each slot to its entries, and each entry to its JSON -- the draft's beside the published revision's,
 * when the configuration is a draft over one. Above them, the client page's own table of the configurations, which
 * publishes them with the impact report.
 *
 * Where the page may edit ([bundleEditGate]), an entry's JSON is edited in place and checked as it is typed
 * ([checkEntry]); entries, slots, and bundles can be added, and entries removed. The edits are held on the page until
 * a save sends them all in one patch ([bundlePatchRequest]): a dry run first, whose impact on the client's forms must
 * be confirmed, then the patch, which reloads the client. **Show the call** shows that request.
 *
 * Wide, since JSON side by side needs the room, and drawn only as it is opened, since a client's configuration can be
 * long. A client with a sandbox shows its own configuration, drafts included, on its page and on its sandbox's alike:
 * the reads name the configuration's owner.
 */
val BundleEditorPage = FC<Props> {
    val generation = useRefreshGeneration()
    // The page's explanations, from the Clients area's copy file, which a client may reword.
    val pageCopy = usePageCopy(PFRAG.clients)
    val bump = useRefreshBump()
    var config by useState<HomeConfig?>(null)
    var row by useState<ClientOverview?>(null)
    var summaries by useState<List<ConfigSummaryView>?>(null)
    var editor by useState<BundleEditorView?>(null)
    var note by useState<String?>(null)
    var error by useState<DisplayError?>(null)
    var edits by useState(BundleEdits())
    var saving by useState(false)
    var saveError by useState<DisplayError?>(null)
    var stale by useState<List<StaleEntryView>?>(null)
    var impact by useState<ImpactReportView?>(null)
    // What a save's dry run said the edits would newly replace of the client's code (issues #1616, #1646), to confirm.
    var replacing by useState<List<CodeReplacementView>?>(null)
    var savedNote by useState<String?>(null)
    var showCall by useState(false)
    var newBundleName by useState("")
    // Whether entries are shown, and edited, in portable form (issue #1460): one setting for the page, remembered.
    var portable by useState { localStorageGet(portableStorageKey, "bundle editor form") == "true" }
    val latest = useRef(0)
    val routeClient = routeParams()[HP.client]

    useEffect(routeClient, generation) {
        val token = (latest.current ?: 0) + 1
        latest.current = token
        bundleEditorScope.launch {
            try {
                val shell = HomeApi.fetchConfig()
                val own = shell.user.client.orEmpty()
                val clientId = clientsOpenId(routeClient, shell.canManageUsers, shell.canSeeAllClients, own)
                if (clientId == null) {
                    if (latest.current == token) note = "Open this page from a client's page."
                    return@launch
                }
                val overview = if (shell.canManageUsers) ClientsApi.listOverview().firstOrNull { it.clientId == clientId } else null
                val read = ClientsApi.bundleEditor(clientId, shell.canSeeAllClients, own)
                val listed = ClientsApi.storedConfigs(clientId, shell.canSeeAllClients, own)
                if (latest.current != token) return@launch
                config = shell
                row = overview
                editor = read
                summaries = listed
                note = if (read == null) "Only this client's own administrators, or one who sees every client, can read its configuration." else null
                error = null
            } catch (e: Throwable) {
                if (latest.current == token) error = userFacingError(e)
            }
        }
    }

    // Edits are kept on the page until saved, so leaving it with some asks first.
    val dirty = edits.count > 0
    useEffect(dirty, routeClient) {
        if (dirty) {
            val client = routeClient
            LeaveGuard.arm({ h -> h[HP.page] == pageBundleEditor && h[HP.client] == client }) {
                LeaveGuard.confirmLeave("You have unsaved edits to this client's configuration. Leave the page and lose them?")
            }
        } else {
            LeaveGuard.disarm()
        }
    }

    val shown = editor
    val dataTypes = useMemo(shown) { shown?.let { slotDataTypes(it) } ?: emptyMap() }
    val clientId = row?.clientId ?: shown?.client ?: routeClient
    val gate = bundleEditGate(row)
    val acrossClients = config?.canSeeAllClients == true
    val checks = if (shown == null) emptyMap() else checkEdits(shown, edits, dataTypes)
    val failing = checks.count { !it.value.ok }

    fun changeEdits(next: BundleEdits) {
        edits = next
        savedNote = null
        stale = null
        saveError = null
        impact = null
        replacing = null
    }

    fun save(confirmed: Boolean) {
        val view = shown ?: return
        val id = clientId ?: return
        val dry = bundlePatchRequest(id, acrossClients, view, edits, checks, dryRun = true) ?: return
        val real = bundlePatchRequest(id, acrossClients, view, edits, checks, dryRun = false) ?: return
        val count = edits.count
        saving = true
        saveError = null
        stale = null
        bundleEditorScope.launch {
            try {
                if (!confirmed) {
                    val answer = Http.sendApi("POST", dry.path, dry.body)[EP.results].toJsonMapOrEmpty()
                    val report = parseImpactReport(answer[CFEP.impact].toJsonMapOrEmpty())
                    val replaces = answer[CFEP.replacements].toJsonListOfMaps().map { parseReplacement(it) }
                    if (report.blocks || replaces.isNotEmpty()) {
                        impact = report.takeIf { it.blocks }
                        replacing = replaces.ifEmpty { null }
                        return@launch
                    }
                }
                Http.sendApi("POST", real.path, real.body)
                edits = BundleEdits()
                impact = null
                replacing = null
                showCall = false
                savedNote = "Saved ${if (count == 1) "1 edit" else "$count edits"}${if (row?.sandboxOf != null) " as a draft" else ""}, and reloaded the client."
                bump()
            } catch (e: Throwable) {
                impact = null
                replacing = null
                val named = staleEntriesOf(e)
                if (named != null) stale = named else saveError = userFacingError(e)
            } finally {
                saving = false
            }
        }
    }

    div {
        className = ClassName("card full bundle-editor")
        clientId?.let { id ->
            p {
                a {
                    className = ClassName("wf-cell-link")
                    href = clientDetailHref(id)
                    +"← Back to ${row?.name ?: id}"
                }
            }
        }
        h1 { +"Configuration${row?.name?.let { " of $it" } ?: ""}" }
        p {
            className = ClassName("subtitle")
            +"Every configuration bundle this client stores, then what it runs from code, each code bundle with this client's "
            +"changes to it. Open a bundle to see its slots, a slot to see its entries, and an entry to see its JSON. A draft's "
            +"entries are shown beside the published revision's."
        }
        row?.sandboxOf?.let { parent ->
            p {
                className = ClassName("subtitle")
                +"A sandbox holds no configuration of its own: it runs the latest of $parent's, drafts included, which is what "
                +"this page shows. An edit saved here is a draft of $parent's, published from here."
            }
        }
        gate.note?.let { p { className = ClassName("subtitle"); +it } }
        gate.sandbox?.let { sandbox ->
            // The word leads to the sandbox's own bundle editor, as the publishing bar's "Publish from its sandbox" leads
            // to its page: straight there for an administrator who sees every client, and for a client-scoped one by
            // first opening the sandbox (#929) -- a fresh session as their user in it.
            p {
                className = ClassName("subtitle")
                +"This client has a sandbox: its configuration is edited in its "
                a {
                    className = ClassName("wf-cell-link")
                    href = bundleEditorHref(sandbox)
                    if (!acrossClients) {
                        title = "Open the sandbox and edit this configuration there."
                        onClick = { e ->
                            e.preventDefault()
                            bundleEditorScope.launch {
                                try {
                                    AuthApi.openSandbox()
                                    afterSessionChange(bump, listOf(HP.page to pageBundleEditor, HP.client to sandbox))
                                } catch (e: Throwable) {
                                    error = userFacingError(e)
                                }
                            }
                        }
                    }
                    +"sandbox"
                }
                +", as drafts, and published from there."
            }
        }
        error?.let { errorText("Couldn't load this client's configuration.", it) }
        note?.let { p { className = ClassName("subtitle"); +it } }
        if (shown == null && error == null && note == null) {
            p { className = ClassName("subtitle"); +"Loading…" }
        }
        val list = summaries
        if (shown != null && list != null && clientId != null) {
            WhatIsThis {
                heading = "Publishing"
                text = pageCopy.explanation(PFRAG.bundlePublishingNs)
            }
            // Closed to start, so the bundles get the room; its line says whether there is anything to publish.
            Disclosure {
                summaryText = publishingSummaryText(list)
                StoredConfigTable {
                    configs = list
                    this.row = row
                    this.acrossClients = acrossClients
                    this.clientId = clientId
                    onChanged = { bump() }
                    // Delete is here (issue #1461); a bundle with edits on the page waits for them.
                    deleteBlocked = edits.drafts.values.filter { it.isEdit }.map { it.bundle }.toSet()
                }
            }
            WhatIsThis {
                heading = "Bundles"
                text = pageCopy.explanation(PFRAG.bundleListNs)
            }
            if (gate.editable) {
                saveBar(
                    SaveBarState(
                        edits.count, failing, saving, showCall, savedNote, saveError, stale, impact, row?.sandboxOf != null,
                        impactFormsOpenable(acrossClients, row?.sandboxOf != null), replacing,
                    ),
                    onSave = { save(confirmed = false) },
                    onConfirm = { save(confirmed = true) },
                    onCancelImpact = {
                        impact = null
                        replacing = null
                    },
                    onToggleCall = { showCall = !showCall },
                    onDiscard = {
                        changeEdits(BundleEdits())
                        showCall = false
                    },
                    onReadAgain = {
                        val named = stale.orEmpty()
                        changeEdits(edits.withoutAll(staleDraftIds(named, shown, edits)))
                        bump()
                    },
                )
                if (showCall) {
                    val call = bundlePatchRequest(clientId, acrossClients, shown, edits, checks, dryRun = false)
                    div {
                        className = ClassName("bundle-editor-json")
                        if (call == null) {
                            p { className = ClassName("type-hint"); +"There is no call to show until every edit passes its checks." }
                        } else {
                            span { className = ClassName("type-hint"); +patchCallLine(call, apiContextRoot) }
                            JsonBlock { text = call.body.toJsonStr() }
                        }
                    }
                }
            }
            if (shown.configs.isEmpty() && edits.newBundles.isEmpty()) p { className = ClassName("subtitle"); +"This client stores no configuration." }
            val ed = EditContext(
                gate.editable, shown, edits, checks, { changeEdits(it) }, portable, formSwitchable(edits),
                onPortable = { next ->
                    // The texts being edited are rewritten in the new form, which the switch is offered only while they parse.
                    edits.inForm(next)?.let {
                        edits = it
                        portable = next
                        localStorageSet(portableStorageKey, next.toString(), "bundle editor form")
                    }
                },
            )
            // A stored bundle named like a code bundle holds the client's changes to it (issue #1646), drawn with it below.
            val codeNames = shown.codeConfigs.map { it.name }.toSet()
            val bundles = (editorBundlesInOrder(shown.configs) + edits.newBundles.map { newBundle(it) }).filter { it.name !in codeNames }
            // Said here, so every bundle the client stores is accounted for where the bundles are listed (issue #1657).
            codeNamedBundlesText(shown.configs, shown.codeConfigs)?.let { p { className = ClassName("type-hint"); +it } }
            for (bundle in bundles) {
                div {
                    key = bundle.name.unsafeCast<Key>()
                    Disclosure {
                        summaryText = bundleLineText(bundle, ed)
                        initiallyOpen = bundle.name in edits.newBundles
                        div {
                            className = ClassName("bundle-editor-actions")
                            if (bundle.name in edits.newBundles) {
                                // Not written yet: nothing to delete, only the page's own bundle to drop.
                                if (gate.editable) {
                                    Button {
                                        size = "small"
                                        onClick = { changeEdits(edits.withoutNewBundle(bundle.name)) }
                                        +"Discard this bundle"
                                    }
                                }
                            } else {
                                // The whole bundle, client-neutral, as a bundle write takes it: to write into another client.
                                CopyButton {
                                    text = portableBundle(bundle, shown.client).toJsonStr()
                                    label = "Copy this bundle as portable JSON, as stored, without unsaved edits"
                                    buttonText = "Copy as portable JSON"
                                }
                            }
                        }
                        for (slot in slotsWithDrafts(bundle, ed)) slotView(bundle, slot, ed)
                        if (ed.editable) addSlotControl(bundle.name, ed)
                    }
                }
            }
            if (gate.editable) {
                val problem = newBundleName.takeIf { it.isNotEmpty() }?.let { newBundleNameProblem(it, shown, edits) }
                div {
                    className = ClassName("bundle-editor-add")
                    Input {
                        value = newBundleName
                        placeholder = "New bundle's name"
                        onChange = { e -> newBundleName = e.target.value }
                    }
                    Button {
                        disabled = newBundleName.isEmpty() || problem != null
                        onClick = {
                            changeEdits(edits.addingBundle(newBundleName))
                            newBundleName = ""
                        }
                        +"Add bundle"
                    }
                    problem?.let { span { className = ClassName("type-hint"); +it } }
                }
                p { className = ClassName("type-hint"); +"A new bundle is created when a save writes its first entry: give it a slot first." }
            }
            if (shown.codeConfigs.isNotEmpty()) {
                WhatIsThis {
                    heading = "From code"
                    text = pageCopy.explanation(PFRAG.bundleCodeNs)
                }
                for (code in shown.codeConfigs) mergedBundleView(code, ed)
            }
        }
    }
}

/** A bundle added on the page and not written yet: no revision, nothing beside it. */
private fun newBundle(name: String): EditorBundle = EditorBundle(name, EditorRevision(0, false, emptyMap(), 0), null)

/**
 * One configuration of the client's code layer (issues #1616, #1646), merged with the stored bundle of its name, which
 * holds the client's changes to it. Each code entry is listed as the code declares it, marked where the client changes it
 * in another bundle; one changed here is shown as the stored entry, with the code's beside it and **Revert to code**; and
 * the stored bundle's other entries are the client's own, added here. **Edit** on a code entry starts the change: a new
 * entry of the stored bundle, the code's own marked `"overrides": "code"` -- or, for a workflow or a type, marked
 * `"overrides": "codeOverlay"`, which a save stores as only what differs from the code's (issue #1657).
 */
private fun ChildrenBuilder.mergedBundleView(code: CodeBundle, ed: EditContext) {
    val codeBundle = code.asEditorBundle()
    val stored = ed.view.configs.firstOrNull { it.name == code.name } ?: newBundle(code.name)
    val keyed = ed.view.slotKeys
    val codeSlots = editorSlots(codeBundle, keyed)
    val storedSlots = slotsWithDrafts(stored, ed)
    val slots = (codeSlots.map { it.slot } + storedSlots.map { it.slot }).distinct()
    div {
        key = "code:${code.name}".unsafeCast<Key>()
        Disclosure {
            summaryText = codeBundleLineText(code, ed.view.replacements)
            p { className = ClassName("type-hint"); +codeBundleNoteText(code) }
            for (slot in slots) {
                val codeSlot = codeSlots.firstOrNull { it.slot == slot }
                val storedSlot = storedSlots.firstOrNull { it.slot == slot }
                div {
                    key = slot.unsafeCast<Key>()
                    className = ClassName("bundle-editor-slot")
                    Disclosure {
                        val line = codeSlot?.let { slotSummaryText(it) } ?: storedSlot?.let { slotSummaryText(it) }.orEmpty()
                        summaryText = codeSlotLineText(line, ed.view.replacements, code.name, slot)
                        val storedSet = slotReplacement(ed.view.replacements, stored.name, slot)
                        storedSet?.let { replacedSet(it, ed) }
                        if (codeSlot != null && editedAsSet(slot)) {
                            columnsSetView(code, stored, codeSlot, storedSlot, storedSet, ed)
                        } else {
                            codeSlot?.entries?.forEachIndexed { i, entry -> codeEntryView(code, stored, slot, entry, i, ed) }
                        }
                        storedSlot?.entries?.forEachIndexed { i, entry -> entryView(stored, slot, entry, i, ed, merged = true) }
                        if (ed.editable) newEntries(stored.name, slot, ed)
                    }
                }
            }
            if (ed.editable) addSlotControl(stored.name, ed)
        }
    }
}

/**
 * A code bundle's columns of the forms list (issue #1646), which replace as one set, so they are edited and reverted as
 * one, from the slot: **Edit the columns** starts the client's own set as the code's whole, each column marked; and
 * once the stored bundle of its name holds a set, **Revert the columns to code** removes every column of it, so the
 * code's set runs again. The code's columns are listed one by one only while the client has no set of its own.
 */
private fun ChildrenBuilder.columnsSetView(
    code: CodeBundle,
    stored: EditorBundle,
    codeSlot: EditorSlot,
    storedSlot: EditorSlot?,
    storedSet: CodeReplacementView?,
    ed: EditContext,
) {
    val slot = codeSlot.slot
    val keyFields = ed.view.slotKeys[slot].orEmpty()
    val drafting = ed.edits.drafts.values.any { it.bundle == stored.name && it.slot == slot && it.original == null }
    if (codeColumnsListed(storedSet, drafting)) {
        codeSlot.entries.forEachIndexed { i, entry -> codeEntryView(code, stored, slot, entry, i, ed) }
    }
    if (!ed.editable) return
    div {
        className = ClassName("bundle-editor-actions")
        when {
            storedSet != null -> {
                val ids = storedSlot?.entries.orEmpty().mapIndexedNotNull { i, entry ->
                    entry.latest?.let { existingEntryId(stored.name, slot, keyFields, it, i) to (entry.latest to i) }
                }
                val reverting = ids.isNotEmpty() && ids.all { (id, _) -> ed.edits.drafts[id]?.removed == true }
                Button {
                    size = "small"
                    disabled = reverting
                    onClick = {
                        var next = ed.edits
                        for ((id, held) in ids) {
                            val (data, at) = held
                            val base = next.drafts[id] ?: ed.draftOf(stored.name, slot, data, stored.stamps[slot]?.getOrNull(at))
                            next = next.with(id, base.withRemoved(true))
                        }
                        ed.change(next)
                    }
                    +"Revert the columns to code"
                }
                span {
                    className = ClassName("type-hint")
                    +(
                        if (reverting) " The code's columns run again when saved."
                        else " Removes this client's set, so the code's runs again."
                        )
                }
            }
            !drafting -> {
                Button {
                    size = "small"
                    onClick = {
                        var next = ed.edits
                        val unwritten = ed.view.configs.none { it.name == stored.name } && stored.name !in next.newBundles
                        if (unwritten) next = next.addingBundle(stored.name)
                        for (column in columnsOverride(codeSlot.entries.mapNotNull { it.latest })) {
                            next = next.adding(stored.name, slot, entryText(ed.shownData(column, slot)), ed.view.client, ed.portable).first
                        }
                        ed.change(next)
                    }
                    +"Edit the columns"
                }
                span {
                    className = ClassName("type-hint")
                    +" The columns replace as one set: this starts this client's own from all of the code's."
                }
            }
            else -> span {
                className = ClassName("type-hint")
                +"This client's own set of columns, which replaces the code's whole when saved."
            }
        }
    }
}

/**
 * One entry of a code bundle (issue #1646), as the code declares it: nothing when the stored bundle of its name changes
 * it -- that entry is shown instead, or the page's new change of it -- else marked where another bundle changes it, and
 * otherwise offering **Edit**, which starts the change in [stored].
 */
private fun ChildrenBuilder.codeEntryView(
    code: CodeBundle,
    stored: EditorBundle,
    slot: String,
    entry: EditorEntry,
    at: Int,
    ed: EditContext,
) {
    val data = entry.latest ?: return
    val keyFields = ed.view.slotKeys[slot].orEmpty()
    val replaced = codeReplacementOf(ed.view.replacements, code.name, slot, keyFields, data)
    // Changed here: the stored entry is shown, with this beside it.
    if (replaced != null && replaced.name == stored.name) return
    val changing = ed.edits.drafts.entries.any { (id, d) ->
        d.bundle == stored.name && d.slot == slot && d.original == null && changesCodeEntry(keyFields, ed.checks[id]?.data, data)
    }
    if (changing) return
    div {
        this.key = "code:${entry.label}#$at".unsafeCast<Key>()
        className = ClassName("bundle-editor-entry")
        Disclosure {
            summaryText = listOfNotNull(entry.label, replaced?.let { "changed in ${it.name}" }).joinToString(" — ")
            jsonColumn(codeFromText(code).replaceFirstChar { it.uppercase() }, slot, data, ed, withSwitch = true)
            // The columns are edited as one set, from the slot (`columnsSetView`).
            if (ed.editable && replaced == null && keyFields.isNotEmpty() && !editedAsSet(slot)) {
                div {
                    className = ClassName("bundle-editor-actions")
                    Button {
                        size = "small"
                        onClick = {
                            var next = ed.edits
                            // The stored bundle of its name, made on the page when the client has none yet.
                            val unwritten = ed.view.configs.none { it.name == stored.name } && stored.name !in next.newBundles
                            if (unwritten) next = next.addingBundle(stored.name)
                            // A workflow or a type is kept as a change to the code's (issue #1657); anything else, whole.
                            val overlay = slot in codeOverlaySlots
                            val text = entryText(ed.shownData(if (overlay) overlayOf(data) else overrideOf(data), slot))
                            val code = data.takeIf { overlay }
                            ed.change(next.adding(stored.name, slot, text, ed.view.client, ed.portable, code).first)
                        }
                        +"Edit"
                    }
                    if (slot == CCT.workflowDef) {
                        span {
                            className = ClassName("type-hint")
                            +" A workflow the client does not want is turned off on its page, not removed."
                        }
                    }
                }
            }
        }
    }
}

/**
 * One slot of [bundle]: what it replaces as a set, its entries, and, where the page edits, its new entries and **Add
 * entry**.
 */
private fun ChildrenBuilder.slotView(bundle: EditorBundle, slot: EditorSlot, ed: EditContext) {
    div {
        key = slot.slot.unsafeCast<Key>()
        className = ClassName("bundle-editor-slot")
        Disclosure {
            summaryText = slotSummaryText(slot)
            initiallyOpen = slot.entries.isEmpty()
            slotReplacement(ed.view.replacements, bundle.name, slot.slot)?.let { replacedSet(it, ed) }
            slot.entries.forEachIndexed { i, entry -> entryView(bundle, slot.slot, entry, i, ed) }
            if (ed.editable) {
                newEntries(bundle.name, slot.slot, ed)
                val keys = ed.view.slotKeys[slot.slot].orEmpty()
                if (keys.isNotEmpty()) {
                    Button {
                        size = "small"
                        onClick = {
                            val (next, _) = ed.edits.adding(bundle.name, slot.slot, newEntryText(keys), ed.view.client, ed.portable)
                            ed.change(next)
                        }
                        +"Add entry"
                    }
                }
            }
        }
    }
}

/** The code's version of what an entry replaces (issues #1616, #1646), closed to start: it does not run. */
private fun ChildrenBuilder.replacedVersion(r: CodeReplacementView, slot: String, ed: EditContext) {
    div {
        className = ClassName("bundle-editor-replaced")
        Disclosure {
            summaryText = replacedHeadingText(r)
            r.codeEntries.forEach { jsonColumn(r.template?.let { "Template $it" } ?: "Code", slot, it, ed) }
        }
    }
}

/** A slot that replaces the code's as a set (issue #1616), the columns of the forms list: what it says, and theirs. */
private fun ChildrenBuilder.replacedSet(r: CodeReplacementView, ed: EditContext) {
    p { className = ClassName("type-hint"); +slotReplacementText(r) }
    replacedVersion(r, r.slot, ed)
}

/**
 * What the editing parts of the page share: whether it edits, the read, the edits held and their checks, and the form
 * entries are shown in -- [portable], switched by [onPortable] while [switchable].
 */
private class EditContext(
    val editable: Boolean,
    val view: BundleEditorView,
    val edits: BundleEdits,
    val checks: Map<String, EntryCheck>,
    val change: (BundleEdits) -> Unit,
    val portable: Boolean,
    val switchable: Boolean,
    val onPortable: (Boolean) -> Unit,
) {
    /** [data], an entry of [slot] as stored, in the form the page shows. */
    fun shownData(data: Map<String, Any?>, slot: String): Map<String, Any?> = entryShown(data, slot, view.client, portable)

    /**
     * A new draft of [data], an entry of [slot] in [bundle] as read with [stamp], its text in the page's form -- an
     * overlay of [code], the code's entry, shown merged over it (issue #1657).
     */
    fun draftOf(bundle: String, slot: String, data: Map<String, Any?>, stamp: Any?, code: Map<String, Any?>? = null): EntryDraft {
        val draft = EntryDraft(bundle, slot, data, stamp, "", client = view.client, portable = portable, code = code)
        return draft.withText(entryText(shownData(draft.originalShown(), slot)))
    }
}

/** Where the bundle editor remembers whether entries are shown in portable form. */
private const val portableStorageKey = "kdrBundleEditorPortable"

/** A bundle's line, saying how many unsaved edits it holds. */
private fun bundleLineText(bundle: EditorBundle, ed: EditContext): String {
    val held = ed.edits.drafts.values.count { it.bundle == bundle.name && it.isEdit }
    val base = if (bundle.name in ed.edits.newBundles) "${bundle.name} — new, not written yet" else bundleSummaryText(bundle)
    return if (held == 0) base else "$base · $held unsaved edit${if (held == 1) "" else "s"}"
}

/** [bundle]'s slots as phase 1 lists them, and after them any slot only the page's new entries are in. */
private fun slotsWithDrafts(bundle: EditorBundle, ed: EditContext): List<EditorSlot> {
    val slots = editorSlots(bundle, ed.view.slotKeys)
    val added = ed.edits.drafts.values.filter { it.bundle == bundle.name && it.original == null }.map { it.slot }.distinct()
        .filter { s -> slots.none { it.slot == s } }
    return slots + added.map { EditorSlot(it, editorSlotLabel(it), emptyList()) }
}

/** One entry: its name and how it stands against the published revision, opening to its JSON, and its edit. */
private fun ChildrenBuilder.entryView(
    bundle: EditorBundle,
    slot: String,
    entry: EditorEntry,
    at: Int,
    ed: EditContext,
    /** Drawn in a code bundle's merged view (issue #1646), where an entry over no code entry is the client's, added here. */
    merged: Boolean = false,
) {
    val data = entry.latest
    val key = ed.view.slotKeys[slot].orEmpty()
    // The entry's place among the latest revision's: the published ones the draft removed come after them.
    val id = data?.let { existingEntryId(bundle.name, slot, key, it, at) }
    val draft = id?.let { ed.edits.drafts[it] }
    val check = id?.let { ed.checks[it] }
    // An entry of the client's that replaces one of its code's (issues #1616, #1646), judged on the latest revision.
    val replaced = data?.let { replacementOf(ed.view.replacements, bundle.name, slot, key, it) }
    // An overlay of the code's entry (issue #1657) is shown as the whole entry it makes, merged over the code's.
    val code = replaced?.codeEntries?.singleOrNull()?.takeIf { data?.isCodeOverlay == true && slot in codeOverlaySlots }
    fun shown(raw: Map<String, Any?>) = if (code != null && raw.isCodeOverlay) codeOverlayShown(slot, code, raw) else raw
    fun draftOf(raw: Map<String, Any?>) = ed.draftOf(bundle.name, slot, raw, bundle.stamps[slot]?.getOrNull(at), code)
    div {
        this.key = "${entry.label}#$at".unsafeCast<Key>()
        className = ClassName("bundle-editor-entry")
        Disclosure {
            summaryText = listOfNotNull(
                entry.label, entryChangeText(entry.change),
                // The columns are the client's own set, which replaces the code's as a whole (`columnsSetView`).
                replaced?.let { replacesText(it, bundle.name) } ?: "added here".takeIf { merged && !editedAsSet(slot) },
                draftMark(draft, check),
            ).joinToString(" — ")
            val before = entry.published
            val publishedHeading = "Published (version ${bundle.published?.version})"
            // The first column's heading carries the page's portable switch, where JSON is copied from and pasted into.
            if (draft != null && !draft.removed) {
                // Edited: the text, and the published JSON beside it when there is one.
                div {
                    if (before != null) className = ClassName("bundle-editor-compare")
                    div {
                        className = ClassName("bundle-editor-json")
                        columnHeading("Editing", slot, ed, withSwitch = true, copyText = draft.text)
                        entryEditor(id, draft, check, ed)
                    }
                    if (before != null) jsonColumn(publishedHeading, slot, shown(before), ed)
                }
            } else if (data != null && before != null && entry.change == EntryChange.changed) {
                // Side by side: what the draft holds, and what is published.
                div {
                    className = ClassName("bundle-editor-compare")
                    jsonColumn("Draft (version ${bundle.latest.version})", slot, shown(data), ed, withSwitch = true)
                    jsonColumn(publishedHeading, slot, shown(before), ed)
                }
            } else if (data != null) {
                jsonColumn("Version ${bundle.latest.version}", slot, shown(data), ed, withSwitch = true)
            } else if (before != null) {
                jsonColumn(publishedHeading, slot, shown(before), ed, withSwitch = true)
            }
            // What is stored of an overlay: only its change (issue #1657), and any part of it the code has since lost.
            if (code != null && data != null) {
                val stale = codeOverlayStale(slot, code, data)
                if (stale.isNotEmpty()) {
                    p { className = ClassName("type-hint"); +staleOverlayText }
                    ul {
                        className = ClassName("bundle-editor-problems")
                        stale.forEachIndexed { i, text -> li { this.key = i.toString().unsafeCast<Key>(); +text } }
                    }
                }
                storedOverlay(data, slot, ed)
            }
            replaced?.let { replacedVersion(it, slot, ed) }
            // An entry has an id only when the latest revision holds it, so `data` is there too.
            if (ed.editable && id != null && data != null) {
                div {
                    className = ClassName("bundle-editor-actions")
                    when {
                        draft?.removed == true -> {
                            span {
                                className = ClassName("type-hint")
                                +(if (replaced != null) "Reverts to the code's when saved. " else "To be removed when saved. ")
                            }
                            Button { size = "small"; onClick = { ed.change(ed.edits.without(id)) }; +"Keep it" }
                        }
                        draft != null -> Button { size = "small"; onClick = { ed.change(ed.edits.without(id)) }; +"Undo edits" }
                        else -> {
                            Button { size = "small"; onClick = { ed.change(ed.edits.with(id, draftOf(data))) }; +"Edit" }
                            // An overlay stops following the code by choice (issue #1657): stored whole, from now on.
                            if (code != null) {
                                Button {
                                    size = "small"
                                    onClick = {
                                        val base = draftOf(data)
                                        ed.change(ed.edits.with(id, base.withText(entryText(ed.shownData(detached(shown(data)), slot)))))
                                    }
                                    asDynamic()["title"] = detachTitle
                                    +"Detach from code"
                                }
                            }
                        }
                    }
                    // The client's definition is never removed: a client has one.
                    if (slot != CCT.clientDef && draft?.removed != true) {
                        Button {
                            size = "small"
                            danger = true
                            onClick = {
                                val base = draft ?: draftOf(data)
                                ed.change(ed.edits.with(id, base.withRemoved(true)))
                            }
                            // An entry over the code's goes back to it (issue #1646): the code's is never removed.
                            +(if (replaced != null) "Revert to code" else "Remove")
                        }
                    }
                }
            }
        }
    }
}

/** The page's new entries of [slot] in [bundle], each open to its text. */
private fun ChildrenBuilder.newEntries(bundle: String, slot: String, ed: EditContext) {
    for ((id, draft) in ed.edits.drafts) {
        if (draft.bundle != bundle || draft.slot != slot || draft.original != null) continue
        div {
            key = id.unsafeCast<Key>()
            className = ClassName("bundle-editor-entry bundle-editor-new")
            div {
                className = ClassName("bundle-editor-json")
                // A change of a code entry (issues #1646, #1657) says so; any other is the client's own, new.
                val mark = ed.checks[id]?.data?.get(CCT.overrides)
                val changesCode = mark == CCT.overridesCodeValue || mark == CCT.overridesCodeOverlayValue
                val what = if (changesCode) "Changing the code's entry" else "New entry"
                val heading = "$what${draftMark(draft, ed.checks[id])?.let { " — $it" } ?: ""}"
                columnHeading(heading, slot, ed, withSwitch = true, copyText = draft.text)
                entryEditor(id, draft, ed.checks[id], ed)
            }
            Button { size = "small"; onClick = { ed.change(ed.edits.without(id)) }; +"Discard" }
        }
    }
}

/** What an entry's line adds for its edit: unsaved, to be removed, or failing its checks. */
private fun draftMark(draft: EntryDraft?, check: EntryCheck?): String? = when {
    draft == null || !draft.isEdit -> null
    draft.removed -> "to be removed"
    check?.ok == false -> "edited, fails its checks"
    else -> "edited, not saved"
}

/** An entry's text, edited, with what is wrong with it beneath. */
private fun ChildrenBuilder.entryEditor(id: String, draft: EntryDraft, check: EntryCheck?, ed: EditContext) {
    TextArea {
        value = draft.text
        asDynamic()["spellCheck"] = false
        asDynamic()["className"] = "bundle-editor-text"
        asDynamic()["rows"] = fittedRows(draft.text, 4, 30)
        onChange = { e -> ed.change(ed.edits.with(id, draft.withText(e.target.value as String))) }
    }
    // JSON pasted from another client names its namespace: offered as this client's, written client.@self (issue #1461).
    val parsed = draft.text.jsonMapResult().valueOrNull()
    val others = if (parsed == null || draft.slot in PORT.keptSlots) emptySet() else foreignClients(parsed, ed.view.client)
    if (parsed != null && others.isNotEmpty()) {
        div {
            className = ClassName("bundle-editor-fixup")
            span { +foreignClientsText(others, ed.view.client) }
            Button {
                size = "small"
                onClick = { ed.change(ed.edits.with(id, draft.withText(entryText(withClientsAsSelf(parsed, others))))) }
                +"Take it as ${ed.view.client}'s"
            }
        }
    }
    if (parsed != null) overlayForm(draft, parsed, ed) { ed.change(ed.edits.with(id, draft.withText(it))) }
    val problems = check?.problems.orEmpty()
    if (problems.isNotEmpty()) {
        ul {
            className = ClassName("bundle-editor-problems")
            problems.forEachIndexed { i, text -> li { key = i.toString().unsafeCast<Key>(); +text } }
        }
    }
    val notes = check?.notes.orEmpty()
    if (notes.isNotEmpty()) {
        ul {
            className = ClassName("type-hint")
            notes.forEachIndexed { i, text -> li { key = i.toString().unsafeCast<Key>(); +text } }
        }
    }
}

/** What a stored overlay with parts the code no longer has a place for says of them (issue #1657). */
private const val staleOverlayText =
    "Parts of this change no longer fit the code, which has changed. They are left out of what runs, and a save of the " +
        "entry drops them:"

/** What **Detach from code** says it does (issue #1657). */
private const val detachTitle =
    "Store the whole entry rather than only its change, so it stops following the code: a later change to the code no longer reaches it."

/**
 * How an entry being edited over the code is kept (issue #1657), for a workflow or a type: as a change to the code's,
 * with **Detach from code** to store it whole instead; or whole, with **Follow the code** to keep only its change again.
 * [onText] takes the draft's text rewritten with the other mark.
 */
private fun ChildrenBuilder.overlayForm(draft: EntryDraft, parsed: Map<String, Any?>, ed: EditContext, onText: (String) -> Unit) {
    if (draft.slot !in codeOverlaySlots) return
    val whole = parsed[CCT.overrides] == CCT.overridesCodeValue
    val code = draft.code ?: codeEntryOf(ed.view, draft.slot, ed.view.slotKeys[draft.slot].orEmpty(), parsed)
    if (!parsed.isCodeOverlay && !(whole && code != null)) return
    div {
        className = ClassName("bundle-editor-fixup")
        if (parsed.isCodeOverlay) {
            span {
                +("Kept as a change to the code: only what differs from the code's entry is stored, so a later change to the " +
                    "code reaches the rest.")
            }
            Button {
                size = "small"
                onClick = { onText(entryText(detached(parsed))) }
                asDynamic()["title"] = detachTitle
                +"Detach from code"
            }
        } else {
            span { +"Stored whole, so a later change to the code does not reach it." }
            Button {
                size = "small"
                onClick = { onText(entryText(overlayOf(parsed))) }
                asDynamic()["title"] = "Store only what differs from the code's entry, so a later change to the code reaches the rest."
                +"Follow the code"
            }
        }
    }
}

/** What is stored of an overlay of the code (issue #1657), closed to start: only its change. */
private fun ChildrenBuilder.storedOverlay(data: Map<String, Any?>, slot: String, ed: EditContext) {
    div {
        className = ClassName("bundle-editor-replaced")
        Disclosure {
            summaryText = "What is stored: only the change, merged over the code's entry on every read"
            jsonColumn("Stored", slot, data, ed)
        }
    }
}

/** The way to give [bundle] a slot it does not hold: chosen, then started with one entry. */
private fun ChildrenBuilder.addSlotControl(bundle: String, ed: EditContext) {
    val choices = addableSlots(bundle, ed.view, ed.edits)
    if (choices.isEmpty()) return
    div {
        className = ClassName("bundle-editor-add")
        Select {
            placeholder = "Add a slot…"
            value = null
            style = js("({ minWidth: 240 })")
            options = choiceOptions(choices.map { "${editorSlotLabel(it)} ($it)" to it })
            onChange = { v ->
                val slot = v as String
                val (next, _) = ed.edits.adding(bundle, slot, newEntryText(ed.view.slotKeys[slot].orEmpty()), ed.view.client, ed.portable)
                ed.change(next)
            }
        }
    }
}

/** The bar that saves the page's edits, and what the last save said. */
private class SaveBarState(
    val count: Int,
    val failing: Int,
    val saving: Boolean,
    val showCall: Boolean,
    val savedNote: String?,
    val saveError: DisplayError?,
    val stale: List<StaleEntryView>?,
    val impact: ImpactReportView?,
    val asDraft: Boolean,
    val formsOpenable: Boolean,
    /** What the dry run said the edits would newly replace of the client's template (issue #1616); null for nothing. */
    val replacing: List<CodeReplacementView>? = null,
)

private fun ChildrenBuilder.saveBar(
    state: SaveBarState,
    onSave: () -> Unit,
    onConfirm: () -> Unit,
    onCancelImpact: () -> Unit,
    onToggleCall: () -> Unit,
    onDiscard: () -> Unit,
    onReadAgain: () -> Unit,
) = div {
    className = ClassName("bundle-editor-save")
    div {
        className = ClassName("bundle-editor-savebar")
        span { +editCountText(state.count, state.failing) }
        Button {
            type = "primary"
            disabled = state.count == 0 || state.failing > 0 || state.impact != null || state.replacing != null
            this.loading = state.saving
            onClick = { onSave() }
            +"Save"
        }
        Button {
            disabled = state.count == 0
            onClick = { onToggleCall() }
            +(if (state.showCall) "Hide the call" else "Show the call")
        }
        Button {
            disabled = state.count == 0 || state.saving
            onClick = { onDiscard() }
            +"Discard all"
        }
    }
    state.savedNote?.let { p { className = ClassName("type-hint"); +it } }
    state.saveError?.let { errorText("The save was refused, and nothing was changed.", it) }
    state.stale?.let { named ->
        div {
            className = ClassName("bundle-editor-notice")
            p { +"The save was refused, and nothing was changed: somebody changed these entries since this page read them." }
            ul { named.forEachIndexed { i, s -> li { key = i.toString().unsafeCast<Key>(); +s.text } } }
            p {
                className = ClassName("type-hint")
                +"Reading them again drops your edits of them, so you can see what they hold now and make the edits again. Your other edits are kept."
            }
            Button { onClick = { onReadAgain() }; +"Read them again" }
        }
    }
    state.replacing?.let { replacing ->
        div {
            className = ClassName("bundle-editor-notice")
            p { +"Saving these edits changes these of the code's, for this client:" }
            ul { replacingText(replacing).forEachIndexed { i, line -> li { key = i.toString().unsafeCast<Key>(); +line } } }
            p { className = ClassName("type-hint"); +replacingNoteText(replacing) }
            // With an effect on the forms too, the impact report below asks once for both.
            if (state.impact == null) {
                Button { type = "primary"; danger = true; this.loading = state.saving; onClick = { onConfirm() }; +"Save anyway" }
                Button { onClick = { onCancelImpact() }; +"Cancel" }
            }
        }
    }
    state.impact?.let { report ->
        div {
            className = ClassName("bundle-editor-notice")
            impactReportBody(report, state.formsOpenable, doing = "Saving these edits")
            if (state.asDraft) {
                p { className = ClassName("type-hint"); +"Saved here, the edits are a draft: the client's forms are affected when it is published." }
            }
            Button { type = "primary"; danger = true; this.loading = state.saving; onClick = { onConfirm() }; +"Save anyway" }
            Button { onClick = { onCancelImpact() }; +"Cancel" }
        }
    }
}

/** An entry of [slot], highlighted in the page's form, under its [heading] -- the first column's with the switch. */
private fun ChildrenBuilder.jsonColumn(heading: String, slot: String, data: Map<String, Any?>, ed: EditContext, withSwitch: Boolean = false) {
    div {
        className = ClassName("bundle-editor-json")
        val json = ed.shownData(data, slot).toJsonStr()
        columnHeading(heading, slot, ed, withSwitch, copyText = json)
        JsonBlock { text = json }
    }
}

/**
 * A column's [heading], saying when it is portable, and -- [withSwitch], on an entry's first column -- the page's switch
 * between portable form and as stored. Not on a slot left as stored either way ([PORT.keptSlots]), where it would
 * change nothing, and disabled while an edited text does not parse, since it could not be rewritten. And **Copy**, for
 * the column's JSON as shown ([copyText]) -- portable when the switch is, the text as typed in an editor -- whether or
 * not the page edits.
 */
private fun ChildrenBuilder.columnHeading(heading: String, slot: String, ed: EditContext, withSwitch: Boolean, copyText: String) {
    val applies = slot !in PORT.keptSlots
    div {
        className = ClassName("bundle-editor-heading")
        span { className = ClassName("type-hint"); +(if (ed.portable && applies) "$heading · portable" else heading) }
        if (withSwitch && applies) {
            Checkbox {
                checked = ed.portable
                disabled = !ed.switchable
                onChange = { e -> ed.onPortable(e.target.checked == true) }
                asDynamic()["title"] = if (ed.switchable) {
                    "Show the JSON with the client's own namespace written client.@self, to copy into another client. " +
                        "Either form can be typed: a save resolves it for this client."
                } else {
                    "Fix the JSON being edited first: it is rewritten in the other form."
                }
                +"Portable"
            }
        }
        CopyButton {
            text = copyText
            label = if (ed.portable && applies) "Copy this JSON, in portable form" else "Copy this JSON"
        }
    }
}
