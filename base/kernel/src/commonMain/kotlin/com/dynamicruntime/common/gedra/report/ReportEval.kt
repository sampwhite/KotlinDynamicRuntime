package com.dynamicruntime.common.gedra.report

import com.dynamicruntime.common.exception.KdrException
import com.dynamicruntime.common.gedra.GE
import com.dynamicruntime.common.gedra.GT
import com.dynamicruntime.common.gedra.canonicalKey
import com.dynamicruntime.common.gedra.entryKeyValuesOrNull
import com.dynamicruntime.common.gedra.workflow.FormWorkflow
import com.dynamicruntime.common.gedra.workflow.WFS
import com.dynamicruntime.common.gedra.workflow.WfColumnCategory
import com.dynamicruntime.common.gedra.workflow.WfPhase
import com.dynamicruntime.common.gedra.workflow.currentApprovals
import com.dynamicruntime.common.gedra.workflow.engagementDataOf
import com.dynamicruntime.common.gedra.workflow.formStatusOf
import com.dynamicruntime.common.gedra.workflow.formWorkflowsOf
import com.dynamicruntime.common.gedra.workflow.surveyStatusOf
import com.dynamicruntime.common.gedra.workflow.workflowStateDataOf
import com.dynamicruntime.common.schema.parseExactBool
import com.dynamicruntime.common.util.formatDay
import com.dynamicruntime.common.util.fmt
import com.dynamicruntime.common.util.parseDateOrNull
import com.dynamicruntime.common.util.parseDayOrNull
import com.dynamicruntime.common.util.pluckDataPath
import com.dynamicruntime.common.util.toJsonListOrEmpty
import com.dynamicruntime.common.util.toJsonMapOrEmpty
import com.dynamicruntime.common.util.toOptStr
import kotlin.math.abs
import kotlin.math.floor
import kotlin.time.Instant
import kotlinx.datetime.LocalDate

/**
 * How the several values of one column are made into one, **within a single form** (issue #978): a keyed trait read
 * whole (`yearly[*]`), a list in the data, a user's labels. Which kinds each is legal for is [allows]; the kind of
 * what it yields is [resultKind].
 */
@Suppress("EnumEntryName")
enum class ReportCombine {
    /** The first value in order -- for a keyed trait, the lowest key's. */
    first,

    /** Every value, in order. */
    list,

    /** Each value once, sorted. */
    distinct,

    /** How many values there are. */
    count,

    sum,
    min,
    max,
    avg;

    /** Whether this may combine values of [kind]: arithmetic needs numbers, and an order needs something ordered. */
    fun allows(kind: ReportKind): Boolean = when (this) {
        first, list, distinct, count -> true
        sum, avg -> kind == ReportKind.number
        min, max -> kind != ReportKind.boolean
    }

    /** The kind of the combined value: a count is a number whatever it counts, and the rest keep [kind]. */
    fun resultKind(kind: ReportKind): ReportKind = if (this == count) ReportKind.number else kind

    /** Whether the combined value is itself a list. Such a column cannot be grouped by. */
    val yieldsList: Boolean get() = this == list || this == distinct

    companion object {
        /**
         * The combine a column gets when it names none: [first] for a path that has one value, [list] for one that
         * may have several -- so the default never drops a value silently.
         */
        fun defaultFor(multiValued: Boolean): ReportCombine = if (multiValued) list else first
    }
}

/**
 * One form, as a report reads it (issue #978). The backend assembles it; the evaluator only reads maps, which is what
 * lets the frontend run the same code.
 */
