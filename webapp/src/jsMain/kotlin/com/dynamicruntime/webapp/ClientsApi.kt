package com.dynamicruntime.webapp

import com.dynamicruntime.common.endpoint.EI
import com.dynamicruntime.common.endpoint.EP
import com.dynamicruntime.common.gedra.ACEP
import com.dynamicruntime.common.gedra.CCT
import com.dynamicruntime.common.gedra.CFEP
import com.dynamicruntime.common.gedra.CLD
import com.dynamicruntime.common.gedra.COV
import com.dynamicruntime.common.gedra.CPY
import com.dynamicruntime.common.gedra.ClientOperatorFields
import com.dynamicruntime.common.gedra.GCI
import com.dynamicruntime.common.gedra.UF
import com.dynamicruntime.common.gedra.ClientStatus
import com.dynamicruntime.common.gedra.GedraConfigOrigin
import com.dynamicruntime.common.cfact.CFACT
import com.dynamicruntime.common.home.HFLD
import com.dynamicruntime.common.home.HMENU
import com.dynamicruntime.common.uiblock.UIB
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
    /** How much of its copy and interface the client's own configuration changes (issue #917). */
    val copyOverrides: Int,
    val blockOverrides: Int,
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
        copyOverrides = int(CLD.copyOverrides),
        blockOverrides = int(CLD.blockOverrides),
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

/**
 * How much a client customizes, as the listing's Customized column says it (issue #917): "3 copy, 2 menu", either
 * half alone, or a dash for none. "Menu" for the interface changes because every block a client can overlay today
 * is one. Pure, and covered under `jsNodeTest`.
 */
fun customizedText(copy: Int, blocks: Int): String = listOfNotNull(
    "$copy copy".takeIf { copy > 0 },
    "$blocks menu".takeIf { blocks > 0 },
).joinToString(", ").ifEmpty { "\u2014" }

/** One piece of copy a client's own configuration sets (issue #917): a row of `/clientAdmin/client/overrides`' `copy`. */
class CopyOverrideView(
    val fileId: String,
    val namespace: String,
    val key: String,
    val audience: String,
    /** What everybody else reads; null when nothing else sets the key. */
    val baseValue: String?,
    val value: String?,
    val configName: String?,
    val origin: String,
    /** The client's source value a stored config overrides, when one does. */
    val sourceValue: String?,
    val orphan: Boolean,
)

/** One field of an interface item a client sets (issue #917). Values are text as the endpoint rendered them. */
class BlockFieldView(val field: String, val baseValue: String?, val value: String?, val configName: String?, val origin: String)

/** One interface item or object a client's own configuration changes (issue #917): a row of the endpoint's `blocks`. */
class BlockOverrideView(
    val blockId: String,
    val path: String,
    /** The item's key within its list; null for an object outside one, or an item added with no key. */
    val itemId: String?,
    val added: Boolean,
    val hidden: Boolean,
    /** The item's label in the block everybody else gets, when it has one. */
    val baseLabel: String?,
    val fields: List<BlockFieldView>,
)

/** What one client's own configuration changes (issue #917): the endpoint's item, parsed. */
class ClientOverridesView(val clientId: String, val copy: List<CopyOverrideView>, val blocks: List<BlockOverrideView>)

/**
 * The overrides item as a [ClientOverridesView]. A copy row without its three-part address, or a block row without a
 * block, is not an override. Pure, and covered under `jsNodeTest`.
 */
fun parseClientOverrides(item: Map<String, Any?>): ClientOverridesView = ClientOverridesView(
    clientId = item[COV.client].toOptStr().orEmpty(),
    copy = item[COV.copy].toJsonListOfMaps().mapNotNull { row ->
        CopyOverrideView(
            fileId = row[COV.fileId].toOptStr() ?: return@mapNotNull null,
            namespace = row[COV.namespaceField].toOptStr() ?: return@mapNotNull null,
            key = row[COV.key].toOptStr() ?: return@mapNotNull null,
            audience = row[COV.audience].toOptStr().orEmpty(),
            baseValue = row[COV.baseValue].toOptStr(),
            value = row[COV.value].toOptStr(),
            configName = row[COV.configName].toOptStr(),
            origin = row[COV.origin].toOptStr().orEmpty(),
            sourceValue = row[COV.sourceValue].toOptStr(),
            orphan = row[COV.orphan] == true,
        )
    },
    blocks = item[COV.blocks].toJsonListOfMaps().mapNotNull { row ->
        BlockOverrideView(
            blockId = row[COV.blockId].toOptStr() ?: return@mapNotNull null,
            path = row[COV.path].toOptStr().orEmpty(),
            itemId = row[COV.itemId].toOptStr(),
            added = row[COV.added] == true,
            hidden = row[COV.hidden] == true,
            baseLabel = row[COV.baseLabel].toOptStr(),
            fields = row[COV.fields].toJsonListOfMaps().mapNotNull { f ->
                BlockFieldView(
                    field = f[COV.field].toOptStr() ?: return@mapNotNull null,
                    baseValue = f[COV.baseValue].toOptStr(),
                    value = f[COV.value].toOptStr(),
                    configName = f[COV.configName].toOptStr(),
                    origin = f[COV.origin].toOptStr().orEmpty(),
                )
            },
        )
    },
)

