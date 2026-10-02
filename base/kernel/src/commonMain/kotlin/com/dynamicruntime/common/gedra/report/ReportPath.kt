package com.dynamicruntime.common.gedra.report

import com.dynamicruntime.common.gedra.bareKeyNumber
import com.dynamicruntime.common.gedra.entryAddress
import com.dynamicruntime.common.gedra.isBareKeyChar
import com.dynamicruntime.common.gedra.keyValueText
import com.dynamicruntime.common.naming.OwnedNameKind
import com.dynamicruntime.common.naming.isOwnedName
import com.dynamicruntime.common.util.Parsed
import com.dynamicruntime.common.util.Problem
import com.dynamicruntime.common.util.ProblemCode
import com.dynamicruntime.common.util.ProblemLocation
import com.dynamicruntime.common.util.isVariableName
import com.dynamicruntime.common.util.jsonMapResult

/**
 * Which entries of a **keyed** trait a form path reads (issue #977). An unkeyed trait has one entry and takes no
 * selector; whether a trait is keyed is a fact about a client's configuration, so it is checked when a path is bound
 * to one, not here.
 */
sealed class KeySelector {
    /** Every entry: `yearly[*]`. The path then has several values, to be combined or listed. */
    object All : KeySelector()

    /** One value per key field, in the key's declared order: `yearly[2024]`, `quarterly[2024,Q1]`. */
    class Positional(val values: List<Any>) : KeySelector()

    /** Key fields by name, which may be only some of them: `quarterly[year=2024]` reads every quarter of 2024. */
    class Named(val values: Map<String, Any>) : KeySelector()
}

/**
 * A parsed **report path** (issue #977): the address of one value -- or, for a keyed trait read whole, several --
 * that a report promotes to a column.
 *
 * ```
 * form.<traitId>[selector]?.<field>(.<field>)*      form.<traitId>[selector]?.@<envelopeField>
 * workflow.<workflowId>.<attr>                      workflow.<workflowId>.approval[<taskId>].<attr>
 * user.<attr>                                       meta.<attr>
 * selector := [*] | [v1,v2,...] | [name=v,...]
 * ```
 *
 * The first segment is the [source]. A trait, workflow or task id is an owned name, bare for a client's own and
 * `root:local` for a global one (`kdr:name`), which is why a path can hold a colon and why the separator between a
 * trait and its key is a bracket. After the trait, `data.` is implied.
 *
 * Only a trait's own data is open-ended. Everything else ends in a name from a fixed vocabulary (`RWF`, `RUSR`,
 * `RMETA`, `RENV`), each with a fixed kind: workflow values in particular are the computed facts a workflow page
 * shows, never raw state, whose shape is the engine's to change.
 *
 * Two paths are equal when their [canonicalText] is.
 */
sealed class ReportPath {
    abstract val source: ReportSource

    /** The path as written back out, the same for every spelling that parses to it. */
    abstract val canonicalText: String

    /**
     * The vocabulary entry the path ends in, with its kind -- or null for a path into a trait's data, whose kind is
     * the trait schema's to say.
     */
    abstract val attr: ReportAttr?

    override fun toString(): String = canonicalText
    override fun equals(other: Any?): Boolean = other is ReportPath && other.canonicalText == canonicalText
    override fun hashCode(): Int = canonicalText.hashCode()
}

/**
 * A value in a form's trait entries: the entry or entries [selector] picks of [traitId], then either the [fields]
 * path into its data or, with [envField], a field of the entry's envelope.
 */
class FormPath(
    val traitId: String,
    val selector: KeySelector?,
    val fields: List<String>,
    val envField: String? = null,
) : ReportPath() {
    override val source: ReportSource get() = ReportSource.form
    override val attr: ReportAttr? get() = envField?.let { RENV.attrs[it] }

    /** The dotted path into the entry's data, as `pluckDataPath` takes it; empty for an envelope field. */
    val dataPath: String get() = fields.joinToString(RPT.sep.toString())

    override val canonicalText: String = buildString {
        append(ReportSource.form.name).append(RPT.sep).append(traitId)
        when (selector) {
            null -> {}
            is KeySelector.All -> append(RPT.selectorOpen).append(RPT.all).append(RPT.selectorClose)
            // The entry's own address: a fully keyed path names exactly the entry `entryAddress` would.
            is KeySelector.Positional -> append(entryAddress("", selector.values))
            is KeySelector.Named -> selector.values.entries.joinTo(
                this, RPT.valueSep.toString(), RPT.selectorOpen.toString(), RPT.selectorClose.toString(),
            ) { (name, value) -> "$name${RPT.nameSep}${keyValueText(value)}" }
        }
        append(RPT.sep)
        if (envField != null) append(RPT.envMark).append(envField) else append(dataPath)
    }
}

