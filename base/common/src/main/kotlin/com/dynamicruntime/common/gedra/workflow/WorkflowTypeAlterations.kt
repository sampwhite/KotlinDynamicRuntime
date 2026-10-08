package com.dynamicruntime.common.gedra.workflow

import com.dynamicruntime.common.context.KdrSchemaStore
import com.dynamicruntime.common.gedra.GedraTrait
import com.dynamicruntime.common.schema.SCH
import com.dynamicruntime.common.schema.SL
import com.dynamicruntime.common.schema.collectDefClosure
import com.dynamicruntime.common.schema.overlayDefs
import com.dynamicruntime.common.schema.overlayTypeOutcome
import com.dynamicruntime.common.schema.refName
import com.dynamicruntime.common.startup.layoutProblems
import com.dynamicruntime.common.util.crc32Hex
import com.dynamicruntime.common.util.toJsonMapOrEmpty
import com.dynamicruntime.common.util.toJsonStr
import com.dynamicruntime.common.util.toOptStr

/*
 * A workflow's alterations of types (issue #984): the workflow's own wording for the forms its pages draw, made the
 * way a client alters a type -- by type name, merged over the client's view of it by the declared merger (#985) --
 * one scope further down. A form's data belongs to its traits, which every workflow on the form shares, so a workflow
 * varies presentation only, and for now only a type's field layout (`WfDef` refuses anything else).
 */

/**
 * The schema store [declared]'s pages are drawn from, over [clientStore]: the client's, with the workflow's type
 * alterations applied -- or [clientStore] itself, by identity, when the workflow alters nothing.
 *
 * Built the way a client variant is, by overlaying the document (`overlayDefs`), so every `$ref` to an altered type
 * lands on the altered body. Because an alteration may carry only a field layout, which is never read into a parsed
 * type, the client's parsed types are reused as they are; only the layouts, derived from the defs, differ. That stops
 * holding the day a workflow may narrow a type, which will mean re-parsing here. Kept on [declared], keyed by the
 * client store it was built over, so a reload -- which builds new stores and new declarations -- is never served a
 * stale one.
 */
fun workflowSchemaStore(clientStore: KdrSchemaStore, declared: WfDeclared): KdrSchemaStore {
    val alterations = declared.def.typeAlterations
    if (alterations.isEmpty()) return clientStore
    declared.storeCache?.let { (over, built) -> if (over === clientStore) return built }
    val built = KdrSchemaStore(
        types = clientStore.types,
        endpoints = clientStore.endpoints,
        tables = clientStore.tables,
        defs = overlayDefs(clientStore.defs, alterations),
    )
    declared.storeCache = clientStore to built
    return built
}

/**
 * What is wrong with [declared]'s type alterations against [store] -- the scope's schema -- as one sentence for the
 * workflow's refusal, or null when nothing is (issue #984). [traits] are the scope's, by which the workflow's pages
 * reach their types.
 *
 * - **Only a type the workflow's pages draw** may be altered: one its tasks' traits reach. Altering any other would be
 *   an edit nothing shows.
 * - **The merge must apply**: a layout entry the merge refuses (a field the inherited layout does not list, where its
 *   order matters) is a fault, as it is for a client.
 * - **The merged layout must pass the layout checks** against the type: every field one the type declares, every
 *   template well-formed -- the checks a client's layout gets.
 */
fun typeAlterationProblem(declared: WfDeclared, store: KdrSchemaStore, traits: List<GedraTrait>): String? {
    val alterations = declared.def.typeAlterations
    if (alterations.isEmpty()) return null
    val byId = traits.associateBy { it.traitId }
    val seeds = declared.def.tasks.flatMap { it.traits }
        .mapNotNull { ref -> byId[ref.traitId]?.dataSchema?.get(SCH.dRef).toOptStr()?.let { refName(it) } }
    val shown = collectDefClosure(seeds, store.defs).keys
    for ((typeName, body) in alterations) {
        if (typeName !in shown) {
            return "alters the type '$typeName', which its pages do not show; a workflow may alter only a type its " +
                "tasks' traits reach."
        }
        val base = (store.defs[typeName] as? Map<*, *>)?.toJsonMapOrEmpty() ?: return "alters the type '$typeName', which is not there."
        val outcome = overlayTypeOutcome(typeName, base, body)
        outcome.problems.firstOrNull()?.let {
            return "alters '$typeName' in a way that cannot apply, at ${it.location?.path ?: typeName}: ${it.message}"
        }
        layoutProblems(typeName, "Type '$typeName' (workflow '${declared.def.workflowId}')", outcome.value, store.types[typeName])
            .firstOrNull()?.let { return "alters the layout of '$typeName': ${it.message}" }
    }
    return null
}

