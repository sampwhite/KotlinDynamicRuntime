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

/** The lists the user set on a report -- what to group by, what a form must have -- for the report they were set on. */
private class ReportListsChoice(val key: String, val groupBy: List<String>?, val excludeEmpty: List<String>?)

/**
 * A download of a whole run (issue #1008), for the walk it is of: how far it has got while it runs, or why it failed.
 */
private class ReportDownload(val key: String, val fetched: Int, val total: Int, val error: DisplayError? = null)

/** A fetched page of a run, with the walk it was fetched for. */
private class ReportShown(val key: String, val page: ReportRunPage)

/**
 * The Reports page (issue #1007): the named reports of a client, and one of them run as a table.
 *
 * Which client and which report are **where the page is** -- they ride the hash (`c`, `rpt`) and are reached by
 * ordinary links, so Back steps through them, as the Clients page does. Whether the open report is shown per form or
 * grouped rides the hash too (`view`), so the page always shows what its address says; what it groups by and what a
 * form must have are the page's own state for that report.
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
    var shownRun by useState<ReportShown?>(null)
    var runError by useState<DisplayError?>(null)
    var running by useState(false)
    var choice by useState<ReportListsChoice?>(null)
    var paging by useState<ReportPaging?>(null)
    // Monotonic tokens, so a slow answer for a client, report or page the user has moved on from is dropped.
    val latestList = useRef(0)
    val latestRun = useRef(0)
    var download by useState<ReportDownload?>(null)
    val latestDownload = useRef(0)
    val listedClient = useRef<String>(null)

    val hash = hashParams()
    val across = config?.canSeeAllClients == true
    // Whose reports: the client an allClients administrator chose, else nobody's by name -- the endpoint then
    // answers for the caller's own client, which is all a client's own administrator may ask about.
    val client = if (across) hash[HP.client] ?: config?.user?.client else null
    val reportId = hash[HP.report]
    val report = listing?.reports?.firstOrNull { it.reportId == reportId }
    val reportKey = "${client.orEmpty()}|${reportId.orEmpty()}"

    // The setup in force: the mode the address names (else the report's own), with the lists set on this report.
    val lists = choice?.takeIf { it.key == reportKey }
    val setup = reportSetupInForce(hash[HP.reportView], report?.defaultMode, lists?.groupBy, lists?.excludeEmpty)
    val pagingKey = reportWalkKey(client, reportId, setup)
    val page = paging?.takeIf { it.key == pagingKey } ?: ReportPaging(pagingKey, null, 0)
    // The rows on screen, only while they are this walk's: another report's, or another setup's, are never drawn
    // under this one's heading while its own are on their way.
    val runPage = shownRun?.takeIf { it.key == pagingKey }?.page

    useEffect(generation) {
        reportsScope.launch {
            try {
                val loaded = HomeApi.fetchConfig()
                config = loaded
                // The client choices, for an administrator who may look across clients: the scoped overview both
                // kinds of administrator may read, so nothing full-scope is asked of a caller who would be refused.
                // Read once: every navigation bumps the generation, and the overview counts every client's forms
                // and users -- far too much to redo for a list of names that a click on a report does not change.
                if (loaded.canManageUsers && loaded.canSeeAllClients && clients == null) clients = ClientsApi.listOverview()
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
    // starts from the top. Within a walk the rows on screen stay until the next page arrives.
    val runnable = report != null
    useEffect(runnable, pagingKey, page.after, generation) {
        val token = (latestRun.current ?: 0) + 1
        latestRun.current = token
        if (!runnable || reportId == null) {
            shownRun = null
            runError = null
            running = false
            return@useEffect
        }
        running = true
        reportsScope.launch {
            try {
                val loaded = ReportsApi.run(reportId, client, setup, page.after)
                if (latestRun.current == token) {
                    shownRun = ReportShown(pagingKey, loaded)
                    runError = null
                    running = false
                }
            } catch (e: Throwable) {
                if (latestRun.current != token) return@launch
                shownRun = null
                runError = userFacingError(e)
                running = false
            }
        }
    }

    // A download is of one run: of this report, as set up. Moving to another report, client or setup abandons it --
    // the walk asks before and after each page whether it is still the latest, and a file is saved only if it is.
    useEffect(pagingKey) {
        latestDownload.current = (latestDownload.current ?: 0) + 1
        download = null
    }

    fun startDownload() {
        val id = reportId ?: return
        val token = (latestDownload.current ?: 0) + 1
        latestDownload.current = token
        val key = pagingKey
        val runSetup = setup
        download = ReportDownload(key, 0, runPage?.numAvailable ?: 0)
        reportsScope.launch {
            try {
                val whole = ReportsApi.runAll(
                    id, client, runSetup,
                    onProgress = { fetched, total -> if (latestDownload.current == token) download = ReportDownload(key, fetched, total) },
                    stillWanted = { latestDownload.current == token },
                ) ?: return@launch
                saveTextFile(reportCsvFileName(id, runSetup.mode), "text/csv;charset=utf-8", reportCsv(whole))
                if (latestDownload.current == token) download = null
            } catch (e: Throwable) {
                // Part of a run would pass for the whole of it, so a walk that fails saves nothing and says so.
                if (latestDownload.current == token) download = ReportDownload(key, 0, 0, userFacingError(e))
            }
        }
    }

    fun changeSetup(next: ReportRunSetup) {
        // The mode goes to the address, which is where the page reads it from; the lists are the session's. Writing
        // the hash fires no event, so setting the lists -- a new object even when they are unchanged -- is also what
        // makes the page read the address again.
        replaceHash(
            buildList {
                add(HP.page to HMENU.pageReports)
                client?.let { add(HP.client to it) }
                reportId?.let { add(HP.report to it) }
                add(HP.reportView to reportModeParam(next.mode))
            },
        )
        choice = ReportListsChoice(reportKey, next.groupBy, next.excludeEmpty)
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
            // A re-read that failed -- a session that ended, a server that went away -- with the page already up.
            loadError?.let { errorText("Couldn't refresh the page; showing what was loaded.", it) }
            div {
                className = ClassName("reports-layout")
                div {
                    className = ClassName("reports-list")
                    reportList(listing, listingError, client, reportId)
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
                            reportDownload(download?.takeIf { it.key == pagingKey }, ::startDownload)
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
                                reportTable(shown, client)
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
private fun ChildrenBuilder.reportList(listing: ReportListing?, error: DisplayError?, client: String?, openId: String?) {
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
                        href = reportsHref(client, r.reportId)
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

/**
 * **Download CSV** (issue #1008): the whole of the run as set up, fetched by walking its cursor. While the walk runs
 * the button says how far it has got; a failure is said beside it, and nothing is saved.
 */
