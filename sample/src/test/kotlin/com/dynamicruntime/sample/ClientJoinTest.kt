package com.dynamicruntime.sample

import com.dynamicruntime.common.context.CL
import com.dynamicruntime.common.context.UPF
import com.dynamicruntime.common.exception.EXC
import com.dynamicruntime.common.flavor.FLV
import com.dynamicruntime.common.gedra.ClientService
import com.dynamicruntime.common.gedra.JOIN
import com.dynamicruntime.common.http.request.ROLE
import com.dynamicruntime.common.simulation.DesignDemo
import com.dynamicruntime.kdn.Startup
import com.dynamicruntime.common.user.ADEP
import com.dynamicruntime.common.user.AEP
import com.dynamicruntime.common.user.PERSONA
import com.dynamicruntime.common.user.TestUser
import com.dynamicruntime.common.user.UserService
import com.dynamicruntime.common.util.toJsonListOfMaps
import com.dynamicruntime.common.util.toJsonListOfStrings
import com.dynamicruntime.common.util.toOptLong
import com.dynamicruntime.kdn.confdemo.ConfDemo
import com.dynamicruntime.sample.gedra.SC
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldContainAll
import io.kotest.matchers.collections.shouldNotContain
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldContain

/**
 * Joining a demo client from `public` (issue #1248): on a reference deployment, a visitor becomes an administrator of
 * `acme` or `designdemo`, or a speaker, organizer or committee member of `confdemo` -- a user of their own identity in
 * that one shared client, with the session moved there. Nowhere else is it offered.
 *
 * Two instances of its own, each with the sample: one under the `reference` flavor, which offers joining and
 * provisions the two data-defined demos at boot, and one without it.
 */
