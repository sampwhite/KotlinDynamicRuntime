package com.dynamicruntime.webapp

import com.dynamicruntime.common.cfact.CFACT
import com.dynamicruntime.common.exception.EXC
import com.dynamicruntime.common.gedra.CLD
import com.dynamicruntime.common.gedra.ClientPresentationFields
import com.dynamicruntime.common.gedra.ClientStatus
import com.dynamicruntime.common.gedra.MNU
import com.dynamicruntime.common.gedra.clientLabel
import com.dynamicruntime.common.home.HMENU
import com.dynamicruntime.common.util.humanizeFieldName
import com.dynamicruntime.common.util.toOptStr
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import react.ChildrenBuilder
import react.FC
import react.Fragment
import react.Key
import react.Props
import react.create
import react.dom.html.ReactHTML.a
import react.dom.html.ReactHTML.code
import react.dom.html.ReactHTML.div
import react.dom.html.ReactHTML.h1
import react.dom.html.ReactHTML.h2
import react.dom.html.ReactHTML.h3
import react.dom.html.ReactHTML.li
import react.dom.html.ReactHTML.p
import react.dom.html.ReactHTML.span
import react.dom.html.ReactHTML.table
import react.dom.html.ReactHTML.tbody
import react.dom.html.ReactHTML.textarea
import react.dom.html.ReactHTML.td
import react.dom.html.ReactHTML.th
import react.dom.html.ReactHTML.thead
import react.dom.html.ReactHTML.tr
import react.dom.html.ReactHTML.ul
import react.useEffect
import react.useRef
import react.useState
import kotlinx.browser.document
import web.dom.ElementId
import web.cssom.ClassName
import web.window.WindowTarget
import web.window._blank

private val clientsScope = MainScope()

/** The Stored configuration heading's element id, which a note above links down to. */
private const val storedConfigurationId = "stored-configuration"

/**
 * The Clients page (issue #905): the clients an administrator oversees, each with where it stands on this node,
 * where its definition comes from, and what it holds. An `allClients` administrator sees every client this node
 * knows of -- present or not, with why not; a client-scoped one sees their own. One table for both: the second
 * simply has one row.
 *
 * Denied honestly, in two layers as Users is: the shell's `canManageUsers` says whether to ask at all, and the
 * endpoint's own refusal -- a `public` self-administrator, who administers only their own users -- is shown as the
 * denial it is, in the endpoint's words, not as a failed load. A failed *refresh* keeps the listing already on
 * screen and says so above it: the page re-reads on every refresh generation, and a blip must not take away what
 * was being read.
 *
 * With `c=<id>` (issue #906) it shows **one client** instead -- as Docs shows one document -- with `← Clients` back:
 * the overview's facts for it, its definition as the scoped retrieve answers, the issues its checks forgave, and
 * the stored configurations this node holds for it. The definition is asked for on its own, keyed on the open id,
 * so a deep link works before the listing has loaded and a client this node does not carry still shows what the
 * listing knows above the retrieve's honest 404. Its definition is edited in place (`DefinitionEditor`, issue
 * #1026) for a client defined in stored configuration.
 */
@Suppress("UnnecessaryVariable")
val ClientsPage = FC<Props> {
    var config by useState<HomeConfig?>(null)
    var rows by useState<List<ClientOverview>?>(null)
    var loadError by useState<DisplayError?>(null)
    // The endpoint's refusal (a 403), in its words: a designed answer, drawn as the permission panel.
    var refusal by useState<String?>(null)
    val generation = useRefreshGeneration()
    // A copy edit that went live bumps the generation (issue #918): this page re-reads the client, and the shell
    // re-reads its config and copy, so a changed wordmark shows in the app bar without a reload.
    val bump = useRefreshBump()
    // The open client's detail (issue #906): read on its own, keyed on the id the hash names.
    val openId = hashParams()[HP.client]
    var definition by useState<ClientDefinitionView?>(null)
    var storedConfigs by useState<List<ConfigSummaryView>?>(null)
    // The definition's failure to load, or -- a designed answer, a 403 or 404 -- what the endpoint said instead.
    var detailError by useState<DisplayError?>(null)
    var detailNote by useState<String?>(null)
    var storedError by useState<DisplayError?>(null)
    // What the open client's own configuration changes (issue #917), read beside the definition.
    var overrides by useState<ClientOverridesView?>(null)
    var overridesError by useState<DisplayError?>(null)
    // The endpoint's designed answer instead -- a refusal -- said once, above, by the definition's note.
    var overridesRefused by useState(false)
    // The overrides of every listed client (issue #917), for the cross-client view: read once the listing has
    // answered, one retrieve per client, keyed on the rows so a refresh re-reads them.
    val acrossView = hashParams().containsKey(HP.overrides)
    var acrossRows by useState<List<Pair<String, ClientOverridesView>>?>(null)
    var acrossError by useState<DisplayError?>(null)
    // Monotonic token, so a slow answer for a client the user has moved on from is dropped rather than shown; and
    // the id last asked about, so a re-read of the same client keeps what is shown until its replacement arrives.
    val latestDetail = useRef(0)
    val lastOpenId = useRef<String>(null)

    useEffect(generation) {
        clientsScope.launch {
            try {
                val loaded = HomeApi.fetchConfig()
                config = loaded
                // Not asked of a caller the shell says may not administer: the panel below says so instead.
                if (loaded.canManageUsers) rows = ClientsApi.listOverview()
                loadError = null
                refusal = null
            } catch (e: Throwable) {
                if ((e as? ApiError)?.status == EXC.notAuthorized) refusal = e.message else loadError = userFacingError(e)
            }
        }
    }

    // The open client's definition and stored configurations, once the shell has said who is asking (the stored
    // configurations' path depends on it), and again on each refresh generation. Keyed on the shell's two facts
    // rather than the config object, which every generation replaces. Cleared only when the *client* changes, so a
    // switch never shows the previous client's under the new heading while a re-read keeps what is shown until its
    // replacement arrives. Each is fetched on its own: a client this node does not carry has no definition to
    // retrieve, which must not hide the configurations it does hold.
    val across = config?.canSeeAllClients
    val own = config?.user?.client
    useEffect(openId, across, own, generation) {
        if (lastOpenId.current != openId) {
            lastOpenId.current = openId
            definition = null
            storedConfigs = null
            detailError = null
            detailNote = null
            storedError = null
            overrides = null
            overridesError = null
            overridesRefused = false
        }
        val id = openId
        if (id == null || across == null || own == null) return@useEffect
        val token = (latestDetail.current ?: 0) + 1
        latestDetail.current = token
        clientsScope.launch {
            try {
                val loaded = ClientsApi.definition(id)
                if (latestDetail.current == token) {
                    definition = loaded
                    detailError = null
                    detailNote = null
                }
            } catch (e: Throwable) {
                if (latestDetail.current != token) return@launch
                // A refusal or an absence is the endpoint's designed answer, said in its words; anything else failed.
                val status = (e as? ApiError)?.status
                if (status == EXC.notAuthorized || status == EXC.notFound) detailNote = e.message else detailError = userFacingError(e)
            }
        }
        clientsScope.launch {
            try {
                val loaded = ClientsApi.storedConfigs(id, across, own)
                if (latestDetail.current == token) {
                    storedConfigs = loaded
                    storedError = null
                }
            } catch (e: Throwable) {
                // Never swallowed into "none": a listing that failed is not a client with no configuration.
                if (latestDetail.current == token) storedError = userFacingError(e)
            }
        }
        clientsScope.launch {
            try {
                val loaded = ClientsApi.overrides(id)
                if (latestDetail.current == token) {
                    overrides = loaded
                    overridesError = null
                    overridesRefused = false
                }
            } catch (e: Throwable) {
                if (latestDetail.current != token) return@launch
                // A refusal is the same answer the definition's retrieve gave, already said under the summary:
                // the section is then left out rather than repeating it as a failure.
                val status = (e as? ApiError)?.status
                if (status == EXC.notAuthorized || status == EXC.notFound) overridesRefused = true else overridesError = userFacingError(e)
            }
        }
    }

    // The cross-client view's data (issue #917): each listed client's overrides, in the listing's order. Only for
    // an administrator who sees across clients -- a scoped one has one client, and the view is not offered.
    useEffect(acrossView, rows, across) {
        if (!acrossView || rows == null || across != true) return@useEffect
        val listed = rows!!
        val token = (latestDetail.current ?: 0) + 1
        latestDetail.current = token
        clientsScope.launch {
            try {
                // Only the clients the listing says change something, and all at once: the rest have nothing to add.
                val loaded = coroutineScope {
                    listed.filter { it.copyOverrides + it.blockOverrides > 0 }
                        .map { c -> async { c.clientId to ClientsApi.overrides(c.clientId) } }.awaitAll()
                }
                if (latestDetail.current == token) {
                    acrossRows = loaded
                    acrossError = null
                }
            } catch (e: Throwable) {
                if (latestDetail.current == token) acrossError = userFacingError(e)
            }
        }
    }

    val current = config
    when {
        refusal != null -> deniedCard(refusal!!)
        // Nothing loaded yet: the load state, or the failure that kept it from loading.
        current == null -> LoadStateCard {
            title = "Clients"
            this.loadError = loadError
            errorLead = "Couldn't load the clients."
        }
        !current.canManageUsers -> deniedCard("You do not have permission to see clients.")
        openId != null -> clientDetail(
            openId, rows?.firstOrNull { it.clientId == openId }, definition, storedConfigs,
            detailError, detailNote, storedError, overrides, overridesError, overridesRefused, current.canSeeAllClients,
            onChanged = bump,
        )
        // The view across clients is the allClients administrator's; anyone else lands on their listing.
        acrossView && current.canSeeAllClients -> overridesAcross(rows, acrossRows, loadError, acrossError)
        else -> clientsListing(rows, current.canSeeAllClients, loadError)
    }
}

