package com.dynamicruntime.common.gedra

import com.dynamicruntime.common.context.KdrCxt
import com.dynamicruntime.common.gedra.workflow.WfEntry
import com.dynamicruntime.common.startup.SchemaCollector
import com.dynamicruntime.common.schema.qualifyTypeName
import com.dynamicruntime.common.util.toOptStr

/** A configuration of a client's code layer, and where it comes from as an issue names it ("template starterKit's 'base'"). */
class CodeConfig(val config: GedraConfig, val where: String)

/**
 * A client's stored configuration standing over its code (issue #1634, step 2a of the overlay design).
 *
 * **The code layer** is everything a client runs that no row stores: its template's code (`ClientExtension`), its own
 * code, and on a sandbox the sandbox's own code. A stored entry marked `"overrides": "code"` ([CCT.overrides]) stands
 * over the code layer's entry of the same key, by the keys [ConfigKeys] states -- the rule a client's own configuration
 * already stands over its template's copies by -- and what it stands over is cut out of the code, whole. An entry
 * marked `"overrides": "codeOverlay"` holds only its change of a code workflow or type, and is merged over the code's as
 * the config is read (issue #1657, `CodeOverlays`), so here it is a whole definition like any other. Nothing removes a
 * code entry: a client turns off what it does not want instead (#1635), and a `$removed` is a `$` note, which no stored
 * entry may hold.
 *
 * **Explicit, not by key alone.** A stored entry whose key matches a code entry without the marker loads as it always
 * has -- replacing a template's copy, colliding with the client's own code -- with an issue saying to mark it, and a
 * marked entry with nothing under it is left out, with an issue. A write runs the same load in its trial, so it
 * refuses either one, and a patch's dry run reports it, whoever the writer is.
 *
 * **Where an override lives** is the writer's to choose. The editor files one in the stored bundle named like the code
 * bundle it covers (step 2b), but a marked entry in a bundle of another name stands over the code just the same, and
 * earns no issue: nothing about what runs differs.
 *
 * Theme: #CFGFG.
 */
object CodeOverrides {
    /**
     * What [config] -- reassembled from [entries], its stored slots -- holds of its entries marked to stand over code: a
     * marked trait with the types it generates, a marked type, cfact, workflow, report, or file ref, and the columns of
     * the forms list when any column is marked.
     */
    fun markedKeys(config: GedraConfig, entries: Map<String, List<Map<String, Any?>>>): ConfigKeys {
        // Whole or as an overlay of the code's (issue #1657): an overlay that read over the code stands over it as its
        // merged definition, and one that did not is in the config as nothing, so its key is not here.
        fun marked(slot: String) = entries[slot].orEmpty().filter { it[CCT.overrides] in markValues }
        fun keysOf(slot: String, field: String) = marked(slot).mapNotNull { it[field].toOptStr() }
        val traits = keysOf(CCT.traitDef, CCT.traitId).filter { it in config.traits }.toSet()
        val generated = traits.flatMap { id ->
            config.traits.getValue(id).let { listOfNotNull(it.typeName, inlineDataTypeName(config, it)) }
        }
        val types = keysOf(CCT.schemaDef, CCT.typeName).map { qualifyTypeName(it, config.namespace) }.filter { it in config.defs }
        return ConfigKeys(
            traits = traits,
            types = (generated + types).toSet(),
            cfacts = keysOf(CCT.cfactDef, CCT.name).toSet(),
            workflows = keysOf(CCT.workflowDef, CCT.workflowId).mapNotNull { id -> config.workflows[id]?.let { id to it.entry } }.toMap(),
            usages = marked(CCT.usageDef).isNotEmpty(),
            reports = keysOf(CCT.reportDef, CCT.reportId).filter { it in config.reports }.toSet(),
            fileRefs = keysOf(CCT.fileRefDef, CCT.refId).toSet(),
        )
    }

    /** What [stored], one client's stored configuration, stands over of its code layer, once judged ([judge]). */
    class Judged(
        /** The stored configs, each without a marked definition that has nothing under it. */
        val stored: List<GedraConfig>,
        /** What the stored configs' marked entries stand over: what the code layer is cut by. */
        val over: ConfigKeys,
    )

