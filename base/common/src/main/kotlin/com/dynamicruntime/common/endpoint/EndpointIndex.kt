package com.dynamicruntime.common.endpoint

import com.dynamicruntime.common.http.request.ContextRoot
import com.dynamicruntime.common.http.request.ROLE
import com.dynamicruntime.common.http.request.RoleLadder
import com.dynamicruntime.common.http.request.SectionRules
import com.dynamicruntime.common.http.request.sectionOf
import com.dynamicruntime.common.portal.PTL
import com.dynamicruntime.common.startup.hasClientCopy

/**
 * The **endpoint index** (issue #1608): every endpoint a node offers, one line each, grouped by section under the
 * access rule that section enforces -- the table of contents for the endpoint catalog, which carries each one's input
 * and output schema. Made from the endpoints and the section rules themselves, so the access a heading states is the
 * one the dispatcher enforces ([SectionRules.admits] reads the same fields).
 *
 * [appEndpoints] are an application node's shared endpoints; [edgeEndpoints], those an edge node adds beyond them.
 * [rules] maps each section to its rules. The committed copy, `docs/reference/endpoints.md`, is made from a test
 * boot holding every component the repository ships.
 */
fun endpointIndexMarkdown(
    appEndpoints: Collection<KdrEndpoint>,
    edgeEndpoints: Collection<KdrEndpoint>,
    rules: Map<String, SectionRules>,
): String {
    val sb = StringBuilder()
    sb.append("# Endpoints\n\n")
    sb.append(
        "Every endpoint the runtime offers, one line each, grouped by section: the first segment of its path, which " +
            "decides who may call it. A path here is under the API context root, `/${ContextRoot.kda}` unless a " +
            "deployment names another, so `GET /health` is called as `GET /${ContextRoot.kda}/health`.\n\n",
    )
    sb.append(
        "Each endpoint's input and output schema is in the endpoint catalog, `GET ${PTL.endpointsApi}` (one " +
            "endpoint by its path and method at `GET /schema/endpoint`), which lists only what the caller may call. " +
            "To ask a test node, or a session in env debug, why an endpoint is withheld, add `_debug=explainAccess`.\n\n",
    )
    sb.append("The notes on an endpoint:\n\n")
    sb.append("- **Per client**: it has a copy for each client at a path naming the client, `/gedra/<client>/...` " +
        "for `/gedra/...`. The copy answers with that client's schema and refuses another client's data.\n")
    sb.append("- **Published**: part of the published API, the endpoints documented for callers outside the team. " +
        "It decides what the catalog offers a caller without env auth, never who may call.\n")
    sb.append("- **Session only**: a signed-in session may call it, an API key may not.\n")
    sb.append("- **Test only**: present only on a test instance, and only to a caller with test access.\n\n")
    sb.append(
        "Not listed: a client's copies (named by the note above), the endpoints a node generates from a client's own " +
            "configuration (a report's typed run), and the multi-node test harness's fixtures. The sample " +
            "component's endpoints are listed; a deployment carries them only in a developer environment.\n\n",
    )
    appendSections(sb, appEndpoints, rules, "##")
    if (edgeEndpoints.isNotEmpty()) {
        sb.append("## On an edge node\n\n")
        sb.append(
            "An edge serves the application's endpoints too, and adds these, under the same section rules.\n\n",
        )
        appendSections(sb, edgeEndpoints, rules, "###")
    }
    return sb.toString()
}

/** [endpoints] by section, alphabetically, each under a [level] heading saying who may call it. */
private fun appendSections(sb: StringBuilder, endpoints: Collection<KdrEndpoint>, rules: Map<String, SectionRules>, level: String) {
    for ((section, inSection) in endpoints.groupBy { sectionOf(it.path) }.toSortedMap()) {
        sb.append(level).append(" `").append(section).append("`\n\n")
        sb.append(sectionAccessText(rules[section])).append("\n\n")
        sb.append("| Method | Path | Notes | What it does |\n|---|---|---|---|\n")
        // A path before the paths beneath it (`/bundle` before `/bundle/delete`), then by method.
        for (ep in inSection.sortedBy { it.path + "\u0000" + it.method.name }) {
            sb.append("| ").append(ep.method.name).append(" | `").append(ep.path).append("` | ")
                .append(notesOf(ep).joinToString(", ")).append(" | ").append(tableCell(ep.description)).append(" |\n")
        }
        sb.append("\n")
    }
}

/** Who may call an endpoint in a section with [rules], in a sentence or two. */
fun sectionAccessText(rules: SectionRules?): String {
    if (rules == null) {
        // The boot refuses a section an endpoint declares without rules, so this is not reached from a booted node.
        return "No access rules."
    }
    if (!rules.needsLogin && !rules.isGated) {
        return "Anyone may call these, signed in or not."
    }
    val parts = mutableListOf<String>()
    val role = rules.requiredRole
    when {
        role == null || role == ROLE.user -> {}
        RoleLadder.rankOf(role) != null -> parts.add("at the `$role` level or above")
        else -> parts.add("holding `$role`" + (RoleLadder.impliedBy[role]?.let { " (or at the `$it` level)" } ?: ""))
    }
    rules.requiredCapability?.let { parts.add("holding `$it`") }
    val who = if (parts.isEmpty()) "A signed-in caller may call these." else "A signed-in caller ${parts.joinToString(" and ")} may call these."
    return if (rules.directReads) "$who A GET that reaches the node directly, not through a proxy, needs none of this." else who
}

private fun notesOf(ep: KdrEndpoint): List<String> = listOfNotNull(
    "per client".takeIf { hasClientCopy(ep) },
    "published".takeIf { ep.publicApi },
    "session only".takeIf { ep.sessionOnly },
    "test only".takeIf { ep.forTestingOnly },
)

/** [text] on one line of a Markdown table: whitespace runs collapsed, and a `|` escaped so it ends no cell. */
private fun tableCell(text: String): String = text.trim().replace(Regex("\\s+"), " ").replace("|", "\\|")
