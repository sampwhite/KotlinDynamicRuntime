package com.dynamicruntime.kdn

import com.dynamicruntime.common.context.ACFG
import com.dynamicruntime.common.context.ENV
import com.dynamicruntime.common.context.KdrCxt
import com.dynamicruntime.common.exception.EXC
import com.dynamicruntime.common.exception.KdrException
import com.dynamicruntime.common.gedra.ClientAudience
import com.dynamicruntime.common.gedra.ClientConfigIssues
import com.dynamicruntime.common.gedra.ClientDef
import com.dynamicruntime.common.gedra.ClientUsageType
import com.dynamicruntime.common.gedra.GCEL
import com.dynamicruntime.common.gedra.GedraConfig
import com.dynamicruntime.common.gedra.GedraConfigReload
import com.dynamicruntime.common.gedra.GedraConfigService
import com.dynamicruntime.common.gedra.GedraConfigType
import com.dynamicruntime.common.gedra.GedraDataType
import com.dynamicruntime.common.gedra.GedraId
import com.dynamicruntime.common.gedra.gedraConfig
import com.dynamicruntime.common.startup.SchemaService
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain

/**
 * A `staticConfig` client takes nothing from the database **in production** (issue #824): a write of its stored
 * config is refused there, and stored config that already exists is ignored at boot and on a reload. Outside
 * production it is an ordinary client -- which is where its configuration is edited before being brought into its
 * source. Production is simulated by booting with `env = prod` on an in-memory database.
 */
class StaticConfigTest : StringSpec({

    val client = StaticClientComponent.clientId
    val db = "staticConfig_shared"

    fun overlay(env: String): Map<String, Any?> = mapOf(
        StaticClientComponent.loadFlag.name to "true", "KDR_DB_NAME" to db, "KDR_LOAD_STORED_CONFIG" to "true",
    ) + if (env == ENV.prod) {
        mapOf(ACFG.env to ENV.prod, ACFG.isTestInstance to false, ACFG.inMemoryOnly to true)
    } else {
        emptyMap()
    }

    fun bootUnit(name: String): KdrCxt =
        Startup.mkTestBootCxt(name, "${name}Test", overlay(ENV.unit), listOf(StaticClientComponent()))

    fun bootProd(name: String): KdrCxt =
        Startup.mkBootCxt(name, "${name}Test", overlay(ENV.prod), listOf(StaticClientComponent()))

    fun bound(cxt: KdrCxt): KdrCxt = cxt.mkSubContext("static", client).also { it.userId = 8240L }

    /** A stored config for the static client adding one trait -- the kind of edit made outside production. */
    fun edit(cxt: KdrCxt): GedraConfig = gedraConfig(cxt, "edits", "staticedits", client) {
        trait("StaticEditEntry", "staticEdit", setOf(GedraDataType.formDoc), "An edit made in the database.") {
            property("text", "Text.")
        }
    }

    "outside production a static client's stored config is written and loaded like any other" {
        val unit = bootUnit("staticUnit")
        GedraConfigService.get(unit).writeConfig(bound(unit), edit(unit))
        GedraConfigReload.reloadClient(unit, client).loaded shouldBe 1
        SchemaService.get(unit).gedraTraitsFor(client).map { it.traitId }.contains("staticEdit") shouldBe true
    }

    // Runs after the case above, over the same database: the stored edit is there, and production ignores it.
    "in production its stored config is ignored at boot and on a reload, and said so" {
        val prod = bootProd("staticProd")
        SchemaService.get(prod).gedraTraitsFor(client).map { it.traitId }.contains("staticEdit") shouldBe false
        val issue = ClientConfigIssues.get(prod).issuesFor(client).single()
        issue.elementKind shouldBe GCEL.client
        issue.message shouldContain "statically configured"

        val reload = GedraConfigReload.reloadClient(prod, client)
        reload.loaded shouldBe 0
        reload.issues.single().message shouldContain "statically configured"
    }

    "in production every change to its stored config is refused" {
        val prod = bootProd("staticProdWrite")
        val svc = GedraConfigService.get(prod)
        fun refused(block: () -> Unit) {
            val ex = shouldThrow<KdrException> { block() }
            ex.code shouldBe EXC.badInput
            ex.message.shouldNotBeNull() shouldContain "statically configured"
        }
        val main = GedraId.of(GedraConfigType.configDoc, client, "edits")
        refused { svc.writeConfig(bound(prod), edit(prod)) }
        refused { svc.patchConfig(bound(prod), main) { it } }
        refused { svc.publish(bound(prod), main) }
        refused { svc.revertToEditable(bound(prod), main) }
        refused { svc.setPublishedOnly(bound(prod), client, true) }
        svc.isStaticHere(bound(prod), client) shouldBe true
    }

    // Only a source definition may set it: a stored one claiming it would describe a client with no definition at
    // all in production.
    "a stored client definition setting staticConfig is refused" {
        val unit = bootUnit("staticStored")
        val other = "staticclaim"
        val claim = gedraConfig(unit, "main", "${other}config", other) {
            defineClient(
                ClientDef(
                    clientId = other, name = other, usageType = ClientUsageType.dev, audience = ClientAudience.internal,
                    enabledEnvironments = setOf(ENV.unit, ENV.local), staticConfig = true,
                ),
            )
        }
        shouldThrow<KdrException> {
            val writer = unit.mkSubContext("claim", other).also { it.userId = 8241L }
            GedraConfigService.get(unit).writeConfig(writer, claim)
        }.message.shouldNotBeNull() shouldContain "staticConfig"
    }
})
