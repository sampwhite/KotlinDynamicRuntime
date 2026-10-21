package com.dynamicruntime.common.gedra

import com.dynamicruntime.common.context.KdrCxt
import com.dynamicruntime.common.exception.EXC
import com.dynamicruntime.common.exception.KdrException
import com.dynamicruntime.common.naming.clientNamespace
import com.dynamicruntime.common.startup.SchemaService
import com.dynamicruntime.common.util.toJsonMapOrEmpty
import com.dynamicruntime.common.util.toOptStr

/**
 * Designing a client's own traits (issue #1118): a new trait from a design ([TraitDesign]), or an edit of one the
 * client stores -- the write behind `POST /clientAdmin/client/traits/design`, and the listing the traits editor
 * draws from.
 *
 * ### Where a trait lands
 *
 * A **new** trait gets a stored configuration of its own, named for it ([CIT.configPrefix]), so each is published,
 * judged and later carried on its own. An **edit** patches the trait's entry where it is stored, whichever
 * configuration that is -- one the editor made, or one written by hand whose fields are all of the editor's kinds
 * ([traitDesignOf]) -- and leaves the rest of the configuration as it was.
 *
 * ### How it takes effect
 *
 * As every editor's save on the client's page does ([ClientStoredEdit]): live at once, or a draft for a client with a
 * sandbox. A trait's configuration made by this editor is its own, so a draft left in one -- a refused publish -- is
 * no stranger's, and does not stop the next edit; any other configuration's is ([ClientStoredEdit.requireNoForeignDraft]).
 *
 * ### What is checked
 *
 * The design's own rules ([traitDesignProblems]): a name, a label on every field, choices for a choice field, and a
 * kept field keeping its kind. Then the write's trial, which holds the plan's limits (issue #1109) -- schemas, and
 * attributes per schema -- and everything a configuration write is held to. An edit that would leave stored forms
 * holding data the trait no longer accepts -- a field made required, a choice removed -- is refused with the impact
 * report until acknowledged (issue #935). A new trait has no data to judge.
 */
object ClientTraitDesignEdit {
    /** What a save did: the configuration the trait is in, the trait as designed now, and how it took effect. */
    class Result(val configName: String, val design: TraitDesign, val issues: List<GedraConfigIssue>, val mode: String)

    /** One of the client's own traits: where it is stored, and its design or why the editor cannot edit it. */
    private class StoredTrait(val row: GedraConfigRow, val entry: Map<String, Any?>) {
        val traitId: String = entry[CCT.traitId].toOptStr().orEmpty()
        val read: TraitDesignRead =
            traitDesignOf(traitId, entry[CCT.description].toOptStr(), entry[CCT.dataSchema].toJsonMapOrEmpty())
    }

    /** The traits [bound]'s client stores, latest revisions, drafts included: what the editor starts from. */
    private fun storedTraits(bound: KdrCxt): Map<String, StoredTrait> = buildMap {
        for (row in GedraConfigService.get(bound).listConfigs(bound)) {
            for (entry in row.entriesBySlot()[CCT.traitDef].orEmpty()) {
                val trait = StoredTrait(row, entry)
                if (trait.traitId.isNotEmpty()) put(trait.traitId, trait)
            }
        }
    }

    /** The client's own trait ids it runs, declared in source code or stored -- what a new trait's id may not be. */
    private fun runningOwnTraitIds(cxt: KdrCxt, client: String): Set<String> {
        val schema = SchemaService.get(cxt)
        return schema.gedraTraitsFor(client).filterNot { schema.isGlobalTrait(it.traitId) }.map { it.traitId }.toSet()
    }

    /**
     * [client]'s own traits for the catalog: each stored one with its design, or why the editor cannot edit it, then
     * any declared in source code, which are edited there. In the order they are stored.
     */
    fun ownTraits(cxt: KdrCxt, client: String): List<Map<String, Any?>> {
        val stored = storedTraits(cxt.mkSubContext("traitDesigns", client))
        val listed = stored.values.map { t ->
            buildMap {
                put(CIT.traitId, t.traitId)
                put(CIT.configName, t.row.configId.baseId)
                t.read.design?.let { put(CIT.design, it.toInfo()) }
                t.read.notEditable?.let { put(CIT.notEditable, it) }
            }
        }
        val inSource = runningOwnTraitIds(cxt, client).filterNot { it in stored }.map {
            mapOf(CIT.traitId to it, CIT.notEditable to "It is declared in source code, where it is edited.")
        }
        return listed + inSource
    }

