package com.dynamicruntime.common.gedra

import com.dynamicruntime.common.context.KdrCxt
import com.dynamicruntime.common.endpoint.HttpMethod
import com.dynamicruntime.common.endpoint.SchModuleBuilder
import com.dynamicruntime.common.exception.KdrException
import com.dynamicruntime.common.schema.SCH
import com.dynamicruntime.common.schema.SCT
import com.dynamicruntime.common.schema.collectDefClosure
import com.dynamicruntime.common.schema.refName
import com.dynamicruntime.common.startup.SchemaService
import com.dynamicruntime.common.user.UADEP
import com.dynamicruntime.common.util.toJsonListOfStrings
import com.dynamicruntime.common.util.toJsonMapOrEmpty
import com.dynamicruntime.common.util.toOptStr

/**
 * Choosing the global traits a client includes (issue #1117): its definition's `includedTraits`, the one field whose
 * policy is a surface of its own ([ClientFieldEdit.ownSurface]) rather than the definition editor.
 *
 * ### What the catalog says
 *
 * Every global data trait, as the platform declares it -- its description and its fields, read-only -- whatever the
 * client has made of it, since that is what including it chooses. A trait the client has altered or extended is
 * marked [CIT.customized]: it is supported without being named, so including it or not changes nothing. Whether the
 * client may take a group such as [CLD.allGlobal] is said with it ([groupsRefusal]). For the traits editor (issue
 * #1118) it carries the client's own traits and its plan's limits too ([ClientTraitDesignEdit]).
 *
 * ### Where a save lands
 *
 * As a definition edit does ([ClientDefinitionEdit]): in the stored config holding the client's definition, patched
 * under its lock, then live at once -- or a draft for a client with a sandbox, which its publish makes live. Refused
 * for a client defined in source code and for a sandbox, in the same words.
 *
 * **Strict for what is added, forgiving of what is there.** An entry the save adds must name a global trait, or be
 * [CLD.allGlobal] for a client that may take it; an entry already in the list is kept as it is, so a stale one -- a
 * trait a code change removed, which the load already drops with an issue -- does not stop a save of something else.
 *
 * **Judged against the client's data.** Taking a trait away leaves the forms holding its entries with data the client
 * no longer supports, so a live save is refused with the impact report (issue #935) unless the caller acknowledges it,
 * as a Design View save removing a choice is (#1040). A draft is not judged: its publish is.
 */
object ClientTraitsEdit {
    /** What a save did: the config it landed in, the client's included traits as stored now, and how it took effect. */
    class Result(val configName: String, val included: List<String>, val issues: List<GedraConfigIssue>, val mode: String)

    /** The catalog of global traits for [client]'s picker -- see the class note -- as the catalog endpoint answers it. */
    fun catalog(cxt: KdrCxt, client: String): Map<String, Any?> {
        val schema = SchemaService.get(cxt)
        val served = schema.schemaStore.servedDefs
        val overlaid = schema.overlaidTypesFor(client)
        val seeds = LinkedHashSet<String>()
        val traits = schema.gedraTraitsFor(client).filter { schema.isGlobalTrait(it.traitId) }.map { trait ->
            // The data's `$ref`, as the workflow view reads it: always one, the author's own or the generated one.
            val dataRef = trait.dataSchema[SCH.dRef].toOptStr()
            dataRef?.let(::refName)?.let { seeds.add(it) }
            buildMap {
                put(CCT.traitId, trait.traitId)
                // The description lives on the generated entry type, not on the trait.
                served[trait.typeName].toJsonMapOrEmpty()[SCH.description].toOptStr()?.let { put(CCT.description, it) }
                put(CCT.appliesTo, trait.appliesTo.map { it.name })
                dataRef?.let { put(CIT.dataRef, it) }
                put(CIT.customized, trait.typeName in overlaid)
            }
        }
        return buildMap {
            put(CLD.client, client)
            put(CIT.traits, traits)
            put(SCH.dDefs, collectDefClosure(seeds, served))
            ClientService.get(cxt).known(client)?.let(::groupsRefusal)?.let { put(CIT.groupsRefused, it) }
            // The client's own traits, for the traits editor (issue #1118), and the plan's limits it is held to.
            put(CIT.ownTraits, ClientTraitDesignEdit.ownTraits(cxt, client))
            ClientTraitDesignEdit.quotas(cxt, client)?.let { put(CIT.quotas, it) }
        }
    }

