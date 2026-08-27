package com.dynamicruntime.sample.content

import com.dynamicruntime.common.content.FCHK
import com.dynamicruntime.common.content.MarkdownFragmentService
import com.dynamicruntime.common.content.UIC
import com.dynamicruntime.common.context.KdrCxt
import com.dynamicruntime.common.exception.EXC
import com.dynamicruntime.common.home.HFRAG
import com.dynamicruntime.common.http.request.ROLE
import com.dynamicruntime.common.http.request.TestHttpClient
import com.dynamicruntime.common.startup.InstanceRegistry
import com.dynamicruntime.common.user.TestUser
import com.dynamicruntime.common.util.jsonMap
import com.dynamicruntime.common.util.toJsonListOfMaps
import com.dynamicruntime.common.util.toJsonListOfStrings
import com.dynamicruntime.common.util.toOptStr
import com.dynamicruntime.kdn.Startup
import com.dynamicruntime.sample.SampleComponent
import com.dynamicruntime.sample.gedra.SC
import com.dynamicruntime.sample.gedra.SF
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe

/**
 * Fragment layering on a real node (issue #456): a base file, a `_overlay.md` beside it, an overlay written
 * in code, and acme's own -- four contributors to one file, and what a reader of each key gets.
 *
 * Boot-level rather than unit, because what is under test is the **wiring**: that a component's declaration
 * and a client's Gedra config both reach the same registry, that the merge happens per client, and that the
 * content server hands out the right document. The merge rules themselves are covered by `FragmentLayerTest`,
 * where they are maps in and a map out.
 */
