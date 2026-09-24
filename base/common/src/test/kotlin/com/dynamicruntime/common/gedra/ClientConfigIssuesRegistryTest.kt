package com.dynamicruntime.common.gedra

import com.dynamicruntime.common.context.ENV
import com.dynamicruntime.common.context.KdrCxt
import com.dynamicruntime.common.context.KdrInstanceConfig
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe

/**
 * The per-client issue registry (issue #840): what it keeps, what it ignores, and how a reload replaces a list.
 * The endpoints that return these lists are exercised end to end in `ClientConfigIssuesTest` (kdn).
 */
class ClientConfigIssuesRegistryTest : StringSpec({

    fun issue(client: String?, message: String, storedConfigId: String? = null) =
        GedraConfigIssue(message, "Dropped it.", client = client, storedConfigId = storedConfigId)

    "issues are kept per client, once each, and an issue with no client is not kept" {
        val registry = ClientConfigIssues()
        registry.record(issue("acme", "One."))
        registry.record(issue("acme", "One."))       // re-reported by a whole-node recheck
        registry.record(issue("acme", "Two.", storedConfigId = "gc.cd.acme.main"))
        registry.record(issue("globex", "Three."))
        registry.record(issue(null, "Nobody's."))
        registry.issuesFor("acme").map { it.message } shouldBe listOf("One.", "Two.")
        registry.issuesForConfig("acme", "gc.cd.acme.main").map { it.message } shouldBe listOf("Two.")
        registry.issuesFor("globex").map { it.message } shouldBe listOf("Three.")
        registry.issuesFor("initech").shouldBeEmpty()
    }

    "replace clears a client's list and hands back what it held, so a failed reload can restore it" {
        val registry = ClientConfigIssues()
        registry.record(issue("acme", "One."))
        val prior = registry.replace("acme", emptyList())
        registry.issuesFor("acme").shouldBeEmpty()
        registry.replace("acme", prior)
        registry.issuesFor("acme").map { it.message } shouldBe listOf("One.")
    }

    // Every forgiven client gets its issues, not only stored ones: a source-code client forgiven in production
    // (where source problems warn) is recorded on its client just the same.
    "a source problem forgiven in production is kept on its client" {
        val prod = KdrCxt("issues", KdrInstanceConfig("issues-prod", ENV.prod, ENV.liveSource))
        reportConfigProblem(prod, issue("acme", "A source problem."), mutableListOf())
        val kept = ClientConfigIssues.get(prod).issuesFor("acme").single()
        kept.message shouldBe "A source problem."
        kept.origin shouldBe GedraConfigOrigin.source
    }
})
