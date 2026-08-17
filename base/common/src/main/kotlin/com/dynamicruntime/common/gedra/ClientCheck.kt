package com.dynamicruntime.common.gedra

import com.dynamicruntime.common.context.ENV
import com.dynamicruntime.common.context.KdrCxt
import com.dynamicruntime.common.exception.KdrException
import com.dynamicruntime.common.logging.LogStartup

/** What checking the declared clients produced (issue #343). */
class ClientCheckResult(
    /** The clients that survived, keyed by [ClientDef.clientId], in contribution order. */
    val clients: Map<String, ClientDef>,
    /** Problems found, in the order they were found. Empty unless something degraded. */
    val issues: List<GedraConfigIssue>,
)

/**
 * Checks the clients the contributed configs declare, and drops the ones that do not hold up (issue #343).
 *
 * Run **after every config has arrived**, unlike `GedraConfigCollector.add`, which checks each config as it
 * comes. Two of the checks here cannot be answered any earlier: whether an extended client exists, and
 * whether an included trait is one this client can see. Rather than split the rules across two moments by how
 * early each happens to be decidable, all of them run here, which also means one place to read and one thing
 * that happens to a failure. Contribution order is preserved, so "first declaration wins" still falls out of
 * arrival order rather than being imposed.
 *
 * What a problem does is [gedraConfigCheckMode]'s answer -- strict everywhere, degrading in production -- the
 * paradigm #296 established and #299 applied to configs. **The degradation is dropping the client, not the
 * config**, which is more proportionate than it first looks and is not a new rule: a client that is not there
 * is a state the design already defines, and everything scoped by it then behaves as though it were absent.
 * The bundle's traits stay declared; nothing can reach them, because reaching them goes through the client.
 */
fun checkClientDefs(cxt: KdrCxt, configs: GedraConfigCollector): ClientCheckResult {
    val mode = gedraConfigCheckMode(cxt)
    val declared = configs.configs.mapNotNull { config -> config.client?.let { config to it } }
    if (mode == GCFG.off) {
        return ClientCheckResult(declared.associate { (_, def) -> def.clientId to def }, emptyList())
    }
    val issues = mutableListOf<GedraConfigIssue>()
    val kept = LinkedHashMap<String, ClientDef>()

    // Pass one: what a definition can be judged on by itself, plus whether one client is declared twice.
    for ((config, def) in declared) {
        val problem = ownProblem(config, def, kept)
        if (problem == null) {
            kept[def.clientId] = def
        } else {
            report(cxt, mode, problem, issues)
        }
    }

    // Pass two: what needs every other client, and every trait, to be present. Checked against the survivors
    // of pass one, so a client extending one that was just dropped is itself dropped rather than left holding
    // a reference to nothing.
    for (def in kept.values.toList()) {
        val problem = relatedProblem(def, kept, configs)
        if (problem != null) {
            kept.remove(def.clientId)
            report(cxt, mode, problem, issues)
        }
    }
    return ClientCheckResult(kept, issues)
}

private fun report(cxt: KdrCxt, mode: String, problem: GedraConfigIssue, issues: MutableList<GedraConfigIssue>) {
    if (mode == GCFG.strict) {
        throw KdrException(
            "${problem.message} Fix it, or set ${GCFG.checkEnvVar}=${GCFG.warn} to start anyway " +
                "(which is the default in ${ENV.prod}).",
        )
    }
    // At error rather than warn, as the config checks log theirs: this is not a caveat, it is a client the
    // deployment believes it carries and does not.
    LogStartup.error(cxt, "${problem.message} ${problem.degradedTo}")
    issues.add(problem)
}

/** The first thing wrong with [def] judged on its own, or null. */
private fun ownProblem(config: GedraConfig, def: ClientDef, kept: Map<String, ClientDef>): GedraConfigIssue? {
    // The charset first, so an unusable name is reported as itself rather than as a disagreement with the id
    // it failed to match. `GedraId` already holds the config's own client to this rule; the declared one is a
    // separate string and has to be held to it separately -- which is the whole point of it being declared.
    idFault(def.clientId)?.let { fault ->
        return GedraConfigIssue(
            "Client id '${def.clientId}' declared by '${config.gedraId}' $fault. A client id is embedded in " +
                "every gedra id the client owns and is addressed from code, so it may hold only ASCII " +
                "letters, digits and underscores, and may not start with a digit.",
            "Dropping the client; '${config.gedraId}''s definitions stay declared and unreachable.",
        )
    }
    if (def.clientId != config.gedraId.client) {
        return GedraConfigIssue(
            "Config '${config.gedraId}' declares the client '${def.clientId}' but is itself filed under " +
                "'${config.gedraId.client}'. A config's id is what binds a definition to a client, so the " +
                "two saying different things leaves no answer to which client this is.",
            "Dropping the client '${def.clientId}'.",
        )
    }
    kept[def.clientId]?.let {
        return GedraConfigIssue(
            "Client '${def.clientId}' is defined twice; '${config.gedraId}' is the second. Every config " +
                "sharing a client is read as one whole, and exactly one of them declares what the client is.",
            "Keeping the first definition; '${config.gedraId}''s is dropped.",
        )
    }
    envProblem(config, def)?.let { return it }
    // Both conditions, and each does real work. What makes a functional group dangerous is that we ship a
    // global trait and it becomes editable in a client that never reviewed it -- which is a statement about
    // two parties, not about production. A customer's dev client tracking new global traits is how they
    // preview what is coming; an internal production client has no second party to surprise.
    if (def.audience == ClientAudience.customer && def.usageType == ClientUsageType.production) {
        val groups = def.includedGroups
        if (groups.isNotEmpty()) {
            return GedraConfigIssue(
                "Client '${def.clientId}' is a ${ClientAudience.customer} client in " +
                    "${ClientUsageType.production} and may not include the functional group(s) " +
                    "${groups.joinToString(", ")}. Its supported set must be fully determined by its own " +
                    "definition, because somebody other than us depends on it: a group computed at load " +
                    "time makes a trait we ship editable in a client that never reviewed it.",
                "Dropping the client '${def.clientId}'.",
            )
        }
    }
    return null
}

