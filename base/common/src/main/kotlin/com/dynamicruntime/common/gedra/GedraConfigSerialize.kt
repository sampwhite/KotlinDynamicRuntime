package com.dynamicruntime.common.gedra

import com.dynamicruntime.common.cfact.CFactDef
import com.dynamicruntime.common.context.KdrCxtBase
import com.dynamicruntime.common.exception.ACT
import com.dynamicruntime.common.exception.EXC
import com.dynamicruntime.common.exception.KdrException
import com.dynamicruntime.common.gedra.workflow.toJsonMap
import com.dynamicruntime.common.schema.SCH
import com.dynamicruntime.common.schema.qualifyTypeName
import com.dynamicruntime.common.schema.refTargetName
import com.dynamicruntime.common.util.toJsonListOfStrings
import com.dynamicruntime.common.util.toJsonMapOrEmpty
import com.dynamicruntime.common.util.toOptStr

/**
 * Turns a [GedraConfig] into the config-trait entries a config row stores, and reassembles one back (issue
 * #613). One slot per field of a config, keyed by the trait id #625's vocabulary defines; the round trip is
 * faithful, so a config authored in source and one loaded from a row are the same thing.
 *
 * ### Generated types are re-manufactured, not stored
 *
 * A config's `defs` mixes types a trait **generated** (its `<Name>Entry` and, for an inline trait, its
 * `<Name>Data`) with types it **declared directly** (`type(...)`). Only the directly-declared ones become
 * `schemaDef` entries; reassembly re-manufactures the generated ones by re-running the trait declarations. The
 * split is computed from the traits ([generatedTypeNames]) -- a type is generated when a trait's `typeName`
 * names it, or it is a trait's own inline data type -- so nothing has to track provenance as the config is
 * built. The same split decides, per trait, whether its stored `dataSchema` is the data type's **body** (inline
 * -- re-manufacture it) or a `$ref` (shared -- the target is a `schemaDef`).
 *
 * Pure over the model and the stored maps; the database write path (#633) and the boot loader (#614) are what
 * persist and consume what this produces.
 */
fun gedraConfigToEntries(config: GedraConfig): Map<String, List<Map<String, Any?>>> {
    // A stored config is a client's, and config traits are hardwired by the runtime (#316) -- never authored as
    // data, never on a row. One carrying them cannot be stored faithfully (its slots have no home and their
    // generated types would misfile as directly-declared ones), so refuse it here rather than silently corrupt
    // the round trip. In practice only `coreConfigTraits` itself has any, and it is not something anyone stores.
    if (config.configTraits.isNotEmpty()) {
        throw KdrException.mkConv(
            "Config '${config.gedraId}' declares config traits (${config.configTraits.keys}), which are hardwired " +
                "and cannot be stored on a row. Only a client configuration is stored.",
        )
    }
    // State traits likewise (issue #873): state is global, declared by components, and a stored config is always
    // a client's -- so there is no slot for one, and a config carrying one is refused rather than stored without.
    if (config.stateTraits.isNotEmpty()) {
        throw KdrException.mkInput(
            "Config '${config.gedraId}' declares state traits (${config.stateTraits.keys}). State is global and " +
                "declared by components, so a client's configuration cannot declare it.",
        )
    }
    val generated = generatedTypeNames(config)
    val out = linkedMapOf<String, List<Map<String, Any?>>>()
    config.client?.let { out[CCT.clientDef] = listOf(it.toInfo()) }
    out[CCT.traitDef] = config.traits.values.map { traitToEntry(config, it) }
    out[CCT.usageDef] = config.usages.map { usageToEntry(it) }
    out[CCT.workflowDef] = config.workflows.values.map {
        linkedMapOf(CCT.workflowId to it.workflowId, CCT.definition to it.toJsonMap())
    }
    out[CCT.reportDef] = config.reports.values.map {
        linkedMapOf(CCT.reportId to it.reportId, CCT.definition to it.toJsonMap())
    } + config.unreadReports.values.map {
        // Written back as stored, so an edit of anything else in the config does not delete it (issue #979).
        linkedMapOf(CCT.reportId to it.slotKey, CCT.definition to it.definition)
    }
    out[CCT.schemaDef] = config.defs.filterKeys { it !in generated }.map { (name, body) ->
        linkedMapOf(CCT.typeName to name, CCT.schema to body)
    }
    out[CCT.fragmentDef] = config.fragments.map {
        linkedMapOf(CCT.fileId to it.fileId, CCT.content to (it.load() ?: emptyMap<String, Any?>()))
    }
    out[CCT.uiBlockDef] = config.uiBlocks.map { linkedMapOf(CCT.blockId to it.blockId, CCT.content to it.content) }
    out[CCT.cfactDef] = config.cfacts.map { cfactToEntry(it) }
    // Drop empty slots so a row holds only the pieces the config actually has.
    return out.filterValues { it.isNotEmpty() }
}