/** A fact about [workflowId] on the form: [attrName], or with [taskId] that task's approval. */
class WorkflowPath(val workflowId: String, val attrName: String, val taskId: String? = null) : ReportPath() {
    override val source: ReportSource get() = ReportSource.workflow
    override val attr: ReportAttr? get() = if (taskId == null) RWF.attrs[attrName] else RWF.approvalAttrs[attrName]

    override val canonicalText: String = buildString {
        append(ReportSource.workflow.name).append(RPT.sep).append(workflowId).append(RPT.sep)
        if (taskId != null) {
            append(RWF.approval).append(RPT.selectorOpen).append(taskId).append(RPT.selectorClose).append(RPT.sep)
        }
        append(attrName)
    }
}

/** An attribute of the form owner's account. */
class UserPath(val attrName: String) : ReportPath() {
    override val source: ReportSource get() = ReportSource.user
    override val attr: ReportAttr? get() = RUSR.attrs[attrName]
    override val canonicalText: String = "${ReportSource.user.name}${RPT.sep}$attrName"
}

/** A fact about the form itself. */
class MetaPath(val attrName: String) : ReportPath() {
    override val source: ReportSource get() = ReportSource.meta
    override val attr: ReportAttr? get() = RMETA.attrs[attrName]
    override val canonicalText: String = "${ReportSource.meta.name}${RPT.sep}$attrName"
}

/** What can be wrong with the text of a report path (issue #977). */
@Suppress("EnumEntryName")
enum class ReportPathProblem : ProblemCode {
    /** No text. */
    blank,

    /** The first segment is not `form`, `workflow`, `user` or `meta`. */
    unknownSource,

    /** A trait, workflow or task id, or a field name, that cannot be one. */
    badIdentifier,

    /** The path stops before it names a value: a source alone, a trait with no field. */
    missingField,

    /** A `[` with no `]`, or a quoted key value with no closing quote. */
    unclosedSelector,

    /** A key value that is missing, repeated, or written with characters a bare value cannot hold. */
    badKeyValue,

    /** A selector mixing positional and named key values. */
    mixedSelectorForms,

    /** A selector where the path takes none: on a field, or on a workflow. */
    selectorNotAllowed,

    /** A name outside the fixed vocabulary of its source. */
    unknownAttribute,

    /** More field segments than a path may hold. */
    tooManyFields,

    /** Text left over after a complete path. */
    trailingText,
}

/**
 * [text] as a [ReportPath], or what is wrong with it and where. Never throws: a path is written by a person into a
 * client's configuration, so a bad one is an ordinary outcome, reported with its offset.
 *
 * What this cannot say is whether the path means anything for a client -- that the trait exists, that it is keyed,
 * that the field is in its schema. That is the registry's question, asked when the configuration loads.
 */
fun parseReportPath(text: String): Parsed<ReportPath> {
    val scanner = ReportPathScanner(text)
    val path = scanner.path()
    return if (path != null) Parsed.Ok(path) else Parsed.Failed(scanner.problem ?: Problem(ReportPathProblem.blank, "No path."))
}

/** [parseReportPath] for a caller that has no use for a bad path; throws the first problem as a conversion error. */
fun parseReportPathOrThrow(text: String): ReportPath = parseReportPath(text).orThrow()

/**
 * The scan behind [parseReportPath]: one pass, left to right, with no recursion -- a path has no nesting to recurse
 * into. A step that fails records the first [problem] and answers null, and every caller passes the null up.
 */
private class ReportPathScanner(private val text: String) {
    private var pos = 0
    var problem: Problem? = null
        private set

    private fun <T> fail(code: ReportPathProblem, message: String, at: Int = pos): T? {
        if (problem == null) problem = Problem(code, message, ProblemLocation(offset = at))
        return null
    }

    private fun peek(): Char? = text.getOrNull(pos)

    private fun eat(c: Char): Boolean = if (peek() == c) { pos++; true } else false

    /** The text up to the next segment boundary or selector, consumed. */
    private fun word(): String {
        val start = pos
        while (pos < text.length && text[pos] != RPT.sep && text[pos] != RPT.selectorOpen) pos++
        return text.substring(start, pos)
    }

