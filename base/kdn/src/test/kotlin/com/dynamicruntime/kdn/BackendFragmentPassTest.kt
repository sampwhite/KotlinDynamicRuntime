package com.dynamicruntime.kdn

import com.dynamicruntime.common.content.FRAG
import com.dynamicruntime.common.content.MarkdownFragmentService
import com.dynamicruntime.common.endpoint.EP
import com.dynamicruntime.common.http.request.TestHttpClient
import com.dynamicruntime.common.test.TEP
import com.dynamicruntime.common.util.toJsonMap
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

    "the fragmentDemo endpoint ships a backend-resolved, frontend-pending string" {
        val cxt = Startup.mkTestBootCxt("beDemo", "beDemoTest")
        val resp = TestHttpClient(cxt.instanceConfig).sendJsonGetRequest(TEP.fragmentDemo)
        val text = resp.getValue(EP.results)!!.toJsonMap()[TEP.demoText] as String
        text shouldContain "Your verification code"                 // backend pull, resolved
        text shouldContain $$"""${@t("portal.welcome")}"""           // frontend pull, still pending
        text shouldContain $$"""${demoVar}"""                        // plain substitution, still pending
    }
})
