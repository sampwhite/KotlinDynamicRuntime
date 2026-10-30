package com.dynamicruntime.kdn

import com.dynamicruntime.common.context.ENV
import com.dynamicruntime.common.context.KdrCxt
import com.dynamicruntime.common.context.ReadScope
import com.dynamicruntime.common.exception.KdrException
import com.dynamicruntime.common.gedra.ClientAudience
import com.dynamicruntime.common.gedra.ClientDef
import com.dynamicruntime.common.gedra.ClientUsageType
import com.dynamicruntime.common.gedra.ConfigImpact
import com.dynamicruntime.common.gedra.GE
import com.dynamicruntime.common.gedra.GedraConfigReload
import com.dynamicruntime.common.gedra.GedraConfigService
import com.dynamicruntime.common.gedra.GedraConfigType
import com.dynamicruntime.common.gedra.GedraDataRow
import com.dynamicruntime.common.gedra.GedraDataService
import com.dynamicruntime.common.gedra.GedraDataType
import com.dynamicruntime.common.gedra.GedraEdit
import com.dynamicruntime.common.gedra.GedraEditAction
import com.dynamicruntime.common.gedra.GedraId
import com.dynamicruntime.common.gedra.GedraPatchTarget
import com.dynamicruntime.common.gedra.ImpactKind
import com.dynamicruntime.common.gedra.gedraConfig
import com.dynamicruntime.common.naming.clientNamespace
import com.dynamicruntime.common.schema.SCT
import com.dynamicruntime.common.schema.computeValue
import com.dynamicruntime.common.util.toJsonMapOrEmpty
import com.dynamicruntime.common.util.toOptStr
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain

/**
 * A type's `g-onWrite` functions on a running node (issue #1501): a client's own trait computing a stored, derived
 * total on every write path, a changed script reaching stored forms through the recompute, the publish impact report
 * counting the forms it would change, and a usage that cannot run refused when the configuration loads. Each scenario
 * in a client of its own on the shared instance.
 */
class SchemaWriteFunctionsTest : StringSpec({
    val cxt = TestInstances.default("schemaWriteFunctions")
    val trait = "expense"

    fun asClient(client: String): KdrCxt = cxt.mkSubContext("onWrite", client).also { it.userId = 9150L }

    /** Writes [client]'s configuration: a trait with two amounts and a total its [script] computes, then reloads it. */
    fun writeClient(client: String, script: String = "a * b", target: String = "total", reload: Boolean = true) {
        val config = gedraConfig(cxt, "${client}cfg", clientNamespace(client), client) {
            defineClient(
                ClientDef(
                    clientId = client, name = client, usageType = ClientUsageType.dev,
                    audience = ClientAudience.internal, enabledEnvironments = setOf(ENV.unit, ENV.local),
                ),
            )
            trait("ExpenseEntry", trait, setOf(GedraDataType.formDoc), "An expense.") {
                property("a", "Amount per item.") { type = SCT.number }
                property("b", "How many items.") { type = SCT.number }
                property("total", "The total; computed, not supplied.") {
                    type = SCT.number
                    derived = true
                }
                onWrite(computeValue(target, script))
            }
        }
        GedraConfigService.get(cxt).writeConfig(asClient(client), config)
        if (reload) GedraConfigReload.reloadClient(cxt, client)
    }

    fun service() = GedraDataService.get(cxt)
    fun entry(data: Map<String, Any?>) = mapOf(GE.traitId to trait, GE.data to data)
    fun create(client: String, data: Map<String, Any?>): GedraDataRow =
        service().createGedra(asClient(client), GedraDataType.formDoc, listOf(entry(data)))
    fun read(client: String, gid: GedraId): GedraDataRow =
        service().queryGedra(asClient(client), gid.fullId, GedraDataType.formDoc, ReadScope.ofClient(client))!!
    fun stored(row: GedraDataRow): Map<String, Any?> =
        row.entries.first { it[GE.traitId].toOptStr() == trait }[GE.data].toJsonMapOrEmpty()
    fun merge(client: String, gid: GedraId, data: Map<String, Any?>) {
        val target = GedraPatchTarget(gid, listOf(GedraEdit(GedraEditAction.addOrMerge, trait, data = data)))
        service().patchGedras(asClient(client), mapOf(GedraDataType.formDoc to listOf(target)), ReadScope.ofClient(client))
    }

    "the total is stored on create and follows every merge" {
        val client = "onwrite1501a"
        writeClient(client)
        val gid = create(client, mapOf("a" to 12.5, "b" to 4.0)).gedraId
        stored(read(client, gid))["total"] shouldBe 50.0

        // A merge carries only what it changes; the total is computed from the merged result.
        merge(client, gid, mapOf("b" to 6.0))
        stored(read(client, gid))["total"] shouldBe 75.0

        // An input cleared clears the total, rather than leaving the old one standing.
        merge(client, gid, mapOf("b" to null))
        stored(read(client, gid))["total"].shouldBeNull()
    }

    "a changed script reaches stored forms through the recompute, and the impact report counts them first" {
        val client = "onwrite1501b"
        writeClient(client)
        val gid = create(client, mapOf("a" to 2.0, "b" to 3.0)).gedraId
        stored(read(client, gid))["total"] shouldBe 6.0

        // Written, not yet reloaded: the report judges the candidate against what the client runs.
        writeClient(client, script = "a * b * 10", reload = false)
        val configId = GedraId.of(GedraConfigType.configDoc, client, "${client}cfg")
        val latest = GedraConfigService.get(cxt).readLatest(asClient(client), configId)!!
        val report = ConfigImpact.reportFor(cxt, client, listOf(latest), anyTier = true)
        report.findings.map { it.kind } shouldContain ImpactKind.valuesRecomputed

        // Reloaded: stored values are stale until a write or the recompute.
        GedraConfigReload.reloadClient(cxt, client)
        val row = read(client, gid)
        service().computedDataCurrent(cxt, row) shouldBe false
        service().recomputeDerivedState(cxt, row)
        val after = read(client, gid)
        stored(after)["total"] shouldBe 60.0
        service().computedDataCurrent(cxt, after) shouldBe true
    }

    "a usage that cannot run on its type is refused when the configuration loads" {
        val client = "onwrite1501c"
        // 'a' is an input, not derived: a caller supplies it, and the function would overwrite what they sent.
        val e = shouldThrow<KdrException> { writeClient(client, target = "a") }
        e.message.orEmpty() shouldContain "is not 'g-derived'"
    }
})