class FragmentOverlayTest : StringSpec({

    InstanceRegistry.register(listOf(SampleComponent()))
    val cxt = Startup.mkTestBootCxt("fragOverlay", "fragOverlayTest", mapOf("KDR_LOAD_SAMPLE" to "true"))

    val service = MarkdownFragmentService.get(cxt)
    val acmeCxt = cxt.mkSubContext("acme", SC.acme)
    val globexCxt = cxt.mkSubContext("globex", SC.globex)

    fun copy(scope: KdrCxt, namespace: String, key: String): String? =
        service.effectiveFragments(scope, SF.content)?.content?.get(namespace)?.get(key)

    "every kind of layer contributes, in the documented order" {
        // The base, untouched by anything.
        copy(cxt, SF.welcome, SF.title) shouldBe "Welcome"
        // The `_overlay.md` beside the base wins over it...
        copy(cxt, SF.welcome, SF.support) shouldBe "Contact the sample desk if you need help."
        // ...and so does an overlay written in code, which is the same kind of layer by a different route.
        copy(cxt, SF.footer, SF.copyright) shouldBe "Copyright the sample deployment."
    }

    "a client's overlay wins over every component layer" {
        copy(acmeCxt, SF.welcome, SF.title) shouldBe "Welcome to Acme"
        // Over the component's *overlay*, not merely over the base -- a client is the most specific thing
        // with an opinion, so it gets the last word.
        copy(acmeCxt, SF.welcome, SF.support) shouldBe "Acme site services will help."
    }

    "a client's overlay changes only what it names" {
        // The keys acme said nothing about keep whatever the layers underneath say, which is what lets an
        // overlay stay a handful of lines while the base grows.
        copy(acmeCxt, SF.welcome, SF.intro) shouldBe copy(cxt, SF.welcome, SF.intro)
        copy(acmeCxt, SF.footer, SF.copyright) shouldBe "Copyright the sample deployment."
    }

    "another client sees none of it" {
        copy(globexCxt, SF.welcome, SF.title) shouldBe "Welcome"
        copy(globexCxt, SF.welcome, SF.support) shouldBe "Contact the sample desk if you need help."
    }

    "a client reading different copy gets a different URL" {
        // The cache correctness the whole change rests on: the response is cached `public` and `immutable`
        // with the URL as the entire key, so two clients reading different copy must never share one.
        val shared = MarkdownFragmentService.fragmentBuildId(cxt, SF.content).shouldNotBeNull()
        val acme = MarkdownFragmentService.fragmentBuildId(acmeCxt, SF.content).shouldNotBeNull()
        acme shouldNotBe shared
        // ...and a client that changes nothing shares the URL, so it also shares the cache entry.
        MarkdownFragmentService.fragmentBuildId(globexCxt, SF.content) shouldBe shared
    }

    "a client renames the shell's own wordmark, and no frontend code knows it exists" {
        // The overlay that changes something a person actually sees: `home.brand` is what the app bar and the
        // home hero render. Asserted on the real `home` file rather than the sample fixture, because a feature
        // demonstrated only on content invented for the demonstration has not been demonstrated.
        val shellCopy = { scope: KdrCxt ->
            service.effectiveFragments(scope, HFRAG.home)?.content?.get("home")?.get("brand")
        }
        shellCopy(cxt) shouldBe "KDR"
        shellCopy(acmeCxt) shouldBe "ACME KDR"
        shellCopy(globexCxt) shouldBe "KDR"
    }

    "the shell's fragment ref differs for a client, so its copy is fetched separately" {
        // What makes the rename actually reach the browser: the UI-config hands acme a different
        // `fileId:buildId`, so its shell fetches its own document rather than a cached shared one.
        MarkdownFragmentService.fragmentBuildId(acmeCxt, HFRAG.home) shouldNotBe
            MarkdownFragmentService.fragmentBuildId(cxt, HFRAG.home)
    }

    "the shell UI-config hands an acme user acme's ref, which is how the rename reaches a browser" {
        // Through the endpoint the frontend actually calls, not the service behind it. The chain that matters
        // is: acme user -> /home/ui/config -> a fileId:buildId of their own -> a fetch of their own document.
        // Everything above this asserts the last link; this asserts the first two.
        val acmeUser = TestUser.create(cxt, "shell@acme.test", userClient = SC.acme)
        val plainUser = TestUser.create(cxt, "shell@example.com")

        fun shellRef(user: TestUser): Map<String, Any?> =
            user.getData("/home/ui/config")[UIC.fragments].toJsonListOfMaps()
                .single { it[UIC.fileId].toOptStr() == HFRAG.home }

        val acmeRef = shellRef(acmeUser)[UIC.buildId].toOptStr()
        val plainRef = shellRef(plainUser)[UIC.buildId].toOptStr()
        acmeRef shouldNotBe plainRef
        acmeRef shouldBe MarkdownFragmentService.fragmentBuildId(acmeCxt, HFRAG.home)
    }

    // --- through the content server -----------------------------------------------------------------------

    "the served document is the one the build id names" {
        val http = TestHttpClient(cxt.instanceConfig)
        val acmeBuildId = MarkdownFragmentService.fragmentBuildId(acmeCxt, SF.content).shouldNotBeNull()
        val handler = http.sendGetRequestRaw("/st/myapp/md/${SF.content}:$acmeBuildId")

        handler.rptStatusCode shouldBe EXC.ok
        handler.rptResponseHeaders["cache-control"] shouldBe listOf(MarkdownFragmentService.cacheControl)
        val map = handler.rptResponseData.shouldNotBeNull().jsonMap().shouldNotBeNull()
        @Suppress("UNCHECKED_CAST")
        val welcome = map[SF.welcome] as Map<String, Any?>
        // Acme's document, served for acme's build id. The URL names a document rather than "whatever this
        // file currently is for whoever is asking", which is what stops a shared cache handing one client's
        // copy to another.
        welcome[SF.title] shouldBe "Welcome to Acme"
    }

    "a build id this node does not have is refused, and the refusal is not cached" {
        val http = TestHttpClient(cxt.instanceConfig)
        val handler = http.sendGetRequestRaw("/st/myapp/md/${SF.content}:deadbeef")
        handler.rptStatusCode shouldBe EXC.notFound
        // Explicitly uncached: a stale URL from a redeploy lands here, and a cached 404 would go on answering
        // for a file that exists.
        handler.rptResponseHeaders["cache-control"] shouldBe listOf("no-store")
    }

    // --- the operator check ------------------------------------------------------------------------------

    "the check reports a row per client, so a client's copy is checked too" {
        val opal = TestUser.create(cxt, "frag-ops@example.com", level = ROLE.operator)
        val rows = opal.getItems("/operator/fragments/check", mapOf(FCHK.fileId to SF.content))
        val clients = rows.map { it[FCHK.client].toOptStr() }
        // The shared variant and acme's. Without the per-client row, a client's overlay would be the only
        // copy on the node that nothing ever syntax-checked.
        clients shouldBe listOf(null, SC.acme)
        for (row in rows) {
            row[FCHK.found] shouldBe true
            row[FCHK.orphans].toJsonListOfStrings().shouldBeEmpty()
        }
    }
})
