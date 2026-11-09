package com.dynamicruntime.sample

import com.dynamicruntime.common.CommonComponent
import com.dynamicruntime.common.content.MarkdownFragmentService
import com.dynamicruntime.common.context.ENV
import com.dynamicruntime.common.context.EVSRC
import com.dynamicruntime.common.context.KdrCxt
import com.dynamicruntime.common.context.KdrInstanceConfig
import com.dynamicruntime.common.flavor.DeployFlavors
import com.dynamicruntime.common.flavor.FLV
import com.dynamicruntime.common.gedra.ClientService
import com.dynamicruntime.common.gedra.GEP
import com.dynamicruntime.common.gedra.workflow.WVF
import com.dynamicruntime.common.home.HFRAG
import com.dynamicruntime.common.http.request.TestHttpClient
import com.dynamicruntime.common.user.AFRAG
import com.dynamicruntime.common.user.TestUser
import com.dynamicruntime.kdn.KdnComponent
import com.dynamicruntime.kdn.Startup
import com.dynamicruntime.kdn.confdemo.ConfDemo
import com.dynamicruntime.sample.gedra.SF
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldContain
import java.io.File
import java.nio.file.Files

/**
 * Deployment flavors with the sample loaded (issue #1736 gathered these): every declared flavor boots (issue #1219);
 * the `samples` flavor loads the sample and a deployment rewords its copy from a flavor directory (issue #1220); and
 * the `reference` flavor changes copy, provisions the conference demo, and opens the site on the web application
 * (issue #1221).
 *
 * What is checked is the mechanism -- which copy wins, what loads, what is provisioned -- never a flavor's wording, which
 * is the copy's to change. Booted in `unit`, where the sample does not load unless told to, against empty flavor
 * directories so a workspace's own copy cannot reach the test. Private instances: they differ in flavor, flavor
 * directory, and `KDR_LOAD_SAMPLE`.
 */