/** A copy row's address as the tables show it: `file: namespace.key`. */
fun copyKeyText(row: CopyOverrideView): String = "${row.fileId}: ${row.namespace}.${row.key}"

/**
 * What an interface row is within its block: the item's key, "(new item)" for one added with no key, the path of
 * an object outside a list, or "(block)" for the block's own fields. Pure, and covered under `jsNodeTest`.
 */
fun blockItemName(row: BlockOverrideView): String = when {
    row.itemId != null -> row.itemId
    row.added -> "(new item)"
    row.path.isEmpty() -> "(block)"
    else -> row.path
}

/** An interface row's address: the block and [blockItemName] within it. */
fun blockItemText(row: BlockOverrideView): String = "${row.blockId}: ${blockItemName(row)}"

/** The row's own `label` field, when the client set one. */
private fun labelField(row: BlockOverrideView): BlockFieldView? = row.fields.firstOrNull { it.field == HFLD.label }

/**
 * What the client did to an interface item, in a word each (issue #917): added; hidden; shown (a condition that was
 * `#never` and no longer is); "condition changed" for any other condition the client set; renamed (a label);
 * reordered (a display order); and "changed" for anything else -- joined when several apply, so a rename beside a
 * hide reads "hidden, renamed". Pure, and covered under `jsNodeTest`.
 */
fun menuChangeText(row: BlockOverrideView): String {
    val fields = row.fields.associateBy { it.field }
    val condition = fields[UIB.cfactExpression]
    val words = buildList {
        if (row.added) add("added")
        if (row.hidden) add("hidden")
        if (!row.added && !row.hidden && condition != null) {
            add(if (condition.baseValue == CFACT.neverName && condition.value != CFACT.neverName) "shown" else "condition changed")
        }
        if (!row.added && HFLD.label in fields) add("renamed")
        if (!row.added && UIB.displayOrder in fields) add("reordered")
        if (isEmpty() && fields.isNotEmpty()) add("changed")
    }
    return words.joinToString(", ")
}

/**
 * The value an interface row shows for the client: its label when it set one; nothing for a row that only hides or
 * shows the item (the change column says it, and the condition is not a value anybody reads); else the one field's
 * value, else the fields as `name: value`. Pure, and covered under `jsNodeTest`.
 */
fun blockValueText(row: BlockOverrideView): String {
    labelField(row)?.let { return it.value.orEmpty() }
    val words = menuChangeText(row)
    if (words == "hidden" || words == "shown") return ""
    val single = row.fields.singleOrNull()
    if (single != null) return single.value.orEmpty()
    return row.fields.joinToString(", ") { "${it.field}: ${it.value.orEmpty()}" }
}

/**
 * An interface row in a phrase, for a list that has no columns (issue #917): what the client did, and the label when
 * it set one -- "renamed: Acme overview", "added: Site audits", "hidden". Pure, and covered under `jsNodeTest`.
 */
fun blockSummaryText(row: BlockOverrideView): String {
    val words = menuChangeText(row)
    val label = labelField(row)?.value ?: return words
    return "$words: $label"
}

/** Which config set a value, and where that config lives: "acmeClient (source)". Pure, and covered under `jsNodeTest`. */
fun setByText(configName: String?, origin: String): String = when {
    configName == null -> origin
    origin.isEmpty() -> configName
    else -> "$configName ($origin)"
}

/**
 * The configs that set an interface row's fields, each once, a stored one first -- it is applied last, so it is the
 * one whose values win where the two set the same field. A row set by one config reads as that config; one set by
 * a source and a stored config names both. Pure, and covered under `jsNodeTest`.
 */
fun blockSetByText(row: BlockOverrideView): String = row.fields
    .sortedByDescending { it.origin == GedraConfigOrigin.stored.name }
    .map { setByText(it.configName, it.origin) }.distinct().joinToString(", ")

/** One key as the cross-client view lists it (issue #917): who overrides it, and with what. */
class KeyAcrossClients(val group: String, val key: String, val clients: List<Pair<String, String>>)

/**
 * Every overridden key across [byClient], grouped by file or block then key, each with the clients overriding it
 * and their values, in the order the clients were given (issue #917). Copy keys are grouped by file; interface
 * items by block. Pure, and covered under `jsNodeTest`.
 */
fun overridesAcrossClients(byClient: List<Pair<String, ClientOverridesView>>): List<KeyAcrossClients> {
    val keys = LinkedHashMap<Pair<String, String>, MutableList<Pair<String, String>>>()
    for ((clientId, view) in byClient) {
        for (row in view.copy) keys.getOrPut(row.fileId to "${row.namespace}.${row.key}") { mutableListOf() }.add(clientId to row.value.orEmpty())
        for (row in view.blocks) keys.getOrPut(row.blockId to blockItemName(row)) { mutableListOf() }.add(clientId to blockSummaryText(row))
    }
    return keys.entries.sortedWith(compareBy({ it.key.first }, { it.key.second })).map { KeyAcrossClients(it.key.first, it.key.second, it.value) }
}

