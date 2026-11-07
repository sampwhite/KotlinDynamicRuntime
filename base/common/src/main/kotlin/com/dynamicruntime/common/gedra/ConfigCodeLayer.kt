package com.dynamicruntime.common.gedra

import com.dynamicruntime.common.gedra.workflow.WFD
import com.dynamicruntime.common.gedra.workflow.WfEntry
import com.dynamicruntime.common.startup.SchemaCollector
import com.dynamicruntime.common.util.toJsonMapOrEmpty
import com.dynamicruntime.common.util.toOptStr

/**
 * One entry of a client's that replaces one of its code's, whole (issues #1616, #1646): the entry is in the client's
 * configuration [name], in [slot], with the key fields [key]; what it replaces is [codeEntries], in the code bundle the
 * editor lists as [codeName] -- the client's own code's ([origin] [CFEP.originCode]) or a copy of its [template]'s
 * ([CFEP.originTemplate], named `from_<template>_<name>`). An entry replaces by its key, a creation or core details
 * workflow by its kind, and the columns of the forms list as one set ([key] empty, every code column listed).
 *
 * A stored entry replaces only when it is [marked] `"overrides": "code"` (issue #1634), or `"codeOverlay"`, holding only
 * its change of the code's entry (issue #1657); one that is not is a write's refusal and an issue at load, and is listed
 * here so the page can say so. The client's own code over its template's needs no mark, and is [marked] as standing over
 * it.
 */
class CodeReplacement(
    val name: String,
    val slot: String,
    val key: Map<String, Any?>,
    val codeName: String,
    val origin: String,
    val template: String?,
    val codeEntries: List<Map<String, Any?>>,
    val marked: Boolean,
    /** Whether the client's entry is an overlay of the code's, holding only its change (issue #1657). */
    val overlay: Boolean = false,
) {
    fun toMap(): Map<String, Any?> = buildMap {
        put(CFEP.name, name)
        put(CFEP.slot, slot)
        put(CFEP.key, key)
        put(CFEP.codeName, codeName)
        put(CFEP.origin, origin)
        template?.let { put(CFEP.template, it) }
        put(CFEP.codeEntries, codeEntries)
        put(CFEP.marked, marked)
        put(CFEP.overlay, overlay)
    }

    /**
     * What it replaces, wherever the client's entry stands: two the same are one replacement, before and after a patch,
     * so an entry moved to another of the client's configurations is not a new one.
     */
    val address: List<Any?> get() = listOf(slot, key, codeName)
}

/** A configuration's slots, as a bundle holds them. */
typealias ConfigSlots = Map<String, List<Map<String, Any?>>>

/**
 * A client's code layer, read once for a request (issues #1616, #1646): its code configurations **as declared**, with
 * their slots ([configs]: its own code's, then a copy of each of its template's, nothing cut by what stands over them),
 * so the editor shows each code bundle whole and marks what is changed. Built once because making slots of a
 * configuration loads its copy files, and a request asks of it more than once. [kindOf] answers a workflow entry's kind
 * ([ConfigCodeLayer.workflowKind]).
 */
class CodeLayer(
    val configs: List<Pair<GedraConfig, ConfigSlots>>,
    private val kindOf: (Map<String, Any?>) -> WfEntry?,
) {
    /**
     * Every entry that replaces one of the code's: each in [stored] -- the client's configurations' latest revisions, by
     * name, so a draft is judged as it would run once published -- over any code entry, and the client's own code over
     * its template's. [keys] are each slot's key fields.
     */
    fun replacements(stored: List<Pair<String, ConfigSlots>>, keys: Map<String, List<String>>): List<CodeReplacement> {
        val code = configs.map { (c, slots) -> CodeSide(c.name, c.originWord, c.inheritedFrom, slots) }
        val ownCode = configs.filter { it.first.inheritedFrom == null }.map { (c, slots) -> OverSide(c.name, slots, stored = false) }
        val copies = code.filter { it.template != null }
        return ConfigCodeLayer.replacements(code, stored.map { (n, slots) -> OverSide(n, slots, stored = true) }, keys, kindOf) +
            ConfigCodeLayer.replacements(copies, ownCode, keys, kindOf)
    }

    /** The layer as the editor shows it ([ConfigCodeLayer.bundleOf]): each bundle whole, as declared. */
    fun bundles(isTestInstance: Boolean): List<Map<String, Any?>> =
        configs.map { (config, slots) -> ConfigCodeLayer.bundleOf(config, ConfigCodeLayer.redacted(slots, isTestInstance)) }
}

