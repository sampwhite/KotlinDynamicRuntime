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

    /** What it replaces, by where it stands: two the same are one replacement, before and after a patch. */
    val address: List<Any?> get() = listOf(name, slot, key, templateName)
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
     * A code configuration as the editor shows it: its name, where it comes from, its namespace, and its slots, each
     * workflow as it runs. The client's definition loses `testFeatures` off a test instance ([isTestInstance]), as a
     * stored one does when emitted (issue #696).
     */
    fun bundleOf(config: GedraConfig, isTestInstance: Boolean): Map<String, Any?> = buildMap {
        put(CFEP.name, config.name)
        put(CFEP.origin, if (config.inheritedFrom != null) CFEP.originTemplate else CFEP.originCode)
        config.inheritedFrom?.let { put(CFEP.template, it) }
        put(CFEP.namespaceField, config.namespace)
        put(CFEP.slots, slotsOf(config, isTestInstance))
    }

    /** [config]'s slots, each workflow as it runs; see [bundleOf]. */
    fun slotsOf(config: GedraConfig, isTestInstance: Boolean): Map<String, List<Map<String, Any?>>> {
        // A client's configuration holds neither; one that did is no client's, and has no slots to show.
        if (config.configTraits.isNotEmpty() || config.stateTraits.isNotEmpty()) return emptyMap()
        val slots = gedraConfigToEntries(config, shownAsRun = true)
        if (isTestInstance) return slots
        return slots.mapValues { (slot, entries) ->
            if (slot != CCT.clientDef) entries else entries.map { it - CLD.testFeatures }
        }
    }

    /**
     * The entries of [own] -- the client's configurations by name, code and stored alike -- that replace one of
     * [templateBundles], the template [template]'s code configurations by name. [keys] are each slot's key fields, and
     * [kindOf] answers a workflow entry's kind (a creation or core details workflow replaces the template's of its
     * kind, whatever its id).
     */
    fun replacements(
        template: String,
        templateBundles: List<Pair<String, Map<String, List<Map<String, Any?>>>>>,
        own: List<Pair<String, Map<String, List<Map<String, Any?>>>>>,
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
        // The columns of the forms list are one set: a client that declares any declares all of them.
        val theirColumns = templateBundles.flatMap { (name, slots) -> slots[CCT.usageDef].orEmpty().map { name to it } }
        val ownColumns = own.firstOrNull { (_, slots) -> slots[CCT.usageDef].orEmpty().isNotEmpty() }
        if (theirColumns.isNotEmpty() && ownColumns != null) {
            out += TemplateReplacement(
                ownColumns.first, CCT.usageDef, emptyMap(), template, theirColumns.first().first, theirColumns.map { it.second },
            )
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
