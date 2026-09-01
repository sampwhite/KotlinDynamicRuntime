package com.dynamicruntime.common.uiblock

import com.dynamicruntime.common.cfact.CFactPredicate
import com.dynamicruntime.common.cfact.parseCFactOrAlways
import com.dynamicruntime.common.context.KdrCxt
import com.dynamicruntime.common.exception.KdrException
import com.dynamicruntime.common.startup.SchemaService
import com.dynamicruntime.common.startup.ServiceInitializer
import com.dynamicruntime.common.util.toOptStr
import java.util.concurrent.ConcurrentHashMap

/**
 * Resolves a UiBlock for a caller (issue #457): the layers merged for their client, then the items whose
 * conditions they do not satisfy taken out.
 *
 * ### Two stages, at two binding times
 *
 * **Merging** depends only on the layers, which are fixed at boot, so it is done once per (block, client) and
 * cached. **cfact resolution** depends on who is calling and cannot be cached at all. Splitting them is what
 * keeps the per-request work to one walk of a small map.
 *
 * ### Why the backend resolves the conditions
 *
 * The frontend receives what it may see; an item whose condition did not match is simply **absent**, exactly
 * as the menu behaves today. Sending expressions instead would mean sending the caller's cfact set with them,
 * which puts `isAdmin` -- and whatever private vocabulary a client registered -- on the wire. Fragment
 * references go the other way and are left for the frontend, because they resolve against copy it has already
 * cached; see `ui-block.md`.
 */
class UiBlockService : ServiceInitializer {
    override val serviceName: String = UiBlockService.serviceName

    /** Merged content per `blockId|client`, computed once. */
    private val mergedCache = ConcurrentHashMap<String, MergedUiBlock>()

    /** Parsed predicates per `client|expression`, so an expression is parsed once however often it is read. */
    private val predicateCache = ConcurrentHashMap<String, CFactPredicate>()

    /**
     * Refuses the boot when a UiBlock is malformed (issue #457) -- here rather than at first use, because a
     * block is read while a page is being built, and a page is the worst place to discover a typo.
     *
     * Two things are checked, and they are the two that otherwise fail silently:
     *
     * - **An overlay naming a block nobody registered.** Nothing would fail: the overlay would merge onto
     *   nothing and its content would never be served, so the customer-specific item simply never appears.
     * - **Every cfact expression, against every client's registry.** A bad name is refused at parse, and this
     *   is where every expression is parsed for the first time. Per client, because the vocabulary varies by
     *   client (issue #455) and an expression valid globally may name nothing at one customer.
     */
    override fun checkInit(cxt: KdrCxt) {
        val sources = registeredUiBlocks(cxt)
        if (sources.isEmpty()) {
            return
        }
        val problems = uiBlockProblems(sources) { client -> SchemaService.get(cxt).cfactsFor(client).names }
        if (problems.isNotEmpty()) {
            throw KdrException(
                "Refusing to start: ${problems.size} problem(s) with UiBlocks.\n" + problems.joinToString("\n"),
            )
        }
    }

    /**
     * [blockId] as this caller should see it: merged for their client, with everything their cfacts do not
     * satisfy removed. Null when nothing registers the block.
     */
    fun resolve(cxt: KdrCxt, blockId: String, targetFacts: Set<String> = emptySet()): Map<String, Any?>? {
        val sources = registeredUiBlocks(cxt).filter { it.blockId == blockId }
        if (sources.isEmpty()) {
            return null
        }
        // A client that overlays nothing merges to the shared content, so it gets no variant of its own.
        val client = cxt.client.takeIf { c -> sources.any { it.client == c } }
        val merged = mergedCache.getOrPut("$blockId|${client ?: ""}") { mergeUiBlock(blockId, sources, client) }
        if (!merged.found) {
            return null
        }
        val registry = SchemaService.get(cxt).cfactsFor(client)
        val present = registry.assemble(cxt, targetFacts)
        return filterByCFacts(merged.content, present) { expression ->
            predicateCache.getOrPut("${client ?: ""}|$expression") { parseCFactOrAlways(expression, registry.names) }
        }
    }

    @Suppress("ConstPropertyName")
    companion object {
        const val serviceName = "UiBlockService"

        fun get(cxt: KdrCxt): UiBlockService =
            cxt.instanceConfig.get(serviceName) as? UiBlockService
                ?: throw KdrException("The $serviceName is not available on this node.")

        /** Every UiBlock layer this node carries, collected at boot by `InstanceRegistry`. */
        fun registeredUiBlocks(cxt: KdrCxt): List<UiBlockSource> =
            (cxt.instanceConfig.get(UIB.registryKey) as? List<*>)?.filterIsInstance<UiBlockSource>() ?: emptyList()
    }
}

