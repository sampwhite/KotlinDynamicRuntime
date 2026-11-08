package com.dynamicruntime.kdn

import com.dynamicruntime.common.context.UPF
import com.dynamicruntime.common.gedra.CCT
import com.dynamicruntime.common.gedra.CFEP
import com.dynamicruntime.common.gedra.CLD
import com.dynamicruntime.common.gedra.ClientConfigIssues
import com.dynamicruntime.common.gedra.ClientSandboxEdit
import com.dynamicruntime.common.gedra.ClientService
import com.dynamicruntime.common.gedra.GCFG
import com.dynamicruntime.common.gedra.GE
import com.dynamicruntime.common.gedra.GED
import com.dynamicruntime.common.gedra.GedraConfigReload
import com.dynamicruntime.common.gedra.GedraConfigService
import com.dynamicruntime.common.gedra.GedraEditAction
import com.dynamicruntime.common.gedra.reassembleGedraConfig
import com.dynamicruntime.common.gedra.sandboxOf
import com.dynamicruntime.common.http.request.ROLE
import com.dynamicruntime.common.startup.BootCheckMode
import com.dynamicruntime.common.startup.SchemaService
import com.dynamicruntime.common.user.AEP
import com.dynamicruntime.common.user.TestUser
import com.dynamicruntime.common.user.UADEP
import com.dynamicruntime.common.util.toOptLong
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain

/**
 * The sandbox switch for a client defined in code (issue #1677): it stores an overlay of the client's definition in
 * the stored bundle named like the code bundle that declares it, holding only `sandbox`, so the sandbox is on if the
 * code or that overlay says so. Its own instance, since it gives the fixture's code clients sandboxes and stored
 * configuration.
 */