/**
 * [reassembleGedraConfig] for a configuration arriving in a **write** (issues #1051, #1052): its slots pass
 * [configSlotFailures] first, and what the reassembly finds wrong with the body is the writer's -- a 400 --
 * rather than the server's.
 *
 * - A slot entry that fails its shape is refused with every failure: the message names each path and what is
 *   wrong there, and the failures travel structured under `extraData`, as a request's own input failures do.
 *   [stored] is the configuration's revision as it stands, when it has one: what the write carries unchanged is
 *   not judged again, so a fault the stored revision already has does not refuse an unrelated write.
 * - A **conversion** fault the reassembly throws -- a client id holding a colon, a workflow whose rules a schema
 *   cannot state -- is the same kind of mistake, made in the body rather than in a field, so it is rethrown as
 *   bad input: the fault itself kept as the cause, its extra data (a parser's code and position) carried up.
 *   Only those: a fault that is not marked a conversion is still a server error here.
 *
 * From stored rows a conversion fault stays what it is: nobody sent those.
 *
 * Every write path reassembles through this -- the bundle write, the import, and `patchConfig` -- and the load
 * does not, so a row a write once accepted still loads.
 */
fun reassembleForWrite(
    cxt: KdrCxtBase,
    name: String,
    namespace: String,
    client: String,
    entriesBySlot: Map<String, List<Map<String, Any?>>>,
    stored: Map<String, List<Map<String, Any?>>>? = null,
): GedraConfig {
    requireWritableSlots(cxt, name, entriesBySlot, stored)
    return try {
        reassembleGedraConfig(cxt, name, namespace, client, entriesBySlot)
    } catch (e: KdrException) {
        if (e.code != EXC.internalError || e.activity != ACT.conversion) throw e
        // The fault says what is wrong; this says whose it is. A reader of the error takes both from
        // `fullMessage`, which gathers the chain.
        throw KdrException(
            "Configuration '$name' cannot be written.", e, EXC.badInput, e.source, e.activity, LinkedHashMap(e.extraData),
        )
    }
}

/**
 * A [GedraConfig] reassembled from its stored [entriesBySlot] (issue #613): re-runs the `gedraConfig(...)`
 * builder so every generated type is manufactured exactly as source would, rather than stored and reloaded. The
 * identity comes from the row -- [name], [namespace] and [client] -- not from the entries.
 *
 * The reader of what is **stored**, so it is forgiving of every slot's shape: a missing field reads as an empty
 * one, a flag that is not a boolean as false, a kind that is no kind is left out. A write goes through
 * [reassembleForWrite], which holds the slots to their declared shapes first.
 */
fun reassembleGedraConfig(
    cxt: KdrCxtBase,
    name: String,
    namespace: String,
    client: String,
    entriesBySlot: Map<String, List<Map<String, Any?>>>,
): GedraConfig = gedraConfig(cxt, name, namespace, client, GedraConfigOrigin.stored) {
    entriesBySlot[CCT.clientDef]?.firstOrNull()?.let { defineClient(ClientDef.fromInfo(it)) }
    // Directly-declared types first, so a trait's shared `$ref` has its target present when the config compiles.
    entriesBySlot[CCT.schemaDef]?.forEach { e ->
        type(e[CCT.typeName].toOptStr().orEmpty()) { data.putAll(e[CCT.schema].toJsonMapOrEmpty()) }
    }
    entriesBySlot[CCT.traitDef]?.forEach { reassembleTrait(it) }
    entriesBySlot[CCT.usageDef]?.forEach { e ->
        traitUsage(
            e[CCT.traitId].toOptStr().orEmpty(),
            e[CCT.label].toOptStr().orEmpty(),
            e[CCT.display].toOptStr().orEmpty(),
            UsageKind.entries.firstOrNull { it.name == e[CCT.kind].toOptStr() } ?: UsageKind.string,
            e[CCT.substring] as? Boolean ?: false,
        )
    }
    entriesBySlot[CCT.workflowDef]?.forEach { workflowFromMap(it[CCT.definition].toJsonMapOrEmpty()) }
    // Forgiving, unlike the other slots: a report that no longer reads costs only itself (`GedraConfig.unreadReports`).
    entriesBySlot[CCT.reportDef]?.forEach {
        storedReportFromMap(it[CCT.definition].toJsonMapOrEmpty(), it[CCT.reportId].toOptStr())
    }
    entriesBySlot[CCT.fragmentDef]?.forEach { e ->
        fragmentOverlay(e[CCT.fileId].toOptStr().orEmpty(), fragmentContentOf(e[CCT.content]))
    }
    entriesBySlot[CCT.uiBlockDef]?.forEach { e ->
        uiBlockOverlay(e[CCT.blockId].toOptStr().orEmpty(), e[CCT.content].toJsonMapOrEmpty())
    }
    entriesBySlot[CCT.cfactDef]?.forEach { e ->
        cfact(
            e[CCT.name].toOptStr().orEmpty(),
            e[CCT.group].toOptStr().orEmpty(),
            e[CCT.description].toOptStr().orEmpty(),
            e[CCT.toFrontend] as? Boolean ?: false,
        )
    }
}

