package com.dynamicruntime.common.gedra.report

import com.dynamicruntime.common.gedra.GE
import com.dynamicruntime.common.gedra.GedraTrait
import com.dynamicruntime.common.gedra.workflow.WfDef
import com.dynamicruntime.common.gedra.workflow.WfEntry
import com.dynamicruntime.common.schema.SCT
import com.dynamicruntime.common.schema.SchProperty
import com.dynamicruntime.common.schema.SchType
import com.dynamicruntime.common.schema.isDateFormat
import com.dynamicruntime.common.util.Parsed
import com.dynamicruntime.common.util.ProblemCode

/**
 * What a report is bound against (issue #980): one scope's view of its configuration -- the global scope's, or one
 * client's. Plain values, so the binding is the same computation at boot, on a reload and in a trial, and a test
 * builds one by hand.
 */
class ReportScope(
    /** The traits a form of this scope may carry, by id: the ones the scope supports that apply to forms. */
    val formTraits: Map<String, GedraTrait>,
    /** The scope's compiled types, by qualified name: where a trait's entry type is found. */
    val types: Map<String, SchType>,
    /** The workflows the scope sees, by id. */
    val workflows: Map<String, WfDef>,
)

/**
 * One column of a bound report (issue #980): the [column] as declared, its [path] bound, and the two readings the
 * declaration may leave to their defaults made explicit -- the [combine] that makes the path's values one, and the
 * [kind] of what that yields. What a run reads; it never re-derives either.
 */
class BoundColumn(val column: ReportColumn, val path: BoundPath, val combine: ReportCombine) {
    val columnId: String get() = column.columnId

    /** The kind of the column's value: the combine's result over the path's kind. */
    val kind: ReportKind get() = combine.resultKind(path.kind)

    /** Whether the column's value is a list -- one that cannot be grouped by. */
    val yieldsList: Boolean get() = combine.yieldsList
}

/** A report whose every column is bound (issue #980): what a scope's registry holds, and what a run executes. */
class BoundReport(val report: ClientReport, val columns: List<BoundColumn>) {
    val reportId: String get() = report.reportId

    /** The column named, or null. */
    fun column(columnId: String): BoundColumn? = columns.firstOrNull { it.columnId == columnId }

    override fun toString(): String = report.reportId
}

/** What can be wrong with a report against a scope (issue #980). The path's own text problems are [ReportPathProblem]s. */
@Suppress("EnumEntryName")
enum class ReportBindProblem : ProblemCode {
    /** The report's own shape: no columns, a column id repeated or blank, a blank label, a default naming no column. */
    shape,

    /** A form path naming a trait the scope's forms cannot carry. */
    unknownTrait,

    /** A selector that does not fit the trait's key: missing, unwanted, the wrong arity, a field not in the key. */
    selector,

    /** A field the trait's data schema does not declare. */
    unknownField,

    /** A path ending at something other than a value: an object, an array of objects. */
    notScalar,

    /** A workflow path naming a workflow the scope does not have, or a task that is not one of its approval tasks. */
    unknownWorkflow,

    /** A declared kind the path's value cannot be read as. */
    kindMismatch,

    /** A combine or rollup the column's kind does not allow. */
    badCombine,

    /** A grouped-by column whose value is a list. */
    groupByList,
}

/**
 * [report] bound against [scope], or the first thing wrong with it (issue #980). The first rather than all, as a
 * config check reports: the report is kept or dropped whole, and the first problem is the one to fix.
 *
 * Checked, in order: the report's shape; each column's path -- that it parses, and that what it names exists in the
 * scope ([bindReportPath]); the column's declared kind against the one its path reads; its combine and rollup against
 * the kind; and that a grouped-by column has one value.
 */
