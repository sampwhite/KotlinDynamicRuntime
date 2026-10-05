package com.dynamicruntime.webapp

import com.dynamicruntime.common.exception.EXC
import com.dynamicruntime.common.gedra.ClientStatus
import com.dynamicruntime.common.gedra.clientLabel
import com.dynamicruntime.common.gedra.report.ReportMode
import com.dynamicruntime.common.home.HMENU
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
import react.dom.html.ReactHTML.span
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

private val reportsScope = MainScope()

/** Where a run's walk stands: the cursor the page on screen started after, and how many rows came before it. */
private class ReportPaging(val key: String, val after: String?, val before: Int)

/** A run's setup as the user changed it, for the report it was changed on. */
private class ReportSetupChoice(val key: String, val setup: ReportRunSetup)

/**
 * The Reports page (issue #1007): the named reports of a client, and one of them run as a table.
 *
 * Which client and which report are **where the page is** -- they ride the hash (`c`, `rpt`) and are reached by
 * ordinary links, so Back steps through them, as the Clients page does. How the open report is shown -- one row per
 * form or per group, what it groups by, what a form must have -- is the page's own state, with the mode mirrored into
 * the hash (`view`) so a link reproduces it.
 *
 * **Paging goes forward only.** A page is fetched after the cursor the previous one handed back (`next`, sent as
 * `after`), and there is no Previous: a cursor says where the next page starts and nothing about the one before, so
 * the way back is **First**. The cursor lives in this component alone -- it belongs to one query, and anything that
 * changes the query (the report, the client, any control) starts the walk again.
 *
 * Denied honestly in two layers, as Clients is: the shell's `canManageUsers` shows a not-available panel without
 * calling the endpoint, and the endpoint's own refusal is shown in its words.
 */
