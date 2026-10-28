package com.dynamicruntime.kdn

import com.dynamicruntime.common.http.request.ROLE
import com.dynamicruntime.common.simulation.Simulations
import com.dynamicruntime.common.user.PERSONA
import com.dynamicruntime.kdn.confdemo.ConfDemo
import com.dynamicruntime.kdn.confdemo.confDemoConfigs
import com.dynamicruntime.kdn.confdemo.provisionConfDemo
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe

/**
 * The conference demo's simulation provisions its committee member as an editor (issue #1455) -- on a fresh copy, and
 * over one an earlier run made an administrator, which a persistent store (a developer's Postgres, a reference node
 * provisioning at boot) still holds. Each case on a copy of its own (`confdemo<suffix>`).
 */
class ConfDemoProvisionTest : StringSpec({
    val cxt = TestInstances.default("confDemoProvision1455")

    /** The committee member of [client] as stored: found, never made, by the simulation's own lookup. */
    fun committeeOf(client: String) = Simulations.provisionUser(cxt, "committee@$client.example", client, ROLE.user)

    "the committee member a fresh run provisions is an editor carrying the committee label" {
        val report = provisionConfDemo(cxt, "p1455a")
        val reviewer = committeeOf(report.clients.single())
        reviewer.persona shouldBe PERSONA.editor
        reviewer.roles shouldBe listOf(ROLE.user, ROLE.editor)
        reviewer.labels shouldBe listOf(ConfDemo.committeeLabel)
        // What the Simulations page signs in with asks for the same.
        val reported = report.users.single { it.email == reviewer.primaryId }
        reported.level shouldBe ROLE.user
        reported.capabilities shouldBe listOf(ROLE.editor)
    }

    "a run over a committee member made an administrator before makes it an editor, keeping its persona" {
        val client = ConfDemo.client + "p1455b"
        for (config in confDemoConfigs(cxt, client)) Simulations.provisionConfig(cxt, config)
        // As the simulation provisioned it before issue #1455.
        Simulations.provisionUser(cxt, "committee@$client.example", client, ROLE.admin, name = "Casey Committee")
        provisionConfDemo(cxt, "p1455b")
        val reviewer = committeeOf(client)
        reviewer.persona shouldBe PERSONA.admin
        reviewer.roles shouldBe listOf(ROLE.user, ROLE.editor)
        reviewer.labels shouldBe listOf(ConfDemo.committeeLabel)
    }
})
