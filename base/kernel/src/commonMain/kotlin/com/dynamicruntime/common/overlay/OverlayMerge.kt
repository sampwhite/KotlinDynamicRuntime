package com.dynamicruntime.common.overlay

import com.dynamicruntime.common.schema.childPath
import com.dynamicruntime.common.util.Problem
import com.dynamicruntime.common.util.ProblemCode
import com.dynamicruntime.common.util.ProblemLocation
import com.dynamicruntime.common.util.toJsonMapOrEmpty

/**
 * Merging an **overlay** onto a **base** resource by a **declared** rule for each of its parts (issue #985).
 *
 * Every resource this runtime overlays -- a schema type a client alters, a type's field layout, later a workflow's
 * variant of a type, tasks, and more -- is a JSON map whose interior lists carry a natural id (a property name, a
 * layout entry's `field`, a task's `id`). That makes them targetable by a delta. Rather than each site hand-writing
 * its merge, a resource kind declares how each of its top-level keys merges ([MergeSpec]), and [mergeOverlay] does
 * the rest. Nothing is inferred from the data: an array is keyed because its spec says so, never because its
 * elements happen to carry a field that looks like an id. (The Kubernetes strategic-merge pattern --
 * `x-kubernetes-list-type`, `x-kubernetes-list-map-keys` -- and what `UiBlockMerge`'s declared `arrayKeys` already
 * do for UiBlocks.)
 *
 * ### Two levels per resource, by construction
 *
 * An overlay reaches a resource's own keys and **one level below** -- the entries of a map, or the elements of a
 * keyed list -- and no further. Nothing deeper is addressable, so anything deeper that needs targeting is a resource
 * of its own with an identity, merged by its own spec: a part ([MergeRule.Resource]; a schema type's `g-layout` is
 * the first), a map's entries ([MergeRule.Resources]; a workflow's `types`), or a keyed list's elements
 * ([MergeRule.Keyed.elementSpec]; a workflow's tasks, issue #1120). The rules make this structural rather than a
 * check: only those take a spec for what they fold, and each is a resource with its own two levels.
 *
 * ### Choosing a rule
 *
 * A resource may let an overlay pick a different rule for some of its parts ([MergeSpec.choices], read from the
 * overlay's directive): a schema type's `properties` restated by default, or merged when an alteration asks. The
 * default is what a reader can take in at once; the alternative is for an author -- often an AI -- who would rather
 * state a delta. Which works better in practice is one of the things to learn.
 *
 * ### The inverse: a diff
 *
 * [diffOverlay] writes the overlay that merges a base into an edited resource (issue #1120): what a surface stores
 * when somebody edited the merged result and only their change should be kept. It is the same rules read the other
 * way, so a change no rule can state -- an element removed from a keyed list, one inserted where order matters -- is
 * reported rather than written.
 *
 * ### What is not here yet
 *
 * Placing a new element relative to a sibling (anchors: `into` / `before` / `after`). A keyed list therefore either
 * refuses an element matching nothing or appends it ([OnNew]); a list whose order matters refuses, and its overlay
 * restates the list instead ([MergeRule.RestateKeyed]) when it means to change what it holds or its order.
 *
 * ### Sharing
 *
 * Nothing in [base] or [overlay] is written to. A part the overlay does not mention is the base's value, shared by
 * reference -- a variant may create new nodes and point at old ones; it must not write into old ones
 * (`docs/design/client-definition.md`).
 */
fun mergeOverlay(spec: MergeSpec, base: Map<String, Any?>, overlay: Map<String, Any?>, path: String = ""): MergeOutcome {
    val problems = mutableListOf<Problem>()
    val chosen = spec.chosenRules(overlay, path, problems)
    val directive = spec.directiveKey
    val out = LinkedHashMap<String, Any?>(base.size + overlay.size)
    for ((key, value) in base) {
        if (key == directive) continue
        val rule = chosen[key] ?: spec.ruleFor(key)
        out[key] = if (key !in overlay) value else mergeValue(rule, value, overlay[key], childPath(path, key), problems)
    }
    for ((key, value) in overlay) {
        if (key !in base && key != directive) out[key] = value
    }
    return MergeOutcome(out, problems)
}

/** A merge's result, and the parts of the overlay it refused (each located at its path in the resource). */
class MergeOutcome(val value: Map<String, Any?>, val problems: List<Problem>)

