package com.dynamicruntime.common.gedra

import com.dynamicruntime.common.context.CL
import com.dynamicruntime.common.user.PERSONA
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain

/**
 * What the load holds a client's join roles to (issue #1248): a client a visitor must never join declares none, and
 * every role of one they may join gives a user of a real persona, a valid suffix and the client's own labels, unlike
 * any other role's.
 */
class JoinRoleFaultsTest : StringSpec({
    val admin = ClientJoinRole(PERSONA.admin, "Administrator", PERSONA.admin, "Manages it.")
    fun client(id: String = "demo1248", usage: ClientUsageType = ClientUsageType.demo, labels: List<String> = listOf("committee"), roles: List<ClientJoinRole>) =
        ClientDef(clientId = id, name = id, usageType = usage, audience = ClientAudience.internal, userLabels = labels, joinRoles = roles)

    "a demo client's well-formed roles pass, and a client with none has nothing to check" {
        val committee = ClientJoinRole("committee", "Committee", PERSONA.admin, "Reviews.", personaSuffix = "review", userLabels = listOf("committee"))
        joinRoleFaults(client(roles = listOf(admin, committee))).shouldBeEmpty()
        joinRoleFaults(client(id = CL.public, roles = emptyList())).shouldBeEmpty()
    }

    "public, hub, a sandbox and a production client are never joined" {
        for ((def, why) in listOf(
            client(id = CL.public, roles = listOf(admin)) to "where a visitor joins from",
            client(id = CL.hub, roles = listOf(admin)) to "the deployment's own client",
            client(id = sandboxOf("demo1248"), roles = listOf(admin)) to "a sandbox",
            client(usage = ClientUsageType.production, roles = listOf(admin)) to "production",
        )) {
            val faults = joinRoleFaults(def)
            faults shouldHaveSize 1
            faults.single() shouldContain why
        }
    }

    "each role must give a user of a real persona and suffix, with the client's labels, and no other role's user" {
        val faults = joinRoleFaults(
            client(
                roles = listOf(
                    admin,
                    admin.copy(name = "organizer"),
                    ClientJoinRole("owner", "Owner", "owner", "Owns it.", personaSuffix = "x"),
                    ClientJoinRole("odd", "Odd", PERSONA.member, "Odd.", personaSuffix = "not valid!"),
                    ClientJoinRole("labeled", "Labeled", PERSONA.member, "Labeled.", personaSuffix = "l", userLabels = listOf("siteLead")),
                    admin.copy(summary = "Again."),
                ),
            ),
        )
        faults.any { "join role 'admin' 2 times" in it } shouldBe true
        faults.any { "give the same user" in it && "'organizer'" in it } shouldBe true
        faults.any { "persona 'owner'" in it } shouldBe true
        faults.any { "suffix 'not valid!'" in it } shouldBe true
        faults.any { "'siteLead'" in it && "does not suggest" in it } shouldBe true
    }

    "a sandbox's derived definition takes none of its parent's join roles" {
        SandboxConfigs.deriveDef(client(roles = listOf(admin))).joinRoles.shouldBeEmpty()
    }

    "join roles round-trip through a stored definition" {
        val def = client(roles = listOf(admin, ClientJoinRole("committee", "Committee", PERSONA.admin, "Reviews.", "review", listOf("committee"))))
        ClientDef.fromInfo(def.toInfo()).joinRoles shouldBe def.joinRoles
        // And a definition written as data is held to their shape.
        readClientDef(com.dynamicruntime.common.context.LiteCxt(), def.toInfo()).failures.shouldBeEmpty()
    }
})
