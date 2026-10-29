package com.dynamicruntime.common.content

import com.dynamicruntime.common.util.resolveDocLink
import com.dynamicruntime.common.webasset.WebAssetCheck
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe

/**
 * Holds `.claude/skills/kdr-images` to the code (issue #1465), by building its example declaration and checking the
 * claims it makes about what the boot refuses and how a document names an image.
 *
 * **What this does not do:** it cannot read the Markdown, and it cannot judge the house look, which is the skill's
 * point. It pins the *example and the rules the skill states* -- if you change them and land here, the skill needs
 * the same edit. Keep the code below a faithful transcription of what the skill shows.
 *
 * The images themselves are covered by `ComponentImageTest` (kdn) and `DocLinkUtilTest`. This is about the
 * documentation.
 */
class ImagesSkillExamplesTest : StringSpec({

    "the skill's declaration: a document image names the repo path its file is kept at" {
        val image = componentImage("kdr:walkPart1", "walk-part1.svg", sourcePath = "docs/images/walk-part1.svg")
        image.sourcePath shouldBe "docs/images/walk-part1.svg"
        IMGS.isSafeFileName(image.fileName) shouldBe true
        IMGS.isRepoPath(image.sourcePath!!) shouldBe true
    }

    "a document's plain image draws the served image with the source path, and GitHub's blob page without it" {
        val served = mapOf("docs/images/walk-part1.svg" to "/st/app/img/kdr:walkPart1:abc")
        val blob = "https://github.com/o/r/blob/main"
        fun resolve(paths: Map<String, String>) =
            resolveDocLink("images/walk-part1.svg", "docs/walk-through.md", emptyMap(), blob, paths) { _, _ -> "" }
        resolve(served) shouldBe "/st/app/img/kdr:walkPart1:abc"
        resolve(emptyMap()) shouldBe "$blob/docs/images/walk-part1.svg"
    }

    "what the boot refuses, as the skill lists it" {
        // A file name: a plain name with a lowercase extension.
        IMGS.isSafeFileName("walk-part1.SVG") shouldBe false
        IMGS.isSafeFileName("images/walk-part1.svg") shouldBe false
        // A source path: a clean repo path.
        listOf("/docs/images/x.svg", "docs/../x.svg", "docs/./x.svg", "docs//x.svg").forEach { IMGS.isRepoPath(it) shouldBe false }
        // The size: 2 MB.
        IMG.maxSize shouldBe 2 * 1024 * 1024
        // An SVG with nothing in it to run; a title, a comment, and text are fine.
        val fine = """<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 10 10" role="img"><title>A dot.</title>""" +
            """<!-- issue #1465 --><circle cx="5" cy="5" r="4"/><text x="1" y="9">x</text></svg>"""
        WebAssetCheck.svgProblem(fine.toByteArray()).shouldBeNull()
        listOf(
            """<svg xmlns="http://www.w3.org/2000/svg"><script>x()</script></svg>""",
            """<svg xmlns="http://www.w3.org/2000/svg" onload="x()"></svg>""",
            """<svg xmlns="http://www.w3.org/2000/svg" xmlns:l="http://www.w3.org/1999/xlink"><a l:href="javascript:x()"/></svg>""",
        ).forEach { WebAssetCheck.svgProblem(it.toByteArray()).shouldNotBeNull() }
    }
})