class ReportSubject(
    /** The form's own facts, keyed by `RMETA`: its id, client, org, owner and dates. Status and cfacts come from [states]. */
    val meta: Map<String, Any?>,
    /** The form's data entries, with derived fields already applied. */
    val entries: List<Map<String, Any?>>,
    /** The form's state entries: workflow state, engagement, approvals, survey completion, cfacts. */
    val states: List<Map<String, Any?>>,
    /** The owner's account, keyed by `RUSR`; null when the owner is unknown or outside what the caller may see. */
    val owner: Map<String, Any?>?,
    /** A workflow's phase now, or null for one this client does not have -- as `formWorkflowsOf` takes it. */
    val phaseOf: (workflowId: String) -> WfPhase?,
) {
    /** The form's workflows as its listing cell shows them, by id; computed once however many columns ask. */
    val workflows: Map<String, FormWorkflow> by lazy { formWorkflowsOf(states, phaseOf).associateBy { it.workflowId } }
}

/**
 * A path bound to what it reads (issue #978): the parsed [path], and what only a client's configuration knows --
 * the trait's primary-key fields ([pkFields], empty for an unkeyed trait and for every other source), the [kind] of
 * the value, and whether the path may yield several ([multiValued]). The registry builds one when it checks a
 * report; a test builds one by hand.
 */
class BoundPath(
    val path: ReportPath,
    val pkFields: List<String>,
    val kind: ReportKind,
    val multiValued: Boolean,
)

/**
 * The column's value for [subject]: what the path reads, each value coerced to the path's kind, the empty ones
 * dropped, and the rest made one by [combine]. Null -- a blank cell -- when there is nothing to show.
 */
fun BoundPath.valueOf(subject: ReportSubject, combine: ReportCombine = ReportCombine.defaultFor(multiValued)): Any? {
    val values = rawValues(subject).mapNotNull { coerceReportValue(it, kind) }.filterNot { isEmptyReportValue(it) }
    return combineReportValues(values, kind, combine)
}

/**
 * Every value the path reads from [subject], as stored, in order, with nulls and nesting removed. A value that is
 * missing -- no such entry, no such field, a workflow the form has no state for -- is simply not in the list.
 */
fun BoundPath.rawValues(subject: ReportSubject): List<Any?> = when (val p = path) {
    is FormPath -> formValues(p, pkFields, subject.entries)
    is WorkflowPath -> listOfNotNull(workflowValue(p, subject))
    is UserPath -> flatValues(subject.owner?.get(p.attrName))
    is MetaPath -> flatValues(metaValue(p.attrName, subject))
}

private fun formValues(path: FormPath, pkFields: List<String>, entries: List<Map<String, Any?>>): List<Any?> {
    val ofTrait = entries.filter { it[GE.traitId].toOptStr() == path.traitId }
    val picked = if (pkFields.isEmpty()) {
        ofTrait
    } else {
        // An entry with no key -- stored before its trait was keyed -- cannot be told from another, and is skipped.
        ofTrait.mapNotNull { entry -> entryKeyValuesOrNull(entry, pkFields)?.let { it to entry } }
            .filter { (key, _) -> selects(path.selector, pkFields, key) }
            .sortedWith { a, b -> compareReportKeys(a.first, b.first) }
            .map { it.second }
    }
    return picked.flatMap { entry ->
        val envField = path.envField
        if (envField != null) flatValues(entry[envField])
        else flatValues(pluckDataPath(entry[GE.data].toJsonMapOrEmpty(), path.dataPath))
    }
}

/** Whether the entry whose key values are [key] is one [selector] picks. No selector on a keyed trait picks them all. */
private fun selects(selector: KeySelector?, pkFields: List<String>, key: List<Any>): Boolean = when (selector) {
    null, is KeySelector.All -> true
    is KeySelector.Positional ->
        selector.values.size == key.size && selector.values.indices.all { keyTextMatches(key[it], selector.values[it]) }
    is KeySelector.Named -> selector.values.all { (field, text) ->
        val at = pkFields.indexOf(field)
        at >= 0 && keyTextMatches(key[at], text)
    }
}

