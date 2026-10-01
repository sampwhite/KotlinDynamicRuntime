package com.dynamicruntime.common.startup

import com.dynamicruntime.common.content.MarkdownFragmentService
import com.dynamicruntime.common.schema.LayoutError
import com.dynamicruntime.common.schema.SCH
import com.dynamicruntime.common.schema.SL
import com.dynamicruntime.common.schema.SchGKeywords
import com.dynamicruntime.common.schema.SchFailCode
import com.dynamicruntime.common.schema.SchLayout
import com.dynamicruntime.common.schema.SchType
import com.dynamicruntime.common.schema.errorContextNames
import com.dynamicruntime.common.schema.errorMessageTemplateProblems
import com.dynamicruntime.common.schema.isNumericType
import com.dynamicruntime.common.schema.layoutFieldProblems
import com.dynamicruntime.common.schema.layoutProblem
import com.dynamicruntime.common.schema.layoutTemplateProblems
import com.dynamicruntime.common.schema.parseTypeLayout
import com.dynamicruntime.common.schema.maxBoundKeyword
import com.dynamicruntime.common.schema.minBoundKeyword
import com.dynamicruntime.common.util.Parsed
import com.dynamicruntime.common.util.Problem
import com.dynamicruntime.common.util.analyzeTemplate
import com.dynamicruntime.common.util.toJsonMap

/**
 * One piece a repair pass removed from a client's type definition (issue #841), and why: what was wrong, and what
 * was dropped so the rest of the definition could stand.
 */
class DefRepair(val message: String, val degradedTo: String)

/**
 * What the keyword repair judges against: the registered options-provider ids, and a check for one
 * `g-visibleWhen` expression (null when sound) -- held by the caller, since only it has the cfact registry.
 */
class DefRepairContext(
    val optionsProviders: Set<String>,
    val visibleWhenProblem: (expression: String) -> String?,
)

/**
 * [body] -- one type a client authored -- with every **keyword-level** fault removed, and what was removed
 * (issue #841). The smallest drop each fault allows, instead of refusing the boot or the reload:
 *
 * - a `g-optionsSource` naming no registered provider (or not an id): the keyword goes, and the field takes free
 *   input; one declared beside `options`: the keyword goes, and the declared options stand;
 * - a `g-visibleWhen` that does not pass [DefRepairContext.visibleWhenProblem], or that gates a **required**
 *   property: the keyword goes, and the field shows for everyone -- acceptable, because the gate controls display,
 *   not access;
 * - a `g-errors` entry keyed by no failure code, or whose template could not render right: that message goes, and
 *   the failure falls back to the validator's own wording;
 * - an unknown `g-` key, or one of ours whose value has the wrong shape ([SchGKeywords], issue #822): the keyword
 *   goes.
 *
 * The checks are the boot's own -- the same messages, from the same helpers -- run on the **raw** definition,
 * where a keyword can still be removed; the boot's later passes over the compiled document then find nothing in
 * it. Returns [body] itself (by identity) when nothing was wrong, so an unrepaired alteration is untouched.
 */