    /** The path, when the whole text is one. */
    fun path(): ReportPath? {
        if (text.isBlank()) return fail(ReportPathProblem.blank, "A report path is empty.")
        val sourceName = word()
        val source = ReportSource.entries.firstOrNull { it.name == sourceName }
            ?: return fail(
                ReportPathProblem.unknownSource,
                "'$sourceName' is not a report source; a path starts with one of " +
                    "${ReportSource.entries.joinToString(", ") { it.name }}.",
                at = 0,
            )
        if (!eat(RPT.sep)) {
            return fail(ReportPathProblem.missingField, "The path names the source '$sourceName' and nothing in it.")
        }
        val path = when (source) {
            ReportSource.form -> formPath()
            ReportSource.workflow -> workflowPath()
            ReportSource.user -> vocabularyPath(source, RUSR.attrs)?.let { UserPath(it) }
            ReportSource.meta -> vocabularyPath(source, RMETA.attrs)?.let { MetaPath(it) }
        } ?: return null
        if (pos < text.length) {
            return fail(ReportPathProblem.trailingText, "The path is complete at '${text.substring(0, pos)}'; '${text.substring(pos)}' follows it.")
        }
        return path
    }

    private fun formPath(): ReportPath? {
        val traitAt = pos
        val traitId = word()
        if (!isOwnedName(OwnedNameKind.trait, traitId)) {
            return fail(ReportPathProblem.badIdentifier, "'$traitId' cannot be a trait id.", traitAt)
        }
        val selector = if (peek() == RPT.selectorOpen) selector() ?: return null else null
        if (!eat(RPT.sep)) {
            return fail(ReportPathProblem.missingField, "The path names the trait '$traitId' and no field of it.")
        }
        if (eat(RPT.envMark)) {
            val envAt = pos
            val envField = word()
            if (envField !in RENV.attrs) {
                return fail(
                    ReportPathProblem.unknownAttribute,
                    "'$envField' is not a field of an entry's envelope; those are ${RENV.attrs.keys.joinToString(", ")}.",
                    envAt,
                )
            }
            return FormPath(traitId, selector, emptyList(), envField)
        }
        val fields = mutableListOf<String>()
        while (true) {
            val fieldAt = pos
            val field = word()
            if (!field.isVariableName()) {
                return fail(ReportPathProblem.badIdentifier, "'$field' cannot be a field name.", fieldAt)
            }
            fields.add(field)
            if (peek() == RPT.selectorOpen) {
                return fail(
                    ReportPathProblem.selectorNotAllowed,
                    "The field '$field' takes no selector: a list in the data is read whole, and an item of one has " +
                        "no position a path could name that an insert would not move.",
                )
            }
            if (!eat(RPT.sep)) break
        }
        if (fields.size > RPT.maxFields) {
            return fail(ReportPathProblem.tooManyFields, "A path holds at most ${RPT.maxFields} fields; this one has ${fields.size}.", at = 0)
        }
        return FormPath(traitId, selector, fields)
    }

    private fun workflowPath(): ReportPath? {
        val workflowAt = pos
        val workflowId = word()
        if (!isOwnedName(OwnedNameKind.workflow, workflowId)) {
            return fail(ReportPathProblem.badIdentifier, "'$workflowId' cannot be a workflow id.", workflowAt)
        }
        if (peek() == RPT.selectorOpen) {
            return fail(ReportPathProblem.selectorNotAllowed, "The workflow '$workflowId' takes no selector.")
        }
        if (!eat(RPT.sep)) {
            return fail(ReportPathProblem.missingField, "The path names the workflow '$workflowId' and nothing about it.")
        }
        val attrAt = pos
        val attr = word()
        if (attr != RWF.approval) {
            if (attr !in RWF.attrs) return unknownAttr(attr, "the workflow source", RWF.attrs.keys + RWF.approval, attrAt)
            return WorkflowPath(workflowId, attr)
        }
        if (!eat(RPT.selectorOpen)) {
            return fail(ReportPathProblem.missingField, "An approval is a task's: write '${RWF.approval}[<taskId>]'.")
        }
        val taskAt = pos
        val close = text.indexOf(RPT.selectorClose, pos)
        if (close < 0) return fail(ReportPathProblem.unclosedSelector, "The '[' has no ']'.", taskAt - 1)
        val taskId = text.substring(pos, close)
        if (!isOwnedName(OwnedNameKind.task, taskId)) {
            return fail(ReportPathProblem.badIdentifier, "'$taskId' cannot be a task id.", taskAt)
        }
        pos = close + 1
        if (!eat(RPT.sep)) {
            return fail(ReportPathProblem.missingField, "The path names the approval of '$taskId' and nothing about it.")
        }
        val approvalAt = pos
        val approvalAttr = word()
        if (approvalAttr !in RWF.approvalAttrs) {
            return unknownAttr(approvalAttr, "an approval", RWF.approvalAttrs.keys, approvalAt)
        }
        return WorkflowPath(workflowId, approvalAttr, taskId)
    }