/** The qualified names of every type a config's traits **generate** -- the entry types, and the inline data types. */
private fun generatedTypeNames(config: GedraConfig): Set<String> {
    val out = linkedSetOf<String>()
    for (trait in config.traits.values) {
        out.add(trait.typeName)
        inlineDataTypeName(config, trait)?.let { out.add(it) }
    }
    return out
}

/**
 * The name of a trait's own **inline** data type, or null when its data is a shared `$ref` (issue #613). A
 * trait's `dataSchema` is always a `$ref`; it points at the generated `<Name>Data` when the data was written
 * inline, and at a directly-declared type when the trait named one -- and only the first is generated.
 */
internal fun inlineDataTypeName(config: GedraConfig, trait: GedraTrait): String? {
    val generatedDataName = qualifyTypeName(traitDataTypeName(trait.typeName), config.namespace)
    val refTarget = (trait.dataSchema[SCH.dRef] as? String)?.let { refTargetName(it) }
    return generatedDataName.takeIf { it == refTarget }
}

/**
 * One trait's stored declaration (issue #613): the DSL inputs, with `dataSchema` the data type's **body** when
 * the trait wrote it inline (so reassembly re-manufactures the type) or the `$ref` when it named a shared type.
 */
internal fun traitToEntry(config: GedraConfig, trait: GedraTrait): Map<String, Any?> = buildMap {
    // The shared metadata projection (issue #702), then this path's config-only extras. `omitEmptyPrimaryKey`
    // keeps the stored form as lean as it was -- an empty key is left out rather than written as `[]`.
    putAll(trait.toMetadataMap(omitEmptyPrimaryKey = true))
    // The description lives on the generated entry type, not on `GedraTrait` (`traitEntry` puts it there via
    // `variantBranch`), so read it back from there -- otherwise a store/load cycle strips a trait's docs.
    config.defs[trait.typeName].toJsonMapOrEmpty()[SCH.description].toOptStr()?.let { put(CCT.description, it) }
    val inlineName = inlineDataTypeName(config, trait)
    put(CCT.dataSchema, if (inlineName != null) config.defs[inlineName] ?: trait.dataSchema else trait.dataSchema)
}

// The full usage rule, `display` included -- the shared projection (issue #702).
private fun usageToEntry(usage: ClientTraitUsage): Map<String, Any?> = usage.toRuleMap()

private fun cfactToEntry(cfact: CFactDef): Map<String, Any?> = linkedMapOf(
    CCT.name to cfact.name,
    CCT.group to cfact.group,
    CCT.description to cfact.description,
    CCT.toFrontend to cfact.toFrontend,
)

/** Re-declares one stored trait (issue #613), feeding its stored `dataSchema` straight into the builder. */
private fun GedraConfigBuilder.reassembleTrait(entry: Map<String, Any?>) {
    val typeName = entry[CCT.typeName].toOptStr().orEmpty()
    val traitId = entry[CCT.traitId].toOptStr().orEmpty()
    val appliesTo = entry[CCT.appliesTo].toJsonListOfStrings()
        .mapNotNull { name -> GedraDataType.entries.firstOrNull { it.name == name } }.toSet()
    val primaryKey = entry[CCT.primaryKey].toJsonListOfStrings()
    val description = entry[CCT.description].toOptStr()
    val dataSchema = entry[CCT.dataSchema].toJsonMapOrEmpty()
    trait(typeName, traitId, appliesTo, description, primaryKey) { data.putAll(dataSchema) }
}

/** A stored fragment `content` (`namespace -> key -> value`) coerced back to the shape a `FragmentSource` holds. */
private fun fragmentContentOf(stored: Any?): Map<String, Map<String, String>> =
    stored.toJsonMapOrEmpty().mapValues { (_, ns) ->
        ns.toJsonMapOrEmpty().entries.associate { (k, v) -> k to v.toOptStr().orEmpty() }
    }