fun bindReport(report: ClientReport, scope: ReportScope): Parsed<BoundReport> {
    shapeProblem(report)?.let { return Parsed.failed(ReportBindProblem.shape, it) }
    val bound = mutableListOf<BoundColumn>()
    for (column in report.columns) {
        val where = "Column '${column.columnId}'"
        val path = when (val p = bindReportPath(column.path, scope)) {
            is Parsed.Ok -> p.value
            is Parsed.Failed -> {
                val first = p.problems.first()
                return Parsed.failed(first.code, "$where reads '${column.path}': ${first.message}", first.location)
            }
        }
        val declared = column.kind
        if (declared != null && !kindReads(declared, path.kind)) {
            return Parsed.failed(
                ReportBindProblem.kindMismatch,
                "$where is declared ${declared.name}, but '${column.path}' holds a ${path.kind.name}, which cannot be read as one.",
            )
        }
        val read = if (declared == null) path else BoundPath(path.path, path.pkFields, declared, path.multiValued)
        val combine = column.combine ?: ReportCombine.defaultFor(read.multiValued)
        if (!combine.allows(read.kind)) {
            return Parsed.failed(
                ReportBindProblem.badCombine,
                "$where combines by ${combine.name}, which a ${read.kind.name} value does not allow.",
            )
        }
        val boundColumn = BoundColumn(column, read, combine)
        column.rollup?.let { rollup ->
            rollupProblem(rollup, boundColumn)?.let { return Parsed.failed(ReportBindProblem.badCombine, "$where $it") }
        }
        bound.add(boundColumn)
    }
    for (id in report.groupBy) {
        val column = bound.first { it.columnId == id }
        if (column.yieldsList) {
            return Parsed.failed(
                ReportBindProblem.groupByList,
                "Column '$id' is grouped by, but its value is a list (combine ${column.combine.name}); a group is " +
                    "one value per column. Give it a combine that yields one -- first, count, min, max.",
            )
        }
    }
    return Parsed.Ok(BoundReport(report, bound))
}

/**
 * What is wrong with [rollup] for [column], or null. Over a column whose value is a list, only a count is a rollup
 * there is one answer to -- a sum or a minimum of lists is not a number -- so the rest are refused rather than left
 * to give a blank per group.
 */
private fun rollupProblem(rollup: ReportCombine, column: BoundColumn): String? = when {
    column.yieldsList && rollup != ReportCombine.count ->
        "rolls up by ${rollup.name}, but its value is a list (combine ${column.combine.name}); a list rolls up only by count."
    !rollup.allows(column.kind) -> "rolls up by ${rollup.name}, which a ${column.kind.name} value does not allow."
    else -> null
}

/**
 * Whether a column declared [declared] may read a value its path says is [held]. Its own kind, always; any value as
 * text, since every one has a text form; and text as anything, since a text field may hold numbers or dates -- the
 * declaration says so, and a value that does not read is a blank cell. Between two kinds that are not text there is
 * no reading, and a declaration asking for one is a mistake.
 */
fun kindReads(declared: ReportKind, held: ReportKind): Boolean =
    declared == held || declared == ReportKind.string || held == ReportKind.string

/** The first thing wrong with [report]'s own shape, or null. */
private fun shapeProblem(report: ClientReport): String? {
    if (report.label.isBlank()) return "The report has no label."
    if (report.columns.isEmpty()) return "The report has no columns."
    val ids = HashSet<String>()
    for (column in report.columns) {
        if (column.columnId.isBlank()) return "A column has no id."
        if (!ids.add(column.columnId)) return "The column id '${column.columnId}' is used twice."
        if (column.label.isBlank()) return "Column '${column.columnId}' has no label."
    }
    for ((what, names) in listOf(RDEF.groupBy to report.groupBy, RDEF.excludeEmpty to report.excludeEmpty)) {
        names.firstOrNull { it !in ids }?.let { return "Its $what names '$it', which is not one of its columns." }
        if (names.toSet().size != names.size) return "Its $what names a column twice."
    }
    return null
}

/**
 * [text] parsed and bound against [scope] (issue #980): what it reads made concrete -- the trait's key fields, the
 * value's kind, whether it may have several -- or why it reads nothing there.
 *
 * - A **form** path's trait is one the scope's forms may carry. A keyed trait needs a selector, an unkeyed one
 *   refuses one; a positional selector gives every key field, a named one only fields of the key, and each key value
 *   must read as its field's kind. The field path is found in the trait's data schema ([walkReportFields]).
 * - A **workflow** path's workflow is a normal one the scope has, and an approval's task one of its approval tasks.
 * - A **user** or **meta** path is in its vocabulary, which the parser has already held it to.
 *
 * A path reading several entries of a keyed trait -- `[*]`, or a named selector leaving key fields open -- may
 * have several values, as does one stepping through an array.
 */
fun bindReportPath(text: String, scope: ReportScope): Parsed<BoundPath> {
    val path = when (val p = parseReportPath(text)) {
        is Parsed.Ok -> p.value
        is Parsed.Failed -> return p
    }
    return when (path) {
        is FormPath -> bindFormPath(path, scope)
        is WorkflowPath -> bindWorkflowPath(path, scope)
        is UserPath, is MetaPath -> {
            val attr = path.attr ?: return Parsed.failed(ReportPathProblem.unknownAttribute, "'$path' names no attribute.")
            Parsed.Ok(BoundPath(path, emptyList(), attr.kind, attr.multiValued))
        }
    }
}