/** Where the Customized column leads (issue #917): the client's detail, where the Copy & menu section is. */
fun clientOverridesHref(clientId: String): String = hashHref(listOf(HP.page to HMENU.pageClients, HP.client to clientId))

/** The cross-client view of the overrides (issue #917), for an administrator who sees across clients. */
fun overridesAcrossHref(): String = hashHref(listOf(HP.page to HMENU.pageClients, HP.overrides to "1"))

/** One key an administrator may override for a client (issue #918): a row of `/clientAdmin/client/copy/keys`. */
class CopyKeyView(val fileId: String, val namespace: String, val key: String, val audience: String, val value: String)

/** The keys listing's items as [CopyKeyView]s; one without its address is not a key. Pure, and covered under `jsNodeTest`. */
fun parseCopyKeys(items: List<Map<String, Any?>>): List<CopyKeyView> = items.mapNotNull { row ->
    CopyKeyView(
        fileId = row[COV.fileId].toOptStr() ?: return@mapNotNull null,
        namespace = row[COV.namespaceField].toOptStr() ?: return@mapNotNull null,
        key = row[COV.key].toOptStr() ?: return@mapNotNull null,
        audience = row[COV.audience].toOptStr().orEmpty(),
        value = row[COV.value].toOptStr().orEmpty(),
    )
}

/** What a set or reset did (issue #918): where it landed, and what the client now reads. */
class CopyEditResult(val configName: String, val value: String?, val stored: Boolean, val issues: List<String>)

/** The set/reset result as a [CopyEditResult]. Pure, and covered under `jsNodeTest`. */
fun parseCopyEditResult(results: Map<String, Any?>): CopyEditResult = CopyEditResult(
    configName = results[COV.configName].toOptStr().orEmpty(),
    value = results[COV.value].toOptStr(),
    stored = results[CPY.stored] == true,
    issues = results[CPY.issues].toJsonListOfMaps().mapNotNull { it[GCI.message].toOptStr() },
)

/**
 * The request that sets or resets one key for a client (issue #918): its address, the client, and -- for a set -- the
 * value. Pure, and covered under `jsNodeTest`.
 */
fun copyEditRequest(clientId: String, fileId: String, namespace: String, key: String, value: String?): Map<String, Any?> =
    linkedMapOf<String, Any?>(COV.client to clientId, COV.fileId to fileId, COV.namespaceField to namespace, COV.key to key)
        .also { if (value != null) it[COV.value] = value }

/**
 * Whether a copy row offers a reset (issue #918): only a **stored** value is data's to remove. A row set in source
 * shows its value and can be overridden, and once overridden the stored value resets to the source one -- the
 * report's `sourceValue`. Pure, and covered under `jsNodeTest`.
 */
fun copyRowResettable(row: CopyOverrideView): Boolean = row.origin == GedraConfigOrigin.stored.name

/**
 * The keys not yet overridden for the client, as an "add an override" picker offers them (issue #918): every
 * shipped key minus the ones the client already sets, grouped by file then namespace in listing order. Pure, and
 * covered under `jsNodeTest`.
 */
fun addableCopyKeys(keys: List<CopyKeyView>, overridden: List<CopyOverrideView>): List<CopyKeyView> {
    val taken = overridden.map { Triple(it.fileId, it.namespace, it.key) }.toSet()
    return keys.filterNot { Triple(it.fileId, it.namespace, it.key) in taken }
}

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

    /** Every key an administrator may override for a client, with the client's value (issue #918). */
    suspend fun copyKeys(clientId: String): List<CopyKeyView> =
        parseCopyKeys(Http.getApi(CPY.keysPath + queryString(mapOf(COV.client to clientId)))[EP.items].toJsonListOfMaps())

    /** Sets one key's value for a client and makes it live (issue #918); the backend refuses a value its trial faults. */
    suspend fun setCopy(clientId: String, fileId: String, namespace: String, key: String, value: String): CopyEditResult =
        parseCopyEditResult(Http.sendApi("POST", CPY.setPath, copyEditRequest(clientId, fileId, namespace, key, value))[EP.results].toJsonMapOrEmpty())

    /** Removes a client's stored value for one key and makes that live (issue #918). */
    suspend fun resetCopy(clientId: String, fileId: String, namespace: String, key: String): CopyEditResult =
        parseCopyEditResult(Http.sendApi("POST", CPY.resetPath, copyEditRequest(clientId, fileId, namespace, key, null))[EP.results].toJsonMapOrEmpty())

    /** What one client's own configuration changes (issue #917), through the scoped retrieve. */
    suspend fun overrides(clientId: String): ClientOverridesView =
        parseClientOverrides(Http.getApi(UADEP.clientOverrides + queryString(mapOf(COV.client to clientId)))[EP.item].toJsonMapOrEmpty())

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
