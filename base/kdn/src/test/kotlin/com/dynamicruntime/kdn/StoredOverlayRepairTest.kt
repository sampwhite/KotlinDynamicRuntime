package com.dynamicruntime.kdn

import com.dynamicruntime.common.content.MarkdownFragmentService
import com.dynamicruntime.common.context.CL
import com.dynamicruntime.common.context.ENV
import com.dynamicruntime.common.context.KdrCxt
import com.dynamicruntime.common.exception.KdrException
import com.dynamicruntime.common.gedra.ClientAudience
import com.dynamicruntime.common.gedra.ClientConfigIssues
import com.dynamicruntime.common.gedra.ClientDef
import com.dynamicruntime.common.gedra.ClientService
import com.dynamicruntime.common.gedra.ClientUsageType
import com.dynamicruntime.common.gedra.ConfigReloadResult
import com.dynamicruntime.common.gedra.GCEL
import com.dynamicruntime.common.gedra.GCFG
import com.dynamicruntime.common.gedra.GedraConfigBuilder
import com.dynamicruntime.common.gedra.GedraConfigReload
import com.dynamicruntime.common.gedra.GedraConfigService
import com.dynamicruntime.common.gedra.gedraConfig
import com.dynamicruntime.common.home.HFLD
import com.dynamicruntime.common.home.HMENU
import com.dynamicruntime.common.schema.SCT
import com.dynamicruntime.common.schema.layout
import com.dynamicruntime.common.startup.BootCheckMode
import com.dynamicruntime.common.uiblock.UIB
import com.dynamicruntime.common.uiblock.UiBlockService
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain

/**
 * The rest of a client's stored configuration costs only itself too (issue #841, part 2): a UiBlock or fragment
 * overlay at fault is dropped rather than served (a reload used to take both unchecked, and the boot refused a bad
 * UiBlock even in production), a stored config breaking the extends rule is skipped rather than refusing the whole
 * reload, and an unresolvable layout pull is reported under the holding config's mode rather than refusing the boot.
 *
 * A shared instance with the stored-config check at `warn` (#839) and a new client per scenario.
 */
class StoredOverlayRepairTest : StringSpec({

    val warn = mapOf(GCFG.storedCheckEnvVar.name to BootCheckMode.warn.name)
    val cxt = Startup.mkTestBootCxt("overlayRepair", "storedOverlayRepairTest", warn)

    fun writer(on: KdrCxt, client: String): KdrCxt = on.mkSubContext("overlayWrite", client).also { it.userId = 8411L }

    fun clientDef(client: String, extendsFrom: String? = null) = ClientDef(
        clientId = client, name = client,
        usageType = ClientUsageType.dev, audience = ClientAudience.internal,
        enabledEnvironments = setOf(ENV.unit, ENV.local), extendsFromClientId = extendsFrom,
    )

    fun storeAndReload(on: KdrCxt, client: String, build: GedraConfigBuilder.() -> Unit): ConfigReloadResult {
        val config = gedraConfig(on, "${client}cfg", "${client}config", client) {
            defineClient(clientDef(client))
            build()
        }
        GedraConfigService.get(on).writeConfig(writer(on, client), config)
        return GedraConfigReload.reloadClient(on, client)
    }

    "a UiBlock overlay naming a cfact nothing declares is dropped, and the block serves without it" {
        val client = "ovl841ui"
        val result = storeAndReload(cxt, client) {
            uiBlockOverlay(HMENU.block) {
                items(HFLD.menu) { item { set(HFLD.id, "extra841"); set(UIB.cfactExpression, "noSuchFact841") } }
            }
        }
        val issue = result.issues.single()
        issue.elementKind shouldBe GCEL.uiBlock
        issue.message shouldContain "noSuchFact841"
        UiBlockService.registeredUiBlocks(cxt).none { it.client == client } shouldBe true
        ClientService.get(cxt).present(client).shouldNotBeNull()
    }

    "a fragment overlay with keys no base declares is dropped, and the client reads the default copy" {
        val client = "ovl841frag"
        val result = storeAndReload(cxt, client) {
            fragmentOverlay("home") {
                namespace("home") {
                    key("title", "Overlaid title")
                    key("noSuchKey841", "Nowhere")
                }
            }
        }
        val issue = result.issues.single()
        issue.elementKind shouldBe GCEL.fragment
        issue.message shouldContain "noSuchKey841"
        MarkdownFragmentService.registeredFragmentSources(cxt).none { it.client == client } shouldBe true
        val asClient = cxt.mkSubContext("read", client)
        MarkdownFragmentService.get(cxt).resolveFragment(asClient, "home", "home", "title") shouldBe
            MarkdownFragmentService.get(cxt).resolveFragment(cxt, "home", "home", "title")
    }

    // The reload used to refuse outright (`KdrException.mkInput`) on the first config breaking the extends rule;
    // it is now that config's problem alone, judged as stored config.
    "a stored config breaking the extends rule is skipped, not the reload" {
        val client = "ovl841ext"
        val config = gedraConfig(cxt, "${client}cfg", "${client}config", client) {
            // `hub` is a source client, but not a template.
            defineClient(clientDef(client, extendsFrom = CL.hub))
        }
        GedraConfigService.get(cxt).writeConfig(writer(cxt, client), config)
        val result = GedraConfigReload.reloadClient(cxt, client)
        result.loaded shouldBe 0
        result.issues.single().message shouldContain "template"
        ClientService.get(cxt).known(client) shouldBe null
    }

    // Checked only at boot, since the fragment registry it needs belongs to a later service. Forgiving drops
    // nothing -- delivery already renders an unresolved pull as written -- so the layout stands.
    "an unresolvable layout pull in stored config no longer refuses the restart" {
        val db = mapOf("KDR_DB_NAME" to "overlayRepair_pull", "KDR_LOAD_STORED_CONFIG" to "true")
        val client = "ovl841pull"
        val first = Startup.mkTestBootCxt("overlayRepair1", "storedOverlayRepair1", db + warn)
        storeAndReload(first, client) {
            type("Pulled") {
                type = SCT.kObject
                property("topic", "A topic.")
                layout { field("topic", label = "%{@t(\"noSuchFile841.ns.key\")}") }
            }
        }

        val restarted = Startup.mkTestBootCxt("overlayRepair2", "storedOverlayRepair2", db + warn)
        val issue = ClientConfigIssues.get(restarted).issuesFor(client).single()
        issue.message shouldContain "noSuchFile841"
        issue.elementId shouldBe "${client}config.Pulled"

        shouldThrow<KdrException> { Startup.mkTestBootCxt("overlayRepair3", "storedOverlayRepair3", db) }
            .message.shouldNotBeNull() shouldContain GCFG.storedCheckEnvVar.name
    }
})
