package com.dynamicruntime.common.content

import com.dynamicruntime.common.exception.KdrException

/**
 * One Markdown document a component serves in-app (issues #492, #1136): the resource it is read from, how it is
 * labeled, and where it lives in the source repository.
 *
 * Declared by the component that ships the file (`ComponentDefinition.docs`), as a fragment file is, because nothing
 * can enumerate the classpath: a document nobody declares is a document nothing links to. Declaring one does two
 * things. It is offered on the home page's Documents list when it is [listed], and its [sourcePath] lets the frontend
 * rewrite a link *to* it, from any document or any form copy, into an in-app link rather than a link into the source
 * repository. An unlisted document is a page of a suite reached from its entry page, linked to but not listed.
 */
class MarkdownDocDef(
    /** The document's id: the resource read is `md-docs/<docId>.md`, and the frontend addresses it by this id. */
    val docId: String,
    val label: String,
    /**
     * The document's path in the source repository, as a link in another document or in form copy names it
     * (`docs/demo-client/review.md`) -- the key a link is resolved by, so it is unique across documents.
     */
    val sourcePath: String,
    /** One line on what the document covers, shown beside its link in the Documents listing (issue #554). */
    val description: String,
    /** Whether the Documents listing offers it; a suite lists its entry page and links the rest (issue #1136). */
    val listed: Boolean = true,
    /**
     * Where the document sorts in the listing (issue #1238): documents are listed by rising [order], and within one
     * order as declared, component by component. Each listed document takes its place from [DOCORDER] (issue #1632),
     * so that it keeps it whichever components contribute documents beside the one declaring it; an unlisted one
     * leaves it at 0.
     */
    val order: Int = 0,
    /**
     * Makes the document's Markdown, for a document generated from the code rather than kept as a file (issue
     * #1347): the schema dialect reference is made from the keyword tables the parser reads, so it cannot drift from
     * them. Null for a file, read from `md-docs/<docId>.md`. The text is made once, on first use, and must depend on
     * nothing but code: its build id is a hash of the text, and a URL carrying it is cached for good.
     *
     * Its [sourcePath] still names a file in the repository, so a link to the document resolves in both places: in
     * the app it opens the generated page, and on GitHub it opens that file, which holds a copy of the page (issue
     * #1603: `refDocsDump` writes it, and `refDocsTest` fails while it is behind) and says where the page is served.
     */
    val generate: (() -> String)? = null,
) {
    /** The generated text, or null for a document kept as a file. */
    val generatedText: String? by lazy { generate?.invoke() }
}

/**
 * The [MarkdownDocDef.order] of each document the Documents list offers (issue #1632), in the order it offers them.
 * The list is kept short -- the documentation's map, the ways in, and the reference index -- since "Start here" maps
 * the rest by audience; a guide or a reference is served unlisted and reached from them. The walk-through and the
 * conference demo are declared by `kdn`, after this module's documents, so each place is set here rather than left to
 * declaration order.
 */
@Suppress("ConstPropertyName")
object DOCORDER {
    /** "Start here", the documentation's map by audience (issue #1510): it reads first. */
    const val startHere = -100
    const val walkThrough = -90
    const val readme = -80
    const val confDemo = -70
    /** The index of the references generated from the code. */
    const val reference = -60
    /** The license reads last. */
    const val license = 100
}

/** Where the components' declared documents are kept in the instance config, in listing order ([checkedMarkdownDocs]). */
@Suppress("ConstPropertyName")
object MDOC {
    const val registryKey = "markdownDocs"
}

/**
 * [docs] as the boot keeps them -- in listing order, by [MarkdownDocDef.order] and then as declared -- refused when
 * two share a [MarkdownDocDef.docId] or a [MarkdownDocDef.sourcePath]: either would make one of them unreachable --
 * a link resolves to a single id, and an id to a single resource -- and which one won would depend on component
 * order. Declarations are code, so this is a bug to fix, not data to forgive.
 */
fun checkedMarkdownDocs(docs: List<MarkdownDocDef>): List<MarkdownDocDef> {
    for ((what, key) in listOf<Pair<String, (MarkdownDocDef) -> String>>("id" to { it.docId }, "source path" to { it.sourcePath })) {
        docs.groupBy(key).filterValues { it.size > 1 }.keys.firstOrNull()?.let {
            throw KdrException("Two documents are declared with the $what '$it'; each document needs its own.")
        }
    }
    docs.firstOrNull { !ContentResources.isSafeFileId(it.docId) }?.let {
        throw KdrException(
            "Document id '${it.docId}' cannot name a resource: a document id is letters, digits, '-' and '_' only.",
        )
    }
    // A stable sort: documents of one order keep the order they were declared in.
    return docs.sortedBy { it.order }
}