/**
 * One client (issue #906): the way back, the heading, the summary rows, the issues, and the stored configurations.
 * [row] is the listing's overview of it when the listing holds it; [def] the retrieved definition -- or, under the
 * summary (which still says what the listing knows), [detailNote], the endpoint's own words for a refusal or an
 * absence, or [detailError], a load that failed. [storedError] likewise stands in for the configurations table.
 */
private fun ChildrenBuilder.clientDetail(
    clientId: String,
    row: ClientOverview?,
    def: ClientDefinitionView?,
    configs: List<ConfigSummaryView>?,
    detailError: DisplayError?,
    detailNote: String?,
    storedError: DisplayError?,
    overrides: ClientOverridesView?,
    overridesError: DisplayError?,
    overridesRefused: Boolean,
    acrossClients: Boolean,
    /** What a copy edit calls once it is live (issue #918): the page re-reads, and so does the shell. */
    onChanged: () -> Unit,
) {
    div {
        className = ClassName("card wide")
        backToListing(HMENU.pageClients)
        h1 { +clientLabel(clientId, row?.name ?: def?.info?.get(CLD.name).toOptStr().orEmpty()) }
        for ((label, value) in clientSummaryRows(clientId, row, def, acrossClients)) readOnlyField(label, value)
        // Editing the definition (issue #1026): the presentation fields of a client defined in stored configuration.
        def?.stored?.let { stored ->
            if (definitionEditable(row)) {
                val offer = definitionEditOffer(def.storedConfig, configs, sandbox = def.info[CLD.sandbox] == true)
                if (offer.editor) {
                    DefinitionEditor {
                        this.clientId = clientId
                        baseline = stored
                        this.onChanged = onChanged
                    }
                }
                offer.note?.let { note ->
                    // Said before the attempt, with the way there: the editor would only be refused for the same reason.
                    p {
                        className = ClassName("subtitle")
                        +"$note, in "
                        a {
                            className = ClassName("wf-cell-link")
                            href = "#"
                            onClick = { e ->
                                e.preventDefault()
                                // Centred, not at the top: the app bar is fixed and would cover a heading scrolled under it.
                                document.getElementById(storedConfigurationId)?.asDynamic()?.scrollIntoView(js("({ block: 'center', behavior: 'smooth' })"))
                            }
                            +"Stored configuration"
                        }
                        +" below."
                    }
                }
            }
        }
        // The client's named reports (issue #1007) -- for a client this node carries, which is what has any to run.
        // Named by client only for an administrator who looks across them; a scoped one's reports are their own.
        if (row?.status == ClientStatus.present.name) {
            p {
                a {
                    className = ClassName("wf-cell-link")
                    href = reportsHref(clientId.takeIf { acrossClients })
                    +"Reports"
                }
            }
        }
        detailError?.let { errorText("Couldn't load this client's definition.", it) }
        detailNote?.let {
            p {
                className = ClassName("subtitle")
                +it
            }
        }
        if (def == null && detailError == null && detailNote == null) {
            p {
                className = ClassName("subtitle")
                +"Loading…"
            }
        }
        // The issues, from the definition when it was read and from the listing's row otherwise (the row carries
        // the messages alone), so a dropped client's reasons show either way.
        val issues = def?.issues?.map { "${it.message} ${it.degradedTo}".trim() + (if (it.origin.isEmpty()) "" else " (${it.origin})") }
            ?: row?.issues.orEmpty()
        if (issues.isNotEmpty()) {
            h2 { +"Issues" }
            ul {
                className = ClassName("wf-reasons")
                issues.forEachIndexed { i, text ->
                    li {
                        key = i.toString().unsafeCast<Key>()
                        +text
                    }
                }
            }
        }
        // Its sandbox (issue #932): whose it is, or adding and removing one.
        row?.let { overview ->
            h2 { +"Sandbox" }
            SandboxSection {
                this.row = overview
                this.onChanged = onChanged
            }
        }
        // A sandbox holds no configuration of its own; it runs its parent's latest (issue #1001), listed here as such.
        h2 {
            id = ElementId(storedConfigurationId)
            +(row?.sandboxOf?.let { "Configuration it runs ($it's)" } ?: "Stored configuration")
        }
        when {
            storedError != null -> errorText("Couldn't load this client's stored configuration.", storedError)
            configs == null -> p {
                className = ClassName("subtitle")
                +"Loading…"
            }
            configs.isEmpty() -> p {
                className = ClassName("subtitle")
                +"This node holds no stored configuration for this client."
            }
            else -> StoredConfigTable {
                this.configs = configs
                this.row = row
                this.acrossClients = acrossClients
                this.clientId = clientId
                this.onChanged = onChanged
            }
        }
        // What the client's own configuration changes about what its people see (issue #917): the copy it rewords
        // and the interface items it renames, hides, shows or adds. The editors (#918, #919) open from these rows.
        if (!overridesRefused) h2 { +"Copy & menu" }
        when {
            overridesRefused -> {}
            overridesError != null -> errorText("Couldn't load this client's copy and menu changes.", overridesError)
            overrides == null -> p {
                className = ClassName("subtitle")
                +"Loading…"
            }
            else -> {
                if (overrides.copy.isEmpty() && overrides.blocks.isEmpty()) {
                    p {
                        className = ClassName("subtitle")
                        +"This client uses the shipped copy and menu."
                    }
                }
                // The copy rows and their editor (issue #918): edit or reset a key, or add an override of any shipped one.
                CopyEditor {
                    this.clientId = clientId
                    rows = overrides.copy
                    this.onChanged = onChanged
                }
                // The home menu and its editor (issue #919): every item, renamed, hidden, shown or reset here.
                h3 { +"Menu" }
                MenuEditor {
                    this.clientId = clientId
                    // The overrides report's home-menu rows say which config -- source or stored -- set an item.
                    setBy = overrides.blocks.filter { it.blockId == HMENU.block && it.itemId != null }.associate { it.itemId!! to blockSetByText(it) }
                    this.onChanged = onChanged
                }
                // Any other block the client changes (the sample's nav, say) is shown as it was: this editor is the
                // home menu's.
                val otherBlocks = overrides.blocks.filter { it.blockId != HMENU.block }
                if (otherBlocks.isNotEmpty()) {
                    h3 { +"Other interface changes" }
                    blockOverridesTable(otherBlocks)
                }
            }
        }
    }
}

