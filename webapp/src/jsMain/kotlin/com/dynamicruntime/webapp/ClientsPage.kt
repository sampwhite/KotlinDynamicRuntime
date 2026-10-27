package com.dynamicruntime.webapp

import com.dynamicruntime.common.exception.EXC
import com.dynamicruntime.common.gedra.CLD
import com.dynamicruntime.common.gedra.ClientPresentationFields
import com.dynamicruntime.common.gedra.ClientStatus
import com.dynamicruntime.common.gedra.MNU
import com.dynamicruntime.common.gedra.clientLabel
import com.dynamicruntime.common.home.HMENU
import com.dynamicruntime.common.home.PFRAG
import com.dynamicruntime.common.util.humanizeFieldName
import com.dynamicruntime.common.util.toJsonListOfStrings
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
import react.PropsWithChildren
import react.create
import react.dom.html.ReactHTML.a
import react.dom.html.ReactHTML.code
import react.dom.html.ReactHTML.details
import react.dom.html.ReactHTML.div
import react.dom.html.ReactHTML.h1
import react.dom.html.ReactHTML.h2
import react.dom.html.ReactHTML.h3
import react.dom.html.ReactHTML.li
import react.dom.html.ReactHTML.nav
import react.dom.html.ReactHTML.p
import react.dom.html.ReactHTML.section
import react.dom.html.ReactHTML.span
import react.dom.html.ReactHTML.summary
import react.dom.html.ReactHTML.table
import react.dom.html.ReactHTML.tbody
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
 * knows of -- present or not, with why not -- as a listing. A client-scoped one oversees one client, and is shown
 * it directly (issue #1082) rather than as a listing of one row.
 *
 * Denied honestly, in two layers as Users is: the shell's `canManageUsers` says whether to ask at all, and the
 * endpoint's own refusal -- a `public` self-administrator, who administers only their own users -- is shown as the
 * denial it is, in the endpoint's words, not as a failed load. A failed *refresh* keeps the listing already on
 * screen and says so above it: the page re-reads on every refresh generation, and a blip must not take away what
 * was being read.
 *
 * An administrator of **one** client is shown that client in place of a listing of one row (issue #1082,
 * [clientsOpenId]), with no way back, since there is nothing behind it.
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
    // The page's own copy (issue #1337): its sections' "What is this?" explanations.
    val pageCopy = usePageCopy(PFRAG.clients)
    var config by useState<HomeConfig?>(null)
    var rows by useState<List<ClientOverview>?>(null)
    var loadError by useState<DisplayError?>(null)
    // The endpoint's refusal (a 403), in its words: a designed answer, drawn as the permission panel.
    var refusal by useState<String?>(null)
    val generation = useRefreshGeneration()
    // A copy edit that went live bumps the generation (issue #918): this page re-reads the client, and the shell
    // re-reads its config and copy, so a changed wordmark shows in the app bar without a reload.
    val bump = useRefreshBump()
    // The open client's detail (issue #906): read on its own, keyed on the id the address names -- or, for an
    // administrator of one client, that client, with none named (issue #1082). [inPlace] says the open client is the
    // one this page shows with nothing named, so there is no listing behind it to go back to.
    val caller = config
    fun openFor(routeClient: String?) = clientsOpenId(
        routeClient, caller?.canManageUsers == true, caller?.canSeeAllClients == true, caller?.user?.client.orEmpty(),
    )
    val openId = openFor(routeParams()[HP.client])
    val inPlace = clientOpenInPlace(
        openId, caller?.canManageUsers == true, caller?.canSeeAllClients == true, caller?.user?.client.orEmpty(),
    )
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
    val acrossView = routeParams().containsKey(HP.overrides)
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
            inPlace, pageCopy, onChanged = bump,
        )
        // The view across clients, and the listing, are the allClients administrator's: a client's own
        // administrator was shown their client above (issue #1082).
        acrossView && current.canSeeAllClients -> overridesAcross(rows, acrossRows, loadError, acrossError)
        current.canSeeAllClients -> clientsListing(rows, loadError)
        // Whoever is left has no listing and no client to open: a `public` self-administrator, in the moment
        // before the overview's refusal arrives (drawn above, in its words) -- or its failure to load.
        else -> LoadStateCard {
            title = "Clients"
            this.loadError = loadError
            errorLead = "Couldn't load the clients."
        }
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
    /** Whether this client is the one the page opens with none named (issue #1082): the caller's one client. */
    inPlace: Boolean,
    /** The page's copy (issue #1337), whose explanations the sections' headings offer. */
    pageCopy: Copy,
    /** What a copy edit calls once it is live (issue #918): the page re-reads, and so does the shell. */
    onChanged: () -> Unit,
) {
    // Whether a save made here is a draft, as the page last read the client: the one answer every editor below says
    // beside its save and sends with it (issue #1191). The listing's row when it holds one, else the definition read.
    val savesDraft = pageSavesDraft(clientId, row, defSandbox = def?.info?.get(CLD.sandbox) == true)
    val present = row?.status == ClientStatus.present.name
    // Whether the client's stored configuration is edited on this page: its definition, traits, and workflows.
    val configEditable = def?.stored != null && definitionEditable(row) &&
        definitionEditOffer(def.storedConfig, configs, sandbox = savesDraft).editor
    // A client whose configuration is not edited here -- declared in source code -- still lists its workflows, for
    // their time windows: parameters, set here whoever declared the workflow (issue #1386).
    val workflowsShown = configEditable || present
    val issues = def?.issues?.map { "${it.message} ${it.degradedTo}".trim() + (if (it.origin.isEmpty()) "" else " (${it.origin})") }
        ?: row?.issues.orEmpty()
    // The panels drawn, in their order (issue #1397): what the section links under the header lead to.
    val panels = buildList {
        add(panelDefinition to "Definition")
        if (workflowsShown) add(panelFormsData to "Forms and data")
        if (!overridesRefused) add(panelCopy to "Copy")
        if (present) add(panelImages to "Images")
        add(panelPublishing to publishingHeading(row))
        if (!overridesRefused) add(panelMenu to "Menu")
        if (acrossClients && row != null) add(panelRetirement to "Retirement")
    }
    // Every child of the card is keyed (issue #1397), so each panel keeps its state by what it is, not by how many
    // elements happen to be drawn above it: mending a file ref clears an issue (issue #1176), and the issues coming
    // and going must not tear down and remake the editors below, losing what was half typed in them.
    div {
        className = ClassName("card wide")
        div {
            key = "header".unsafeCast<Key>()
            className = ClassName("client-header")
            // No way back to a listing that would only open this page again; a listing the page was opened *from*
            // is still somewhere to go back to.
            if (clientBackOffered(inPlace, routeParams()[HP.from])) backToListing(HMENU.pageClients)
            h1 { +clientLabel(clientId, row?.name ?: def?.info?.get(CLD.name).toOptStr().orEmpty()) }
            val chips = clientStatusChips(row, issues.size)
            val links = clientHeaderLinks(row, acrossClients, clientId)
            if (chips.isNotEmpty() || links.isNotEmpty()) {
                div {
                    className = ClassName("client-header-line")
                    chips.forEach { chip ->
                        span {
                            key = chip.unsafeCast<Key>()
                            className = ClassName("user-mark")
                            +chip
                        }
                    }
                    links.forEachIndexed { i, link ->
                        if (i > 0) span {
                            key = "sep$i".unsafeCast<Key>()
                            className = ClassName("subtitle")
                            +"·"
                        }
                        val target = link.href
                        if (target == null) span { key = link.text.unsafeCast<Key>(); +link.text } else a {
                            key = link.text.unsafeCast<Key>()
                            className = ClassName("wf-cell-link")
                            href = target
                            +link.text
                        }
                    }
                }
            }
            clientSaveEffectLine(row, savesDraft)?.let {
                p {
                    className = ClassName("subtitle")
                    +it
                }
            }
            unpublishedBannerText(row, configs)?.let { text ->
                div {
                    className = ClassName("client-banner")
                    +"$text "
                    panelLink(panelPublishing, "Review and publish")
                }
            }
            if (issues.isNotEmpty()) {
                div {
                    className = ClassName("client-issues")
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
            }
            if (panels.size > 2) {
                nav {
                    className = ClassName("client-sections")
                    panels.forEach { (id, title) ->
                        span {
                            key = id.unsafeCast<Key>()
                            panelLink(id, title)
                        }
                    }
                }
            }
        }
        clientPanel(panelDefinition, "Definition", "") {
            // Editing the definition (issue #1026): the presentation fields of a client defined in stored configuration.
            def?.stored?.let { stored ->
                if (definitionEditable(row)) {
                    val offer = definitionEditOffer(def.storedConfig, configs, sandbox = savesDraft)
                    if (offer.editor) {
                        DefinitionEditor {
                            this.clientId = clientId
                            baseline = stored
                            this.savesDraft = savesDraft
                            platform = acrossClients
                            platformDefaults = def.platformDefaults
                            this.onChanged = onChanged
                        }
                    }
                    offer.note?.let { note ->
                        // Said before the attempt, with the way there: the editor would only be refused for the same reason.
                        p {
                            className = ClassName("subtitle")
                            +"$note, in "
                            panelLink(panelPublishing, "Stored configuration")
                            +" below."
                        }
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
            div {
                className = ClassName("client-def-groups")
                for (group in clientSummaryGroups(clientId, row, def, acrossClients, traitsShownBelow = configEditable)) {
                    div {
                        key = group.heading.unsafeCast<Key>()
                        className = ClassName("client-def-group")
                        WhatIsThis {
                            heading = group.heading
                            headingLevel = 3
                            text = pageCopy.explanation(group.explanationNs)
                        }
                        for ((label, value) in group.rows) {
                            // The customer row opens the customer's page where the caller may (issue #1114).
                            val href = clientSummaryHref(label, row, acrossClients, def?.info?.get(CLD.customerId).toOptStr())
                            if (href == null) readOnlyField(label, value) else linkedField(label, value, href)
                        }
                    }
                }
            }
        }
        if (workflowsShown) {
            clientPanel(panelFormsData, "Forms and data", pageCopy.explanation(PFRAG.formsDataNs)) {
                // The global traits it includes (issue #1117), a field of its stored definition chosen apart from the
                // definition editor, and offered where that editor is: a save lands in the same configuration under the
                // same rules.
                val stored = def?.stored
                if (configEditable && stored != null) {
                    h3 { +"Traits" }
                    ClientTraitsSection {
                        this.clientId = clientId
                        this.stored = stored[CLD.includedTraits].toJsonListOfStrings()
                        this.savesDraft = savesDraft
                        formsOpenable = impactFormsOpenable(acrossClients, onSandboxPage = row?.sandboxOf != null)
                        this.onChanged = onChanged
                    }
                }
                // Its workflows made from templates (issue #1120), offered where the traits are, for the same reason.
                h3 { +"Workflows" }
                ClientWorkflowsSection {
                    this.clientId = clientId
                    this.savesDraft = savesDraft
                    this.configEditable = configEditable
                    formsOpenable = impactFormsOpenable(acrossClients, onSandboxPage = row?.sandboxOf != null)
                    this.onChanged = onChanged
                }
                // Bringing its forms' stored state up to date with the configuration above now, rather than overnight
                // (issue #1412). A sandbox's too: its forms are its own.
                row?.let { overview ->
                    WhatIsThis {
                        heading = "Form states"
                        headingLevel = 3
                        text = pageCopy.explanation(PFRAG.formStatesNs)
                    }
                    FormStatesSection { this.row = overview }
                }
            }
        }
        // What the client's own configuration changes about what its people see (issue #917): the copy it rewords,
        // file by file, and its editor (issues #918, #1062).
        if (!overridesRefused) {
            clientPanel(panelCopy, "Copy", pageCopy.explanation(PFRAG.copyNs)) {
                when {
                    overridesError != null -> errorText("Couldn't load this client's copy changes.", overridesError)
                    overrides == null -> p {
                        className = ClassName("subtitle")
                        +"Loading…"
                    }
                    else -> {
                        if (overrides.copy.isEmpty()) {
                            p {
                                className = ClassName("subtitle")
                                +"This client uses the shipped copy."
                            }
                        }
                        CopyFileEditor {
                            this.clientId = clientId
                            this.inPlace = inPlace
                            this.overrides = overrides.copy
                            this.savesDraft = savesDraft
                            this.onChanged = onChanged
                        }
                    }
                }
            }
        }
        // Its own tab icon and brand mark (issue #1130), and its named images (issue #1176) -- for a client this node
        // carries, whose people there are to see them. A sandbox shows its parent's web assets, and says so; its own
        // page lists the files its parent has drafted.
        if (present && row != null) {
            clientPanel(panelImages, "Images", pageCopy.explanation(PFRAG.imagesNs)) {
                h3 { +"Web assets" }
                WebAssetsSection {
                    this.clientId = clientId
                    sandboxOf = row.sandboxOf
                    this.onChanged = onChanged
                }
                WhatIsThis {
                    heading = "Files"
                    headingLevel = 3
                    text = pageCopy.explanation(PFRAG.filesNs)
                }
                FileRefsSection {
                    this.clientId = clientId
                    sandboxOf = row.sandboxOf
                    hasSandbox = row.hasSandbox
                    staticHere = row.staticHere
                    this.onChanged = onChanged
                }
            }
        }
        // Its sandbox (issue #932), then the configuration it stores, where the drafts the panels above make are
        // published. A sandbox holds no configuration of its own; it runs its parent's latest (issue #1001), listed
        // here as such.
        clientPanel(panelPublishing, publishingHeading(row), "") {
            row?.let { overview ->
                WhatIsThis {
                    heading = "Sandbox"
                    headingLevel = 3
                    text = pageCopy.explanation(PFRAG.sandboxNs)
                }
                SandboxSection {
                    this.row = overview
                    this.acrossClients = acrossClients
                    this.onChanged = onChanged
                }
            }
            WhatIsThis {
                heading = row?.sandboxOf?.let { "Configuration it runs ($it's)" } ?: "Stored configuration"
                headingLevel = 3
                headingId = storedConfigurationId
                text = pageCopy.explanation(PFRAG.storedConfigNs)
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
        }
        // The home menu and its editor (issue #919), collapsed: changing it is rare, and it is the longest table here.
        if (!overridesRefused) {
            clientPanel(panelMenu, "Menu", pageCopy.explanation(PFRAG.menuNs)) {
                when {
                    overridesError != null -> errorText("Couldn't load this client's menu changes.", overridesError)
                    overrides == null -> p {
                        className = ClassName("subtitle")
                        +"Loading…"
                    }
                    else -> {
                        // The overrides report's home-menu rows say which config -- source or stored -- set an item.
                        val setBy = overrides.blocks.filter { it.blockId == HMENU.block && it.itemId != null }.associate { it.itemId!! to blockSetByText(it) }
                        // Any other block the client changes (the sample's nav, say) is shown as it was: this editor is
                        // the home menu's.
                        val otherBlocks = overrides.blocks.filter { it.blockId != HMENU.block }
                        Disclosure {
                            summaryText = "Show the menu — ${menuPanelSummary(setBy.size)}"
                            MenuEditor {
                                this.clientId = clientId
                                this.savesDraft = savesDraft
                                this.setBy = setBy
                                this.onChanged = onChanged
                            }
                            if (otherBlocks.isNotEmpty()) {
                                h3 { +"Other interface changes" }
                                blockOverridesTable(otherBlocks)
                            }
                        }
                    }
                }
            }
        }
        // Retiring it, or bringing it back (issue #1182): the platform's switch, so only for an administrator who sees
        // every client -- who alone can reach a retired client's page, through the listing. Last, being the rarest.
        if (acrossClients) {
            row?.let { overview ->
                clientPanel(panelRetirement, "Retirement", "", extraClass = "client-panel-danger") {
                    RetirementSection {
                        this.row = overview
                        storedConfig = def?.storedConfig
                        this.configs = configs
                        this.onChanged = onChanged
                    }
                }
            }
        }
    }
}

/** The client page's panels' element ids (issue #1397), which its section links lead to. */
private const val panelDefinition = "client-definition"
private const val panelFormsData = "client-forms-data"
private const val panelCopy = "client-copy"
private const val panelImages = "client-images"
private const val panelPublishing = "client-publishing"
private const val panelMenu = "client-menu"
private const val panelRetirement = "client-retirement"

/** The publishing panel's heading: a sandbox has no sandbox of its own, only its parent's configuration. */
private fun publishingHeading(row: ClientOverview?): String = if (row?.sandboxOf != null) "Publishing" else "Sandbox and publishing"

/**
 * A panel of the client page (issue #1397): a keyed `section` set apart from the others, headed by [heading] with
 * its "What is this?" [explanation] (none when blank), its element id [id] what a section link leads to.
 */
private fun ChildrenBuilder.clientPanel(id: String, heading: String, explanation: String, extraClass: String? = null, body: ChildrenBuilder.() -> Unit) {
    section {
        key = id.unsafeCast<Key>()
        this.id = ElementId(id)
        className = ClassName(listOfNotNull("client-panel", extraClass).joinToString(" "))
        WhatIsThis {
            this.heading = heading
            text = explanation
        }
        body()
    }
}

/**
 * A link to a panel of the page (issue #1397), scrolled to rather than navigated to: the address is the page's, and a
 * hash would be read as one. Centred, not at the top: the app bar is fixed and would cover a heading scrolled under it.
 */
private fun ChildrenBuilder.panelLink(id: String, text: String) {
    a {
        className = ClassName("wf-cell-link")
        href = "#"
        onClick = { e ->
            e.preventDefault()
            document.getElementById(id)?.asDynamic()?.scrollIntoView(js("({ block: 'start', behavior: 'smooth' })"))
        }
        +text
    }
}

external interface DisclosureProps : PropsWithChildren {
    /** The line the closed disclosure shows, which opens it. */
    var summaryText: String
}

/**
 * A section of the page that starts closed (issue #1397), its contents drawn only once opened: the menu's editor,
 * whose table is the page's longest and rarely changed, reads nothing until somebody asks for it.
 */
private val Disclosure = FC<DisclosureProps> { props ->
    var expanded by useState(false)
    details {
        className = ClassName("client-disclosure")
        this.open = expanded
        onToggle = { e -> expanded = e.currentTarget.asDynamic().open == true }
        summary { +props.summaryText }
        if (expanded) +props.children
    }
}

external interface DefinitionEditorProps : Props {
    var clientId: String
    /** The client's stored definition, which the draft is seeded from and diffed against. */
    var baseline: Map<String, Any?>
    /** Whether a save is a draft, as the page last read the client ([savesAsDraft]): said beside Save, and sent with it. */
    var savesDraft: Boolean
    /** Whether the caller sees every client, and so is offered the platform's fields too (issue #1116). */
    var platform: Boolean
    /** The deployment's values of the fields a client may leave blank (issue #1308), said as each one's placeholder. */
    var platformDefaults: Map<String, Int>
    var onChanged: () -> Unit
}

/**
 * The definition editor (issues #1026, #1116): **Edit definition** opens the fields the caller's policy lets them
 * edit (`ClientPresentationFields.namesFor`) -- for a client's administrator its name, note, time zone, and suggested
 * user labels; for an administrator who sees every client also what it is for, whose it is, where it runs, its
 * preload, its domains, and its web resources -- seeded from the client's **stored** definition ([definitionDraftOf];
 * a draft already saved shows, and can be taken back); Save sends only what changed ([definitionEditRequest]) and is
 * offered only when something did; Cancel drops the draft. Everything else stays in the read-only rows above, each
 * saying who sets it ([whoSetsNote]). A save takes effect as the copy and menu editors' do -- live, or a draft for a
 * client with a sandbox, which the note says ([savedNote]) -- and [DefinitionEditorProps.onChanged] re-reads the
 * page and the shell, so the heading and the listing row follow the new name. A refusal is shown in the backend's
 * words under the fields, the draft kept, so it can be corrected rather than retyped. Beside Save it says how the
 * save will take effect ([saveEffectText]), and sends that ([expectedMode]); a save refused for no longer taking
 * effect so ([saveConflict]) re-reads the page, which then says how it does.
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
        val request = definitionEditRequest(props.clientId, baseline, draft, expectedMode(props.savesDraft), props.platform)
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
                val shown = saveFailure(e, props.onChanged)
                if (latest.current == token) editError = shown
            } finally {
                if (latest.current == token) busy = false
            }
        }
    }

    if (!editing) {
        p {
            Button {
                onClick = { open() }
                +"Edit definition"
            }
        }
    } else {
        val request = definitionEditRequest(props.clientId, baseline, draft, expectedMode(props.savesDraft), props.platform)
        // The one list the endpoint's input is built from, so a field joining it is drawn here without a change --
        // under its section's heading (issue #1308), declared beside it in the kernel.
        for ((section, names) in ClientPresentationFields.bySection(ClientPresentationFields.namesFor(props.platform))) {
            h3 { +section.heading }
            for (name in names) {
                val label = humanizeFieldName(name)
                fun set(value: String) {
                    draft = draft + (name to value)
                }
                when (ClientPresentationFields.kind(name)) {
                    ClientPresentationFields.Kind.choice -> choiceRow(label) {
                        Select {
                            value = draft[name].orEmpty()
                            options = choiceOptions(ClientPresentationFields.choicesOf(name).map { it to it })
                            disabled = busy
                            style = js("({ minWidth: 200 })")
                            onChange = { v -> set((v as? String).orEmpty()) }
                        }
                    }
                    ClientPresentationFields.Kind.choices -> choiceRow(label) {
                        Select {
                            mode = "multiple"
                            value = choicesOfText(name, draft[name].orEmpty()).toTypedArray()
                            options = choiceOptions(ClientPresentationFields.choicesOf(name).map { it to it })
                            disabled = busy
                            style = js("({ minWidth: 280 })")
                            onChange = { v -> set((v as Array<*>).joinToString(", ")) }
                        }
                    }
                    ClientPresentationFields.Kind.flag -> choiceRow(label) {
                        Checkbox {
                            checked = draft[name] == "true"
                            disabled = busy
                            onChange = { e -> set((e.target.checked == true).toString()) }
                        }
                    }
                    ClientPresentationFields.Kind.timeZone -> choiceRow(label) {
                        TimeZonePicker {
                            value = draft[name].orEmpty()
                            disabled = busy
                            onChange = { v -> set(v) }
                        }
                    }
                    ClientPresentationFields.Kind.number -> textField(
                        label, draft[name].orEmpty(), disabled = busy, placeholder = platformDefaultText(name, props.platformDefaults),
                    ) { v -> set(v) }
                    else -> textField(label, draft[name].orEmpty(), disabled = busy) { v -> set(v) }
                }
            }
        }
        p {
            className = ClassName("subtitle")
            +("Labels are comma-separated; each is matched exactly as written. $timeZoneEditNote $numberFieldsEditNote " +
                saveEffectText(props.savesDraft))
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

/** A labeled row holding a control other than a text input, laid out as [textField]'s row is. */
external interface StoredConfigTableProps : Props {
    var configs: List<ConfigSummaryView>
    var row: ClientOverview?
    var acrossClients: Boolean
    var clientId: String
    var onChanged: () -> Unit
}

/**
 * The impact dialog's subject (issues #935, #1353): a [report] on publishing [names] together, from a [refused]
 * publish or a check; with [canPublish] where this page publishes them (not a client's page whose sandbox does).
 */
private class ImpactDialog(val names: List<String>, val report: ImpactReportView, val refused: Boolean, val canPublish: Boolean)

/**
 * A client's configuration bundles (issues #906, #1001): one row per bundle -- its latest revision's version, when it
 * was published and last written, whether it is live ([bundleLiveText], by the client's tier), its issues.
 *
 * **Publishing is of a group** (issue #1353): a row whose latest revision is unpublished has a box, and nothing else
 * -- no Publish on a row. A bar above the table selects ("Select all unpublished", [toggleAllUnpublished]) and acts on
 * the selection: **Publish selected** publishes it in one request, judged together, so bundles sound only as a set --
 * a workflow moved from one to another -- go live together; **Check impact** shows what publishing exactly that
 * selection would do to the client's stored data. What the page may do is [publishGroup]'s: a client with a sandbox
 * publishes from the sandbox, so its own page checks impact only and the bar points there. A publish reloads the
 * client, and [StoredConfigTableProps.onChanged] re-reads the page.
 *
 * Publishing is judged against the client's stored data (issue #935). A publish that would affect it is refused with a
 * report, which opens a dialog listing what would happen, with **Publish anyway** for the same selection.
 */
private val StoredConfigTable = FC<StoredConfigTableProps> { props ->
    val bump = useRefreshBump()
    var busy by useState(false)
    var error by useState<DisplayError?>(null)
    var impact by useState<ImpactDialog?>(null)
    var selected by useState<Set<String>>(emptySet())
    // What the Published and Updated columns read their relative times against (issue #1141).
    val now = useRelativeClock()
    useEffect(props.clientId) { error = null; impact = null; selected = emptySet() }
    val publishedOnly = props.row?.let { it.publishedOnly || it.sandboxOf != null } ?: true
    val group = publishGroup(props.row, props.configs)
    val selectable = selectableBundles(props.row, props.configs)
    // A bundle published meanwhile, or no longer listed, drops out of the selection by itself.
    val chosen = selectedBundles(selected, selectable)

    fun publish(names: List<String>, acknowledge: Boolean) {
        busy = true
        error = null
        clientsScope.launch {
            try {
                ClientsApi.publishBundles(props.clientId, names, props.acrossClients, acknowledgeImpact = acknowledge)
                impact = null
                selected = emptySet()
                props.onChanged()
            } catch (e: Throwable) {
                val refusedOver = impactOf(e)
                if (refusedOver != null) impact = ImpactDialog(names, refusedOver, refused = true, canPublish = true) else error = userFacingError(e)
            } finally {
                busy = false
            }
        }
    }

    fun checkImpact(names: List<String>, canPublish: Boolean) {
        busy = true
        error = null
        clientsScope.launch {
            try {
                impact = ImpactDialog(names, ClientsApi.bundleImpact(props.clientId, names, props.acrossClients), refused = false, canPublish = canPublish)
            } catch (e: Throwable) {
                error = userFacingError(e)
            } finally {
                busy = false
            }
        }
    }

    // The bar: what the group may do here, and where publishing happens when not here.
    if (group != null) {
        div {
            className = ClassName("row publish-bar")
            if (selectable.isNotEmpty()) {
                Checkbox {
                    checked = chosen.size == selectable.size
                    indeterminate = chosen.isNotEmpty() && chosen.size < selectable.size
                    disabled = busy
                    onChange = { selected = toggleAllUnpublished(chosen.toSet(), selectable) }
                    +"Select all unpublished"
                }
                if (group.publish) {
                    Button {
                        type = "primary"
                        size = "small"
                        this.loading = busy && impact == null
                        disabled = busy || chosen.isEmpty()
                        group.note?.let { asDynamic()["title"] = it }
                        onClick = { publish(chosen, acknowledge = false) }
                        +"Publish selected (${chosen.size})"
                    }
                }
                if (group.checkImpact) {
                    Button {
                        size = "small"
                        disabled = busy || chosen.isEmpty()
                        asDynamic()["title"] = "What publishing the selected configurations together would do to the forms the client already stores."
                        onClick = { checkImpact(chosen, canPublish = group.publish) }
                        +"Check impact"
                    }
                }
            }
            val sandbox = group.sandboxClient
            if (sandbox == null) {
                // Publishing here: what it does, said once for the group (on a client's latest revision, it changes
                // nothing it runs). Not publishing at all: why.
                group.note?.let { note ->
                    span {
                        className = ClassName("type-hint")
                        +note
                    }
                }
            } else {
                // "Publish from its sandbox": the word leads to the sandbox's own page, where this table publishes. An
                // administrator who may open another client's detail goes straight there; a client-scoped one first
                // opens the sandbox (#929) -- a fresh session as their user in it -- and lands on that page (#1001).
                span {
                    className = ClassName("type-hint")
                    +"Publish from its "
                    a {
                        className = ClassName("wf-cell-link")
                        href = clientDetailHref(sandbox)
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
    div {
        className = ClassName("op-table-scroll")
        table {
            className = ClassName("op-table")
            thead {
                tr {
                    th { className = ClassName("op-select"); +"" }
                    th { +"Name" }
                    th { className = ClassName("op-num"); +"Version" }
                    th { +"Published" }
                    th { +"Updated" }
                    th { +"Live" }
                    th { className = ClassName("op-num"); +"Issues" }
                }
            }
            tbody {
                props.configs.forEach { c ->
                    tr {
                        key = c.name.unsafeCast<Key>()
                        // A row that could be published here is marked, so what a note above sends people to is found
                        // at a glance.
                        if (configRowNeedsPublish(props.row, c)) className = ClassName("op-row-attention")
                        td {
                            className = ClassName("op-select")
                            if (c.name in selectable) {
                                Checkbox {
                                    checked = c.name in chosen
                                    disabled = busy
                                    asDynamic()["aria-label"] = "Select ${c.name}"
                                    onChange = { e -> selected = if (e.target.checked == true) chosen.toSet() + c.name else chosen.toSet() - c.name }
                                }
                            }
                        }
                        td { +c.name }
                        td { className = ClassName("op-num"); +c.version.toString() }
                        td {
                            val at = c.publishedAt
                            when {
                                !c.published -> +"No"
                                at == null -> +"Yes"
                                else -> RelativeTime { iso = at; this.now = now }
                            }
                        }
                        td {
                            val at = c.updatedAt
                            if (at == null) +"\u2014" else RelativeTime { iso = at; this.now = now }
                        }
                        // A sandbox runs its parent's latest, so for it every row is what it runs (issue #1001).
                        td { +(if (props.row?.sandboxOf != null) "Runs here" else bundleLiveText(c, publishedOnly)) }
                        td { className = ClassName("op-num"); +c.issueCount.toString() }
                    }
                }
            }
        }
    }
    error?.let { errorText("Couldn't publish the configurations, check their impact, or open the sandbox.", it) }
    ImpactReportDialog {
        dialog = impact
        formsOpenable = impactFormsOpenable(props.acrossClients, onSandboxPage = props.row?.sandboxOf != null)
        publishing = busy
        onClose = { impact = null }
        onPublish = { names, acknowledge -> publish(names, acknowledge) }
    }
}

private external interface ImpactReportDialogProps : Props {
    var dialog: ImpactDialog?
    var formsOpenable: Boolean
    var publishing: Boolean
    var onClose: () -> Unit
    var onPublish: (names: List<String>, acknowledge: Boolean) -> Unit
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
        title = dialog?.let {
            val names = bundleNamesText(it.names)
            if (it.refused) "Publishing $names was stopped" else "Impact of publishing $names"
        }
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
                    onClick = { props.onPublish(dialog.names, report?.blocks == true) }
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
    /** Whether the caller sees every client, for whom a sandbox's way back to its client is a plain link. */
    var acrossClients: Boolean
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
    val bump = useRefreshBump()
    val shellFacts = useShellFacts()
    useEffect(props.row.clientId) { error = null }
    val control = sandboxControl(props.row)
    p {
        className = ClassName("subtitle")
        +control.text
    }
    // The way back to the client (issue #1422; the counterpart of the publishing bar's "Publish from its sandbox"):
    // having published here, to see the client as its people do. Offered on every sandbox page, whatever is published.
    // In a sandbox session it switches back as the app bar's "Back to <client>" does, to the same user; an
    // administrator who sees every client, looking at a sandbox's page from outside, needs only the link.
    sandboxWayBackTarget(props.row, props.acrossClients, shellFacts.inSandbox, shellFacts.sandboxWayBackUserId)?.let { parent ->
        p {
            className = ClassName("subtitle")
            +"What is published here reaches $parent's people. "
            a {
                className = ClassName("wf-cell-link")
                href = clientDetailHref(parent)
                shellFacts.sandboxWayBackUserId?.takeIf { shellFacts.inSandbox }?.let { userId ->
                    title = "Leave the sandbox, and show $parent's own page."
                    onClick = { e ->
                        e.preventDefault()
                        error = null
                        clientsScope.launch {
                            try {
                                AuthApi.leaveSandbox(userId)
                                afterSessionChange(bump, listOf(HP.page to HMENU.pageClients, HP.client to parent))
                            } catch (e: Throwable) {
                                error = userFacingError(e)
                            }
                        }
                    }
                }
                +"Back to $parent"
            }
            +" to see it as they do."
        }
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
    error?.let {
        val lead = when (control.add) {
            true -> "Couldn't add the sandbox."
            false -> "Couldn't remove the sandbox."
            null -> "Couldn't leave the sandbox."
        }
        errorText(lead, it)
    }
}

external interface RetirementSectionProps : Props {
    var row: ClientOverview
    /** The configuration holding the client's stored definition, as the definition read says; null until read. */
    var storedConfig: String?
    var configs: List<ConfigSummaryView>?
    var onChanged: () -> Unit
}

/**
 * Retiring a client, or bringing one back, on its detail page (issue #1182): what [retirementControl] says, and its
 * action. Retiring is asked about first, saying what follows ([retireConsequence]), since it ends the client's sessions
 * at once; bringing it back is not, since it only restores. Either is live in the one call, here and on every node, and
 * [RetirementSectionProps.onChanged] re-reads the page. A refusal shows in the backend's words.
 */
private val RetirementSection = FC<RetirementSectionProps> { props ->
    var busy by useState(false)
    var confirming by useState(false)
    var error by useState<DisplayError?>(null)
    useEffect(props.row.clientId) { error = null }
    val control = retirementControl(props.row, props.storedConfig, props.configs)
    p {
        className = ClassName("subtitle")
        +control.text
    }
    fun run(retire: Boolean) {
        busy = true
        error = null
        clientsScope.launch {
            try {
                ClientsApi.setRetired(props.row.clientId, retire)
                props.onChanged()
            } catch (e: Throwable) {
                error = userFacingError(e)
            } finally {
                busy = false
            }
        }
    }
    when (control.retire) {
        true -> Popconfirm {
            title = "Retire ${props.row.clientId}?"
            description = retireConsequence
            okText = "Retire"
            cancelText = "Cancel"
            placement = "bottomLeft"
            open = confirming
            onOpenChange = { open -> confirming = open }
            onConfirm = {
                confirming = false
                run(true)
            }
            okButtonProps = js("({ danger: true })")
            disabled = busy
            Button {
                danger = true
                loading = busy
                +"Retire"
            }
        }
        false -> Button {
            type = "primary"
            loading = busy
            onClick = { run(false) }
            +"Bring back"
        }
        null -> {}
    }
    error?.let { errorText(if (control.retire == false) "Couldn't bring the client back." else "Couldn't retire the client.", it) }
}

external interface MenuEditorProps : Props {
    var clientId: String
    /** Which config set each changed item, by item id -- from the overrides report, which knows source from stored. */
    var setBy: Map<String, String>
    /** Whether a change is a draft, as the page last read the client ([savesAsDraft]): said under the table, and sent with each. */
    var savesDraft: Boolean
    var onChanged: () -> Unit
}

/**
 * The home menu as a client sees it, and its editor (issue #919). Every item the menu holds -- before any one
 * caller's cfacts are applied, since an editor lists what can be changed -- with its shipped label, the client's,
 * who is offered it, and the actions: **Rename**, **Hide**, **Show** (to one of the audiences the backend lists for
 * the item, chosen by name -- issue #1094; a client never writes an expression) and, where the client's stored
 * configuration changed the item, **Reset**. Read from `/clientAdmin/client/menu/items` on mount and again after
 * each change.
 *
 * Hiding or showing is presentation, not permission -- the section gate still decides who may reach a page -- and
 * the hint under the table says so. A change is written, trial-checked, published and made live in one call, as a
 * copy edit is -- or a draft, which the hint says beforehand ([saveEffectText]) and each change sends
 * ([expectedMode]); a refusal is shown in the backend's words, and one for no longer taking effect so
 * ([saveConflict]) re-reads the page.
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
    // Whether every item is listed, the deployment's own included, or only those the client's people may be offered
    // (issue #1397): most of the shipped menu is for operators, test instances, and debug sessions.
    var everyItem by useState(false)
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
            // The previous client's failure is not this one's: left set, it would be shown over this one's load.
            loadError = null
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
                editError = saveFailure(e, props.onChanged)
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
            val all = items!!
            val groups = menuGroups(all)
            val forPeople = menuItemsForPeople(all)
            val listed = if (everyItem) all else forPeople
            // A rename is sent only when it would change something; Enter and the Save button agree on that.
            fun rename(item: MenuItemView) {
                if (draft.isBlank() || draft == item.label) return
                run({ ClientsApi.setMenuItem(props.clientId, item.itemId, draft, null, null, expectedMode(props.savesDraft)) }) { "Renamed ${item.itemId} to \"${it.label}\"." }
            }
            if (forPeople.size < all.size) {
                p {
                    className = ClassName("subtitle")
                    +(if (everyItem) "Every item of the menu: ${all.size}. " else "The ${forPeople.size} of ${all.size} items this client's people may be offered. ")
                    Button {
                        type = "link"
                        size = "small"
                        onClick = { everyItem = !everyItem }
                        +(if (everyItem) "Show only those" else "Show every item")
                    }
                }
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
                                            options = choiceOptions(menuShowChoices(item))
                                            style = js("({ minWidth: 260 })")
                                            onChange = { v -> audience = v as? String }
                                        }
                                    } else {
                                        // The audience by name; the condition it stands for is the detail (issue #1094).
                                        span {
                                            title = menuVisibilityDetail(item)
                                            +menuVisibilityText(item)
                                        }
                                    }
                                }
                                td { +(props.setBy[item.itemId] ?: "\u2014") }
                                td {
                                    when {
                                        // The client's own item (issue #1094): every action here changes a shipped
                                        // item, and the backend refuses one the shipped menu does not hold.
                                        item.added -> span {
                                            className = ClassName("subtitle")
                                            title = "Added by this client's configuration: there is no shipped item to rename, hide or reset to. It is changed where it is declared."
                                            +"added"
                                        }
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
                                                    run({ ClientsApi.setMenuItem(props.clientId, item.itemId, null, MNU.show, audience, expectedMode(props.savesDraft)) }) { menuShownNote(item.itemId, it) }
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
                                                        run({ ClientsApi.setMenuItem(props.clientId, item.itemId, null, MNU.hide, null, expectedMode(props.savesDraft)) }) { "${item.itemId} is now hidden." }
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
                                                        run({ ClientsApi.resetMenuItem(props.clientId, item.itemId, expectedMode(props.savesDraft)) }) { "Reset ${item.itemId}; it shows as shipped." }
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
                +"Hiding or showing an item changes what is offered, not who may reach the page behind it: the section gate still decides that. ${saveEffectText(props.savesDraft)}"
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

/** antd option groups for a Select: `{ label, options: [{ label, value }] }` per non-empty group, in the order given. */
fun groupedOptions(vararg groups: Pair<String, List<Pair<String, String>>>): Array<dynamic> = groups
    .filter { it.second.isNotEmpty() }
    .map { (title, pairs) ->
        val obj: dynamic = js("({})")
        obj.label = title
        obj.options = choiceOptions(pairs)
        obj
    }.toTypedArray()

/** antd `{ label, value }` objects for a Select, from label/value pairs. */
fun choiceOptions(pairs: List<Pair<String, String>>): Array<dynamic> = pairs.map { (label, value) ->
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

/** A summary row whose value is a link: [readOnlyField]'s shape, for a count with a listing behind it. */
private fun ChildrenBuilder.linkedField(label: String, value: String, href: String) {
    div {
        className = ClassName("row")
        span {
            className = ClassName("field-label")
            +label
        }
        span { countCell(value, href) }
    }
}

/**
 * The listing card, for an administrator who sees across clients (anyone else is shown their one client, issue
 * #1082): the heading, a line saying whose clients these are, and the table -- or the empty state. A [loadError]
 * with rows on screen is a failed refresh: said above the rows, which stay.
 */
private fun ChildrenBuilder.clientsListing(rows: List<ClientOverview>?, loadError: DisplayError?) {
    div {
        className = ClassName("card wide")
        h1 { +"Clients" }
        p {
            className = ClassName("subtitle")
            +"Every client this node knows of, present or not. "
            // The view across clients (issue #917).
            a {
                className = ClassName("wf-cell-link")
                href = overridesAcrossHref()
                +"Copy & menu across clients"
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
                                        href = clientDetailHref(c.clientId)
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
                                    countCell(c.forms.toString(), clientFormsHref(c.clientId, acrossClients = true).takeIf { present })
                                }
                                td {
                                    className = ClassName("op-num")
                                    countCell(userCountText(c.users, c.unclaimedUsers), clientUsersHref(c.clientId, acrossClients = true).takeIf { present })
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
