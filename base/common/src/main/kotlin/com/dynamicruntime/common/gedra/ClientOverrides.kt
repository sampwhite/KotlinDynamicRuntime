package com.dynamicruntime.common.gedra

import com.dynamicruntime.common.cfact.CFACT
import com.dynamicruntime.common.content.EffectiveFragments
import com.dynamicruntime.common.content.FragmentSource
import com.dynamicruntime.common.content.overlayPrecedence
import com.dynamicruntime.common.exception.KdrException
import com.dynamicruntime.common.home.HFLD
import com.dynamicruntime.common.schema.JsonMappable
import com.dynamicruntime.common.uiblock.MergedUiBlock
import com.dynamicruntime.common.uiblock.UIB
import com.dynamicruntime.common.uiblock.UiBlockSource
import com.dynamicruntime.common.util.toJsonStr

/**
 * What a client's own configuration changes about what its people see (issue #916) -- the read model behind "which
 * clients customize what", and what an editor of that copy and interface will be built on.
 *
 * Two mechanisms, reported side by side because to an administrator they are one question:
 *  - **Copy** -- Markdown fragment overlays: one [CopyOverride] per key a client layer sets.
 *  - **Interface** -- UiBlock overlays (menus among them): one [BlockOverride] per keyed-array item, or per object
 *    outside one, that a client layer touches.
 *
 * Only the client's **own** layers are the subject. A component's overlays apply to everybody, so they are part of
 * the [CopyOverride.baseValue] a client departs from, not something the client did.
 *
 * Pure over the layers, with the merges passed in, so the rules are testable without a booted node and the
 * endpoint can hand over the fragment service's cached merges instead of re-reading classpath files.
 */

/** One fragment key a client's configuration sets. */
class CopyOverride(
    val fileId: String,
    val namespace: String,
    val key: String,
    val audience: String,
    /** What everybody else reads: the base with the components' overlays. Null when nothing else sets the key. */
    val baseValue: String?,
    /** What this client reads. */
    val value: String?,
    /** The client config whose layer won, and whether it is stored. */
    val configName: String?,
    val stored: Boolean,
    /** The client's own source-config value that a stored layer overrides, when one does -- what "revert" returns to. */
    val sourceValue: String?,
    /** Whether no base declares the key, so the override is not replacing anything anybody reads. */
    val orphan: Boolean,
) : JsonMappable {
    override fun toJsonMap(): Map<String, Any?> {
        val out = linkedMapOf<String, Any?>(
            COV.fileId to fileId,
            COV.namespaceField to namespace,
            COV.key to key,
            COV.audience to audience,
        )
        if (baseValue != null) out[COV.baseValue] = baseValue
        if (value != null) out[COV.value] = value
        if (configName != null) out[COV.configName] = configName
        out[COV.origin] = originName(stored)
        if (sourceValue != null) out[COV.sourceValue] = sourceValue
        out[COV.orphan] = orphan
        return out
    }
}

/** One field of a UiBlock item or object that a client's configuration sets. Values are rendered as text. */
class BlockFieldOverride(
    val field: String,
    val baseValue: String?,
    val value: String?,
    val configName: String?,
    val stored: Boolean,
) : JsonMappable {
    override fun toJsonMap(): Map<String, Any?> {
        val out = linkedMapOf<String, Any?>(COV.field to field)
        if (baseValue != null) out[COV.baseValue] = baseValue
        if (value != null) out[COV.value] = value
        if (configName != null) out[COV.configName] = configName
        out[COV.origin] = originName(stored)
        return out
    }
}

/**
 * One UiBlock item (in a keyed array, [itemId] set) or object (outside one, [itemId] null) that a client's
 * configuration changes, with the fields it sets.
 */
class BlockOverride(
    val blockId: String,
    val path: String,
    val itemId: String?,
    val added: Boolean,
    val hidden: Boolean,
    /** The item's label in the block everybody else gets, when it has one -- see `COV.baseLabel`. */
    val baseLabel: String?,
    val fields: List<BlockFieldOverride>,
) : JsonMappable {
    override fun toJsonMap(): Map<String, Any?> {
        val out = linkedMapOf<String, Any?>(COV.blockId to blockId, COV.path to path)
        if (itemId != null) out[COV.itemId] = itemId
        out[COV.added] = added
        out[COV.hidden] = hidden
        if (baseLabel != null) out[COV.baseLabel] = baseLabel
        out[COV.fields] = fields.map { it.toJsonMap() }
        return out
    }
}