external interface DefinitionEditorProps : Props {
    var clientId: String
    /** The client's stored definition, which the draft is seeded from and diffed against. */
    var baseline: Map<String, Any?>
    var onChanged: () -> Unit
}

/**
 * The definition editor (issue #1026): **Edit definition** opens the presentation fields -- name, note, domain
 * prefix, custom domain, web resources, suggested user labels -- seeded from the client's **stored** definition
 * ([definitionDraftOf]; a draft already saved shows, and can be taken back); Save sends only what changed ([definitionEditRequest]) and is offered only when something
 * did; Cancel drops the draft. The fields the platform sets (#820) and the structural ones are not here: they stay
 * in the read-only rows above. A save takes effect as the copy and menu editors' do -- live, or a draft for a
 * client with a sandbox, which the note says ([savedNote]) -- and [DefinitionEditorProps.onChanged] re-reads the
 * page and the shell, so the heading and the listing row follow the new name. A refusal is shown in the backend's
 * words under the fields, the draft kept, so it can be corrected rather than retyped.
 */
private val DefinitionEditor = FC<DefinitionEditorProps> { props ->
    var editing by useState(false)
    var draft by useState(definitionDraftOf(props.baseline))
    var busy by useState(false)
    var editError by useState<DisplayError?>(null)
    var note by useState<String?>(null)
    // The definition a save just stored, until the page's re-read brings the same: an editor reopened in between
    // starts from it, so the old values are not sent back as changes.
    var saved by useState<Map<String, Any?>?>(null)
    val baseline = saved ?: props.baseline
    useEffect(props.baseline) { saved = null }
    // Which client a save was started for: a save still in flight when the administrator opens another client is
    // disowned, so its note and close land nowhere rather than on the new client's page.
    val latest = useRef(0)
    // A new client under the same editor: nothing of the previous one's editing state carries over.
    useEffect(props.clientId) {
        latest.current = (latest.current ?: 0) + 1
        editing = false
        busy = false
        editError = null
        note = null
    }

    fun open() {
        draft = definitionDraftOf(baseline)
        editing = true
        editError = null
        note = null
    }

    fun save() {
        val request = definitionEditRequest(props.clientId, baseline, draft)
        if (!definitionEditChanges(request)) return
        val token = latest.current
        busy = true
        editError = null
        clientsScope.launch {
            try {
                val result = ClientsApi.setDefinition(request)
                if (latest.current == token) {
                    note = savedNote("Saved the definition to ${result.configName}.", result.mode)
                    editing = false
                    saved = result.info
                }
                props.onChanged()
            } catch (e: Throwable) {
                if (latest.current == token) editError = userFacingError(e)
            } finally {
                if (latest.current == token) busy = false
            }
        }
    }

    if (!editing) {
        p {
            Button {
                size = "small"
                onClick = { open() }
                +"Edit definition"
            }
        }
    } else {
        val request = definitionEditRequest(props.clientId, baseline, draft)
        // The one list the endpoint's input is built from, so a field joining it is drawn here without a change.
        for (name in ClientPresentationFields.names) {
            textField(humanizeFieldName(name), draft[name].orEmpty(), disabled = busy) { v -> draft = draft + (name to v) }
        }
        p {
            className = ClassName("subtitle")
            +"Labels are comma-separated; each is matched exactly as written."
        }
        p {
            Button {
                type = "primary"
                loading = busy
                disabled = !definitionEditChanges(request)
                onClick = { save() }
                +"Save"
            }
            Button {
                type = "link"
                disabled = busy
                onClick = { editing = false; editError = null }
                +"Cancel"
            }
        }
        editError?.let { errorText("Couldn't save the definition.", it) }
    }
    note?.let {
        p {
            className = ClassName("subtitle")
            +it
        }
    }
}

/** The address of the key an editor is open on, and the text it started from. */
/** [audience] is who the file is for -- `FragmentAudience` -- which decides the value's template syntax (issue #1001). */
private class CopyEditTarget(val fileId: String, val namespace: String, val key: String, val startValue: String, val audience: String)

external interface StoredConfigTableProps : Props {
    var configs: List<ConfigSummaryView>
    var row: ClientOverview?
    var acrossClients: Boolean
    var clientId: String
    var onChanged: () -> Unit
}

/**
 * The impact dialog's subject (issue #935): a [report] on publishing [name], from a [refused] publish or a check; with
 * [canPublish] where this page publishes the configuration (not a client's page whose sandbox does).
 */
private class ImpactDialog(val name: String, val report: ImpactReportView, val refused: Boolean, val canPublish: Boolean)

/**
 * A client's configuration bundles (issues #906, #1001): one row per bundle -- its latest revision's version, when it
 * was published and last written, whether it is live ([bundleLiveText], by the client's tier), its issues -- and an
 * **actions** column. Publish is the first action ([bundleAction] decides where it is offered, and why not where it
 * is not); View and Edit are to join it. A publish reloads the client, and [StoredConfigTableProps.onChanged]
 * re-reads the page.
 *
 * Publishing is judged against the client's stored data (issue #935). A publish that would affect it is refused with
 * a report, which opens a dialog listing what would happen, with **Publish anyway**; **Check impact**, beside Publish
 * where the client runs only what is published, opens the same dialog before any publish -- most useful from the
 * sandbox, just before going live.
 */
