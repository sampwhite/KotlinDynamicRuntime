package com.dynamicruntime.kdn

import com.dynamicruntime.common.context.ENV
import com.dynamicruntime.common.context.KdrSchemaStore
import com.dynamicruntime.common.gedra.ClientAudience
import com.dynamicruntime.common.gedra.ClientDef
import com.dynamicruntime.common.gedra.ClientUsageType
import com.dynamicruntime.common.gedra.GCFG
import com.dynamicruntime.common.gedra.GU
import com.dynamicruntime.common.gedra.GedraConfigReload
import com.dynamicruntime.common.gedra.GedraConfigService
import com.dynamicruntime.common.gedra.GedraConfigType
import com.dynamicruntime.common.gedra.GedraDataType
import com.dynamicruntime.common.gedra.GedraId
import com.dynamicruntime.common.gedra.gedraConfig
import com.dynamicruntime.common.naming.clientNamespace
import com.dynamicruntime.common.startup.SchemaService
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeSameInstanceAs
import io.kotest.matchers.types.shouldNotBeSameInstanceAs

/**
 * The context's two schema reads (issue #946): `getGlobalSchema` is the store every client shares, and
 * `getClientSchema` the store of the client the context is **bound** to -- a client's variant, following a re-bind,
 * and dropped with the global one when that is reset.
 */
class ClientSchemaTest : StringSpec({
    val cxt = Startup.mkTestBootCxt("clientSchema946", "clientSchema946")
    val client = "cschema946"
    val union = "${GCFG.globalNamespace}.${GU.unionName(GedraDataType.formDoc)}"

    // A client whose stored configuration adds a trait, so its schema is a variant of the global one.
    val setup = cxt.mkSubContext("setup", client).also { it.userId = 9460L }
    val svc = GedraConfigService.get(cxt)
    svc.writeConfig(
        setup,
        gedraConfig(cxt, "main", clientNamespace(client), client) {
            defineClient(
                ClientDef(
                    clientId = client, name = client, usageType = ClientUsageType.dev, audience = ClientAudience.internal,
                    enabledEnvironments = setOf(ENV.unit, ENV.local),
                ),
            )
            trait("NoteEntry", "note946", setOf(GedraDataType.formDoc), "A note.") { property("text", "What it says.") }
        },
    )
    svc.publish(setup, GedraId.of(GedraConfigType.configDoc, client, "main"))
    GedraConfigReload.reloadClient(cxt, client)

    fun knowsNote(store: KdrSchemaStore): Boolean = store.types[union]?.variants?.isKnown("note946") == true

    "a context bound to a client that varies the schema reads that client's variant" {
        val bound = cxt.mkSubContext("bound", client)
        val schema = SchemaService.get(cxt)
        bound.getClientSchema() shouldBeSameInstanceAs schema.storeFor(client)
        knowsNote(bound.getClientSchema()) shouldBe true
        // The global store is the one every client shares, and knows nothing of the client's trait.
        bound.getGlobalSchema() shouldBeSameInstanceAs schema.schemaStore
        knowsNote(bound.getGlobalSchema()) shouldBe false
        bound.getClientSchema() shouldNotBeSameInstanceAs bound.getGlobalSchema()
    }

    "re-binding the context's client re-resolves; a client that varies nothing reads the global store" {
        val bound = cxt.mkSubContext("rebound", client)
        knowsNote(bound.getClientSchema()) shouldBe true
        bound.client = "nobody946"
        bound.getClientSchema() shouldBeSameInstanceAs bound.getGlobalSchema()
        bound.client = client
        knowsNote(bound.getClientSchema()) shouldBe true
        // A sub context for another client does not inherit the cached store.
        bound.mkSubContext("other", "nobody946").getClientSchema() shouldBeSameInstanceAs bound.getGlobalSchema()
    }

    "dropping the cached global store drops the client store with it" {
        val bound = cxt.mkSubContext("reset", client)
        val before = bound.getClientSchema()
        // A reload publishes a fresh variant; the context keeps what it read until its cache is reset.
        GedraConfigReload.reloadClient(cxt, client)
        bound.getClientSchema() shouldBeSameInstanceAs before
        bound.schemaStore = null
        bound.getClientSchema() shouldBeSameInstanceAs SchemaService.get(cxt).storeFor(client)
        bound.getClientSchema() shouldNotBeSameInstanceAs before
    }
})
