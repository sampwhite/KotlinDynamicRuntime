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
)

/** Where the components' declared documents are kept in the instance config, in declaration order. */
@Suppress("ConstPropertyName")
object MDOC {
    const val registryKey = "markdownDocs"
}

/**
 * [docs] as the boot keeps them, refused when two share a [MarkdownDocDef.docId] or a [MarkdownDocDef.sourcePath]:
 * either would make one of them unreachable -- a link resolves to a single id, and an id to a single resource -- and
 * which one won would depend on component order. Declarations are code, so this is a bug to fix, not data to forgive.
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
    return docs
}
