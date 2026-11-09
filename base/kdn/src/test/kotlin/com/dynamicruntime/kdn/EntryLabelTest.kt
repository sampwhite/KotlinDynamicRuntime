package com.dynamicruntime.kdn

import com.dynamicruntime.common.content.MarkdownFragmentService
import com.dynamicruntime.common.home.HFRAG
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe

/**
 * What a form heads each entry of a list with (issue #1714), as the home copy ships it: the line the web app says until
 * the shell's copy arrives, so the two never disagree. The conference demo's own, for its co-speakers, is held to its
 * page by `ConfDemoDocsTest`.
 */
class EntryLabelTest : StringSpec({
    val cxt = TestInstances.default("entryLabel1714")

    "the home copy ships the line the web app falls back to" {
        val home = MarkdownFragmentService.get(cxt).effectiveFragmentsFor(cxt, HFRAG.home, null).shouldNotBeNull()
        home.content[HFRAG.formEntriesNs]?.get(HFRAG.formEntryLabel) shouldBe HFRAG.defaultEntryLabel
    }
})