/**
 * How a resource kind merges: a [MergeRule] per top-level key, and [default] for every key not named. The default
 * is [MergeRule.Replace], which is also what a key a spec has never heard of gets -- an overlay that mentions a key
 * means its value.
 *
 * **An overlay may choose differently, where the resource allows it.** [choices] names, per key, the rules an
 * overlay may pick instead of [rules]' default, by name; the overlay picks with a map under [directiveKey] --
 * `{ "<key>": "<choice>" }` -- which is read here and never part of the result. Only what [choices] declares can be
 * chosen: a choice a resource does not offer is refused, not guessed at. (The Kubernetes strategic-merge-patch
 * directives -- `$patch: replace` and its kin -- are the precedent: a declared default per field, and a patch author
 * stating where they want otherwise.)
 */
class MergeSpec(
    val rules: Map<String, MergeRule>,
    val default: MergeRule = MergeRule.Replace,
    val directiveKey: String? = null,
    val choices: Map<String, Map<String, MergeRule>> = emptyMap(),
) {
    fun ruleFor(key: String): MergeRule = rules[key] ?: default

    /** The rules [overlay]'s directive chooses, by key; each unknown key or choice reported and ignored. */
    fun chosenRules(overlay: Map<String, Any?>, path: String, problems: MutableList<Problem>): Map<String, MergeRule> {
        val raw = directiveKey?.let { overlay[it] } ?: return emptyMap()
        val at = childPath(path, directiveKey)
        val asked = (raw as? Map<*, *>)?.toJsonMapOrEmpty()
        if (asked == null) {
            problems.add(Problem(OverlayMergeError.unknownChoice, "This must map a part to how it merges.", ProblemLocation(at)))
            return emptyMap()
        }
        val out = LinkedHashMap<String, MergeRule>()
        for ((key, choice) in asked) {
            val offered = choices[key].orEmpty()
            val rule = offered[choice as? String]
            if (rule == null) {
                val what = if (offered.isEmpty()) "In '$directiveKey', '$key' offers no choice of how it merges" else
                    "In '$directiveKey', '$key' merges as one of ${offered.keys.joinToString(", ") { "'$it'" }}, not '$choice'"
                problems.add(Problem(OverlayMergeError.unknownChoice, "$what.", ProblemLocation(childPath(at, key))))
            } else {
                out[key] = rule
            }
        }
        return out
    }
}

/** The names an overlay chooses a [MergeRule] by, in a [MergeSpec]'s directive (issue #985). */
@Suppress("ConstPropertyName")
object MCH {
    /** [MergeRule.Merge] for a map; [MergeRule.Keyed] for a keyed list. */
    const val merge = "merge"

    /** [MergeRule.Restate] for a map; [MergeRule.RestateKeyed] for a keyed list. */
    const val restate = "restate"
}

/** How one part of a resource merges; see [mergeOverlay]. */
sealed interface MergeRule {
    /** The overlay's value wins whole. The default. */
    data object Replace : MergeRule

    /**
     * A map whose entries the overlay sets one by one: an entry it does not mention is the base's, a `null` entry
     * removes the base's, and an empty one (`{}`) keeps it -- the last so that an overlay may name an entry without
     * changing it, as it does under [Restate].
     */
    data object Merge : MergeRule

    /**
     * A list of objects identified by their [key] field. An overlay element replaces the base element with the same
     * key ([element] [KeyedElement.replace]) or is folded into it, entry by entry ([KeyedElement.merge]); one
     * matching nothing is handled by [onNew]. Elements keep the base's order; appended ones follow. A choice that
     * depends on the resource -- a layout appends only when its mode makes order meaningless -- is made by the
     * enclosing [Resource]'s spec, which sees the resource.
     */
    data class Keyed(
        val key: String,
        val element: KeyedElement = KeyedElement.replace,
        val onNew: OnNew = OnNew.refuse,
        /** Added to a refusal's message: what the author can do instead, in the resource's own terms. */
        val refusalHint: String? = null,
        /**
         * For [KeyedElement.merge]: the spec each element merges by, as a resource of its own (issue #1120) -- so an
         * element's parts may in turn be restated, merged, or keyed. Without one, an element is folded entry by entry.
         */
        val elementSpec: ((base: Map<String, Any?>, overlay: Map<String, Any?>) -> MergeSpec)? = null,
    ) : MergeRule

