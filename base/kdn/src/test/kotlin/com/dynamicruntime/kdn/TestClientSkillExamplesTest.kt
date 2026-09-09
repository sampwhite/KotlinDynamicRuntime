package com.dynamicruntime.kdn

import com.dynamicruntime.common.context.ENV
import com.dynamicruntime.common.context.KdrCxt
import com.dynamicruntime.common.exception.EXC
import com.dynamicruntime.common.gedra.ClientAudience
import com.dynamicruntime.common.gedra.ClientDef
import com.dynamicruntime.common.gedra.ClientService
import com.dynamicruntime.common.gedra.ClientUsageType
import com.dynamicruntime.common.gedra.GDF
import com.dynamicruntime.common.gedra.GE
import com.dynamicruntime.common.gedra.GEP
import com.dynamicruntime.common.gedra.GedraConfigReload
import com.dynamicruntime.common.gedra.GedraConfigService
import com.dynamicruntime.common.gedra.GedraDataType
import com.dynamicruntime.common.gedra.gedraConfig
import com.dynamicruntime.common.startup.SchemaService
import com.dynamicruntime.common.user.TestUser
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldNotContain
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe

/**
 * Holds `.claude/skills/kdr-test-clients` to the code, by running its worked example and checking the claims it
 * makes about the result -- the same guard the other `*SkillExamplesTest` classes give their skills.
 *
 * A skill is read *instead of* working the API out, so a wrong one is followed rather than noticed. Keep the
 * code below a faithful transcription of the skill's example; do not "improve" it past what the skill shows.
 * The technique itself (reload semantics, isolation) is covered by `GedraConfigReloadTest`; this pins the
 * documentation, including the reload -> create-user chain that is the skill's headline.
 */
class TestClientSkillExamplesTest : StringSpec({
    // One shared instance for the whole spec -- the technique is to create a client per scenario, not a node.
    val cxt = Startup.mkTestBootCxt("skillTestClients", "skillTestClients")

    // A write is attributed, so bind a sub-context to the client with a userId before writing its config.
    fun asClient(client: String): KdrCxt = cxt.mkSubContext("setup", client).also { it.userId = 9000L }

    // Create a client dynamically: define it, give it a trait, persist the bundle, and reload it live.
    fun createClient(client: String, trait: String) {
        val config = gedraConfig(cxt, "${client}cfg", "${client}config", client) {
            defineClient(
                ClientDef(
                    clientId = client, name = client, usageType = ClientUsageType.dev,
                    audience = ClientAudience.internal, enabledEnvironments = setOf(ENV.unit, ENV.local),
                ),
            )
            trait("${trait}Entry", trait, setOf(GedraDataType.formDoc), "The $trait trait.") {
                property("text", "A value.", required = true)
            }
        }
        GedraConfigService.get(cxt).writeConfig(asClient(client), config)
        GedraConfigReload.reloadClient(cxt, client)
    }

    "a dynamically created client is isolated, and a user can be placed in it" {
        createClient("scenarioA", "alphaTrait")
        createClient("scenarioB", "betaTrait")

        val schema = SchemaService.get(cxt)
        // Each client's own schema carries its trait -- and only its own; the reload made it live with no restart.
        schema.gedraTraitsFor("scenarioA").map { it.traitId } shouldContain "alphaTrait"
        schema.gedraTraitsFor("scenarioA").map { it.traitId } shouldNotContain "betaTrait"
        ClientService.get(cxt).known("scenarioA").shouldNotBeNull()

        // A user placed in the new client lands there; its form docs, workflows and data are then isolated by
        // that client segment in their ids and the scope-to-SQL confinement -- a clean namespace, no new node.
        val user = TestUser.create(cxt, "u@scenarioA.test", userClient = "scenarioA")
        user.selfClient() shouldBe "scenarioA"
    }

    // Narrowing a schema *after* data is captured, on one running node with no restart -- the reload replaces
    // the old two-build persistent-H2 dance. A value valid at capture is rejected once the reload makes a
    // narrower revision live.
    "a config reload narrows a live schema, so a captured value is then rejected -- no restart" {
        val client = "narrowcap"

        // Author the client's `topic` field with the given options, and reload it live.
        fun writeClient(vararg topics: String) {
            val config = gedraConfig(cxt, "${client}cfg", "${client}config", client) {
                defineClient(
                    ClientDef(
                        clientId = client, name = client, usageType = ClientUsageType.dev,
                        audience = ClientAudience.internal, enabledEnvironments = setOf(ENV.unit, ENV.local),
                    ),
                )
                trait("QEntry", "q", setOf(GedraDataType.formDoc), "The q trait.") {
                    property("topic", "Topic") { for (t in topics) option(t) }
                }
            }
            GedraConfigService.get(cxt).writeConfig(asClient(client), config)
            GedraConfigReload.reloadClient(cxt, client)
        }

        writeClient("alpha", "beta")
        val user = TestUser.create(cxt, "u@$client.test", userClient = client)
        val entry = mapOf(GE.traitId to "q", GE.data to mapOf("topic" to "alpha"))

        // Capture: a form doc with topic "alpha" is accepted and stored (the create does not throw).
        user.postData(GEP.formDocCreate, mapOf(GDF.entries to listOf(entry)))

        // Narrow the schema on the running node: drop "alpha", reload. No rebuild, no restart, no new instance.
        writeClient("beta")

        // The very value just captured is now rejected -- the narrowing is live.
        user.expectError(EXC.badInput, GEP.formDocCreate, mapOf(GDF.entries to listOf(entry)))
    }
})
