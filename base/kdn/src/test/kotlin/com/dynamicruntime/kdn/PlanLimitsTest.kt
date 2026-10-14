package com.dynamicruntime.kdn

import com.dynamicruntime.common.context.ENV
import com.dynamicruntime.common.context.KdrCxt
import com.dynamicruntime.common.exception.KdrException
import com.dynamicruntime.common.gedra.CLD
import com.dynamicruntime.common.gedra.CUST
import com.dynamicruntime.common.gedra.ClientAudience
import com.dynamicruntime.common.gedra.ClientUsageType
import com.dynamicruntime.common.gedra.CustomerDef
import com.dynamicruntime.common.gedra.CustomerStore
import com.dynamicruntime.common.gedra.GedraConfig
import com.dynamicruntime.common.gedra.GedraConfigReload
import com.dynamicruntime.common.gedra.GedraConfigService
import com.dynamicruntime.common.gedra.GedraDataService
import com.dynamicruntime.common.gedra.GedraDataType
import com.dynamicruntime.common.gedra.PLAN
import com.dynamicruntime.common.gedra.PlanUsage
import com.dynamicruntime.common.gedra.QUOTA
import com.dynamicruntime.common.gedra.QuotaMeasure
import com.dynamicruntime.common.gedra.gedraConfig
import com.dynamicruntime.common.gedra.workflow.WfEntry
import com.dynamicruntime.common.gedra.workflow.WfSaveKind
import com.dynamicruntime.common.http.request.ROLE
import com.dynamicruntime.common.naming.clientNamespace
import com.dynamicruntime.common.user.ADEP
import com.dynamicruntime.common.user.TestUser
import com.dynamicruntime.common.user.UADEP
import com.dynamicruntime.common.user.UserService
import com.dynamicruntime.common.util.toJsonMapOrEmpty
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain

/**
 * A client held to its plan (issue #1109): its configuration by the write's trial, its forms and users when one is
 * created -- and only what would add to it, so a client already over may still change what it has. Over the shared
 * instance, with clients of this issue's own.
 */
class PlanLimitsTest : StringSpec({
    val cxt = TestInstances.default("plans1109")
    val operator = TestUser.createFullAdmin(cxt, "operator@plans1109.test")

    fun create(clientId: String, vararg more: Pair<String, Any?>) = operator.postData(
        ADEP.clientCreate,
        mapOf(
            CLD.clientId to clientId, CLD.name to "Client $clientId", CLD.usageType to ClientUsageType.dev.name,
            CLD.audience to ClientAudience.customer.name, CLD.enabledEnvironments to listOf(ENV.unit, ENV.local),
        ) + more,
    )
    fun asClient(client: String): KdrCxt = cxt.mkSubContext("plans", client).also { it.userId = 11090L }

    /** [client]'s workflows config: a trait, and [workflows] normal workflows of [tasks] tasks each. */
    fun workflows(client: String, workflows: Int, tasks: Int = 1, label: String = "W"): GedraConfig =
        gedraConfig(cxt, "flows", clientNamespace(client), client) {
            trait("QEntry", "q", setOf(GedraDataType.formDoc)) { property("a", "A.") }
            for (w in 1..workflows) {
                workflow("w$w", WfEntry.normal) {
                    this.label = "$label $w"
                    for (t in 1..tasks) {
                        task("t$t", "T$t") {
                            trait("q")
                            save("s$t", "S$t", WfSaveKind.edit)
                        }
                    }
                }
            }
        }
    fun write(config: GedraConfig) {
        GedraConfigService.get(cxt).writeConfig(asClient(config.gedraId.client), config, trial = true)
        GedraConfigReload.reloadClient(cxt, config.gedraId.client)
    }
    fun refusal(config: GedraConfig): String =
        shouldThrow<KdrException> { GedraConfigService.get(cxt).writeConfig(asClient(config.gedraId.client), config, trial = true) }.message.orEmpty()

    "a self-serve client is on the free plan: its configuration is held to it at the write" {
        val client = "${CUST.free}_cfg1109"
        create(client)
        write(workflows(client, 4))
        refusal(workflows(client, 5)) shouldContain
            "Client '$client' is on the free plan, and this change would take it over: 5 workflows (the plan allows 4)"
        refusal(workflows(client, 1, tasks = 3)) shouldContain "workflow 'w1' with 3 tasks (the plan allows 2)"
    }

    "a client over its plan may still change what it has, but not add to it" {
        val client = "cap1109"
        create(client, CLD.newCustomer to mapOf(CUST.name to "Cap"))
        write(workflows(client, 5)) // unlimited, as a customer naming no plan is
        // Its customer moves to the free plan: the client is now over, and keeps all it has.
        CustomerStore.put(cxt, CustomerDef(client, "Cap", rootClientId = client, plan = PLAN.free))
        write(workflows(client, 5, label = "Renamed"))
        write(workflows(client, 4))
        refusal(workflows(client, 5)) shouldContain "5 workflows (the plan allows 4)"
    }

    "forms and users are counted when one is made, and the next one past the plan is refused" {
        val client = "${CUST.free}_data1109"
        create(client)
        val owner = asClient(client)
        repeat(PLAN.freeQuotas.forms!!) { GedraDataService.get(cxt).createGedra(owner, GedraDataType.formDoc, emptyList()) }
        shouldThrow<KdrException> { GedraDataService.get(cxt).createGedra(owner, GedraDataType.formDoc, emptyList()) }
            .message.orEmpty() shouldContain "Client '$client' is on the free plan and holds 100 forms, as many as its plan allows (100)"

        val users = UserService.get(cxt)
        repeat(PLAN.freeQuotas.users!!) { users.provisionUser(cxt, "u$it@data1109.test", client, listOf(ROLE.user)) }
        shouldThrow<KdrException> { users.provisionUser(cxt, "over@data1109.test", client, listOf(ROLE.user)) }
            .message.orEmpty() shouldContain "holds 20 users, as many as its plan allows (20)"
    }

    "the Clients overview says a client's plan and its usage, and nothing for a client on none" {
        val free = "${CUST.free}_view1109"
        val plain = "plain1109"
        create(free)
        create(plain)
        write(workflows(free, 2))
        val rows = operator.getItems(UADEP.clientsOverview).associateBy { it[CLD.clientId] }
        PlanUsage.fromInfo(rows.getValue(free)[CLD.planUsage].toJsonMapOrEmpty()) shouldBe PlanUsage(
            PLAN.free,
            listOf(
                QuotaMeasure(QUOTA.forms, 0, 100), QuotaMeasure(QUOTA.users, 0, 20),
                QuotaMeasure(QUOTA.workflows, 2, 4), QuotaMeasure(QUOTA.schemas, 1, 10),
            ),
        )
        rows.getValue(plain).containsKey(CLD.planUsage) shouldBe false
    }
})
