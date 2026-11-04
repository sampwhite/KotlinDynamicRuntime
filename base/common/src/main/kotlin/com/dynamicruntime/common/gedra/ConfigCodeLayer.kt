package com.dynamicruntime.common.gedra

import com.dynamicruntime.common.gedra.workflow.WFD
import com.dynamicruntime.common.gedra.workflow.WfEntry
import com.dynamicruntime.common.startup.SchemaCollector
import com.dynamicruntime.common.util.toJsonMapOrEmpty
import com.dynamicruntime.common.util.toOptStr

/**
 * One entry of a client's that replaces one of its template's, whole (issue #1616): the entry is in the client's
 * configuration [name], in [slot], with the key fields [key]; what it replaces is [templateEntries], declared in the
 * template [template]'s configuration [templateName]. An entry replaces by its key, a creation or core details
 * workflow by its kind, and the columns of the forms list as one set ([key] empty, every template column listed).
 */
class TemplateReplacement(
    val name: String,
    val slot: String,
    val key: Map<String, Any?>,
    val template: String,
    val templateName: String,
    val templateEntries: List<Map<String, Any?>>,
) {
    fun toMap(): Map<String, Any?> = linkedMapOf(
        CFEP.name to name,
        CFEP.slot to slot,
        CFEP.key to key,
        CFEP.template to template,
        CFEP.templateName to templateName,
        CFEP.templateEntries to templateEntries,
    )

    /**
     * What it replaces, wherever the client's entry stands: two the same are one replacement, before and after a patch,
     * so an entry moved to another of the client's configurations is not a new one.
     */
    val address: List<Any?> get() = listOf(slot, key, template, templateName)
}

/** A configuration's slots, as a bundle holds them. */
typealias ConfigSlots = Map<String, List<Map<String, Any?>>>

/**
 * A client's code layer, read once for a request (issue #1616): its code configurations with their slots ([configs],
 * its own code's, then its template's copies), and, when it extends one, its [template]'s code configurations by name
 * ([templateBundles]). Built once because making slots of a configuration loads its copy files, and a request asks of
 * it more than once. [kindOf] answers a workflow entry's kind ([ConfigCodeLayer.workflowKind]).
 */
class CodeLayer(
    val configs: List<Pair<GedraConfig, ConfigSlots>>,
    val template: String?,
    val templateBundles: List<Pair<String, ConfigSlots>>,
    private val kindOf: (Map<String, Any?>) -> WfEntry?,
) {
    /**
     * The client's entries that replace one of its template's: in its own code, and in [stored], its configurations'
     * latest revisions by name, so a draft is judged as it would run once published. [keys] are each slot's key fields.
     */
    fun replacements(stored: List<Pair<String, ConfigSlots>>, keys: Map<String, List<String>>): List<TemplateReplacement> {
        val template = template ?: return emptyList()
        val ownCode = configs.filter { it.first.inheritedFrom == null }.map { (c, slots) -> c.name to slots }
        return ConfigCodeLayer.replacements(template, templateBundles, ownCode + stored, keys, kindOf)
    }

    /**
     * The layer as the editor shows it ([ConfigCodeLayer.bundleOf]), each template copy without what [replacements]
     * replace. A copy is cut at load by what the client runs, and is cut here by what its latest revisions hold too, so
     * the page never shows one entry as both replaced and run: a draft's replacement, or a stored file ref, which
     * the load leaves in the copy and the registry overrides.
     */
    fun bundles(
        replacements: List<TemplateReplacement>,
        keys: Map<String, List<String>>,
        isTestInstance: Boolean,
    ): List<Map<String, Any?>> =
        configs.map { (config, slots) ->
            val shown = if (config.inheritedFrom == null) slots else ConfigCodeLayer.withoutReplaced(config, slots, replacements, keys)
            ConfigCodeLayer.bundleOf(config, ConfigCodeLayer.redacted(shown, isTestInstance))
        }
}

/**
 * The **code layer** of a client's configuration (issue #1616), as the bundle editor shows it: what the client runs
 * that no row stores, and which of the client's entries replace its template's.
 *
 * The layer is two kinds of configuration, both read from the collector, so it is what the node runs:
 *
 * - **The client's own code:** each configuration a component declares for it.
 * - **Its template's copies** (`ClientDef.extendsFromClientId`, issue #945): the template's code configurations,
 *   cloned under the client and cut to what the client does not define itself ([ClientExtension]).
 *
 * A **replacement** is told by the merge rules [ClientExtension.clones] applies, read off entries rather than models,
 * so a draft in the editor is judged as it would run once published: traits, types, cfacts, workflows, reports, and
 * file refs by their keys; a creation or core details workflow by its kind as well; and the columns of the forms list
 * as one set. Copy and interface are layered key by key rather than replaced, so they are never one.
 */