class FlavorsTest : StringSpec({
    val noCopy = Files.createTempDirectory("flavorsNoCopy1736").toFile()

    fun boot(name: String, overlay: Map<String, Any?>, flavorDir: File = noCopy): KdrCxt = Startup.mkTestBootCxt(
        name, name, mapOf(FLV.flavorDirEnvVar.name to flavorDir.path) + overlay,
        additionalComponents = listOf(SampleComponent()),
    )

    fun copy(cxt: KdrCxt, fileId: String, ns: String): Map<String, String> =
        MarkdownFragmentService.get(cxt).effectiveFragmentsFor(cxt, fileId, null)!!.content[ns].orEmpty()

    fun sampleLoaded(cxt: KdrCxt): Boolean =
        MarkdownFragmentService.get(cxt).effectiveFragmentsFor(cxt, SF.content, null)?.found == true

    val reference = boot("flavorsReference", mapOf(FLV.deployFlavorsEnvVar.name to FLV.reference))
    val plain = boot("flavorsPlain", mapOf(SampleComponent.loadSampleEnvVar.name to "true"))

    "every declared flavor boots on its own, so a flavor's overlay naming a dropped key fails here" {
        // One boot per flavor, enumerated from the declarations: flavors do not interact, so each is checked alone, and
        // a flavor added later is covered with no change here.
        val probe = KdrCxt.mkSimpleCxt("flavorEnum", KdrInstanceConfig("flavorEnum", ENV.unit, ENV.liveSource))
        val declared = listOf(CommonComponent(), KdnComponent(), SampleComponent()).flatMap { it.flavorDefs(probe) }
        for (def in declared) {
            val cxt = boot("eachFlavor_${def.name}", mapOf(FLV.deployFlavorsEnvVar.name to def.name, SampleComponent.loadSampleEnvVar.name to "true"))
            DeployFlavors.get(cxt).active shouldBe listOf(def.name)
        }
    }

    "a flavor's copy replaces core's where it sets a key, and leaves core's where it does not" {
        copy(reference, HFRAG.home, "home")["title"] shouldNotBe copy(plain, HFRAG.home, "home")["title"]
        copy(reference, HFRAG.home, HFRAG.siteNs)[HFRAG.title] shouldNotBe copy(plain, HFRAG.home, HFRAG.siteNs)[HFRAG.title]
        // A key core leaves empty and the reference flavor fills, and one the flavor empties.
        copy(plain, AFRAG.auth, "login")["intro"] shouldBe ""
        copy(reference, AFRAG.auth, "login")["intro"].orEmpty() shouldNotBe ""
        copy(plain, AFRAG.auth, "register")["isEntityLabel"].orEmpty() shouldNotBe ""
        copy(reference, AFRAG.auth, "register")["isEntityLabel"] shouldBe ""
        // A key the flavor does not set is core's either way.
        copy(reference, AFRAG.auth, "register")["isEntityHelp"] shouldBe copy(plain, AFRAG.auth, "register")["isEntityHelp"]
    }

    "the reference flavor loads the sample by its default, and an explicit KDR_LOAD_SAMPLE beats that" {
        reference.instanceConfig.resolveEnvVar(SampleComponent.loadSampleEnvVar).source shouldBe EVSRC.flavor
        sampleLoaded(reference) shouldBe true
        val withoutSample = boot(
            "flavorsReferenceNoSample",
            mapOf(FLV.deployFlavorsEnvVar.name to FLV.reference, SampleComponent.loadSampleEnvVar.name to "false"),
        )
        sampleLoaded(withoutSample) shouldBe false
        // The sample's own copy goes with it; core's flavor copy stays.
        copy(withoutSample, HFRAG.home, "publicHome")["demosNote"] shouldBe ""
        copy(withoutSample, HFRAG.home, "home")["title"] shouldBe copy(reference, HFRAG.home, "home")["title"]
    }

    "the reference flavor provisions the conference demo at boot, and each workflow page links to its walkthrough" {
        ClientService.get(reference).present(ConfDemo.client).shouldNotBeNull()
        val speaker = TestUser.create(reference, "speaker@${ConfDemo.client}.example", userClient = ConfDemo.client)
        speaker.getData(GEP.workflowView)[WVF.help].toString() shouldContain "(/docs/demo-client/create-talk.md)"
        // Without the flavor the demo is offered but not provisioned, and carries no help.
        ClientService.get(plain).present(ConfDemo.client).shouldBeNull()
        MarkdownFragmentService.get(plain).resolveFragment(plain, WVF.helpFile, ConfDemo.client, ConfDemo.createTalkWorkflow)
            .orEmpty() shouldBe ""
    }

    "the reference flavor opens the site root on the web application, not the endpoint portal (issue #1268)" {
        fun landing(cxt: KdrCxt) = TestHttpClient(cxt.instanceConfig).sendGetRequestRaw("/").rptResponseHeaders["location"]
        landing(reference) shouldBe mutableListOf("/wa")
        landing(plain) shouldBe mutableListOf("/cp")
    }

    "the samples flavor loads the sample, and a deployment rewords its copy from the flavor directory, key by key" {
        val samples = mapOf(FLV.deployFlavorsEnvVar.name to SampleComponent.samplesFlavor)
        val asShipped = boot("flavorsSamples", samples)
        asShipped.instanceConfig.resolveEnvVar(SampleComponent.loadSampleEnvVar).source shouldBe EVSRC.flavor
        sampleLoaded(asShipped) shouldBe true
        val shipped = copy(asShipped, HFRAG.home, "home")
        shipped["title"] shouldNotBe copy(plain, HFRAG.home, "home")["title"]

        val dir = Files.createTempDirectory("flavorsSamplesCopy1736").toFile()
        File(dir, "samples.md").writeText("# !home\n# @home\n# +intro Our own words about the samples.\n")
        val reworded = copy(boot("flavorsSamplesCopy", samples, dir), HFRAG.home, "home")
        reworded["intro"] shouldBe "Our own words about the samples."
        // A key the deployment did not reword is the flavor's.
        reworded["title"] shouldBe shipped["title"]
    }
})
