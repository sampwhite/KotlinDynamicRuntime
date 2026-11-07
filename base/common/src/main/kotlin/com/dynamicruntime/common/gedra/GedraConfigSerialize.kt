package com.dynamicruntime.common.gedra

import com.dynamicruntime.common.cfact.CFactDef
import com.dynamicruntime.common.context.KdrCxtBase
import com.dynamicruntime.common.exception.ACT
import com.dynamicruntime.common.exception.EXC
import com.dynamicruntime.common.exception.KdrException
import com.dynamicruntime.common.gedra.workflow.CTAK
import com.dynamicruntime.common.gedra.workflow.WFD
import com.dynamicruntime.common.gedra.workflow.WorkflowTemplates
import com.dynamicruntime.common.gedra.workflow.toJsonMap
import com.dynamicruntime.common.schema.SCH
import com.dynamicruntime.common.schema.qualifyTypeName
import com.dynamicruntime.common.schema.refTargetName
import com.dynamicruntime.common.util.toJsonListOfMaps
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
 * persist and consume what this produces. With [shownAsRun], a workflow made from a template is given as it expands,
 * and config and state traits, which have no slot, are left out rather than refused, since what is shown is what runs
 * rather than what would be stored (issue #1616): the bundle editor's code layer.
 */
fun gedraConfigToEntries(config: GedraConfig, shownAsRun: Boolean = false): Map<String, List<Map<String, Any?>>> {
    // A stored config is a client's, and config traits are hardwired by the runtime (#316) -- never authored as
    // data, never on a row. One carrying them cannot be stored faithfully (its slots have no home and their
    // generated types would misfile as directly-declared ones), so refuse it here rather than silently corrupt
    // the round trip. In practice only `coreConfigTraits` itself has any, and it is not something anyone stores.
    if (config.configTraits.isNotEmpty() && !shownAsRun) {
        throw KdrException.mkConv(
            "Config '${config.gedraId}' declares config traits (${config.configTraits.keys}), which are hardwired " +
                "and cannot be stored on a row. Only a client configuration is stored.",
        )
    }
    // State traits likewise (issue #873): state is global, declared by components, and a stored config is always
    // a client's -- so there is no slot for one, and a config carrying one is refused rather than stored without.
    if (config.stateTraits.isNotEmpty() && !shownAsRun) {
        throw KdrException.mkInput(
            "Config '${config.gedraId}' declares state traits (${config.stateTraits.keys}). State is global and " +
                "declared by components, so a client's configuration cannot declare it.",
        )
    }
    // Shown as run, the types a state or config trait generates are not directly declared ones either.
    val leftOut = if (!shownAsRun) emptySet() else {
        config.stateTraits.values.flatMap { listOfNotNull(it.typeName, inlineDataTypeName(config, it)) } +
            config.configTraits.values.map { it.typeName }
    }
    val generated = generatedTypeNames(config) + leftOut
    val out = linkedMapOf<String, List<Map<String, Any?>>>()
    config.client?.let { out[CCT.clientDef] = listOf(it.toInfo()) }
    out[CCT.customerDef] = config.customers.map { it.toInfo() }
    out[CCT.traitDef] = config.traits.values.map { traitToEntry(config, it) }
    out[CCT.usageDef] = config.usages.map { usageToEntry(it) }
    // An overlay of the code is stored as written (issue #1657), never as the definition it merged into.
    val overlaid = config.codeOverlays.filter { it.applied }.map { it.slot to it.key }.toSet()
    out[CCT.workflowDef] = config.workflows.values.filter { (CCT.workflowDef to it.workflowId) !in overlaid }.map {
        // One made from a template is stored as its overlay of the template (issue #1120), never as what it expands to:
        // read back, an expanded definition naming its template would be taken for an overlay, and refused.
        val stored = when {
            shownAsRun -> it.toJsonMap()
            it.templateOverlay != null -> it.templateOverlay
            it.fromTemplate != null -> throw KdrException.mkConv(
                "Workflow '${it.workflowId}' is made from template '${it.fromTemplate}' and carries no overlay to store; " +
                    "store what `workflowTemplateOverlay` makes of it.",
            )
            else -> it.toJsonMap()
        }
        linkedMapOf(CCT.workflowId to it.workflowId, CCT.definition to stored)
    } + config.unreadWorkflows.values.map {
        // Written back as stored, so an edit of anything else in the config does not delete it (issue #1120).
        linkedMapOf(CCT.workflowId to it.workflowId, CCT.definition to it.definition)
    }
    out[CCT.reportDef] = config.reports.values.map {
        linkedMapOf(CCT.reportId to it.reportId, CCT.definition to it.toJsonMap())
    } + config.unreadReports.values.map {
        // Written back as stored, so an edit of anything else in the config does not delete it (issue #979).
        linkedMapOf(CCT.reportId to it.slotKey, CCT.definition to it.definition)
    }
    out[CCT.schemaDef] = config.defs.filterKeys { it !in generated && (CCT.schemaDef to it) !in overlaid }.map { (name, body) ->
        linkedMapOf(CCT.typeName to name, CCT.schema to body)
    } + config.shadowedTypes.values.map {
        // Written back as stored, so an edit of anything else in the config does not delete it (issue #1542).
        linkedMapOf(CCT.typeName to it.typeName, CCT.schema to it.schema)
    }
    out[CCT.fragmentDef] = config.fragments.map {
        linkedMapOf(CCT.fileId to it.fileId, CCT.content to (it.load() ?: emptyMap<String, Any?>()))
    }
    out[CCT.uiBlockDef] = config.uiBlocks.map { linkedMapOf(CCT.blockId to it.blockId, CCT.content to it.content) }
    out[CCT.cfactDef] = config.cfacts.map { cfactToEntry(it) }
    // One entry an id: a slot holds one entry per key, and a row edited by hand to hold an id twice would otherwise
    // be unwritable until one was removed. The first is the one a load keeps, so it is the one written back.
    out[CCT.fileRefDef] = config.fileRefs.distinctBy { it.refId }.map { it.toEntry() }
    config.listing?.let { out[CCT.listingDef] = listOf(it.toEntry()) }
    // Each entry the config marks to stand over code (issue #1634) carries the mark, as the stored entry says it.
    if (!config.overridesCode.isEmpty) markOverCode(out, config.overridesCode)
    // And each overlay of the code as stored, marked as it was (issue #1657), whether or not it read over the code.
    for (overlay in config.codeOverlays) out[overlay.slot] = out[overlay.slot].orEmpty() + overlay.entry
    // Drop empty slots so a row holds only the pieces the config actually has.
    return out.filterValues { it.isNotEmpty() }
}

/** Marks each entry of [out] that [keys] hold, by its slot's key, `"overrides": "code"` (issue #1634). */
private fun markOverCode(out: MutableMap<String, List<Map<String, Any?>>>, keys: ConfigKeys) {
    fun marked(slot: String, holds: (Map<String, Any?>) -> Boolean) {
        out[slot]?.let { entries -> out[slot] = entries.map { if (holds(it)) it + (CCT.overrides to CCT.overridesCodeValue) else it } }
    }
    marked(CCT.traitDef) { it[CCT.traitId] in keys.traits }
    marked(CCT.schemaDef) { it[CCT.typeName] in keys.types }
    marked(CCT.cfactDef) { it[CCT.name] in keys.cfacts }
    marked(CCT.workflowDef) { it[CCT.workflowId] in keys.workflows }
    marked(CCT.reportDef) { it[CCT.reportId] in keys.reports }
    marked(CCT.fileRefDef) { it[CCT.refId] in keys.fileRefs }
    if (keys.usages) marked(CCT.usageDef) { true }
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
 * Every write path reassembles through this -- the bundle write, the import, and `patchConfigs` -- and the load
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
    // A workflow made from a template that no longer reads is kept as stored when the write carries it unchanged, and
    // refused when the write adds or changes it (issue #1120).
    val storedWorkflows = stored?.get(CCT.workflowDef).orEmpty().map { it[CCT.definition] }
    return try {
        reassembleGedraConfig(cxt, name, namespace, client, entriesBySlot, code = null) { raw -> raw in storedWorkflows }
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
 * [definition] with any time windows it was stored with taken out (issue #1356), when it is kept as stored -- every
 * definition a load reads, and one a write carries unchanged ([keepAsStored]). Windows were part of a workflow's
 * definition until they became its parameters, and a definition saved then keeps its workflow and loses only its
 * windows, which are now set on the client's page. A write that adds or changes windows is not kept so: a definition
 * naming them is refused there.
 */
private fun withoutStoredWindows(definition: Map<String, Any?>, keepAsStored: Boolean): Map<String, Any?> {
    val windowKeys = setOf(WFD.lifetime, WFD.relevancy, WFD.engagement)
    if (definition.keys.none { it in windowKeys } || !keepAsStored) return definition
    return definition.filterKeys { it !in windowKeys }
}

/**
 * [definition] without the singleton-cfact rules it was stored with for a status a search keyword sets (issue #1596),
 * when it is kept as stored, as [withoutStoredWindows] takes out windows; the cfacts taken out go to [dropped]. A
 * workflow said Needs Review with such a rule until the `review` keyword became the one way to. Like windows, the rule
 * is gone for good: a write carrying the definition unchanged stores it without the rule. The keyword stands in for it
 * only where a task carries it -- an approval task does already, and a data task can be given it. A write that adds or
 * changes such a rule is not kept so: the workflow's own check refuses it there, saying to give the task the keyword.
 */
private fun withoutKeywordStatusSingletons(
    definition: Map<String, Any?>,
    keepAsStored: Boolean,
    dropped: (cfacts: List<String>) -> Unit,
): Map<String, Any?> {
    if (!keepAsStored) return definition
    val (stale, kept) = definition[WFD.singletons].toJsonListOfMaps().partition { it[WFD.cfact].toOptStr() in CTAK.statusCfacts }
    if (stale.isEmpty()) return definition
    dropped(stale.mapNotNull { it[WFD.cfact].toOptStr() })
    return if (kept.isEmpty()) definition - WFD.singletons else definition + (WFD.singletons to kept)
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
    /**
     * The client's code, which its entries marked `"overrides": "codeOverlay"` are read over (issue #1657): what a load
     * passes. Null for a write's reassembly, which keeps each overlay as written; the write's trial reads them over the
     * code, as the load will.
     */
    code: CodeEntries? = null,
    /**
     * Whether a workflow made from a template that does not read is kept as unread, given its stored definition (issue
     * #1120): all of them for a load; for a write, only what it carries unchanged ([reassembleForWrite]).
     */
    keepUnreadWorkflow: (Map<String, Any?>) -> Boolean = { true },
): GedraConfig = gedraConfig(cxt, name, namespace, client, GedraConfigOrigin.stored) {
    // A workflow made from a template is read over the deployment's template (issue #1120).
    workflowTemplates = WorkflowTemplates.lookup(cxt)
    this.keepUnreadWorkflow = keepUnreadWorkflow
    entriesBySlot[CCT.clientDef]?.firstOrNull()?.let { defineClient(ClientDef.fromInfo(it)) }
    entriesBySlot[CCT.customerDef]?.forEach { defineCustomer(CustomerDef.fromInfo(it)) }
    // Directly-declared types first, so a trait's shared `$ref` has its target present when the config compiles.
    entriesBySlot[CCT.schemaDef]?.forEach { e ->
        // An overlay of a code type (issue #1657) is read over the code's.
        if (e.isCodeOverlay) return@forEach codeOverlay(CCT.schemaDef, e, code)
        type(e[CCT.typeName].toOptStr().orEmpty()) { data.putAll(e[CCT.schema].toJsonMapOrEmpty()) }
    }
    entriesBySlot[CCT.traitDef]?.forEach { reassembleTrait(it) }
    entriesBySlot[CCT.usageDef]?.forEach { e ->
        val usage = usageFromRuleMap(e)
        traitUsage(usage.traitId, usage.label, usage.display, usage.kind, usage.substring, usage.presentation, usage.columnId)
    }
    entriesBySlot[CCT.workflowDef]?.forEach { e ->
        // An overlay of a code workflow (issue #1657) is read over the code's.
        if (e.isCodeOverlay) return@forEach codeOverlay(CCT.workflowDef, e, code)
        val stored = e[CCT.definition].toJsonMapOrEmpty()
        val keepAsStored = keepUnreadWorkflow(stored)
        val definition = withoutKeywordStatusSingletons(withoutStoredWindows(stored, keepAsStored), keepAsStored) { cfacts ->
            droppedSingletons[stored[WFD.workflowId].toOptStr() ?: "?"] = cfacts
        }
        workflowFromMap(definition)
    }
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
    // Forgiving, as a report is: a ref that is no good costs only itself, and it is the load that says so.
    entriesBySlot[CCT.fileRefDef]?.forEach { fileRefFromMap(it) }
    // One entry (issue #1599), read leniently: a value this release does not know is the default.
    entriesBySlot[CCT.listingDef]?.firstOrNull()?.let { listing(ListingDef.fromEntry(it)) }
}.let { config ->
    // What its entries mark to stand over code (issue #1634): read here, so a write stores the marks it was given and a
    // load reads them back.
    val marked = CodeOverrides.markedKeys(config, entriesBySlot)
    if (marked.isEmpty) config else config.copy(overridesCode = marked)
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
    // The shared metadata projection (issue #702), then this path's config-only extras. A key rides in the data
    // type's body, as its `g-primaryKey` (issue #1521).
    putAll(trait.toMetadataMap())
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
    val description = entry[CCT.description].toOptStr()
    val dataSchema = entry[CCT.dataSchema].toJsonMapOrEmpty()
    trait(typeName, traitId, appliesTo, description) { data.putAll(dataSchema) }
}

/** A stored fragment `content` (`namespace -> key -> value`) coerced back to the shape a `FragmentSource` holds. */
private fun fragmentContentOf(stored: Any?): Map<String, Map<String, String>> =
    stored.toJsonMapOrEmpty().mapValues { (_, ns) ->
        ns.toJsonMapOrEmpty().entries.associate { (k, v) -> k to v.toOptStr().orEmpty() }
    }
