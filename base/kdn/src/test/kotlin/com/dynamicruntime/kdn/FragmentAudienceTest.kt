package com.dynamicruntime.kdn

import com.dynamicruntime.common.content.FRAG
import com.dynamicruntime.common.content.FragmentAudience
import com.dynamicruntime.common.content.MarkdownFragmentService
import com.dynamicruntime.common.content.fragmentInline
import com.dynamicruntime.common.context.KdrCxt
import com.dynamicruntime.common.exception.EXC
import com.dynamicruntime.common.http.request.TestHttpClient
import com.dynamicruntime.common.util.ScriptError
import com.dynamicruntime.common.util.jsonMap
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain

/**
 * A fragment file's **audience** (issue #514): a [FragmentAudience.frontend] file is delivered by the content
 * server, a [FragmentAudience.backend] file never is -- it exists only to be pulled server-side by the backend
 * `@t` pass (issue #505). The two files here are registered through `FRAG.registryKey`, which replaces the
 * node's set for the test, so the only files in play are the ones each case declares.
 *
 * The load-bearing assertion is the asymmetry: the same backend file that [serve][MarkdownFragmentService]
 * refuses over HTTP is one the backend resolver resolves happily. That is the point of the audience -- private,
 * not absent.
 */
class FragmentAudienceTest : StringSpec({

    fun service(cxt: KdrCxt): MarkdownFragmentService = MarkdownFragmentService.get(cxt)

    /** A frontend file and a backend file, both bases, registered as the node's whole fragment set. */
    fun register(cxt: KdrCxt) {
        val front = fragmentInline("audFront", origin = "test", isOverlay = false) {
            namespace("welcome") { key("title", "Hi there") }
        }
        val back = fragmentInline(
            "audBack", origin = "test", isOverlay = false, audience = FragmentAudience.backend,
        ) {
            namespace("email") { key("subject", "Secret code") }
        }
        cxt.instanceConfig.put(FRAG.registryKey, listOf(front, back))
    }

    "the content server delivers a frontend file and refuses a backend one as though absent" {
        val cxt = Startup.mkTestBootCxt("audServe", "audServeTest")
        register(cxt)
        val client = TestHttpClient(cxt.instanceConfig)

        // The frontend file is served, values and all.
        val frontBuildId = MarkdownFragmentService.fragmentBuildId(cxt, "audFront").shouldNotBeNull()
        val frontResp = client.sendGetRequestRaw("/st/myapp/md/audFront:$frontBuildId")
        frontResp.rptStatusCode shouldBe EXC.ok
        @Suppress("UNCHECKED_CAST")
        val welcome = frontResp.rptResponseData.shouldNotBeNull().jsonMap()!!["welcome"] as Map<String, Any?>
        welcome["title"] shouldBe "Hi there"

        // The backend file is found on the node -- but has no served build id, and its URL 404s either way.
        MarkdownFragmentService.fragmentBuildId(cxt, "audBack").shouldBeNull()
        val backBuildId = service(cxt).effectiveFragments(cxt, "audBack").shouldNotBeNull().buildId
        client.sendGetRequestRaw("/st/myapp/md/audBack:$backBuildId").rptStatusCode shouldBe EXC.notFound
        client.sendGetRequestRaw("/st/myapp/md/audBack").rptStatusCode shouldBe EXC.notFound
    }

    "a backend file the server withholds is still resolvable by the backend pass" {
        val cxt = Startup.mkTestBootCxt("audPull", "audPullTest")
        register(cxt)
        val resolver = service(cxt).backendResolver(cxt)
        // The whole reason a backend file exists: a `%{@t("fileId.namespace.key")}` pulls it server-side.
        resolver.resolve("audBack.email.subject") shouldBe "Secret code"
        // And a frontend file is backend-pullable too -- the backend has every file; audience only governs
        // whether the *frontend* is served it.
        resolver.resolve("audFront.welcome.title") shouldBe "Hi there"
    }

    "the boot check does not mistake a backend file's cross-file reference for a dangling one" {
        val cxt = Startup.mkTestBootCxt("audCheckRef", "audCheckRefTest")
        // A backend value pulls another file three-part. The per-file walk cannot resolve three parts (that is
        // the registry-wide backend-pass check, still a follow-up), so the point is only that it does not
        // *mis-report* it -- applying the frontend two-part rule would call it dangling.
        val back = fragmentInline(
            "audBack", origin = "test", isOverlay = false, audience = FragmentAudience.backend,
        ) {
            namespace("email") { key("subject", $$"""%{@t("other.ns.key")}""") }
        }
        cxt.instanceConfig.put(FRAG.registryKey, listOf(back))
        val issues = service(cxt).checkFragments(cxt).flatMap { it.issues }
        issues.any { it.code == ScriptError.fragmentNotFound } shouldBe false
    }

    "the boot check still catches a malformed backend block" {
        val cxt = Startup.mkTestBootCxt("audCheckBad", "audCheckBadTest")
        // Syntax is syntax whoever finishes the value: an unterminated `%{` is caught here, at the keyboard.
        val back = fragmentInline(
            "audBack", origin = "test", isOverlay = false, audience = FragmentAudience.backend,
        ) {
            namespace("email") { key("subject", "%{unterminated") }
        }
        cxt.instanceConfig.put(FRAG.registryKey, listOf(back))
        service(cxt).checkFragments(cxt).flatMap { it.issues }.isNotEmpty() shouldBe true
    }
})
