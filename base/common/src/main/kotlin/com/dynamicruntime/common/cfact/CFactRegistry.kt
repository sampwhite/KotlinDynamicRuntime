package com.dynamicruntime.common.cfact

import com.dynamicruntime.common.context.KdrCxt
import com.dynamicruntime.common.exception.KdrException

/**
 * What decides whether one cfact is present for a request (issue #455).
 *
 * Declared **beside** the [CFactDef] it answers for, which is the point of the pairing: the description
 * promises to say what makes the cfact true, and the code that makes it true is on the next line, where a
 * change to either is visible to whoever changes the other.
 *
 * A source answers about the **request** only. Facts about one *thing being rendered* are passed to
 * [CFactRegistry.assemble] instead, because they cannot be computed from the request -- which is also why
 * there is no per-request cache of the assembled set to be tempted by.
 */
fun interface CFactSource {
    /** Whether the cfact this was registered for is present for [cxt]. */
    fun isPresent(cxt: KdrCxt): Boolean
}

/**
 * The cfacts one scope knows about, and what makes each of them true (issue #455).
 *
 * There is one of these per client (plus the global one every client's is built from), because a client's own
 * config may add names. What it is *for* is [names]: a cfact expression parses against exactly this set, so a
 * name nobody declared is a refused parse rather than a condition that is quietly always false -- or, under a
 * negation, quietly always true.
 */
class CFactRegistry(
    /** Every cfact this scope knows, keyed by name, in declaration order. */
    val defs: Map<String, CFactDef>,
    /**
     * What decides each request-scoped cfact, keyed by name. A subset of [defs]: a name may be declared with
     * nothing yet computing it, which is how a client declares up front what its data will later refer to.
     */
    private val sources: Map<String, CFactSource>,
) {
    /** The names an expression in this scope may write -- what [CFactParser] validates against. */
    val names: Set<String> = defs.keys

    /** Parses [expression] against [names], reading absence as "always matches"; see [parseCFactOrAlways]. */
    fun parse(expression: String?): CFactPredicate = parseCFactOrAlways(expression, names)

    /**
     * The cfacts present for [cxt], optionally about one thing being rendered ([targetFacts]).
     *
     * **The set is per (request, target), never per request.** `loggedIn` is a fact about the request, but
     * "the caller may approve *this* task" is a fact about the request *relative to one row* -- so a set
     * computed once and reused for everything on a page would answer the second question with the first
     * row's answer. Taking the target's facts as an argument is what keeps that mistake unavailable; nothing
     * produces them yet, and the shape is here, so the first thing that does is not a redesign.
     *
     * An unregistered [targetFacts] entry is **refused**. Those come from a component's own code rather than
     * from anything a caller supplies, so an unknown one is a programming mistake, and dropping it silently
     * would show or hide something with no word said.
     */
    fun assemble(cxt: KdrCxt, targetFacts: Set<String> = emptySet()): Set<String> {
        val unknown = targetFacts.filterNot { it in names }
        if (unknown.isNotEmpty()) {
            throw KdrException(
                "Cfact(s) ${unknown.sorted()} were supplied about a target but are not declared here " +
                    "(declared: ${names.sorted()}). A cfact has to be declared by a component or a client " +
                    "before anything can produce it.",
            )
        }
        val present = LinkedHashSet<String>()
        for ((name, source) in sources) {
            if (source.isPresent(cxt)) {
                present.add(name)
            }
        }
        present.addAll(targetFacts)
        return present
    }

    /**
     * The **frontend-delivered** cfacts mapped to presence (issues #564, #569): the whole `toFrontend`
     * vocabulary, each name to whether it is in [present] (the set [assemble] returns). Not only the present
     * ones -- the frontend parses a `g-visibleWhen` gate against these names, so one it may still name while
     * lacking (a `~hasEnvAuth` gate) has to be here as `false`, or the parse would choke on an unknown name.
     *
     * Takes the already-assembled [present] rather than assembling again, so a caller that has it (the workflow
     * view, which assembles for its per-task filter) does not run every cfact source a second time.
     */
    fun deliveredCfacts(present: Set<String>): Map<String, Boolean> =
        defs.values.filter { it.toFrontend }.associate { it.name to (it.name in present) }

    override fun toString(): String = names.sorted().toString()
}