/**
 * Everything wrong with [sources], as messages (issue #457) -- the pure half of [UiBlockService.checkInit].
 *
 * Takes [allowedFor] rather than reading a service, for the reason `SchemaService.checkOptionsSources` does:
 * a test can then ask what a real set of blocks would say when checked against a vocabulary that is missing
 * something, which is the only way to see that the walk reaches the expressions at all rather than quietly
 * finding nothing.
 *
 * Every problem is collected before any is reported, so somebody fixing a block sees all of them in one boot.
 */
fun uiBlockProblems(sources: List<UiBlockSource>, allowedFor: (String?) -> Set<String>): List<String> {
    val problems = mutableListOf<String>()
    val declared = sources.filter { !it.isOverlay }.map { it.blockId }.toSet()
    for (overlay in sources.filter { it.isOverlay }) {
        if (overlay.blockId !in declared) {
            problems.add(
                "The overlay of UiBlock '${overlay.blockId}' from ${overlay.origin} names a block nothing " +
                    "registers. It would merge onto nothing and never be served.",
            )
        }
    }
    // Actions are checked once per block rather than per client: the vocabulary is hardwired in shared code
    // (`UiActions`), so unlike a cfact name it cannot differ between customers.
    for (source in sources) {
        collectActions(source.content).forEach { call ->
            val declared = UiActions.forName(call.first)
            when {
                declared == null -> problems.add(
                    "UiBlock '${source.blockId}' (${source.origin}) calls '${call.first}', which no frontend " +
                        "function declares. A name nothing implements is a click that does nothing.",
                )
                declared.arity != call.second -> problems.add(
                    "UiBlock '${source.blockId}' (${source.origin}) calls '${call.first}' with ${call.second} " +
                        "parameter(s); it takes ${declared.arity}.",
                )
            }
        }
    }
    // Every scope a block is ever resolved for: the shared one, and each client that overlays something. Per
    // client because the vocabulary varies by client (issue #455), so an expression valid globally may name
    // nothing at one customer -- and that customer is who would find out.
    val clients = listOf<String?>(null) + sources.mapNotNull { it.client }.distinct()
    for (blockId in declared) {
        for (client in clients) {
            val merged = mergeUiBlock(blockId, sources, client)
            val allowed = allowedFor(client)
            collectExpressions(merged.content).forEach { expression ->
                runCatching { parseCFactOrAlways(expression, allowed) }.exceptionOrNull()?.let {
                    val where = blockId + (client?.let { c -> " (client '$c')" } ?: "")
                    problems.add("UiBlock '$where': ${it.message}")
                }
            }
            // Parenting is a property of the merged structure, not the caller (cfacts have not been applied
            // yet, so every item is present) -- but a client overlay can add an item with a bad parent, so it
            // is checked per client like the expressions above.
            problems.addAll(collectParentIssues(merged, client))
        }
    }
    return problems
}

/**
 * Every parenting problem in [merged] (issue #517) -- the boot check behind [UIB.parentId], the mirror of
 * `ClientCheck`'s one-level extension rule for menu-style drill-downs.
 *
 * Walked over each array the base keys by a primary field ([MergedUiBlock.arrayKeys]), so it is general over
 * any keyed array rather than knowing about menus. For each item that names a [UIB.parentId]:
 *  - the named parent must be **another item in that same array** (a typo or a dropped-elsewhere id is refused,
 *    not silently rendered parentless);
 *  - it must not name **itself**;
 *  - the parent must be **top-level** -- carry no parentId of its own, since nesting is one level.
 *
 * And separately: an item that **is** a parent (some sibling names it) must carry no [UIB.action], because a
 * drill-down draws a parent as a group header and would silently discard its route or call.
 */
