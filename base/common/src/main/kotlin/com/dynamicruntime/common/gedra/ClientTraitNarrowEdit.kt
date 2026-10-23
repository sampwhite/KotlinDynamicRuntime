package com.dynamicruntime.common.gedra

import com.dynamicruntime.common.context.KdrCxt
import com.dynamicruntime.common.exception.EXC
import com.dynamicruntime.common.exception.KdrException
import com.dynamicruntime.common.naming.clientNamespace
import com.dynamicruntime.common.schema.SCH
import com.dynamicruntime.common.schema.refName
import com.dynamicruntime.common.startup.SchemaService
import com.dynamicruntime.common.util.toJsonListOfStrings
import com.dynamicruntime.common.util.toJsonMapOrEmpty
import com.dynamicruntime.common.util.toOptStr

/** A global trait as a base: its id, its data type's qualified name, and that type's body as the platform declares it. */
class GlobalTraitBase(val traitId: String, val typeName: String, val body: Map<String, Any?>) {
    /** The fields the type requires. */
    val required: List<String> get() = body[SCH.required].toJsonListOfStrings()
}

/**
 * Every global data trait as a base ([GlobalTraitBase]), keyed by trait id -- what a narrowing narrows and an extension
 * extends (issue #1119). A trait whose data is not a `$ref` to a named type has no type to alter, and is left out.
 */
fun globalTraitBases(cxt: KdrCxt): Map<String, GlobalTraitBase> {
    val schema = SchemaService.get(cxt)
    val served = schema.schemaStore.servedDefs
    return schema.gedraTraitsFor(GID.globalClient).mapNotNull { trait ->
        val typeName = trait.dataSchema[SCH.dRef].toOptStr()?.let(::refName) ?: return@mapNotNull null
        val body = served[typeName].toJsonMapOrEmpty().ifEmpty { return@mapNotNull null }
        trait.traitId to GlobalTraitBase(trait.traitId, typeName, body)
    }.toMap()
}

/**
 * Narrowing a global trait for a client (issue #1119): the write behind `POST /clientAdmin/client/traits/narrow`, and
 * the read the catalog shows each global trait's narrowing by. The moves and what they write are [TraitNarrowing]'s.
 *
 * ### Where a narrowing lands
 *
 * As the client's **alteration** of the trait's data type: a `schemaDef` entry under the global type's own name, which
 * the load holds to the narrowing rules. A client's first narrowing of a trait gets a stored configuration of its own,
 * named for the trait ([CIT.narrowPrefix]); an alteration already stored -- in that configuration, or one written by
 * hand -- is patched where it is. Taking a narrowing back to nothing removes the entry, so the client has the trait as
 * the platform declares it. An alteration declared in source code is edited there.
 *
 * ### How it takes effect
 *
 * As every editor's save on the client's page does ([ClientStoredEdit]): live, or a draft for a client with a sandbox.
 * A live save that would leave stored forms holding data the narrowed trait refuses -- a field made required, a choice
 * taken away -- is refused with the impact report until acknowledged (issue #935). A draft is reported on too, against
 * the live client, for information ([ClientStoredEdit.draftImpact]). Hiding a field changes no data, and judges none.
 *
 * A narrowing does not include the trait: it says how the client takes a trait it includes, and has no effect on one
 * it does not (`supportedTraits` counts a trait as customized by an alteration of its entry type, not of its data).
 */
@Suppress("ConstPropertyName")
object ClientTraitNarrowEdit {
    /** What a save did: the configuration the alteration is in, the narrowing now, how it took effect, and a draft's impact. */
    class Result(
        val configName: String,
        val narrowing: TraitNarrowing,
        val issues: List<GedraConfigIssue>,
        val mode: String,
        val draftImpact: ImpactReport?,
    )

    /** The client's stored alteration of a type: the configuration holding it, and its entry. */
    private class StoredAlteration(val row: GedraConfigRow, val entry: Map<String, Any?>) {
        val body: Map<String, Any?> get() = entry[CCT.schema].toJsonMapOrEmpty()
    }

    /** [bound]'s client's stored alterations, by type name, latest revisions, drafts included. */
    private fun storedAlterations(bound: KdrCxt, typeNames: Set<String>): Map<String, StoredAlteration> = buildMap {
        for (row in GedraConfigService.get(bound).listConfigs(bound)) {
            for (entry in row.entriesBySlot()[CCT.schemaDef].orEmpty()) {
                val name = entry[CCT.typeName].toOptStr() ?: continue
                if (name in typeNames) put(name, StoredAlteration(row, entry))
            }
        }
    }

    /**
     * Each global trait's narrowing for [client]'s catalog, by trait id: the narrowing, or why the editor cannot edit
     * the client's alteration, and the configuration holding a stored one.
     */
    fun narrowings(cxt: KdrCxt, client: String): Map<String, Map<String, Any?>> {
        // Named for a sandbox, its parent's, which the sandbox runs and its editors change (issue #1358).
        val owner = sandboxParentOf(client) ?: client
        val bases = globalTraitBases(cxt)
        val stored = storedAlterations(cxt.mkSubContext("traitNarrowings", owner), bases.values.map { it.typeName }.toSet())
        val inSource = SchemaService.get(cxt).overlaidTypesFor(owner)
        return bases.mapValues { (traitId, base) ->
            val alteration = stored[base.typeName]
            buildMap {
                alteration?.let { put(CIT.narrowingConfig, it.row.configId.baseId) }
                if (alteration == null && base.typeName in inSource) {
                    put(CIT.narrowingNotEditable, sourceAlteration)
                } else {
                    val read = traitNarrowingOf(traitId, base.body, alteration?.body)
                    read.narrowing?.let { put(CIT.narrowing, it.toInfo()) }
                    read.notEditable?.let { put(CIT.narrowingNotEditable, it) }
                }
            }
        }
    }