/**
 * The global registry and each client's, built together (issue #455) -- the cfact counterpart of
 * `SchemaService.storeFor`, and absent-means-global for the same reason.
 */
class CFactRegistries(
    /** What a caller with no client, or a client that adds nothing, sees. */
    val global: CFactRegistry,
    /** The registry of each client that adds something; a client absent here uses [global]. */
    val byClient: Map<String, CFactRegistry>,
) {
    /** The registry [client] sees: their own when they add anything, otherwise [global]. */
    fun forClient(client: String?): CFactRegistry =
        if (client == null) global else byClient[client] ?: global

    companion object {
        /** The empty pair, for a node whose registries have not been built yet. */
        val empty: CFactRegistries = CFactRegistries(CFactRegistry(emptyMap(), emptyMap()), emptyMap())
    }
}

/**
 * Builds the global registry and each client's from what was collected, refusing the boot when a client's
 * declarations collide (issue #455).
 *
 * **Here rather than at each registration**, because this is the first moment holding every contributor at
 * once: a client's configs arrive one bundle at a time, and no single bundle can see whether a component
 * elsewhere already took the name. The same reason `checkOptionsSources` waits for the compiled document.
 *
 * Every problem is collected before any is thrown. Somebody fixing a client's declarations should see all of
 * them in one boot rather than one per attempt.
 *
 * What arrives here is what a collector accumulated, so it is **copied** rather than held: a registry a
 * request parses against has to be the set this function checked, and a live view of a mutable collector is
 * not that. It would also be a way *round* the checks below -- a name added after the build would never be
 * held to "a client may only add" -- so the copy is taken here, where the invariant is stated, rather than
 * left to each caller to remember.
 */
fun buildCFactRegistries(
    global: Map<String, CFactDef>,
    sources: Map<String, CFactSource>,
    perClient: Map<String, List<CFactDef>>,
): CFactRegistries {
    val globalDefs = global.toMap()
    val globalSources = sources.toMap()
    val globalRegistry = CFactRegistry(globalDefs, globalSources)
    val problems = mutableListOf<String>()
    val byClient = LinkedHashMap<String, CFactRegistry>()
    for ((client, declared) in perClient) {
        val own = LinkedHashMap<String, CFactDef>()
        for (def in declared) {
            val existing = globalDefs[def.name]
            if (existing != null) {
                // Additive-only, and this is the failure it prevents: an expression in component-owned data
                // naming this cfact would mean one thing everywhere and another here -- or stop parsing at
                // this client alone, discovered by that client.
                problems.add(
                    "Client '$client' redeclares the cfact '${def.name}', which is already declared globally " +
                        "(group '${existing.group}'). A client may add cfacts, never redefine one.",
                )
                continue
            }
            val twice = own.put(def.name, def)
            if (twice != null) {
                problems.add(
                    "Client '$client' declares the cfact '${def.name}' twice, in groups '${twice.group}' and " +
                        "'${def.group}'. A name identifies one fact.",
                )
            }
        }
        if (own.isNotEmpty()) {
            // The client's own names on top of the global ones: what makes every shared expression still
            // parse here, which is the whole content of "a client may only add".
            byClient[client] = CFactRegistry(globalDefs + own, globalSources)
        }
    }
    if (problems.isNotEmpty()) {
        throw KdrException(
            "Refusing to start: ${problems.size} problem(s) with cfact declarations.\n" + problems.joinToString("\n"),
        )
    }
    return CFactRegistries(globalRegistry, byClient)
}
