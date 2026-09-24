package com.dynamicruntime.common.startup

import com.dynamicruntime.common.context.KdrCxt
import com.dynamicruntime.common.context.KdrSchemaStore
import com.dynamicruntime.common.exception.KdrException
import com.dynamicruntime.common.gedra.ClientDef
import com.dynamicruntime.common.gedra.GCFG
import com.dynamicruntime.common.gedra.GID
import com.dynamicruntime.common.gedra.GU
import com.dynamicruntime.common.gedra.formDocsQueryDefName
import com.dynamicruntime.common.gedra.withSearchProperties
import com.dynamicruntime.common.gedra.entryEditUnionDefs
import com.dynamicruntime.common.gedra.entryUnionDefs
import com.dynamicruntime.common.gedra.GedraConfigIssue
import com.dynamicruntime.common.gedra.GCEL
import com.dynamicruntime.common.gedra.issue
import com.dynamicruntime.common.gedra.reportConfigProblem
import com.dynamicruntime.common.gedra.supportedTraits
import com.dynamicruntime.common.schema.LogSchema
import com.dynamicruntime.common.schema.SCH
import com.dynamicruntime.common.schema.SchType
import com.dynamicruntime.common.schema.collectLayouts
import com.dynamicruntime.common.schema.layoutFieldProblems
import com.dynamicruntime.common.schema.layoutTemplateProblems
import com.dynamicruntime.common.schema.narrowingProblems
import com.dynamicruntime.common.schema.overlayDefs
import com.dynamicruntime.common.schema.parseSchemaTypes
import com.dynamicruntime.common.util.toJsonMap

/**
 * Builds the per-client schema variants at startup (issue #356).
 *
 * A variant is the global document with one client's overlays applied and **re-parsed**, never the global
 * `SchType` graph edited or cloned. See `overlayDefs` for why: a `$ref` is bound to an object pointer during
 * parsing, so re-parsing is what makes a reference to an altered type resolve to the altered form -- for
 * nested constructs, union branches, and array items alike -- with no traversal to get wrong.
 *
 * Endpoints and tables are **shared by reference** with the global store. `client-definition.md` settles that
 * a variant carrying tables identical to global's is harmless and that sharing them is free; endpoints are the
 * same case, and per-client generated endpoints are later work that will add to a variant rather than change
 * what this does.
 *
 * What is *not* here, deliberately: the per-client entry unions, which need `supportedTraits(client)`, and
 * applying a variant to a request. This builds the stores and nothing consults them yet, which is the same
 * order #343 followed -- the refusals become trustworthy before anything depends on them.
 */
