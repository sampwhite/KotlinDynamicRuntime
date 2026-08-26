package com.dynamicruntime.common.operator

import com.dynamicruntime.common.context.ENVGRP
import com.dynamicruntime.common.context.EVSRC
import com.dynamicruntime.common.context.EnvVarDef
import com.dynamicruntime.common.context.EnvVarRegistry
import com.dynamicruntime.common.context.KdrCxt
import com.dynamicruntime.common.context.KdrInstanceConfig

/**
 * The groups in the order they render, which is [ENVGRP]'s own declaration order rather than whatever order
 * class-loading happened to register the variables in. A group the registry carries that is not named here
 * (there is none today) falls to the end, alphabetically, so an unforeseen group is still shown rather than
 * dropped.
 */
private val groupOrder = listOf(
    ENVGRP.application, ENVGRP.database, ENVGRP.logging, ENVGRP.node,
    ENVGRP.caching, ENVGRP.content, ENVGRP.gedra, ENVGRP.edge,
)

/**
 * Assembles the operator environment-variable reference as one Markdown document (issue #371) -- the whole
 * document, so the frontend renders text it did not compose, the way it renders the README.
 *
 * This is the half of #371 that a file could never be: for each variable it shows **how it resolved on this
 * node** -- the value and where the value came from -- not merely a documented default. `"true, from
 * KDR_ENV=local"` is a fact about the node being asked; `"on for local/dev"` is a claim that can rot.
 *
 * It lists the variables this node has **declared**. Because a variable is read only through its declaration
 * ([KdrInstanceConfig.getEnvVar] takes an [EnvVarDef]), that is exactly the set this node can read; a variable
 * absent from the list is one no code path on this node has touched -- which, for "why is *this* node behaving
 * like that", is the honest scope. Deliberately not completed by a classpath scan or a maintained warm-list:
 * either would reintroduce the drift this issue exists to delete.
 */
fun renderEnvVarReference(cxt: KdrCxt): String {
    val config = cxt.instanceConfig
    val byGroup = EnvVarRegistry.all().groupBy { it.group }
    val orderedGroups = groupOrder.filter { it in byGroup.keys } +
        byGroup.keys.filter { it !in groupOrder }.sorted()

    val sb = StringBuilder()
    sb.append("# Environment variables\n\n")
    sb.append(
        "Every environment variable this node has declared, and the value each resolved to **here** -- a fact " +
            "about this node, not a documented default. **The list grows as the node runs**: a variable no code " +
            "path has read yet is not listed, so an absent name means \"not touched yet on this node\", not " +
            "\"not a variable\".\n\n",
    )
    for (group in orderedGroups) {
        sb.append("## ").append(group).append("\n\n")
        for (def in byGroup.getValue(group).sortedBy { it.name }) {
            appendVar(sb, config, def)
        }
    }
    return sb.toString()
}

/** One variable's block: its name, how it resolved here, its documented default, and the description prose. */
private fun appendVar(sb: StringBuilder, config: KdrInstanceConfig, def: EnvVarDef) {
    val resolution = config.resolveEnvVar(def)
    sb.append("### `").append(def.name).append("`\n\n")

    sb.append("**On this node:** ")
    val value = resolution.value
    val from = inlineCode(resolution.matchedName ?: def.name) + " (" + sourceText(resolution.source) + ")"
    when {
        value == null -> sb.append("_unset_ -- the default below applies.\n\n")
        // Set, but to the empty string -- which most read sites (`AdminRules`, `NodeUtil`, `GoogleIdToken`)
        // normalize to unset, so a bare empty value would otherwise read as "configured" when the behavior is
        // off. Said outright rather than shown as an (invalid) empty code span, which is the wrong conclusion
        // the view exists to prevent.
        value.isEmpty() -> sb.append("set to an **empty string** -- from ").append(from)
            .append("; most variables treat an empty value as unset.\n\n")
        else -> sb.append(inlineCode(value)).append(" -- from ").append(from).append(".\n\n")
    }

    sb.append("**Default when unset:** ").append(prose(def.defaultDoc)).append("\n\n")
    sb.append(prose(def.description)).append("\n\n")
}

/** Matches a KDoc `[Symbol]` reference -- a bare `[Identifier]` (dotted members allowed), not a `[text](url)` link. */
private val kdocReference = Regex("""\[([A-Za-z][\w.]*)](?!\()""")

/**
 * A def's KDoc prose rendered for the operator document: a `[Symbol]` reference -- clickable in the source for
 * a developer, but a dangling `[Symbol]` if dropped into CommonMark verbatim -- becomes an inline-code span,
 * which reads as "a name" rather than a broken link. A real `[text](url)` link is left alone (the negative
 * lookahead); the descriptions carry none today, but the guard keeps this honest if one is ever added.
 */
private fun prose(text: String): String = kdocReference.replace(text) { "`${it.groupValues[1]}`" }

/** Plain words for a resolution source, so the document reads for an operator rather than naming a constant. */
private fun sourceText(source: String): String = when (source) {
    EVSRC.config -> "instance config"
    EVSRC.processEnv -> "the environment"
    else -> source
}

/**
 * A value as an inline-code span, made safe to drop into one: a backtick would end the span early and a
 * newline would break it, so both are neutralized. The values shown here (ports, hosts, booleans, ids) do not
 * normally carry either, but an operator reading a diagnostic should never see it rendered wrong.
 */
private fun inlineCode(value: String): String =
    "`" + value.replace("`", "'").replace("\n", " ").replace("\r", "") + "`"
