package com.dynamicruntime.webapp

import com.dynamicruntime.common.endpoint.EI
import com.dynamicruntime.common.endpoint.EP
import com.dynamicruntime.common.gedra.ACEP
import com.dynamicruntime.common.gedra.CCT
import com.dynamicruntime.common.gedra.CFEP
import com.dynamicruntime.common.gedra.CLD
import com.dynamicruntime.common.gedra.ClientOperatorFields
import com.dynamicruntime.common.gedra.GCI
import com.dynamicruntime.common.gedra.UF
import com.dynamicruntime.common.gedra.ClientStatus
import com.dynamicruntime.common.gedra.GedraConfigOrigin
import com.dynamicruntime.common.home.HMENU
import com.dynamicruntime.common.user.UADEP
import com.dynamicruntime.common.user.USF
import com.dynamicruntime.common.util.humanizeFieldName
import com.dynamicruntime.common.util.toJsonListOfMaps
import com.dynamicruntime.common.util.toJsonListOfStrings
import com.dynamicruntime.common.util.toJsonMapOrEmpty
import com.dynamicruntime.common.util.toOptStr

/**
 * One client as the Clients page lists it (issue #905): a row of `/clientAdmin/clients/overview`. [status] and
 * [origin] are the wire names (`ClientStatus`, `GedraConfigOrigin`), kept as strings so a value this build does not
 * know still lists rather than dropping the row. [issues] are the messages of what the checks forgave -- or, for a
 * dropped client, why -- so the listing can say it rather than only count it.
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
    val issues: List<String>,
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
        issues = row[CLD.issues].toJsonListOfMaps().mapNotNull { it[GCI.message].toOptStr() },
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

/**
 * Where a client's Forms count leads (issue #905): My forms with that client chosen, for an administrator who sees
 * across clients -- the listing's `client` is its chosen-client selector (#714). A client-scoped administrator's
 * listing is their own client already, so their link carries nothing: the page would drop the key anyway
 * (`formsInitialSearch`). Pure, and covered under `jsNodeTest`.
 */
fun clientFormsHref(clientId: String, acrossClients: Boolean): String =
    hashHref(listOf(HP.page to HMENU.pageForms) + if (acrossClients) listOf(EI.client to clientId) else emptyList())

/**
 * Where a client's Users count leads (issue #905): the Users page filtered to that client, for an administrator who
 * sees across clients -- `client` is an allClients-only filter there, and the page draws no control for it to
 * anyone else, so a scoped administrator's link carries nothing rather than a filter they could not clear. Pure,
 * and covered under `jsNodeTest`.
 */
fun clientUsersHref(clientId: String, acrossClients: Boolean): String =
    hashHref(listOf(HP.page to HMENU.pageUsers) + if (acrossClients) listOf(USF.client to clientId) else emptyList())

/** One configuration issue as the detail lists it (issue #906): what is wrong, what was dropped, and where it came from. */
class ConfigIssueView(val message: String, val degradedTo: String, val origin: String)

/**
 * One client's definition as the detail view shows it (issue #906): its attributes as `ClientInfo` writes them
 * ([info], read by the `CLD` keys), whether this node carries it, its issues, and what it defines -- supported
 * trait ids, listing-column labels and workflow ids.
 */
class ClientDefinitionView(
    val info: Map<String, Any?>,
    val present: Boolean,
    val issues: List<ConfigIssueView>,
    val traitIds: List<String>,
    val usageLabels: List<String>,
    val workflowIds: List<String>,
)

/** One stored configuration as the detail lists it (issue #906): a row of the config bundles listing. */
class ConfigSummaryView(
    val name: String,
    val version: Int,
    val published: Boolean,
    val publishedAt: String?,
    val updatedAt: String?,
    val issueCount: Int,
)

/** The issues of a definition or a stored configuration, as the wire carries them. */
private fun parseConfigIssues(raw: Any?): List<ConfigIssueView> = raw.toJsonListOfMaps().mapNotNull { issue ->
    val message = issue[GCI.message].toOptStr() ?: return@mapNotNull null
    ConfigIssueView(message, issue[GCI.degradedTo].toOptStr().orEmpty(), issue[GCI.origin].toOptStr().orEmpty())
}

/** The definition item as a [ClientDefinitionView]. Pure, and covered under `jsNodeTest`. */
fun parseClientDefinition(item: Map<String, Any?>): ClientDefinitionView = ClientDefinitionView(
    info = item[CLD.client].toJsonMapOrEmpty(),
    present = item[CLD.present] == true,
    issues = parseConfigIssues(item[CLD.issues]),
    traitIds = item[CLD.traits].toJsonListOfMaps().mapNotNull { it[CCT.traitId].toOptStr() },
    usageLabels = item[CLD.usages].toJsonListOfMaps().mapNotNull { it[UF.label].toOptStr() },
    workflowIds = item[CLD.workflows].toJsonListOfStrings(),
)