private fun originName(stored: Boolean): String =
    (if (stored) GedraConfigOrigin.stored else GedraConfigOrigin.source).name

/** [client]'s own fragment overlays among [sources], in the order they are applied -- source, then stored. */
fun clientFragmentLayers(sources: List<FragmentSource>, client: String): List<FragmentSource> =
    sources.filter { it.isOverlay && it.client == client }.sortedBy { overlayPrecedence(it.client, it.stored) }

/** [client]'s own UiBlock overlays among [sources], in applied order. */
fun clientUiBlockLayers(sources: List<UiBlockSource>, client: String): List<UiBlockSource> =
    sources.filter { it.isOverlay && it.client == client }.sortedBy { overlayPrecedence(it.client, it.stored) }

/** How many fragment keys [client]'s layers set -- the overview's count, from the layers alone, with no merge. */
fun countCopyOverrides(sources: List<FragmentSource>, client: String): Int =
    clientFragmentLayers(sources, client)
        .flatMap { layer -> layer.load().orEmpty().flatMap { (ns, keys) -> keys.keys.map { "${layer.fileId}|$ns|$it" } } }
        .toSet().size

/**
 * Every fragment key [client]'s layers set, grouped by file in the order the files are first touched.
 *
 * [merged] answers a file's content for a client, or for everybody with a null client -- the fragment service's
 * cached `effectiveFragmentsFor` in the endpoint, `mergeFragmentLayers` in a test.
 */
fun copyOverrides(
    sources: List<FragmentSource>,
    client: String,
    merged: (fileId: String, client: String?) -> EffectiveFragments?,
): List<CopyOverride> {
    val layers = clientFragmentLayers(sources, client)
    val out = mutableListOf<CopyOverride>()
    for (fileId in layers.map { it.fileId }.distinct()) {
        val shared = merged(fileId, null)
        val effective = merged(fileId, client) ?: continue
        // Walked in applied order, so the last layer to set a key is the one whose value the client reads.
        val winner = LinkedHashMap<Pair<String, String>, FragmentSource>()
        val sourceValue = HashMap<Pair<String, String>, String>()
        for (layer in layers.filter { it.fileId == fileId }) {
            for ((ns, keys) in layer.load().orEmpty()) {
                for ((key, value) in keys) {
                    winner[ns to key] = layer
                    if (!layer.stored) sourceValue[ns to key] = value
                }
            }
        }
        val orphans = effective.orphans.toSet()
        for ((at, layer) in winner) {
            val (ns, key) = at
            out.add(
                CopyOverride(
                    fileId, ns, key, effective.audience.name,
                    baseValue = shared?.content?.get(ns)?.get(key),
                    value = effective.content[ns]?.get(key),
                    configName = layer.configName,
                    stored = layer.stored,
                    sourceValue = if (layer.stored) sourceValue[at] else null,
                    orphan = "$ns.$key" in orphans,
                ),
            )
        }
    }
    return out
}

/**
 * Where a client layer touches a block: a keyed-array item (`itemId` set), an object outside one, or an item a
 * keyed array holds with **no key** ([keyless] set -- `mergeArray` appends such an item rather than dropping it, so
 * it is served and has to be reported). A keyless item has nothing to match it by, so each is its own touch,
 * told apart by [ordinal].
 */
private data class BlockTouch(val path: String, val itemId: String?, val ordinal: Int = 0) {
    var keyless: Map<*, *>? = null
}

/**
 * Each item and object [layers] touch, with the fields each sets and the last layer to set it -- the rows of a
 * block's overrides before any merge is consulted, so [countBlockOverrides] can count them cheaply.
 *
 * Fields are taken one level deep: a keyed item's own fields, or an object's non-object values. A field holding an
 * object or an array is reported whole, as text -- and that includes a keyed array nested **inside** an item
 * (`items.*.actions`): only keyed arrays reached through objects are walked into items. Nothing declares a nested
 * one today; an editor that needs to address one of its elements is where the walk would grow.
 */
