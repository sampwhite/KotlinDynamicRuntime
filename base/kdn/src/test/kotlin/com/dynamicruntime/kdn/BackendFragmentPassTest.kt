package com.dynamicruntime.kdn

import com.dynamicruntime.common.content.FRAG
import com.dynamicruntime.common.content.MarkdownFragmentService
import com.dynamicruntime.common.endpoint.EP
import com.dynamicruntime.common.http.request.TestHttpClient
import com.dynamicruntime.common.test.TEP
import com.dynamicruntime.common.util.toJsonMap
import com.dynamicruntime.common.exception.KdrException
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain

/**
 * The backend `@t` pass (Phase 4 of issue #505): a `%{@t("fileId.namespace.key")}` resolved on the backend,
 * cross-file across the whole registry, before content ships -- while `${...}` is left for the frontend.
 *
 * The `sample` fragment file is registered on an ordinary boot, so `sample.email.subject` and
 * `sample.portal.welcome` are real three-part addresses to resolve against.
 */
class BackendFragmentPassTest : StringSpec({

    fun service(cxt: com.dynamicruntime.common.context.KdrCxt) = MarkdownFragmentService.get(cxt)

    "the backend resolver resolves a three-part key across the registry" {
        val cxt = Startup.mkTestBootCxt("beResolve", "beResolveTest")
        val r = service(cxt).backendResolver(cxt)
        r.resolve("${FRAG.sample}.email.subject") shouldBe "Your verification code"
        r.resolve("${FRAG.sample}.portal.welcome") shouldBe "Welcome to the portal."
    }

    "the backend resolver names nothing for a bad or non-three-part key" {
        val cxt = Startup.mkTestBootCxt("beResolveBad", "beResolveBadTest")
        val r = service(cxt).backendResolver(cxt)
        r.resolve("email.subject").shouldBeNull()             // two parts -- no file named
        r.resolve("nofile.email.subject").shouldBeNull()      // unknown file
        r.resolve("${FRAG.sample}.email.gone").shouldBeNull() // unknown key
        r.resolve("${FRAG.sample}.gone.subject").shouldBeNull() // unknown namespace
    }

    $$"the backend pass resolves %{...} and leaves ${...} for the frontend" {
        val cxt = Startup.mkTestBootCxt("bePass", "bePassTest")
        val out = service(cxt).backendPass(
            cxt,
            $$"""be=%{@t("sample.email.subject")} fe=${@t("portal.welcome")} v=${keep}""",
        )
        // The backend pull is substituted; the frontend pull and its `${...}` block survive verbatim.
        out shouldBe $$"""be=Your verification code fe=${@t("portal.welcome")} v=${keep}"""
    }

    "choosing '%' makes '%%' an escape and a lone '%' literal" {
        // The cost of any template prefix, documented on backendPassPrefix so copy running a backend pass can
        // live with it: doubled is the escape, lone is untouched.
        val cxt = Startup.mkTestBootCxt("bePct", "bePctTest")
        val s = service(cxt)
        s.backendPass(cxt, "100% off") shouldBe "100% off"       // a lone % is literal
        s.backendPass(cxt, "100%% off") shouldBe "100% off"      // doubled escapes to one
        s.backendPass(cxt, "100%%% off") shouldBe "100%% off"    // so a literal %% needs %%%
    }

    "an unguarded backend reference to a missing fragment throws, and a guarded one takes its default" {
        // A *literal* missing key like this is now a boot finding too (issue #505), so it would not reach a
        // running node; this pins the runtime behavior that still backstops a **computed** key, which no
        // static check can resolve -- loud rather than a silently wrong string. `?:` opts into degrading.
        val cxt = Startup.mkTestBootCxt("beMiss", "beMissTest")
        val s = service(cxt)
        shouldThrow<KdrException> { s.backendPass(cxt, """x=%{@t("sample.email.gone")}""") }
        s.backendPass(cxt, """x=%{@t("sample.email.gone") ?: "fallback"}""") shouldBe "x=fallback"
    }

    $$"a backend-composed string may carry ${...} onward, in the carrier's context rather than the source's" {
        // The two-pass model working as intended: a surviving `${...}` is for the frontend to finish. What it
        // resolves against is the *element* that carries the string, not the file the text came from -- which
        // is fine for a data substitution the carrier supplies, and is the author's assertion to get right for
        // a `${@t(...)}`. Demonstrated rather than asserted: `sample.email.body` carries `${code}`.
        val cxt = Startup.mkTestBootCxt("beSplice", "beSpliceTest")
        val out = service(cxt).backendPass(cxt, """%{@t("sample.email.body")}""")
        out shouldContain $$"""${code}"""
        // Untouched by the backend pass, so whoever evaluates this next owns resolving it -- and owes it a
        // `code`. Nothing can check that binding statically; it is made at request time.
    }

    "the fragmentDemo endpoint ships a backend-resolved, frontend-pending string" {
        val cxt = Startup.mkTestBootCxt("beDemo", "beDemoTest")
        val resp = TestHttpClient(cxt.instanceConfig).sendJsonGetRequest(TEP.fragmentDemo)
        val text = resp.getValue(EP.results)!!.toJsonMap()[TEP.demoText] as String
        text shouldContain "Your verification code"                 // backend pull, resolved
        text shouldContain $$"""${@t("portal.welcome")}"""           // frontend pull, still pending
        text shouldContain $$"""${demoVar}"""                        // plain substitution, still pending
    }
})
