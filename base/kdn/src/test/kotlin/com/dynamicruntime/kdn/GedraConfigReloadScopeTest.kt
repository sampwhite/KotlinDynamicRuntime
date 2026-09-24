package com.dynamicruntime.kdn

import com.dynamicruntime.common.context.ENV
import com.dynamicruntime.common.context.KdrCxt
import com.dynamicruntime.common.exception.KdrException
import com.dynamicruntime.common.gedra.ClientAudience
import com.dynamicruntime.common.gedra.ClientConfigIssues
import com.dynamicruntime.common.gedra.ClientDef
import com.dynamicruntime.common.gedra.ClientService
import com.dynamicruntime.common.gedra.ClientUsageType
import com.dynamicruntime.common.gedra.ConfigReloadResult
import com.dynamicruntime.common.gedra.GCFG
import com.dynamicruntime.common.gedra.GedraConfigReload
import com.dynamicruntime.common.gedra.GedraConfigService
import com.dynamicruntime.common.gedra.GedraDataType
import com.dynamicruntime.common.gedra.gedraConfig
import com.dynamicruntime.common.startup.BCHK
import com.dynamicruntime.common.startup.BootCheckMode
import com.dynamicruntime.common.startup.BootCheckRegistry
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain

/**
 * A reload judges only the client being reloaded (issue #842). Before, its second phase re-ran the client,
 * workflow and schema checks over **every** client: on strict, one client's bad definition failed every later
 * reload of any client (review item B4); on warn, every reload reported every other client's problems again.
 *
 * Each case boots its own instance -- and so, since issue #836, its own in-memory database -- because each stores
 * a deliberately bad client definition that stays in the node's collectors.
 */
class GedraConfigReloadScopeTest : StringSpec({

    fun writer(cxt: KdrCxt, client: String): KdrCxt = cxt.mkSubContext("scopeWrite", client).also { it.userId = 8420L }

    fun storeAndReload(cxt: KdrCxt, client: String, includedTraits: List<String> = emptyList()): ConfigReloadResult {
        val config = gedraConfig(cxt, "${client}cfg", "${client}config", client) {
            defineClient(
                ClientDef(
                    clientId = client, name = client, usageType = ClientUsageType.dev, audience = ClientAudience.internal,
                    enabledEnvironments = setOf(ENV.unit, ENV.local), includedTraits = includedTraits,
                ),
            )
            trait("${client}Entry", "${client}Trait", setOf(GedraDataType.formDoc), "A trait of $client.") {
                property("text", "A value.")
            }
        }
        GedraConfigService.get(cxt).writeConfig(writer(cxt, client), config)
        return GedraConfigReload.reloadClient(cxt, client)
    }

    // B4 strict, now fixed: the bad client's own reload is refused (stored config is strict in unit), but it no
    // longer takes an unrelated client's reload down with it.
    "on strict, one client's bad definition does not fail another client's reload" {
        val cxt = Startup.mkTestBootCxt("reloadScopeS", "reloadScopeStrict")
        val bad = "scope842bad"
        val good = "scope842good"

        shouldThrow<KdrException> { storeAndReload(cxt, bad, includedTraits = listOf("noSuchTrait842")) }
            .message.shouldNotBeNull() shouldContain "noSuchTrait842"

        val result = storeAndReload(cxt, good)
        result.loaded shouldBe 1
        result.issues.shouldBeEmpty()
        ClientService.get(cxt).present(good).shouldNotBeNull()
    }

    "on warn, a reload does not report another client's problems again" {
        val cxt = Startup.mkTestBootCxt(
            "reloadScopeW", "reloadScopeWarn", mapOf(GCFG.storedCheckEnvVar.name to BootCheckMode.warn.name),
        )
        val bad = "scope842drop"
        val good = "scope842fine"

        // The bad client is dropped, with one issue on it.
        storeAndReload(cxt, bad, includedTraits = listOf("noSuchTrait842")).issues.size shouldBe 1
        ClientService.get(cxt).known(bad) shouldBe null

        // Reloading another client reports nothing about it: no new finding in the operator report (a whole-node
        // recheck re-recorded the bad client's there on every reload), and neither service's list grows.
        fun storedFindings() = BootCheckRegistry.get(cxt).results().first { it.name == BCHK.storedConfig }.findings.size
        val findingsBefore = storedFindings()
        storeAndReload(cxt, good).issues.shouldBeEmpty()
        storedFindings() shouldBe findingsBefore
        ClientService.get(cxt).issues.count { it.client == bad } shouldBe 1
        ClientConfigIssues.get(cxt).issuesFor(bad).size shouldBe 1
        // The dropped client stays dropped, and the good one is present.
        ClientService.get(cxt).known(bad) shouldBe null
        ClientService.get(cxt).present(good).shouldNotBeNull()
    }
})
