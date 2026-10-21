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
import com.dynamicruntime.common.home.HEP
import com.dynamicruntime.common.home.HFLD
import com.dynamicruntime.common.content.UIC
import com.dynamicruntime.common.gedra.sandboxOf
import com.dynamicruntime.common.util.toJsonMapOrEmpty

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

    "the shell says a joined demo client is a shared space, in its sandbox too, and nowhere else" {
        fun sharedDemo(user: TestUser) = user.getData(HEP.homeUiConfig)[UIC.state].toJsonMapOrEmpty()[HFLD.sharedDemo]
        val visitor = TestUser.create(reference, "shell@join1248.test")
        sharedDemo(visitor) shouldBe null
        join(visitor, SC.acme, PERSONA.admin)
        sharedDemo(visitor) shouldBe true
        // Every administrator who joined may open acme's sandbox, which is as shared, though its definition declares no roles.
        visitor.postData(AEP.openSandbox, emptyMap())
        visitor.selfClient() shouldBe sandboxOf(SC.acme)
        sharedDemo(visitor) shouldBe true
        // Without the flavor, acme is not a shared space.
        sharedDemo(TestUser.create(plain, "shell@join1248.test", userClient = SC.acme)) shouldBe null
    }

    "the shell tells a visitor in public that joining is offered, so the public home page asks (issue #1324)" {
        fun joinOffered(user: TestUser) = user.getData(HEP.homeUiConfig)[UIC.state].toJsonMapOrEmpty()[HFLD.joinOffered]
        joinOffered(TestUser.create(reference, "offered@join1324.test")) shouldBe true
        joinOffered(TestUser.create(reference, "inacme@join1324.test", userClient = SC.acme)) shouldBe null
        joinOffered(TestUser.create(plain, "offered@join1324.test")) shouldBe null
    }

    "designdemo, defined in stored configuration, is joined as its designer" {
        val visitor = TestUser.create(reference, "designer@join1248.test")
        join(visitor, DesignDemo.client, PERSONA.admin)
        visitor.selfClient() shouldBe DesignDemo.client
    }

    "a user an administrator disabled is not joined again; one deleted for good may join afresh" {
        val users = UserService.get(reference)
        val disabled = TestUser.create(reference, "disabled@join1248.test")
        val disabledId = join(disabled, SC.acme, PERSONA.admin)[JOIN.userId].toOptLong().shouldNotBeNull()
        users.deleteUser(reference.mkSubContext("joinTest", SC.acme), users.queryByUserId(reference, disabledId)!!, permanent = false)
        val back = TestUser.create(reference, "disabled@join1248.test", userClient = CL.public)
        roleOf(joinable(back), SC.acme, PERSONA.admin)[JOIN.removed] shouldBe true
        roleOf(joinable(back), SC.acme, PERSONA.admin)[JOIN.userId] shouldBe null
        back.expectError(EXC.badInput, JOIN.joinPath, mapOf(JOIN.client to SC.acme, JOIN.role to PERSONA.admin, JOIN.acknowledged to true))
            .toString() shouldContain "was disabled by one of its administrators"
        users.queryByUserId(reference, disabledId).shouldNotBeNull().enabled shouldBe false

        // A delete for good gives up the user's key, so the person joins as the role again with a user of their own.
        val deleted = TestUser.create(reference, "deleted@join1248.test")
        val deletedId = join(deleted, SC.acme, PERSONA.admin)[JOIN.userId].toOptLong().shouldNotBeNull()
        users.deleteUser(reference.mkSubContext("joinTest", SC.acme), users.queryByUserId(reference, deletedId)!!, permanent = true)
        val again = TestUser.create(reference, "deleted@join1248.test", userClient = CL.public)
        roleOf(joinable(again), SC.acme, PERSONA.admin)[JOIN.removed] shouldBe null
        join(again, SC.acme, PERSONA.admin)[JOIN.userId] shouldNotBe deletedId
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