/** A code bundle as the replacements are judged against it: as the editor lists it, its origin, and its slots. */
class CodeSide(val name: String, val origin: String, val template: String?, val slots: ConfigSlots)

/** A configuration of the client's whose entries may stand over code: stored, or the client's own code. */
class OverSide(val name: String, val slots: ConfigSlots, val stored: Boolean)

/** Where a code configuration comes from, as a code bundle says it: the client's own code, or its template's. */
private val GedraConfig.originWord: String get() = if (inheritedFrom != null) CFEP.originTemplate else CFEP.originCode

/**
 * The **code layer** of a client's configuration (issues #1616, #1646), as the bundle editor shows it: what the client
 * runs that no row stores, as declared, and which of the client's entries replace an entry of it.
 *
 * The layer is two kinds of configuration:
 *
 * - **The client's own code:** each configuration a component declares for it, as declared -- the load cuts out what a
 *   marked stored entry stands over (`CodeOverrides`), but the editor shows the code whole and marks the entry.
 * - **Its template's copies** (`ClientDef.extendsFromClientId`, issue #945): the template's code configurations, cloned
 *   under the client, whole here too.
 *
 * A **replacement** is told by the keys [ConfigKeys] states, read off entries rather than models, so a draft in the
 * editor is judged as it would run once published: traits, types, cfacts, workflows, reports, and file refs by their
 * keys; a creation or core details workflow by its kind as well; and the columns of the forms list as one set. Copy and
 * interface are layered key by key rather than replaced, so they are never one.
 */
object ConfigCodeLayer {
    /** The slots whose entries replace a code entry by their key fields. */
    private val keyedSlots = listOf(CCT.traitDef, CCT.schemaDef, CCT.cfactDef, CCT.workflowDef, CCT.reportDef, CCT.fileRefDef)

    /**
     * [client]'s code layer, read once ([CodeLayer]), as declared: [ownCode], its own code configurations as the loader
     * holds them declared (`GedraConfigLoadService.ownSourceOf`), then a whole copy of each of [templateCode], the code of
     * [template], the client it extends, when it extends one.
     */
    fun read(
        collector: SchemaCollector,
        client: String,
        ownCode: List<GedraConfig>,
        template: String?,
        templateCode: List<GedraConfig>,
    ): CodeLayer {
        val copies = if (template == null) emptyList()
        else ClientExtension.clones(client, template, templateCode, emptyList(), GedraConfigOrigin.source)
        return CodeLayer((ownCode + copies).map { it to slotsOf(it) }) { workflowKind(collector, it) }
    }

    /** A code configuration as the editor shows it: its name, where it comes from, its namespace, and its [slots]. */
    fun bundleOf(config: GedraConfig, slots: ConfigSlots): Map<String, Any?> = buildMap {
        put(CFEP.name, config.name)
        put(CFEP.origin, if (config.inheritedFrom != null) CFEP.originTemplate else CFEP.originCode)
        config.inheritedFrom?.let { put(CFEP.template, it) }
        put(CFEP.namespaceField, config.namespace)
        put(CFEP.slots, slots)
    }

    /**
     * [config]'s slots, each workflow as it runs. State and config traits have no slot, so they are left out rather than
     * refused, as a stored configuration's would be: the rest of what the configuration declares is still shown.
     */
    fun slotsOf(config: GedraConfig): ConfigSlots = gedraConfigToEntries(config, shownAsRun = true)

