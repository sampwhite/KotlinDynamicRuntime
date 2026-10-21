package com.dynamicruntime.sample

import com.dynamicruntime.common.content.MarkdownFragmentService
import com.dynamicruntime.common.context.EVSRC
import com.dynamicruntime.common.context.KdrCxt
import com.dynamicruntime.common.flavor.FLV
import com.dynamicruntime.common.gedra.ClientService
import com.dynamicruntime.common.gedra.GEP
import com.dynamicruntime.common.gedra.workflow.WVF
import com.dynamicruntime.common.home.HFRAG
import com.dynamicruntime.common.http.request.TestHttpClient
import com.dynamicruntime.common.user.AFRAG
import com.dynamicruntime.common.user.TestUser
import com.dynamicruntime.kdn.Startup
import com.dynamicruntime.kdn.confdemo.ConfDemo
import com.dynamicruntime.sample.gedra.SF
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import java.nio.file.Files

/**
 * The `reference` deployment flavor (issue #1221): what a reference deployment changes, from the three components
 * that contribute to it -- core's home page and sign-in copy, the sample's note and its clients, and kdn's conference
 * demo, provisioned at boot with a walkthrough link on each workflow page; and the site root opening on the web
 * application -- and, without it, none of that.
 *
 * Booted in `unit`, where the sample does not load unless told to, against an empty flavor directory so a workspace's
 * own copy cannot reach the test. Private instances: they differ in flavor and in `KDR_LOAD_SAMPLE`.
 */
class ReferenceFlavorTest : StringSpec({

    val noCopy = Files.createTempDirectory("referenceNoCopy1221").toFile()

    fun boot(name: String, overlay: Map<String, Any?>): KdrCxt = Startup.mkTestBootCxt(
        name, name, mapOf(FLV.flavorDirEnvVar.name to noCopy.path) + overlay,
        additionalComponents = listOf(SampleComponent()),
    )

    fun copy(cxt: KdrCxt, fileId: String, ns: String): Map<String, String> =
        MarkdownFragmentService.get(cxt).effectiveFragmentsFor(cxt, fileId, null)!!.content[ns].orEmpty()

    val reference = boot("referenceOn1221", mapOf(FLV.deployFlavorsEnvVar.name to FLV.reference))
    val plain = boot("referenceOff1221", mapOf("KDR_LOAD_SAMPLE" to "true"))

    "core says on the home page what a reference deployment is, and on the sign-in page that an account is temporary" {
        copy(reference, HFRAG.home, "home")["title"] shouldBe "A reference deployment"
        copy(reference, AFRAG.auth, "login")["intro"].orEmpty() shouldContain "your account included"
        copy(plain, HFRAG.home, "home")["title"] shouldBe "Welcome"
        copy(plain, AFRAG.auth, "login")["intro"] shouldBe ""
    }

    "core says on the profile page that the bar switches between a person's users too (issue #1330)" {
        copy(reference, AFRAG.profile, "users")["helpExtra"].orEmpty() shouldContain "bar at the top"
        copy(plain, AFRAG.profile, "users")["helpExtra"] shouldBe ""
        // The help pulls it, so a reference deployment's help ends with it and anyone else's with nothing.
        copy(plain, AFRAG.profile, "users")["help"].orEmpty() shouldContain $$"${@t(\"users.helpExtra\")}"
    }

    "the sample loads, by the flavor's default, and says what its clients demonstrate" {
        reference.instanceConfig.resolveEnvVar(SampleComponent.loadSampleEnvVar).source shouldBe EVSRC.flavor
        MarkdownFragmentService.get(reference).effectiveFragmentsFor(reference, SF.content, null).shouldNotBeNull()
        copy(reference, HFRAG.home, "home")["note"].orEmpty() shouldContain "Acme"
        copy(plain, HFRAG.home, "home")["note"] shouldBe ""
    }

    "the conference demo is provisioned at boot, and each workflow page links to its walkthrough" {
        ClientService.get(reference).present(ConfDemo.client).shouldNotBeNull()
        val speaker = TestUser.create(reference, "speaker@${ConfDemo.client}.example", userClient = ConfDemo.client)
        speaker.getData(GEP.workflowView)[WVF.help].toString() shouldContain "(/docs/demo-client/create-talk.md)"
        // Without the flavor the demo is offered but not provisioned, and carries no help.
        ClientService.get(plain).present(ConfDemo.client).shouldBeNull()
        MarkdownFragmentService.get(plain).resolveFragment(plain, WVF.helpFile, ConfDemo.client, ConfDemo.createTalkWorkflow)
            .orEmpty() shouldBe ""
    }

    "the site root opens on the web application, not the endpoint portal (issue #1268)" {
        fun landing(cxt: KdrCxt) = TestHttpClient(cxt.instanceConfig).sendGetRequestRaw("/").rptResponseHeaders["location"]
        landing(reference) shouldBe mutableListOf("/wa")
        landing(plain) shouldBe mutableListOf("/cp")
    }

    "an explicit KDR_LOAD_SAMPLE beats the flavor's default" {
        val withoutSample = boot("referenceNoSample1221", mapOf(FLV.deployFlavorsEnvVar.name to FLV.reference, "KDR_LOAD_SAMPLE" to "false"))
        MarkdownFragmentService.get(withoutSample).effectiveFragmentsFor(withoutSample, SF.content, null).shouldBeNull()
        // The sample's note goes with it; core's copy stays.
        copy(withoutSample, HFRAG.home, "home")["note"] shouldBe ""
        copy(withoutSample, HFRAG.home, "home")["title"] shouldBe "A reference deployment"
    }
})