fun repairTypeDef(
    where: String,
    body: Map<String, Any?>,
    context: DefRepairContext,
): Pair<Map<String, Any?>, List<DefRepair>> {
    val repairs = mutableListOf<DefRepair>()

    // Rebuilds as it walks; an unrepaired type is handed back by identity below, so the copies cost nothing
    // that matters.
    fun repairNode(at: String, node: Map<String, Any?>, requiredHere: Boolean): Map<String, Any?> {
        val out = LinkedHashMap(node)
        // An unknown `g-` key, or one of ours with a value of the wrong shape (issue #822): the keyword goes, and the
        // rest of the definition stands. First, so the checks below read only well-shaped keywords.
        for ((key, value) in node) {
            val problem = SchGKeywords.problem(at, key, value) ?: continue
            out.remove(key)
            repairs.add(DefRepair(problem.message, "Dropping '$key'."))
        }

        when (val source = out[SCH.optionsSource]) {
            null -> {}
            !is String -> {
                out.remove(SCH.optionsSource)
                repairs.add(
                    DefRepair(
                        "$at declares '${SCH.optionsSource}' as $source, which is not an id.",
                        "Dropping '${SCH.optionsSource}'; the field takes free input.",
                    ),
                )
            }
            !in context.optionsProviders -> {
                out.remove(SCH.optionsSource)
                repairs.add(
                    DefRepair(
                        "$at sources its options from '$source', which no component registered.",
                        "Dropping '${SCH.optionsSource}'; the field takes free input.",
                    ),
                )
            }
            else -> if (out[SCH.options] != null) {
                out.remove(SCH.optionsSource)
                repairs.add(
                    DefRepair(
                        "$at declares both '${SCH.options}' and '${SCH.optionsSource}'.",
                        "Dropping '${SCH.optionsSource}'; the declared options stand.",
                    ),
                )
            }
        }

        (out[SCH.visibleWhen] as? String)?.let { expression ->
            val detail = context.visibleWhenProblem(expression)
                ?: if (requiredHere) "gates a required property, which a caller it hides could never submit" else null
            if (detail != null) {
                out.remove(SCH.visibleWhen)
                repairs.add(
                    DefRepair(
                        "$at: '${SCH.visibleWhen}' expression '$expression' $detail.",
                        "Dropping '${SCH.visibleWhen}'; the field shows for every caller.",
                    ),
                )
            }
        }

        (out[SCH.errors] as? Map<*, *>)?.let { raw ->
            val jsonType = node[SCH.type] as? String
            // A number's bound may be exclusive instead (issue #823).
            val numeric = isNumericType(jsonType)
            val hasMin = node[minBoundKeyword(jsonType)] != null || numeric && node[SCH.exclusiveMinimum] != null
            val hasMax = node[maxBoundKeyword(jsonType)] != null || numeric && node[SCH.exclusiveMaximum] != null
            val kept = LinkedHashMap<String, Any?>()
            for ((k, v) in raw) {
                val codeKey = k.toString()
                val message = v as? String
                val known = codeKey == SCH.errorDefault || SchFailCode.entries.any { it.name == codeKey }
                val problems = when {
                    !known -> listOf("$at: '${SCH.errors}' names '$codeKey', which is not a failure code.")
                    message == null -> listOf("$at: the '${SCH.errors}' message for '$codeKey' is not text.")
                    else -> errorMessageTemplateProblems(
                        at, codeKey, message,
                        errorContextNames(SchFailCode.entries.firstOrNull { it.name == codeKey }, hasMin, hasMax),
                        MarkdownFragmentService.backendPassPrefix,
                    )
                }
                if (problems.isEmpty()) {
                    kept[codeKey] = v
                } else {
                    val degradedTo = "Dropping that message; the failure uses the validator's own wording."
                    problems.forEach { repairs.add(DefRepair(it, degradedTo)) }
                }
            }
            if (kept.size != raw.size) {
                if (kept.isEmpty()) out.remove(SCH.errors) else out[SCH.errors] = kept
            }
        }

        // Children. A property is judged against its parent's `required`, since a gate on a required one is refused;
        // `g-errors` and `g-layout` hold copy, not schema, and are not walked into.
        val required = (node[SCH.required] as? List<*>).orEmpty().filterIsInstance<String>().toSet()
        for ((key, value) in out.entries.toList()) {
            out[key] = when {
                key == SCH.errors || key == SCH.layout -> value
                key == SCH.properties && value is Map<*, *> ->
                    value.toJsonMap().mapValuesTo(LinkedHashMap()) { (child, childBody) ->
                        if (childBody is Map<*, *>) {
                            repairNode("$where property '$child'", childBody.toJsonMap(), child in required)
                        } else {
                            childBody
                        }
                    }
                value is Map<*, *> -> repairNode(at, value.toJsonMap(), requiredHere = false)
                value is List<*> ->
                    value.map { if (it is Map<*, *>) repairNode(at, it.toJsonMap(), requiredHere = false) else it }
                else -> value
            }
        }
        return out
    }

    val repaired = repairNode(where, body, requiredHere = false)
    return (if (repairs.isEmpty()) body else repaired) to repairs
}

