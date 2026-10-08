package com.dynamicruntime.kdn

import com.dynamicruntime.common.content.MarkdownFragmentService
import com.dynamicruntime.common.context.ACFG
import com.dynamicruntime.common.context.ENV
import com.dynamicruntime.common.context.ENVGRP
import com.dynamicruntime.common.context.EnvVarDef
import com.dynamicruntime.common.context.KdrCxt
import com.dynamicruntime.common.exception.KdrException
import com.dynamicruntime.common.gedra.ACEP
import com.dynamicruntime.common.gedra.CFEP
import com.dynamicruntime.common.gedra.ClientAudience
import com.dynamicruntime.common.gedra.ClientConfigIssues
import com.dynamicruntime.common.gedra.ClientDef
import com.dynamicruntime.common.gedra.ClientUsageType
import com.dynamicruntime.common.gedra.GCFG
import com.dynamicruntime.common.gedra.GU
import com.dynamicruntime.common.gedra.GedraConfig
import com.dynamicruntime.common.gedra.GedraConfigBuilder
import com.dynamicruntime.common.gedra.GedraConfigLoadService
import com.dynamicruntime.common.gedra.GedraConfigOrigin
import com.dynamicruntime.common.gedra.GedraConfigReload
import com.dynamicruntime.common.gedra.GedraConfigService
import com.dynamicruntime.common.gedra.GedraDataType
import com.dynamicruntime.common.gedra.gedraConfig
import com.dynamicruntime.common.gedra.gedraConfigToEntries
import com.dynamicruntime.common.gedra.sandboxOf
import com.dynamicruntime.common.home.HFRAG
import com.dynamicruntime.common.naming.clientNamespace
import com.dynamicruntime.common.startup.ComponentDefinition
import com.dynamicruntime.common.startup.SchemaService
import com.dynamicruntime.common.user.TestUser
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain

/**
 * Source-code overlays for a sandbox (issue #940): source code files a config under `<parent>:sandbox`, a change the
 * sandbox runs and the parent does not -- above the parent's source, below its stored revisions. Overlays for a
 * parent with no sandbox are reported and not loaded, one repeating the parent's source is flagged, a static
 * parent's sandbox shows its overlay in production, and nothing stored may be filed under a sandbox still.
 */
class SandboxOverlayTest : StringSpec({
    // Source problems are reported rather than refusing the boot, so the reported cases can be looked at.
    val flags = mapOf(OverlayFixture.loadFlag.name to "true", GCFG.checkEnvVar.name to "warn")
    // Its own instance (issue #1075): the boot is under test: what it loads and reports from OverlayFixture's source
    // overlays (check at warn).
    val cxt = Startup.mkTestBootCxt("sandboxOverlay940", "sandboxOverlay940", flags, listOf(OverlayFixture()))

    fun known(node: KdrCxt, client: String, traitId: String): Boolean =
        SchemaService.get(node).storeFor(client).types["${GCFG.globalNamespace}.${GU.unionName(GedraDataType.formDoc)}"]
            ?.variants?.isKnown(traitId) == true

    fun brand(node: KdrCxt, client: String): String? =
        MarkdownFragmentService.get(node).effectiveFragmentsFor(node, HFRAG.home, client)?.content?.get("home")?.get("brand")

    fun issues(client: String) = ClientConfigIssues.get(cxt).issuesFor(client).map { it.message }

    "an overlay's trait and copy are the sandbox's, not the parent's" {
        val parent = OverlayFixture.parent
        known(cxt, sandboxOf(parent), OverlayFixture.previewTrait) shouldBe true
        known(cxt, parent, OverlayFixture.previewTrait) shouldBe false
        // The parent's own source trait runs in both.
        known(cxt, sandboxOf(parent), OverlayFixture.baseTrait) shouldBe true
        brand(cxt, sandboxOf(parent)) shouldBe "Overlay brand"
        brand(cxt, parent) shouldBe "Parent brand"
        issues(sandboxOf(parent)).shouldBeEmpty()
    }

    "the parent's stored revision wins over an overlay that sets the same thing" {
        val parent = OverlayFixture.parent
        val setup = cxt.mkSubContext("setup", parent).also { it.userId = 9400L }
        // A draft: the sandbox runs the parent's latest, the parent (published-only) does not.
        GedraConfigService.get(cxt).writeConfig(
            setup,
            gedraConfig(cxt, "copy", clientNamespace(parent), parent) {
                fragmentOverlay(HFRAG.home) { namespace("home") { key("brand", "Stored brand") } }
            },
        )
        GedraConfigReload.reloadClient(cxt, parent)
        brand(cxt, sandboxOf(parent)) shouldBe "Stored brand"
        brand(cxt, parent) shouldBe "Parent brand"
        // The overlay's trait is still there: only what the stored layer sets is overridden.
        known(cxt, sandboxOf(parent), OverlayFixture.previewTrait) shouldBe true
    }

    "an overlay for a parent without the flag is reported on the parent, and not loaded" {
        val parent = OverlayFixture.unflagged
        issues(parent).single { "overlay for the sandbox" in it } shouldContain "has no sandbox"
        GedraConfigLoadService.get(cxt).loadedFor(sandboxOf(parent)).shouldBeEmpty()
    }

    "an overlay that repeats the parent's source is reported" {
        val sandbox = sandboxOf(OverlayFixture.repeating)
        issues(sandbox).single() shouldContain "repeats the parent's definition: copy '${HFRAG.home}: home.brand'"
        // Loaded as written; it changes nothing the parent's source does not already say.
        brand(cxt, sandbox) shouldBe "Same brand"
    }

    "nothing stored or imported may be filed under a sandbox; source may" {
        val sandbox = sandboxOf(OverlayFixture.parent)
        val stored = shouldThrow<KdrException> {
            gedraConfig(cxt, "stash", clientNamespace(OverlayFixture.parent), sandbox, origin = GedraConfigOrigin.stored) {}
        }
        stored.message.orEmpty() shouldContain "cannot be stored under the sandbox"
        gedraConfig(cxt, "fine", clientNamespace(OverlayFixture.parent), sandbox) {}.gedraId.client shouldBe sandbox

        // The write endpoints build stored configs, so a write naming the sandbox is refused there too.
        val admin = TestUser.createFullAdmin(cxt, "overlay-admin@example.com")
        val bundle = gedraConfig(cxt, "stash", clientNamespace(OverlayFixture.parent), OverlayFixture.parent) {
            trait("StashEntry", "stash", setOf(GedraDataType.formDoc), "A stash.") { property("v", "A value.") }
        }
        admin.expectError(
            400, ACEP.bundleWrite,
            data = mapOf(CFEP.client to sandbox, CFEP.name to "stash", CFEP.namespaceField to bundle.namespace, CFEP.slots to gedraConfigToEntries(bundle)),
        )
    }

    "a static parent's sandbox shows its overlay in production" {
        // Its own instance (issue #1075): a production boot with OverlayFixture: what that boot gives a static parent's
        // sandbox is under test.
        val prod = Startup.mkBootCxt(
            "sandboxOverlayProd940", "sandboxOverlayProd940",
            flags + mapOf(ACFG.env to ENV.prod, ACFG.isTestInstance to false, ACFG.inMemoryOnly to true),
            listOf(OverlayFixture()),
        )
        val parent = OverlayFixture.staticParent
        known(prod, sandboxOf(parent), OverlayFixture.previewTrait) shouldBe true
        known(prod, parent, OverlayFixture.previewTrait) shouldBe false
    }
})

