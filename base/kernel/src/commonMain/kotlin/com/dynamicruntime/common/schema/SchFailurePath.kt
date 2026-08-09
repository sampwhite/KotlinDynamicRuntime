package com.dynamicruntime.common.schema

// Treating a SchFailure's `path` as a *location in the data* rather than as text to print.
//
// A failure already names where it happened -- `input.contacts[1].handle` -- in the spelling childPath and
// indexPath produce. Anything wanting to put a message beside the thing that caused it has to answer two
// questions about that path: which failures belong to *this* field, and which belong to this field or to
// anything inside it.
//
// Both live here, in the kernel beside the failures themselves, because they are plain operations over kernel
// types that no surface varies -- the endpoint form asks them per field, and a server that ever wants to
// summarize an error tree would ask exactly the same things. Keeping them here also keeps them testable
// without a browser, which the form traversal that consumes them is not.

/**
 * Is [path] the same location as [prefix], or somewhere inside it?
 *
 * Nesting is decided on the separator that follows the prefix, never on the raw text, so a sibling whose name
 * merely starts the same way is not swept up: `input.nameOfThing` is *not* inside `input.name`, and
 * `contacts10[0]` is not inside `contacts1`. An empty [prefix] is the root, and everything is inside the root.
 */
fun isPathAtOrBelow(path: String, prefix: String): Boolean {
    if (prefix.isEmpty()) return true
    if (!path.startsWith(prefix)) return false
    if (path.length == prefix.length) return true
    val next = path[prefix.length]
    return next == '.' || next == '['
}

/**
 * The property key immediately below [prefix] on the way to [path], or null when [path] is not a *keyed*
 * child of [prefix] — it is [prefix] itself, lies elsewhere, or is an array element (`contacts[0]`, whose
 * next step is an index rather than a key).
 *
 * Lets an object ask which of its children a failure belongs to without walking the tree: given
 * `input.address.street` and `input`, the answer is `address`. An object that knows its own declared
 * properties can then spot a failure reported against a key it does not declare — which is the one failure
 * with no field of its own to sit beside, since nothing renders a property the schema never had.
 */
fun childKeyOf(path: String, prefix: String): String? {
    if (!isPathAtOrBelow(path, prefix) || path.length <= prefix.length) return null
    if (prefix.isNotEmpty() && path[prefix.length] != '.') return null
    val start = if (prefix.isEmpty()) 0 else prefix.length + 1
    var end = start
    while (end < path.length && path[end] != '.' && path[end] != '[') end++
    return path.substring(start, end).takeIf { it.isNotEmpty() }
}

/**
 * The failures grouped by their exact path, so a render pass can look a field up rather than rescanning the
 * whole list once per field.
 */
fun List<SchFailure>.byPath(): Map<String, List<SchFailure>> = groupBy { it.path }

/** The failures reported at [prefix] or anywhere inside it. */
fun List<SchFailure>.atOrBelow(prefix: String): List<SchFailure> =
    filter { isPathAtOrBelow(it.path, prefix) }

/**
 * The failures that survive an edit at [prefix] — everything not on the same line of the tree.
 *
 * An edit invalidates a failure when one of the two paths contains the other, in **either** direction:
 *
 * - **Descendants**, because a structural edit reshapes what is under it. Removing an element from `contacts`
 *   re-indexes the rest, so a failure held against `contacts[1].handle` no longer refers to the value it was
 *   reported for, and keeping it would point a message at whatever moved into that slot.
 * - **Ancestors**, because a container's failure was computed from contents that just changed. Supplying
 *   `input.name` is precisely what brings `input` into existence, so *"Required property 'input' is missing"*
 *   is provably stale the moment the child is filled in — and leaving it up tells the person the opposite of
 *   what they just did.
 *
 * Siblings are untouched: `input.name` says nothing about `input.score`.
 *
 * This clears rather than re-checks, which is why it can afford to be generous. Dropping a failure that a
 * fresh validation would have raised again only means the person is told to validate; keeping one the edit
 * already fixed means telling them something false.
 */
fun List<SchFailure>.clearedAt(prefix: String): List<SchFailure> =
    filterNot { isPathAtOrBelow(it.path, prefix) || isPathAtOrBelow(prefix, it.path) }