/**
 * A stamp of [def]'s content (issue #984): what a Design View edit says it was based on, compared under the write
 * lock with the stored definition's, so an editor working from a definition that has since changed is refused rather
 * than overwriting the change. Computed over the parsed definition's own JSON form, so the stored entry and the
 * loaded definition stamp alike.
 */
// `WfDef.toJsonMap` -- the workflow package's own -- not the util `Any.toJsonMap`, which an explicit import would let
// win over a same-package extension and cast the definition to a map; this file imports `toJsonMapOrEmpty` instead.
fun workflowDefStamp(def: WfDef): String = def.toJsonMap().toJsonStr(compact = true).crc32Hex()

/**
 * [definition] -- a workflow definition's JSON form -- with its layout entry for [field] of [typeName] set to [entry],
 * or removed when [entry] is null (issue #984). Removing the last entry removes the layout, the type's alteration and,
 * last of all, the definition's `types`, so an emptied override leaves no trace. [inherited] -- the entry the client's
 * view of the type has for [field], or null -- is recorded as the entry's **basis** when one is set, and the basis is
 * removed with the entry. Pure, so a test pins it.
 */
fun withLayoutEntry(
    definition: Map<String, Any?>,
    typeName: String,
    field: String,
    entry: Map<String, Any?>?,
    inherited: Map<String, Any?>?,
): Map<String, Any?> = withLayoutAlteration(definition, typeName) { layout, typeBasis ->
    val fields = (layout[SL.schemaFields] as? List<*>).orEmpty().filter { (it as? Map<*, *>)?.get(SL.field) != field }
        .toMutableList()
    if (entry != null) fields.add(LinkedHashMap(entry).also { it[SL.field] = field })
    if (fields.isEmpty()) layout.remove(SL.schemaFields) else layout[SL.schemaFields] = fields
    if (entry != null) typeBasis[field] = inherited?.let { LinkedHashMap(it) } ?: LinkedHashMap<String, Any?>() else typeBasis.remove(field)
}

/**
 * [definition] -- a workflow definition's JSON form -- with its layout alteration of [typeName] rewritten by [edit],
 * which is handed mutable copies of that alteration's `g-layout` and of the type's **basis** (`typeBasis`, what each
 * override replaced) to change in place (issues #984, #1070). Whatever the edit leaves empty is removed -- the layout,
 * the type's alteration and basis, and last of all the definition's `types` and `typeBasis` -- so an emptied override
 * leaves no trace. Pure.
 */
internal fun withLayoutAlteration(
    definition: Map<String, Any?>,
    typeName: String,
    edit: (layout: MutableMap<String, Any?>, typeBasis: MutableMap<String, Any?>) -> Unit,
): Map<String, Any?> {
    fun Any?.asMap(): LinkedHashMap<String, Any?> = LinkedHashMap(toJsonMapOrEmpty())
    val types = definition[WFD.types].asMap()
    val alteration = types[typeName].asMap()
    val layout = alteration[SCH.layout].asMap()
    val basis = definition[WFD.typeBasis].asMap()
    val typeBasis = basis[typeName].asMap()
    edit(layout, typeBasis)
    if (layout.isEmpty()) alteration.remove(SCH.layout) else alteration[SCH.layout] = layout
    if (alteration.isEmpty()) types.remove(typeName) else types[typeName] = alteration
    if (typeBasis.isEmpty()) basis.remove(typeName) else basis[typeName] = typeBasis

    val out = LinkedHashMap(definition)
    if (types.isEmpty()) out.remove(WFD.types) else out[WFD.types] = types
    if (basis.isEmpty()) out.remove(WFD.typeBasis) else out[WFD.typeBasis] = basis
    return out
}

/** The layout entry for [field] in the `g-layout` of [typeName] as [store] has it, or null when there is none. */
fun layoutEntryOf(store: KdrSchemaStore, typeName: String, field: String): Map<String, Any?>? {
    val layout = (store.defs[typeName] as? Map<*, *>)?.get(SCH.layout) as? Map<*, *> ?: return null
    return (layout[SL.schemaFields] as? List<*>).orEmpty()
        .firstOrNull { (it as? Map<*, *>)?.get(SL.field) == field }?.toJsonMapOrEmpty()
}