    /**
     * Judges [stored] -- one client's stored configs as a load reads them -- against [code], its code layer: each marked
     * definition with nothing under it is left out, and each unmarked one whose key matches a code entry is said, with
     * where the code's is. Issues go to [sink] as stored-config problems. Answers the stored configs to load and what
     * they stand over, which the caller cuts the code by.
     */
    fun judge(cxt: KdrCxt, stored: List<GedraConfig>, code: List<CodeConfig>, sink: MutableList<GedraConfigIssue>): Judged {
        val codeKeys = code.map { it to ConfigKeys.of(it.config) }
        val allCode = codeKeys.fold(ConfigKeys.none) { acc, (_, keys) -> acc + keys }
        var over = ConfigKeys.none
        val judged = stored.map { config ->
            val marked = config.overridesCode
            val under = marked.over(allCode)
            // A marked trait's generated types are its own, named for its namespace, and follow the trait: they are left
            // out only with a trait that is, and are never judged on their own.
            val missing = marked - under
            val typesOf = generatedTypesOf(config)
            val followTheirTrait = marked.traits.flatMap(typesOf).toSet() - missing.traits.flatMap(typesOf).toSet()
            val nothingUnder = missing - ConfigKeys(types = followTheirTrait)
            for (what in nothingUnder.described(generatedTypesOf(config))) {
                reportConfigProblem(
                    cxt,
                    config.issue(
                        "Stored config '${config.name}' marks $what to stand over code (\"${CCT.overrides}\": " +
                            "\"${CCT.overridesCodeValue}\"), but no code of client '${config.gedraId.client}' declares it.",
                        "Leaving it out; the rest of the config loads. Drop the mark if it is the client's own.",
                    ),
                    sink,
                )
            }
            // What is left of the unmarked part that a code entry also holds: loaded as it always was, and said.
            val unmarked = ConfigKeys.of(config) - marked
            for ((codeConfig, keys) in codeKeys) {
                val matched = unmarked.over(keys)
                // A creation or core details workflow stands over the code's of its kind whatever its id, so it is said so.
                val byKind = matched.workflows.filterKeys { it !in keys.workflows }
                val held = (matched - ConfigKeys(workflows = byKind)).described(generatedTypesOf(config)).map {
                    "holds $it, which ${codeConfig.where} also declares"
                } + byKind.map { (id, kind) ->
                    "holds the ${kind.words} workflow '$id', which stands over the one ${codeConfig.where} declares (a client has one)"
                }
                for (what in held) {
                    reportConfigProblem(
                        cxt,
                        config.issue(
                            "Stored config '${config.name}' $what, without marking it " +
                                "\"${CCT.overrides}\": \"${CCT.overridesCodeValue}\".",
                            "Loading it as before; mark the entry to say it stands over the code's.",
                        ),
                        sink,
                    )
                }
            }
            over += under
            if (nothingUnder.isEmpty) config else config.cutBy(nothingUnder).copy(overridesCode = under)
        }
        return Judged(judged, over)
    }

    /**
     * [entry], an entry of [slot] an editor is about to store for [client], marked `"overrides": "code"` when [client]'s
     * code holds its key: for an editor whose write stands over the code by what it is -- a creation workflow made for a
     * client whose template has one, a file uploaded over a ref the client was shipped with -- so the write says so, as
     * every write that stands over code must. [entry] as it is otherwise, and on a node with no configuration to read.
     */
    fun markedIfOverCode(cxt: KdrCxt, client: String, slot: String, entry: Map<String, Any?>): Map<String, Any?> {
        val owner = sandboxParentOf(client) ?: client
        val collector = SchemaCollector.get(cxt) ?: return entry
        val layer = GedraConfigLoadService.get(cxt).codeLayerOf(collector, owner, ClientService.get(cxt).known(owner))
        val code = ConfigKeys.of(layer.map { it.config })
        val over = when (slot) {
            CCT.traitDef -> entry[CCT.traitId] in code.traits
            CCT.cfactDef -> entry[CCT.name] in code.cfacts
            CCT.reportDef -> entry[CCT.reportId] in code.reports
            CCT.fileRefDef -> entry[CCT.refId] in code.fileRefs
            CCT.workflowDef -> entry[CCT.workflowId] in code.workflows ||
                ConfigCodeLayer.workflowKind(collector, entry)?.let { it in WfEntry.singletons && it in code.singletonKinds } == true
            else -> false
        }
        return if (over) entry + (CCT.overrides to CCT.overridesCodeValue) else entry
    }

    /** The values of [CCT.overrides]: an entry that stands over the code's whole, or as an overlay of it (issue #1657). */
    private val markValues = setOf(CCT.overridesCodeValue, CCT.overridesCodeOverlayValue)

    /** The types each of [config]'s traits generates, so a trait is named without them. */
    private fun generatedTypesOf(config: GedraConfig): (String) -> Set<String> = { id ->
        config.traits[id]?.let { setOfNotNull(it.typeName, inlineDataTypeName(config, it)) }.orEmpty()
    }
}
