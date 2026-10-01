package com.dynamicruntime.kdn

import com.dynamicruntime.common.content.MarkdownFragmentService
import com.dynamicruntime.common.context.CL
import com.dynamicruntime.common.context.ENV
import com.dynamicruntime.common.context.KdrCxt
import com.dynamicruntime.common.exception.KdrException
import com.dynamicruntime.common.gedra.ClientAudience
import com.dynamicruntime.common.gedra.ClientDef
import com.dynamicruntime.common.gedra.ClientService
import com.dynamicruntime.common.gedra.ClientUsageType
import com.dynamicruntime.common.gedra.GedraConfig
import com.dynamicruntime.common.gedra.GedraConfigBuilder
import com.dynamicruntime.common.gedra.GedraConfigLoadService
import com.dynamicruntime.common.gedra.GedraConfigReload
import com.dynamicruntime.common.gedra.GedraConfigService
import com.dynamicruntime.common.gedra.GedraDataType
import com.dynamicruntime.common.gedra.copyOverrides
import com.dynamicruntime.common.gedra.gedraConfig
import com.dynamicruntime.common.gedra.sandboxOf
import com.dynamicruntime.common.gedra.workflow.WfEntry
import com.dynamicruntime.common.gedra.workflow.WfSaveKind
import com.dynamicruntime.common.gedra.workflow.WorkflowService
import com.dynamicruntime.common.schema.SCT
import com.dynamicruntime.common.startup.ComponentDefinition
import com.dynamicruntime.common.startup.SchemaService
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldNotContain
import io.kotest.matchers.maps.shouldContainKey
import io.kotest.matchers.maps.shouldNotContainKey
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain

/**
 * A client built on a template (issue #945): the template's source configuration cloned under the client, with the
 * client's own applied over it -- at boot for a source client, on a reload for a data-defined one, in the trial a
 * write runs, and for the sandbox of an extending client.
 */
