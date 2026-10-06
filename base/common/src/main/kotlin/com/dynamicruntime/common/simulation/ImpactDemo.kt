package com.dynamicruntime.common.simulation

import com.dynamicruntime.common.context.ENV
import com.dynamicruntime.common.context.KdrCxt
import com.dynamicruntime.common.context.KdrCxtBase
import com.dynamicruntime.common.gedra.ClientAudience
import com.dynamicruntime.common.gedra.ClientDef
import com.dynamicruntime.common.gedra.ClientUsageType
import com.dynamicruntime.common.gedra.GE
import com.dynamicruntime.common.gedra.GedraConfig
import com.dynamicruntime.common.gedra.GedraDataType
import com.dynamicruntime.common.gedra.gedraConfig
import com.dynamicruntime.common.gedra.sandboxOf
import com.dynamicruntime.common.home.HMENU
import com.dynamicruntime.common.http.request.ROLE
import com.dynamicruntime.common.naming.clientNamespace

/**
 * The publish impact demo (issue #935): a client with a Shadow Sandbox -- so it runs only what it publishes -- forms
 * stored under both of its traits, and an unpublished **draft** of its traits that would strand them: it drops
 * [visit] and makes a [note] require an `owner` no stored note has. Publishing that draft from the client page is
 * refused with the impact report, which the page shows with "Publish anyway"; "Check impact" shows it first.
 */
@Suppress("ConstPropertyName")
object ImpactDemo {
    const val client = "impactdemo"

    /** The simulation that provisions it: `/fixture/simulate/impact-demo`. */
    const val simulationName = "impact-demo"

    /** The configuration holding the client's definition. */
    const val main = "main"

    /** The configuration holding its traits, the one left with a harmful draft. */
    const val traits = "traits"

    const val note = "note"
    const val visit = "visit"
}

/** The demo client's definition: with a sandbox, so it runs only what it publishes. */
fun impactDemoMain(cxt: KdrCxtBase, client: String = ImpactDemo.client): GedraConfig =
    gedraConfig(cxt, ImpactDemo.main, clientNamespace(client), client) {
        defineClient(
            ClientDef(
                clientId = client, name = "Impact demo", usageType = ClientUsageType.demo,
                audience = ClientAudience.internal, enabledEnvironments = setOf(ENV.unit, ENV.local, ENV.dev), sandbox = true,
            ),
        )
    }

/**
 * The demo's traits: [ImpactDemo.note], and [ImpactDemo.visit] unless [harmful] -- which drops `visit` and makes a
 * note require an `owner` every stored note lacks.
 */
fun impactDemoTraits(cxt: KdrCxtBase, client: String = ImpactDemo.client, harmful: Boolean = false): GedraConfig =
    gedraConfig(cxt, ImpactDemo.traits, clientNamespace(client), client) {
        trait("NoteEntry", ImpactDemo.note, setOf(GedraDataType.formDoc), "A note.") {
            property("text", "What it says.")
            if (harmful) property("owner", "Who owns the note.", required = true)
        }
        if (!harmful) {
            trait("VisitEntry", ImpactDemo.visit, setOf(GedraDataType.formDoc), "A site visit.") { property("site", "Where.") }
        }
    }

/**
 * Provisions the impact demo (issue #935): the client `impactdemo<suffix>` with its two configurations published,
 * three forms holding its traits, then the harmful draft of its traits, left unpublished. A rerun first puts the
 * harmless traits back and publishes them, so it is safe to repeat; the forms accumulate.
 */
fun provisionImpactDemo(cxt: KdrCxt, suffix: String? = null): SimulationReport {
    val client = ImpactDemo.client + Simulations.checkedSuffix(suffix)
    // The traits before the definition: once a published definition asks for a sandbox, the client runs only what
    // is published, so both are published before the forms are made.
    Simulations.provisionConfig(cxt, impactDemoTraits(cxt, client))
    Simulations.provisionConfig(cxt, impactDemoMain(cxt, client))
    Simulations.publishConfig(cxt, client, ImpactDemo.traits)
    Simulations.publishConfig(cxt, client, ImpactDemo.main)

    val owner = Simulations.provisionUser(cxt, "pat@$client.example", client, ROLE.user, name = "Pat Owner")
    fun entry(traitId: String, field: String, value: String) = mapOf(GE.traitId to traitId, GE.data to mapOf(field to value))
    Simulations.createForm(cxt, owner, listOf(entry(ImpactDemo.visit, "site", "North yard"), entry(ImpactDemo.note, "text", "Gate code changed.")))
    Simulations.createForm(cxt, owner, listOf(entry(ImpactDemo.visit, "site", "Dock 4")))
    Simulations.createForm(cxt, owner, listOf(entry(ImpactDemo.note, "text", "Nothing to report.")))

    // The draft: written and reloaded, so the sandbox runs it while the client keeps what it published.
    Simulations.provisionConfig(cxt, impactDemoTraits(cxt, client, harmful = true), publish = false)

    val admin = Simulations.provisionUser(cxt, "chief@$client.example", client, ROLE.admin, name = "Casey Chief")
    return SimulationReport(
        clients = listOf(client, sandboxOf(client)),
        users = listOf(
            Simulations.reported(
                admin, ROLE.admin,
                "the administrator -- on the client's page, follow \"Publish from its sandbox\", then Check impact or " +
                    "Publish on '${ImpactDemo.traits}'",
            ),
            Simulations.reported(owner, ROLE.user, "the owner of the stored forms"),
        ),
        startPage = "page=${HMENU.pageClients}&c=$client",
        summary = "Client '$client' has a draft of '${ImpactDemo.traits}' that would strand forms it stores. Sign in as " +
            "the administrator and publish it from the sandbox.",
    )
}
