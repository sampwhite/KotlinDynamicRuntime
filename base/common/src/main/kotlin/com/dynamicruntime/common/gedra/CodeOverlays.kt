package com.dynamicruntime.common.gedra

import com.dynamicruntime.common.context.KdrCxt
import com.dynamicruntime.common.exception.KdrException
import com.dynamicruntime.common.gedra.workflow.toJsonMap
import com.dynamicruntime.common.startup.SchemaCollector
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
                // The client's own definition, which an overlay may turn the sandbox on for (issue #1677); never a
                // template's copy, which defines no client.
                config.client?.takeIf { config.inheritedFrom == null }?.let { put(CCT.clientDef to it.clientId, it.toInfo()) }
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

    /**
 * The code's definition of [key] in [slot]: a workflow by id, a schema type by qualified name, the client's definition by
 * client id; null when none.
 */
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
    if (slot == CCT.clientDef) return clientDefOverlay(entry, code)
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
 * Reads [entry], a stored client definition marked `"overrides": "codeOverlay"`, over [code] (issue #1677): it changes
 * the client's definition in code, and for now only says whether the client has a sandbox ([clientDefOverlayKeys]). It
 * defines no client -- the code's definition stands -- and applies when the code declares the client it names, turning
 * the sandbox on where it sets `sandbox` ([GedraConfig.overlayAsksForSandbox]). Any other key is left out and said, as a
 * part of a workflow's overlay the code has no place for is; one naming a client the code does not declare is kept as
 * stored, applying nothing. With no [code] (a write's reassembly) it is only kept.
 */
private fun GedraConfigBuilder.clientDefOverlay(entry: Map<String, Any?>, code: CodeEntries?) {
    val key = entry[CLD.clientId].toOptStr().orEmpty()
    fun record(applied: Boolean, problems: List<String>) {
        codeOverlays.add(CodeOverlay(CCT.clientDef, key, entry, applied, problems))
    }
    if (code == null) return record(false, emptyList())
    if (code.definition(CCT.clientDef, key) == null) {
        return record(false, listOf("The client's code declares no definition of client '$key' for it to change."))
    }
    record(true, clientDefOverlayFaults(entry))
}

/**
 * What is wrong with [entry], an overlay of a client's definition in code (issue #1677), one line a fault: a key it may
 * not hold ([clientDefOverlayKeys]), and a `sandbox` that is not true or false. What a write refuses, and what a load
 * leaves out and reports.
 */
fun clientDefOverlayFaults(entry: Map<String, Any?>): List<String> = buildList {
    for (key in entry.keys.filter { it !in clientDefOverlayKeys }) {
        add("$key: a change to a client's definition in code may set only whether it has a sandbox.")
    }
    entry[CLD.sandbox]?.takeIf { it !is Boolean }?.let { add("${CLD.sandbox}: is true or false, not '$it'.") }
}

/**
 * The issues for [config]'s overlays of its code that did not apply whole (issue #1657): one that applied without some
 * part the code no longer has a place for, and one that did not apply at all, so the code's entry runs. Reported where
 * the other entries a load leaves out are (`unreadEntryIssues`), and judged, like them, as stored config: forgiven at a
 * load, and refused by a write's trial when the write brings it.
 */
fun codeOverlayIssues(config: GedraConfig): List<GedraConfigIssue> = config.codeOverlays.filter { it.problems.isNotEmpty() }.map { o ->
    val what = when (o.slot) {
        CCT.workflowDef -> "workflow '${o.key}'"
        CCT.clientDef -> "definition of client '${o.key}'"
        else -> "type '${o.key}'"
    }
    val kind = when (o.slot) {
        CCT.workflowDef -> GCEL.workflow
        CCT.clientDef -> GCEL.client
        else -> GCEL.type
    }
    if (o.applied) {
        // A client's definition is not merged: what it leaves out is what such a change may not hold (issue #1677).
        val parts = if (o.slot == CCT.clientDef) "parts such a change may not hold" else "parts the code no longer has a place for"
        config.issue(
            "Stored config '${config.gedraId}' changes the code's $what with $parts: " + o.problems.joinToString(" "),
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

/**
 * A stored definition as an editor that rewrites it whole sees it (issue #1657): [body], the whole definition, and --
 * for an entry that is an overlay of the code -- [code], the code's definition it is merged over, so a rewrite is stored
 * as the difference again ([stored]) rather than as the whole, which would restate every part and stop following the
 * code. Design View's edits of a workflow and of a type's layout and choices are made this way.
 */
class StoredWhole(val slot: String, val key: String, val body: Map<String, Any?>, val code: Map<String, Any?>?) {
    /** [edited], the rewritten whole, as the entry stores it: as it is, or for an overlay only what differs from the code's. */
    fun stored(edited: Map<String, Any?>): Map<String, Any?> {
        val code = code ?: return edited
        val diff = diffCodeOverlay(slot, code, edited)
        if (diff.problems.isNotEmpty()) {
            throw KdrException.mkInput(
                "${what(slot, key)} is kept as a change to the code, which cannot state this: " +
                    overlayProblemTexts(diff.problems).joinToString(" ") + " Detach it from the code in the bundle editor first.",
            )
        }
        return diff.overlay
    }

    companion object {
        /**
         * [entry], a stored entry of [slot] keyed by [key] that [client] holds, as a whole definition: merged over the
         * client's code as declared when it is an overlay of the code, and as stored otherwise. An overlay with nothing
         * under it, or holding parts the code no longer has a place for, is refused: a rewrite of it would drop them
         * unseen, so it is fixed in the bundle editor, which says what is dropped.
         */
        fun of(cxt: KdrCxt, client: String, slot: String, key: String, entry: Map<String, Any?>): StoredWhole {
            val field = codeOverlayBodyField(slot)
            val body = entry[field].toJsonMapOrEmpty()
            if (!entry.isCodeOverlay) return StoredWhole(slot, key, body, null)
            val code = codeDefinition(cxt, client, slot, key)
                ?: throw KdrException.mkInput("${what(slot, key)} is a change to the code's, and the client's code no longer declares it.")
            val merged = mergeCodeOverlay(slot, code, body)
            if (merged.problems.isNotEmpty()) {
                throw KdrException.mkInput(
                    "${what(slot, key)} is a change to the code's that holds parts the code no longer has a place for: " +
                        overlayProblemTexts(merged.problems).joinToString(" ") + " Fix or remove them in the bundle editor first.",
                )
            }
            return StoredWhole(slot, key, merged.value, code)
        }

        /** [client]'s code's definition of [key] in [slot], as declared: what its overlay of it is merged over. */
        private fun codeDefinition(cxt: KdrCxt, client: String, slot: String, key: String): Map<String, Any?>? {
            val owner = sandboxParentOf(client) ?: client
            val collector = SchemaCollector.get(cxt) ?: return null
            val loader = GedraConfigLoadService.get(cxt)
            return CodeEntries { loader.codeLayerOf(collector, owner, ClientService.get(cxt).known(owner)) }.definition(slot, key)
        }

        private fun what(slot: String, key: String) = if (slot == CCT.workflowDef) "Workflow '$key'" else "Type '$key'"
    }
}
