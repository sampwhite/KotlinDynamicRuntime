package com.dynamicruntime.common.page

import com.dynamicruntime.common.cfact.CFactParser
import com.dynamicruntime.common.schema.validate
import com.dynamicruntime.common.util.isVariableName

/**
 * What a page document's fences are judged against (issue #1792): the [catalog] of widget kinds, whether the copy is
 * [shipped] (a component's or a flavor's) or a client's own, whether a `copy` key exists in the page's own fragment
 * file ([copyKeyExists]), the cfacts a condition may name at all ([knownCfacts]), and those sent to the frontend
 * ([frontendCfacts]), which are all a condition judged in the browser can see.
 */
class PageCheckScope(
    val catalog: PageWidgetCatalog,
    val shipped: Boolean,
    val copyKeyExists: (namespace: String, key: String) -> Boolean,
    val knownCfacts: Set<String>,
    val frontendCfacts: Set<String>,
)

/**
 * Every problem of [text]'s widget fences (issue #1792), each a sentence naming the fence by its line and id, or none
 * for copy without fences. The same rule for copy a component ships, judged at boot, and a client's own, judged when it
 * is written; a renderer leaves out a fence with a problem rather than failing the page. A fence:
 *
 *  - is closed, and its JSON is an object;
 *  - has an `id` that is a variable name, unique in the document;
 *  - names a widget kind the catalog has, which this copy may place -- or, naming none, has a `copy` to show;
 *  - has parameters that fit its kind's schema, and no parameter the kind does not have;
 *  - names a `copy` key as `namespace.key`, which exists in the page's fragment file;
 *  - has a condition that parses and names only cfacts sent to the frontend: one naming another would never hold, and
 *    nothing would say why;
 *  - gives its fence keys (`id`, `widget`, `copy`, the condition, `jumpLabel`) as text.
 */
fun pageDocumentProblems(text: String, scope: PageCheckScope): List<String> {
    if (!text.contains(PGW.infoString)) return emptyList()
    val problems = mutableListOf<String>()
    val ids = mutableSetOf<String>()
    for (source in splitPageDocument(text).filterIsInstance<PageFenceSource>()) {
        val at = "The widget fence at line ${source.line}"
        if (!source.closed) {
            problems.add("$at is never closed, so it runs to the end of the copy.")
            continue
        }
        val fence = readPageFence(source).getOrElse {
            problems.add("$at cannot be read: ${it.message}.")
            continue
        }
        val named = fence.id?.let { "$at ('$it')" } ?: at
        fun problem(why: String) = problems.add("$named $why")
        when {
            fence.id == null -> problem("has no ${PGW.id}; every fence needs one, unique in its page.")
            !fence.id.isVariableName() -> problem("has the ${PGW.id} '${fence.id}', which is not a variable name.")
            !ids.add(fence.id) -> problem("has the ${PGW.id} of an earlier fence; each fence's is its own.")
        }
        fenceKindProblem(fence, scope)?.let { problem(it) }
        fence.copy?.let { key -> copyKeyProblem(key, scope)?.let { problem(it) } }
        fence.cfactExpression?.let { rule -> conditionProblem(rule, scope)?.let { problem(it) } }
        for (key in fence.notText) problem("has a $key that is not text.")
    }
    return problems
}

/** What is wrong with [fence]'s kind and parameters, or null. A fence's keys that are not text are named here too. */
private fun fenceKindProblem(fence: PageFence, scope: PageCheckScope): String? {
    val widget = fence.widget
        ?: return if (fence.copy == null) "names neither a ${PGW.widget} nor a ${PGW.copy}, so it would draw nothing." else null
    val kind = scope.catalog.kind(widget)
        ?: return "names the widget '$widget', which is not one of ${scope.catalog.kinds.joinToString(", ") { "'${it.name}'" }}."
    if (!scope.shipped && kind.placement == PageWidgetPlacement.shippedCopy) {
        return "places the widget '$widget', which only ${kind.placement.words} may place."
    }
    val type = scope.catalog.paramsType(kind)
    val unknown = fence.params.keys - type.properties.keys
    if (unknown.isNotEmpty()) {
        val has = type.properties.keys.takeIf { it.isNotEmpty() }?.joinToString(", ") { "'$it'" } ?: "none"
        return "gives the widget '$widget' ${unknown.sorted().joinToString(", ") { "'$it'" }}, which it does not take " +
            "(its parameters: $has)."
    }
    val failures = validate(type, fence.params)
    if (failures.isNotEmpty()) {
        return "gives the widget '$widget' parameters it refuses: " +
            failures.joinToString("; ") { "${it.path.ifEmpty { "(the fence)" }}: ${it.message}" }
    }
    return null
}

/** What is wrong with a fence's [key] for its `copy`, or null: a `namespace.key` the page's fragment file has. */
private fun copyKeyProblem(key: String, scope: PageCheckScope): String? {
    val namespace = key.substringBefore('.', "")
    val name = key.substringAfter('.', "")
    if (namespace.isEmpty() || name.isEmpty() || '.' in name) {
        return "shows the ${PGW.copy} '$key', which is not a 'namespace.key' of the page's fragment file."
    }
    return if (scope.copyKeyExists(namespace, name)) null else "shows the ${PGW.copy} '$key', which the page's fragment file does not have."
}

/** What is wrong with a fence's condition [rule], or null: it parses, and names only cfacts sent to the frontend. */
private fun conditionProblem(rule: String, scope: PageCheckScope): String? {
    val analysis = CFactParser.analyze(rule, scope.knownCfacts)
    analysis.problems.firstOrNull()?.let { return "has a ${PGW.cfactExpression} that does not parse: ${it.message}" }
    val hidden = analysis.names - scope.frontendCfacts
    if (hidden.isEmpty()) return null
    return "has a ${PGW.cfactExpression} naming ${hidden.sorted().joinToString(", ") { "'$it'" }}, which the frontend is " +
        "never sent, so it would never hold. A page's conditions are judged in the browser."
}
