package com.dynamicruntime.common.gedra

import com.dynamicruntime.common.exception.KdrException
import com.dynamicruntime.common.gedra.workflow.toJsonMap
import com.dynamicruntime.common.schema.qualifyTypeName
import com.dynamicruntime.common.util.toJsonMapOrEmpty
import com.dynamicruntime.common.util.toOptStr

/**
 * The code a client's stored overlays are read over (issue #1657): each workflow and directly declared schema type of
 * its code layer -- its template's copies, then its own code, as declared -- by key, as definitions. [layer] is read
 * once, on the first overlay looked up, so a client whose stored configuration holds none never builds it. Where two
 * code configurations declare one key, the later wins, as the client's own code stands over its template's copies.
 */
class CodeEntries(layer: () -> List<CodeConfig>) {
    private val index: Map<Pair<String, String>, Map<String, Any?>> by lazy {
        buildMap {
            for (config in layer().map { it.config }) {
                config.workflows.values.forEach { put(CCT.workflowDef to it.workflowId, it.toJsonMap()) }
                val generated = generatedTypes(config)
                config.defs.filterKeys { it !in generated }.forEach { (name, body) -> put(CCT.schemaDef to name, body.toJsonMapOrEmpty()) }
            }
        }
    }

    /** The types [layer]'s traits generate, by qualified name, to the trait each is for. */
    private val generatedByTrait: Map<String, String> by lazy {
        buildMap { for (config in layer().map { it.config }) generatedTypes(config).forEach { (type, trait) -> put(type, trait) } }
    }

    /** The code's definition of [key] in [slot]: a workflow by id, a schema type by qualified name; null when none. */
    fun definition(slot: String, key: String): Map<String, Any?>? = index[slot to key]

    /** The trait whose generated type [typeName] is, when it is one: such a type changes only with its trait. */
    fun traitGenerating(typeName: String): String? = generatedByTrait[typeName]

    private fun generatedTypes(config: GedraConfig): Map<String, String> = buildMap {
        for (trait in config.traits.values) {
            put(trait.typeName, trait.traitId)
            inlineDataTypeName(config, trait)?.let { put(it, trait.traitId) }
        }
    }
}

/**
 * Reads [entry], a stored entry of [slot] marked `"overrides": "codeOverlay"`, over [code] (issue #1657): merged over the
 * code's definition of its key and declared as that, so it stands in the config as any definition does; the parts the
 * merge refused are left out, and said on the [CodeOverlay] recorded for it. When it does not read -- no code to read it
 * over, a key the code does not declare, a result that is not a definition -- the code's runs, and the overlay is kept
 * as stored. With no [code] at all (a write's reassembly, which the trial then reads again over the code) it is only
 * kept, with nothing said.
 *
 * Theme: #CFGFG.
 */
internal fun GedraConfigBuilder.codeOverlay(slot: String, entry: Map<String, Any?>, code: CodeEntries?) {
    val workflow = slot == CCT.workflowDef
    val raw = entry[if (workflow) CCT.workflowId else CCT.typeName].toOptStr().orEmpty()
    val key = if (workflow) raw else qualifyTypeName(raw, namespace)
    fun keep(problems: List<String>) {
        codeOverlays.add(CodeOverlay(slot, key, entry, applied = false, problems = problems))
    }
    if (code == null) return keep(emptyList())
    val what = if (workflow) "workflow '$key'" else "type '$key'"
    val base = code.definition(slot, key) ?: return keep(
        listOf(
            code.traitGenerating(key)?.let { "$what is the one trait '$it' generates, which changes only with its trait." }
                ?: "The client's code declares no $what for it to change.",
        ),
    )
    val merged = mergeCodeOverlay(slot, base, entry[if (workflow) CCT.definition else CCT.schema].toJsonMapOrEmpty())
    val problems = overlayProblemTexts(merged.problems)
    try {
        if (workflow) workflowFromMap(merged.value) else type(key) { data.putAll(merged.value) }
    } catch (e: KdrException) {
        return keep(problems + (e.message ?: "It does not read as a definition once merged."))
    }
    codeOverlays.add(CodeOverlay(slot, key, entry, applied = true, problems = problems))
}

/**
 * The issues for [config]'s overlays of its code that did not apply whole (issue #1657): one that applied without some
 * part the code no longer has a place for, and one that did not apply at all, so the code's entry runs. Reported where
 * the other entries a load leaves out are (`unreadEntryIssues`), and judged, like them, as stored config: forgiven at a
 * load, and refused by a write's trial when the write brings it.
 */
fun codeOverlayIssues(config: GedraConfig): List<GedraConfigIssue> = config.codeOverlays.filter { it.problems.isNotEmpty() }.map { o ->
    val workflow = o.slot == CCT.workflowDef
    val what = if (workflow) "workflow '${o.key}'" else "type '${o.key}'"
    val kind = if (workflow) GCEL.workflow else GCEL.type
    if (o.applied) {
        config.issue(
            "Stored config '${config.gedraId}' changes the code's $what with parts the code no longer has a place for: " +
                o.problems.joinToString(" "),
            "Leaving those parts out; the rest of the change applies, and it is kept as stored.",
            kind, o.key,
        )
    } else {
        config.issue(
            "Stored config '${config.gedraId}' holds a change to the code's $what that cannot be read over the code: " +
                o.problems.joinToString(" "),
            "Running the code's $what as it is; the change is kept as stored.",
            kind, o.key,
        )
    }
}
