package com.dynamicruntime.kdn

import com.dynamicruntime.common.content.FCHK
import com.dynamicruntime.common.content.FRAG
import com.dynamicruntime.common.content.FragmentAudience
import com.dynamicruntime.common.content.FragmentSource
import com.dynamicruntime.common.content.MarkdownFragmentService
import com.dynamicruntime.common.content.fragmentInline
import com.dynamicruntime.common.content.fragmentRefs
import com.dynamicruntime.common.context.KdrCxt
import com.dynamicruntime.common.exception.EXC
import com.dynamicruntime.common.exception.KdrException
import com.dynamicruntime.common.http.request.TestHttpClient
import com.dynamicruntime.common.util.ScriptError
import com.dynamicruntime.common.util.jsonMap
import io.kotest.assertions.throwables.shouldNotThrowAny
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldBeEmpty
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
            namespace("email") { key("subject", """%{@t("other.ns.key")}""") }
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

    "a malformed *frontend* block in a backend file is caught too" {
        // The half a single backend-prefix parse would miss: `${` is plain text to the `%` parser, so without
        // the second pass this file passes a strict boot and fails later at frontend render, an unbounded
        // distance from the keyboard. A backend file's `${...}` is carried onward, not exempt from syntax.
        val cxt = Startup.mkTestBootCxt("audCarried", "audCarriedTest")
        val back = fragmentInline(
            "audBack", origin = "test", isOverlay = false, audience = FragmentAudience.backend,
        ) {
            namespace("email") { key("subject", $$"""Hello ${unterminated""") }
        }
        cxt.instanceConfig.put(FRAG.registryKey, listOf(back))
        service(cxt).checkFragments(cxt).flatMap { it.issues }.isNotEmpty() shouldBe true
    }

    // --- the audience conflict ---------------------------------------------------------------------------

    /**
     * Two components declare one fileId and disagree. The merge resolves it safely -- backend wins, so nothing
     * private is served -- but that safe resolution takes a **delivered** file private, and every UI-config
     * naming it then fails somewhere else entirely. So the conflict is a boot finding, reported at its cause.
     */
    "bases disagreeing about audience is a boot finding, and a strict boot refuses" {
        val cxt = Startup.mkTestBootCxt("audConflict", "audConflictTest")
        val asFrontend = fragmentInline("audBoth", origin = "componentA", isOverlay = false) {
            namespace("welcome") { key("title", "Public copy") }
        }
        val asBackend = fragmentInline(
            "audBoth", origin = "componentB", isOverlay = false, audience = FragmentAudience.backend,
        ) {
            namespace("email") { key("subject", "Private copy") }
        }
        cxt.instanceConfig.put(FRAG.registryKey, listOf(asFrontend, asBackend))

        val shared = service(cxt).checkFragments(cxt).single { it.client == null }
        shared.audienceConflict shouldBe true
        shared.audience shouldBe FragmentAudience.backend

        // mkTestBootCxt forces the unit environment, where the check is strict.
        val ex = shouldThrow<KdrException> { service(cxt).checkFragmentsAtStartup(cxt) }
        (ex.message ?: "") shouldContain "audBoth"
        (ex.message ?: "") shouldContain "no longer delivered"
    }

    "a conflict a client alone has is reported on that client's row" {
        // The shared content is consistent, and only this client's own base disagrees -- so the file goes
        // private *for them alone*, and nothing on the shared row can say so. Suppressing the flag by
        // `client == null` would swallow exactly that.
        //
        // Built with FragmentSource directly because no builder can currently produce a client-scoped base:
        // `GedraConfigBuilder.fragmentOverlay` is the only thing that sets a client and it cannot set
        // `isOverlay`. `mergeFragmentLayers` admits one regardless (it filters by client before splitting bases
        // from overlays), so this guards the day such a builder is added.
        val cxt = Startup.mkTestBootCxt("audClientConf", "audClientConfTest")
        val sharedBase = FragmentSource("audCli", isOverlay = false, client = null, origin = "componentA") {
            mapOf("welcome" to mapOf("title" to "Public copy"))
        }
        val acmeBase = FragmentSource(
            "audCli", isOverlay = false, client = "acme", origin = "acmeConfig", audience = FragmentAudience.backend,
        ) {
            mapOf("email" to mapOf("subject" to "Acme private"))
        }
        cxt.instanceConfig.put(FRAG.registryKey, listOf(sharedBase, acmeBase))

        val rows = service(cxt).checkFragments(cxt)
        // The shared content has one consistent base, so it is clean and frontend...
        val shared = rows.single { it.client == null }
        shared.audienceConflict shouldBe false
        shared.audience shouldBe FragmentAudience.frontend
        // ...while acme's variant is in conflict, and says so on its own row.
        val acme = rows.single { it.client == "acme" }
        acme.audienceConflict shouldBe true
        acme.audience shouldBe FragmentAudience.backend

        val ex = shouldThrow<KdrException> { service(cxt).checkFragmentsAtStartup(cxt) }
        ex.message.orEmpty() shouldContain "client 'acme'"
    }

    "a conflict the shared content already has is not repeated per client" {
        // The other half: the suppression still does its original job. The conflict is in the shared bases, so
        // every client inherits it -- reporting it per row would say one thing N times, and a boot refusal
        // listing it three times reads as three broken files.
        val cxt = Startup.mkTestBootCxt("audDupConf", "audDupConfTest")
        val frontBase = FragmentSource("audCli", isOverlay = false, client = null, origin = "componentA") {
            mapOf("welcome" to mapOf("title" to "Public"))
        }
        val backBase = FragmentSource(
            "audCli", isOverlay = false, client = null, origin = "componentB", audience = FragmentAudience.backend,
        ) {
            mapOf("email" to mapOf("subject" to "Private"))
        }
        val acmeOverlay = FragmentSource("audCli", isOverlay = true, client = "acme", origin = "acmeConfig") {
            mapOf("welcome" to mapOf("title" to "Acme"))
        }
        cxt.instanceConfig.put(FRAG.registryKey, listOf(frontBase, backBase, acmeOverlay))

        val rows = service(cxt).checkFragments(cxt)
        rows.single { it.client == null }.audienceConflict shouldBe true
        rows.single { it.client == "acme" }.audienceConflict shouldBe false
        // Said once, not once per variant.
        rows.count { it.audienceConflict } shouldBe 1
    }

    // --- the finding count ------------------------------------------------------------------------------

    "issueCount counts every kind of finding, not just template issues" {
        // The 'is this file clean?' column. A file a strict boot refuses on must never report 0 here -- which
        // is exactly what it did while the count was `issues.size`.
        val cxt = Startup.mkTestBootCxt("audCount", "audCountTest")
        val front = fragmentInline("audFront", origin = "test", isOverlay = false) {
            namespace("welcome") { key("title", """Hi %{@t("other.ns.key")}""") }
        }
        cxt.instanceConfig.put(FRAG.registryKey, listOf(front))

        val row = service(cxt).checkFragments(cxt).single { it.client == null }
        row.issues.shouldBeEmpty()          // no *template* problem...
        row.audienceIssues.size shouldBe 1  // ...but an audience violation...
        row.findingCount shouldBe 1         // ...which the count must include.
        row.toJsonMap()[FCHK.issueCount] shouldBe 1
    }

    "a note is not counted as a finding" {
        val cxt = Startup.mkTestBootCxt("audCount2", "audCount2Test")
        val back = fragmentInline("audBack", origin = "test", isOverlay = false, audience = FragmentAudience.backend) {
            namespace("email") { key("subject", $$"""Hello ${@t("greeting.hello")}""") }
        }
        cxt.instanceConfig.put(FRAG.registryKey, listOf(back))

        val row = service(cxt).checkFragments(cxt).single { it.client == null }
        row.notes.size shouldBe 1
        row.findingCount shouldBe 0   // nothing here refuses a boot
    }

    "a UI-config naming a backend file fails saying why, not 'not available'" {
        // If the conflict above is ignored (production only warns), this is what the reader gets at the far
        // end. It must name the audience: the file is loaded and present, so "not available" sends them
        // hunting for a missing resource that is sitting right there.
        val cxt = Startup.mkTestBootCxt("audRefs", "audRefsTest")
        register(cxt)
        val ex = shouldThrow<KdrException> { fragmentRefs(cxt, "audBack") }
        ex.fullMessage() shouldContain "backend (private)"
        // A genuinely absent file still reports as absent -- the two failures stay distinguishable.
        shouldThrow<KdrException> { fragmentRefs(cxt, "audNoSuchFile") }
            .fullMessage() shouldContain "is not available"
    }

    // --- check 1: a frontend file must contain no %{...} backend block ------------------------------------

    "a frontend file carrying a backend block is a finding, and a strict boot refuses" {
        val cxt = Startup.mkTestBootCxt("chk1", "chk1Test")
        // A %{@t(...)} in a frontend file is the mistake this catches: it is served with no backend pass, so it
        // reaches the browser as the literal text `%{@t(...)}`.
        val front = fragmentInline("audFront", origin = "test", isOverlay = false) {
            namespace("welcome") { key("title", """Welcome %{@t("other.ns.key")}""") }
        }
        cxt.instanceConfig.put(FRAG.registryKey, listOf(front))

        val shared = service(cxt).checkFragments(cxt).single { it.client == null }
        shared.audienceIssues.any { it.contains("welcome.title") && it.contains("backend block") } shouldBe true
        // Not double-reported as a reference problem: the $ pass sees `%{...}` as plain text.
        shared.issues.any { it.code == ScriptError.fragmentNotFound } shouldBe false

        val ex = shouldThrow<KdrException> { service(cxt).checkFragmentsAtStartup(cxt) }
        ex.message.orEmpty() shouldContain "backend block"
    }

    // --- check 3: a backend pull may name only a backend file ---------------------------------------------

    "a backend pull naming a frontend file is a finding" {
        val cxt = Startup.mkTestBootCxt("chk3fe", "chk3feTest")
        val front = fragmentInline("audFront", origin = "test", isOverlay = false) {
            namespace("welcome") { key("title", "Hi") }
        }
        val back = fragmentInline("audBack", origin = "test", isOverlay = false, audience = FragmentAudience.backend) {
            namespace("email") { key("subject", """%{@t("audFront.welcome.title")}""") }
        }
        cxt.instanceConfig.put(FRAG.registryKey, listOf(front, back))
        val issues = service(cxt).checkFragments(cxt).flatMap { it.audienceIssues }
        issues.any { it.contains("audFront") && it.contains("must name a backend file") } shouldBe true
    }

    "a backend pull naming another backend file is clean" {
        val cxt = Startup.mkTestBootCxt("chk3be", "chk3beTest")
        val data = fragmentInline("audData", origin = "test", isOverlay = false, audience = FragmentAudience.backend) {
            namespace("email") { key("subject", "Your code") }
        }
        val back = fragmentInline("audBack", origin = "test", isOverlay = false, audience = FragmentAudience.backend) {
            namespace("email") { key("body", """Subject: %{@t("audData.email.subject")}""") }
        }
        cxt.instanceConfig.put(FRAG.registryKey, listOf(data, back))
        service(cxt).checkFragments(cxt).flatMap { it.audienceIssues } shouldBe emptyList()
    }

    "a backend pull naming an undeclared file is a finding" {
        val cxt = Startup.mkTestBootCxt("chk3none", "chk3noneTest")
        val back = fragmentInline("audBack", origin = "test", isOverlay = false, audience = FragmentAudience.backend) {
            namespace("email") { key("subject", """%{@t("audNope.a.b")}""") }
        }
        cxt.instanceConfig.put(FRAG.registryKey, listOf(back))
        service(cxt).checkFragments(cxt).flatMap { it.audienceIssues }
            .any { it.contains("audNope") && it.contains("not a declared") } shouldBe true
    }

    // --- check 2: a carried frontend pull is a note, not a finding ----------------------------------------

    "a backend file carrying a frontend pull is a note, and does not fail a strict boot" {
        val cxt = Startup.mkTestBootCxt("chk2", "chk2Test")
        // The deliberate case the design revived: a backend template carries a `${@t(...)}` for the frontend to
        // finish against whatever element carries the composed string. Unverifiable here, so it is named, not failed.
        val back = fragmentInline("audBack", origin = "test", isOverlay = false, audience = FragmentAudience.backend) {
            namespace("email") { key("subject", $$"""Hello ${@t("greeting.hello")}""") }
        }
        cxt.instanceConfig.put(FRAG.registryKey, listOf(back))

        val shared = service(cxt).checkFragments(cxt).single { it.client == null }
        shared.notes.any { it.contains("greeting.hello") && it.contains("carries a frontend pull") } shouldBe true
        shared.audienceIssues shouldBe emptyList()
        // A note never refuses a boot, even in strict (unit) mode.
        shouldNotThrowAny { service(cxt).checkFragmentsAtStartup(cxt) }
    }
})
