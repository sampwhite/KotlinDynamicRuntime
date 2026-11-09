package com.dynamicruntime.kdn

import com.dynamicruntime.common.content.FRAG
import com.dynamicruntime.common.content.MarkdownFragmentService
import com.dynamicruntime.common.endpoint.EP
import com.dynamicruntime.common.exception.KdrException
import com.dynamicruntime.common.http.request.TestHttpClient
import com.dynamicruntime.common.schema.LAYSTR
import com.dynamicruntime.common.schema.SL
import com.dynamicruntime.common.schema.parseDeliveredLayouts
import com.dynamicruntime.common.schema.resolveDeliveredLayouts
import com.dynamicruntime.common.test.TEP
import com.dynamicruntime.common.util.toJsonMap
import com.dynamicruntime.common.util.toJsonMapOrEmpty
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.core.spec.style.stringSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain

/**
 * The backend `@t` pass (Phase 4 of issue #505): a `%{@t("fileId.namespace.key")}` resolved on the backend,
 * cross-file across the whole registry, before content ships -- while `${...}` is left for the frontend.
 *
 * The `sample` fragment file is registered on an ordinary boot, so `sample.email.subject` and
 * `sample.portal.welcome` are real three-part addresses to resolve against.
 *
 * Also, since issue #1739, the tests that were `LayoutStringsDeliveryTest`, each kept whole below.
 */
class BackendFragmentPassTest : StringSpec({
    include(backendFragmentPassTests)
    include(layoutStringsDeliveryTests)
})

/** This file's own tests, as the class describes them. */
private val backendFragmentPassTests = stringSpec {

    fun service(cxt: com.dynamicruntime.common.context.KdrCxt) = MarkdownFragmentService.get(cxt)

    "the backend resolver resolves a three-part key across the registry" {
        val cxt = TestInstances.default("beResolve")
        val r = service(cxt).backendResolver(cxt)
        r.resolve("${FRAG.sample}.email.subject") shouldBe "Your verification code"
        r.resolve("${FRAG.sample}.portal.welcome") shouldBe "Welcome to the portal."
    }

    "the backend resolver names nothing for a bad or non-three-part key" {
        val cxt = TestInstances.default("beResolveBad")
        val r = service(cxt).backendResolver(cxt)
        r.resolve("email.subject").shouldBeNull()             // two parts -- no file named
        r.resolve("nofile.email.subject").shouldBeNull()      // unknown file
        r.resolve("${FRAG.sample}.email.gone").shouldBeNull() // unknown key
        r.resolve("${FRAG.sample}.gone.subject").shouldBeNull() // unknown namespace
    }

    $$"the backend pass resolves %{...} and leaves ${...} for the frontend" {
        val cxt = TestInstances.default("bePass")
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
        val cxt = TestInstances.default("bePct")
        val s = service(cxt)
        s.backendPass(cxt, "100% off") shouldBe "100% off"       // a lone % is literal
        s.backendPass(cxt, "100%% off") shouldBe "100% off"      // doubled escapes to one
        s.backendPass(cxt, "100%%% off") shouldBe "100%% off"    // so a literal %% needs %%%
    }

    "an unguarded backend reference to a missing fragment throws, and a guarded one takes its default" {
        // A *literal* missing key like this is now a boot finding too (issue #505), so it would not reach a
        // running node; this pins the runtime behavior that still backstops a **computed** key, which no
        // static check can resolve -- loud rather than a silently wrong string. `?:` opts into degrading.
        val cxt = TestInstances.default("beMiss")
        val s = service(cxt)
        shouldThrow<KdrException> { s.backendPass(cxt, """x=%{@t("sample.email.gone")}""") }
        s.backendPass(cxt, """x=%{@t("sample.email.gone") ?: "fallback"}""") shouldBe "x=fallback"
    }

    $$"a backend-composed string may carry ${...} onward, in the carrier's context rather than the source's" {
        // The two-pass model working as intended: a surviving `${...}` is for the frontend to finish. What it
        // resolves against is the *element* that carries the string, not the file the text came from -- which
        // is fine for a data substitution the carrier supplies, and is the author's assertion to get right for
        // a `${@t(...)}`. Demonstrated rather than asserted: `sample.email.body` carries `${code}`.
        val cxt = TestInstances.default("beSplice")
        val out = service(cxt).backendPass(cxt, """%{@t("sample.email.body")}""")
        out shouldContain $$"""${code}"""
        // Untouched by the backend pass, so whoever evaluates this next owns resolving it -- and owes it a
        // `code`. Nothing can check that binding statically; it is made at request time.
    }

    "the fragmentDemo endpoint ships a backend-resolved, frontend-pending string" {
        val cxt = TestInstances.default("beDemo")
        val resp = TestHttpClient(cxt.instanceConfig).alwaysWithTestAccess().sendJsonGetRequest(TEP.fragmentDemo)
        val text = resp.getValue(EP.results)!!.toJsonMap()[TEP.demoText] as String
        text shouldContain "Your verification code"                 // backend pull, resolved
        text shouldContain $$"""${@t("portal.welcome")}"""           // frontend pull, still pending
        text shouldContain $$"""${demoVar}"""                        // plain substitution, still pending
    }
}

/**
 * A layout's form-level strings (issue #641) are copy like its heading and field text, so a backend `%{@t(...)}` in
 * one is resolved at delivery rather than shipped as written and rendered as literal text (issue #814). And what is
 * delivered parses back on the page, a heading-only layout included.
 */
private val layoutStringsDeliveryTests = stringSpec {

    val cxt = TestInstances.default("layoutStrings")

    fun delivered(strings: Map<String, String>): Map<String, Any?> {
        val block = mapOf(
            SL.fragmentFileId to "mail",
            SL.schemaFields to listOf(mapOf(SL.field to "a")),
            SL.strings to strings,
        )
        return resolveDeliveredLayouts(cxt, mapOf("t.Form" to block))["t.Form"].toJsonMapOrEmpty()[SL.strings]
            .toJsonMapOrEmpty()
    }

    "a backend pull in a form-level string is resolved at delivery, and plain copy is left alone" {
        val strings = delivered(
            mapOf(
                // Two-part, against the block's fragmentFileId (a backend file of the core).
                LAYSTR.formErrorHint to """%{@t("common.footer")}""",
                LAYSTR.formErrorSummary to $$"Plain, with a frontend ${field} left for the page.",
            ),
        )
        strings[LAYSTR.formErrorHint] shouldBe
            "This message was sent automatically. If you were not expecting it, you can ignore it."
        strings[LAYSTR.formErrorSummary] shouldBe $$"Plain, with a frontend ${field} left for the page."
    }

    "a heading-only layout is delivered with no field list, so the page's parse takes it" {
        // A client's alteration giving a type only a heading (issue #1070): written with an empty field list, the
        // page's strict parse refused it and the forms page could not load.
        val delivered = resolveDeliveredLayouts(cxt, mapOf("t.Form" to mapOf(SL.label to "Site Visit Data")))
        delivered["t.Form"].toJsonMapOrEmpty().containsKey(SL.schemaFields) shouldBe false
        parseDeliveredLayouts(delivered).getValue("t.Form").label shouldBe "Site Visit Data"
    }

    // Only what no boot check can see (a computed key) reaches here unresolved; it degrades to the copy as written.
    "a pull that cannot resolve at delivery is delivered as written" {
        val computed = """%{@t(chosen)}"""
        delivered(mapOf(LAYSTR.formErrorHint to computed))[LAYSTR.formErrorHint] shouldBe computed
    }
}
