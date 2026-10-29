package com.dynamicruntime.common.content

import com.dynamicruntime.common.util.headingSlug
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.ints.shouldBeGreaterThan
import java.io.File

/**
 * Every link in the repository's documentation resolves (issue #1294): `README.md`, both `CLAUDE.md` files, everything
 * under `docs/` and `.claude/skills/`, and the guides in `examples/`. A link names a page or file that exists in the repository, and a
 * `#section` that is a heading of the page it names -- as GitHub mints heading anchors, which the app's renderer
 * matches (`headingSlug`). An outside address, a `mailto:`, and a page of the app (`/wa/...`, or `#page=...` from
 * before paths) are left alone.
 *
 * The documents link to each other by relative paths, which a move breaks without a word; this is what makes a
 * broken one fail the build. Links inside code -- fenced blocks and inline code spans -- are not links.
 */
class RepoDocLinksTest : StringSpec({
    // The tests run in the module's directory (base/common), two below the repository root.
    val repoRoot = File("../..").canonicalFile

    fun documents(): List<File> {
        val roots = listOf(File(repoRoot, "README.md"), File(repoRoot, "CLAUDE.md"), File(repoRoot, "webapp/CLAUDE.md"))
        // The skills too (issue #1468): `kdr-webapp-pages` is an index linking its page files by relative path.
        val trees = listOf("docs", "examples", ".claude/skills").map { File(repoRoot, it) }
            .flatMap { dir -> dir.walkTopDown().filter { it.isFile && it.name.endsWith(".md") }.toList() }
        return (roots + trees).filter { it.exists() }.sortedBy { it.path }
    }

    "every link in the documentation resolves, to a file that exists and a section it has" {
        val docs = documents()
        docs.size shouldBeGreaterThan 10
        val problems = mutableListOf<String>()
        for (doc in docs) {
            for ((line, target) in links(doc)) {
                val where = "${doc.relativeTo(repoRoot)}, line $line"
                val appPage = target.startsWith("/wa/") || target.startsWith("#") && target.contains("=")
                if (target.contains("://") || target.startsWith("mailto:") || appPage) continue
                val path = target.substringBefore('#')
                val anchor = target.substringAfter('#', "").ifEmpty { null }
                val file = when {
                    path.isEmpty() -> doc
                    path.startsWith("/") -> File(repoRoot, path.removePrefix("/"))
                    else -> File(doc.parentFile, path)
                }.canonicalFile
                if (!file.exists() || !file.path.startsWith(repoRoot.path)) {
                    problems.add("$where: '$target' names '$path', which is not in the repository.")
                    continue
                }
                if (anchor != null && anchor !in headingSlugs(file)) {
                    problems.add("$where: '$target' names the section '#$anchor', which '${file.name}' has no heading for.")
                }
            }
        }
        problems.shouldBeEmpty()
    }
})

private val linkRegex = Regex("""\[[^]]*]\(([^)\s]+)\)""")

/** Each Markdown link of [doc] outside code, with its line number. */
private fun links(doc: File): List<Pair<Int, String>> {
    var inFence = false
    return doc.readLines().flatMapIndexed { i, line ->
        if (line.trimStart().startsWith("```")) inFence = !inFence
        if (inFence) emptyList() else linkRegex.findAll(line.replace(Regex("`[^`]*`"), "")).map { i + 1 to it.groupValues[1] }.toList()
    }
}

/** The anchor ids [file]'s headings render with, as GitHub and the app both mint them. */
private fun headingSlugs(file: File): Set<String> {
    if (!file.name.endsWith(".md")) return emptySet()
    var inFence = false
    return file.readLines().mapNotNull { line ->
        if (line.trimStart().startsWith("```")) inFence = !inFence
        if (inFence) null else Regex("""^#{1,6}\s+(.*?)\s*#*\s*$""").find(line)?.groupValues?.get(1)?.let { headingSlug(it) }
    }.toSet()
}