    /** [slots] as they may be shown: the client's definition loses `testFeatures` off a test instance (issue #696). */
    fun redacted(slots: ConfigSlots, isTestInstance: Boolean): ConfigSlots = if (isTestInstance) slots else {
        slots.mapValues { (slot, entries) -> if (slot != CCT.clientDef) entries else entries.map { it - CLD.testFeatures } }
    }

    /**
     * The entries of [over] -- the client's configurations by name, stored or its own code -- that replace one of [code],
     * code bundles as the editor lists them. [keys] are each slot's key fields, and [kindOf] answers a workflow entry's
     * kind (a creation or core details workflow replaces the code's of that kind, whatever its id).
     */
    fun replacements(
        code: List<CodeSide>,
        over: List<OverSide>,
        keys: Map<String, List<String>>,
        kindOf: (Map<String, Any?>) -> WfEntry?,
    ): List<CodeReplacement> {
        val out = mutableListOf<CodeReplacement>()
        // Whole, or as an overlay of the code's entry (issue #1657).
        fun marked(side: OverSide, entry: Map<String, Any?>) =
            !side.stored || entry[CCT.overrides] == CCT.overridesCodeValue || entry.isCodeOverlay
        for (slot in keyedSlots) {
            val keyFields = keys[slot].orEmpty().ifEmpty { continue }
            val theirs = code.flatMap { side -> side.slots[slot].orEmpty().map { side to it } }
            if (theirs.isEmpty()) continue
            for (side in over) {
                for (entry in side.slots[slot].orEmpty()) {
                    val byKey = theirs.firstOrNull { addressesEntry(keyFields, it.second, entry) }
                    // A creation or core details workflow of the client's replaces the code's of that kind, by another id
                    // too: a client has one answer to how a form is created, or how its details are kept.
                    val byKind = if (byKey != null || slot != CCT.workflowDef) null else {
                        kindOf(entry)?.takeIf { it in WfEntry.singletons }?.let { kind -> theirs.firstOrNull { kindOf(it.second) == kind } }
                    }
                    val (codeSide, replaced) = byKey ?: byKind ?: continue
                    out += CodeReplacement(
                        side.name, slot, keyFields.associateWith { entry[it] }, codeSide.name, codeSide.origin, codeSide.template,
                        listOf(replaced), marked(side, entry), overlay = side.stored && entry.isCodeOverlay,
                    )
                }
            }
        }
        // The columns of the forms list are one set: a configuration that declares any declares all of them, so each one
        // holding some is part of what replaces the code's.
        val theirColumns = code.flatMap { side -> side.slots[CCT.usageDef].orEmpty().map { side to it } }
        if (theirColumns.isNotEmpty()) {
            val first = theirColumns.first().first
            for (side in over) {
                val columns = side.slots[CCT.usageDef].orEmpty().ifEmpty { continue }
                out += CodeReplacement(
                    side.name, CCT.usageDef, emptyMap(), first.name, first.origin, first.template, theirColumns.map { it.second },
                    columns.any { marked(side, it) },
                )
            }
        }
        return out
    }

    /**
     * A workflow entry's kind: its definition's own `entry`, or, for one stored as an overlay of a global template
     * (`fromTemplate`, issue #1120), the template's, found among [collector]'s code workflows.
     */
    fun workflowKind(collector: SchemaCollector, entry: Map<String, Any?>): WfEntry? {
        val definition = entry[CCT.definition].toJsonMapOrEmpty()
        val named = definition[WFD.entry].toOptStr()
            ?: definition[WFD.fromTemplate].toOptStr()?.let { id ->
                val workflows = collector.gedraConfigs.configs.asSequence().flatMap { it.workflows.values }
                workflows.firstOrNull { it.workflowId == id }?.entry?.name
            }
        return WfEntry.entries.firstOrNull { it.name == named }
    }
}
