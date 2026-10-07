package com.dynamicruntime.common.gedra

import com.dynamicruntime.common.content.MarkdownFragmentService
import com.dynamicruntime.common.context.KdrCxt
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
 * A workflow's own variant (#984) replaces a slot with literal text, so it pulls nothing to name.
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
        val client = cxt.client
        val layouts = SchemaService.get(cxt).storeFor(client).layouts
        val fragments = MarkdownFragmentService.get(cxt)
        // The client's own layers, file by file, read once: what says whether a key is the client's and from where.
        val overrides by lazy {
            copyOverrides(MarkdownFragmentService.registeredFragmentSources(cxt), client) { fileId, forClient ->
                fragments.effectiveFragmentsFor(cxt, fileId, forClient)
            }.associateBy { Triple(it.fileId, it.namespace, it.key) }
        }
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
                    val pulls = pulledKeys(text, layout.fragmentFileId)
                    if (pulls.isEmpty()) continue
                    bySlot[slot] = linkedMapOf(
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
                if (bySlot.isNotEmpty()) byField[field.field] = bySlot
            }
            if (byField.isNotEmpty()) out[typeName] = byField
        }
        return out
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