class CodeClientSandboxTest : StringSpec({
    val cxt = Startup.mkTestBootCxt("codeSandbox", "kdnCodeClientSandbox1677", emptyMap(), listOf(ExtensionTemplateComponent()))
    // A code client whose code leaves the sandbox off, and one whose code turns it on.
    val off = ExtensionTemplateComponent.ownLayer
    val inCode = ExtensionTemplateComponent.ownCode
    val svc = GedraConfigService.get(cxt)

    fun bundles(client: String) = svc.listConfigs(cxt.mkSubContext("codeSandbox", client)).associateBy { it.configId.baseId }
    fun hasSandbox(client: String) = ClientService.get(cxt).isPresent(sandboxOf(client))
    fun cfacts(client: String): Set<String> = SchemaService.get(cxt).cfactsFor(client).names

    /** A patch adding cfact [name] to [config]: a change to the configuration, which a sandbox makes as a draft. */
    fun addCfact(config: String, name: String) = mapOf(
        CFEP.configs to listOf(
            mapOf(
                CFEP.name to config,
                CFEP.edits to listOf(
                    mapOf(
                        CFEP.slot to CCT.cfactDef, GED.action to GedraEditAction.addOrReplace.name,
                        GE.data to mapOf(CCT.name to name, CCT.group to "sbx", CCT.description to "A cfact.", CCT.toFrontend to false),
                    ),
                ),
            ),
        ),
    )

    "the switch gives a code client a sandbox by an overlay of its definition, and takes it away again" {
        val chief = TestUser.create(cxt, "chief@$off.test", level = ROLE.admin, userClient = off)
        hasSandbox(off) shouldBe false
        ClientSandboxEdit.codeSetsSandbox(cxt, off) shouldBe false

        val on = chief.postData(UADEP.clientSandbox, mapOf(CLD.sandbox to true))
        on[CLD.sandbox] shouldBe true
        on[CLD.publishedOnly] shouldBe true
        // Stored, published, in the bundle named like the code's that declares the client, and nothing else in it.
        val own = bundles(off).getValue("own")
        own.isPublished shouldBe true
        own.entriesBySlot().filterValues { it.isNotEmpty() } shouldBe mapOf(
            CCT.clientDef to listOf(mapOf(CLD.clientId to off, CCT.overrides to CCT.overridesCodeOverlayValue, CLD.sandbox to true)),
        )
        ClientService.get(cxt).present(off).shouldNotBeNull().sandbox shouldBe true
        hasSandbox(off) shouldBe true

        // Taken away with nothing else stored: the bundle the switch made goes with the overlay.
        val removed = chief.postData(UADEP.clientSandbox, mapOf(CLD.sandbox to false))
        removed[CLD.sandbox] shouldBe false
        removed[CLD.publishedOnly] shouldBe false
        hasSandbox(off) shouldBe false
        bundles(off).keys.contains("own") shouldBe false

        // Again, and this time a change made in the sandbox: a draft of the client's, which the sandbox runs and the
        // client does not until it is published from there. It lands in the same bundle, beside the overlay.
        chief.postData(UADEP.clientSandbox, mapOf(CLD.sandbox to true))[CLD.sandbox] shouldBe true
        val opened = TestUser.create(cxt, "chief@$off.test", level = ROLE.admin, userClient = off)
        opened.postData(AEP.openSandbox, emptyMap())[UPF.userId].toOptLong().shouldNotBeNull()
        opened.postData(CFEP.bundlePatch, addCfact("own", "offTried"))
        ("offTried" in cfacts(sandboxOf(off))) shouldBe true
        ("offTried" in cfacts(off)) shouldBe false
        opened.postData(CFEP.bundlePublish, mapOf(CFEP.names to listOf("own")))
        ("offTried" in cfacts(off)) shouldBe true

        // Taken away: the overlay goes, the bundle keeps the rest, and the client runs its latest again.
        chief.postData(UADEP.clientSandbox, mapOf(CLD.sandbox to false))[CLD.publishedOnly] shouldBe false
        hasSandbox(off) shouldBe false
        bundles(off).getValue("own").entriesBySlot().filterValues { it.isNotEmpty() }.keys shouldContainExactly setOf(CCT.cfactDef)
        ("offTried" in cfacts(off)) shouldBe true
    }

    "a sandbox the code sets is removed only there" {
        val chief = TestUser.create(cxt, "chief@$inCode.test", level = ROLE.admin, userClient = inCode)
        ClientSandboxEdit.codeSetsSandbox(cxt, inCode) shouldBe true
        hasSandbox(inCode) shouldBe true
        chief.expectError(400, UADEP.clientSandbox, mapOf(CLD.sandbox to false)).toString() shouldContain "set in its code"
        // Asking for what the code already gives changes nothing, and stores nothing.
        chief.postData(UADEP.clientSandbox, mapOf(CLD.sandbox to true))[CLD.sandbox] shouldBe true
        bundles(inCode).keys.filter { it == "own" } shouldBe emptyList()
        hasSandbox(inCode) shouldBe true
    }

    "an overlay of a client's definition holds only its sandbox, and only for a client its code declares" {
        val chief = TestUser.create(cxt, "keys@$off.test", level = ROLE.admin, userClient = off)
        fun overlay(entry: Map<String, Any?>) = mapOf(
            CFEP.configs to listOf(
                mapOf(
                    CFEP.name to "defOverlay",
                    CFEP.edits to listOf(
                        mapOf(
                            CFEP.slot to CCT.clientDef, GED.action to GedraEditAction.addOrReplace.name,
                            GE.data to mapOf(CLD.clientId to off, CCT.overrides to CCT.overridesCodeOverlayValue) + entry,
                        ),
                    ),
                ),
            ),
            CFEP.dryRun to true,
        )
        chief.expectError(400, CFEP.bundlePatch, overlay(mapOf(CLD.sandbox to true, CLD.name to "Renamed"))).toString() shouldContain
            "may set only whether it has a sandbox"
        chief.expectError(400, CFEP.bundlePatch, overlay(mapOf(CLD.sandbox to "yes"))).toString() shouldContain "true or false"
        // Naming a client the code does not declare for this one: the trial reads it over the code, and refuses it.
        chief.expectError(400, CFEP.bundlePatch, overlay(mapOf(CLD.clientId to inCode, CLD.sandbox to true))).toString() shouldContain
            "declares no definition of client '$inCode'"
        // What the switch stores passes.
        chief.postData(CFEP.bundlePatch, overlay(mapOf(CLD.sandbox to true)))
    }

    "an overlay of a client's definition stored with more than its sandbox loads, leaving the rest out and saying so" {
        // Forgiven at a load outside unit tests, as stored configuration is: an instance that reports rather than refuses.
        val warn = Startup.mkTestBootCxt(
            "codeSandboxWarn", "kdnCodeClientSandboxWarn1677", mapOf(GCFG.storedCheckEnvVar.name to BootCheckMode.warn.name),
            listOf(ExtensionTemplateComponent()),
        )
        // Stored without the write's checks, as one written before them would be.
        val bound = warn.mkSubContext("codeSandbox", off).also { it.userId = 16770L }
        val config = reassembleGedraConfig(
            warn, "legacy", "client.$off.legacy", off,
            mapOf(
                CCT.clientDef to listOf(
                    mapOf(CLD.clientId to off, CCT.overrides to CCT.overridesCodeOverlayValue, CLD.sandbox to true, CLD.name to "Renamed"),
                ),
            ),
        )
        GedraConfigService.get(warn).writeConfig(bound, config, trial = false)
        GedraConfigReload.reloadClient(warn, off)
        val def = ClientService.get(warn).present(off).shouldNotBeNull()
        def.name shouldBe "Own layer"
        def.sandbox shouldBe true
        ClientService.get(warn).isPresent(sandboxOf(off)) shouldBe true
        ClientConfigIssues.get(warn).issuesFor(off).joinToString { it.message } shouldContain
            "changes the code's definition of client '$off' with parts such a change may not hold"
    }
})
