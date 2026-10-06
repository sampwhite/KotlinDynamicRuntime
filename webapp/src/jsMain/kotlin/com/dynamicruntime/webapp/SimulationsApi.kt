package com.dynamicruntime.webapp

import com.dynamicruntime.common.endpoint.EP
import com.dynamicruntime.common.test.SIM
import com.dynamicruntime.common.test.TEP
import com.dynamicruntime.common.util.toJsonListOfMaps
import com.dynamicruntime.common.util.toJsonListOfStrings
import com.dynamicruntime.common.util.toJsonMapOrEmpty
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