class ClientExtensionTest : StringSpec({
    val tpl = ExtensionTemplateComponent.template
    val kid = ExtensionTemplateComponent.child
    val cxt = Startup.mkTestBootCxt("clientExtension", "clientExtensionTest", emptyMap(), listOf(ExtensionTemplateComponent()))

    fun traits(client: String) = SchemaService.get(cxt).gedraTraitsFor(client)
    fun traitIds(client: String) = traits(client).map { it.traitId }
    fun typeOf(client: String, traitId: String) = traits(client).single { it.traitId == traitId }.typeName
    fun title(client: String) =
        MarkdownFragmentService.get(cxt).resolveFragment(cxt.mkSubContext("read", client), "home", "home", "title")
    fun brand(client: String) =
        MarkdownFragmentService.get(cxt).resolveFragment(cxt.mkSubContext("read", client), "home", "home", "brand")
    fun writer(client: String): KdrCxt = cxt.mkSubContext("extensionWrite", client).also { it.userId = 9450L }

    fun dataDef(client: String, labels: List<String> = emptyList()) = ClientDef(
        clientId = client, name = client, usageType = ClientUsageType.dev, audience = ClientAudience.internal,
        enabledEnvironments = setOf(ENV.unit, ENV.local), extendsFromClientId = tpl, userLabels = labels,
    )

    fun store(client: String, name: String = "main", define: Boolean = true, trial: Boolean = false, build: GedraConfigBuilder.() -> Unit = {}) {
        val config = gedraConfig(cxt, name, "${client}${name}config", client) {
            if (define) defineClient(dataDef(client))
            build()
        }
        GedraConfigService.get(cxt).writeConfig(writer(client), config, trial = trial)
    }

    "a source client gets its template's traits, types, cfacts, workflows and copy" {
        traitIds(kid) shouldContain "tplNote"
        SchemaService.get(cxt).storeFor(kid).types shouldContainKey "tplbaseconfig.Shared"
        SchemaService.get(cxt).cfactsFor(kid).defs shouldContainKey "tplFlag"
        WorkflowService.get(cxt).forClient(kid).workflow("tplFlow").shouldNotBeNull()
        // A key only the template sets reads the template's; one the client sets reads the client's.
        brand(kid) shouldBe "TPL"
        title(kid) shouldBe "Kid title"
        // The template's copies are filed under the client, keep the template's namespace, and are the loader's.
        val copies = GedraConfigLoadService.get(cxt).loadedFor(kid).filter { it.inheritedFrom == tpl }
        copies.map { it.name } shouldContainExactly listOf("from_${tpl}_base")
        copies.single().namespace shouldBe "tplbaseconfig"
    }

    "a trait the client redefines replaces the template's and appears once" {
        traitIds(kid).count { it == "tplScore" } shouldBe 1
        typeOf(kid, "tplScore") shouldBe "tplkidconfig.KidScoreEntry"
        // The template still has its own.
        typeOf(tpl, "tplScore") shouldBe "tplbaseconfig.TplScoreEntry"
    }

    "the client's definition takes the template's defaults" {
        val def = ClientService.get(cxt).present(kid).shouldNotBeNull()
        def.webResourcesId shouldBe "tplres"
        def.userLabels shouldContainExactly listOf("vip", "kidlabel")
        def.includedTraits shouldContain "tplNote"
    }

    "nothing reaches a client that does not extend the template" {
        traitIds(CL.hub) shouldNotContain "tplNote"
        SchemaService.get(cxt).storeFor(CL.hub).types shouldNotContainKey "tplbaseconfig.Shared"
        brand(CL.hub) shouldBe "KDR"
    }

    "the copy report names the template for a value only the template sets" {
        val fragments = MarkdownFragmentService.get(cxt)
        val rows = copyOverrides(MarkdownFragmentService.registeredFragmentSources(cxt), kid) { f, c ->
            fragments.effectiveFragmentsFor(cxt, f, c)
        }
        rows.single { it.key == "brand" }.inheritedFrom shouldBe tpl
        rows.single { it.key == "title" }.inheritedFrom.shouldBeNull()
        rows.single { it.key == "title" }.value shouldBe "Kid title"
    }

    "the sandbox of an extending client is extended itself rather than carrying its parent's copy" {
        val sandbox = sandboxOf(kid)
        traitIds(sandbox) shouldContain "tplNote"
        typeOf(sandbox, "tplScore") shouldBe "tplkidconfig.KidScoreEntry"
        val loaded = GedraConfigLoadService.get(cxt).loadedFor(sandbox)
        // One copy of the template, its own; the parent's copy is not among the rebound configs.
        loaded.count { it.inheritedFrom == tpl } shouldBe 1
        loaded.none { it.name.startsWith("from_") && it.inheritedFrom == null } shouldBe true
        ClientService.get(cxt).present(sandbox)?.extendsFromClientId shouldBe tpl
    }

    "a data-defined client is built on the template at reload, and rebuilt when it stops redefining" {
        val client = "tpldata"
        store(client) {
            trait("DataNoteEntry", "tplNote", setOf(GedraDataType.formDoc), "The client's note.") { property("memo", "A memo.") }
        }
        GedraConfigReload.reloadClient(cxt, client)
        typeOf(client, "tplNote") shouldBe "${client}mainconfig.DataNoteEntry"
        typeOf(client, "tplScore") shouldBe "tplbaseconfig.TplScoreEntry"
        brand(client) shouldBe "TPL"
        ClientService.get(cxt).present(client)?.webResourcesId shouldBe "tplres"

        // The next revision drops the redefinition: the copy is rebuilt with the template's trait back in it.
        store(client)
        GedraConfigReload.reloadClient(cxt, client)
        typeOf(client, "tplNote") shouldBe "tplbaseconfig.TplNoteEntry"
        traitIds(client).count { it == "tplNote" } shouldBe 1
    }

    "a write that newly redefines a template trait passes its trial" {
        val client = "tpltrial"
        store(client, trial = true)
        GedraConfigReload.reloadClient(cxt, client)
        typeOf(client, "tplScore") shouldBe "tplbaseconfig.TplScoreEntry"
        // The trial remakes the copy without `tplScore`, rather than judging the revision beside the copy holding it.
        store(client, trial = true) {
            trait("TrialScoreEntry", "tplScore", setOf(GedraDataType.formDoc), "The client's score.") { property("grade", "A grade.") }
        }
        GedraConfigReload.reloadClient(cxt, client)
        typeOf(client, "tplScore") shouldBe "${client}mainconfig.TrialScoreEntry"
    }

    "two of the client's own configs redefining one template trait are still a collision" {
        val client = "tpldup"
        store(client) {
            trait("DupOneEntry", "tplNote", setOf(GedraDataType.formDoc), "One.") { property("one", "One.") }
        }
        store(client, name = "second", define = false) {
            trait("DupTwoEntry", "tplNote", setOf(GedraDataType.formDoc), "Two.") { property("two", "Two.") }
        }
        // Strict in unit tests: the reload refuses, naming both declarations.
        val message = shouldThrow<KdrException> { GedraConfigReload.reloadClient(cxt, client) }.message.shouldNotBeNull()
        message shouldContain "Trait 'tplNote' is declared by both"
    }

    "a client may not author into the template's namespace" {
        val client = "tplns"
        val config = gedraConfig(cxt, "main", "tplbaseconfig", client) {
            defineClient(dataDef(client))
            type("Mine") { type = SCT.kObject; property("x", "X.") }
        }
        shouldThrow<KdrException> { GedraConfigService.get(cxt).writeConfig(writer(client), config, trial = true) }
            .message.shouldNotBeNull() shouldContain "tplbaseconfig"
    }
})

