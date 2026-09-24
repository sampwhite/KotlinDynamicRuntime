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
import com.dynamicruntime.common.gedra.GCEL
import com.dynamicruntime.common.gedra.GCFG
import com.dynamicruntime.common.gedra.GedraConfig
import com.dynamicruntime.common.gedra.GedraConfigLoadService
import com.dynamicruntime.common.gedra.GedraConfigOrigin
import com.dynamicruntime.common.gedra.GedraConfigService
import com.dynamicruntime.common.gedra.GedraDataType
import com.dynamicruntime.common.gedra.gedraConfig
import com.dynamicruntime.common.startup.BCHK
import com.dynamicruntime.common.startup.BootCheckMode
import com.dynamicruntime.common.startup.BootCheckRegistry
import com.dynamicruntime.common.startup.SchemaService
import io.kotest.assertions.throwables.shouldNotThrowAny
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain

/**
 * The boot-time load of stored client configurations (issue #614): a node that restarts picks up config a peer
 * wrote to the database, beside the source-declared ones. The first restart-visible behavior in #611.
 *
 * Each case pins its **own** `KDR_DB_NAME`, so its two boots share one in-memory database: without it, each
 * boot would get a database named after its instance (issue #836) and the second would find nothing stored. The
 * two boots take different instance names, so the second is a genuine fresh boot (the registry caches by
 * instance name) rather than the first one handed back.
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
        // Judged as stored config (issue #839): the refusal names the stored config and the variable that forgives it.
        ex.message.shouldNotBeNull() shouldContain GCFG.storedCheckEnvVar.name
        ex.message.shouldNotBeNull() shouldContain "extcfg"
    }

    // Issue #839: outside unit tests stored config is forgiven by default; a unit test opts into that with
    // KDR_STORED_CONFIG_CHECK=warn. The same bad stored config then costs only itself -- the node boots, the config
    // is dropped, and the issue says which stored config it was -- while source config stays strict.
    "with the stored-config check at warn, a bad stored config is dropped and the node boots" {
        val db = mapOf("KDR_DB_NAME" to "cfgBootLoad_forgive", "KDR_LOAD_STORED_CONFIG" to "true")
        val client = "forgiveclient"

        val cxt1 = Startup.mkTestBootCxt("cfgLoad1d", "cfgBootLoad1d", db)
        val config = gedraConfig(cxt1, "forgivecfg", "forgiveclientconfig", client) {
            defineClient(
                ClientDef(
                    clientId = client, name = "Forgiven Client",
                    usageType = ClientUsageType.dev, audience = ClientAudience.internal,
                    enabledEnvironments = setOf(ENV.unit, ENV.local),
                    extendsFromClientId = CL.hub,
                ),
            )
        }
        GedraConfigService.get(cxt1).writeConfig(writer(cxt1, client), config)

        val cxt2 = Startup.mkTestBootCxt(
            "cfgLoad2d", "cfgBootLoad2d", db + mapOf(GCFG.storedCheckEnvVar.name to BootCheckMode.warn.name),
        )
        ClientService.get(cxt2).known(client) shouldBe null
        val issue = GedraConfigLoadService.get(cxt2).issues.single()
        issue.message shouldContain "template"
        issue.origin shouldBe GedraConfigOrigin.stored
        issue.storedConfigId.shouldNotBeNull() shouldContain "forgivecfg"
        issue.client shouldBe client
        issue.elementKind shouldBe GCEL.config
        // Reported under its own check, naming the variable that governs it.
        val report = BootCheckRegistry.get(cxt2).results().first { it.name == BCHK.storedConfig }
        report.envVar shouldBe GCFG.storedCheckEnvVar.name
        report.mode shouldBe BootCheckMode.warn
        report.findings.single() shouldContain "forgivecfg"
    }

    // A problem belongs to whoever holds the reference (issue #839). Here a stored client includes a trait no
    // component declares -- what a code change removing that trait would leave behind -- so it is the stored
    // client's problem, forgiven under warn. Since issue #841 only the entry is dropped: the client stands, and its
    // issue names the stored config.
    "a stored client naming a trait that does not exist is the stored config's problem" {
        val db = mapOf("KDR_DB_NAME" to "cfgBootLoad_holder", "KDR_LOAD_STORED_CONFIG" to "true")
        val client = "holderclient"

        val cxt1 = Startup.mkTestBootCxt("cfgLoad1e", "cfgBootLoad1e", db)
        val config = gedraConfig(cxt1, "holdercfg", "holderclientconfig", client) {
            defineClient(
                ClientDef(
                    clientId = client, name = "Holder Client",
                    usageType = ClientUsageType.dev, audience = ClientAudience.internal,
                    enabledEnvironments = setOf(ENV.unit, ENV.local),
                    includedTraits = listOf("noSuchTrait839"),
                ),
            )
        }
        GedraConfigService.get(cxt1).writeConfig(writer(cxt1, client), config)

        // Strict by default in unit: the restart is refused, naming the stored-config variable.
        shouldThrow<KdrException> { Startup.mkTestBootCxt("cfgLoad2e", "cfgBootLoad2e", db) }
            .message.shouldNotBeNull() shouldContain GCFG.storedCheckEnvVar.name

        val cxt3 = Startup.mkTestBootCxt(
            "cfgLoad3e", "cfgBootLoad3e", db + mapOf(GCFG.storedCheckEnvVar.name to BootCheckMode.warn.name),
        )
        ClientService.get(cxt3).present(client).shouldNotBeNull().includedTraits.shouldBeEmpty()
        val issue = ClientService.get(cxt3).issues.single { it.client == client }
        issue.message shouldContain "noSuchTrait839"
        issue.storedConfigId.shouldNotBeNull() shouldContain "holdercfg"
        issue.elementKind shouldBe GCEL.client
        issue.elementId shouldBe client
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