/**
 * Source configs for [SandboxOverlayTest]: a parent with a sandbox and an overlay for it; a parent without the flag,
 * with an overlay anyway; a parent whose overlay repeats its source; and a static parent with a sandbox and an overlay.
 */
class OverlayFixture : ComponentDefinition {
    override val providerName: String = "sandboxOverlayFixture"
    override fun isLoaded(cxt: KdrCxt): Boolean = cxt.getEnvBool(loadFlag) == true

    private fun def(client: String, sandbox: Boolean, static: Boolean = false) = ClientDef(
        clientId = client, name = client, usageType = if (static) ClientUsageType.production else ClientUsageType.dev,
        audience = ClientAudience.internal, enabledEnvironments = setOf(ENV.unit, ENV.local, ENV.prod),
        sandbox = sandbox, staticConfig = static,
    )

    /** A config for [client] in [parent]'s namespace -- the overlay's rule, so promotion renames nothing. */
    private fun config(cxt: KdrCxt, name: String, client: String, parent: String = client, build: GedraConfigBuilder.() -> Unit) =
        gedraConfig(cxt, name, clientNamespace(parent), client, build = build)

    override fun gedraConfigs(cxt: KdrCxt): List<GedraConfig> = listOf(
        config(cxt, "main", parent) {
            defineClient(def(parent, sandbox = true))
            trait("BaseEntry", baseTrait, setOf(GedraDataType.formDoc), "The parent's own.") { property("v", "A value.") }
            fragmentOverlay(HFRAG.home) { namespace("home") { key("brand", "Parent brand") } }
        },
        config(cxt, "preview", sandboxOf(parent), parent) {
            trait("PreviewEntry", previewTrait, setOf(GedraDataType.formDoc), "Only the sandbox's, for now.") { property("v", "A value.") }
            fragmentOverlay(HFRAG.home) { namespace("home") { key("brand", "Overlay brand") } }
        },
        config(cxt, "main", unflagged) { defineClient(def(unflagged, sandbox = false)) },
        config(cxt, "preview", sandboxOf(unflagged), unflagged) {
            trait("PreviewEntry", previewTrait, setOf(GedraDataType.formDoc), "Nowhere to go.") { property("v", "A value.") }
        },
        config(cxt, "main", repeating) {
            defineClient(def(repeating, sandbox = true))
            fragmentOverlay(HFRAG.home) { namespace("home") { key("brand", "Same brand"); key("title", "Hello") } }
        },
        config(cxt, "leftover", sandboxOf(repeating), repeating) {
            fragmentOverlay(HFRAG.home) { namespace("home") { key("brand", "Same brand") } }
        },
        config(cxt, "main", staticParent) { defineClient(def(staticParent, sandbox = true, static = true)) },
        config(cxt, "preview", sandboxOf(staticParent), staticParent) {
            trait("PreviewEntry", previewTrait, setOf(GedraDataType.formDoc), "Only the sandbox's.") { property("v", "A value.") }
        },
    )

    @Suppress("ConstPropertyName")
    companion object {
        val loadFlag = EnvVarDef(
            "KDR_LOAD_SANDBOX_OVERLAY_FIXTURE", group = ENVGRP.application, defaultDoc = "off",
            description = "Test-only flag that loads the sandbox-overlay fixture component regardless of environment.",
        )
        const val parent = "ovlparent"
        const val unflagged = "ovlnoflag"
        const val repeating = "ovlrepeat"
        const val staticParent = "ovlstatic"
        const val baseTrait = "base"
        const val previewTrait = "preview"
    }
}
