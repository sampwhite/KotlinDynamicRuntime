package com.dynamicruntime.common.operator

import com.dynamicruntime.common.util.kdocRefsAsCode
import com.dynamicruntime.common.context.ENVGRP
import com.dynamicruntime.common.context.EVSRC
import com.dynamicruntime.common.context.EnvVarDef
import com.dynamicruntime.common.context.EnvVarRegistry
import com.dynamicruntime.common.context.KdrCxt
import com.dynamicruntime.common.context.KdrInstanceConfig

/**
 * The groups in the order they render, which is [ENVGRP]'s own declaration order rather than whatever order
 * class-loading happened to register the variables in. A group the registry carries that is not named here
 * falls to the end, alphabetically, so an unforeseen group is still shown rather than dropped -- but a group
 * added to [ENVGRP] is added here, or it renders wherever its name happens to sort.
 */
private val groupOrder = listOf(
    ENVGRP.application, ENVGRP.database, ENVGRP.logging, ENVGRP.node,
    ENVGRP.caching, ENVGRP.content, ENVGRP.gedra, ENVGRP.edge, ENVGRP.jobs, ENVGRP.fileStore, ENVGRP.assistant,
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

    val sb = StringBuilder()
    sb.append("# Environment variables\n\n")
    sb.append(
        "Every environment variable this node has declared, and the value each resolved to **here** -- a fact " +
            "about this node, not a documented default. **The list grows as the node runs**: a variable no code " +
            "path has read yet is not listed, so an absent name means \"not touched yet on this node\", not " +
            "\"not a variable\".\n\n",
    )
    for (group in orderedGroups(byGroup.keys)) {
        sb.append("## ").append(group).append("\n\n")
        for (def in byGroup.getValue(group).sortedBy { it.name }) {
            appendVar(sb, config, def)
        }
    }
    return sb.toString()
}

/** [groups] in the order they render: [groupOrder]'s, then any it does not name, alphabetically. */
private fun orderedGroups(groups: Set<String>): List<String> =
    groupOrder.filter { it in groups } + groups.filter { it !in groupOrder }.sorted()

/**
 * The environment variables [defs] declares, as a reference document (issue #1607): by group, as the operator view
 * orders them, each with its documented default and its description, and whether it is read before deployment
 * flavors resolve. Unlike [renderEnvVarReference] it says nothing of any node -- no value, no source -- so it depends
 * only on the declarations it is handed; the committed copy in `docs/reference/environment-variables.md` is made from
 * every declaration in the repository.
 */
fun envVarCatalogMarkdown(defs: List<EnvVarDef>): String {
    val byGroup = defs.groupBy { it.group }
    val sb = StringBuilder()
    sb.append("# Environment variables\n\n")
    sb.append(
        "Every environment variable the runtime reads, by area, with what it does and what applies when it is unset. A " +
            "variable is read only through its declaration in the code, so this list is complete: a name not here is " +
            "not read. A deployment reads only the variables of the components it carries.\n\n",
    )
    sb.append(
        "A variable is set in the process environment, or as an entry of the instance config, which wins over it; a " +
            "workspace's `${KdrInstanceConfig.defaultEnvVarsFileName}` supplies those the environment leaves unset " +
            "(see `${KdrInstanceConfig.defaultsFileEnvVar.name}`). On a node with a boot role, a name carrying the role " +
            "wins over the plain one: `KDR_EDGE_PORT` over `KDR_PORT` on an edge. A deployment flavor may default a " +
            "variable nothing else sets. A running node's operator view, `GET /operator/env/reference`, lists the " +
            "variables it has read, with the value each resolved to and where the value came from.\n\n",
    )
    for (group in orderedGroups(byGroup.keys)) {
        sb.append("## ").append(group).append("\n\n")
        for (def in byGroup.getValue(group).sortedBy { it.name }) {
            sb.append("### `").append(def.name).append("`\n\n")
            sb.append("**Default when unset:** ").append(def.defaultDoc.kdocRefsAsCode()).append("\n\n")
            if (def.bootstrap) {
                sb.append("**Read before deployment flavors resolve**, so a flavor cannot default it.\n\n")
            }
            sb.append(def.description.kdocRefsAsCode()).append("\n\n")
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

    sb.append("**Default when unset:** ").append(def.defaultDoc.kdocRefsAsCode()).append("\n\n")
    sb.append(def.description.kdocRefsAsCode()).append("\n\n")
}

/** Plain words for a resolution source, so the document reads for an operator rather than naming a constant. */
private fun sourceText(source: String): String = when (source) {
    EVSRC.config -> "instance config"
    EVSRC.processEnv -> "the environment"
    EVSRC.flavor -> "a deployment flavor's default -- see /operator/flavors"
    else -> source
}

/**
 * A value as an inline-code span, made safe to drop into one: a backtick would end the span early and a
 * newline would break it, so both are neutralized. The values shown here (ports, hosts, booleans, ids) do not
 * normally carry either, but an operator reading a diagnostic should never see it rendered wrong.
 */
private fun inlineCode(value: String): String =
    "`" + value.replace("`", "'").replace("\n", " ").replace("\r", "") + "`"
