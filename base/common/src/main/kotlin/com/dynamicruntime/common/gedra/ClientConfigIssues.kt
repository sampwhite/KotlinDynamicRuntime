package com.dynamicruntime.common.gedra

import com.dynamicruntime.common.context.KdrCxt

/** Wire field names of a [GedraConfigIssue] as the client and config endpoints return it (issue #840). */
@Suppress("ConstPropertyName")
object GCI {
    const val message = "message"
    const val degradedTo = "degradedTo"
    const val client = "client"
    const val storedConfigId = "storedConfigId"
    const val elementKind = "elementKind"
    const val elementId = "elementId"

    /** `source` or `stored` -- a [GedraConfigOrigin] name. */
    const val origin = "origin"
}

/** The issue as its wire map: every field it knows, absent ones left out. */
fun GedraConfigIssue.toWireMap(): Map<String, Any?> = linkedMapOf<String, Any?>(
    GCI.message to message,
    GCI.degradedTo to degradedTo,
    GCI.client to client,
    GCI.storedConfigId to storedConfigId,
    GCI.elementKind to elementKind,
    GCI.elementId to elementId,
    GCI.origin to origin.name,
).filterValues { it != null }

/**
 * Each client's **configuration issues** (issue #840): the problems a check forgave in the client's definitions,
 * kept on the client so an administrator asking "why is this not working?" is answered by the client itself --
 * its definition, its summary, its stored configs, and a reload all return them -- rather than by a log line
 * nobody was watching.
 *
 * Filled by [reportConfigProblem], the one place a forgiven problem is turned into a consequence, so no check can
 * forget to record. Keyed by [GedraConfigIssue.client] -- the client holding the offending definition, which for
 * a component's own is `global` -- and it holds **every** forgiven client's issues, source and stored alike: a
 * source-code client forgiven in production is as much in need of explaining as a stored one.
 *
 * **Replaced per client on each load.** A boot starts empty; a reload of one client ([GedraConfigReload]) clears
 * that client's list first ([replace]) and restores it if the reload fails, so the list is always what the
 * client's *current* configuration produced. A finding re-reported while other clients are re-checked is taken
 * once ([record] ignores an issue it already holds), so whole-node rechecks do not repeat a client's list.
 */
class ClientConfigIssues {
    private val byClient = LinkedHashMap<String, List<GedraConfigIssue>>()

    /** Adds [issue] to its client's list, unless an identical one is there already. One with no client is not kept. */
    @Synchronized
    fun record(issue: GedraConfigIssue) {
        val client = issue.client ?: return
        val held = byClient[client].orEmpty()
        if (held.none { it.sameAs(issue) }) {
            byClient[client] = held + issue
        }
    }

    /** [client]'s issues, in the order they were found. */
    @Synchronized
    fun issuesFor(client: String): List<GedraConfigIssue> = byClient[client].orEmpty()

    /** The issues held by the stored config [storedConfigId] of [client]. */
    fun issuesForConfig(client: String, storedConfigId: String): List<GedraConfigIssue> =
        issuesFor(client).filter { it.storedConfigId == storedConfigId }

    /** Sets [client]'s list to [issues], returning what it held -- so a reload can clear it and restore on failure. */
    @Synchronized
    fun replace(client: String, issues: List<GedraConfigIssue>): List<GedraConfigIssue> {
        val prior = byClient[client].orEmpty()
        if (issues.isEmpty()) byClient.remove(client) else byClient[client] = issues
        return prior
    }

    private fun GedraConfigIssue.sameAs(other: GedraConfigIssue): Boolean =
        message == other.message && storedConfigId == other.storedConfigId &&
            elementKind == other.elementKind && elementId == other.elementId

    @Suppress("ConstPropertyName")
    companion object {
        /** Instance-config key under which the registry is published. */
        const val key = "ClientConfigIssues"

        /**
         * The registry for this instance, created on first use -- as `BootCheckRegistry` is, and for the same
         * reason: a config check can run before any startup service would have installed it.
         */
        @Synchronized
        fun get(cxt: KdrCxt): ClientConfigIssues {
            val existing = cxt.instanceConfig.get(key) as? ClientConfigIssues
            if (existing != null) {
                return existing
            }
            val created = ClientConfigIssues()
            cxt.instanceConfig.put(key, created)
            return created
        }
    }
}
