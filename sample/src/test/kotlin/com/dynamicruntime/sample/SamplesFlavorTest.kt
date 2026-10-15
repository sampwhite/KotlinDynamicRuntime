package com.dynamicruntime.sample

import com.dynamicruntime.common.content.MarkdownFragmentService
import com.dynamicruntime.common.context.EVSRC
import com.dynamicruntime.common.context.KdrCxt
import com.dynamicruntime.common.flavor.FLV
import com.dynamicruntime.common.home.HFRAG
import com.dynamicruntime.kdn.Startup
import com.dynamicruntime.sample.gedra.SF
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import java.io.File
import java.nio.file.Files

/**
 * The `samples` deployment flavor (issue #1220): the sample component's own, and the working example of a deployment
 * rewording a flavor's copy from its flavor directory. Booted in `unit`, where the sample does not load unless told
 * to -- so its loading here is the flavor's doing. Private instances: each differs in the flavor directory it reads.
 */
class SamplesFlavorTest : StringSpec({

    fun boot(name: String, flavorDir: File): KdrCxt = Startup.mkTestBootCxt(
        name, name,
        mapOf(FLV.deployFlavorsEnvVar.name to SampleComponent.samplesFlavor, FLV.flavorDirEnvVar.name to flavorDir.path),
        additionalComponents = listOf(SampleComponent()),
    )

    fun home(cxt: KdrCxt): Map<String, String> =
        MarkdownFragmentService.get(cxt).effectiveFragmentsFor(cxt, HFRAG.home, null)!!.content.getValue("home")

    "the flavor loads the samples and points the home page at them" {
        val cxt = boot("samplesFlavor1220", Files.createTempDirectory("noCopy1220").toFile())
        cxt.instanceConfig.resolveEnvVar(SampleComponent.loadSampleEnvVar).source shouldBe EVSRC.flavor
        // The sample's own fragment file is served only when the sample loaded.
        MarkdownFragmentService.get(cxt).effectiveFragmentsFor(cxt, SF.content, null)!!.found shouldBe true
        home(cxt)["title"] shouldBe "Welcome to the samples"
        home(cxt)["intro"].orEmpty() shouldContain "Acme and Globex"
    }

    "a deployment rewords the flavor's copy from its flavor directory, key by key" {
        val dir = Files.createTempDirectory("samplesCopy1220").toFile()
        File(dir, "samples.md").writeText("# !home\n# @home\n# +intro Our own words about the samples.\n")
        val home = home(boot("samplesFlavorCopy1220", dir))
        home["intro"] shouldBe "Our own words about the samples."
        home["title"] shouldBe "Welcome to the samples"
    }
})