/**
 * Whether a selector's [text] names the key value [value]. By canonical key, under which a year held as `2024`,
 * `2024.0` or `"2024"` is one key -- and, for a numeric key, by number, so a path written `[2024.0]` finds the year
 * too. Text is never read as a number against a *string* key: the code `02134` matches only `02134`. A boolean key is
 * matched by the spellings the registry accepts for one (`parseExactBool`), so `[yes]` finds the entry keyed `true`
 * rather than binding and then matching nothing.
 */
fun keyTextMatches(value: Any, text: String): Boolean =
    canonicalKey(value) == text ||
        (value is Number && plainNumberOrNull(text) == value.toDouble()) ||
        (value is Boolean && parseExactBool(text) == value)

/**
 * One fact about a workflow on the form. **A report says about a workflow only what the forms listing would show**:
 * every attribute is blank for a workflow the client no longer has, one outside its lifetime, and one closed to new
 * engagement that this form never engaged with ([WfPhase.isShown]) -- whatever state entries are left behind. The
 * state of a retired workflow is kept, not shown, and a report that showed "engaged" and "approved" for it would be
 * reporting work on something that is not there.
 *
 * Every name in `RWF.attrs` and `RWF.approvalAttrs` has a branch here. One without is a defect -- the parser
 * accepted a name this cannot read -- and is thrown as one rather than shown as a blank column.
 */
private fun workflowValue(path: WorkflowPath, subject: ReportSubject): Any? {
    val id = path.workflowId
    val engagement = engagementDataOf(subject.states, id)
    val engaged = engagement?.get(WFS.engaged) == true
    if (subject.phaseOf(id)?.isShown(engaged) != true) return null
    val taskId = path.taskId
    if (taskId != null) {
        val approval = currentApprovals(subject.states, id)[taskId]
        return when (path.attrName) {
            RWF.approved -> approval != null
            RWF.approvedAt -> approval?.get(WFS.approvedAt)
            RWF.approvedBy -> approval?.get(WFS.approvedBy)
            else -> throw unreadAttribute(path)
        }
    }
    val shown = subject.workflows[id]
    return when (path.attrName) {
        RWF.category -> shown?.category?.name
        RWF.finished -> shown?.category == WfColumnCategory.finished
        RWF.ctaTask -> shown?.ctaTask
        RWF.engaged -> engaged
        RWF.lastEngagedAt -> engagement?.get(WFS.lastEngagedAt)
        RWF.eligible -> workflowStateDataOf(subject.states, id)?.get(WFS.eligible) as? Boolean
        RWF.tasksDone -> workflowStateDataOf(subject.states, id)?.get(WFS.tasksDone) as? Boolean
        else -> throw unreadAttribute(path)
    }
}

private fun unreadAttribute(path: ReportPath): KdrException =
    KdrException("The report path '$path' names an attribute the evaluator has no reading for.")

private fun metaValue(attr: String, subject: ReportSubject): Any? = when (attr) {
    RMETA.formStatus -> formStatusOf(subject.states)
    RMETA.surveyStatus -> surveyStatusOf(subject.states)
    RMETA.cfacts -> subject.states.filter { it[GE.traitId].toOptStr() == GT.cfacts }
        .flatMap { it[GE.data].toJsonMapOrEmpty()[GT.facts].toJsonListOrEmpty() }
    else -> subject.meta[attr]
}

/**
 * [value] as a flat list: a list's elements, nested lists opened, nulls dropped; one value as itself. The nesting a
 * data path can produce is bounded by the path's own length, and [depth] holds the walk to it regardless.
 */
private fun flatValues(value: Any?, depth: Int = 0): List<Any?> = when {
    value == null -> emptyList()
    value is List<*> -> if (depth > RPT.maxFields) emptyList() else value.flatMap { flatValues(it, depth + 1) }
    else -> listOf(value)
}

/**
 * [value] as a value of [kind], or null when it is not one -- a blank cell, never an error: a report shows what is
 * stored, and one odd value must not cost the row. Typed, not text: a number is a `Long` or a `Double` ([reportNumber]), a date an
 * `Instant` -- or a `LocalDate` for a day written as a day, so a day-only field is shown as the day it is rather
 * than as a midnight.
 */