    /** The one attribute a `user` or `meta` path names. */
    private fun vocabularyPath(source: ReportSource, attrs: Map<String, ReportAttr>): String? {
        val attrAt = pos
        val attr = word()
        if (attr !in attrs) return unknownAttr(attr, "the ${source.name} source", attrs.keys, attrAt)
        return attr
    }

    private fun <T> unknownAttr(attr: String, of: String, known: Collection<String>, at: Int): T? =
        fail(ReportPathProblem.unknownAttribute, "'$attr' is not an attribute of $of; those are ${known.joinToString(", ")}.", at)

    /** A selector, from its `[` through its `]`. */
    private fun selector(): KeySelector? {
        val openAt = pos
        pos++
        if (eat(RPT.all)) {
            if (eat(RPT.selectorClose)) return KeySelector.All
            return if (peek() == null) fail(ReportPathProblem.unclosedSelector, "The '[' has no ']'.", openAt)
            else fail(ReportPathProblem.badKeyValue, "'${RPT.all}' selects every entry and stands alone: '[${RPT.all}]'.", openAt)
        }
        val positional = mutableListOf<Any>()
        val named = LinkedHashMap<String, Any>()
        while (true) {
            val itemAt = pos
            val name = keyName()
            val value = keyValue() ?: return null
            if (name == null) positional.add(value) else {
                if (name in named) return fail(ReportPathProblem.badKeyValue, "The key field '$name' is given twice.", itemAt)
                named[name] = value
            }
            if (positional.isNotEmpty() && named.isNotEmpty()) {
                return fail(
                    ReportPathProblem.mixedSelectorForms,
                    "A selector gives its key values in order or by name, not both.",
                    itemAt,
                )
            }
            when {
                eat(RPT.valueSep) -> {}
                eat(RPT.selectorClose) -> break
                peek() == null -> return fail(ReportPathProblem.unclosedSelector, "The '[' has no ']'.", openAt)
                else -> return fail(
                    ReportPathProblem.badKeyValue,
                    "'${peek()}' cannot be part of a bare key value; write the value in double quotes.",
                )
            }
        }
        return if (named.isEmpty()) KeySelector.Positional(positional) else KeySelector.Named(named)
    }

    /** The `name` of a `name=value` item, consumed with its `=`; null, and nothing consumed, for a positional value. */
    private fun keyName(): String? {
        var end = pos
        while (end < text.length && (text[end].isLetterOrDigit() || text[end] == '_')) end++
        if (end == pos || text.getOrNull(end) != RPT.nameSep) return null
        val name = text.substring(pos, end)
        pos = end + 1
        return name
    }

    /** One key value: quoted with JSON's escapes, or bare -- and a bare one that spells a number is that number. */
    private fun keyValue(): Any? {
        val valueAt = pos
        if (peek() == RPT.quote) {
            var end = pos + 1
            while (end < text.length && text[end] != RPT.quote) end += if (text[end] == '\\') 2 else 1
            if (end >= text.length) {
                return fail(ReportPathProblem.unclosedSelector, "The quoted key value has no closing quote.", valueAt)
            }
            val literal = text.substring(pos, end + 1)
            pos = end + 1
            // Read as the one value of a JSON object, so the escapes are exactly JSON's and none are re-implemented.
            return "{\"v\":$literal}".jsonMapResult().valueOrNull()?.get("v") as? String
                ?: fail(ReportPathProblem.badKeyValue, "$literal is not a quoted value: its escapes are JSON's.", valueAt)
        }
        while (pos < text.length && isBareKeyChar(text[pos])) pos++
        val bare = text.substring(valueAt, pos)
        if (bare.isEmpty()) return fail(ReportPathProblem.badKeyValue, "A key value is missing.", valueAt)
        return bareKeyNumber(bare) ?: bare
    }
}