val ReportsPage = FC<Props> {
    var config by useState<HomeConfig?>(null)
    var clients by useState<List<ClientOverview>?>(null)
    var loadError by useState<DisplayError?>(null)
    var refusal by useState<String?>(null)
    val generation = useRefreshGeneration()

    var listing by useState<ReportListing?>(null)
    var listingError by useState<DisplayError?>(null)
    var runPage by useState<ReportRunPage?>(null)
    var runError by useState<DisplayError?>(null)
    var running by useState(false)
    var choice by useState<ReportSetupChoice?>(null)
    var paging by useState<ReportPaging?>(null)
    // Monotonic tokens, so a slow answer for a client, report or page the user has moved on from is dropped.
    val latestList = useRef(0)
    val latestRun = useRef(0)
    val listedClient = useRef<String>(null)

    val hash = hashParams()
    val across = config?.canSeeAllClients == true
    // Whose reports: the client an allClients administrator chose, else nobody's by name -- the endpoint then
    // answers for the caller's own client, which is all a client's own administrator may ask about.
    val client = if (across) hash[HP.client] ?: config?.user?.client else null
    val reportId = hash[HP.report]
    val report = listing?.reports?.firstOrNull { it.reportId == reportId }
    val reportKey = "${client.orEmpty()}|${reportId.orEmpty()}"

    // The setup in force: what the user set on this report, else the hash's mode over the report's own defaults.
    val setup = choice?.takeIf { it.key == reportKey }?.setup
        ?: ReportRunSetup(reportModeOf(hash[HP.reportView]) ?: report?.defaultMode ?: ReportMode.detail)
    val pagingKey = "$reportKey|${setup.signature}"
    val page = paging?.takeIf { it.key == pagingKey } ?: ReportPaging(pagingKey, null, 0)

    useEffect(generation) {
        reportsScope.launch {
            try {
                val loaded = HomeApi.fetchConfig()
                config = loaded
                // The client choices, for an administrator who may look across clients: the scoped overview both
                // kinds of administrator may read, so nothing full-scope is asked of a caller who would be refused.
                if (loaded.canManageUsers && loaded.canSeeAllClients) clients = ClientsApi.listOverview()
                loadError = null
                refusal = null
            } catch (e: Throwable) {
                if ((e as? ApiError)?.status == EXC.notAuthorized) refusal = e.message else loadError = userFacingError(e)
            }
        }
    }

    // The client's reports, once the shell has said who is asking, and again on each refresh generation.
    val mayRead = config?.canManageUsers == true
    useEffect(mayRead, client, across, generation) {
        if (!mayRead) return@useEffect
        val token = (latestList.current ?: 0) + 1
        latestList.current = token
        // Cleared only when the client changes: a re-read of the same client (every navigation bumps the generation)
        // keeps what is shown until its replacement arrives, so opening a report does not blank the page.
        if (listedClient.current != (client ?: "")) {
            listedClient.current = client ?: ""
            listing = null
        }
        reportsScope.launch {
            try {
                val loaded = ReportsApi.list(client)
                if (latestList.current == token) {
                    listing = loaded
                    listingError = null
                    refusal = null
                }
            } catch (e: Throwable) {
                if (latestList.current != token) return@launch
                if ((e as? ApiError)?.status == EXC.notAuthorized) refusal = e.message else listingError = userFacingError(e)
            }
        }
    }

    // One page of the open report. Keyed on everything that makes the query, and on the cursor: a new cursor is the
    // next page of the same walk, and anything else is a new walk -- whose paging key no longer matches, so it
    // starts from the top. The rows on screen stay until their replacement arrives.
    val runnable = report != null
    useEffect(runnable, pagingKey, page.after, generation) {
        val token = (latestRun.current ?: 0) + 1
        latestRun.current = token
        if (!runnable || reportId == null) {
            runPage = null
            runError = null
            return@useEffect
        }
        running = true
        reportsScope.launch {
            try {
                val loaded = ReportsApi.run(reportId, client, setup, page.after)
                if (latestRun.current == token) {
                    runPage = loaded
                    runError = null
                    running = false
                }
            } catch (e: Throwable) {
                if (latestRun.current != token) return@launch
                runPage = null
                runError = userFacingError(e)
                running = false
            }
        }
    }

    fun changeSetup(next: ReportRunSetup) {
        choice = ReportSetupChoice(reportKey, next)
        // The mode is part of what a link to this report reproduces; the lists are the session's.
        replaceHash(
            buildList {
                add(HP.page to HMENU.pageReports)
                hash[HP.client]?.let { add(HP.client to it) }
                reportId?.let { add(HP.report to it) }
                add(HP.reportView to reportModeParam(next.mode))
            },
        )
    }

    val current = config
    when {
        refusal != null -> reportsDenied(refusal!!)
        current == null -> LoadStateCard {
            title = "Reports"
            this.loadError = loadError
            errorLead = "Couldn't load the reports."
        }
        !current.canManageUsers -> reportsDenied("You do not have permission to run reports.")
        else -> div {
            className = ClassName("card wide")
            div {
                className = ClassName("row reports-head")
                h1 { +"Reports" }
                if (across) clientPicker(clients, client)
            }
            p {
                className = ClassName("subtitle")
                +"Named reports over a client's forms: one row per form, or grouped and totalled."
            }
            div {
                className = ClassName("reports-layout")
                div {
                    className = ClassName("reports-list")
                    reportList(listing, listingError, hash[HP.client], reportId)
                }
                div {
                    className = ClassName("reports-run")
                    when {
                        reportId == null -> p {
                            className = ClassName("subtitle")
                            +(if (listing?.reports?.isEmpty() == true) "" else "Choose a report to run it.")
                        }
                        listing != null && report == null -> p {
                            className = ClassName("subtitle")
                            +"This client has no report '$reportId'."
                        }
                        report != null -> {
                            reportHeading(report)
                            reportControls(report, setup, running, ::changeSetup)
                            runError?.let {
                                errorText("Couldn't run the report.", it)
                                // A cursor outlives neither a changed report nor a reloaded configuration: the way
                                // out of either is the same -- the first page of the run as it now is.
                                if (page.after != null) {
                                    Button {
                                        type = "link"
                                        onClick = { paging = null }
                                        +"Start again from the first page"
                                    }
                                }
                            }
                            val shown = runPage
                            if (shown == null) {
                                if (runError == null) p {
                                    className = ClassName("subtitle")
                                    +"Running…"
                                }
                            } else {
                                reportTable(shown, hash[HP.client])
                                reportPagingBar(
                                    shown, page.before, running,
                                    onFirst = { paging = null },
                                    onNext = { shown.next?.let { paging = ReportPaging(pagingKey, it, page.before + shown.rows.size) } },
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

/** The page as a denial: the heading and why, whichever layer said so. */
private fun ChildrenBuilder.reportsDenied(why: String) {
    div {
        className = ClassName("card wide")
        h1 { +"Reports" }
        p {
            className = ClassName("subtitle")
            +why
        }
    }
}

/**
 * The client an `allClients` administrator is looking at. Choosing one is a move to another place -- a new history
 * entry, with no report open, since a report id means nothing in another client.
 */
private fun ChildrenBuilder.clientPicker(clients: List<ClientOverview>?, chosen: String?) {
    span {
        className = ClassName("type-hint")
        +"Client:"
    }
    Select {
        value = chosen
        // Only a client this node carries has reports to run.
        options = clients.orEmpty().filter { it.status == ClientStatus.present.name }.map { c ->
            val option: dynamic = js("({})")
            option.label = clientLabel(c.clientId, c.name)
            option.value = c.clientId
            option
        }.toTypedArray()
        style = js("({ minWidth: 220 })")
        onChange = { v -> (v as? String)?.let { navigateHash(listOf(HP.page to HMENU.pageReports, HP.client to it)) } }
    }
}

/** The client's reports as links, the open one marked, and under them the problems that cost the client a report. */
private fun ChildrenBuilder.reportList(listing: ReportListing?, error: DisplayError?, hashClient: String?, openId: String?) {
    error?.let { errorText("Couldn't load the reports.", it) }
    when {
        listing == null -> if (error == null) p {
            className = ClassName("subtitle")
            +"Loading…"
        }
        listing.reports.isEmpty() -> p {
            className = ClassName("subtitle")
            +"This client has no reports. A report is declared in a client's configuration."
        }
        else -> ul {
            listing.reports.forEach { r ->
                li {
                    key = r.reportId.unsafeCast<Key>()
                    className = ClassName(if (r.reportId == openId) "reports-item current" else "reports-item")
                    a {
                        className = ClassName("wf-cell-link")
                        href = reportsHref(hashClient, r.reportId)
                        +r.label
                    }
                    span {
                        className = ClassName("type-hint")
                        +(if (r.columns.size == 1) "1 column" else "${r.columns.size} columns")
                        r.template?.let { +" · from template $it" }
                    }
                }
            }
        }
    }
    val issues = listing?.issues.orEmpty()
    if (issues.isNotEmpty()) {
        p {
            className = ClassName("reports-issues-lead")
            +(if (issues.size == 1) "1 report was dropped:" else "${issues.size} reports were dropped:")
        }
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

private fun ChildrenBuilder.reportHeading(report: ReportInfo) {
    h2 { +report.label }
    report.description?.let {
        p {
            className = ClassName("subtitle")
            +it
        }
    }
}

/**
 * How the report is shown: per form or grouped; in a grouped run, what it groups by; and the columns a form must
 * have a value for. Each list starts at the report's own and is the user's from the first change -- including
 * emptied, which asks for none rather than for the report's own again.
 */
private fun ChildrenBuilder.reportControls(report: ReportInfo, setup: ReportRunSetup, busy: Boolean, change: (ReportRunSetup) -> Unit) {
    fun options(columns: List<ReportColumnInfo>): Array<dynamic> = columns.map { c ->
        val option: dynamic = js("({})")
        option.label = c.label
        option.value = c.columnId
        option
    }.toTypedArray()

    fun chosen(value: dynamic): List<String> = (value as? Array<*>)?.mapNotNull { it as? String } ?: emptyList()

    div {
        className = ClassName("row reports-controls")
        Button {
            type = if (setup.mode == ReportMode.detail) "primary" else "default"
            size = "small"
            disabled = busy && setup.mode != ReportMode.detail
            onClick = { if (setup.mode != ReportMode.detail) change(ReportRunSetup(ReportMode.detail, setup.groupBy, setup.excludeEmpty)) }
            +"Per form"
        }
        Button {
            type = if (setup.mode == ReportMode.aggregate) "primary" else "default"
            size = "small"
            disabled = busy && setup.mode != ReportMode.aggregate
            onClick = { if (setup.mode != ReportMode.aggregate) change(ReportRunSetup(ReportMode.aggregate, setup.groupBy, setup.excludeEmpty)) }
            +"Grouped"
        }
        if (setup.mode == ReportMode.aggregate) {
            span {
                className = ClassName("type-hint")
                +"Group by:"
            }
            Select {
                mode = "multiple"
                value = (setup.groupBy ?: report.groupBy).toTypedArray()
                // A column whose value is a list has no one value to group by, so it is not offered.
                options = options(report.columns.filterNot { it.yieldsList })
                placeholder = "Nothing: one total row"
                style = js("({ minWidth: 200 })")
                onChange = { v -> change(ReportRunSetup(setup.mode, chosen(v), setup.excludeEmpty)) }
            }
        }
        span {
            className = ClassName("type-hint")
            +"Must have:"
        }
        Select {
            mode = "multiple"
            value = (setup.excludeEmpty ?: report.excludeEmpty).toTypedArray()
            options = options(report.columns)
            placeholder = "Any form"
            style = js("({ minWidth: 200 })")
            onChange = { v -> change(ReportRunSetup(setup.mode, setup.groupBy, chosen(v))) }
        }
    }
}

/** The run as a table: a column per [reportTableColumns], and in a detail run a leading link to each row's form. */
private fun ChildrenBuilder.reportTable(run: ReportRunPage, hashClient: String?) {
    val columns = reportTableColumns(run.summary)
    val detail = run.summary.mode == ReportMode.detail
    if (run.rows.isEmpty()) {
        p {
            className = ClassName("subtitle")
            +(if (run.numAvailable == 0) "No forms match." else "No more rows.")
        }
        return
    }
    div {
        className = ClassName("op-table-scroll")
        table {
            className = ClassName("op-table")
            thead {
                tr {
                    if (detail) th { +"Form" }
                    columns.forEach { c ->
                        th {
                            key = "${c.source.name}:${c.key}".unsafeCast<Key>()
                            if (c.numeric) className = ClassName("op-num")
                            +c.label
                        }
                    }
                }
            }
            tbody {
                run.rows.forEachIndexed { i, row ->
                    tr {
                        key = (row.gedraId ?: "group-$i").unsafeCast<Key>()
                        if (detail) {
                            td {
                                row.gedraId?.let { id ->
                                    a {
                                        className = ClassName("wf-cell-link")
                                        href = reportFormHref(id, hashClient)
                                        +"Open"
                                    }
                                }
                            }
                        }
                        columns.forEach { c ->
                            td {
                                key = "${c.source.name}:${c.key}".unsafeCast<Key>()
                                if (c.numeric) className = ClassName("op-num")
                                +reportCellText(reportCellValue(row, c), c.kind, c.source)
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * Under the table: which rows these are, what the run read and left out, and the walk's two moves -- **First**,
 * offered once the walk has left the first page, and **Next**, until a page hands back no cursor.
 */
private fun ChildrenBuilder.reportPagingBar(run: ReportRunPage, before: Int, busy: Boolean, onFirst: () -> Unit, onNext: () -> Unit) {
    div {
        className = ClassName("row reports-paging")
        span {
            className = ClassName("type-hint")
            +"${reportRangeText(before, run.rows.size, run.numAvailable, run.summary.mode)} · ${reportCountsText(run.summary)}"
        }
        if (before > 0 || run.next != null) {
            Button {
                type = "link"
                disabled = before == 0 || busy
                onClick = onFirst
                +"← First"
            }
            Button {
                type = "link"
                disabled = run.next == null || busy
                onClick = onNext
                +"Next →"
            }
        }
    }
}
