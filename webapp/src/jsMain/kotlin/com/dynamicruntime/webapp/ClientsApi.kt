package com.dynamicruntime.webapp

import com.dynamicruntime.common.endpoint.EP
import com.dynamicruntime.common.gedra.CLD
import com.dynamicruntime.common.gedra.ClientStatus
import com.dynamicruntime.common.gedra.GedraConfigOrigin
import com.dynamicruntime.common.user.UADEP
import com.dynamicruntime.common.util.toJsonListOfMaps
import com.dynamicruntime.common.util.toJsonListOrEmpty
import com.dynamicruntime.common.util.toOptStr

/**
 * One client as the Clients page lists it (issue #905): a row of `/clientAdmin/clients/overview`. [status] and
 * [origin] are the wire names (`ClientStatus`, `GedraConfigOrigin`), kept as strings so a value this build does not
 * know still lists rather than dropping the row.
 */
class ClientOverview(
    val clientId: String,
    val name: String,
    val status: String,
    val origin: String,
    val storedConfigs: Int,
    val forms: Int,
    val users: Int,
    val unclaimedUsers: Int,
    val workflowCount: Int,
    val hasSurvey: Boolean,
    val issueCount: Int,
)

/** The overview endpoint's items as rows; one without a client id is not a client. Pure, and covered under `jsNodeTest`. */
fun parseClientOverview(items: List<Map<String, Any?>>): List<ClientOverview> = items.mapNotNull { row ->
    fun int(key: String): Int = (row[key] as? Number)?.toInt() ?: 0
    val clientId = row[CLD.clientId].toOptStr() ?: return@mapNotNull null
    ClientOverview(
        clientId = clientId,
        name = row[CLD.name].toOptStr().orEmpty(),
        status = row[CLD.status].toOptStr().orEmpty(),
        origin = row[CLD.origin].toOptStr().orEmpty(),
        storedConfigs = int(CLD.storedConfigs),
        forms = int(CLD.forms),
        users = int(CLD.users),
        unclaimedUsers = int(CLD.unclaimedUsers),
        workflowCount = int(CLD.workflowCount),
        hasSurvey = row[CLD.hasSurvey] == true,
        issueCount = row[CLD.issues].toJsonListOrEmpty().size,
    )
}

private fun issues(n: Int): String = if (n == 1) "1 issue" else "$n issues"

/**
 * How a client's load reads (issue #905): loaded, with any issues the checks forgave; or why it is not carried here.
 * An unknown status is shown as its name rather than hidden. Pure, and covered under `jsNodeTest`.
 */
fun clientLoadText(status: String, issueCount: Int): String = when (status) {
    ClientStatus.present.name -> if (issueCount == 0) "Loaded" else "Loaded, ${issues(issueCount)} forgiven"
    ClientStatus.notEnabled.name -> "Not enabled here"
    ClientStatus.dropped.name -> "Dropped by a check" + (if (issueCount == 0) "" else " \u2014 ${issues(issueCount)}")
    ClientStatus.storedOnly.name -> "Stored config only"
    else -> status
}

/**
 * Where a client's definition comes from, as the listing says it (issue #905): "Source", or "Source + 2 stored" when
 * stored overlays are loaded on it; "Stored", or "Stored (2 configs)" -- a stored definition counts its own config,
 * so it is never "+ N". Pure, and covered under `jsNodeTest`.
 */
fun clientOriginText(origin: String, storedConfigs: Int): String = when (origin) {
    GedraConfigOrigin.source.name -> if (storedConfigs == 0) "Source" else "Source + $storedConfigs stored"
    GedraConfigOrigin.stored.name -> if (storedConfigs <= 1) "Stored" else "Stored ($storedConfigs configs)"
    else -> origin
}

/** A client's active users with the unclaimed ones told apart: "12", or "12 (3 unclaimed)". Pure, and covered under `jsNodeTest`. */
fun userCountText(users: Int, unclaimed: Int): String = if (unclaimed == 0) "$users" else "$users ($unclaimed unclaimed)"

/** A client's workflows, noting a survey: "4", "4, survey". Pure, and covered under `jsNodeTest`. */
fun workflowsText(count: Int, hasSurvey: Boolean): String = if (hasSurvey) "$count, survey" else "$count"

/** The clients endpoints (issue #905): the scoped surface, which both kinds of administrator reach. */
object ClientsApi {
    /** The clients this administrator oversees, as the overview lists them. */
    suspend fun listOverview(): List<ClientOverview> =
        parseClientOverview(Http.getApi(UADEP.clientsOverview)[EP.items].toJsonListOfMaps())
}