fun buildClientVariants(
    cxt: KdrCxt,
    collected: SchemaCollector,
    global: KdrSchemaStore,
    queryBase: Any?,
    /**
     * Restrict the build to this one client (issue #616): a running-node reload rebuilds a single client's
     * variant off the current collector, over the very same per-client body the boot runs for every client --
     * so a reloaded variant and a booted one are the same computation. Null builds every varying client.
     */
    onlyClient: String? = null,
    /**
     * What a client's own definitions are repaired against (issue #841): the registered options providers and the
     * `g-visibleWhen` check. A fault is dropped at the smallest grain -- a keyword, a message, a layout, one type
     * change -- and reported under the holding config's check mode, rather than refusing the boot or the reload.
     */
    repair: DefRepairContext,
): Map<String, KdrSchemaStore> {
    val defsByClient = collected.gedraConfigs.configs.mapNotNull { it.client }.associateBy { it.clientId }
    // A client that only declares usage rules (issue #538) varies its listing's search fields without
    // overlaying a `$def` or restricting its traits -- so it would be missed by the two sets below, which is
    // exactly the ordinary case for a client that adds a search column and nothing else.
    val usageClients = collected.gedraConfigs.configs
        .filter { it.usages.isNotEmpty() }
        .map { it.gedraId.client }
        .filter { it != GID.globalClient }
    // Every client that could differ from global: one that overlaid something, one whose definition restricts
    // which traits it supports, and one that declared usage rules. The middle has no overlays at all, so
    // iterating those alone would miss it -- a client that narrows its trait set purely by declaration is the
    // ordinary case.
    val clients = (collected.clientOverlays.keys + defsByClient.keys + usageClients).toSet()
        .let { all -> if (onlyClient == null) all else all.filter { it == onlyClient }.toSet() }
    if (clients.isEmpty()) {
        return emptyMap()
    }
    val queryName = formDocsQueryDefName()
    val issues = mutableListOf<GedraConfigIssue>()
    val out = LinkedHashMap<String, KdrSchemaStore>()
    for (client in clients) {
        val declared = collected.clientOverlays[client] ?: emptyMap()
        val narrowed = keepWhatNarrows(cxt, collected, client, global.defs, declared, issues)
        // Issue #841: a fault in the client's own definitions costs only itself. Keyword-level faults are repaired
        // on the raw definitions first, where a keyword can still be removed.
        var authored = repairKeywords(cxt, collected, client, narrowed, repair, issues)
        // The client's forms-listing search fields (issue #538): its usage rules' parameters merged onto the
        // pristine query base -- never onto the global-augmented type, or an overriding client would inherit
        // global's parameters too. Folded in only when they differ from the global type, like the unions
        // below: a generated overlay returning only the difference, so an inheriting client shares the store.
        val clientQuery = withSearchProperties(queryBase, collected.gedraConfigs.usagesFor(client))
        val queryOverlay = if (clientQuery != global.defs[queryName]) mapOf(queryName to clientQuery) else emptyMap()
        // The unions and the query overlay are applied **after** the authored overlay, and so replace rather
        // than merge. `overlayDefs` is built for an authored alteration, where an unmentioned key means "leave
        // it as it was" -- exactly wrong for a type regenerated whole, whose absent `oneOf` is the statement
        // being made. Merged, the global `oneOf` would survive underneath and the client would go on
        // recognizing every trait. Each `+` is guarded so an empty generated set keeps `overlayDefs`'
        // identity result (a client varying nothing shares the global store rather than paying for a parse).
        // A function of the authored set, because the unions follow what the client overlaid and a repair below
        // can drop an alteration.
        fun compose(from: Map<String, Any?>): Map<String, Any?> {
            val unions = changedUnions(cxt, collected, global, client, defsByClient[client], from.keys)
            var composed: Map<String, Any?> = overlayDefs(global.defs, from)
            if (unions.isNotEmpty()) composed = composed + unions
            if (queryOverlay.isNotEmpty()) composed = composed + queryOverlay
            return composed
        }
        var defs = compose(authored)
        if (defs === global.defs) {
            // Identity, not equality: a client whose overlays all fell away -- or who declared none that
            // change anything -- shares the global store rather than paying for a parse that would produce
            // the same answer.
            continue
        }
        // A type change that will not compile is dropped, not the variant (issue #841); then a layout the client
        // wrote that names what its type lacks, or will not parse, is dropped and the variant re-parsed.
        val parsed = parseDroppingFaults(cxt, collected, client, authored, ::compose, issues)
        authored = parsed.first
        var types = parsed.second
        defs = compose(authored)
        val layoutFree = dropFaultyLayouts(cxt, collected, client, global.defs, authored, defs, types, issues)
        if (layoutFree !== authored) {
            authored = layoutFree
            defs = compose(authored)
            types = parseSchemaTypes(defs)
        }
        if (defs === global.defs) {
            continue
        }
        out[client] = KdrSchemaStore(
            types = types,
            endpoints = global.endpoints,
            tables = global.tables,
            // The variant's own layouts (issue #584) derive from these defs: a client may overlay a type's
            // `g-layout` (it is a presentation key the narrowing check permits), and an unmentioned one is
            // inherited from global by reference -- then pruned to whatever properties the client kept.
            defs = defs,
        )
        LogSchema.debug(cxt) {
            "Built a schema variant for '$client': ${authored.size} altered definition(s)."
        }
    }
    return out
}

/**
 * The entry unions for [client], but **only where they differ** from the global ones (issue #356).
 *
 * The unions are what makes a client's *supported* set real: `entryUnionDefs` is already a function of
 * (client, kind) and is called once with the global scope, so a variant is the same call with this client's
 * `supportedTraits`. A client supporting fewer traits gets a union with fewer branches, and an entry carrying
 * a trait it does not support lands on the default branch as plain JSON -- carried rather than refused, which
 * is what makes an allowlist tolerable to change at all (#301).
 *
 * Returning only what differs is load-bearing rather than an optimization: a client that supports exactly
 * what global does produces exactly the global union, and handing that back as an "overlay" would make
 * `overlayDefs` build a variant that is equal to the global document in every respect while not being the
 * same object -- so `hub` and `public`, which include `#allGlobal` and vary nothing, would each pay for a
 * parse and a store to hold the same answer.
 *
 * These are **generated**, so they are not put through the narrowing check. Fewer branches is narrower, but
 * the rule is about what a client may *author*, and nobody authored this.
 */