/** What is wrong with [ClientDef.enabledEnvironments], or null. */
private fun envProblem(config: GedraConfig, def: ClientDef): GedraConfigIssue? {
    if (def.enabledEnvironments.isEmpty()) {
        return GedraConfigIssue(
            "Client '${def.clientId}' (from '${config.gedraId}') names no environments. A client enabled " +
                "nowhere can be referred to and never used, which is a state to arrive at by disabling one, " +
                "not to declare.",
            "Dropping the client '${def.clientId}'.",
        )
    }
    val unknown = def.enabledEnvironments.filterNot { it in ENV.names }.sorted()
    if (unknown.isNotEmpty()) {
        return GedraConfigIssue(
            "Client '${def.clientId}' names ${unknown.joinToString(", ") { "'$it'" }}, which " +
                "${if (unknown.size == 1) "is not an environment" else "are not environments"}. " +
                "The environments that exist are ${ENV.names.joinToString(", ")}.",
            "Dropping the client '${def.clientId}'.",
        )
    }
    // Naming anything past the developer's own two requires naming both of those as well. The reason belongs
    // with the rule: a client in active use must never be one that local development and the unit tests are
    // locked out of.
    val beyond = def.enabledEnvironments.filterNot { it == ENV.unit || it == ENV.local }.sorted()
    val missing = listOf(ENV.unit, ENV.local).filterNot { it in def.enabledEnvironments }
    if (beyond.isNotEmpty() && missing.isNotEmpty()) {
        return GedraConfigIssue(
            "Client '${def.clientId}' is enabled in ${beyond.joinToString(", ")} but not in " +
                "${missing.joinToString(" or ")}. A client in active use must never be one that local " +
                "development and the unit tests are locked out of.",
            "Dropping the client '${def.clientId}'.",
        )
    }
    return null
}

/** The first thing wrong with [def] in relation to the other clients and the declared traits, or null. */
private fun relatedProblem(
    def: ClientDef,
    kept: Map<String, ClientDef>,
    configs: GedraConfigCollector,
): GedraConfigIssue? {
    def.extendsFromClientId?.let { parentId ->
        val parent = kept[parentId]
            ?: return GedraConfigIssue(
                "Client '${def.clientId}' extends '$parentId', which this deployment does not define.",
                "Dropping the client '${def.clientId}'.",
            )
        parent.extendsFromClientId?.let { grandparentId ->
            return GedraConfigIssue(
                "Client '${def.clientId}' extends '$parentId', which itself extends '$grandparentId'. " +
                    "Extension is one level: a client is built on a base, and that base is not built on " +
                    "another. (A client naming itself lands here too, being its own parent.)",
                "Dropping the client '${def.clientId}'.",
            )
        }
    }
    val visible = configs.traitsFor(def.clientId).map { it.traitId }.toSet()
    val unknown = def.includedTraitIds.filterNot { it in visible }
    if (unknown.isNotEmpty()) {
        return GedraConfigIssue(
            "Client '${def.clientId}' includes ${unknown.joinToString(", ") { "'$it'" }}, which " +
                "${if (unknown.size == 1) "is not a trait" else "are not traits"} it can see. A client sees " +
                "its own traits and global's, and nobody else's -- so this is a typo, a trait that was " +
                "never declared, or somebody else's.",
            "Dropping the client '${def.clientId}'.",
        )
    }
    val badGroups = def.includedGroups.filterNot { it == CLD.allGlobal }
    if (badGroups.isNotEmpty()) {
        return GedraConfigIssue(
            "Client '${def.clientId}' includes the group(s) ${badGroups.joinToString(", ")}, which do not " +
                "exist. The groups that exist are ${CLD.allGlobal}.",
            "Dropping the client '${def.clientId}'.",
        )
    }
    return null
}

/**
 * What is wrong with [clientId] as a gedra id segment, as a phrase completing "the id ...", or null.
 *
 * Spelled out rather than using `isLetterOrDigit()`, which is Unicode-aware, for the reason `GedraId` gives:
 * a client id that admitted Cyrillic lookalikes would be a way to declare two clients that read identically
 * to a person and differ to every map keyed by one.
 */
private fun idFault(clientId: String): String? {
    if (clientId.isEmpty()) {
        return "is empty"
    }
    val first = clientId[0]
    if (!(first in 'a'..'z' || first in 'A'..'Z' || first == '_')) {
        return "starts with '$first'"
    }
    val bad = clientId.firstOrNull {
        !(it in 'a'..'z' || it in 'A'..'Z' || it in '0'..'9' || it == '_')
    }
    return if (bad == null) null else "holds '$bad'"
}
