package com.dynamicruntime.kdn

import com.dynamicruntime.common.content.FRAG
import com.dynamicruntime.common.content.FragmentAudience
import com.dynamicruntime.common.content.MarkdownFragmentService
import com.dynamicruntime.common.content.fragmentInline
import com.dynamicruntime.common.context.KdrCxt
import com.dynamicruntime.common.exception.KdrException
import com.dynamicruntime.common.util.ScriptError
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain

/**
 * Registry-wide validation of the **backend** `@t` pass (the reference-level remainder of issue #505). Where
 * the frontend checker (`FragmentReferenceBootTest`) resolves a two-part pull within one file, a backend pull
 * is `%{@t("fileId.namespace.key")}` -- three parts across the whole registry -- so its validation lives in
 * `MarkdownFragmentService`, which has the registry, not in the per-file kernel walk. Two things it catches
 * that the audience checks (`FragmentAudienceTest`) do not: a pull to a **missing key** in a real backend
 * file, and a **cycle** of backend files pulling each other.
 *
 * Fragments are injected through `FRAG.registryKey`, replacing the node's set, so only the files each case
 * declares are in play. Values use `%{...}` (no `$`), so plain strings -- no multi-dollar prefix.
 */
class BackendReferenceCheckTest : StringSpec({

    fun service(cxt: KdrCxt): MarkdownFragmentService = MarkdownFragmentService.get(cxt)

    fun backend(fileId: String, ns: String, vararg entries: Pair<String, String>) =
        fragmentInline(fileId, origin = "test", isOverlay = false, audience = FragmentAudience.backend) {
            namespace(ns) { entries.forEach { (k, v) -> key(k, v) } }
        }

    fun issues(cxt: KdrCxt) = service(cxt).checkFragments(cxt).flatMap { it.issues }

    // --- dangling: the pulled key must exist in the (backend) target ------------------------------------

    "a backend pull naming a missing key in a backend file is a dangling reference" {
        val cxt = Startup.mkTestBootCxt("beDangling", "beDanglingTest")
        val data = backend("beData", "email", "subject" to "Code")
        val puller = backend("bePull", "email", "body" to """%{@t("beData.email.gone")}""")
        cxt.instanceConfig.put(FRAG.registryKey, listOf(data, puller))
        issues(cxt).any {
            it.code == ScriptError.fragmentNotFound && it.message.contains("beData") && it.message.contains("email.gone")
        } shouldBe true
    }

    "a guarded backend pull to a missing key is left to its default, not reported" {
        val cxt = Startup.mkTestBootCxt("beGuarded", "beGuardedTest")
        val data = backend("beData", "email", "subject" to "Code")
        val puller = backend("bePull", "email", "body" to """%{@t("beData.email.gone") ?: "fallback"}""")
        cxt.instanceConfig.put(FRAG.registryKey, listOf(data, puller))
        issues(cxt).any { it.code == ScriptError.fragmentNotFound } shouldBe false
    }

    "a backend pull whose key exists in the target is clean" {
        val cxt = Startup.mkTestBootCxt("beValidRef", "beValidRefTest")
        val data = backend("beData", "email", "subject" to "Code")
        val puller = backend("bePull", "email", "body" to """Subject: %{@t("beData.email.subject")}""")
        cxt.instanceConfig.put(FRAG.registryKey, listOf(data, puller))
        issues(cxt).filter {
            it.code == ScriptError.fragmentNotFound || it.code == ScriptError.fragmentCycle
        }.shouldBeEmpty()
    }

    // --- cross-file cycles ------------------------------------------------------------------------------

    "a backend reference cycle across files is reported" {
        val cxt = Startup.mkTestBootCxt("beCycle", "beCycleTest")
        val a = backend("beA", "x", "a" to """%{@t("beB.x.b")}""")
        val b = backend("beB", "x", "b" to """%{@t("beA.x.a")}""")
        cxt.instanceConfig.put(FRAG.registryKey, listOf(a, b))
        issues(cxt).any {
            it.code == ScriptError.fragmentCycle && it.message.contains("beA.x.a") && it.message.contains("beB.x.b")
        } shouldBe true
    }

    "a strict boot refuses on a backend reference cycle" {
        val cxt = Startup.mkTestBootCxt("beCycleStrict", "beCycleStrictTest")
        val a = backend("beA", "x", "a" to """%{@t("beB.x.b")}""")
        val b = backend("beB", "x", "b" to """%{@t("beA.x.a")}""")
        cxt.instanceConfig.put(FRAG.registryKey, listOf(a, b))
        shouldThrow<KdrException> { service(cxt).checkFragmentsAtStartup(cxt) }.message.orEmpty() shouldContain "cycle"
    }

    "a non-cyclic backend chain is clean" {
        val cxt = Startup.mkTestBootCxt("beChain", "beChainTest")
        val a = backend("beA", "x", "a" to """%{@t("beB.x.b")}""")
        val b = backend("beB", "x", "b" to "leaf")
        cxt.instanceConfig.put(FRAG.registryKey, listOf(a, b))
        issues(cxt).filter { it.code == ScriptError.fragmentCycle }.shouldBeEmpty()
    }

    // --- the checks resolve in the *variant's* client view, not the caller's -----------------------------

    /**
     * A client overlay can replace a base value with a pull and so close a cycle that exists **for that client
     * alone**. Scanning only the shared content would miss it entirely; scanning only the ambient client would
     * also make the answer depend on who asked. Both are why the scan is per variant (issue #505).
     */
    "a cycle closed only by a client's overlay is found, and named for that client" {
        val cxt = Startup.mkTestBootCxt("beCliCycle", "beCliCycleTest")
        // Shared content is acyclic: beA -> beB, and beB's value is a leaf.
        val a = backend("beA", "x", "a" to """%{@t("beB.x.b")}""")
        val b = backend("beB", "x", "b" to "leaf")
        // Acme's overlay replaces that leaf with a pull back to beA, closing the loop for acme only. It
        // overlays a key the base declares, so it is not an orphan and nothing else objects.
        val acme = fragmentInline("beB", origin = "acmeConfig", client = "acme") {
            namespace("x") { key("b", """%{@t("beA.x.a")}""") }
        }
        cxt.instanceConfig.put(FRAG.registryKey, listOf(a, b, acme))

        // Reported on the entry-point file's shared row, naming acme -- the entry point may be a file acme does
        // not overlay (as here: acme overlays beB, the cycle starts at beA), so a client row is not guaranteed
        // to exist and the finding would be dropped. The message is what says which variant is broken.
        val cycleIssues = service(cxt).checkFragments(cxt).flatMap { it.issues }
            .filter { it.code == ScriptError.fragmentCycle }
        cycleIssues.any { it.message.contains("acme") } shouldBe true
        // And a strict boot refuses on it, which is the point -- it would otherwise reach acme's users only.
        shouldThrow<KdrException> { service(cxt).checkFragmentsAtStartup(cxt) }
            .message.orEmpty() shouldContain "acme"
    }

    "a shared cycle is reported once, not repeated on every client's row" {
        val cxt = Startup.mkTestBootCxt("beCliDup", "beCliDupTest")
        val a = backend("beA", "x", "a" to """%{@t("beB.x.b")}""")
        val b = backend("beB", "x", "b" to """%{@t("beA.x.a")}""")
        // Acme overlays an unrelated key of a backend file, so it gets its own variant rows...
        val acme = fragmentInline("beB", origin = "acmeConfig", client = "acme") {
            namespace("x") { key("b", """%{@t("beA.x.a")}""") }
        }
        cxt.instanceConfig.put(FRAG.registryKey, listOf(a, b, acme))
        // ...but the cycle is already the shared content's, so it is said once rather than once per client.
        service(cxt).checkFragments(cxt).flatMap { it.issues }
            .count { it.code == ScriptError.fragmentCycle } shouldBe 1
    }

    /**
     * The target lookup must resolve in the *variant's* client view too, not just the pulling content. Built so
     * the pull exists **only** in acme's variant and resolves **only** against acme's view of the target: the
     * shared content has neither, so nothing is reported there, and reading the target with the ambient client
     * instead would wrongly call acme's pull dangling.
     */
    "a pull that resolves only in the client's own view of the target is not reported" {
        val cxt = Startup.mkTestBootCxt("beCliTarget", "beCliTargetTest")
        val data = backend("beData", "email", "subject" to "Code")
        val puller = backend("bePull", "email", "body" to "plain, no pull")
        // Acme adds a key the shared base does not have. (That also earns an `orphans` finding -- with only
        // overlays available to a client, adding a key is indistinguishable from overlaying a renamed one --
        // which is beside the point here: this asserts only on dangling references.)
        val acmeData = fragmentInline("beData", origin = "acmeConfig", client = "acme") {
            namespace("email") { key("acmeOnly", "Acme only") }
        }
        // ...and, in its own copy of the puller, pulls a key only the shared base lacks. Overlaying the puller
        // is what gives `bePull` an acme row at all -- without it there is no acme variant to check.
        val acmePull = fragmentInline("bePull", origin = "acmeConfig", client = "acme") {
            namespace("email") { key("body", """%{@t("beData.email.acmeOnly")}""") }
        }
        cxt.instanceConfig.put(FRAG.registryKey, listOf(data, puller, acmeData, acmePull))

        val rows = service(cxt).checkFragments(cxt)
        // Acme's variant of the puller exists and its pull resolves, so nothing is reported for it.
        rows.any { it.fileId == "bePull" && it.client == "acme" } shouldBe true
        rows.flatMap { it.issues }.any { it.code == ScriptError.fragmentNotFound } shouldBe false
    }
})