/** A fixture component with a source template and a source client built on it (issue #945). */
class ExtensionTemplateComponent : ComponentDefinition {
    override val providerName: String = "extensionTemplateFixture"

    override fun gedraConfigs(cxt: KdrCxt): List<GedraConfig> = listOf(
        gedraConfig(cxt, "base", "tplbaseconfig", template) {
            defineClient(
                ClientDef(
                    clientId = template, name = "Template", usageType = ClientUsageType.template,
                    audience = ClientAudience.internal, enabledEnvironments = setOf(ENV.unit, ENV.local),
                    webResourcesId = "tplres", includedTraits = listOf("tplNote"), userLabels = listOf("vip"),
                ),
            )
            type("Shared") { type = SCT.kObject; property("code", "A code.") }
            trait("TplNoteEntry", "tplNote", setOf(GedraDataType.formDoc), "A note.") { property("note", "A note.") }
            trait("TplScoreEntry", "tplScore", setOf(GedraDataType.formDoc), "A score.") {
                property("score", "A score.") { type = SCT.integer }
            }
            cfact("tplFlag", "tpl", "A template flag.")
            workflow("tplFlow", WfEntry.normal) { task("a", "A") { trait("tplNote"); save("s", "Save", WfSaveKind.edit) } }
            fragmentOverlay("home") {
                namespace("home") {
                    key("title", "Template title")
                    key("brand", "TPL")
                }
            }
        },
        gedraConfig(cxt, "kid", "tplkidconfig", child) {
            defineClient(
                ClientDef(
                    clientId = child, name = "Kid", usageType = ClientUsageType.dev,
                    audience = ClientAudience.internal, enabledEnvironments = setOf(ENV.unit, ENV.local),
                    extendsFromClientId = template, userLabels = listOf("kidlabel"), sandbox = true,
                ),
            )
            trait("KidScoreEntry", "tplScore", setOf(GedraDataType.formDoc), "The child's score.") {
                property("stars", "Stars.") { type = SCT.integer }
            }
            fragmentOverlay("home") { namespace("home") { key("title", "Kid title") } }
        },
    )

    @Suppress("ConstPropertyName")
    companion object {
        const val template = "tplbase"
        const val child = "tplkid"
    }
}
