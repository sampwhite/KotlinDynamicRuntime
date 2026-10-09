package com.dynamicruntime.common.gedra

import com.dynamicruntime.common.content.MarkdownFragmentService
import com.dynamicruntime.common.context.KdrCxt
import com.dynamicruntime.common.gedra.workflow.WfDef
import com.dynamicruntime.common.schema.SL
import com.dynamicruntime.common.startup.SchemaService
import com.dynamicruntime.common.util.analyzeTemplate

/**
 * Field copy **pulled from a fragment file** (issue #1010): a layout's label, description, or hint written as
 * `%{@t("namespace.key")}`, whose words live at a fragment key rather than in the definition. Those words are the
 * client's to change as data -- a copy override (#918), what the Clients page's copy editor writes -- even where the
 * definition itself is global or declared in source, which Design View cannot edit. So the definition read names, per
 * field and copy slot, the keys a slot pulls, and their wording, and the inspector edits the **shared wording** at
 * each key through the copy endpoints.
 *
 * The slots read are the client's **own layout** for each type -- what every workflow inherits, the shared level.
 * A workflow's own variant (#984) replaces a slot with literal text, so it pulls nothing to name. Since issue #1070
 * the same is read for a type's **heading** and a workflow's **labels** -- its own, its tasks' and its saves'.
 */
object DesignPulledCopy {
    /** The copy slots of a layout field entry, in the order the inspector shows them. */
    val copySlots: List<String> = listOf(SL.label, SL.description, SL.hint)

    /**
     * `{ typeName -> { field -> { slot -> { mixed, keys: [...] } } } }` for every slot of [typeNames]' layouts, as the
     * client of [cxt] has them, that pulls a fragment key: whether the slot is the pull alone or [DSV.mixed] with other
     * text, and per key its file, namespace, and key, the value this client reads and the shipped value, and -- when
     * the client's own configuration sets it -- where ([COV.origin]) and, for a stored value over a source one, the
     * source value a reset returns to. Empty when nothing is pulled.
     */
    fun facts(cxt: KdrCxt, typeNames: Set<String>): Map<String, Any?> {
        val layouts = SchemaService.get(cxt).storeFor(cxt.client).layouts
        val pulls = Pulls(cxt)
        val out = linkedMapOf<String, Any?>()
        for (typeName in typeNames) {
            val layout = layouts[typeName] ?: continue
            val byField = linkedMapOf<String, Any?>()
            for (field in layout.fields) {
                val bySlot = linkedMapOf<String, Any?>()
                for (slot in copySlots) {
                    val text = when (slot) {
                        SL.label -> field.label
                        SL.description -> field.description
                        else -> field.hint
                    } ?: continue
                    pulls.slot(text, layout.fragmentFileId)?.let { bySlot[slot] = it }
                }
                if (bySlot.isNotEmpty()) byField[field.field] = bySlot
            }
            if (byField.isNotEmpty()) out[typeName] = byField
        }
        return out
    }

    /**
     * `{ typeName -> slot }` for each of [typeNames] whose layout **heading**, as the client of [cxt] has it, pulls a
     * fragment key (issue #1070) -- each slot as [facts] gives one. Empty when none does.
     */
    fun headingFacts(cxt: KdrCxt, typeNames: Set<String>): Map<String, Any?> {
        val layouts = SchemaService.get(cxt).storeFor(cxt.client).layouts
        val pulls = Pulls(cxt)
        return typeNames.mapNotNull { typeName ->
            val layout = layouts[typeName] ?: return@mapNotNull null
            layout.label?.let { pulls.slot(it, layout.fragmentFileId) }?.let { typeName to it }
        }.toMap(LinkedHashMap())
    }