    /**
     * The plan's limits the editor holds a design to (issue #1109): the schemas [client] declares and may, and the
     * attributes one may have. Null for a client on no plan, or a plan that limits neither.
     */
    fun quotas(cxt: KdrCxt, client: String): Map<String, Any?>? {
        val plan = PlanLimits.planOf(cxt, client) ?: return null
        val quotas = PLAN.quotasOf(plan)
        if (quotas.schemas == null && quotas.attributesPerSchema == null) return null
        // Forms and users are not shown here, so they are not counted.
        val usage = PlanLimits.usageOf(cxt, client, forms = 0, users = 0)
        return buildMap {
            put(CIT.plan, plan)
            usage?.measures?.firstOrNull { it.measure == QUOTA.schemas }?.let {
                put(CIT.schemasUsed, it.used)
                put(CIT.schemasLimit, it.limit)
            }
            quotas.attributesPerSchema?.let { put(CIT.attributesLimit, it) }
        }
    }

    /**
     * Saves [design] for [client]: a new trait when it names none, an edit of the stored one it names otherwise -- see
     * the class note. [expected] is how the caller told its user the save would take effect; [impact] whether an edit
     * that affects stored forms goes ahead.
     */
    fun save(cxt: KdrCxt, client: String, design: TraitDesign, expected: String? = null, impact: ImpactGate = ImpactGate.refuse): Result {
        sandboxParentOf(client)?.let { parent ->
            throw KdrException.mkInput("'$client' is a sandbox: its traits are its parent's, which are designed on '$parent'.")
        }
        val target = ClientStoredEdit.target(cxt, client, "traitDesign", expected)
        val bound = target.bound
        val stored = storedTraits(bound)
        val taken = stored.keys + runningOwnTraitIds(cxt, client)
        val existing = design.traitId?.let { id ->
            stored[id] ?: throw KdrException(
                if (id in taken) "Trait '$id' is declared in source code, where it is edited."
                else "Client '$client' has no trait '$id' to edit.",
                code = if (id in taken) EXC.badInput else EXC.notFound,
            )
        }
        val before = existing?.let { it.read.design ?: throw KdrException.mkInput("Trait '${it.traitId}' cannot be edited here. ${it.read.notEditable}") }
        val keyed = withKeys(design, taken)
        val problems = traitDesignProblems(keyed, before)
        if (problems.isNotEmpty()) throw KdrException.mkInput(problems.joinToString(" "))
        val traitId = keyed.traitId!!
        val written: GedraConfigRow
        val reload: ConfigReloadResult
        if (existing == null) {
            val name = CIT.configPrefix + traitId
            val entry = buildMap {
                put(CCT.traitId, traitId)
                put(CCT.typeName, "${clientNamespace(client)}.${traitId.replaceFirstChar { it.uppercase() }}Entry")
                put(CCT.appliesTo, listOf(GedraDataType.formDoc.name))
                keyed.description?.let { put(CCT.description, it) }
                put(CCT.dataSchema, keyed.toDataSchema())
            }
            val config = reassembleForWrite(bound, name, clientNamespace(client), client, mapOf(CCT.traitDef to listOf(entry)))
            written = GedraConfigService.get(bound).writeConfig(bound, config, trial = true, mustBeNew = true)
            // Nothing stored can hold a trait that did not exist, so there is no impact to judge. A publish refused
            // after the write's own trial passed leaves the configuration as a draft, which the refusal says.
            reload = ClientStoredEdit.takeEffect(cxt, target, written, undo = {})
        } else {
            if (!existing.row.configId.baseId.startsWith(CIT.configPrefix)) {
                ClientStoredEdit.requireNoForeignDraft(target, existing.row, "an edit of its traits")
            }
            val entry = LinkedHashMap(existing.entry)
            keyed.description?.let { entry[CCT.description] = it } ?: entry.remove(CCT.description)
            entry[CCT.dataSchema] = keyed.toDataSchema(existing.entry[CCT.dataSchema].toJsonMapOrEmpty())
            if (entry == existing.entry) throw KdrException.mkInput("Trait '$traitId' is already designed so; nothing to save.")
            fun patch(row: GedraConfigRow, to: Map<String, Any?>) = GedraConfigService.get(bound).patchConfig(bound, row.configId, trial = true) { slots ->
                val out = LinkedHashMap(slots)
                out[CCT.traitDef] = slots[CCT.traitDef].orEmpty().map { if (it[CCT.traitId] == traitId) to else it }
                out
            }
            written = patch(existing.row, entry)
            reload = ClientStoredEdit.takeEffect(
                cxt, target, written,
                undo = { patch(written, existing.entry) },
                restorePublished = existing.row.isPublished,
                impact = impact,
            )
        }
        return Result(written.configId.baseId, keyed, reload.issues, target.mode)
    }
}
