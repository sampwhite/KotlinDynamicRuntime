package com.dynamicruntime.sample.content

import com.dynamicruntime.common.content.ComponentImageService
import com.dynamicruntime.common.content.MarkdownFragmentService
import com.dynamicruntime.common.exception.EXC
import com.dynamicruntime.common.home.HFRAG
import com.dynamicruntime.common.http.request.TestHttpClient
import com.dynamicruntime.kdn.Startup
import com.dynamicruntime.sample.SampleComponent
import com.dynamicruntime.sample.gedra.SC
import com.dynamicruntime.sample.gedra.SF
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain

/**
 * The image the sample ships (issue #1339): read from its resources, served under the static root, and named by acme's
 * home page copy, which a deployment carrying the sample loads with no problem found.
 */
class SampleImageTest : StringSpec({
    val cxt = Startup.mkTestBootCxt("sampleImage", "sampleImageTest", mapOf("KDR_LOAD_SAMPLE" to "true"), additionalComponents = listOf(SampleComponent()))

    "the sample's image is served as an SVG by its name and content" {
        val served = ComponentImageService.get(cxt).servedNames().getValue(SF.siteVisitFlow)
        val handler = TestHttpClient(cxt.instanceConfig).sendGetRequestRaw("/st/myapp/img/$served")
        handler.rptStatusCode shouldBe EXC.ok
        handler.rptResponseMimeType shouldBe "image/svg+xml"
    }

    "acme's home page shows it" {
        val copy = MarkdownFragmentService.get(cxt).effectiveFragmentsFor(cxt, HFRAG.home, SC.acme)
        copy?.content?.get("home")?.get("note").orEmpty() shouldContain "img:${SF.siteVisitFlow}"
    }
})