    /** Why [def] may not include a group such as [CLD.allGlobal], or null when it may. */
    fun groupsRefusal(def: ClientDef): String? = if (def.mayIncludeGroups) null else {
        "Client '${def.clientId}' is a ${ClientAudience.customer} client in ${ClientUsageType.production}, so it " +
            "names each trait it includes: what it supports must be settled by its own definition, not by the traits " +
            "the platform adds later."
    }

    /**
     * Sets [client]'s included traits to [included] -- trait ids and [CLD.allGlobal], in the order to keep -- and makes
     * that take effect, live or as a draft; see the class note. [expected] is how the caller told its user the save
     * would take effect ([ClientStoredEdit.target]); [impact] whether a live save that affects stored data goes ahead.
     */
    fun set(cxt: KdrCxt, client: String, included: List<String>, expected: String? = null, impact: ImpactGate = ImpactGate.refuse): Result {
        sandboxParentOf(client)?.let { parent ->
            throw KdrException.mkInput(
                "'$client' is a sandbox: the traits it includes are its parent's, which are chosen on '$parent'.",
            )
        }
        if (ClientService.get(cxt).originOf(client) == GedraConfigOrigin.source) {
            throw KdrException.mkInput(
                "Client '$client' is defined in source code (its ClientDef), so the traits it includes are chosen " +
                    "there; a stored definition cannot override it.",
            )
        }
        val target = ClientStoredEdit.target(cxt, client, "traitsEdit", expected)
        val bound = target.bound
        val holder = ClientStoredEdit.definitionHolder(bound)
            ?: throw KdrException.mkInput("Client '$client' has no stored definition to include traits in.")
        ClientStoredEdit.requireNoForeignDraft(target, holder, "a change of its traits")
        val before = holder.entriesBySlot()[CCT.clientDef]?.firstOrNull().toJsonMapOrEmpty()
        val current = before[CLD.includedTraits].toJsonListOfStrings()
        val wanted = included.map { it.trim() }.filter { it.isNotEmpty() }.distinct()
        if (wanted == current) throw KdrException.mkInput("Client '$client' already includes exactly these traits; nothing to save.")
        for (entry in wanted.filterNot { it in current }) {
            addedEntryFault(cxt, client, entry)?.let { throw KdrException.mkInput(it) }
        }
        val written = ClientStoredEdit.patchDefinition(bound, holder) { def ->
            if (wanted.isEmpty()) def.remove(CLD.includedTraits) else def[CLD.includedTraits] = wanted
        }
        val reload = ClientStoredEdit.takeEffect(
            cxt, target, written,
            undo = { ClientStoredEdit.patchDefinition(bound, written) { def -> def.clear(); def.putAll(before) } },
            // The draft rule admitted only a published holder, so a refused publish puts it back as published.
            restorePublished = true,
            impact = impact,
        )
        return Result(written.configId.baseId, wanted, reload.issues, target.mode)
    }

    /** Why [client] may not add [entry] to its included traits, or null when it may. */
    private fun addedEntryFault(cxt: KdrCxt, client: String, entry: String): String? {
        if (entry.startsWith(CLD.groupSigil)) {
            if (entry != CLD.allGlobal) return "There is no group '$entry'; the one group is ${CLD.allGlobal}."
            return ClientService.get(cxt).known(client)?.let(::groupsRefusal)
        }
        val schema = SchemaService.get(cxt)
        if (schema.isGlobalTrait(entry)) return null
        if (schema.gedraTraitsFor(client).any { it.traitId == entry }) {
            return "'$entry' is client '$client''s own trait, which it supports without including it."
        }
        return "'$entry' is not a global trait."
    }
}

