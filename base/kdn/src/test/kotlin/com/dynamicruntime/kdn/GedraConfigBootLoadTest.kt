package com.dynamicruntime.kdn

import com.dynamicruntime.common.context.CL
import com.dynamicruntime.common.context.ENV
import com.dynamicruntime.common.context.KdrCxt
import com.dynamicruntime.common.exception.KdrException
import com.dynamicruntime.common.gedra.ClientAudience
import com.dynamicruntime.common.gedra.ClientDef
import com.dynamicruntime.common.gedra.ClientService
import com.dynamicruntime.common.gedra.ClientUsageType
import com.dynamicruntime.common.gedra.GedraConfig
import com.dynamicruntime.common.gedra.GedraConfigLoadService
import com.dynamicruntime.common.gedra.GedraConfigService
import com.dynamicruntime.common.gedra.GedraDataType
import com.dynamicruntime.common.gedra.gedraConfig
import com.dynamicruntime.common.content.MarkdownFragmentService
import com.dynamicruntime.common.startup.SchemaService
import io.kotest.assertions.throwables.shouldNotThrowAny
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe

/**
 * The boot-time load of stored client configurations (issue #614): a node that restarts picks up config a peer
 * wrote to the database, beside the source-declared ones. The first restart-visible behavior in #611.
 *
 * Each case pins its **own** `KDR_DB_NAME`, so its two boots share one isolated in-memory database and its
 * client-defining configs do not leak into the default database every other spec boots against. The two boots
 * take different instance names, so the second is a genuine fresh boot (the registry caches by instance name)
 * rather than the first one handed back.
 */
class GedraConfigBootLoadTest : StringSpec({

    /** A context bound to [client] as an actor, for writing that client's config. */
    fun writer(cxt: KdrCxt, client: String): KdrCxt = cxt.mkSubContext("cfgWrite", client).also { it.userId = 7000L }

    "a stored config's client and traits are present after a restart" {
        val db = mapOf("KDR_DB_NAME" to "cfgBootLoad_basic", "KDR_LOAD_STORED_CONFIG" to "true")
        val client = "bootclient"

        // Boot 1: write a config that defines a client and one trait.
        val cxt1 = Startup.mkTestBootCxt("cfgLoad1a", "cfgBootLoad1a", db)
        val config: GedraConfig = gedraConfig(cxt1, "bootcfg", "bootclientconfig", client) {
            defineClient(
                ClientDef(
                    clientId = client, name = "Boot Client",
                    usageType = ClientUsageType.dev, audience = ClientAudience.internal,
                    enabledEnvironments = setOf(ENV.unit, ENV.local),
                ),
            )
            trait("BootNoteEntry", "bootNote", setOf(GedraDataType.formDoc), "A note added by boot config.") {
                property("text", "The note.", required = true)
            }
        }
        GedraConfigService.get(cxt1).writeConfig(writer(cxt1, client), config)
        // The writing node did not have the client before it wrote (its own boot-load ran first, on an empty db).
        ClientService.get(cxt1).known(client) shouldBe null

        // Boot 2 -- the restart -- shares the database and loads what boot 1 wrote.
        val cxt2 = Startup.mkTestBootCxt("cfgLoad2a", "cfgBootLoad2a", db)
        val loaded = ClientService.get(cxt2).known(client)
        loaded.shouldNotBeNull()
        loaded.name shouldBe "Boot Client"
        // And the trait it declared is compiled into this node's schema for that client.
        SchemaService.get(cxt2).gedraTraitsFor(client).map { it.traitId } shouldContain "bootNote"
    }

    "a stored config that extends a non-template source client is refused at boot" {
        val db = mapOf("KDR_DB_NAME" to "cfgBootLoad_extends", "KDR_LOAD_STORED_CONFIG" to "true")
        val client = "extclient"

        val cxt1 = Startup.mkTestBootCxt("cfgLoad1b", "cfgBootLoad1b", db)
        // `hub` is a source client, but a production one, not a template -- so a data config may not extend it.
        val config = gedraConfig(cxt1, "extcfg", "extclientconfig", client) {
            defineClient(
                ClientDef(
                    clientId = client, name = "Extending Client",
                    usageType = ClientUsageType.dev, audience = ClientAudience.internal,
                    enabledEnvironments = setOf(ENV.unit, ENV.local),
                    extendsFromClientId = CL.hub,
                ),
            )
        }
        GedraConfigService.get(cxt1).writeConfig(writer(cxt1, client), config)

        // The restart refuses the boot: in the unit environment a config problem is strict (it refuses rather
        // than degrading), and an unloadable stored config is that same kind of problem.
        val ex = shouldThrow<KdrException> { Startup.mkTestBootCxt("cfgLoad2b", "cfgBootLoad2b", db) }
        (ex.message ?: "").contains("template") shouldBe true
    }

    "a stored config's fragment overlay is served after a restart, and a re-load does not double it" {
        val db = mapOf("KDR_DB_NAME" to "cfgBootLoad_frag", "KDR_LOAD_STORED_CONFIG" to "true")
        val client = "fragclient"

        val cxt1 = Startup.mkTestBootCxt("cfgLoad1c", "cfgBootLoad1c", db)
        // A config that overlays the shipped `home` fragment for its own client (`home.title` exists in the
        // base file, so the overlay is not orphaned) and defines the client so the overlay has a variant.
        val config = gedraConfig(cxt1, "fragcfg", "fragclientconfig", client) {
            defineClient(
                ClientDef(
                    clientId = client, name = "Frag Client",
                    usageType = ClientUsageType.dev, audience = ClientAudience.internal,
                    enabledEnvironments = setOf(ENV.unit, ENV.local),
                ),
            )
            fragmentOverlay("home") { namespace("home") { key("title", "Boot Overlaid Title") } }
        }
        GedraConfigService.get(cxt1).writeConfig(writer(cxt1, client), config)

        // The restart loads the config and folds its overlay into the fragment registry the content service
        // reads, so the client's people are served the overlaid copy (a caller with no client sees the base).
        val cxt2 = Startup.mkTestBootCxt("cfgLoad2c", "cfgBootLoad2c", db)
        ClientService.get(cxt2).known(client).shouldNotBeNull()
        val asClient = cxt2.mkSubContext("read", client)
        MarkdownFragmentService.get(cxt2).resolveFragment(asClient, "home", "home", "title") shouldBe "Boot Overlaid Title"

        // Forcing the load again -- as a later startup service may -- is a no-op rather than a second add, which
        // the collector would reject as "contributed twice" and refuse the boot over.
        shouldNotThrowAny { GedraConfigLoadService.get(cxt2).checkInit(cxt2) }
        ClientService.get(cxt2).known(client).shouldNotBeNull()
    }
})