private fun ChildrenBuilder.reportDownload(download: ReportDownload?, start: () -> Unit) {
    val walking = download != null && download.error == null
    div {
        className = ClassName("row reports-download")
        Button {
            size = "small"
            loading = walking
            disabled = walking
            onClick = start
            +(if (walking) reportDownloadProgressText(download!!.fetched, download.total) else "Download CSV")
        }
        download?.error?.let { errorText("The download failed part-way, so nothing was saved.", it) }
    }
}

/**
 * Hands [text] to the browser to save as [fileName]: a Blob behind a transient link, so nothing leaves the page and
 * no server holds the file. The leading byte-order mark is what makes Excel read the file as UTF-8.
 */
private fun saveTextFile(fileName: String, mimeType: String, text: String) {
    js(
        """
        (function () {
            var blob = new Blob(['\uFEFF' + text], { type: mimeType });
            var url = URL.createObjectURL(blob);
            var a = document.createElement('a');
            a.href = url;
            a.download = fileName;
            document.body.appendChild(a);
            a.click();
            document.body.removeChild(a);
            setTimeout(function () { URL.revokeObjectURL(url); }, 1000);
        })()
        """,
    )
}

/** The run as a table: a column per [reportTableColumns], and in a detail run a leading link to each row's form. */
private fun ChildrenBuilder.reportTable(run: ReportRunPage, client: String?) {
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
                                        href = reportFormHref(id, client)
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