/** The traits editor's endpoints (issue #1117), in the clients module beside the definition editor's. */
internal fun SchModuleBuilder.clientTraitsEndpoints() {
    type(CIT.traitTypeName) {
        type = SCT.kObject
        description = "One global trait a client may include (issue #1117), as the platform declares it."
        property(CCT.traitId, "The trait's id, which the client's included traits name.", required = true)
        property(CCT.description, "What the trait is for.")
        property(CCT.appliesTo, "The kinds of gedra that may carry an entry of the trait.", required = true) {
            type = SCT.array
            items { type = SCT.string }
        }
        property(CIT.dataRef, $$"The schema reference of the trait's data -- its fields -- into the catalog's $defs.")
        property(
            CIT.customized,
            "Whether the client alters or extends the trait, which makes it supported without being included.",
            required = true,
        ) { type = SCT.boolean }
    }

    type(CIT.designTypeName) {
        type = SCT.kObject
        description = "A client's own trait as the traits editor designs it (issue #1118): a name, a description, and " +
            "fields of a fixed set of kinds. A trait, field or choice with no key yet is new, and gets one from its label."
        property(CIT.traitId, "The trait's id; absent for a new trait, which gets one from its name.")
        property(CIT.label, "The trait's name, shown as its heading.", required = true)
        property(CIT.description, "What the trait is for.")
        property(CIT.fields, "The fields, in the order a form shows them.", required = true) {
            type = SCT.array
            items {
                type = SCT.kObject
                property(CIT.key, "The field's key; absent for a new field, which gets one from its label. It never changes.")
                property(CIT.label, "The field's label.", required = true)
                property(CIT.kind, "What the field holds.", required = true) { TraitFieldKind.entries.forEach { option(it.name) } }
                property(CIT.description, "What the field asks for.")
                property(CIT.required, "Whether the data requires it, whoever writes an entry.") { type = SCT.boolean }
                property(CIT.choices, "A choice field's choices, in order.") {
                    type = SCT.array
                    items {
                        type = SCT.kObject
                        property(CIT.value, "The choice's stored value; absent for a new one. It never changes.")
                        property(CIT.label, "The choice's label.", required = true)
                    }
                }
            }
        }
    }

    type(CIT.ownTraitTypeName) {
        type = SCT.kObject
        description = "One of the client's own traits (issue #1118): where it is stored, and its design or why the " +
            "editor cannot edit it."
        property(CIT.traitId, "The trait's id.", required = true)
        property(CIT.configName, "The stored configuration holding it; absent for one declared in source code.")
        property(CIT.design, "The trait as the editor designs it; absent when it cannot.") { ref(CIT.designTypeName) }
        property(CIT.notEditable, "Why the editor cannot edit it; absent when it can.")
    }

    type(CIT.quotasTypeName) {
        type = SCT.kObject
        description = "The plan's limits a trait's design is held to (issue #1109)."
        property(CIT.plan, "The client's plan.", required = true)
        property(CIT.schemasUsed, "How many schemas the client declares now.") { type = SCT.integer }
        property(CIT.schemasLimit, "How many schemas its plan allows.") { type = SCT.integer }
        property(CIT.attributesLimit, "How many attributes one schema may have.") { type = SCT.integer }
    }

    type(CIT.catalogTypeName) {
        type = SCT.kObject
        description = "The global traits a client may include (issue #1117), with the schema of their fields."
        property(CLD.client, "The client.", required = true)
        property(CIT.traits, "Every global data trait, in declaration order.", required = true) {
            type = SCT.array
            items { ref(CIT.traitTypeName) }
        }
        property(SCH.dDefs, "The types the traits' fields are declared by, keyed by name, as the platform declares them.", required = true) {
            type = SCT.kObject
        }
        property(CIT.groupsRefused, "Why the client may not include a group such as ${CLD.allGlobal}; absent when it may.")
        property(CIT.ownTraits, "The client's own traits (issue #1118), in the order they are stored.", required = true) {
            type = SCT.array
            items { ref(CIT.ownTraitTypeName) }
        }
        property(CIT.quotas, "The plan's limits a trait's design is held to (issue #1109); absent for a client on no plan.") {
            ref(CIT.quotasTypeName)
        }
    }

    itemEndpoint(
        UADEP.clientTraits,
        "The global traits a client may include (issue #1117): each with its description, the kinds of gedra it " +
            "applies to, and its fields as the platform declares them, with whether the client customizes it -- and " +
            "whether the client may include ${CLD.allGlobal}. The caller's own client, or one an administrator who " +
            "sees every client names.",
        HttpMethod.GET,
        outputRef = CIT.catalogTypeName,
        inputFields = { overseenClientField(CLD.client, "The client to choose traits for; the caller's own when absent.") },
        needsClientConfig = true,
    ) { c, request ->
        ClientTraitsEdit.catalog(c, overseenClient(c, request[CLD.client].toOptStr()))
    }

    type(CIT.setResultTypeName) {
        type = SCT.kObject
        description = "What a change of a client's included traits did (issue #1117)."
        property(CLD.client, "The client.", required = true)
        property(COV.configName, "The stored configuration the change landed in.", required = true)
        property(CLD.includedTraits, "The traits the client's stored definition includes now.", required = true) {
            type = SCT.array
            items { type = SCT.string }
        }
        saveOutcomeFields()
    }

    generalEndpoint(
        UADEP.clientTraitsSet,
        "Sets the traits a client includes (issue #1117): global trait ids, and ${CLD.allGlobal} for a client that may " +
            "take it, as the whole list in the order to keep. An entry the list adds must be one of those; one already " +
            "there is kept as it is. Written to the client's stored definition under the config lock, refused when a " +
            "trial reload finds a new problem, then published and reloaded -- refused, with the impact report, when " +
            "that would leave forms the client stores holding a trait it no longer supports, unless the impact is " +
            "acknowledged. For a client with a Shadow Sandbox (issue #930) saved as a draft instead, judged when it " +
            "is published. Refused for a client defined in source code, for a sandbox (its traits are its parent's), " +
            "and while the definition's configuration has somebody's unpublished changes, which a live save would " +
            "publish.",
        HttpMethod.POST,
        outputRef = CIT.setResultTypeName,
        needsClientConfig = true,
        inputFields = {
            overseenClientField(CLD.client)
            field(CLD.includedTraits, "The trait ids, and groups, the client is to include; empty includes none.", required = true) {
                type = SCT.array
                items { type = SCT.string }
            }
            field(
                IMP.acknowledgeImpact,
                "Save although forms the client stores hold entries of a trait it would no longer support; without " +
                    "it, such a save is refused with the impact report.",
            ) { type = SCT.boolean }
            expectedModeField()
        },
    ) { c, request ->
        val client = overseenClient(c, request[CLD.client].toOptStr())
        val gate = if (request[IMP.acknowledgeImpact] == true) ImpactGate.acknowledged else ImpactGate.refuse
        val result = ClientTraitsEdit.set(c, client, request[CLD.includedTraits].toJsonListOfStrings(), expectedModeOf(request), gate)
        mapOf(
            CLD.client to client,
            COV.configName to result.configName,
            CLD.includedTraits to result.included,
            CPY.mode to result.mode,
            CPY.issues to result.issues.map { it.toWireMap() },
        )
    }

    type(CIT.designResultTypeName) {
        type = SCT.kObject
        description = "What saving a trait's design did (issue #1118)."
        property(CLD.client, "The client.", required = true)
        property(CIT.configName, "The stored configuration the trait is in.", required = true)
        property(CIT.design, "The trait as designed now, every key given.", required = true) { ref(CIT.designTypeName) }
        saveOutcomeFields()
    }

    generalEndpoint(
        UADEP.clientTraitDesign,
        "Creates or edits one of a client's own traits from a design (issue #1118). A design naming no trait makes a " +
            "new one, in a stored configuration of its own; one naming a stored trait edits it where it is stored. Keys " +
            "are made from first labels and never change after, and a kept field keeps its kind. Written under the " +
            "config lock, refused when a trial reload finds a new problem -- the plan's limits on schemas and " +
            "attributes among them -- then published and reloaded; for a client with a Shadow Sandbox saved as a " +
            "draft instead. An edit that would leave stored forms holding data the trait no longer accepts is refused " +
            "with the impact report unless the impact is acknowledged. Refused for a sandbox (its traits are its " +
            "parent's) and for a trait declared in source code.",
        HttpMethod.POST,
        outputRef = CIT.designResultTypeName,
        needsClientConfig = true,
        inputFields = {
            overseenClientField(CLD.client)
            field(CIT.design, "The trait's design.", required = true) { ref(CIT.designTypeName) }
            field(
                IMP.acknowledgeImpact,
                "Save although forms the client stores hold data the edited trait would no longer accept; without it, " +
                    "such a save is refused with the impact report.",
            ) { type = SCT.boolean }
            expectedModeField()
        },
    ) { c, request ->
        val client = overseenClient(c, request[CLD.client].toOptStr())
        val gate = if (request[IMP.acknowledgeImpact] == true) ImpactGate.acknowledged else ImpactGate.refuse
        val design = try {
            traitDesignFromInfo(request[CIT.design].toJsonMapOrEmpty())
        } catch (e: IllegalArgumentException) {
            throw KdrException.mkInput(e.message ?: "The design does not read.")
        }
        val result = ClientTraitDesignEdit.save(c, client, design, expectedModeOf(request), gate)
        mapOf(
            CLD.client to client,
            CIT.configName to result.configName,
            CIT.design to result.design.toInfo(),
            CPY.mode to result.mode,
            CPY.issues to result.issues.map { it.toWireMap() },
        )
    }
}