private val StoredConfigTable = FC<StoredConfigTableProps> { props ->
    val bump = useRefreshBump()
    var busy by useState<String?>(null)
    var error by useState<DisplayError?>(null)
    var impact by useState<ImpactDialog?>(null)
    useEffect(props.clientId) { error = null; impact = null }
    val publishedOnly = props.row?.let { it.publishedOnly || it.sandboxOf != null } ?: true

    fun publish(name: String, acknowledge: Boolean) {
        busy = name
        error = null
        clientsScope.launch {
            try {
                ClientsApi.publishBundle(props.clientId, name, props.acrossClients, acknowledgeImpact = acknowledge)
                impact = null
                props.onChanged()
            } catch (e: Throwable) {
                val refusedOver = impactOf(e)
                if (refusedOver != null) impact = ImpactDialog(name, refusedOver, refused = true, canPublish = true) else error = userFacingError(e)
            } finally {
                busy = null
            }
        }
    }

    fun checkImpact(name: String, canPublish: Boolean) {
        busy = name
        error = null
        clientsScope.launch {
            try {
                impact = ImpactDialog(name, ClientsApi.bundleImpact(props.clientId, name, props.acrossClients), refused = false, canPublish = canPublish)
            } catch (e: Throwable) {
                error = userFacingError(e)
            } finally {
                busy = null
            }
        }
    }
    div {
        className = ClassName("op-table-scroll")
        table {
            className = ClassName("op-table")
            thead {
                tr {
                    th { +"Name" }
                    th { className = ClassName("op-num"); +"Version" }
                    th { +"Published" }
                    th { +"Updated" }
                    th { +"Live" }
                    th { className = ClassName("op-num"); +"Issues" }
                    th { +"" }
                }
            }
            tbody {
                props.configs.forEach { c ->
                    val action = bundleAction(props.row, c)
                    tr {
                        key = c.name.unsafeCast<Key>()
                        // A row offered Publish is marked, so the button a note above sends people to is found at a glance.
                        if (configRowNeedsPublish(props.row, c)) className = ClassName("op-row-attention")
                        td { +c.name }
                        td { className = ClassName("op-num"); +c.version.toString() }
                        td { +(if (c.published) c.publishedAt?.let { formatTimestamp(it) } ?: "Yes" else "No") }
                        td { +(c.updatedAt?.let { formatTimestamp(it) } ?: "\u2014") }
                        // A sandbox runs its parent's latest, so for it every row is what it runs (issue #1001).
                        td { +(if (props.row?.sandboxOf != null) "Runs here" else bundleLiveText(c, publishedOnly)) }
                        td { className = ClassName("op-num"); +c.issueCount.toString() }
                        td {
                            if (action.publish) {
                                span {
                                    className = ClassName("row-actions")
                                    Button {
                                        size = "small"
                                        loading = busy == c.name
                                        disabled = busy != null
                                        action.note?.let { asDynamic()["title"] = it }
                                        onClick = { publish(c.name, acknowledge = false) }
                                        +"Publish"
                                    }
                                    if (action.checkImpact) checkImpactButton(c.name, busy, canPublish = true, ::checkImpact)
                                }
                            } else {
                                // A client with a sandbox publishes there, but its impact is checked here too, where
                                // the report's forms can be opened (issue #935).
                                if (action.checkImpact) {
                                    span {
                                        className = ClassName("row-actions")
                                        checkImpactButton(c.name, busy, canPublish = false, ::checkImpact)
                                    }
                                }
                                val sandbox = action.sandboxClient
                                if (sandbox == null) {
                                    action.note?.let { note ->
                                        span {
                                            className = ClassName("type-hint")
                                            +note
                                        }
                                    }
                                } else {
                                    // "Publish from its sandbox": the word leads to the sandbox's own page, where this
                                    // row offers Publish. An administrator who may open another client's detail goes
                                    // straight there; a client-scoped one first opens the sandbox (#929) -- a fresh
                                    // session as their user in it -- and lands on that page (issue #1001).
                                    span {
                                        className = ClassName("type-hint")
                                        +"Publish from its "
                                        a {
                                            className = ClassName("wf-cell-link")
                                            href = hashHref(listOf(HP.page to HMENU.pageClients, HP.client to sandbox))
                                            if (!props.acrossClients) {
                                                title = "Open the sandbox and show this configuration there."
                                                onClick = { e ->
                                                    e.preventDefault()
                                                    clientsScope.launch {
                                                        try {
                                                            AuthApi.openSandbox()
                                                            afterSessionChange(bump, listOf(HP.page to HMENU.pageClients, HP.client to sandbox))
                                                        } catch (e: Throwable) {
                                                            error = userFacingError(e)
                                                        }
                                                    }
                                                }
                                            }
                                            +"sandbox"
                                        }
                                        +", after previewing it there."
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
    error?.let { errorText("Couldn't publish the configuration, check its impact, or open its sandbox.", it) }
    ImpactReportDialog {
        dialog = impact
        formsOpenable = impactFormsOpenable(props.acrossClients, onSandboxPage = props.row?.sandboxOf != null)
        publishing = busy != null
        onClose = { impact = null }
        onPublish = { name, acknowledge -> publish(name, acknowledge) }
    }
}

/** A bundle row's Check impact button (issue #935). */
private fun ChildrenBuilder.checkImpactButton(name: String, busy: String?, canPublish: Boolean, check: (String, Boolean) -> Unit) {
    Button {
        size = "small"
        disabled = busy != null
        asDynamic()["title"] = "What publishing it would do to the forms the client already stores."
        onClick = { check(name, canPublish) }
        +"Check impact"
    }
}

private external interface ImpactReportDialogProps : Props {
    var dialog: ImpactDialog?
    var formsOpenable: Boolean
    var publishing: Boolean
    var onClose: () -> Unit
    var onPublish: (name: String, acknowledge: Boolean) -> Unit
}

/**
 * The publish impact report in a dialog (issue #935): the report ([impactReportBody]), its forms openable where this
 * session can read them ([impactFormsOpenable]). Its publish button goes ahead -- **Publish anyway**, acknowledging the report, when it found anything; plain Publish after a
 * check that found nothing -- and is absent where the configuration publishes from the sandbox.
 */
private val ImpactReportDialog = FC<ImpactReportDialogProps> { props ->
    val dialog = props.dialog
    val report = dialog?.report
    Modal {
        open = dialog != null
        title = dialog?.let { if (it.refused) "Publishing ${it.name} was stopped" else "Impact of publishing ${it.name}" }
        onCancel = props.onClose
        footer = if (dialog == null) null else Fragment.create {
            Button {
                onClick = props.onClose
                +(if (report?.blocks == true && dialog.canPublish) "Cancel" else "Close")
            }
            if (dialog.canPublish) {
                Button {
                    type = "primary"
                    danger = report?.blocks == true
                    loading = props.publishing
                    onClick = { props.onPublish(dialog.name, report?.blocks == true) }
                    +(if (report?.blocks == true) "Publish anyway" else "Publish")
                }
            }
        }
        if (report != null) impactReportBody(report, props.formsOpenable)
    }
}

/**
 * An impact report's body (issues #935, #1040): [impactSummary] -- saying what is [doing] it -- then a line per finding
 * ([impactFindingText]) with a few of the forms' ids to go and look at, each opening in the raw trait editor in a new
 * tab where this session can read them ([formsOpenable]); otherwise a note says where they open instead. Shared by
 * the client page's publish dialog and Design View's save of a removed choice.
 */
internal fun ChildrenBuilder.impactReportBody(report: ImpactReportView, formsOpenable: Boolean, doing: String? = null) {
    p { +(doing?.let { impactSummary(report, it) } ?: impactSummary(report)) }
    if (!formsOpenable && report.findings.isNotEmpty()) {
        p {
            className = ClassName("type-hint")
            +"These forms are ${report.client}'s, which this sandbox session cannot open; open them from ${report.client}'s own page."
        }
    }
    if (report.findings.isNotEmpty()) {
        ul {
            report.findings.forEachIndexed { i, f ->
                li {
                    key = i.toString().unsafeCast<Key>()
                    +impactFindingText(f)
                    if (f.sampleIds.isNotEmpty()) {
                        div {
                            className = ClassName("type-hint")
                            +(if (f.count > f.sampleIds.size) "For example: " else "Forms: ")
                            f.sampleIds.forEachIndexed { j, id ->
                                if (j > 0) +", "
                                if (formsOpenable) {
                                    a {
                                        href = impactFormHref(id)
                                        target = WindowTarget._blank
                                        rel = "noopener"
                                        title = "Open this form's entries in a new tab."
                                        code { +id }
                                    }
                                } else {
                                    code { +id }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

external interface SandboxSectionProps : Props {
    var row: ClientOverview
    var onChanged: () -> Unit
}

/**
 * A client's sandbox on its detail page (issue #932): what it has ([sandboxControl]), and adding or removing one. The
 * change is published and the client reloaded in the one call, so the sandbox is there -- or gone -- at once, and
 * [SandboxSectionProps.onChanged] re-reads the page and the shell (whose Open sandbox follows it).
 */
private val SandboxSection = FC<SandboxSectionProps> { props ->
    var busy by useState(false)
    var error by useState<DisplayError?>(null)
    useEffect(props.row.clientId) { error = null }
    val control = sandboxControl(props.row)
    p {
        className = ClassName("subtitle")
        +control.text
    }
    control.add?.let { add ->
        Button {
            type = if (add) "primary" else "default"
            loading = busy
            onClick = {
                busy = true
                error = null
                clientsScope.launch {
                    try {
                        ClientsApi.setSandbox(props.row.clientId, add)
                        props.onChanged()
                    } catch (e: Throwable) {
                        error = userFacingError(e)
                    } finally {
                        busy = false
                    }
                }
            }
            +(if (add) "Add a sandbox" else "Remove the sandbox")
        }
    }
    error?.let { errorText(if (control.add == true) "Couldn't add the sandbox." else "Couldn't remove the sandbox.", it) }
}

external interface CopyEditorProps : Props {
    var clientId: String
    var rows: List<CopyOverrideView>
    var onChanged: () -> Unit
}

/**
 * The copy a client rewords, and its editor (issue #918). The table: each key with what everybody else reads, what
 * the client reads, who set it, and its actions -- **Edit** opens the editor on that key, **Reset** (a stored value
 * only: data cannot take away what source code or the shipped file says) removes the client's value. **Add an
 * override** offers every shipped key the client does not yet set -- file, then namespace, then key, each a choice
 * -- with the value the client reads today as the starting text; the keys are fetched the first time it is opened.
 *
 * A save sets the key and makes it live in one call; the backend refuses a value whose trial finds a problem, and
 * the refusal is shown in its words under the editor. Once live, [CopyEditorProps.onChanged] re-reads the page.
 */
private val CopyEditor = FC<CopyEditorProps> { props ->
    var target by useState<CopyEditTarget?>(null)
    var draft by useState("")
    var busy by useState(false)
    var editError by useState<DisplayError?>(null)
    var note by useState<String?>(null)
    // The "add an override" picker: the shipped keys (read on first open), and the choice so far.
    var adding by useState(false)
    var keys by useState<List<CopyKeyView>?>(null)
    var keysError by useState<DisplayError?>(null)
    var pickFile by useState<String?>(null)
    var pickNamespace by useState<String?>(null)
    val latestKeys = useRef(0)

    // A new client under the same editor: nothing of the previous one's editing state carries over -- and a keys
    // fetch still in flight for the previous client is disowned, so it cannot fill the new one's picker.
    useEffect(props.clientId) {
        latestKeys.current = (latestKeys.current ?: 0) + 1
        target = null
        adding = false
        keys = null
        keysError = null
        pickFile = null
        pickNamespace = null
        editError = null
        note = null
    }

    fun open(fileId: String, namespace: String, key: String, startValue: String, audience: String) {
        target = CopyEditTarget(fileId, namespace, key, startValue, audience)
        draft = startValue
        adding = false
        editError = null
        note = null
    }

    fun close() {
        target = null
        editError = null
    }

    fun run(action: suspend () -> CopyEditResult, done: (CopyEditResult) -> String) {
        busy = true
        editError = null
        clientsScope.launch {
            try {
                val result = action()
                note = savedNote(done(result), result.mode)
                target = null
                props.onChanged()
            } catch (e: Throwable) {
                editError = userFacingError(e)
            } finally {
                busy = false
            }
        }
    }

    fun openAdd() {
        adding = true
        target = null
        editError = null
        note = null
        if (keys != null) return
        val token = (latestKeys.current ?: 0) + 1
        latestKeys.current = token
        clientsScope.launch {
            try {
                val loaded = ClientsApi.copyKeys(props.clientId)
                if (latestKeys.current == token) {
                    keys = loaded
                    keysError = null
                }
            } catch (e: Throwable) {
                if (latestKeys.current == token) keysError = userFacingError(e)
            }
        }
    }

    if (props.rows.isNotEmpty()) {
        div {
            className = ClassName("op-table-scroll")
            table {
                className = ClassName("op-table")
                thead {
                    tr {
                        th { +"Copy" }
                        th { +"Shipped" }
                        th { +"This client" }
                        th { +"Set by" }
                        th { +"" }
                    }
                }
                tbody {
                    props.rows.forEach { r ->
                        tr {
                            key = copyKeyText(r).unsafeCast<Key>()
                            td {
                                +copyKeyText(r)
                                // A file the application does not show (issue #933): the override is stored and
                                // served, and changes nothing anyone sees here -- said, so a change that seems to
                                // do nothing is explained.
                                if (r.shownOn == null) {
                                    +" "
                                    span {
                                        className = ClassName("subtitle")
                                        title = "$notShownNote: a deployment's own frontend may read it by URL, but no page here does."
                                        +"(not shown here)"
                                    }
                                }
                                // An orphan replaces nothing anybody reads -- usually a renamed key -- and silently
                                // reverts to the shipped copy, which is exactly why it is said here.
                                if (r.orphan) {
                                    +" "
                                    span {
                                        className = ClassName("subtitle")
                                        title = "No shipped copy declares this key, so this value is not read."
                                        +"(orphan)"
                                    }
                                }
                            }
                            td { valueCell(r.baseValue) }
                            td {
                                valueCell(r.value)
                                // The client's own source value a stored change overrides: what a reset returns to.
                                r.sourceValue?.let {
                                    div {
                                        className = ClassName("subtitle cell-clamp")
                                        title = it
                                        +"was: $it"
                                    }
                                }
                            }
                            td { +setByText(r.configName, r.origin, r.template) }
                            td {
                                // An orphan has no shipped key to set, so a save of it would only be refused: reset
                                // is what works on that row.
                                if (!r.orphan) {
                                    Button {
                                        type = "link"
                                        size = "small"
                                        disabled = busy
                                        onClick = { open(r.fileId, r.namespace, r.key, r.value.orEmpty(), r.audience) }
                                        +"Edit"
                                    }
                                }
                                if (copyRowResettable(r)) {
                                    Button {
                                        type = "link"
                                        size = "small"
                                        disabled = busy
                                        onClick = {
                                            // What it reads now may be the client's source value, not the shipped
                                            // one, so the note says the value rather than guessing its origin.
                                            run({ ClientsApi.resetCopy(props.clientId, r.fileId, r.namespace, r.key) }) {
                                                "Reset ${r.fileId}: ${r.namespace}.${r.key}; it now reads " +
                                                    (if (it.value == null) "nothing." else "\"${it.value}\".")
                                            }
                                        }
                                        +"Reset"
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    val open = target
    if (open != null) {
        div {
            className = ClassName("copy-editor")
            h3 { +"${open.fileId}: ${open.namespace}.${open.key}" }
            textarea {
                // `code` is what draws the inset well (background and border); without it the textarea keeps the
                // browser's light default and the theme's near-white text is unreadable on it.
                className = ClassName("code json-edit copy-edit")
                value = draft
                disabled = busy
                spellCheck = true
                onChange = { e -> draft = e.target.value }
            }
            p {
                className = ClassName("type-hint")
                // The value's syntax, by who the file is for (issue #1001), then what a save does.
                +"${copySyntaxHint(open.audience)} Saving makes it live for this client at once -- or, for a client with a sandbox, saves a draft the sandbox shows. A value the checks fault is refused and nothing changes."
            }
            editError?.let { errorText("Couldn't save the copy.", it) }
            div {
                className = ClassName("row")
                Button {
                    type = "primary"
                    loading = busy
                    disabled = draft == open.startValue
                    onClick = {
                        run({ ClientsApi.setCopy(props.clientId, open.fileId, open.namespace, open.key, draft) }) {
                            "Saved ${open.fileId}: ${open.namespace}.${open.key} to ${it.configName}."
                        }
                    }
                    +"Save"
                }
                Button {
                    type = "link"
                    disabled = busy
                    onClick = { close() }
                    +"Cancel"
                }
            }
        }
    } else if (adding) {
        div {
            className = ClassName("copy-editor")
            h3 { +"Add an override" }
            when {
                keysError != null -> errorText("Couldn't load the copy keys.", keysError!!)
                keys == null -> p {
                    className = ClassName("subtitle")
                    +"Loading…"
                }
                else -> {
                    val addable = addableCopyKeys(keys!!, props.rows)
                    // The files the application shows first, each saying where; the rest set apart (issue #933).
                    val files = copyFileChoices(addable)
                    val namespaces = addable.filter { it.fileId == pickFile }.map { it.namespace }.distinct()
                    val choices = addable.filter { it.fileId == pickFile && it.namespace == pickNamespace }
                    div {
                        className = ClassName("row")
                        Select {
                            value = pickFile
                            placeholder = "File"
                            options = groupedOptions(
                                "Shown by this application" to files.filter { it.shownOn != null }.map { copyFileLabel(it) to it.fileId },
                                notShownNote to files.filter { it.shownOn == null }.map { it.fileId to it.fileId },
                            )
                            style = js("({ minWidth: 260 })")
                            onChange = { v -> pickFile = v as? String; pickNamespace = null }
                        }
                        Select {
                            value = pickNamespace
                            placeholder = "Namespace"
                            disabled = pickFile == null
                            options = choiceOptions(namespaces.map { it to it })
                            style = js("({ minWidth: 160 })")
                            onChange = { v -> pickNamespace = v as? String }
                        }
                        Select {
                            value = null
                            placeholder = "Key"
                            disabled = pickNamespace == null
                            options = choiceOptions(choices.map { it.key to it.key })
                            style = js("({ minWidth: 200 })")
                            onChange = { v ->
                                choices.firstOrNull { it.key == v as? String }?.let { open(it.fileId, it.namespace, it.key, it.value, it.audience) }
                            }
                        }
                        Button {
                            type = "link"
                            onClick = { adding = false }
                            +"Cancel"
                        }
                    }
                }
            }
        }
    } else {
        div {
            className = ClassName("row")
            Button {
                disabled = busy
                onClick = { openAdd() }
                +"Add an override"
            }
        }
        note?.let {
            p {
                className = ClassName("subtitle")
                +it
            }
        }
        // A reset's refusal lands here, since no editor is open for it.
        editError?.let { errorText("Couldn't change the copy.", it) }
    }
}

external interface MenuEditorProps : Props {
    var clientId: String
    /** Which config set each changed item, by item id -- from the overrides report, which knows source from stored. */
    var setBy: Map<String, String>
    var onChanged: () -> Unit
}

/**
 * The home menu as a client sees it, and its editor (issue #919). Every item the menu holds -- before any one
 * caller's cfacts are applied, since an editor lists what can be changed -- with its shipped label, the client's,
 * who is offered it, and the actions: **Rename**, **Hide**, **Show** (to an audience the shipped menu already draws
 * for -- a client picks one, it never writes an expression) and, where the client's stored configuration changed
 * the item, **Reset**. Read from `/clientAdmin/client/menu/items` on mount and again after each change.
 *
 * Hiding or showing is presentation, not permission -- the section gate still decides who may reach a page -- and
 * the hint under the table says so. A change is written, trial-checked, published and made live in one call, as a
 * copy edit is; a refusal is shown in the backend's words.
 */
private val MenuEditor = FC<MenuEditorProps> { props ->
    var items by useState<List<MenuItemView>?>(null)
    var loadError by useState<DisplayError?>(null)
    var busy by useState(false)
    var editError by useState<DisplayError?>(null)
    var note by useState<String?>(null)
    // The item being renamed, and its draft; the item being shown, and the chosen audience.
    var renaming by useState<String?>(null)
    var draft by useState("")
    var showing by useState<String?>(null)
    var audience by useState<String?>(null)
    val latest = useRef(0)
    val lastClient = useRef<String>(null)
    // Re-read on the app's refresh generation -- an idle tick, a return to the tab, another editor's save -- as the
    // rest of the page does; an edit here bumps it through `onChanged`.
    val generation = useRefreshGeneration()

    useEffect(props.clientId, generation) {
        val token = (latest.current ?: 0) + 1
        latest.current = token
        // A new client: nothing of the previous one's rows or editing state carries over, while a re-read of the
        // same client keeps what is shown until its replacement arrives.
        if (lastClient.current != props.clientId) {
            lastClient.current = props.clientId
            items = null
            renaming = null
            showing = null
            editError = null
            note = null
        }
        clientsScope.launch {
            try {
                val loaded = ClientsApi.menuItems(props.clientId)
                if (latest.current == token) {
                    items = loaded
                    loadError = null
                }
            } catch (e: Throwable) {
                if (latest.current == token) loadError = userFacingError(e)
            }
        }
    }

    fun run(action: suspend () -> MenuEditResult, done: (MenuEditResult) -> String) {
        busy = true
        editError = null
        clientsScope.launch {
            try {
                val result = action()
                note = savedNote(done(result), result.mode)
                renaming = null
                showing = null
                props.onChanged()
            } catch (e: Throwable) {
                editError = userFacingError(e)
            } finally {
                busy = false
            }
        }
    }

    when {
        loadError != null -> errorText("Couldn't load this client's menu.", loadError!!)
        items == null -> p {
            className = ClassName("subtitle")
            +"Loading…"
        }
        else -> {
            val listed = items!!
            val audiences = menuAudiences(listed)
            val groups = menuGroups(listed)
            // A rename is sent only when it would change something; Enter and the Save button agree on that.
            fun rename(item: MenuItemView) {
                if (draft.isBlank() || draft == item.label) return
                run({ ClientsApi.setMenuItem(props.clientId, item.itemId, draft, null, null) }) { "Renamed ${item.itemId} to \"${it.label}\"." }
            }
            div {
                className = ClassName("op-table-scroll")
                table {
                    className = ClassName("op-table")
                    thead {
                        tr {
                            th { +"Item" }
                            th { +"Shipped" }
                            th { +"This client" }
                            th { +"Offered to" }
                            th { +"Set by" }
                            th { +"" }
                        }
                    }
                    tbody {
                        listed.forEach { item ->
                            tr {
                                key = item.itemId.unsafeCast<Key>()
                                td {
                                    // A child sits under its parent, as it does in the menu.
                                    if (item.parentId != null) +"\u2003"
                                    +item.itemId
                                }
                                td { +(item.baseLabel ?: "\u2014") }
                                td {
                                    if (renaming == item.itemId) {
                                        Input {
                                            value = draft
                                            disabled = busy
                                            style = js("({ width: 200 })")
                                            onChange = { e -> draft = e.target.value }
                                            onPressEnter = { rename(item) }
                                        }
                                    } else {
                                        +(item.label ?: "\u2014")
                                        if (item.stored) {
                                            +" "
                                            span {
                                                className = ClassName("subtitle")
                                                +"(changed)"
                                            }
                                        }
                                    }
                                }
                                td {
                                    if (showing == item.itemId) {
                                        Select {
                                            value = audience
                                            placeholder = "Offer to"
                                            options = choiceOptions(audiences.map { audienceText(it) to it })
                                            style = js("({ minWidth: 200 })")
                                            onChange = { v -> audience = v as? String }
                                        }
                                    } else {
                                        +menuVisibilityText(item)
                                    }
                                }
                                td { +(props.setBy[item.itemId] ?: "\u2014") }
                                td {
                                    when {
                                        renaming == item.itemId -> {
                                            Button {
                                                type = "primary"
                                                size = "small"
                                                loading = busy
                                                disabled = draft.isBlank() || draft == item.label
                                                onClick = { rename(item) }
                                                +"Save"
                                            }
                                            Button {
                                                type = "link"
                                                size = "small"
                                                disabled = busy
                                                onClick = { renaming = null; editError = null }
                                                +"Cancel"
                                            }
                                        }
                                        showing == item.itemId -> {
                                            Button {
                                                type = "primary"
                                                size = "small"
                                                loading = busy
                                                disabled = audience == null
                                                onClick = {
                                                    run({ ClientsApi.setMenuItem(props.clientId, item.itemId, null, MNU.show, audience) }) { "${item.itemId} is now offered to ${audienceText(it.condition)}." }
                                                }
                                                +"Show"
                                            }
                                            Button {
                                                type = "link"
                                                size = "small"
                                                disabled = busy
                                                onClick = { showing = null; editError = null }
                                                +"Cancel"
                                            }
                                        }
                                        else -> {
                                            Button {
                                                type = "link"
                                                size = "small"
                                                disabled = busy
                                                onClick = { renaming = item.itemId; draft = item.label.orEmpty(); showing = null; editError = null; note = null }
                                                +"Rename"
                                            }
                                            if (menuItemHidden(item)) {
                                                Button {
                                                    type = "link"
                                                    size = "small"
                                                    disabled = busy
                                                    onClick = { showing = item.itemId; audience = null; renaming = null; editError = null; note = null }
                                                    +"Show"
                                                }
                                            } else if (item.itemId in groups) {
                                                // A group cannot be hidden -- the bar would take its children with it -- so
                                                // no Hide is offered; the backend refuses it too.
                                                span {
                                                    className = ClassName("subtitle")
                                                    title = "A group cannot be hidden: hide the items under it instead."
                                                    +"group"
                                                }
                                            } else {
                                                Button {
                                                    type = "link"
                                                    size = "small"
                                                    disabled = busy
                                                    onClick = {
                                                        run({ ClientsApi.setMenuItem(props.clientId, item.itemId, null, MNU.hide, null) }) { "${item.itemId} is now hidden." }
                                                    }
                                                    +"Hide"
                                                }
                                            }
                                            if (item.stored) {
                                                Button {
                                                    type = "link"
                                                    size = "small"
                                                    disabled = busy
                                                    onClick = {
                                                        run({ ClientsApi.resetMenuItem(props.clientId, item.itemId) }) { "Reset ${item.itemId}; it shows as shipped." }
                                                    }
                                                    +"Reset"
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
            p {
                className = ClassName("type-hint")
                +"Hiding or showing an item changes what is offered, not who may reach the page behind it: the section gate still decides that."
            }
            editError?.let { errorText("Couldn't change the menu.", it) }
            note?.let {
                p {
                    className = ClassName("subtitle")
                    +it
                }
            }
        }
    }
}

/** An audience as the Show choice names it: "everyone" for the always-condition, else the expression as written. */
private fun audienceText(condition: String?): String = when (condition) {
    null, CFACT.alwaysName -> "everyone"
    else -> condition
}

/** antd option groups for a Select: `{ label, options: [{ label, value }] }` per non-empty group, in the order given. */
private fun groupedOptions(vararg groups: Pair<String, List<Pair<String, String>>>): Array<dynamic> = groups
    .filter { it.second.isNotEmpty() }
    .map { (title, pairs) ->
        val obj: dynamic = js("({})")
        obj.label = title
        obj.options = choiceOptions(pairs)
        obj
    }.toTypedArray()

/** antd `{ label, value }` objects for a Select, from label/value pairs. */
private fun choiceOptions(pairs: List<Pair<String, String>>): Array<dynamic> = pairs.map { (label, value) ->
    val obj: dynamic = js("({})")
    obj.label = label
    obj.value = value
    obj
}.toTypedArray()

/** The interface items a client changes: what it did, the base label, the client's, and who set it. */
private fun ChildrenBuilder.blockOverridesTable(rows: List<BlockOverrideView>) {
    div {
        className = ClassName("op-table-scroll")
        table {
            className = ClassName("op-table")
            thead {
                tr {
                    th { +"Menu item" }
                    th { +"Change" }
                    th { +"Shipped" }
                    th { +"This client" }
                    th { +"Set by" }
                }
            }
            tbody {
                rows.forEachIndexed { i, r ->
                    tr {
                        key = "${blockItemText(r)}#$i".unsafeCast<Key>()
                        td { +blockItemText(r) }
                        td { +menuChangeText(r) }
                        td { +(r.baseLabel ?: "\u2014") }
                        td { +blockValueText(r).ifEmpty { "\u2014" } }
                        td { +blockSetByText(r) }
                    }
                }
            }
        }
    }
}

/**
 * A fragment value as a cell: rendered inline, since it is Markdown, and clamped to one line by `.cell-clamp` with
 * the whole on hover -- clamped after rendering, never cut before it, since a cut through a link or an emphasis
 * would show its syntax. A dash for none.
 */
private fun ChildrenBuilder.valueCell(value: String?) {
    if (value == null) {
        +"\u2014"
        return
    }
    span {
        className = ClassName("cell-clamp")
        title = value
        MarkdownInline { source = value }
    }
}

/**
 * The overrides across every client (issue #917): each overridden key, grouped by file or block, with the clients
 * that override it and their values -- the `allClients` administrator's answer to "who customizes what".
 */
private fun ChildrenBuilder.overridesAcross(
    rows: List<ClientOverview>?,
    byClient: List<Pair<String, ClientOverridesView>>?,
    /** The listing's failure: to load, or -- with rows on screen -- to refresh; each said as what it is. */
    loadError: DisplayError?,
    acrossError: DisplayError?,
) {
    div {
        className = ClassName("card wide")
        backToListing(HMENU.pageClients)
        h1 { +"Copy & menu across clients" }
        p {
            className = ClassName("subtitle")
            +"Every piece of copy and every menu item a client changes, and which clients change it."
        }
        loadError?.let { errorText(if (rows == null) "Couldn't load the clients." else "Couldn't refresh the clients; showing what was loaded.", it) }
        acrossError?.let { errorText(if (byClient == null) "Couldn't load the clients' changes." else "Couldn't refresh the clients' changes; showing what was loaded.", it) }
        val keys = byClient?.let { overridesAcrossClients(it) }
        when {
            rows == null || keys == null -> if (loadError == null && acrossError == null) p {
                className = ClassName("subtitle")
                +"Loading…"
            }
            keys.isEmpty() -> p {
                className = ClassName("subtitle")
                +"No client changes the shipped copy or menu."
            }
            else -> div {
                className = ClassName("op-table-scroll")
                table {
                    className = ClassName("op-table")
                    thead {
                        tr {
                            th { +"File or menu" }
                            th { +"Key" }
                            th { +"Clients" }
                        }
                    }
                    tbody {
                        keys.forEach { k ->
                            tr {
                                key = "${k.group}|${k.key}".unsafeCast<Key>()
                                td { +k.group }
                                td { +k.key }
                                td {
                                    ul {
                                        className = ClassName("wf-reasons")
                                        k.clients.forEach { (clientId, value) ->
                                            li {
                                                key = clientId.unsafeCast<Key>()
                                                a {
                                                    className = ClassName("wf-cell-link")
                                                    href = clientOverridesHref(clientId)
                                                    +clientLabel(clientId, rows.firstOrNull { it.clientId == clientId }?.name.orEmpty())
                                                }
                                                +": "
                                                valueCell(value)
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

/** The page as a denial: the heading and why, whichever layer said so. */
private fun ChildrenBuilder.deniedCard(why: String) {
    div {
        className = ClassName("card wide")
        h1 { +"Clients" }
        p {
            className = ClassName("subtitle")
            +why
        }
    }
}

/**
 * The listing card: the heading, a line saying whose clients these are, and the table -- or the empty state. A
 * [loadError] with rows on screen is a failed refresh: said above the rows, which stay.
 */
private fun ChildrenBuilder.clientsListing(rows: List<ClientOverview>?, acrossClients: Boolean, loadError: DisplayError?) {
    div {
        className = ClassName("card wide")
        h1 { +"Clients" }
        p {
            className = ClassName("subtitle")
            +(if (acrossClients) "Every client this node knows of, present or not. " else "Your client, as this node carries it.")
            // The view across clients (issue #917) is the allClients administrator's: a scoped one has one client.
            if (acrossClients) {
                a {
                    className = ClassName("wf-cell-link")
                    href = overridesAcrossHref()
                    +"Copy & menu across clients"
                }
            }
        }
        loadError?.let { errorText(if (rows == null) "Couldn't load the clients." else "Couldn't refresh the clients; showing what was loaded.", it) }
        when {
            rows == null -> if (loadError == null) p {
                className = ClassName("subtitle")
                +"Loading…"
            }
            rows.isEmpty() -> p {
                className = ClassName("subtitle")
                +"No client is present on this node for you."
            }
            else -> div {
                className = ClassName("op-table-scroll")
                table {
                    className = ClassName("op-table")
                    thead {
                        tr {
                            th { +"Client" }
                            th { +"Load" }
                            th { +"Definition" }
                            th { className = ClassName("op-num"); +"Forms" }
                            th { className = ClassName("op-num"); +"Users" }
                            th { className = ClassName("op-num"); +"Workflows" }
                            th { +"Customized" }
                        }
                    }
                    tbody {
                        // Each sandbox right after its parent (issue #932), so the pair reads together.
                        withSandboxesBesideParents(rows).forEach { c ->
                            tr {
                                key = c.clientId.unsafeCast<Key>()
                                td {
                                    // The client's own page (issue #906): its definition, issues and stored configuration.
                                    a {
                                        className = ClassName("wf-cell-link")
                                        href = hashHref(listOf(HP.page to HMENU.pageClients, HP.client to c.clientId))
                                        +clientLabel(c.clientId, c.name)
                                    }
                                    sandboxRowNote(c)?.let { note ->
                                        span {
                                            className = ClassName("sandbox-row-note")
                                            +note
                                        }
                                    }
                                }
                                td {
                                    // The issues themselves, on hover: what a check forgave, or why the client was dropped.
                                    if (c.issues.isNotEmpty()) title = c.issues.joinToString("\n")
                                    +clientLoadText(c.status, c.issues.size)
                                }
                                td { +clientOriginText(c.origin, c.storedConfigs) }
                                // The counts open the listings behind them (issue #905) -- for a present client only:
                                // the forms and users pages work in a client this node carries.
                                val present = c.status == ClientStatus.present.name
                                td {
                                    className = ClassName("op-num")
                                    countCell(c.forms.toString(), clientFormsHref(c.clientId, acrossClients).takeIf { present })
                                }
                                td {
                                    className = ClassName("op-num")
                                    countCell(userCountText(c.users, c.unclaimedUsers), clientUsersHref(c.clientId, acrossClients).takeIf { present })
                                }
                                td { className = ClassName("op-num"); +workflowsText(c.workflowCount, c.hasSurvey) }
                                // How much the client's own configuration changes (issue #917), leading to the detail's
                                // Copy & menu section; a dash, unlinked, when it changes nothing.
                                td {
                                    val text = customizedText(c.copyOverrides, c.blockOverrides)
                                    countCell(text, clientOverridesHref(c.clientId).takeIf { c.copyOverrides + c.blockOverrides > 0 })
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

/** A count as a link to the listing behind it, or plain text when there is nowhere to go. */
private fun ChildrenBuilder.countCell(text: String, href: String?) {
    if (href == null) {
        +text
    } else {
        a {
            className = ClassName("wf-cell-link")
            this.href = href
            +text
        }
    }
}
