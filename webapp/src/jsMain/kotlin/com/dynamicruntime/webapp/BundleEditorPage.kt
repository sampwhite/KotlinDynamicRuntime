package com.dynamicruntime.webapp

import com.dynamicruntime.common.home.PFRAG
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
import react.dom.html.ReactHTML.p
import react.dom.html.ReactHTML.span
import react.useEffect
import react.useRef
import react.useState
import web.cssom.ClassName

/** The bundle editor page's route (issue #1436), reached from a client's page: `page=bundleEditor&client=<id>`. */
const val pageBundleEditor = "bundleEditor"

/** Where a client's bundle editor is. */
fun bundleEditorHref(clientId: String): String = routeHref(listOf(HP.page to pageBundleEditor, HP.client to clientId))

private val bundleEditorScope = MainScope()

/**
 * The bundle editor, phase 1 (issue #1436): every configuration a client stores, on one page, read-only. Each
 * configuration opens to its slots, each slot to its entries, and each entry to its JSON -- the draft's beside the
 * published revision's, when the configuration is a draft over one. Above them, the client page's own table of the
 * configurations, which publishes them with the impact report.
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

    val shown = editor
    val clientId = row?.clientId ?: shown?.client ?: routeClient
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
            +"Every configuration bundle this client stores, read-only. Open a bundle to see its slots, a slot to see its "
            +"entries, and an entry to see its JSON. A draft's entries are shown beside the published revision's."
        }
        row?.sandboxOf?.let { parent ->
            p {
                className = ClassName("subtitle")
                +"A sandbox holds no configuration of its own: it runs the latest of $parent's, drafts included, which is what this page shows."
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
                    acrossClients = config?.canSeeAllClients == true
                    this.clientId = clientId
                    onChanged = { bump() }
                }
            }
            WhatIsThis {
                heading = "Bundles"
                text = pageCopy.explanation(PFRAG.bundleListNs)
            }
            if (shown.configs.isEmpty()) p { className = ClassName("subtitle"); +"This client stores no configuration." }
            for (bundle in editorBundlesInOrder(shown.configs)) {
                div {
                    key = bundle.name.unsafeCast<Key>()
                    Disclosure {
                        summaryText = bundleSummaryText(bundle)
                        for (slot in editorSlots(bundle, shown.slotKeys)) {
                            div {
                                key = slot.slot.unsafeCast<Key>()
                                className = ClassName("bundle-editor-slot")
                                Disclosure {
                                    summaryText = slotSummaryText(slot)
                                    slot.entries.forEachIndexed { i, entry -> entryView(bundle, entry, i) }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

/** One entry: its name and how it stands against the published revision, opening to its JSON. */
private fun ChildrenBuilder.entryView(bundle: EditorBundle, entry: EditorEntry, at: Int) {
    div {
        key = "${entry.label}#$at".unsafeCast<Key>()
        className = ClassName("bundle-editor-entry")
        Disclosure {
            summaryText = listOfNotNull(entry.label, entryChangeText(entry.change)).joinToString(" — ")
            val draft = entry.latest
            val before = entry.published
            if (draft != null && before != null && entry.change == EntryChange.changed) {
                // Side by side: what the draft holds, and what is published.
                div {
                    className = ClassName("bundle-editor-compare")
                    jsonColumn("Draft (version ${bundle.latest.version})", draft)
                    jsonColumn("Published (version ${bundle.published?.version})", before)
                }
            } else {
                val data = draft ?: before
                if (data != null) jsonColumn(if (draft == null) "Published (version ${bundle.published?.version})" else null, data)
            }
        }
    }
}

/** An entry's JSON, as stored and highlighted, under its [heading] when there is one. */
private fun ChildrenBuilder.jsonColumn(heading: String?, data: Map<String, Any?>) {
    div {
        className = ClassName("bundle-editor-json")
        heading?.let { span { className = ClassName("type-hint"); +it } }
        JsonBlock { text = data.toJsonStr() }
    }
}
