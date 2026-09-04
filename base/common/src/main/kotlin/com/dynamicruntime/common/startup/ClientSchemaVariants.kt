package com.dynamicruntime.common.startup

import com.dynamicruntime.common.context.KdrCxt
import com.dynamicruntime.common.context.KdrSchemaStore
import com.dynamicruntime.common.gedra.ClientDef
import com.dynamicruntime.common.gedra.GCFG
import com.dynamicruntime.common.gedra.GID
import com.dynamicruntime.common.gedra.GU
import com.dynamicruntime.common.gedra.formDocsQueryDefName
import com.dynamicruntime.common.gedra.withSearchProperties
import com.dynamicruntime.common.gedra.entryEditUnionDefs
import com.dynamicruntime.common.gedra.entryUnionDefs
import com.dynamicruntime.common.gedra.GedraConfigIssue
import com.dynamicruntime.common.gedra.gedraConfigCheckMode
import com.dynamicruntime.common.gedra.reportConfigProblem
import com.dynamicruntime.common.gedra.supportedTraits
import com.dynamicruntime.common.schema.LogSchema
import com.dynamicruntime.common.schema.collectLayouts
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
    if (clients.isEmpty()) {
        return emptyMap()
    }
    val queryName = formDocsQueryDefName()
    val mode = gedraConfigCheckMode(cxt)
    val issues = mutableListOf<GedraConfigIssue>()
    val out = LinkedHashMap<String, KdrSchemaStore>()
    for (client in clients) {
        val declared = collected.clientOverlays[client] ?: emptyMap()
        val authored = keepWhatNarrows(cxt, client, global.defs, declared, mode, issues)
        val unions = changedUnions(cxt, collected, global, client, defsByClient[client], authored.keys)
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
        var defs: Map<String, Any?> = overlayDefs(global.defs, authored)
        if (unions.isNotEmpty()) defs = defs + unions
        if (queryOverlay.isNotEmpty()) defs = defs + queryOverlay
        if (defs === global.defs) {
            // Identity, not equality: a client whose overlays all fell away -- or who declared none that
            // change anything -- shares the global store rather than paying for a parse that would produce
            // the same answer.
            continue
        }
        out[client] = KdrSchemaStore(
            types = parseSchemaTypes(defs),
            endpoints = global.endpoints,
            tables = global.tables,
            defs = defs,
            // This client's own layouts (issue #584): a client can overlay a type's `g-layout`, and `defs` here
            // carries the merged result, so a read of it is the variant's layout -- inheriting global's where
            // the client changed nothing.
            layouts = collectLayouts(defs),
        )
        LogSchema.debug(cxt) {
            "Built a schema variant for '$client': ${authored.size} altered definition(s), " +
                "${unions.size} regenerated union(s)."
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
    client: String,
    globalDefs: Map<String, Any?>,
    declared: Map<String, Any?>,
    mode: BootCheckMode,
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
            mode,
            GedraConfigIssue(
                "Client '$client' alters '$name' in a way that does not narrow it. " +
                    problems.joinToString(" "),
                "Dropping the alteration; '$name' stays as the global document declares it.",
            ),
            issues,
        )
    }
    return kept
}