class ClientJoinTest : StringSpec({
    val reference = Startup.mkTestBootCxt(
        "clientJoinRef", "clientJoinRef", mapOf(FLV.deployFlavorsEnvVar.name to FLV.reference, "KDR_LOAD_SAMPLE" to "true"),
        additionalComponents = listOf(SampleComponent()),
    )
    val plain = Startup.mkTestBootCxt(
        "clientJoinPlain", "clientJoinPlain", mapOf("KDR_LOAD_SAMPLE" to "true"), additionalComponents = listOf(SampleComponent()),
    )

    fun joinable(user: TestUser) = user.getItems(JOIN.joinablePath)
    fun roleOf(rows: List<Map<String, Any?>>, client: String, role: String): Map<String, Any?> =
        rows.first { it[JOIN.client] == client }[JOIN.roles].toJsonListOfMaps().first { it[JOIN.name] == role }
    fun join(user: TestUser, client: String, role: String) =
        user.postData(JOIN.joinPath, mapOf(JOIN.client to client, JOIN.role to role, JOIN.acknowledged to true))

    "the reference deployment carries all three demos, designdemo provisioned at boot" {
        val clients = ClientService.get(reference)
        clients.present(DesignDemo.client).shouldNotBeNull()
        clients.present(ConfDemo.client).shouldNotBeNull()
        clients.present(SC.acme).shouldNotBeNull()
    }

    "a visitor in public is offered each demo client, with its roles" {
        val visitor = TestUser.create(reference, "looker@join1248.test")
        visitor.selfClient() shouldBe CL.public
        val rows = joinable(visitor)
        rows.map { it[JOIN.client] } shouldContainAll listOf(SC.acme, DesignDemo.client, ConfDemo.client)
        rows.first { it[JOIN.client] == ConfDemo.client }[JOIN.roles].toJsonListOfMaps().map { it[JOIN.name] } shouldBe
            listOf(ConfDemo.speakerRole, ConfDemo.organizerRole, ConfDemo.committeeRole)
        // Not joined yet: no user held in any role.
        roleOf(rows, SC.acme, PERSONA.admin)[JOIN.userId] shouldBe null
    }

    "joining acme makes the visitor its administrator there, of their own identity, and moves the session" {
        val visitor = TestUser.create(reference, "admin@join1248.test")
        val identity = visitor.getData(AEP.selfInfo)[UPF.identityId]
        val result = join(visitor, SC.acme, PERSONA.admin)
        val info = visitor.getData(AEP.selfInfo)
        info[UPF.client] shouldBe SC.acme
        info[UPF.userId] shouldBe result[JOIN.userId]
        info[UPF.identityId] shouldBe identity
        val roles = info[UPF.roles].toJsonListOfStrings()
        roles shouldContain ROLE.admin
        // The persona's roles and nothing more: the platform's capability never comes with a join.
        roles shouldNotContain ROLE.allClients
        visitor.expectError(EXC.notAuthorized, ADEP.users)

        // Back in public, the joined role shows its user, and joining again finds the same one.
        val again = TestUser.create(reference, "admin@join1248.test", userClient = CL.public)
        roleOf(joinable(again), SC.acme, PERSONA.admin)[JOIN.userId] shouldBe result[JOIN.userId]
        join(again, SC.acme, PERSONA.admin)[JOIN.userId] shouldBe result[JOIN.userId]
    }

    "each conference-demo role is a user of its own, with the role's persona, suffix and labels" {
        val visitor = TestUser.create(reference, "conf@join1248.test")
        val committee = join(visitor, ConfDemo.client, ConfDemo.committeeRole)[JOIN.userId].toOptLong().shouldNotBeNull()
        val inPublic = TestUser.create(reference, "conf@join1248.test", userClient = CL.public)
        val organizer = join(inPublic, ConfDemo.client, ConfDemo.organizerRole)[JOIN.userId].toOptLong().shouldNotBeNull()
        organizer shouldNotBe committee
        val users = UserService.get(reference)
        val committeeRow = users.queryByUserId(reference, committee).shouldNotBeNull()
        committeeRow.persona shouldBe PERSONA.admin
        committeeRow.personaSuffix shouldBe ConfDemo.committeeSuffix
        committeeRow.labels shouldBe listOf(ConfDemo.committeeLabel)
        val organizerRow = users.queryByUserId(reference, organizer).shouldNotBeNull()
        organizerRow.personaSuffix shouldBe ""
        organizerRow.labels shouldBe emptyList()
        // A speaker is an ordinary user.
        val speaker = join(TestUser.create(reference, "conf@join1248.test", userClient = CL.public), ConfDemo.client, ConfDemo.speakerRole)
        val speakerRow = users.queryByUserId(reference, speaker[JOIN.userId].toOptLong()!!).shouldNotBeNull()
        speakerRow.persona shouldBe PERSONA.member
        speakerRow.roles shouldNotContain ROLE.admin
    }

    "a join is refused without the acknowledgment, and for what is not offered" {
        val visitor = TestUser.create(reference, "careful@join1248.test")
        visitor.expectError(EXC.badInput, JOIN.joinPath, mapOf(JOIN.client to SC.acme, JOIN.role to PERSONA.admin))
            .toString() shouldContain "shared demo space"
        visitor.expectError(
            EXC.badInput, JOIN.joinPath, mapOf(JOIN.client to "globex", JOIN.role to PERSONA.admin, JOIN.acknowledged to true),
        ).toString() shouldContain "not a client you may join"
        visitor.expectError(
            EXC.badInput, JOIN.joinPath, mapOf(JOIN.client to SC.acme, JOIN.role to "owner", JOIN.acknowledged to true),
        ).toString() shouldContain "not a role"
        // Nothing was made: the visitor is still in public.
        visitor.selfClient() shouldBe CL.public
    }

    "joining is absent outside public, and on a deployment that does not offer it" {
        val inAcme = TestUser.create(reference, "elsewhere@join1248.test", userClient = SC.acme)
        inAcme.expectError(EXC.notFound, JOIN.joinablePath)
        inAcme.expectError(EXC.notFound, JOIN.joinPath, mapOf(JOIN.client to SC.acme, JOIN.role to PERSONA.admin, JOIN.acknowledged to true))

        val visitor = TestUser.create(plain, "plain@join1248.test")
        visitor.selfClient() shouldBe CL.public
        visitor.expectError(EXC.notFound, JOIN.joinablePath)
        visitor.expectError(EXC.notFound, JOIN.joinPath, mapOf(JOIN.client to SC.acme, JOIN.role to PERSONA.admin, JOIN.acknowledged to true))
    }
})
