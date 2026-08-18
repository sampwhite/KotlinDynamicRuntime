package com.dynamicruntime.common.startup

import com.dynamicruntime.common.context.KdrCxt
import com.dynamicruntime.common.context.KdrSchemaStore
import com.dynamicruntime.common.gedra.GedraConfigIssue
import com.dynamicruntime.common.gedra.gedraConfigCheckMode
import com.dynamicruntime.common.gedra.reportConfigProblem
import com.dynamicruntime.common.schema.LogSchema
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
 * nested constructs, union branches and array items alike -- with no traversal to get wrong.
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
): Map<String, KdrSchemaStore> {
    if (collected.clientOverlays.isEmpty()) {
        return emptyMap()
    }
    val mode = gedraConfigCheckMode(cxt)
    val issues = mutableListOf<GedraConfigIssue>()
    val out = LinkedHashMap<String, KdrSchemaStore>()
    for ((client, declared) in collected.clientOverlays) {
        val overlays = keepWhatNarrows(cxt, client, global.defs, declared, mode, issues)
        val defs = overlayDefs(global.defs, overlays)
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
        )
        LogSchema.debug(cxt) { "Built a schema variant for '$client' from ${overlays.size} definition(s)." }
    }
    return out
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
    mode: String,
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