    /**
     * The labels of [def] that pull a fragment key (issue #1070), each slot as [facts] gives one:
     * `{ workflow: slot, tasks: { taskId: slot }, saves: { taskId: { saveId: slot } } }`, with a label that pulls nothing
     * left out, and an empty part omitted. A workflow's labels have no default file, so a pull names its file
     * (`%{@t("fileId.ns.key")}`). Empty when no label pulls anything.
     */
    fun labelFacts(cxt: KdrCxt, def: WfDef): Map<String, Any?> {
        val pulls = Pulls(cxt)
        val out = linkedMapOf<String, Any?>()
        def.label.takeIf { it.isNotBlank() }?.let { pulls.slot(it, null) }?.let { out[DSV.workflow] = it }
        val tasks = def.tasks.mapNotNull { task -> pulls.slot(task.label, null)?.let { task.id to it } }.toMap(LinkedHashMap())
        if (tasks.isNotEmpty()) out[DSV.tasks] = tasks
        val saves = def.tasks.mapNotNull { task ->
            task.saves.mapNotNull { save -> pulls.slot(save.label, null)?.let { save.id to it } }
                .toMap(LinkedHashMap()).takeIf { it.isNotEmpty() }?.let { task.id to it }
        }.toMap(LinkedHashMap())
        if (saves.isNotEmpty()) out[DSV.saves] = saves
        return out
    }

    /**
     * What one copy slot pulls, for the client of a context: the client's own configuration layers, file by file, are
     * read once, on the first slot that pulls anything.
     */
    private class Pulls(private val cxt: KdrCxt) {
        private val client = cxt.client
        private val fragments = MarkdownFragmentService.get(cxt)
        private val overrides by lazy {
            copyOverrides(MarkdownFragmentService.registeredFragmentSources(cxt), client) { fileId, forClient ->
                fragments.effectiveFragmentsFor(cxt, fileId, forClient)
            }.associateBy { Triple(it.fileId, it.namespace, it.key) }
        }

        /** [text]'s slot -- whether it is [DSV.mixed], and each key it [DSV.pulls] -- or null when it pulls nothing. */
        fun slot(text: String, defaultFileId: String?): Map<String, Any?>? {
            val pulls = pulledKeys(text, defaultFileId)
            if (pulls.isEmpty()) return null
            return linkedMapOf(
                DSV.mixed to !isPullAlone(text),
                DSV.pulls to pulls.map { (fileId, namespace, key) ->
                    val pull = linkedMapOf<String, Any?>(COV.fileId to fileId, COV.namespaceField to namespace, COV.key to key)
                    fragments.effectiveFragmentsFor(cxt, fileId, client)?.content?.get(namespace)?.get(key)?.let { pull[COV.value] = it }
                    fragments.effectiveFragmentsFor(cxt, fileId, null)?.content?.get(namespace)?.get(key)?.let { pull[COV.baseValue] = it }
                    overrides[Triple(fileId, namespace, key)]?.let { own ->
                        pull[COV.origin] = if (own.stored) GedraConfigOrigin.stored.name else GedraConfigOrigin.source.name
                        own.sourceValue?.let { pull[COV.sourceValue] = it }
                    }
                    pull
                },
            )
        }
    }

    /**
     * The fragment keys [text] pulls with a literal `%{@t("…")}`, each as (file, namespace, key): a two-part
     * `namespace.key` read against [defaultFileId] -- the layout's `fragmentFileId` -- and a three-part
     * `fileId.namespace.key` as written. A pull this cannot place (a two-part key with no default file, or one computed
     * at render time) is left out: there is no key to name. Pure.
     */
    fun pulledKeys(text: String, defaultFileId: String?): List<Triple<String, String, String>> {
        if (MarkdownFragmentService.backendPassPrefix !in text) return emptyList()
        return text.analyzeTemplate(MarkdownFragmentService.backendPassPrefix).refs.mapNotNull { ref ->
            val parts = ref.key.split('.')
            when (parts.size) {
                3 -> Triple(parts[0], parts[1], parts[2])
                2 if defaultFileId != null -> Triple(defaultFileId, parts[0], parts[1])
                else -> null
            }
        }.distinct()
    }

    /** Whether [text] is one backend pull and nothing else -- its words are the key's, so editing the key edits them. */
    fun isPullAlone(text: String): Boolean {
        val trimmed = text.trim()
        val analysis = trimmed.analyzeTemplate(MarkdownFragmentService.backendPassPrefix)
        return analysis.blockCount == 1 && analysis.refs.size == 1 &&
            trimmed.startsWith(MarkdownFragmentService.backendPassPrefix + "{") && trimmed.endsWith("}") &&
            trimmed.indexOf('}') == trimmed.length - 1
    }
}
