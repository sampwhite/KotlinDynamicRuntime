package com.dynamicruntime.common.schema

import com.dynamicruntime.common.util.toJsonMap
import com.dynamicruntime.common.util.toOptStr

/** The fields every schema function usage carries (issue #1501), whatever its kind. Each name matches its value. */
@Suppress("ConstPropertyName")
object SFU {
    /** Which registered function kind the usage is, as `traitId` says which trait an entry is. */
    const val fn = "fn"

    /** Run order within the usage's list -- lower first, ties keeping the order written. Zero when unstated. */
    const val priority = "priority"
}

/**
 * The life-cycle events of an object's data that a schema may attach functions to (issue #1501), each by its own
 * keyword. Only [write] exists: the others (a read, say, for a value that changes with the day rather than with the
 * data) wait for a need, and each would be one more member here and an executor for it, not another mechanism.
 *
 * The kernel knows only the event and its keyword, as it knows only a workflow event's identity (`WfEventType`
 * lives beside the workflow definition): what a function of an event *does* is registered Kotlin, in `base:common`.
 */
@Suppress("EnumEntryName")
enum class SchEventType(
    /** The `g-` keyword a type lists this event's functions under. */
    val keyword: String,
) {
    /** Runs on an object's data each time it is written, after an edit is merged ([SCH.onWrite]). */
    write(SCH.onWrite),
}

/**
 * A **usage** of a schema function as a type declares it (issue #1501): the raw `{fn, priority?, ...}` object, and
 * nothing more -- the shape a workflow function usage has (`WfFunctionUsage`). Pure data, because a function's
 * *kind* (its settings' schema, its execution) is Kotlin registered in `base:common` and unknown to the kernel.
 *
 * [fn] is null for a usage naming none: the parse does not judge, so a stored definition with such a usage still
 * compiles, and the check that resolves usages against the registered kinds reports it and drops it.
 */
class SchFunctionUsage(val initData: Map<String, Any?>) : JsonMappable {
    /** The function-kind discriminator, e.g. `computeValue`; null when the usage names none. */
    val fn: String? = initData[SFU.fn].toOptStr()

    /** Run order within the list -- lower first, ties keeping the order written. */
    val priority: Int = (initData[SFU.priority] as? Number)?.toInt() ?: 0

    /** The usage is its own settings, so a code-built and a stored definition round-trip identically. */
    override fun toJsonMap(): Map<String, Any?> = initData
}

/**
 * The function usages an event keyword's [raw] value holds, in run order ([SchFunctionUsage.priority], ties keeping
 * the order written). A value that is not a list, or an element that is not an object, contributes nothing: the
 * keyword's shape is judged before anything is parsed ([SchGKeywords]), so neither reaches here from a document the
 * parser accepts.
 */
fun parseFunctionUsages(raw: Any?): List<SchFunctionUsage> =
    (raw as? List<*>).orEmpty().mapNotNull { (it as? Map<*, *>)?.toJsonMap() }.map { SchFunctionUsage(it) }.sortedBy { it.priority }