    /**
     * A map that is **the set the overlay mentions**: an entry it leaves out is gone, and an empty entry (`{}`, or
     * null) keeps the base's. The rule for a contract list -- a type's `properties` -- where somebody reading an
     * alteration should see every entry it has, rather than a fragment plus whatever the base happened to hold.
     */
    data object Restate : MergeRule

    /**
     * A keyed list that is **the list the overlay gives**, in its order: an element it leaves out is gone, and one
     * naming only its [key] keeps the base's element for that key. [Restate] for a list whose elements have ids --
     * how an overlay says what a list holds and in what order, where [Keyed] could only change elements in place.
     */
    data class RestateKeyed(val key: String) : MergeRule

    /**
     * A list an overlay may only **add to** (issue #1501): the overlay's elements follow the base's, which it can
     * neither change nor remove -- a type's `g-onWrite` functions, which a client's alteration adds its own beside but
     * may not take away, since the base's produce values the type's every user reads. An overlay of `null` removes
     * nothing and is reported. Where the base has none, the overlay's list stands as written.
     */
    data object Append : MergeRule

    /**
     * A part that is a resource of its own, merged by its own spec -- which may depend on what is being merged (a
     * layout's [MergeRule.Keyed] list refuses new elements only when its mode makes order mean something). When the
     * base has none, the overlay's stands as written; an overlay of `null` removes it.
     */
    class Resource(val spec: (base: Map<String, Any?>, overlay: Map<String, Any?>) -> MergeSpec) : MergeRule

    /**
     * A map whose every entry is a resource of its own, merged by [spec] (issue #1120): a workflow's `types`, each a
     * schema type's alteration. An entry the overlay does not mention is the base's; one the base lacks stands as
     * written; a `null` one removes the base's. [Merge], with each entry merged rather than replaced.
     */
    class Resources(val spec: (base: Map<String, Any?>, overlay: Map<String, Any?>) -> MergeSpec) : MergeRule
}

/** Whether a keyed list's overlay element replaces its match whole, or is folded into it entry by entry. */
@Suppress("EnumEntryName")
enum class KeyedElement { replace, merge }

/** What a keyed list does with an overlay element whose key matches no base element. */
@Suppress("EnumEntryName")
enum class OnNew {
    /** Left out, and reported: the list's order matters, and nothing says where the element would go. */
    refuse,

    /** Added after the base's elements: the list's order means nothing. */
    append,
}

/** Why a part of an overlay was not merged (issue #985). */
@Suppress("EnumEntryName")
enum class OverlayMergeError : ProblemCode {
    /** A keyed list's element whose key matches no base element, where the list refuses new ones. */
    unmatchedElement,

    /** A keyed list's element with no key, which therefore cannot be matched to anything. */
    missingKey,

    /** A directive asking for a way of merging the resource does not offer for that part. */
    unknownChoice,

    /** A change [diffOverlay] found that no rule of the resource can state: an element removed, inserted, or moved. */
    cannotStateChange,

    /** An overlay that would remove what an append-only list ([MergeRule.Append]) holds. */
    appendOnly,
}

private fun mergeValue(rule: MergeRule, base: Any?, over: Any?, path: String, problems: MutableList<Problem>): Any? {
    // Null in an overlay always means "none": a removal, under every rule but the one that cannot remove.
    if (over == null) {
        if (rule != MergeRule.Append || base == null) return null
        problems.add(Problem(OverlayMergeError.appendOnly, appendOnlyMessage, ProblemLocation(path)))
        return base
    }
    return when (rule) {
        MergeRule.Replace -> over
        MergeRule.Append -> if (base is List<*> && over is List<*>) base + over else over
        MergeRule.Merge -> if (base is Map<*, *> && over is Map<*, *>) mergeEntries(base.toJsonMapOrEmpty(), over.toJsonMapOrEmpty()) else over
        MergeRule.Restate -> if (base is Map<*, *> && over is Map<*, *>) restate(base.toJsonMapOrEmpty(), over.toJsonMapOrEmpty()) else over
        is MergeRule.Keyed -> if (base is List<*> && over is List<*>) mergeKeyed(rule, base, over, path, problems) else over
        is MergeRule.RestateKeyed -> if (base is List<*> && over is List<*>) restateKeyed(rule, base, over, path, problems) else over
        is MergeRule.Resource -> if (base is Map<*, *> && over is Map<*, *>) {
            mergeResource(rule.spec, base.toJsonMapOrEmpty(), over.toJsonMapOrEmpty(), path, problems)
        } else {
            over
        }
        is MergeRule.Resources -> if (base is Map<*, *> && over is Map<*, *>) {
            val b = base.toJsonMapOrEmpty()
            val out = LinkedHashMap(b)
            for ((name, entry) in over.toJsonMapOrEmpty()) {
                val prior = b[name]
                out[name] = when {
                    entry == null -> null
                    prior is Map<*, *> && entry is Map<*, *> ->
                        mergeResource(rule.spec, prior.toJsonMapOrEmpty(), entry.toJsonMapOrEmpty(), childPath(path, name), problems)
                    else -> entry
                }
            }
            out.filterValues { it != null }
        } else {
            over
        }
    }
}

