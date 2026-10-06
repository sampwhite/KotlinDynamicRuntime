package com.dynamicruntime.sample.simulation

import com.dynamicruntime.common.context.KdrCxt
import com.dynamicruntime.common.endpoint.SchModule
import com.dynamicruntime.common.endpoint.schemaModule
import com.dynamicruntime.common.exception.KdrException
import com.dynamicruntime.common.gedra.GE
import com.dynamicruntime.common.gedra.report.ReportHistoryWriter
import com.dynamicruntime.common.gedra.report.ReportService
import com.dynamicruntime.common.gedra.report.ReportSnapshotTrigger
import com.dynamicruntime.common.home.HMENU
import com.dynamicruntime.common.http.request.ROLE
import com.dynamicruntime.common.simulation.SimulationReport
import com.dynamicruntime.common.simulation.Simulations
import com.dynamicruntime.common.simulation.simulationEndpoint
import com.dynamicruntime.common.util.addDays
import com.dynamicruntime.sample.gedra.SC
import com.dynamicruntime.sample.gedra.ST

/**
 * The sample's simulations (issue #997): scenarios over the sample's own clients, so they are declared here, compile
 * against its constants, and are offered only where the sample is loaded -- the one place they could work.
 */
object SampleSimulations {
    fun schema(cxt: KdrCxt): SchModule = schemaModule(cxt, "sample.simulation") {
        simulationEndpoint(
            ReportDemo.simulationName,
            "Creates forms for the sample's reports to show (issue #1005): ${ReportDemo.acmeForms} acme forms over " +
                "three owners, five auditors and five reporting years, and ${ReportDemo.globexForms} globex forms with " +
                "yearly records -- enough for a second page of a detail run and several groups of a grouped one. " +
                "Each run ADDS that many forms again; it never rewrites. Lists an acme administrator for the Reports " +
                "page, and an administrator over all clients for its Client picker and globex's reports.",
        ) { c, _ -> provisionReportDemo(c) }
        simulationEndpoint(
            ReportHistoryDemo.simulationName,
            "Gives the Reports page's History view a series to draw (issue #1036): everything '${ReportDemo.simulationName}' " +
                "creates, then ${ReportHistoryDemo.days} days of ${ReportHistoryDemo.formsPerDay} more acme forms each, " +
                "with a snapshot of acme's two history reports stored after each day's forms and dated that day -- " +
                "the last ${ReportHistoryDemo.days} days, ending today. The snapshots are backdated rows; the node's " +
                "clock is not moved. Each run ADDS the forms again, replaces each past day's snapshot with a newer " +
                "one (a past day keeps its latest), and adds another for today. Shares its forms and users with " +
                "'${ReportDemo.simulationName}', so running both only adds forms.",
        ) { c, _ -> provisionReportHistoryDemo(c) }
    }
}

/** The report demo's shape: how many forms, over whom, varied how. */
object ReportDemo {
    const val simulationName = "report-demo"

    const val acmeForms = 32
    const val globexForms = 10

    val auditors = listOf("Smith", "Jones", "Lee", "Okafor", "Tanaka")
    val years = listOf(2021L, 2022L, 2023L, 2024L, 2025L)

    /** The acme owners the forms are spread over, each with a name so the roster's Owner column has one. */
    val acmeOwners = listOf(
        "pat.owner@acme.example" to "Pat Owner",
        "sam.field@acme.example" to "Sam Field",
        "lee.site@acme.example" to "Lee Site",
    )
    val globexOwner = "robin.owner@globex.example" to "Robin Owner"

    const val acmeAdmin = "reports.admin@acme.example"
    const val overseer = "reports.overseer@acme.example"
}

/** The report-history demo's shape (issue #1036): how many days, how many forms a day, and which reports are snapshotted. */
object ReportHistoryDemo {
    const val simulationName = "report-history-demo"

    const val days = 5
    const val formsPerDay = 6

    /** Acme's reports that ask for history: the ones the nightly job would snapshot, and the History view charts. */
    val reports = listOf(SC.expensesByYear, SC.auditOverview)

    /**
     * Where the simulation sends its administrator: the first report's History view. The parameter names are the
     * webapp's (`HP.report`, `HP.reportView`), which this module sits below and cannot name.
     */
    val startPage = "page=${HMENU.pageReports}&rpt=${reports.first()}&view=history"
}

/**
 * The entries of acme's [n]th demo form: always an expense report -- its year, amount and count varied by [n] so the
 * totals differ -- and, for most, a site audit. Every seventh form has no audit (a blank auditor, the group with no
 * value, and what `excludeEmpty` leaves out), and every fifth audit's findings are still open. Deterministic, so a
 * rerun adds the same shapes again and a screenshot can be compared with the last.
 */
fun reportDemoAcmeEntries(n: Int): List<Map<String, Any?>> = buildList {
    add(
        mapOf(
            GE.traitId to ST.expenseReport,
            GE.data to mapOf(
                ST.year to ReportDemo.years[(n * 3) % ReportDemo.years.size],
                ST.perItemAmount to 5.0 + (n % 6) * 7.5,
                ST.itemCount to (1 + n % 4).toLong(),
            ),
        ),
    )
    if (n % 7 != 6) {
        add(
            mapOf(
                GE.traitId to SC.siteAudit,
                GE.data to mapOf(
                    SC.auditor to ReportDemo.auditors[n % ReportDemo.auditors.size],
                    SC.findings to if (n % 5 == 0) "open" else "closed",
                ),
            ),
        )
    }
}

