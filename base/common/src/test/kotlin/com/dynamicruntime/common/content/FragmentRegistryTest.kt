package com.dynamicruntime.common.content

import com.dynamicruntime.common.context.KdrCxt
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe

/**
 * What the service makes of the layers a boot collected (issue #456): how many of them apply, and how a
 * missing file is reported.
 *
 * Built by putting layers straight into the registry rather than by booting a node, which is the seam under
 * test -- and which lets a *missing* file be examined at all, since a real boot refuses to start over one.
 */
class FragmentRegistryTest : StringSpec({

    fun serviceOver(vararg sources: FragmentSource): Pair<MarkdownFragmentService, KdrCxt> {
        val cxt = KdrCxt.mkSimpleCxt("fragmentRegistryTest")
        cxt.instanceConfig.put(FRAG.registryKey, sources.toList())
        return MarkdownFragmentService() to cxt
    }

    fun inline(fileId: String, ns: String, key: String, value: String, origin: String = "aComponent") =
        fragmentInline(fileId, origin = origin, isOverlay = false) { namespace(ns) { key(key, value) } }

    "two layers a component wrote with the same origin both apply" {
        // They are different statements: a layer's content is part of what it is. Deduplicating by the other
        // fields would read these as one and silently drop the second, and `origin` is prose a component
        // naturally sets to its own name, so the collision is the expected case rather than a corner.
        val (service, cxt) = serviceOver(
            inline("home", "welcome", "title", "Welcome"),
            inline("home", "footer", "copyright", "Ours"),
        )
        val content = service.effectiveFragments(cxt, "home")?.content
        content?.get("welcome")?.get("title") shouldBe "Welcome"
        content?.get("footer")?.get("copyright") shouldBe "Ours"
    }

    "the same file declared twice is folded in twice, harmlessly" {
        // Why no deduplication is needed: folding a layer in again puts the same keys over themselves.
        val (service, cxt) = serviceOver(
            inline("home", "welcome", "title", "Welcome"),
            inline("home", "welcome", "title", "Welcome"),
        )
        service.effectiveFragments(cxt, "home")?.content?.get("welcome") shouldBe mapOf("title" to "Welcome")
    }

    "a missing file is reported once, not once per client that overlays it" {
        // A base layer belongs to no client, so a file whose base is absent is absent for everybody. Reporting
        // it per variant would make one missing resource read as three broken files in a boot refusal.
        val absentBase = FragmentSource("home", isOverlay = false, client = null, origin = "missing") { null }
        val acme = fragmentInline("home", origin = "acmeConfig", client = "acme") {
            namespace("welcome") { key("title", "Acme") }
        }
        val globex = fragmentInline("home", origin = "globexConfig", client = "globex") {
            namespace("welcome") { key("title", "Globex") }
        }
        val (service, cxt) = serviceOver(absentBase, acme, globex)

        val rows = service.checkFragments(cxt)
        rows.map { it.client } shouldContainExactly listOf(null)
        rows.single().found shouldBe false
    }

    "a present file is still reported per client, because a client's copy is copy" {
        val (service, cxt) = serviceOver(
            inline("home", "welcome", "title", "Welcome"),
            fragmentInline("home", origin = "acmeConfig", client = "acme") {
                namespace("welcome") { key("title", "Acme") }
            },
        )
        service.checkFragments(cxt).map { it.client } shouldContainExactly listOf(null, "acme")
    }
})