fun collectParentIssues(merged: MergedUiBlock, client: String? = null): List<String> {
    val problems = mutableListOf<String>()
    val where = merged.blockId + (client?.let { " (client '$it')" } ?: "")
    for ((path, keyField) in merged.arrayKeys) {
        val items = (arrayAtPath(merged.content, path) ?: continue)
            .filterIsInstance<Map<*, *>>().map { asObject(it) }
        val byId = items.associateBy { it[keyField].toOptStr() }
        // An item is a parent iff some sibling names it; such an item must not also carry an action of its own.
        val referencedParents = items.mapNotNull { it[UIB.parentId].toOptStr() }.toSet()
        for (item in items) {
            val id = item[keyField].toOptStr()
            if (id != null && id in referencedParents && item[UIB.action] != null) {
                problems.add(
                    "UiBlock '$where': item '$id' in array '$path' has children and its own action. A parent " +
                        "is drawn as a group header, so its action would be silently discarded -- give the " +
                        "action to a child, or drop the children.",
                )
            }
            val parentId = item[UIB.parentId].toOptStr() ?: continue
            val parent = byId[parentId]
            when {
                parent == null -> problems.add(
                    "UiBlock '$where': item '$id' in array '$path' names parent '$parentId', which no item in " +
                        "that array declares. A child whose parent is absent would render parentless.",
                )
                parentId == id -> problems.add(
                    "UiBlock '$where': item '$id' in array '$path' names itself as its parent.",
                )
                parent[UIB.parentId].toOptStr() != null -> problems.add(
                    "UiBlock '$where': item '$id' in array '$path' nests under '$parentId', which itself has a " +
                        "parent. Nesting is one level: a child is drawn under a top-level item, not under " +
                        "another child.",
                )
            }
        }
    }
    return problems
}

/** The array at a dotted [path] from [content]'s root (`"nav.items"`), or null if the path names no array. */
private fun arrayAtPath(content: Map<String, Any?>, path: String): List<*>? {
    var node: Any? = content
    for (segment in path.split(".")) {
        node = (node as? Map<*, *>)?.get(segment) ?: return null
    }
    return node as? List<*>
}

/**
 * Removes every object whose [UIB.cfactExpression] expression is not satisfied by [present].
 *
 * **Recursive, and keyed on the presence of the field rather than on where the object sits.** One rule, so a
 * UiBlock can grow a shape the resolver has never seen -- a section, an action inside an item -- and its
 * conditions work without this learning about it. An object dropped from an array is removed; an object
 * dropped from a field leaves that field absent.
 */
fun filterByCFacts(
    node: Map<String, Any?>,
    present: Set<String>,
    predicate: (String) -> CFactPredicate,
): Map<String, Any?> {
    val out = LinkedHashMap<String, Any?>()
    for ((key, value) in node) {
        // Neither the condition nor the order travels. Both have already done their work by now -- the
        // condition decided whether this object is here at all, and the order decided where it sits in an
        // array that is now sorted -- and shipping them would put the caller's vocabulary on the wire and
        // invite a frontend to re-sort a list the backend already ordered.
        if (key == UIB.cfactExpression || key == UIB.displayOrder) continue
        when (value) {
            is Map<*, *> -> {
                val child = asObject(value)
                if (matches(child, present, predicate)) out[key] = filterByCFacts(child, present, predicate)
            }
            is List<*> -> out[key] = value.mapNotNull { element ->
                if (element is Map<*, *>) {
                    val child = asObject(element)
                    if (matches(child, present, predicate)) filterByCFacts(child, present, predicate) else null
                } else {
                    element
                }
            }
            else -> out[key] = value
        }
    }
    return out
}

/** Whether [node]'s condition (if it states one) is satisfied; an object stating none always matches. */
private fun matches(node: Map<String, Any?>, present: Set<String>, predicate: (String) -> CFactPredicate): Boolean {
    val expression = node[UIB.cfactExpression].toOptStr() ?: return true
    return predicate(expression).matches(present)
}

/** Every call anywhere in [node] as `(name, parameterCount)`, for the boot check; routes are not calls. */
fun collectActions(node: Map<String, Any?>): List<Pair<String, Int>> {
    val out = mutableListOf<Pair<String, Int>>()
    for ((key, value) in node) {
        when {
            key == UIB.action && value is List<*> -> {
                val name = value.firstOrNull() as? String
                if (name != null) out.add(name to value.size - 1)
            }
            value is Map<*, *> -> out.addAll(collectActions(asObject(value)))
            value is List<*> -> value.forEach { if (it is Map<*, *>) out.addAll(collectActions(asObject(it))) }
        }
    }
    return out
}

/** Every cfact expression anywhere in [node], for the boot check. */
fun collectExpressions(node: Map<String, Any?>): List<String> {
    val out = mutableListOf<String>()
    for ((key, value) in node) {
        when {
            key == UIB.cfactExpression -> value.toOptStr()?.let { out.add(it) }
            value is Map<*, *> -> out.addAll(collectExpressions(asObject(value)))
            value is List<*> -> value.forEach { if (it is Map<*, *>) out.addAll(collectExpressions(asObject(it))) }
        }
    }
    return out
}

@Suppress("UNCHECKED_CAST")
private fun asObject(map: Map<*, *>): Map<String, Any?> = map as Map<String, Any?>