private fun blockTouches(
    layers: List<UiBlockSource>,
    arrayKeys: Map<String, String>,
): Map<BlockTouch, LinkedHashMap<String, UiBlockSource>> {
    val touches = LinkedHashMap<BlockTouch, LinkedHashMap<String, UiBlockSource>>()
    var keylessCount = 0
    fun walk(node: Map<*, *>, path: String, layer: UiBlockSource, depth: Int) {
        if (depth > maxBlockDepth) {
            throw KdrException("UiBlock '${layer.blockId}' from ${layer.origin} nests deeper than $maxBlockDepth levels.")
        }
        for ((k, v) in node) {
            val name = k.toString()
            val at = if (path.isEmpty()) name else "$path.$name"
            val keyField = arrayKeys[at]
            when {
                v is List<*> && keyField != null -> for (item in v) {
                    val map = item as? Map<*, *> ?: continue
                    val id = map[keyField]?.toString()
                    val touch = if (id != null) BlockTouch(at, id) else BlockTouch(at, null, ++keylessCount).also { it.keyless = map }
                    val fields = touches.getOrPut(touch) { LinkedHashMap() }
                    for (f in map.keys) {
                        if (f != keyField) fields[f.toString()] = layer
                    }
                }
                v is Map<*, *> -> walk(v, at, layer, depth + 1)
                else -> touches.getOrPut(BlockTouch(path, null)) { LinkedHashMap() }[name] = layer
            }
        }
    }
    for (layer in layers) walk(layer.content, "", layer, 0)
    return touches
}

private const val maxBlockDepth = 20

/** How many items and objects [client]'s layers change, over every block -- the overview's count, with no merge. */
fun countBlockOverrides(sources: List<UiBlockSource>, client: String): Int =
    clientUiBlockLayers(sources, client).groupBy { it.blockId }.entries.sumOf { (blockId, layers) ->
        blockTouches(layers, baseArrayKeys(sources, blockId)).size
    }

/** The merge rules [blockId]'s bases declare, which say which arrays are keyed. */
private fun baseArrayKeys(sources: List<UiBlockSource>, blockId: String): Map<String, String> =
    sources.filter { it.blockId == blockId && !it.isOverlay }.fold(emptyMap()) { acc, s -> acc + s.arrayKeys }

/**
 * Every item and object [client]'s layers change, block by block in the order the blocks are first touched.
 * [merged] answers a block merged for a client, or for everybody with a null client (`mergeUiBlock`).
 */
fun blockOverrides(
    sources: List<UiBlockSource>,
    client: String,
    merged: (blockId: String, client: String?) -> MergedUiBlock,
): List<BlockOverride> {
    val layers = clientUiBlockLayers(sources, client)
    val out = mutableListOf<BlockOverride>()
    for (blockId in layers.map { it.blockId }.distinct()) {
        val shared = merged(blockId, null)
        val effective = merged(blockId, client)
        for ((touch, fields) in blockTouches(layers.filter { it.blockId == blockId }, shared.arrayKeys)) {
            val keyField = shared.arrayKeys[touch.path]
            val keyless = touch.keyless
            // A keyless item is the client's own by construction, and cannot be found in a merge by anything but
            // itself -- so it is reported as the layer wrote it.
            val base = if (keyless != null) null else objectAt(shared.content, touch, keyField)
            val mine = keyless ?: objectAt(effective.content, touch, keyField)
            val isItem = touch.itemId != null || keyless != null
            out.add(
                BlockOverride(
                    blockId, touch.path, touch.itemId,
                    added = isItem && base == null,
                    // Withdrawn *by this client*: an item the base already withdraws is not the client's doing,
                    // even when the client renames it.
                    hidden = isItem && mine.isNever() && !base.isNever(),
                    baseLabel = if (isItem) base?.get(HFLD.label).asText() else null,
                    fields = fields.map { (field, layer) ->
                        BlockFieldOverride(
                            field, base?.get(field).asText(), mine?.get(field).asText(), layer.configName, layer.stored,
                        )
                    },
                ),
            )
        }
    }
    return out
}

private fun Map<*, *>?.isNever(): Boolean = this?.get(UIB.cfactExpression) == CFACT.neverName

/** The item [touch] names in the keyed array at its path, or the object at its path; null when absent. */
private fun objectAt(content: Map<String, Any?>, touch: BlockTouch, keyField: String?): Map<*, *>? {
    var node: Any? = content
    if (touch.path.isNotEmpty()) {
        for (segment in touch.path.split(".")) {
            node = (node as? Map<*, *>)?.get(segment) ?: return null
        }
    }
    if (touch.itemId == null) return node as? Map<*, *>
    return (node as? List<*>)?.firstOrNull { (it as? Map<*, *>)?.get(keyField)?.toString() == touch.itemId } as? Map<*, *>
}

/** A block field's value as text: a string as it is, a number or flag as written, anything larger as compact JSON. */
private fun Any?.asText(): String? = when (this) {
    null -> null
    is String -> this
    is Number, is Boolean -> toString()
    else -> toJsonStr(compact = true)
}
