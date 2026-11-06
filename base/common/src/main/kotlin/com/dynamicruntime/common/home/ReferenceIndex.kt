@file:Suppress("MarkdownUnresolvedFileReference")

package com.dynamicruntime.common.home

import com.dynamicruntime.common.content.MarkdownDocDef
import com.dynamicruntime.common.operator.OENV

/** Where the references are kept in the repository (issues #1603, #1632). */
@Suppress("ConstPropertyName")
object REFDOC {
    /** The references' directory: a served document whose source path is under it is a reference. */
    const val dir = "docs/reference/"

    /** The index of the references, which is also the directory's index on GitHub. */
    const val indexPath = "${dir}README.md"

    /** The environment variables' reference, which no server serves: a node knows only the variables it has read. */
    const val envVarsPath = "${dir}environment-variables.md"

    /** The endpoint index, which no server serves either: a node holds only its own deployment's components. */
    const val endpointsPath = "${dir}endpoints.md"
}

/**
 * The **reference index** (issue #1632), served as "Reference" and listed on the Documents page in place of the
 * references themselves: each one's label, linked, and its one-line description, then the two kept in the repository
 * alone. The served ones are made from the document declarations ([coreDocs]), every one whose source path is under
 * [REFDOC.dir], so a new reference joins the index by being declared. Depends on nothing but code, as a generated
 * document must.
 *
 * Its links are relative to the directory, so they work on GitHub, where the committed copy is the directory's index,
 * and in the app, where a link to a served reference opens it and a link to one of the other two opens its copy in the
 * repository.
 */
fun referenceIndexMarkdown(): String {
    val served = servedReferences()
    // language=Markdown
    return $$"""# Reference

These pages list what the runtime reads and offers: every schema keyword, validation code, expression function, fact,
copy role, report path, form layout key, role, and configuration slot, and every environment variable and endpoint.
Each is generated from the code it describes, so it says what the runtime does, not what it was once meant to do. A
copy of each is committed beside the code, and the build's `check` fails while one is behind it.

Read a guide first for how the pieces fit together: [Start here](../start-here.md) says which, by what you want to do.

$${referenceTable(served.map { Triple(it.label, it.sourcePath, it.description) })}

## In the repository only

A running server does not serve these two, since it cannot list either whole: it knows only the environment variables
it has read so far, and only the endpoints of the components its deployment includes. Each links to its copy in the
repository.

$${referenceTable(committedOnly)}
"""
}

/** The served references, in the order they are declared: every core document under [REFDOC.dir] but this index. */
fun servedReferences(): List<MarkdownDocDef> =
    coreDocs().filter { it.sourcePath.startsWith(REFDOC.dir) && it.docId != HDOC.referenceIndex }

/** The two references no server serves, each as (label, source path, description). */
private val committedOnly = listOf(
    Triple(
        "Environment variables", REFDOC.envVarsPath,
        "Every environment variable the runtime reads, with its default and what it does. A running node lists the " +
            "ones it has read, with their values, at `GET ${OENV.envReferencePath}`.",
    ),
    Triple(
        "Endpoints", REFDOC.endpointsPath,
        "Every endpoint the runtime offers, one line each, grouped by the section that decides who may call it.",
    ),
)

/** A table of references, each (label, source path, description), its label linked relative to [REFDOC.dir]. */
private fun referenceTable(rows: List<Triple<String, String, String>>): String =
    (listOf("| Reference | What it covers |", "|---|---|") + rows.map { (label, path, description) ->
        "| [$label](${path.removePrefix(REFDOC.dir)}) | $description |"
    }).joinToString("\n")
