package com.dynamicruntime.webapp

import com.dynamicruntime.common.endpoint.EP
import com.dynamicruntime.common.gedra.CCT
import com.dynamicruntime.common.gedra.CFEP
import com.dynamicruntime.common.gedra.PORT
import com.dynamicruntime.common.home.PFRAG
import com.dynamicruntime.common.util.jsonMapResult
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
                val own = shell.user?.client.orEmpty()
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
                    if (report.blocks) {
                        impact = report
                        return@launch
                    }
                }
                Http.sendApi("POST", real.path, real.body)
                edits = BundleEdits()
                impact = null
                showCall = false
                savedNote = "Saved ${if (count == 1) "1 edit" else "$count edits"}${if (row?.sandboxOf != null) " as a draft" else ""}, and reloaded the client."
                bump()
            } catch (e: Throwable) {
                impact = null
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
            +"Every configuration bundle this client stores. Open a bundle to see its slots, a slot to see its "
            +"entries, and an entry to see its JSON. A draft's entries are shown beside the published revision's."
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
                        impactFormsOpenable(acrossClients, row?.sandboxOf != null),
                    ),
                    onSave = { save(confirmed = false) },
                    onConfirm = { save(confirmed = true) },
                    onCancelImpact = { impact = null },
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
            val bundles = editorBundlesInOrder(shown.configs) + edits.newBundles.map { EditorBundle(it, EditorRevision(0, false, emptyMap(), 0), null) }
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
                        for (slot in slotsWithDrafts(bundle, ed)) {
                            div {
                                key = slot.slot.unsafeCast<Key>()
                                className = ClassName("bundle-editor-slot")
                                Disclosure {
                                    summaryText = slotSummaryText(slot)
                                    initiallyOpen = slot.entries.isEmpty()
                                    slot.entries.forEachIndexed { i, entry -> entryView(bundle, slot.slot, entry, i, ed) }
                                    newEntries(bundle.name, slot.slot, ed)
                                    if (ed.editable && shown.slotKeys[slot.slot].orEmpty().isNotEmpty()) {
                                        Button {
                                            size = "small"
                                            onClick = {
                                                val (next, _) = ed.edits.adding(
                                                    bundle.name, slot.slot, newEntryText(shown.slotKeys[slot.slot].orEmpty()), shown.client, portable,
                                                )
                                                ed.change(next)
                                            }
                                            +"Add entry"
                                        }
                                    }
                                }
                            }
                        }
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
        }
    }
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

    /** A new draft of [data], an entry of [slot] in [bundle] as read with [stamp], its text in the page's form. */
    fun draftOf(bundle: String, slot: String, data: Map<String, Any?>, stamp: Any?) =
        EntryDraft(bundle, slot, data, stamp, entryText(shownData(data, slot)), client = view.client, portable = portable)
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
private fun ChildrenBuilder.entryView(bundle: EditorBundle, slot: String, entry: EditorEntry, at: Int, ed: EditContext) {
    val data = entry.latest
    val key = ed.view.slotKeys[slot].orEmpty()
    // The entry's place among the latest revision's: the published ones the draft removed come after them.
    val id = data?.let { existingEntryId(bundle.name, slot, key, it, at) }
    val draft = id?.let { ed.edits.drafts[it] }
    val check = id?.let { ed.checks[it] }
    div {
        this.key = "${entry.label}#$at".unsafeCast<Key>()
        className = ClassName("bundle-editor-entry")
        Disclosure {
            summaryText = listOfNotNull(entry.label, entryChangeText(entry.change), draftMark(draft, check)).joinToString(" — ")
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
                    if (before != null) jsonColumn(publishedHeading, slot, before, ed)
                }
            } else if (data != null && before != null && entry.change == EntryChange.changed) {
                // Side by side: what the draft holds, and what is published.
                div {
                    className = ClassName("bundle-editor-compare")
                    jsonColumn("Draft (version ${bundle.latest.version})", slot, data, ed, withSwitch = true)
                    jsonColumn(publishedHeading, slot, before, ed)
                }
            } else if (data != null) {
                jsonColumn("Version ${bundle.latest.version}", slot, data, ed, withSwitch = true)
            } else if (before != null) {
                jsonColumn(publishedHeading, slot, before, ed, withSwitch = true)
            }
            if (ed.editable && id != null && data != null) {
                div {
                    className = ClassName("bundle-editor-actions")
                    when {
                        draft?.removed == true -> {
                            span { className = ClassName("type-hint"); +"To be removed when saved. " }
                            Button { size = "small"; onClick = { ed.change(ed.edits.without(id)) }; +"Keep it" }
                        }
                        draft != null -> Button { size = "small"; onClick = { ed.change(ed.edits.without(id)) }; +"Undo edits" }
                        else -> Button {
                            size = "small"
                            onClick = { ed.change(ed.edits.with(id, ed.draftOf(bundle.name, slot, data, bundle.stamps[slot]?.getOrNull(at)))) }
                            +"Edit"
                        }
                    }
                    // The client's definition is never removed: a client has one.
                    if (slot != CCT.clientDef && draft?.removed != true) {
                        Button {
                            size = "small"
                            danger = true
                            onClick = {
                                val base = draft ?: ed.draftOf(bundle.name, slot, data, bundle.stamps[slot]?.getOrNull(at))
                                ed.change(ed.edits.with(id, base.withRemoved(true)))
                            }
                            +"Remove"
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
                val heading = "New entry${draftMark(draft, ed.checks[id])?.let { " — $it" } ?: ""}"
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
        asDynamic()["autoSize"] = js("({ minRows: 4, maxRows: 30 })")
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
    val problems = check?.problems.orEmpty()
    if (problems.isNotEmpty()) {
        ul {
            className = ClassName("bundle-editor-problems")
            problems.forEachIndexed { i, text -> li { key = i.toString().unsafeCast<Key>(); +text } }
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
            disabled = state.count == 0 || state.failing > 0 || state.impact != null
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