    /**
     * Why an alteration the client runs but does not store cannot be edited: it is declared in source code -- the
     * client's own, or a template's it extends.
     */
    private const val sourceAlteration = "Its alteration is declared in source code, where it is edited."

    /**
     * Saves [narrowing] of a global trait for [client] -- see the class note. [expected] is how the caller told its
     * user the save would take effect; [impact] whether a save that affects stored forms goes ahead.
     */
    fun save(cxt: KdrCxt, client: String, narrowing: TraitNarrowing, expected: String? = null, impact: ImpactGate = ImpactGate.refuse): Result {
        // From a sandbox, its parent's: the sandbox holds no configuration, and a save is a draft of the parent's (#1358).
        val owner = sandboxParentOf(client) ?: client
        val base = globalTraitBases(cxt)[narrowing.traitId]
            ?: throw KdrException("There is no global trait '${narrowing.traitId}' to narrow.", code = EXC.notFound)
        val problems = traitNarrowingProblems(narrowing, base.body)
        if (problems.isNotEmpty()) throw KdrException.mkInput(problems.joinToString(" "))
        val target = ClientStoredEdit.target(cxt, client, "traitNarrow", expected)
        val bound = target.bound
        val existing = storedAlterations(bound, setOf(base.typeName))[base.typeName]
        if (existing == null && base.typeName in SchemaService.get(cxt).overlaidTypesFor(owner)) {
            throw KdrException.mkInput("Trait '${narrowing.traitId}' cannot be narrowed here. $sourceAlteration")
        }
        existing?.let { e ->
            traitNarrowingOf(narrowing.traitId, base.body, e.body).notEditable?.let {
                throw KdrException.mkInput("Trait '${narrowing.traitId}' cannot be narrowed here. $it")
            }
        }
        val body = narrowing.toAlteration(base.body, existing?.body)
        val written: GedraConfigRow
        val reload: ConfigReloadResult
        val name = CIT.narrowPrefix + narrowing.traitId.replace(':', '_')
        // The editor's own configuration for the trait outlives a narrowing taken back to nothing, which leaves it empty;
        // narrowing again writes into it, since a configuration once written is never made anew.
        val holder = existing?.row ?: GedraConfigService.get(bound).listConfigs(bound).firstOrNull { it.configId.baseId == name }
        if (holder == null) {
            body ?: throw KdrException.mkInput("Client '$owner' takes trait '${narrowing.traitId}' as it is; nothing to save.")
            val entry = mapOf(CCT.typeName to base.typeName, CCT.schema to body)
            val config = reassembleForWrite(bound, name, clientNamespace(owner), owner, mapOf(CCT.schemaDef to listOf(entry)))
            written = GedraConfigService.get(bound).writeConfig(bound, config, trial = true, mustBeNew = true)
            reload = ClientStoredEdit.takeEffect(cxt, target, written, undo = {}, impact = impact)
        } else {
            if (existing == null) {
                body ?: throw KdrException.mkInput("Client '$owner' takes trait '${narrowing.traitId}' as it is; nothing to save.")
            } else {
                val ours = existing.row.configId.baseId.startsWith(CIT.narrowPrefix)
                if (!ours) ClientStoredEdit.requireNoForeignDraft(target, existing.row, "a narrowing of its traits")
                if (body == existing.body) throw KdrException.mkInput("Trait '${narrowing.traitId}' is already narrowed so; nothing to save.")
            }
            // The type's entry rewritten, removed (`to` null), or added where the configuration has none.
            fun patch(row: GedraConfigRow, to: Map<String, Any?>?) =
                GedraConfigService.get(bound).patchConfigs(bound, trial = true) { edit(row.configId) { slots ->
                    val out = LinkedHashMap(slots)
                    val listed = slots[CCT.schemaDef].orEmpty()
                    val entries = listed.mapNotNull {
                        if (it[CCT.typeName] != base.typeName) it else to?.let { b -> LinkedHashMap(it).also { e -> e[CCT.schema] = b } }
                    }
                    val added = if (to == null || listed.any { it[CCT.typeName] == base.typeName }) emptyList()
                    else listOf(mapOf(CCT.typeName to base.typeName, CCT.schema to to))
                    if (entries.isEmpty() && added.isEmpty()) out.remove(CCT.schemaDef) else out[CCT.schemaDef] = entries + added
                    out
                } }.single()
            written = patch(holder, body)
            reload = ClientStoredEdit.takeEffect(
                cxt, target, written,
                undo = { patch(written, existing?.body) },
                restorePublished = holder.isPublished,
                impact = impact,
            )
        }
        val now = traitNarrowingOf(narrowing.traitId, base.body, body).narrowing ?: narrowing
        return Result(written.configId.baseId, now, reload.issues, target.mode, ClientStoredEdit.draftImpact(target, written))
    }
}