private fun bindFormPath(path: FormPath, scope: ReportScope): Parsed<BoundPath> {
    val trait = scope.formTraits[path.traitId]
        ?: return Parsed.failed(ReportBindProblem.unknownTrait, "'${path.traitId}' is not a trait the forms here can carry.")
    val data = scope.types[trait.typeName]?.properties?.get(GE.data)?.valueType
        ?: return Parsed.failed(
            ReportBindProblem.unknownTrait,
            "The trait '${path.traitId}' has no compiled entry type '${trait.typeName}' here.",
        )
    val pk = trait.primaryKey
    val selector = path.selector
    // Which entries the path reads, and whether that can be more than one.
    val severalEntries = when {
        pk.isEmpty() && selector != null -> return Parsed.failed(
            ReportBindProblem.selector,
            "The trait '${path.traitId}' is not keyed -- a form has one entry of it -- so its path takes no selector.",
        )
        pk.isEmpty() -> false
        selector == null -> return Parsed.failed(
            ReportBindProblem.selector,
            "The trait '${path.traitId}' is keyed by ${pk.joinToString(", ")}, so its path names which entries: " +
                "[*] for all, or key values.",
        )
        selector is KeySelector.All -> true
        selector is KeySelector.Positional -> {
            if (selector.values.size != pk.size) {
                return Parsed.failed(
                    ReportBindProblem.selector,
                    "The trait '${path.traitId}' is keyed by ${pk.size} field(s) (${pk.joinToString(", ")}), and the " +
                        "selector gives ${selector.values.size} value(s).",
                )
            }
            keyValueProblem(path, pk.zip(selector.values), data)?.let { return it }
            false
        }
        selector is KeySelector.Named -> {
            selector.values.keys.firstOrNull { it !in pk }?.let {
                return Parsed.failed(
                    ReportBindProblem.selector,
                    "'$it' is not a key field of the trait '${path.traitId}', which is keyed by ${pk.joinToString(", ")}.",
                )
            }
            keyValueProblem(path, selector.values.toList(), data)?.let { return it }
            selector.values.size < pk.size
        }
        else -> false
    }
    val envField = path.envField
    if (envField != null) {
        val attr = path.attr ?: return Parsed.failed(ReportPathProblem.unknownAttribute, "'$envField' is not an envelope field.")
        return Parsed.Ok(BoundPath(path, pk, attr.kind, severalEntries || attr.multiValued))
    }
    return when (val walked = walkReportFields(data, path.fields)) {
        is Parsed.Ok -> Parsed.Ok(BoundPath(path, pk, walked.value.kind, severalEntries || walked.value.multiValued))
        is Parsed.Failed -> {
            val first = walked.problems.first()
            Parsed.failed(first.code, "In the trait '${path.traitId}': ${first.message}", first.location)
        }
    }
}

/** A failure when a selector's key value does not read as its key field's kind, else null. */
private fun keyValueProblem(path: FormPath, values: List<Pair<String, String>>, data: SchType): Parsed.Failed? {
    for ((field, text) in values) {
        val kind = walkReportFields(data, listOf(field)).valueOrNull()?.kind ?: ReportKind.string
        if (coerceReportValue(text, kind) == null) {
            return Parsed.failed(
                ReportBindProblem.selector,
                "The key value '$text' is not a ${kind.name}, which the key field '$field' of the trait " +
                    "'${path.traitId}' is.",
            )
        }
    }
    return null
}

private fun bindWorkflowPath(path: WorkflowPath, scope: ReportScope): Parsed<BoundPath> {
    val attr = path.attr ?: return Parsed.failed(ReportPathProblem.unknownAttribute, "'$path' names no attribute.")
    val wf = scope.workflows[path.workflowId]
        ?: return Parsed.failed(ReportBindProblem.unknownWorkflow, "'${path.workflowId}' is not a workflow here.")
    // A report says about a workflow what the forms listing's workflow column does, which only a normal workflow has:
    // a creation or survey workflow has no phase on a form, so every cell would be blank (issue #981 review).
    if (wf.entry != WfEntry.normal) {
        return Parsed.failed(
            ReportBindProblem.unknownWorkflow,
            "'${path.workflowId}' is a ${wf.entry.name} workflow; a report reads where a form stands in a " +
                "${WfEntry.normal.name} workflow, which is the only kind a form has a place in.",
        )
    }
    val taskId = path.taskId
    if (taskId != null) {
        val task = wf.tasks.firstOrNull { it.id == taskId }
            ?: return Parsed.failed(
                ReportBindProblem.unknownWorkflow,
                "The workflow '${path.workflowId}' has no task '$taskId'.",
            )
        if (task.approval == null) {
            return Parsed.failed(
                ReportBindProblem.unknownWorkflow,
                "The task '$taskId' of the workflow '${path.workflowId}' is not an approval task, so it has no approval to report.",
            )
        }
    }
    return Parsed.Ok(BoundPath(path, emptyList(), attr.kind, attr.multiValued))
}

