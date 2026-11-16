package com.dynamicruntime.common.home

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.collections.shouldNotBeEmpty
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain

/**
 * The reference index (issue #1632) lists every served reference from the declarations, links the two no server
 * serves, and is the one reference the Documents list offers.
 *
 * Theme: #DOCDR.
 */
class ReferenceIndexTest : StringSpec({
    "every document under the references' directory but the index is a served reference, generated and unlisted" {
        val references = servedReferences()
        references.map { it.docId } shouldContainExactlyInAnyOrder listOf(
            HDOC.schemaDialect, HDOC.validationMessages, HDOC.expressions, HDOC.facts, HDOC.copy, HDOC.pageWidgets, HDOC.reportPaths,
            HDOC.formLayouts, HDOC.menuAndHomeCards, HDOC.rolesAndAccess, HDOC.clientConfiguration,
        )
        for (doc in references) {
            (doc.generate != null) shouldBe true
            doc.listed shouldBe false
        }
        val index = coreDocs().single { it.docId == HDOC.referenceIndex }
        index.sourcePath shouldBe REFDOC.indexPath
        index.listed shouldBe true
    }

    "the index links each reference, served or not, relative to its directory, and not itself" {
        val page = referenceIndexMarkdown()
        val references = servedReferences().shouldNotBeEmpty()
        for (doc in references) {
            page shouldContain "| [${doc.label}](${doc.sourcePath.removePrefix(REFDOC.dir)}) | ${doc.description} |"
        }
        page shouldContain "](${REFDOC.envVarsPath.removePrefix(REFDOC.dir)})"
        page shouldContain "](${REFDOC.endpointsPath.removePrefix(REFDOC.dir)})"
        page shouldNotContain "](README.md)"
    }
})
