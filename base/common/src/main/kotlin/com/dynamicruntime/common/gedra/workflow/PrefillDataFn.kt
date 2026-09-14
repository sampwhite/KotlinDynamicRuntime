package com.dynamicruntime.common.gedra.workflow

import com.dynamicruntime.common.context.KdrCxt
import com.dynamicruntime.common.exception.KdrException
import com.dynamicruntime.common.gedra.GE
import com.dynamicruntime.common.gedra.GSRC
import com.dynamicruntime.common.util.toOptStr

/**
 * The **execution** interface of the `prefillData` event (issue #679): a resolved [WfFunction] that supplies a
 * task's fields with default values as the workflow **view** is assembled -- never state. It lives in
 * `base:common` because it takes a [KdrCxt]; the kernel holds only the thin [WfFunction] base.
 *
 * A prefill decorates the view a page renders and nothing else: it runs **after** a task's completeness and
 * validity are judged (on the real entries), and the default it supplies is **presented as if entered** -- the
 * frontend renders it as user input -- but the backend state judgment never sees it, so it can never satisfy a
 * required trait. That guarantee is structural: [runPrefillData] merges defaults into the *presented* entries
 * only, never the entries requiredness is computed from.
 *
 * A concrete function lives in its own file (see [PrefillFromOwnerFn]). The base->event cast is the companion
 * [of] plus the [prefillDataFns] extension, so the one cast site is event-named.
 */
interface PrefillDataFn : WfFunction {
    /** Fixed for the event; the resolution pass already refused a `prefillData` function on the workflow-global list. */
    override val event: WfEventType get() = WfEventType.prefillData

    /**
     * Reads what it needs from [params] (the form owner's attributes) and supplies defaults through
     * [PrefillDataParams.supply], returning nothing -- output travels in the parameters object. Called once per
     * task as the view resolves.
     */
    fun prefill(cxt: KdrCxt, params: PrefillDataParams)

    companion object {
        /**
         * Narrows a resolved [WfFunction] to a [PrefillDataFn]. The resolution second pass already checked that a
         * function on a task's `prefillData` list is a `prefillData` kind, so a failure here is a wiring bug (a
         * creation returning the wrong execution type), not a config error -- hence a raw throw.
         */
        fun of(fn: WfFunction): PrefillDataFn = fn as? PrefillDataFn
            ?: throw KdrException(
                "Workflow function '${fn.fn}' sits on a prefillData list but did not resolve to a ${PrefillDataFn::class.simpleName}.",
            )
    }
}

/**
 * What a [PrefillDataFn] is handed on execution (issue #679): the form owner's [ownerAttributes] (e.g., their
 * `publicName`) to read a default from, and a sink for the defaults it concludes. A class with an output slot
 * rather than a return value, per the implementation rule that a function's execution method returns void and
 * communicates through its object.
 *
 * A function need not check whether a trait already has an entry: [runPrefillData] drops the defaults for any
 * trait that does, so a prefill only ever creates an entry, never overrides entered data.
 */
class PrefillDataParams(
    val ownerAttributes: Map<String, Any?>,
) {
    // trait id -> its default data (nested where a supplied path was dotted), in supply order.
    private val defaults = LinkedHashMap<String, LinkedHashMap<String, Any?>>()

    /**
     * Supplies [value] as the default at [valuePath] of [traitId]'s data. [valuePath] is a dotted path (the
     * write counterpart of a `computeCFactsFromData` read path), so `address.city` nests -- symmetric with how
     * a value is read. A null value supplies nothing.
     */
    fun supply(traitId: String, valuePath: String, value: Any?) {
        if (value == null) {
            return
        }
        putAtPath(defaults.getOrPut(traitId) { LinkedHashMap() }, valuePath.split('.'), value)
    }

    /** The supplied defaults, trait id to its default data -- read by [runPrefillData] to decorate the view. */
    internal fun supplied(): Map<String, Map<String, Any?>> = defaults
}

/** Sets [value] at the dotted [segments] of [data], building intermediate maps; a non-map on the way is replaced. */
private fun putAtPath(data: MutableMap<String, Any?>, segments: List<String>, value: Any?) {
    var current = data
    for (i in 0 until segments.size - 1) {
        @Suppress("UNCHECKED_CAST")
        val existing = current[segments[i]] as? MutableMap<String, Any?>
        current = existing ?: LinkedHashMap<String, Any?>().also { current[segments[i]] = it }
    }
    current[segments.last()] = value
}

/** This task's resolved `prefillData` functions, in priority order -- the task-scoped list A resolved. */
fun WfTask.prefillDataFns(): List<PrefillDataFn> =
    resolvedFunctions.filter { it.event == WfEventType.prefillData }.map { PrefillDataFn.of(it) }

/**
 * Runs [task]'s `prefillData` functions over the form owner's [ownerAttributes] and returns [currentEntries]
 * plus a synthetic **prefill** entry for each defaulted trait that has **no entry yet** -- the **presented**
 * entries a task view carries (issue #679). A prefill defaults a whole trait, not a field: a trait that already
 * has an entry (any real data) is left untouched, so a prefill only ever *creates*, never merges into or
 * overrides what a person gave. Each synthetic entry carries [GSRC.prefill] as its [GE.source], so the frontend
 * presents it as a default and tells it apart from entered data. It is view-only -- never written, and never
 * counted toward requiredness, which is judged on [currentEntries] before this runs.
 */
fun runPrefillData(
    cxt: KdrCxt,
    task: WfTask,
    ownerAttributes: Map<String, Any?>,
    currentEntries: List<Map<String, Any?>>,
): List<Map<String, Any?>> {
    val fns = task.prefillDataFns()
    if (fns.isEmpty()) {
        return currentEntries
    }
    val params = PrefillDataParams(ownerAttributes)
    fns.forEach { it.prefill(cxt, params) }
    val defaultsByTrait = params.supplied()
    if (defaultsByTrait.isEmpty()) {
        return currentEntries
    }

    val present = currentEntries.mapNotNull { it[GE.traitId].toOptStr() }.toSet()
    val prefills = defaultsByTrait
        // A trait with an entry already is left alone; a prefill only creates one where none exists.
        .filterKeys { it !in present }
        .map { (traitId, fieldDefaults) ->
            linkedMapOf<String, Any?>(GE.traitId to traitId, GE.data to fieldDefaults, GE.source to GSRC.prefill)
        }
    return if (prefills.isEmpty()) currentEntries else currentEntries + prefills
}