fun coerceReportValue(value: Any?, kind: ReportKind): Any? = when (kind) {
    ReportKind.string -> when (value) {
        null, is Map<*, *>, is List<*> -> null
        // Trimmed, as every other kind is read: `Smith` and `Smith ` are one auditor, and must be one group.
        is String -> value.trim()
        else -> value.fmt()
    }
    ReportKind.number -> when (value) {
        is Long -> value
        is Number -> reportNumber(value.toDouble())
        is String -> value.trim().let { it.toLongOrNull() ?: plainNumberOrNull(it)?.let(::reportNumber) }
        else -> null
    }
    ReportKind.date -> when (value) {
        is Instant, is LocalDate -> value
        is String -> value.trim().let { it.parseDayOrNull() ?: it.parseDateOrNull() }
        else -> null
    }
    ReportKind.boolean -> when (value) {
        is Boolean -> value
        // The closed set of spellings the schema validator reads a boolean from, so the two cannot disagree.
        is String -> parseExactBool(value)
        else -> null
    }
}

/**
 * A number as a report holds it: a `Long` when it is whole, a `Double` when it has a fraction, and null when it is
 * not a number at all (NaN, an infinity).
 *
 * Decided by **value**, never by the runtime type, because the type does not survive the platforms: in JS every
 * `Int`, `Short` and `Double` is one kind of number, so "is it an `Int`?" answers differently there than on the JVM,
 * and a rule built on it gives a report two answers. Whole-or-not is the same question everywhere. A whole value
 * too large for a `Double` to hold exactly stays a `Double` -- turning it into a `Long` would invent digits.
 */
fun reportNumber(value: Double): Number? = when {
    value.isNaN() || value.isInfinite() -> null
    value == floor(value) && abs(value) <= maxExactWhole -> value.toLong()
    else -> value
}

/** The largest whole number a `Double` holds exactly: 2^53. */
private const val maxExactWhole = 9007199254740992.0

/**
 * The number [text] spells, or null when it spells none: an optional sign, digits, an optional fraction, an optional
 * exponent, and nothing else. Narrower than `String.toDoubleOrNull` on purpose, because that function is not one
 * function: on the JVM it also takes Java's spellings (`7d`, `7f`, a hex float) and in JS it does not, so a report
 * built on it would read a cell as 7 on the backend and as blank in the frontend.
 */
fun plainNumberOrNull(text: String): Double? {
    var i = 0
    fun digits(): Boolean {
        val start = i
        while (i < text.length && text[i] in '0'..'9') i++
        return i > start
    }
    if (i < text.length && (text[i] == '-' || text[i] == '+')) i++
    if (!digits()) return null
    if (i < text.length && text[i] == '.') {
        i++
        if (!digits()) return null
    }
    if (i < text.length && (text[i] == 'e' || text[i] == 'E')) {
        i++
        if (i < text.length && (text[i] == '-' || text[i] == '+')) i++
        if (!digits()) return null
    }
    return if (i == text.length) text.toDoubleOrNull() else null
}

/**
 * Whether [value] is **empty** for a report (issue #978): null, a blank string, an empty list, or a list of nothing
 * but empty values. `0` and `false` are values -- a count of none and a "no" are things a report says.
 */
fun isEmptyReportValue(value: Any?): Boolean = when (value) {
    null -> true
    is String -> value.isBlank()
    is List<*> -> value.all { isEmptyReportValue(it) }
    else -> false
}

/**
 * [values] -- already of [kind], with no empties -- made one by [combine]. Null when there is nothing to combine,
 * except for a count, which is then zero. A combine [ReportCombine.allows] does not permit for the kind yields null:
 * the registry refuses such a column when a configuration loads, so reaching here with one is not a caller's error
 * to report per row.
 */