/**
 * Malformed **backend** `%{...}` blocks in a layout's copy (issue #605) -- the registry-free half of the
 * fragment-pull check. A layout `label` / `description` / `hint` may carry a `%{@t("…")}` pull resolved at
 * delivery; an unterminated or empty `%{...}` block would otherwise fail per request, so it is caught here
 * at boot. Whether a well-formed pull actually *resolves* (its target file and key exist) is a cross-service
 * check that needs the fragment registry, which is not available to this startup-phase service: it runs in
 * the regular phase, in `LayoutCheckService` via `SchemaService.checkLayoutPulls` (issue #620). So a literal pull that
 * misses is now refused at boot; only a *computed* or guarded pull can miss at delivery, where it degrades
 * gracefully (`resolveDeliveredLayouts`).
 */
fun layoutBackendBlockProblems(where: String, layout: SchLayout): List<Problem> {
    val problems = mutableListOf<Problem>()
    fun checkBackendBlocks(what: String, at: String, text: String?) {
        if (text == null || MarkdownFragmentService.backendPassPrefix !in text) {
            return
        }
        for (issue in text.analyzeTemplate(MarkdownFragmentService.backendPassPrefix).issues) {
            problems.add(
                layoutProblem(
                    LayoutError.malformedTemplate,
                    "$where: the '${SCH.layout}' $what has a malformed backend block: ${issue.message}",
                    at, issue,
                ),
            )
        }
    }
    checkBackendBlocks("heading", SL.label, layout.label)
    // The form-level strings (issue #641) are delivered through the same backend pass (issue #814).
    for ((key, text) in layout.strings) {
        checkBackendBlocks("string '$key'", "${SL.strings}.$key", text)
    }
    for ((i, field) in layout.fields.withIndex()) {
        val at = "${SL.schemaFields}[$i]"
        checkBackendBlocks("${field.field}'s label", "$at.${SL.label}", field.label)
        checkBackendBlocks("${field.field}'s description", "$at.${SL.description}", field.description)
        checkBackendBlocks("${field.field}'s hint", "$at.${SL.hint}", field.hint)
        // An error override (issue #588) is frontend `${'$'}{…}`-only; delivery does not run the backend pass
        // over it, so a `%{…}` block there would ship raw. Refuse any -- not merely a malformed one --
        // rather than let it render as literal text.
        for ((codeKey, message) in field.errors) {
            if (MarkdownFragmentService.backendPassPrefix in message &&
                message.analyzeTemplate(MarkdownFragmentService.backendPassPrefix).blockCount > 0
            ) {
                problems.add(
                    layoutProblem(
                        LayoutError.backendBlock,
                        "$where: the '${SCH.layout}' error '$codeKey' for '${field.field}' uses a backend block " +
                            "('%{…}'); a layout error message supports only frontend parameter substitution (see #588).",
                        "$at.${SL.errors}.$codeKey",
                    ),
                )
            }
        }
    }
    return problems
}

/**
 * Everything wrong with type [name]'s `g-layout` in [body] (issue #909): the block's own parse problems when it does
 * not parse, else what the boot checks find against [type] -- its fields, its copy templates, and its backend
 * blocks. Empty when [body] declares no layout. [where] names the type in the checks' messages. Shared by the
 * global boot check and the client-variant build, which each drop a layout this finds fault with.
 */
fun layoutProblems(name: String, where: String, body: Map<*, *>, type: SchType?): List<Problem> =
    when (val parsed = parseTypeLayout(name, body)) {
        is Parsed.Failed -> parsed.problems
        is Parsed.Ok -> parsed.value?.let { layout ->
            layoutFieldProblems(where, layout, type) + layoutTemplateProblems(where, layout, type) +
                layoutBackendBlockProblems(where, layout)
        }.orEmpty()
    }
