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
})