private fun changedUnions(
    cxt: KdrCxt,
    collected: SchemaCollector,
    global: KdrSchemaStore,
    client: String,
    def: ClientDef?,
    overlaidTypes: Set<String>,
): Map<String, Any?> {
    val traits = supportedTraits(collected.gedraConfigs, client, def, overlaidTypes)
    val built = LinkedHashMap<String, Any?>()
    for (kind in GU.entryKinds) {
        built.putAll(entryUnionDefs(cxt, GCFG.globalNamespace, kind, traits))
        built.putAll(entryEditUnionDefs(cxt, GCFG.globalNamespace, kind, traits))
    }
    return built.filter { (name, body) -> body != global.defs[name] }
}

/**
 * [declared] with any alteration that would widen a global type dropped.
 *
 * A name the global document does not have is a new type for this client and is taken as it stands -- there
 * is nothing for it to widen. A name it does have is an alteration, and is held to the three ways a client
 * may narrow (see `narrowingProblems`).
 *
 * The **offending alteration** is dropped, not the client's whole variant and not the client. That is the
 * proportionate answer: the type reverts to the global one, which is the shared truth and wider rather than
 * narrower, so nothing this client stores under it can be invalid to anybody else. Dropping the variant
 * entirely would do the same for every type the client altered, including the ones that were fine.
 */
private fun keepWhatNarrows(
    cxt: KdrCxt,
    collected: SchemaCollector,
    client: String,
    globalDefs: Map<String, Any?>,
    declared: Map<String, Any?>,
    issues: MutableList<GedraConfigIssue>,
): Map<String, Any?> {
    val kept = LinkedHashMap<String, Any?>(declared.size)
    for ((name, body) in declared) {
        val base = globalDefs[name]
        if (base !is Map<*, *> || body !is Map<*, *>) {
            kept[name] = body
            continue
        }
        val problems = narrowingProblems(name, base.toJsonMap(), body.toJsonMap())
        if (problems.isEmpty()) {
            kept[name] = body
            continue
        }
        reportConfigProblem(
            cxt,
            alterationIssue(
                collected, client, name,
                "Client '$client' alters '$name' in a way that does not narrow it. " + problems.joinToString(" "),
                "Dropping the alteration; '$name' stays as the global document declares it.",
            ),
            issues,
        )
    }
    return kept
}

/**
 * An issue about [client]'s definition of [name], held by the config that contributed it (issue #839) -- so a
 * stored overlay is judged as stored config, and a source one as source.
 */
private fun alterationIssue(
    collected: SchemaCollector,
    client: String,
    name: String,
    message: String,
    degradedTo: String,
): GedraConfigIssue =
    collected.gedraConfigs.contributorOf(client, name)?.issue(message, degradedTo, GCEL.type, name)
        ?: GedraConfigIssue(message, degradedTo, client = client, elementKind = GCEL.type, elementId = name)

/**
 * [authored] with each alteration's keyword-level faults removed ([repairTypeDef], issue #841), every removal
 * reported against the config that holds it. Returns [authored] itself when nothing needed repair.
 */
private fun repairKeywords(
    cxt: KdrCxt,
    collected: SchemaCollector,
    client: String,
    authored: Map<String, Any?>,
    repair: DefRepairContext,
    issues: MutableList<GedraConfigIssue>,
): Map<String, Any?> {
    var out: LinkedHashMap<String, Any?>? = null
    for ((name, body) in authored) {
        if (body !is Map<*, *>) continue
        val (repaired, repairs) = repairTypeDef("Type '$name' (client '$client')", body.toJsonMap(), repair)
        if (repairs.isEmpty()) continue
        for (r in repairs) {
            reportConfigProblem(cxt, alterationIssue(collected, client, name, r.message, r.degradedTo), issues)
        }
        (out ?: LinkedHashMap(authored).also { out = it })[name] = repaired
    }
    return out ?: authored
}

