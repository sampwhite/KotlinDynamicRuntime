package com.dynamicruntime.kdn

import com.dynamicruntime.common.context.ENV
import com.dynamicruntime.common.context.KdrCxt
import com.dynamicruntime.common.exception.KdrException
import com.dynamicruntime.common.gedra.CFEP
import com.dynamicruntime.common.gedra.ClientAudience
import com.dynamicruntime.common.gedra.ClientDef
import com.dynamicruntime.common.gedra.ClientService
import com.dynamicruntime.common.gedra.ClientUsageType
import com.dynamicruntime.common.gedra.GedraConfigLoadService
import com.dynamicruntime.common.gedra.GedraConfigReload
import com.dynamicruntime.common.gedra.GedraConfigService
import com.dynamicruntime.common.gedra.GedraDataType
import com.dynamicruntime.common.gedra.gedraConfig
import com.dynamicruntime.common.http.request.ROLE
import com.dynamicruntime.common.startup.SchemaService
import com.dynamicruntime.common.user.TestUser
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldNotContain
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeSameInstanceAs
import io.kotest.matchers.types.shouldNotBeSameInstanceAs

/**
 * Reloading one client's stored configuration on a running node (issue #616): the reload changes what the
 * client's schema advertises and validates against, a request in flight is unaffected, a second client is
 * untouched, and a reload that fails leaves the node exactly as it was. One booted instance; unique clients.
 */
class GedraConfigReloadTest : StringSpec({
    val cxt = Startup.mkTestBootCxt("gedraCfgReload", "gedraCfgReloadTest")

    fun asClient(client: String): KdrCxt = cxt.mkSubContext("reload", client).also { it.userId = 9000L }
    fun schema() = SchemaService.get(cxt)
    fun ns(client: String) = "${client}config"

    /** Stores a config for [client] defining it and declaring the named traits, then reloads the client. */
    fun storeAndReload(client: String, vararg traits: String) {
        val config = gedraConfig(cxt, "${client}cfg", ns(client), client) {
            defineClient(
                ClientDef(
                    clientId = client, name = client, usageType = ClientUsageType.dev,
                    audience = ClientAudience.internal, enabledEnvironments = setOf(ENV.unit, ENV.local),
                ),
            )
            for (t in traits) {
                trait("${t}Entry", t, setOf(GedraDataType.formDoc), "The $t trait.") {
                    property("text", "A value.", required = true)
                }
            }
        }
        GedraConfigService.get(cxt).writeConfig(asClient(client), config)
        GedraConfigReload.reloadClient(cxt, client)
    }

    "a reload changes what a client's schema advertises and validates against" {
        val client = "reloadone"
        storeAndReload(client, "rlAlpha")
        schema().gedraTraitsFor(client).map { it.traitId } shouldContain "rlAlpha"
        schema().storeFor(client).types.keys shouldContain "${ns(client)}.rlAlphaEntry"
        ClientService.get(cxt).known(client).shouldNotBeNull()

        // A new revision adds a trait; after the reload the client's variant carries it -- and only the
        // client's: the global store never learns a client's generated type.
        storeAndReload(client, "rlAlpha", "rlBeta")
        schema().gedraTraitsFor(client).map { it.traitId } shouldContain "rlBeta"
        schema().storeFor(client).types.keys shouldContain "${ns(client)}.rlBetaEntry"
        schema().schemaStore.types.keys shouldNotContain "${ns(client)}.rlBetaEntry"
        GedraConfigLoadService.get(cxt).loadedFor(client).size shouldBe 1
    }

    "a request in flight keeps the store it started with" {
        val client = "reloadflight"
        storeAndReload(client, "rlGamma")
        // A request memoizes the global store on first read; a reload publishes a new one, and this request
        // goes on seeing the old.
        val request = cxt.mkSubContext("inflight", client)
        val started = request.getSchema()
        storeAndReload(client, "rlGamma", "rlDelta")
        request.getSchema() shouldBeSameInstanceAs started
        schema().schemaStore shouldNotBeSameInstanceAs started
    }

    "a second client is untouched by the first's reload" {
        val a = "reloada"
        val b = "reloadb"
        storeAndReload(a, "rlA1")
        storeAndReload(b, "rlB1")
        val bTypes = schema().storeFor(b).types
        val bTraits = schema().gedraTraitsFor(b).map { it.traitId }

        storeAndReload(a, "rlA1", "rlA2")
        // Every store is re-wrapped with the new endpoint map, but B's parsed types are reused by reference
        // and its traits are exactly what they were.
        schema().storeFor(b).types shouldBeSameInstanceAs bTypes
        schema().gedraTraitsFor(b).map { it.traitId } shouldBe bTraits
        schema().gedraTraitsFor(b).map { it.traitId } shouldNotContain "rlA2"
    }

    "a reload that fails its checks leaves the node as it was" {
        val victim = "reloadvictim"
        val owner = "reloadowner"
        storeAndReload(owner, "rlShared")
        storeAndReload(victim, "rlMine")
        val before = schema().gedraTraitsFor(victim).map { it.traitId }

        // The victim's next revision claims a trait id the owner already holds: the collector refuses it, and in
        // the unit environment a config problem is strict, so the reload throws -- before anything is
        // published, and with the collector rolled back to the victim's previous configuration.
        shouldThrow<KdrException> { storeAndReload(victim, "rlMine", "rlShared") }
        schema().gedraTraitsFor(victim).map { it.traitId } shouldBe before
        GedraConfigLoadService.get(cxt).loadedFor(victim).size shouldBe 1
        schema().gedraTraitsFor(owner).map { it.traitId } shouldContain "rlShared"
    }

    // Its own database: an admin created without a client lands in `public`, and on the default in-memory
    // database shared across specs `public` accumulates other specs' stored configs -- several declaring one
    // cfact, which a reload rightly refuses. The endpoint is what is under test here, not that leftover.
    "the endpoint reloads the caller's own client" {
        val own = Startup.mkTestBootCxt("gedraCfgReloadEp", "gedraCfgReloadEpTest", mapOf("KDR_DB_NAME" to "cfgReload_endpoint"))
        val admin = TestUser.create(own, "reloadadmin@example.com", level = ROLE.admin)
        val result = admin.postData(CFEP.reload, emptyMap())
        result[CFEP.client] shouldBe admin.selfClient()
        (result[CFEP.loaded] as Number).toInt() shouldBe GedraConfigLoadService.get(own).loadedFor(admin.selfClient()!!).size
    }
})