object ConfigCodeLayer {
    /** The slots whose entries replace a template's by their key fields. */
    private val keyedSlots = listOf(CCT.traitDef, CCT.schemaDef, CCT.cfactDef, CCT.workflowDef, CCT.reportDef, CCT.fileRefDef)

    /** [client]'s own code configurations, then its template's copies, as [collector] holds them. */
    fun codeConfigs(collector: SchemaCollector, client: String): List<GedraConfig> {
        val held = collector.gedraConfigs.configs.filter { it.gedraId.client == client && !it.isStoredHere() }
        return held.filter { it.inheritedFrom == null } + held.filter { it.inheritedFrom != null }
    }

    /** The code configurations of [template], the client a client extends. */
    fun templateConfigs(collector: SchemaCollector, template: String): List<GedraConfig> =
        collector.gedraConfigs.configs.filter { it.gedraId.client == template && it.inheritedFrom == null && !it.isStored }

    /**
     * [client]'s code layer, read once ([CodeLayer]): its code configurations' slots, and those of [template], the client
     * it extends, when it extends one.
     */
    fun read(collector: SchemaCollector, client: String, template: String?): CodeLayer = CodeLayer(
        configs = codeConfigs(collector, client).map { it to slotsOf(it) },
        template = template,
        templateBundles = template?.let { t -> templateConfigs(collector, t).map { it.name to slotsOf(it) } }.orEmpty(),
        kindOf = { workflowKind(collector, it) },
    )

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

    /** [copy]'s [slots] -- a template's copy -- without each entry one of [replacements] replaces, by its [keys]. */
    fun withoutReplaced(
        copy: GedraConfig,
        slots: ConfigSlots,
        replacements: List<TemplateReplacement>,
        keys: Map<String, List<String>>,
    ): ConfigSlots {
        val mine = replacements.filter { ClientExtension.cloneName(it.template, it.templateName) == copy.name }
        if (mine.isEmpty()) return slots
        return slots.mapValues { (slot, entries) ->
            val here = mine.filter { it.slot == slot }
            when {
                here.isEmpty() -> entries
                // The columns of the forms list replace as one set.
                here.any { it.key.isEmpty() } -> emptyList()
                else -> entries.filterNot { entry -> here.any { addressesEntry(keys[slot].orEmpty(), it.key, entry) } }
            }
        }.filterValues { it.isNotEmpty() }
    }

    /**
     * The entries of [own] -- the client's configurations by name, code and stored alike -- that replace one of
     * [templateBundles], the template [template]'s code configurations by name. [keys] are each slot's key fields, and
     * [kindOf] answers a workflow entry's kind (a creation or core details workflow replaces the template's of its
     * kind, whatever its id).
     */
    fun replacements(
        template: String,
        templateBundles: List<Pair<String, ConfigSlots>>,
        own: List<Pair<String, ConfigSlots>>,
        keys: Map<String, List<String>>,
        kindOf: (Map<String, Any?>) -> WfEntry?,
    ): List<TemplateReplacement> {
        val out = mutableListOf<TemplateReplacement>()
        for (slot in keyedSlots) {
            val keyFields = keys[slot].orEmpty().ifEmpty { continue }
            val theirs = templateBundles.flatMap { (name, slots) -> slots[slot].orEmpty().map { name to it } }
            if (theirs.isEmpty()) continue
            for ((name, slots) in own) {
                for (entry in slots[slot].orEmpty()) {
                    val byKey = theirs.firstOrNull { addressesEntry(keyFields, it.second, entry) }
                    // A creation or core details workflow of the client's replaces the template's of that kind, by
                    // another id too: a client has one answer to how a form is created, or how its details are kept.
                    val byKind = if (byKey != null || slot != CCT.workflowDef) null else {
                        kindOf(entry)?.takeIf { it in WfEntry.singletons }?.let { kind -> theirs.firstOrNull { kindOf(it.second) == kind } }
                    }
                    val replaced = byKey ?: byKind ?: continue
                    val key = keyFields.associateWith { entry[it] }
                    out += TemplateReplacement(name, slot, key, template, replaced.first, listOf(replaced.second))
                }
            }
        }
        // The columns of the forms list are one set: a client that declares any declares all of them, so each of its
        // configurations holding some is part of what replaces the template's.
        val theirColumns = templateBundles.flatMap { (name, slots) -> slots[CCT.usageDef].orEmpty().map { name to it } }
        if (theirColumns.isNotEmpty()) {
            for ((name, slots) in own) {
                if (slots[CCT.usageDef].orEmpty().isEmpty()) continue
                val from = theirColumns.first().first
                out += TemplateReplacement(name, CCT.usageDef, emptyMap(), template, from, theirColumns.map { it.second })
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

    /**
     * A template's copy is filed under the client with the origin of the client's own definition, which may be stored,
     * but no row holds it: it is code.
     */
    private fun GedraConfig.isStoredHere(): Boolean = isStored && inheritedFrom == null
}