private fun mergeResource(
    spec: (Map<String, Any?>, Map<String, Any?>) -> MergeSpec,
    base: Map<String, Any?>,
    over: Map<String, Any?>,
    path: String,
    problems: MutableList<Problem>,
): Map<String, Any?> {
    val outcome = mergeOverlay(spec(base, over), base, over, path)
    problems.addAll(outcome.problems)
    return outcome.value
}

private fun mergeEntries(base: Map<String, Any?>, over: Map<String, Any?>): Map<String, Any?> {
    val out = LinkedHashMap(base)
    for ((name, entry) in over) {
        when (entry) {
            null -> out.remove(name)
            is Map<*, *> if entry.isEmpty() && name in base -> Unit
            else -> out[name] = entry
        }
    }
    return out
}

private fun restateKeyed(rule: MergeRule.RestateKeyed, base: List<*>, over: List<*>, path: String, problems: MutableList<Problem>): List<Any?> {
    val out = mutableListOf<Any?>()
    for (incoming in over) {
        val element = (incoming as? Map<*, *>)?.toJsonMapOrEmpty()
        val key = element?.get(rule.key)
        if (element == null || key == null) {
            problems.add(Problem(OverlayMergeError.missingKey, "An entry here carries no '${rule.key}', so it names nothing.", ProblemLocation(path)))
            continue
        }
        // Naming only the key keeps the base's element: how an overlay lists an entry it does not change.
        val inherited = if (element.size == 1) base.firstOrNull { (it as? Map<*, *>)?.get(rule.key) == key } else null
        out.add(inherited ?: element)
    }
    return out
}

private fun restate(base: Map<String, Any?>, over: Map<String, Any?>): Map<String, Any?> {
    // In the overlay's own order: a restated set says its order too, which for `properties` is the payload order.
    val out = LinkedHashMap<String, Any?>(over.size)
    for ((name, entry) in over) {
        // Only an absent body inherits -- null, or an empty object; any other value, a scalar included, is stated.
        val inherits = entry == null || (entry is Map<*, *> && entry.isEmpty())
        out[name] = if (inherits) base[name] ?: entry else entry
    }
    return out
}

private fun mergeKeyed(
    rule: MergeRule.Keyed,
    base: List<*>,
    over: List<*>,
    path: String,
    problems: MutableList<Problem>,
): List<Any?> {
    val out = base.toMutableList()
    for (incoming in over) {
        val element = (incoming as? Map<*, *>)?.toJsonMapOrEmpty()
        val key = element?.get(rule.key)
        if (element == null || key == null) {
            problems.add(Problem(OverlayMergeError.missingKey, "An entry here carries no '${rule.key}', so it matches nothing.", ProblemLocation(path)))
            continue
        }
        val at = out.indexOfFirst { (it as? Map<*, *>)?.get(rule.key) == key }
        if (at >= 0) {
            val prior = (out[at] as Map<*, *>).toJsonMapOrEmpty()
            out[at] = when {
                rule.element == KeyedElement.replace -> element
                rule.elementSpec != null -> mergeResource(rule.elementSpec, prior, element, "$path[$key]", problems)
                else -> prior + element
            }
            continue
        }
        when (rule.onNew) {
            OnNew.append -> out.add(element)
            OnNew.refuse -> problems.add(
                Problem(
                    OverlayMergeError.unmatchedElement,
                    "'$key' matches no entry here, and this list does not take new ones: its order matters, and " +
                        "nothing says where the entry would go." + (rule.refusalHint?.let { " $it" } ?: ""),
                    ProblemLocation("$path[$key]"),
                ),
            )
        }
    }
    return out
}

