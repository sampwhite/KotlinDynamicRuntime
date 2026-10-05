package com.dynamicruntime.script

import com.dynamicruntime.common.endpoint.EP
import com.dynamicruntime.common.endpoint.clientPath
import com.dynamicruntime.common.exception.KdrException
import com.dynamicruntime.common.gedra.GDF
import com.dynamicruntime.common.gedra.GE
import com.dynamicruntime.common.gedra.GEP
import com.dynamicruntime.common.home.HMENU
import com.dynamicruntime.common.http.request.ROLE
import com.dynamicruntime.common.util.toJsonMapOrEmpty

/**
 * The names the report demo writes by. Literals rather than the sample's own constants: `config` sits below `sample`
 * in the module graph and cannot see them, and a probe that drifts from the sample fails loudly -- a create refused
 * for an unknown trait -- rather than quietly.
 */
@Suppress("ConstPropertyName")
object ReportDemo {
    const val acme = "acme"
    const val globex = "globex"

    const val siteAudit = "acmeSiteAudit"
    const val auditor = "auditor"
    const val findings = "findings"
    const val expenseReport = "sample:expenseReport"
    const val year = "year"
    const val perItemAmount = "perItemAmount"
    const val itemCount = "itemCount"
    const val yearly = "sample:yearly"
    const val note = "note"

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
            GE.traitId to ReportDemo.expenseReport,
            GE.data to mapOf(
                ReportDemo.year to ReportDemo.years[(n * 3) % ReportDemo.years.size],
                ReportDemo.perItemAmount to 5.0 + (n % 6) * 7.5,
                ReportDemo.itemCount to (1 + n % 4).toLong(),
            ),
        ),
    )
    if (n % 7 != 6) {
        add(
            mapOf(
                GE.traitId to ReportDemo.siteAudit,
                GE.data to mapOf(
                    ReportDemo.auditor to ReportDemo.auditors[n % ReportDemo.auditors.size],
                    ReportDemo.findings to if (n % 5 == 0) "open" else "closed",
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
        mapOf(GE.traitId to ReportDemo.yearly, GE.data to mapOf(ReportDemo.year to y, ReportDemo.note to "Notes for $y, form ${n + 1}"))
    }
}

/** Name of the [reportDemo] scenario. */
const val reportDemoName = "report-demo"

/**
 * Creates forms for the sample reports to show (issue #1005): [ReportDemo.acmeForms] acme forms over three owners,
 * five auditors and five reporting years, and [ReportDemo.globexForms] globex forms with yearly records -- enough for
 * a second page of a detail run and several groups of a grouped one. Needs a test instance that loads the sample
 * (`local`/`dev`, or `KDR_LOAD_SAMPLE=true`). Each run **adds** forms; on an in-memory node, rerun after a restart.
 */
fun reportDemo(cxt: ProbeContext) {
    // A create that does not make a form **fails the run** -- thrown, so the probe ends on its FAILED marker rather
    // than on "completed" above a half-made data set that reads as a whole one.
    fun create(session: ProbeSession, client: String, entries: List<Map<String, Any?>>) {
        val resp = session.sendPostRequest(clientPath(GEP.formDocCreate, client), mapOf(GDF.entries to entries))
        if (!resp.isSuccess || resp.body[EP.item].toJsonMapOrEmpty()[GDF.gedraId] == null) {
            throw KdrException(
                "A form could not be created in '$client' (HTTP ${resp.statusCode}): " +
                    "${resp.errorMessage ?: resp.rawBody.take(200)}. Does the instance at ${cxt.baseUrl} load the sample?",
            )
        }
    }

    val owners = ReportDemo.acmeOwners.map { (email, name) ->
        cxt.session(email).also { it.becomeUser(email, client = ReportDemo.acme, name = name) }
    }
    for (n in 0 until ReportDemo.acmeForms) create(owners[n % owners.size], ReportDemo.acme, reportDemoAcmeEntries(n))
    println("Created ${ReportDemo.acmeForms} forms in '${ReportDemo.acme}'.")

    val globexOwner = cxt.session("globex-owner").also {
        it.becomeUser("robin.owner@globex.example", client = ReportDemo.globex, name = "Robin Owner")
    }
    for (n in 0 until ReportDemo.globexForms) create(globexOwner, ReportDemo.globex, reportDemoGlobexEntries(n))
    println("Created ${ReportDemo.globexForms} forms in '${ReportDemo.globex}'.")

    println()
    println("Open ${cxt.baseUrl}/wa/#page=${HMENU.pageReports} as an acme administrator. To sign in as one, from the")
    println("browser's console on that page, then reload:")
    println("  fetch('/kda/fixture/becomeUser', {method: 'POST', headers: {'Content-Type': 'application/json'},")
    println("    body: JSON.stringify({email: 'reports.admin@acme.example', level: '${ROLE.admin}', client: '${ReportDemo.acme}'})})")
    println("For the Client picker and globex's yearlyNotes, add capabilities: ['${ROLE.allClients}'].")
}
