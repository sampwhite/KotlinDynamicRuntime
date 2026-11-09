package kdn

import com.dynamicruntime.common.exception.KdrException
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.assertions.withClue
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainAll
import io.kotest.matchers.ints.shouldBeGreaterThan
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import java.io.File

/**
 * The committed copies of the reference documents are current (issues #1603, #1607): each file's generated region holds
 * what its generator makes now. Run by `./gradlew :launch:refDocsTest`, a task of its own whose inputs are the files,
 * as `docLinksTest` is (the module's `test` task leaves it out, and `check` runs it).
 *
 * Theme: #DOCDR.
 */
class ReferenceCopiesTest : StringSpec({
    // The tests run in the module's directory (launch), one below the repository root.
    val repoRoot = File("..").canonicalFile

    "every reference document's file holds what its generator makes now" {
        val copies = referenceCopies(repoRoot)
        copies.size shouldBeGreaterThan 5
        val stale = copies.filter { copy ->
            val text = File(repoRoot, copy.sourcePath).readText()
            withGeneratedRegion(text, copy.generate(), copy.sourcePath) != text
        }.map { it.sourcePath }
        withClue("Behind their generators; rewrite them with `${GENREF.dumpCommand}` and commit the result") {
            stale.shouldBeEmpty()
        }
    }

    "the environment variables are found in every module, not only those a test has loaded" {
        // One from base/common, then one from each module that only that module reads: the edge, the assistant, the
        // multi-node fixtures, and the launcher. Nothing else in this JVM loads those modules' classes.
        declaredEnvVars(repoRoot).map { it.name } shouldContainAll listOf(
            "KDR_PORT", "KDR_EDGE_UPSTREAM", "KDR_ASSISTANT_MODEL", "KDR_MULTI_NODE_TEST", "KDR_CUSTOM_CONFIG",
        )
    }

    "the endpoint index holds every component's endpoints, and what only an edge adds apart" {
        val index = endpointIndex(repoRoot)
        // Core, the webapp host, the assistant, and the sample on the application node.
        for (path in listOf("`/admin/clients`", "`/app/ui/config`", "`/clientAdmin/assistant/status`", "`/demo/file/list`")) {
            index shouldContain path
        }
        // The edge's sign-in, under its own heading after the application's sections.
        index.substringAfter("## On an edge node") shouldContain "`/auth/env/login`"
        index.substringBefore("## On an edge node") shouldNotContain "`/auth/env/login`"
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