/** The entries of globex's [n]th demo form: one to four yearly records ending at a year that varies by [n]. */
fun reportDemoGlobexEntries(n: Int): List<Map<String, Any?>> {
    val last = 2022 + n % 4
    return (0..(n % 4)).map { back ->
        val y = (last - back).toLong()
        mapOf(GE.traitId to ST.yearly, GE.data to mapOf(ST.year to y, ST.note to "Notes for $y, form ${n + 1}"))
    }
}

/**
 * Provisions the report demo (issues #1005, #997): its owners, the forms spread over them, and the administrators to
 * look at the reports as. Adds forms on every run.
 */
fun provisionReportDemo(cxt: KdrCxt): SimulationReport {
    val owners = ReportDemo.acmeOwners.map { (email, name) -> Simulations.provisionUser(cxt, email, SC.acme, ROLE.user, name = name) }
    for (n in 0 until ReportDemo.acmeForms) Simulations.createForm(cxt, owners[n % owners.size], reportDemoAcmeEntries(n))
    val globexOwner = ReportDemo.globexOwner.let { (email, name) -> Simulations.provisionUser(cxt, email, SC.globex, ROLE.user, name = name) }
    for (n in 0 until ReportDemo.globexForms) Simulations.createForm(cxt, globexOwner, reportDemoGlobexEntries(n))

    val admin = Simulations.provisionUser(cxt, ReportDemo.acmeAdmin, SC.acme, ROLE.admin, name = "Ada Reports")
    val overseerCaps = listOf(ROLE.allClients)
    val overseer = Simulations.provisionUser(cxt, ReportDemo.overseer, SC.acme, ROLE.admin, name = "Oz Overseer", capabilities = overseerCaps)
    return SimulationReport(
        clients = listOf(SC.acme, SC.globex),
        users = listOf(
            Simulations.reported(admin, ROLE.admin, "an acme administrator, who runs acme's reports"),
            Simulations.reported(overseer, ROLE.admin, "an administrator over all clients: the Client picker, and globex's reports", overseerCaps),
        ),
        startPage = "page=${HMENU.pageReports}",
        summary = "Created ${ReportDemo.acmeForms} forms in '${SC.acme}' and ${ReportDemo.globexForms} in '${SC.globex}'. " +
            "A rerun adds as many again.",
    )
}

/**
 * Provisions the report-history demo (issue #1036): the report demo's forms and users ([provisionReportDemo]), then
 * [ReportHistoryDemo.days] days' worth of further acme forms, each day's followed by a snapshot of acme's history
 * reports **dated that day** -- today's last -- so the History view opens on a series that grows day by day.
 *
 * The days are made by backdating the snapshots (`takenAt`), never by moving the instance clock: the clock is the
 * whole node's, and a simulation that rewound it would rewind every other request made meanwhile. The snapshots are
 * stored as the nightly job's would be, under this simulation's name as their launch, so a row says where it came
 * from. The forms themselves are all created now; only what the reports counted on each "day" differs.
 */
fun provisionReportHistoryDemo(cxt: KdrCxt): SimulationReport {
    val base = provisionReportDemo(cxt)
    // Already provisioned by the report demo above; asked for again only for their rows.
    val owners = ReportDemo.acmeOwners.map { (email, name) -> Simulations.provisionUser(cxt, email, SC.acme, ROLE.user, name = name) }
    val registry = ReportService.get(cxt).forClient(SC.acme)
    val reports = ReportHistoryDemo.reports.map { id ->
        registry.report(id)?.bound ?: throw KdrException("The sample's '${SC.acme}' has no report '$id' to snapshot.")
    }
    val now = cxt.instanceNow()
    for (day in 0 until ReportHistoryDemo.days) {
        for (i in 0 until ReportHistoryDemo.formsPerDay) {
            val n = ReportDemo.acmeForms + day * ReportHistoryDemo.formsPerDay + i
            Simulations.createForm(cxt, owners[n % owners.size], reportDemoAcmeEntries(n))
        }
        val takenAt = now.addDays(day - (ReportHistoryDemo.days - 1))
        for (report in reports) {
            ReportHistoryWriter.snapshot(
                cxt, SC.acme, report, ReportSnapshotTrigger.scheduled, launchName = ReportHistoryDemo.simulationName, takenAt = takenAt,
            )
        }
    }
    val added = ReportHistoryDemo.days * ReportHistoryDemo.formsPerDay
    return SimulationReport(
        clients = base.clients,
        users = base.users,
        startPage = ReportHistoryDemo.startPage,
        summary = base.summary.removeSuffix(" A rerun adds as many again.") +
            " Then $added more in '${SC.acme}' over ${ReportHistoryDemo.days} days, with a snapshot of " +
            "${ReportHistoryDemo.reports.joinToString(" and ") { "'$it'" }} dated each day, ending today. A rerun " +
            "adds the forms again and a newer snapshot for each day.",
    )
}

