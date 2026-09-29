package com.dynamicruntime.webapp

import com.dynamicruntime.common.exception.EXC
import com.dynamicruntime.common.gedra.CLD
import com.dynamicruntime.common.gedra.ClientStatus
import com.dynamicruntime.common.gedra.clientLabel
import com.dynamicruntime.common.home.HMENU
import com.dynamicruntime.common.util.toOptStr
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.launch
import react.ChildrenBuilder
import react.FC
import react.Key
import react.Props
import react.dom.html.ReactHTML.a
import react.dom.html.ReactHTML.div
import react.dom.html.ReactHTML.h1
import react.dom.html.ReactHTML.h2
import react.dom.html.ReactHTML.li
import react.dom.html.ReactHTML.p
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
import web.cssom.ClassName

private val clientsScope = MainScope()

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
 * listing knows above the retrieve's honest 404. Editing a client and designing its workflows (#903) will open here.
 */
val ClientsPage = FC<Props> {
    var config by useState<HomeConfig?>(null)
    var rows by useState<List<ClientOverview>?>(null)
    var loadError by useState<DisplayError?>(null)
    // The endpoint's refusal (a 403), in its words: a designed answer, drawn as the permission panel.
    var refusal by useState<String?>(null)
    val generation = useRefreshGeneration()
    // The open client's detail (issue #906): read on its own, keyed on the id the hash names.
    val openId = hashParams()[HP.client]
    var definition by useState<ClientDefinitionView?>(null)
    var storedConfigs by useState<List<ConfigSummaryView>?>(null)
    // The definition's failure to load, or -- a designed answer, a 403 or 404 -- what the endpoint said instead.
    var detailError by useState<DisplayError?>(null)
    var detailNote by useState<String?>(null)
    var storedError by useState<DisplayError?>(null)
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
            detailError, detailNote, storedError, current.canSeeAllClients,
        )
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
    acrossClients: Boolean,
) {
    div {
        className = ClassName("card wide")
        backToListing(HMENU.pageClients)
        h1 { +clientLabel(clientId, row?.name ?: def?.info?.get(CLD.name).toOptStr().orEmpty()) }
        for ((label, value) in clientSummaryRows(clientId, row, def, acrossClients)) readOnlyField(label, value)
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
        h2 { +"Stored configuration" }
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
            else -> div {
                className = ClassName("op-table-scroll")
                table {
                    className = ClassName("op-table")
                    thead {
                        tr {
                            th { +"Name" }
                            th { className = ClassName("op-num"); +"Version" }
                            th { +"Published" }
                            th { +"Updated" }
                            th { className = ClassName("op-num"); +"Issues" }
                        }
                    }
                    tbody {
                        configs.forEach { c ->
                            tr {
                                key = c.name.unsafeCast<Key>()
                                td { +c.name }
                                td { className = ClassName("op-num"); +c.version.toString() }
                                td { +(if (c.published) c.publishedAt?.let { formatTimestamp(it) } ?: "Yes" else "No") }
                                td { +(c.updatedAt?.let { formatTimestamp(it) } ?: "\u2014") }
                                td { className = ClassName("op-num"); +c.issueCount.toString() }
                            }
                        }
                    }
                }
            }
        }
        // Editing the client, and designing its workflows, land here (#903, later slices).
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
            +(if (acrossClients) "Every client this node knows of, present or not." else "Your client, as this node carries it.")
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
                        }
                    }
                    tbody {
                        rows.forEach { c ->
                            tr {
                                key = c.clientId.unsafeCast<Key>()
                                td {
                                    // The client's own page (issue #906): its definition, issues and stored configuration.
                                    a {
                                        className = ClassName("wf-cell-link")
                                        href = hashHref(listOf(HP.page to HMENU.pageClients, HP.client to c.clientId))
                                        +clientLabel(c.clientId, c.name)
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
