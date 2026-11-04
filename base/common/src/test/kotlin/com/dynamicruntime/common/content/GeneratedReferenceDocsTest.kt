package com.dynamicruntime.common.content

import com.dynamicruntime.common.exception.KdrException
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.assertions.withClue
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.ints.shouldBeGreaterThan
import io.kotest.matchers.shouldBe
import java.io.File

/**
 * The committed copies of the generated reference documents are current (issue #1603): each file's generated region
 * holds what its generator makes now. Run by `./gradlew :base:common:refDocsTest`, a task of its own whose inputs are
 * the files, as `docLinksTest` is (the module's `test` task leaves it out, and `check` runs it).
 */
class GeneratedReferenceDocsTest : StringSpec({
    // The tests run in the module's directory (base/common), two below the repository root.
    val repoRoot = File("../..").canonicalFile

    "every generated document's file holds what its generator makes now" {
        val docs = generatedReferenceDocs()
        docs.size shouldBeGreaterThan 5
        val stale = docs.filter { doc ->
            val text = File(repoRoot, doc.sourcePath).readText()
            withGeneratedRegion(text, doc.generatedText.orEmpty(), doc.sourcePath) != text
        }.map { it.sourcePath }
        withClue("Behind their generators; rewrite them with `${GENREF.dumpCommand}` and commit the result") {
            stale.shouldBeEmpty()
        }
    }

    "a region is replaced, and the file's own text around it is kept" {
        val file = "# Hand\n\n${GENREF.beginPrefix} old -->\nold text\n${GENREF.endLine}\n\n## About\n"
        withGeneratedRegion(file, "# New\n\nnew text\n", "x.md") shouldBe
            "# Hand\n\n${GENREF.beginLine}\n# New\n\nnew text\n${GENREF.endLine}\n\n## About\n"
    }

    "a file without one well-formed region is refused" {
        for (file in listOf(
            "no markers\n",
            "${GENREF.endLine}\n${GENREF.beginLine}\n",
            "${GENREF.beginLine}\n${GENREF.endLine}\n${GENREF.beginLine}\n${GENREF.endLine}\n",
        )) {
            shouldThrow<KdrException> { withGeneratedRegion(file, "text", "x.md") }
        }
    }
})