/** A diff's result: the overlay, and the changes it could not state (each located at its path in the resource). */
class DiffOutcome(val overlay: Map<String, Any?>, val problems: List<Problem>)

/**
 * The overlay that [mergeOverlay] by [spec] turns [base] into [edited] (issue #1120), stating only what differs: a key
 * whose value is the base's is left out, and one [edited] no longer has is `null`. A part is diffed by the rule that
 * would merge it -- by default, not by any choice an overlay could make -- so a merged map names only its changed
 * entries, a keyed list only its changed elements, and a resource is diffed by its own spec.
 *
 * A change the rule cannot state is reported, not written: a keyed list's element removed, moved, or (where the list
 * refuses new ones) added. What the overlay does state merges back to [edited] exactly.
 */
fun diffOverlay(spec: MergeSpec, base: Map<String, Any?>, edited: Map<String, Any?>, path: String = ""): DiffOutcome {
    val problems = mutableListOf<Problem>()
    val out = LinkedHashMap<String, Any?>()
    for ((key, value) in edited) {
        if (key == spec.directiveKey) continue
        if (key !in base) {
            out[key] = value
        } else if (base[key] != value) {
            diffValue(spec.ruleFor(key), base[key], value, childPath(path, key), problems)?.let { out[key] = it.value }
        }
    }
    for (key in base.keys) {
        if (key in edited || key == spec.directiveKey) continue
        // A removal, which an append-only list cannot take: reported here, where the edit is judged, not on a later merge.
        if (spec.ruleFor(key) == MergeRule.Append && base[key] != null) {
            problems.add(Problem(OverlayMergeError.cannotStateChange, appendOnlyMessage, ProblemLocation(childPath(path, key))))
        } else {
            out[key] = null
        }
    }
    return DiffOutcome(out, problems)
}

/** What a diff states for one part: [value], which may itself be null (a removal). */
private class Stated(val value: Any?)

/** The overlay value making [base] into the different [edited] under [rule], or null when it cannot be stated. */
private fun diffValue(rule: MergeRule, base: Any?, edited: Any?, path: String, problems: MutableList<Problem>): Stated? {
    if (edited == null && base != null && rule == MergeRule.Append) {
        problems.add(Problem(OverlayMergeError.cannotStateChange, appendOnlyMessage, ProblemLocation(path)))
        return null
    }
    if (edited == null || base == null) return Stated(edited)
    return when (rule) {
        MergeRule.Replace -> Stated(edited)
        // The base's elements, unchanged and first, then what was added; anything else would change what it holds.
        MergeRule.Append -> if (base is List<*> && edited is List<*> && edited.size > base.size && edited.subList(0, base.size) == base) {
            Stated(edited.subList(base.size, edited.size))
        } else {
            problems.add(Problem(OverlayMergeError.cannotStateChange, appendOnlyMessage, ProblemLocation(path)))
            null
        }
        MergeRule.Merge -> if (base is Map<*, *> && edited is Map<*, *>) {
            Stated(diffEntries(base.toJsonMapOrEmpty(), edited.toJsonMapOrEmpty()) { _, _, e -> Stated(e) })
        } else {
            Stated(edited)
        }
        MergeRule.Restate -> if (base is Map<*, *> && edited is Map<*, *>) {
            // Every entry named, in the edited order; one the base already has as it is, by `{}` -- unless it is not a
            // map, which `{}` could not stand for.
            val b = base.toJsonMapOrEmpty()
            Stated(edited.toJsonMapOrEmpty().mapValues { (name, e) -> if (b[name] == e && e is Map<*, *>) emptyMap<String, Any?>() else e })
        } else {
            Stated(edited)
        }
        is MergeRule.Keyed -> if (base is List<*> && edited is List<*>) diffKeyed(rule, base, edited, path, problems) else Stated(edited)
        is MergeRule.RestateKeyed -> if (base is List<*> && edited is List<*>) {
            val byKey = base.filterIsInstance<Map<*, *>>().associateBy { it[rule.key] }
            Stated(edited.map { e -> if (e is Map<*, *> && e.size > 1 && byKey[e[rule.key]] == e) mapOf(rule.key to e[rule.key]) else e })
        } else {
            Stated(edited)
        }
        is MergeRule.Resource -> if (base is Map<*, *> && edited is Map<*, *>) {
            Stated(diffResource(rule.spec, base.toJsonMapOrEmpty(), edited.toJsonMapOrEmpty(), path, problems))
        } else {
            Stated(edited)
        }
        is MergeRule.Resources -> if (base is Map<*, *> && edited is Map<*, *>) {
            Stated(
                diffEntries(base.toJsonMapOrEmpty(), edited.toJsonMapOrEmpty()) { name, b, e ->
                    if (b is Map<*, *> && e is Map<*, *>) {
                        Stated(diffResource(rule.spec, b.toJsonMapOrEmpty(), e.toJsonMapOrEmpty(), childPath(path, name), problems))
                    } else {
                        Stated(e)
                    }
                },
            )
        } else {
            Stated(edited)
        }
    }
}

