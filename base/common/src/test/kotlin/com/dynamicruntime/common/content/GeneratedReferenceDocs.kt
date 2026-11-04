package com.dynamicruntime.common.content

import com.dynamicruntime.common.exception.KdrException
import com.dynamicruntime.common.home.coreDocs
import java.io.File

/**
 * The generated reference documents, committed (issue #1603). A running server makes each of them from the code
 * ([MarkdownDocDef.generate]); the repository holds a copy in the file at its source path, between two marker lines,
 * so it can be read and searched without a build or a server, and a change to a reference shows in a diff. The
 * file's own text outside the markers -- where the document comes from, where it is served -- is written by hand.
 *
 * `./gradlew :base:common:refDocsDump` rewrites the copies ([main]), and `refDocsTest` ([GeneratedReferenceDocsTest],
 * part of `check`) fails while one differs from what its generator makes now. Both live with the tests, so nothing
 * here ships.
 */
@Suppress("ConstPropertyName")
object GENREF {
    const val dumpCommand = "./gradlew :base:common:refDocsDump"

    /** How the line opening a generated region begins; the rest of the line is the dump's to write. */
    const val beginPrefix = "<!-- kdr-generated: begin"
    const val beginLine = "$beginPrefix -- copied from its generator by `$dumpCommand`; edit the generator, not this -->"
    const val endLine = "<!-- kdr-generated: end -->"
}

/** The documents a generator makes. Only core's: no other component declares a generated one yet. */
fun generatedReferenceDocs(): List<MarkdownDocDef> = coreDocs().filter { it.generate != null }

/**
 * [fileText] with its generated region holding [generated], the rest of it unchanged. Refused when the file has no
 * region, or more than one, or an end before its begin: the markers are written by hand, once, where the document
 * goes, and a file whose markers are wrong is not one to guess about. [where] names the file in the message.
 */
fun withGeneratedRegion(fileText: String, generated: String, where: String): String {
    val lines = fileText.lines()
    val begins = lines.indices.filter { lines[it].startsWith(GENREF.beginPrefix) }
    val ends = lines.indices.filter { lines[it] == GENREF.endLine }
    val begin = begins.singleOrNull()
    val end = ends.singleOrNull()
    if (begin == null || end == null || end < begin) {
        throw KdrException(
            "$where needs exactly one generated region: a line beginning '${GENREF.beginPrefix}' and, after it, " +
                "the line '${GENREF.endLine}'.",
        )
    }
    val region = listOf(GENREF.beginLine) + generated.trimEnd('\n').lines() + GENREF.endLine
    return (lines.subList(0, begin) + region + lines.subList(end + 1, lines.size)).joinToString("\n")
}

/**
 * Rewrites each generated document's copy under the repository root named by the one argument, leaving a file whose
 * copy is current untouched (so its timestamp says when the reference last changed), and lists what it rewrote.
 */
fun main(args: Array<String>) {
    val repoRoot = File(args.singleOrNull() ?: throw KdrException("Name the repository root, and nothing else.")).canonicalFile
    val docs = generatedReferenceDocs()
    var rewritten = 0
    for (doc in docs) {
        val file = File(repoRoot, doc.sourcePath)
        if (!file.isFile) {
            throw KdrException("${doc.sourcePath} does not exist: a generated document's file holds its copy, so write " +
                "it first, with the markers where the copy goes.")
        }
        val text = file.readText()
        val next = withGeneratedRegion(text, doc.generatedText.orEmpty(), doc.sourcePath)
        if (next != text) {
            file.writeText(next)
            rewritten++
            println("Rewrote ${doc.sourcePath}")
        }
    }
    println("Generated reference documents: ${docs.size}, rewritten: $rewritten.")
}
