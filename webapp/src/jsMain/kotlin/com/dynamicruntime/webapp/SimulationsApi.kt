package com.dynamicruntime.webapp

import com.dynamicruntime.common.endpoint.EP
import com.dynamicruntime.common.test.SIM
import com.dynamicruntime.common.test.TEP
import com.dynamicruntime.common.util.jsonResult
import com.dynamicruntime.common.util.toJsonListOfMaps
import com.dynamicruntime.common.util.toJsonListOfStrings
import com.dynamicruntime.common.util.toJsonMapOrEmpty
import com.dynamicruntime.common.util.toJsonStr
import com.dynamicruntime.common.util.toOptStr

/*
 * The Simulations page's API and its pure half (issue #997): the listing is the catalog's shape, so it parses with
 * [parseCatalog]; a run answers with a report, which [parseSimulationReport] reads and the sign-in helpers turn into
 * what the page does next. Pure functions are covered by `SimulationsTest` under jsNodeTest.
 */

object SimulationsApi {
    /** The simulations this node offers, as a [Catalog] of their endpoints -- empty off a test instance. */
    suspend fun fetchSimulations(): Catalog = parseCatalog(Http.getApi(SIM.list)[EP.results].toJsonMapOrEmpty())

    /** Signs this browser in as [user] -- the `becomeUser` fixture, which a test instance offers. */
    suspend fun signInAs(user: SimulationUserInfo): ApiResult<Map<String, Any?>> =
        Http.sendApiResult("POST", TEP.becomeUser, becomeUserBody(user))
}

/** A user a simulation reported: what signing in as them takes, and what they are for. */
class SimulationUserInfo(
    val email: String,
    val client: String,
    val level: String,
    val persona: String?,
    val purpose: String,
    val capabilities: List<String>,
)

/** A simulation's report: the [clients] it provisioned, the [users] to sign in as, where to start, and a [summary]. */
class SimulationRun(
    val clients: List<String>,
    val users: List<SimulationUserInfo>,
    val startPage: String?,
    val summary: String,
)

/** The report in a run's `results`; a user missing its address or client is left out rather than offered broken. */
fun parseSimulationReport(results: Map<String, Any?>): SimulationRun = SimulationRun(
    clients = results[SIM.clients].toJsonListOfStrings(),
    users = results[SIM.users].toJsonListOfMaps().mapNotNull { u ->
        val email = u[SIM.email].toOptStr() ?: return@mapNotNull null
        val client = u[SIM.client].toOptStr() ?: return@mapNotNull null
        SimulationUserInfo(
            email, client, u[SIM.level].toOptStr().orEmpty(), u[SIM.persona].toOptStr(),
            u[SIM.purpose].toOptStr() ?: email, u[SIM.capabilities].toJsonListOfStrings(),
        )
    },
    startPage = results[SIM.startPage].toOptStr()?.removePrefix("#")?.ifBlank { null },
    summary = results[SIM.summary].toOptStr().orEmpty(),
)

/**
 * The `becomeUser` body that signs in as [user]: by address, client and persona -- which name the user, so the one the
 * simulation provisioned is the one found -- with the level and capabilities a create would need.
 */
fun becomeUserBody(user: SimulationUserInfo): Map<String, Any?> = buildMap {
    put(TEP.email, user.email)
    put(TEP.client, user.client)
    if (user.level.isNotBlank()) put(TEP.level, user.level)
    user.persona?.let { put(TEP.persona, it) }
    if (user.capabilities.isNotEmpty()) put(TEP.capabilities, user.capabilities)
}

/** A report's start page (`page=newForm&from=forms`) as the hash pairs to navigate to; empty (home) when there is none. */
fun startPageHash(startPage: String?): List<Pair<String, String>> =
    startPage.orEmpty().split('&').mapNotNull { part ->
        val key = part.substringBefore('=').trim()
        if (key.isEmpty()) null else key to part.substringAfter('=', "")
    }

/** A simulation's short name, from its endpoint path: `/fixture/simulate/design-demo` reads `design-demo`. */
fun simulationName(path: String): String = path.removePrefix(SIM.pathRoot)