/**
 * Parses [client]'s variant, dropping any **type change that will not compile** rather than the variant (issue
 * #841): an unresolvable `$ref`, a malformed keyword the parser refuses. Returns the alterations kept and the parsed
 * types. The common case -- everything compiles -- costs the one parse it always did.
 *
 * On a failure, each alteration is judged the way it would be alone: with the client's other alterations minus it.
 * One whose removal lets the rest compile is the culprit; failing that (two bad ones, each hiding the other), one
 * that will not compile over the global document by itself is. Whatever is still refused after both is a
 * combination nothing narrower explains, and the client's alterations are dropped together -- its variant then
 * the global document plus what it generates -- so the node still starts.
 */
private fun parseDroppingFaults(
    cxt: KdrCxt,
    collected: SchemaCollector,
    client: String,
    authored: Map<String, Any?>,
    compose: (Map<String, Any?>) -> Map<String, Any?>,
    issues: MutableList<GedraConfigIssue>,
): Pair<Map<String, Any?>, Map<String, SchType>> {
    fun tryParse(from: Map<String, Any?>): Map<String, SchType>? =
        try {
            parseSchemaTypes(compose(from))
        } catch (_: KdrException) {
            null
        }
    fun failure(from: Map<String, Any?>): String =
        try {
            parseSchemaTypes(compose(from))
            ""
        } catch (e: KdrException) {
            e.message.orEmpty()
        }

    tryParse(authored)?.let { return authored to it }
    fun drop(kept: MutableMap<String, Any?>, name: String) {
        val why = failure(mapOf(name to authored[name])).ifEmpty { failure(kept) }
        kept.remove(name)
        reportConfigProblem(
            cxt,
            alterationIssue(
                collected, client, name,
                "Client '$client''s definition of '$name' does not compile: $why",
                "Dropping it; '$name' stays as the global document declares it (or is absent, when it was new).",
            ),
            issues,
        )
    }
    val kept = LinkedHashMap(authored)
    authored.keys.firstOrNull { name -> tryParse(kept - name) != null }?.let { culprit ->
        drop(kept, culprit)
        return kept to tryParse(kept)!!
    }
    for (name in authored.keys) {
        if (tryParse(mapOf(name to authored[name])) == null) {
            drop(kept, name)
        }
    }
    tryParse(kept)?.let { return kept to it }
    val why = failure(kept)
    for (name in kept.keys.toList()) {
        reportConfigProblem(
            cxt,
            alterationIssue(
                collected, client, name,
                "Client '$client''s schema changes do not compile together: $why",
                "Dropping all of the client's schema changes; its variant is the global document.",
            ),
            issues,
        )
    }
    return emptyMap<String, Any?>() to parseSchemaTypes(compose(emptyMap()))
}

/**
 * [authored] with each `g-layout` the client **wrote** removed when it is at fault (issue #841) -- one that will not
 * parse, names a field its type lacks, or carries a malformed template -- so the type falls back to global's layout
 * (or none) rather than the variant refusing. A layout inherited from global by reference is global's to answer for
 * and is skipped, as the boot check skips it. Returns [authored] itself when nothing was dropped.
 */
private fun dropFaultyLayouts(
    cxt: KdrCxt,
    collected: SchemaCollector,
    client: String,
    globalDefs: Map<String, Any?>,
    authored: Map<String, Any?>,
    defs: Map<String, Any?>,
    types: Map<String, SchType>,
    issues: MutableList<GedraConfigIssue>,
): Map<String, Any?> {
    fun rawLayout(from: Map<String, Any?>, name: String): Any? = (from[name] as? Map<*, *>)?.get(SCH.layout)
    var out: LinkedHashMap<String, Any?>? = null
    for ((name, body) in authored) {
        if (body !is Map<*, *> || body[SCH.layout] == null) continue
        if (rawLayout(defs, name) === rawLayout(globalDefs, name)) continue
        val where = "Type '$name' (client '$client')"
        val problems = try {
            val layout = collectLayouts(mapOf(name to defs[name])).getValue(name)
            layoutFieldProblems(where, layout, types[name]) + layoutTemplateProblems(where, layout, types[name]) +
                layoutBackendBlockProblems(where, layout)
        } catch (e: KdrException) {
            listOf(e.message.orEmpty())
        }
        if (problems.isEmpty()) continue
        reportConfigProblem(
            cxt,
            alterationIssue(
                collected, client, name, problems.joinToString(" "),
                "Dropping the client's '${SCH.layout}' on '$name'; the type renders with global's layout, or none.",
            ),
            issues,
        )
        (out ?: LinkedHashMap(authored).also { out = it })[name] = body.toJsonMap() - SCH.layout
    }
    return out ?: authored
}
