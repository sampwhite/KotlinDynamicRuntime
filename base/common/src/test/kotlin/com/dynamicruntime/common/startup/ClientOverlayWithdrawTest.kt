package com.dynamicruntime.common.startup

import com.dynamicruntime.common.cfact.CFactDef
import com.dynamicruntime.common.context.ENV
import com.dynamicruntime.common.context.KdrCxt
import com.dynamicruntime.common.context.KdrInstanceConfig
import com.dynamicruntime.common.gedra.GedraConfigOrigin
import com.dynamicruntime.common.gedra.gedraConfig
import com.dynamicruntime.common.naming.clientNamespace
import com.dynamicruntime.common.schema.SCH
import com.dynamicruntime.common.schema.SCT
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe

/**
 * Two configs of one client declaring one type name (issue #1014), driven through the collector the boot and a
 * reload use: what the client's overlay map holds after each is added and withdrawn.
 */
class ClientOverlayWithdrawTest : StringSpec({
    val cxt = KdrCxt("withdraw", KdrInstanceConfig("withdraw-1014", ENV.local, ENV.liveSource))
    val client = "acme1014"
    val typeName = "client.$client.Thing"

    fun config(name: String, origin: GedraConfigOrigin, description: String) =
        gedraConfig(cxt, name, clientNamespace(client), client, origin) {
            type("Thing") {
                type = SCT.kObject
                this.description = description
                property("x", "X.")
            }
            cfact("ready", "status", description)
        }
    fun held(collector: SchemaCollector): Any? =
        (collector.clientOverlays[client]?.get(typeName) as? Map<*, *>)?.get(SCH.description)

    "withdrawing a stored config leaves a source declaration of the same type in place" {
        val collector = SchemaCollector()
        val source = config("main", GedraConfigOrigin.source, "From source.")
        val stored = config("extra", GedraConfigOrigin.stored, "From storage.")
        collector.addGedraConfig(cxt, source) shouldBe true
        collector.addGedraConfig(cxt, stored) shouldBe true
        // Both held: the later declaration is the one the client sees, as before.
        held(collector) shouldBe "From storage."
        collector.removeGedraConfig(stored) shouldBe true
        held(collector) shouldBe "From source."
        // The cfacts are rebuilt the same way: the source config's own stays.
        collector.clientCFacts[client].orEmpty().map(CFactDef::description) shouldBe listOf("From source.")
    }

    "withdrawing the last config of a client leaves nothing of it behind" {
        val collector = SchemaCollector()
        val stored = config("extra", GedraConfigOrigin.stored, "From storage.")
        collector.addGedraConfig(cxt, stored) shouldBe true
        collector.removeGedraConfig(stored) shouldBe true
        collector.clientOverlays.containsKey(client) shouldBe false
        collector.clientCFacts.containsKey(client) shouldBe false
    }
})