/**
 * A run the Simulations page remembers (issue #1099): which [simulation], against which [dataId] -- the app config's,
 * when the run happened -- when it [ranAt] (ISO), and its [run] report. Signing in as one of its users swaps the
 * session, which leaves the page; remembered, the report is still there to come back to.
 */
class SavedSimulationRun(val simulation: String, val dataId: String?, val ranAt: String, val run: SimulationRun)

/** How many runs the page remembers: the most recent, newest first. */
const val recentRunsLimit = 10

private const val savedSimulation = "simulation"
private const val savedDataId = "dataId"
private const val savedRanAt = "ranAt"

/** [runs] with [saved] in front, newest first, keeping at most [limit]. Pure, covered under `jsNodeTest`. */
fun rememberRun(runs: List<SavedSimulationRun>, saved: SavedSimulationRun, limit: Int = recentRunsLimit): List<SavedSimulationRun> =
    (listOf(saved) + runs).take(limit)

/** The remembered runs split by whether the data they provisioned is still there: [current] and [stale]. */
class RecentRuns(val current: List<SavedSimulationRun>, val stale: List<SavedSimulationRun>)

/**
 * [runs] split by [dataId], the data the node serves now: a run made against other data -- an in-memory node since
 * restarted, say -- provisioned users that are gone, so it is [RecentRuns.stale] and offers no sign-in. A node that
 * serves no data id cannot say, and every run counts as current. Pure, covered under `jsNodeTest`.
 */
fun recentRunsFor(runs: List<SavedSimulationRun>, dataId: String?): RecentRuns {
    if (dataId == null) return RecentRuns(runs, emptyList())
    val (current, stale) = runs.partition { it.dataId == dataId }
    return RecentRuns(current, stale)
}

/**
 * [runs] as the text the page stores. Each run's report is written under its wire names ([SIM]), so it reads back
 * through [parseSimulationReport] as a run's response does. Pure, covered under `jsNodeTest`.
 */
fun encodeSavedRuns(runs: List<SavedSimulationRun>): String = runs.map { saved ->
    val r = saved.run
    linkedMapOf<String, Any?>(
        savedSimulation to saved.simulation,
        savedDataId to saved.dataId,
        savedRanAt to saved.ranAt,
        SIM.clients to r.clients,
        SIM.users to r.users.map { u ->
            linkedMapOf<String, Any?>(
                SIM.email to u.email, SIM.client to u.client, SIM.level to u.level, SIM.persona to u.persona,
                SIM.purpose to u.purpose, SIM.capabilities to u.capabilities,
            )
        },
        SIM.startPage to r.startPage,
        SIM.summary to r.summary,
    )
}.toJsonStr(compact = true)

/**
 * The stored text as runs; none when there is no text or it does not parse -- a store written by another version of
 * the page is forgotten rather than misread -- and an entry without its simulation or time is left out. Pure,
 * covered under `jsNodeTest`.
 */
fun decodeSavedRuns(text: String?): List<SavedSimulationRun> {
    val list = text?.jsonResult()?.valueOrNull() ?: return emptyList()
    return list.toJsonListOfMaps().mapNotNull { m ->
        SavedSimulationRun(
            simulation = m[savedSimulation].toOptStr() ?: return@mapNotNull null,
            dataId = m[savedDataId].toOptStr(),
            ranAt = m[savedRanAt].toOptStr() ?: return@mapNotNull null,
            run = parseSimulationReport(m),
        )
    }
}

/**
 * How a remembered run is headed: the simulation, the clients it provisioned, and when it ran -- `report-demo · acme,
 * globex · 2026-10-09 14:05 UTC`. Pure, covered under `jsNodeTest`.
 */
fun recentRunHeading(saved: SavedSimulationRun): String =
    listOf(saved.simulation, saved.run.clients.joinToString(", ").ifEmpty { null }, formatTimestamp(saved.ranAt))
        .filterNotNull().joinToString(" · ")