/** A merged map's changed entries, each [entry]'s statement of it, and `null` for one [edited] dropped. */
private fun diffEntries(
    base: Map<String, Any?>,
    edited: Map<String, Any?>,
    entry: (name: String, base: Any?, edited: Any?) -> Stated?,
): Map<String, Any?> {
    val out = LinkedHashMap<String, Any?>()
    for ((name, e) in edited) {
        if (name !in base) out[name] = e
        else if (base[name] != e) entry(name, base[name], e)?.let { out[name] = it.value }
    }
    for (name in base.keys) if (name !in edited) out[name] = null
    return out
}

private fun diffResource(
    spec: (Map<String, Any?>, Map<String, Any?>) -> MergeSpec,
    base: Map<String, Any?>,
    edited: Map<String, Any?>,
    path: String,
    problems: MutableList<Problem>,
): Map<String, Any?> {
    // The spec is chosen from the edited resource, as a merge chooses it from the overlay: what it would be merging.
    val outcome = diffOverlay(spec(base, edited), base, edited, path)
    problems.addAll(outcome.problems)
    return outcome.overlay
}

private fun diffKeyed(rule: MergeRule.Keyed, base: List<*>, edited: List<*>, path: String, problems: MutableList<Problem>): Stated? {
    val baseElements = base.mapNotNull { (it as? Map<*, *>)?.toJsonMapOrEmpty() }
    val editedElements = edited.mapNotNull { (it as? Map<*, *>)?.toJsonMapOrEmpty() }
    val baseKeys = baseElements.map { it[rule.key] }
    val editedKeys = editedElements.map { it[rule.key] }
    fun cannot(why: String): Stated? {
        problems.add(Problem(OverlayMergeError.cannotStateChange, why + (rule.refusalHint?.let { " $it" } ?: ""), ProblemLocation(path)))
        return null
    }
    // The base's elements stay, in their order; only the list's end may take new ones, and only where it appends.
    (baseKeys - editedKeys.toSet()).firstOrNull()?.let { return cannot("'$it' is gone from this list, which an overlay cannot remove an entry from.") }
    if (editedKeys.take(baseKeys.size) != baseKeys) return cannot("The entries here are in another order, which an overlay of this list cannot state.")
    if (editedKeys.size > baseKeys.size && rule.onNew == OnNew.refuse) {
        return cannot("'${editedKeys[baseKeys.size]}' is new here, and this list does not take new entries: its order matters.")
    }
    val out = mutableListOf<Any?>()
    editedElements.forEachIndexed { i, e ->
        val b = baseElements.getOrNull(i)
        when {
            b == null -> out.add(e)
            b == e -> Unit
            rule.element == KeyedElement.replace -> out.add(e)
            rule.elementSpec != null ->
                out.add(mapOf(rule.key to e[rule.key]) + diffResource(rule.elementSpec, b, e, "$path[${e[rule.key]}]", problems))
            else -> {
                // Folded entry by entry, so an entry dropped cannot be stated: a merged element only gains or changes.
                b.keys.firstOrNull { it !in e }?.let { return cannot("'${e[rule.key]}' no longer has '$it', which an overlay here cannot remove.") }
                out.add(mapOf(rule.key to e[rule.key]) + e.filter { (k, v) -> b[k] != v })
            }
        }
    }
    return Stated(out)
}

private const val appendOnlyMessage =
    "This list can only be added to: what the type already holds here stays, in its order, and an alteration's own entries follow it."