/** The config bundles listing's items as [ConfigSummaryView]s; one without a name is not a configuration. Pure, and covered under `jsNodeTest`. */
fun parseConfigSummaries(items: List<Map<String, Any?>>): List<ConfigSummaryView> = items.mapNotNull { row ->
    ConfigSummaryView(
        name = row[CFEP.name].toOptStr() ?: return@mapNotNull null,
        version = (row[CFEP.version] as? Number)?.toInt() ?: 0,
        published = row[CFEP.published] == true,
        publishedAt = row[CFEP.publishedAt].toOptStr(),
        updatedAt = row[CFEP.updatedAt].toOptStr(),
        issueCount = row[CFEP.issues].toJsonListOfMaps().size,
    )
}

/** The note beside an operator-only attribute (issue #820) for an administrator who may not change it. */
const val platformSetNote = "(set by the platform)"

/**
 * The read-only summary of a client (issue #906): its attributes in the order an administrator reads them, then
 * what it defines. From the overview [row] when the listing has one (status, origin, counts) and the definition
 * [def] when it could be read; either may be missing -- a client this node does not carry has no definition to
 * retrieve, and a deep link arrives before the listing -- so [clientId] is the hash's, which is always there. The
 * operator-only attributes (`ClientOperatorFields`) are noted as the platform's when the caller may not change
 * them, so the later editor disables exactly those. A list that is empty reads as a dash. Pure, and covered under
 * `jsNodeTest`.
 */
fun clientSummaryRows(clientId: String, row: ClientOverview?, def: ClientDefinitionView?, canSeeAllClients: Boolean): List<Pair<String, String>> {
    val info = def?.info.orEmpty()
    fun text(key: String): String = info[key].toOptStr()?.ifBlank { null } ?: "\u2014"
    fun list(items: List<String>): String = if (items.isEmpty()) "\u2014" else items.joinToString(", ")
    fun operatorField(key: String): String {
        val value = text(key)
        return if (canSeeAllClients || value == "\u2014") value else "$value $platformSetNote"
    }
    val name = row?.name?.ifBlank { null } ?: info[CLD.name].toOptStr().orEmpty()
    return buildList {
        add("Client id" to clientId)
        add("Name" to name.ifBlank { "\u2014" })
        add("Description" to text(CLD.description))
        row?.let {
            add("Load" to clientLoadText(it.status, it.issues.size))
            add("Definition" to clientOriginText(it.origin, it.storedConfigs))
        }
        if (def != null) {
            // The two the platform sets (#820): a client's own administrator sees them, marked, and may not change them.
            for (key in ClientOperatorFields.defaults.keys) add(humanizeFieldName(key) to operatorField(key))
            add("Enabled in" to list(info[CLD.enabledEnvironments].toJsonListOfStrings()))
            add("Extends" to text(CLD.extendsFromClientId))
            add("Domain" to (info[CLD.customDomain].toOptStr() ?: info[CLD.domainPrefix].toOptStr()?.let { "$it (prefix)" } ?: "\u2014"))
            add("Web resources" to text(CLD.webResourcesId))
            add("Static config" to if (info[CLD.staticConfig] == true) "Yes" else "No")
            add("User labels" to list(info[CLD.userLabels].toJsonListOfStrings()))
            add("Traits" to list(def.traitIds))
            add("Listing columns" to list(def.usageLabels))
            add("Workflows" to list(def.workflowIds))
        }
        row?.let {
            add("Forms" to it.forms.toString())
            add("Users" to userCountText(it.users, it.unclaimedUsers))
        }
    }
}

/** The clients endpoints (issue #905): the scoped surface, which both kinds of administrator reach. */
object ClientsApi {
    /** The clients this administrator oversees, as the overview lists them. */
    suspend fun listOverview(): List<ClientOverview> =
        parseClientOverview(Http.getApi(UADEP.clientsOverview)[EP.items].toJsonListOfMaps())

    /** One client's definition (issue #906), through the scoped retrieve: the caller's own, or one they may name. */
    suspend fun definition(clientId: String): ClientDefinitionView =
        parseClientDefinition(Http.getApi(UADEP.clientDefinition + queryString(mapOf(CLD.client to clientId)))[EP.item].toJsonMapOrEmpty())

    /**
     * The stored configurations this node holds for a client (issue #906), from the listing [storedConfigsPath]
     * names -- or none, when there is no listing this caller may ask about that client.
     */
    suspend fun storedConfigs(clientId: String, acrossClients: Boolean, ownClient: String): List<ConfigSummaryView> {
        val path = storedConfigsPath(clientId, acrossClients, ownClient) ?: return emptyList()
        return parseConfigSummaries(Http.getApi(path)[EP.items].toJsonListOfMaps())
    }
}

/**
 * Which stored-configuration listing answers for [clientId] (issue #906): the full-scope one, naming the client,
 * for an administrator who sees across clients; the client-scoped one for the caller's **own** client -- it lists
 * that client whatever is asked, so it is asked only about it; and null for another client, whose page then shows
 * none rather than the caller's own under a foreign heading. Pure, and covered under `jsNodeTest`.
 */
fun storedConfigsPath(clientId: String, acrossClients: Boolean, ownClient: String): String? = when {
    acrossClients -> ACEP.bundles + queryString(mapOf(CFEP.client to clientId))
    clientId == ownClient -> CFEP.bundles
    else -> null
}
