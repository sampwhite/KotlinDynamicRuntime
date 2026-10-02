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
 * of its own with an identity, merged by its own spec ([MergeRule.Resource]); a schema type's `g-layout` is the
 * first. The rules make this structural rather than a check: none of them takes a spec for the values it folds, so
 * a spec cannot express a third level.
 *
 * ### What is not here yet
 *
 * Removing an element (a tombstone) and placing a new one relative to a sibling (anchors: `into` / `before` /
 * `after`). A keyed list therefore either refuses an element matching nothing or appends it ([OnNew]), and a list
 * whose order matters refuses: placement is exactly what an anchor would say.
 *
 * ### Sharing
 *
 * Nothing in [base] or [overlay] is written to. A part the overlay does not mention is the base's value, shared by
 * reference -- a variant may create new nodes and point at old ones; it must not write into old ones
 * (`client-definition.md`).
 */
fun mergeOverlay(spec: MergeSpec, base: Map<String, Any?>, overlay: Map<String, Any?>, path: String = ""): MergeOutcome {
    val problems = mutableListOf<Problem>()
    val out = LinkedHashMap<String, Any?>(base.size + overlay.size)
    for ((key, value) in base) {
        out[key] = if (key !in overlay) value else mergeValue(spec.ruleFor(key), value, overlay[key], childPath(path, key), problems)
    }
    for ((key, value) in overlay) {
        if (key !in base) out[key] = value
    }
    return MergeOutcome(out, problems)
}

/** A merge's result, and the parts of the overlay it refused (each located at its path in the resource). */
class MergeOutcome(val value: Map<String, Any?>, val problems: List<Problem>)

/**
 * How a resource kind merges: a [MergeRule] per top-level key, and [default] for every key not named. The default
 * is [MergeRule.Replace], which is also what a key a spec has never heard of gets -- an overlay that mentions a key
 * means its value.
 */
class MergeSpec(val rules: Map<String, MergeRule>, val default: MergeRule = MergeRule.Replace) {
    fun ruleFor(key: String): MergeRule = rules[key] ?: default
}

/** How one part of a resource merges; see [mergeOverlay]. */
sealed interface MergeRule {
    /** The overlay's value wins whole. The default. */
    data object Replace : MergeRule

    /** A map whose entries the overlay sets one by one; an entry it does not mention is the base's. */
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
    ) : MergeRule

    /**
     * A map that is **the set the overlay mentions**: an entry it leaves out is gone, and an empty entry (`{}`, or
     * null) keeps the base's. The rule for a contract list -- a type's `properties` -- where somebody reading an
     * alteration should see every entry it has, rather than a fragment plus whatever the base happened to hold.
     */
    data object Restate : MergeRule

    /**
     * A part that is a resource of its own, merged by its own spec -- which may depend on what is being merged (a
     * layout's [MergeRule.Keyed] list refuses new elements only when its mode makes order mean something). When the
     * base has none, the overlay's stands as written; an overlay of `null` removes it.
     */
    class Resource(val spec: (base: Map<String, Any?>, overlay: Map<String, Any?>) -> MergeSpec) : MergeRule
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
}

private fun mergeValue(rule: MergeRule, base: Any?, over: Any?, path: String, problems: MutableList<Problem>): Any? {
    // Null in an overlay always means "none": a removal, under every rule.
    if (over == null) return null
    return when (rule) {
        MergeRule.Replace -> over
        MergeRule.Merge -> if (base is Map<*, *> && over is Map<*, *>) base.toJsonMapOrEmpty() + over.toJsonMapOrEmpty() else over
        MergeRule.Restate -> if (base is Map<*, *> && over is Map<*, *>) restate(base.toJsonMapOrEmpty(), over.toJsonMapOrEmpty()) else over
        is MergeRule.Keyed -> if (base is List<*> && over is List<*>) mergeKeyed(rule, base, over, path, problems) else over
        is MergeRule.Resource -> if (base is Map<*, *> && over is Map<*, *>) {
            val b = base.toJsonMapOrEmpty()
            val o = over.toJsonMapOrEmpty()
            val outcome = mergeOverlay(rule.spec(b, o), b, o, path)
            problems.addAll(outcome.problems)
            outcome.value
        } else {
            over
        }
    }
}

private fun restate(base: Map<String, Any?>, over: Map<String, Any?>): Map<String, Any?> {
    // In the overlay's own order: a restated set says its order too, which for `properties` is the payload order.
    val out = LinkedHashMap<String, Any?>(over.size)
    for ((name, entry) in over) {
        val declared = (entry as? Map<*, *>)?.toJsonMapOrEmpty()
        out[name] = if (declared.isNullOrEmpty()) base[name] ?: entry else entry
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
    val out = base.toMutableList<Any?>()
    for (incoming in over) {
        val element = (incoming as? Map<*, *>)?.toJsonMapOrEmpty()
        val key = element?.get(rule.key)
        if (element == null || key == null) {
            problems.add(Problem(OverlayMergeError.missingKey, "An entry here carries no '${rule.key}', so it matches nothing.", ProblemLocation(path)))
            continue
        }
        val at = out.indexOfFirst { (it as? Map<*, *>)?.get(rule.key) == key }
        if (at >= 0) {
            out[at] = when (rule.element) {
                KeyedElement.replace -> element
                KeyedElement.merge -> (out[at] as Map<*, *>).toJsonMapOrEmpty() + element
            }
            continue
        }
        when (rule.onNew) {
            OnNew.append -> out.add(element)
            OnNew.refuse -> problems.add(
                Problem(
                    OverlayMergeError.unmatchedElement,
                    "'$key' matches no entry here, and this list does not take new ones: its order matters, and " +
                        "nothing says where the entry would go.",
                    ProblemLocation("$path[$key]"),
                ),
            )
        }
    }
    return out
}