fun combineReportValues(values: List<Any>, kind: ReportKind, combine: ReportCombine): Any? {
    if (combine == ReportCombine.count) return values.size.toLong()
    if (values.isEmpty() || !combine.allows(kind)) return null
    return when (combine) {
        ReportCombine.first -> values.first()
        ReportCombine.list -> values
        ReportCombine.distinct -> values.distinctBy { reportValueKey(it) }.sortedWith(::compareReportValues)
        ReportCombine.min -> values.minWithOrNull(::compareReportValues)
        ReportCombine.max -> values.maxWithOrNull(::compareReportValues)
        ReportCombine.sum -> sumOf(values)
        ReportCombine.avg -> reportNumber(values.sumOf { (it as Number).toDouble() } / values.size)
        ReportCombine.count -> values.size.toLong()
    }
}

/**
 * The sum of numbers: a `Long` while every one is whole, so a sum of counts is not shown with a fraction. A whole sum
 * past what a `Long` holds is given as a `Double` -- large and inexact, which is what it is -- rather than wrapped
 * round to a wrong and possibly negative total.
 */
private fun sumOf(values: List<Any>): Number? {
    if (values.all { it is Long }) {
        var total = 0L
        var overflowed = false
        for (v in values) {
            val next = total + (v as Long)
            // Two's complement: the sum overflowed exactly when both operands differ in sign from the result.
            if (((total xor next) and (v xor next)) < 0) {
                overflowed = true
                break
            }
            total = next
        }
        if (!overflowed) return total
    }
    return reportNumber(values.sumOf { (it as Number).toDouble() })
}

/**
 * What makes two report values the same value -- for `distinct`, and for an aggregate run's groups (#981): a number
 * by its canonical key, so `2` and `2.0` are one, and null as itself.
 */
fun reportValueKey(value: Any?): String = when (value) {
    null -> "-"
    is Number -> "n:" + canonicalKey(value)
    is LocalDate -> "d:" + value.formatDay()
    else -> value::class.simpleName + ":" + value.toString()
}

/**
 * Orders two report values (issue #978), **totally** -- which a cursor over grouped rows depends on. Nulls last.
 * Numbers by value, whichever of `Long` and `Double` each is; dates in time, a day standing for its first instant;
 * `false` before `true`; text without regard to case, then by case, so that two strings differing only in case
 * still have an order; a list element by element, the shorter first. Values of different sorts -- which one column
 * never mixes -- fall back to the order of their text.
 */
fun compareReportValues(a: Any?, b: Any?): Int = when {
    a == null && b == null -> 0
    a == null -> 1
    b == null -> -1
    a is Long && b is Long -> a.compareTo(b)
    a is Number && b is Number -> a.toDouble().compareTo(b.toDouble())
    a is Boolean && b is Boolean -> a.compareTo(b)
    isDateValue(a) && isDateValue(b) -> compareDates(a, b)
    a is String && b is String -> a.compareTo(b, ignoreCase = true).let { if (it != 0) it else a.compareTo(b) }
    a is List<*> && b is List<*> -> compareReportKeys(a, b)
    else -> a.fmt().compareTo(b.fmt())
}

/** Orders two key tuples -- a keyed entry's key values, or a group's column values -- part by part, the shorter first. */
fun compareReportKeys(a: List<Any?>, b: List<Any?>): Int {
    for (i in 0 until minOf(a.size, b.size)) {
        val c = compareReportValues(a[i], b[i])
        if (c != 0) return c
    }
    return a.size.compareTo(b.size)
}

private fun isDateValue(value: Any): Boolean = value is Instant || value is LocalDate

private fun compareDates(a: Any, b: Any): Int = when {
    a is Instant && b is Instant -> a.compareTo(b)
    a is LocalDate && b is LocalDate -> a.compareTo(b)
    // A day beside an instant: the day's text is a prefix of the instant's, and ISO text orders as time does.
    else -> dateText(a).compareTo(dateText(b))
}

private fun dateText(value: Any): String = if (value is LocalDate) value.formatDay() else value.fmt()
