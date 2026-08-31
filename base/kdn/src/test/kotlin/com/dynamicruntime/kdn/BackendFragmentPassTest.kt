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

    "the backend pass resolves %{...} and leaves \${...} for the frontend" {
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
        // No boot check catches this yet (its own follow-up), so it is a render-time failure -- loud rather
        // than a silently wrong string. `?:` is how an author opts into degrading instead.
        val cxt = Startup.mkTestBootCxt("beMiss", "beMissTest")
        val s = service(cxt)
        shouldThrow<KdrException> { s.backendPass(cxt, $$"""x=%{@t("sample.email.gone")}""") }
        s.backendPass(cxt, $$"""x=%{@t("sample.email.gone") ?: "fallback"}""") shouldBe "x=fallback"
    }

    "backend-pulled text keeps the caller's later context, which is why a pull should not carry \${...}" {
        // The constraint documented on backendPass: a pulled `${...}` is spliced out unevaluated and is later
        // resolved by the frontend against the *element's* file, not the source's. Demonstrated rather than
        // asserted, because it is the trap an author would otherwise meet at render time.
        val cxt = Startup.mkTestBootCxt("beSplice", "beSpliceTest")
        // `sample.email.body` carries `${code}` / `${minutes}`.
        val out = service(cxt).backendPass(cxt, $$"""%{@t("sample.email.body")}""")
        out shouldContain $$"""${code}"""
        // It survived the backend pass untouched -- so whatever evaluates this string next owns resolving it,
        // in a context that has nothing to do with where the text came from.
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
