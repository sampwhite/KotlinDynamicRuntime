package com.dynamicruntime.kdn

import com.dynamicruntime.common.content.FRAG
import com.dynamicruntime.common.content.FragmentNamespaceBuilder
import com.dynamicruntime.common.content.MarkdownFragmentService
import com.dynamicruntime.common.content.fragmentInline
import com.dynamicruntime.common.context.KdrCxt
import com.dynamicruntime.common.exception.KdrException
import com.dynamicruntime.common.util.ScriptError
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe

/**
 * The boot fragment check validates `@t` references, not just template syntax (Phase 2 of issue #505). The
 * per-reference and cycle logic is covered on both targets in `FragmentReferenceCheckTest`; this exercises the
 * wiring -- that `checkFragments` runs it over the registered fragments, and a strict boot refuses on a
 * finding. A fragment is injected through `FRAG.registryKey`, which replaces the node's registered set for the
 * duration of the check.
 */
class FragmentReferenceBootTest : StringSpec({

    fun service(cxt: KdrCxt): MarkdownFragmentService = MarkdownFragmentService.get(cxt)

    fun register(cxt: KdrCxt, build: FragmentNamespaceBuilder.() -> Unit) {
        cxt.instanceConfig.put(
            FRAG.registryKey,
            listOf(fragmentInline("wf", origin = "test", isOverlay = false) { namespace("items", build) }),
        )
    }

    "a dangling @t reference in a registered fragment is reported" {
        val cxt = Startup.mkTestBootCxt("fragRefDangling", "fragRefDanglingTest")
        register(cxt) {
            key("chooser", $$"""${@t("items.gone")}""")
            key("noItems", "none")
        }
        val issues = service(cxt).checkFragments(cxt).flatMap { it.issues }
        issues.any { it.code == ScriptError.fragmentNotFound && it.message.contains("items.gone") } shouldBe true
    }

    "a valid @t reference is clean" {
        val cxt = Startup.mkTestBootCxt("fragRefValid", "fragRefValidTest")
        register(cxt) {
            key("chooser", $$"""${@t("items.noItems")}""")
            key("noItems", "none")
        }
        val refIssues = service(cxt).checkFragments(cxt)
            .flatMap { it.issues }
            .filter { it.code == ScriptError.fragmentNotFound || it.code == ScriptError.fragmentCycle }
        refIssues shouldBe emptyList()
    }

    "a reference cycle in a registered fragment is reported" {
        val cxt = Startup.mkTestBootCxt("fragRefCycle", "fragRefCycleTest")
        register(cxt) {
            key("a", $$"""${@t("items.b")}""")
            key("b", $$"""${@t("items.a")}""")
        }
        val issues = service(cxt).checkFragments(cxt).flatMap { it.issues }
        issues.any { it.code == ScriptError.fragmentCycle } shouldBe true
    }

    "a strict boot refuses to start on a dangling @t reference" {
        // mkTestBootCxt forces the unit environment, where the check is strict.
        val cxt = Startup.mkTestBootCxt("fragRefStrict", "fragRefStrictTest")
        register(cxt) {
            key("chooser", $$"""${@t("items.gone")}""")
            key("noItems", "none")
        }
        val ex = shouldThrow<KdrException> { service(cxt).checkFragmentsAtStartup(cxt) }
        (ex.message ?: "").contains("items.gone") shouldBe true
    }
})
