package com.dynamicruntime.common.content

import com.dynamicruntime.common.util.crc32Hex

/**
 * A fragment file's content after every layer that applies has been folded in (issue #456), together with the
 * [buildId] that identifies exactly this content.
 *
 * [client] is the client the merge was done *for*, or null for the content everybody else gets. It is not
 * part of [buildId] on purpose: two clients whose effective content happens to be identical share a build id,
 * and so share a URL and a cache entry. The id names **content**, which is the property the permanent cache
 * depends on.
 */
class EffectiveFragments(
    val fileId: String,
    val client: String?,
    /** The merged `namespace -> key -> value` map, which is what the frontend receives. */
    val content: Map<String, Map<String, String>>,
    /** Content hash of [content] -- see [fragmentContentBuildId]. */
    val buildId: String,
    /** Whether any **base** layer supplied content; false when the file is declared and its resource absent. */
    val found: Boolean,
    /** Overlay keys, as `namespace.key`, that no base layer declares -- see [orphanedOverlayKeys]. */
    val orphans: List<String>,
)

/**
 * Merges every layer that applies to one fragment file, for [client] (issue #456).
 *
 * ### The order, written down here because this is the only place it means anything
 *
 * 1. **Base** layers, in declaration order.
 * 2. **Component** overlays (`client == null`), in declaration order.
 * 3. **This client's** overlays, in declaration order.
 *
 * Later wins, so a client's overlay is consulted before a component's and both before the base -- "consulted
 * first" and "applied last" being the same statement. The client-after-component half is the one that had to
 * be decided rather than fallen into (Sam): a client is the most specific thing that has an opinion, so it
 * gets the last word, and a component cannot take a customer's copy back by adding an overlay of its own.
 *
 * Within a step, declaration order settles it. That is not much of a rule, and it does not have to be while a
 * key overlaid twice at the same level is an authoring mistake rather than a composition -- if that stops
 * being true, this is the paragraph to change.
 *
 * A layer whose resource is absent contributes nothing and is not an error here; a *declared* file with no
 * base content is reported by [EffectiveFragments.found], which the boot check turns into a finding.
 */
fun mergeFragmentLayers(fileId: String, sources: List<FragmentSource>, client: String?): EffectiveFragments {
    val applicable = sources.filter { it.fileId == fileId && (it.client == null || it.client == client) }
    val bases = applicable.filter { !it.isOverlay }
    // A component's overlays before this client's, which is the whole of the precedence decision above.
    val overlays = applicable.filter { it.isOverlay }.sortedBy { if (it.client == null) 0 else 1 }

    val merged = LinkedHashMap<String, LinkedHashMap<String, String>>()
    var found = false
    for (source in bases) {
        val content = source.load() ?: continue
        found = true
        foldInto(merged, content)
    }
    // Captured before the overlays are folded in: afterward every overlay key is present by definition, and
    // the question "does a base declare this?" can no longer be asked of the result.
    val baseKeys = merged.entries.flatMap { (ns, keys) -> keys.keys.map { "$ns.$it" } }.toSet()
    val overlaid = LinkedHashSet<String>()
    for (source in overlays) {
        val content = source.load() ?: continue
        foldInto(merged, content)
        content.forEach { (ns, keys) -> keys.keys.forEach { overlaid.add("$ns.$it") } }
    }
    val effective = merged.mapValues { it.value.toMap() }
    return EffectiveFragments(
        fileId, client, effective, fragmentContentBuildId(effective), found,
        orphans = orphanedOverlayKeys(baseKeys, overlaid),
    )
}

/** Folds one layer's namespaces and keys over what is already there, key by key. */
private fun foldInto(
    into: LinkedHashMap<String, LinkedHashMap<String, String>>,
    layer: Map<String, Map<String, String>>,
) {
    for ((namespace, keys) in layer) {
        // Per key, not per namespace: an overlay naming one key of a namespace must not remove the rest of
        // it, which is what replacing the namespace wholesale would do -- and would do *silently*, since the
        // frontend asking for a key it no longer receives renders the key path and warns rather than failing.
        into.getOrPut(namespace) { LinkedHashMap() }.putAll(keys)
    }
}

/**
 * Overlay keys no base declares, as sorted `namespace.key` strings.
 *
 * **The failure this exists for is silence.** An overlay whose base key was renamed does not fail -- it simply
 * stops winning a lookup that no longer happens, and the base's own copy is served in its place. Nothing is
 * missing, nothing throws, and the customer-specific wording quietly reverts to the default. Reported through
 * `/operator/fragments/check` (issue #456), where the rest of this family of problem already surfaces.
 *
 * A key an overlay *adds* is indistinguishable from one whose base was renamed, so this reports both. Adding
 * a key by overlay is unusual -- the base is where a file's keys are declared -- and being told about it is
 * the right outcome either way.
 */
fun orphanedOverlayKeys(baseKeys: Set<String>, overlaidKeys: Set<String>): List<String> =
    overlaidKeys.filterNot { it in baseKeys }.sorted()

/**
 * The cache-busting id for merged fragment content: a content hash, exactly as a single file's was
 * ([ContentResources.buildId]) and for the same reason -- the permanent `Cache-Control` is sound only while
 * the URL changes whenever the bytes behind it change.
 *
 * It has to be computed over the **merged** map rather than over the base resource. A client's overlay changes
 * what is served without touching any file, so a file-derived id would leave two clients sharing one URL for
 * two different documents, and whichever was cached first would be served to both.
 *
 * ### Why every part is length-prefixed
 *
 * A Markdown value may contain any character at all, so there is no separator to reserve and no marker to
 * trust: a scheme tagging each part with what it *is* (`n`/`k`/`v`) would still not say where it **ends**, and
 * `{a: {b: "c", d: "e"}}` would hash as `{a: {b: "cnakdve"}}` does. A length says where a part ends, which is
 * the only thing that makes the encoding unambiguous -- and the position in the triple already says which
 * part it is, so a marker would buy nothing on top.
 *
 * The key **count** is written before a namespace's pairs for the same reason one level up. Without it a
 * namespace with no keys would contribute nothing at all, so `{"a": {}}` and `{}` would share an id while
 * serving different documents; and merely emitting the namespace once instead would leave `{a: {b:c, d:e}}`
 * and `{a: {}, b: {c:d}, e: {}}` producing one string between them.
 */
fun fragmentContentBuildId(content: Map<String, Map<String, String>>): String {
    val canonical = buildString {
        fun part(text: String) = append(text.length).append(':').append(text)
        for ((namespace, keys) in content) {
            part(namespace)
            append(keys.size).append(':')
            for ((key, value) in keys) {
                part(key)
                part(value)
            }
        }
    }
    return canonical.crc32Hex()
}