/** Where a field walk ended (issue #980): the [kind] of the value there, and whether an array on the way makes it several. */
class ReportFieldTarget(val kind: ReportKind, val multiValued: Boolean)

/**
 * [fields] followed down the data schema [type] to the value they name (issue #980). A property whose value is a
 * named type (`$ref`) is followed into it -- parsing has already bound it -- an array is stepped into, its items read
 * whole, which makes the target several values; and a union's property is found in **every** branch that declares
 * it, since a form's entry may be any of them. The walk must end at a value: a string, number, date or boolean, or an
 * array of them -- in every branch it reached. Branches that disagree on the value's kind are read as text, the one
 * kind every value has, so no branch's values are coerced to blanks under another's kind.
 *
 * Bounded three ways: the path's own length ([RPT.maxFields]), [maxArrayNesting] for the arrays between two fields,
 * and [maxUnionNesting] for unions whose branches are unions -- so neither a long path nor a deep schema drives an
 * unbounded walk.
 */
fun walkReportFields(type: SchType, fields: List<String>): Parsed<ReportFieldTarget> {
    if (fields.size > RPT.maxFields) {
        return Parsed.failed(ReportPathProblem.tooManyFields, "A path holds at most ${RPT.maxFields} fields.")
    }
    // The types the walk may be at: one, until a union's branches each declare the next field.
    var at = listOf(type)
    var multi = false
    var walked = ""
    for (field in fields) {
        val next = ArrayList<SchType>()
        for (candidate in at) {
            val opened = openArrays(candidate) ?: return nestedTooDeep(walked)
            multi = multi || opened !== candidate
            for (prop in propertiesOf(opened, field, 0)) {
                if (next.none { it === prop.valueType }) next.add(prop.valueType)
            }
        }
        if (next.isEmpty()) {
            return Parsed.failed(
                ReportBindProblem.unknownField,
                if (walked.isEmpty()) "Its data has no field '$field'." else "'$walked' has no field '$field'.",
            )
        }
        walked = if (walked.isEmpty()) field else "$walked${RPT.sep}$field"
        at = next
    }
    val kinds = LinkedHashSet<ReportKind>()
    for (candidate in at) {
        val leaf = openArrays(candidate) ?: return nestedTooDeep(walked)
        multi = multi || leaf !== candidate
        kinds.add(
            scalarKind(leaf) ?: return Parsed.failed(
                ReportBindProblem.notScalar,
                "'$walked' is ${leaf.jsonType?.let { "an $it" } ?: "a value with no type"}, not a value a column can " +
                    "show; name a field inside it.",
            ),
        )
    }
    return Parsed.Ok(ReportFieldTarget(kinds.singleOrNull() ?: ReportKind.string, multi))
}

/** How many arrays may nest between two fields of a path before the walk refuses. */
const val maxArrayNesting = 8

/** How deep a union's branches may themselves be unions before the walk stops looking in them. */
const val maxUnionNesting = 8

/** [type] with any arrays around it stepped into, or null past [maxArrayNesting]. */
private fun openArrays(type: SchType): SchType? {
    var at = type
    var depth = 0
    while (at.jsonType == SCT.array) {
        if (++depth > maxArrayNesting) return null
        at = at.itemType ?: return at
    }
    return at
}

private fun nestedTooDeep(walked: String): Parsed.Failed = Parsed.failed(
    ReportBindProblem.notScalar,
    "${walked.ifEmpty { "Its data" }} nests arrays more than $maxArrayNesting deep.",
)

/**
 * The property [name] of [type]: its own when it declares one, otherwise -- for a union -- every branch's that does,
 * the default branch's last. [depth] bounds the walk down unions whose branches are unions.
 */
private fun propertiesOf(type: SchType, name: String, depth: Int): List<SchProperty> {
    type.properties[name]?.let { return listOf(it) }
    if (depth >= maxUnionNesting) return emptyList()
    val v = type.variants ?: return emptyList()
    return (v.branches + listOfNotNull(v.defaultBranch)).flatMap { propertiesOf(it, name, depth + 1) }
}

/** The report kind of a scalar [type], or null for anything else. A date is a string with a date format. */
private fun scalarKind(type: SchType): ReportKind? = when (type.jsonType) {
    SCT.string -> if (isDateFormat(type.format)) ReportKind.date else ReportKind.string
    SCT.integer, SCT.number -> ReportKind.number
    SCT.boolean -> ReportKind.boolean
    else -> null
}
