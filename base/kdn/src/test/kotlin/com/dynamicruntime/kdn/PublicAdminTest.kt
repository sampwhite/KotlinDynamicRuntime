package com.dynamicruntime.kdn

import com.dynamicruntime.common.content.UIC
import com.dynamicruntime.common.context.CL
import com.dynamicruntime.common.exception.EXC
import com.dynamicruntime.common.gedra.CCT
import com.dynamicruntime.common.gedra.CFEP
import com.dynamicruntime.common.gedra.CLC
import com.dynamicruntime.common.gedra.GDF
import com.dynamicruntime.common.gedra.GE
import com.dynamicruntime.common.gedra.GEP
import com.dynamicruntime.common.gedra.GT
import com.dynamicruntime.common.home.HEP
import com.dynamicruntime.common.home.HFEAT
import com.dynamicruntime.common.http.request.ROLE
import com.dynamicruntime.common.mail.MailService
import com.dynamicruntime.common.user.ADF
import com.dynamicruntime.common.user.PERSONA
import com.dynamicruntime.common.user.TestUser
import com.dynamicruntime.common.user.UADEP
import com.dynamicruntime.common.user.UserService
import com.dynamicruntime.common.util.toJsonMapOrEmpty
import com.dynamicruntime.common.util.toOptStr
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldNotContain
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe

/**
 * `public` users are administrators of themselves only (issue #805): self-registration into `public` grants
 * `admin`, and that reach covers the person's own users and nothing else -- no other `public` user, no data but
 * their own, and not the `public` client's configuration.
 *
 * Registered through the real flow, since what is under test is what a self-registration grants.
 */
class PublicAdminTest : StringSpec({
    val cxt = Startup.mkTestBootCxt("publicAdmin", "publicAdminTest")
    val users = UserService.get(cxt)

    val me = TestUser.register(cxt, "pubadmin-me@other.test", "pubadminme")
    val stranger = TestUser.register(cxt, "pubadmin-stranger@other.test", "pubadminstranger")

    fun listed(user: TestUser): List<Any?> = user.getItems(UADEP.users).map { it[ADF.userId] }

    "a self-registration into public is an administrator of themselves, as a member" {
        me.selfClient() shouldBe CL.public
        me.selfRoles() shouldContain ROLE.admin
        me.selfRoles() shouldNotContain ROLE.allClients
        // The admin role marks no different kind of user in `public`, so the persona stays the member's.
        users.queryByUserId(cxt, me.userId).shouldNotBeNull().persona shouldBe PERSONA.member
        // The Users page is theirs to open.
        me.getData(HEP.homeUiConfig)[UIC.features].toJsonMapOrEmpty()[HFEAT.canManageUsers] shouldBe true
    }

    "their Users list holds their own users, and no other public user" {
        listed(me) shouldBe listOf(me.userId)
        listed(stranger) shouldBe listOf(stranger.userId)
    }

    "they create users of their own, registered at once, and appear in their list" {
        val variant = me.postData(
            UADEP.userCreate,
            mapOf(ADF.primaryId to "pubadmin-me@other.test", ADF.personaSuffix to "B"),
        )
        val variantId = variant[ADF.userId] as Long
        variant[ADF.client].toOptStr() shouldBe CL.public
        users.queryByUserId(cxt, variantId).shouldNotBeNull().isRegistered shouldBe true
        listed(me).toSet() shouldBe setOf(me.userId, variantId)
        // Still invisible to the stranger.
        listed(stranger) shouldBe listOf(stranger.userId)
    }

    "they cannot create a user at anybody else's address, and nothing is mailed there" {
        val target = "pubadmin-victim@other.test"
        me.expectError(EXC.badInput, UADEP.userCreate, data = mapOf(ADF.primaryId to target))
        MailService.get(cxt).lastEmailTo(target).shouldBeNull()
        users.queryIdentityByAddress(cxt, target).shouldBeNull()
        // Nor place one of their own in another client: that takes allClients, as it always has.
        me.expectError(
            EXC.badInput, UADEP.userCreate,
            data = mapOf(ADF.primaryId to "pubadmin-me@other.test", ADF.client to CL.hub, ADF.personaSuffix to "H"),
        )
    }

    "another public user is out of reach on every admin endpoint, as absent" {
        val other = stranger.userId
        me.expectError(EXC.notFound, UADEP.userSetRoles, data = mapOf(ADF.userId to other, ADF.roles to listOf(ROLE.user)))
        me.expectError(EXC.notFound, UADEP.userSetEnabled, data = mapOf(ADF.userId to other, ADF.enabled to false))
        me.expectError(EXC.notFound, UADEP.userSetName, data = mapOf(ADF.userId to other, ADF.name to "Renamed"))
        me.expectError(EXC.notFound, UADEP.userInvite, data = mapOf(ADF.userId to other))
        users.queryByUserId(cxt, other).shouldNotBeNull().enabled shouldBe true
    }

    "the admin role never widens what they read: another public user's forms stay theirs" {
        val theirs = stranger.postItem(
            GEP.formDocCreate,
            mapOf(GDF.entries to listOf(mapOf(GE.traitId to GT.name, GE.data to mapOf(GT.name to "Private")))),
        )[GDF.gedraId].toOptStr().shouldNotBeNull()
        me.getItems(GEP.formDocs).map { it[GDF.gedraId] } shouldNotContain theirs
        me.expectError(EXC.notFound, GEP.formDoc, args = mapOf(GDF.gedraId to theirs))
    }

    "the public client's configuration is not theirs to read or change" {
        me.expectError(EXC.notAuthorized, CFEP.bundles)
        me.expectError(EXC.notAuthorized, CFEP.reload, data = emptyMap())
        // A well-formed write into the `public` client's own namespace, so what refuses it is the authority
        // check rather than input validation.
        me.expectError(
            EXC.notAuthorized, CFEP.bundleWrite,
            data = mapOf(
                CFEP.name to "hijack",
                CFEP.namespaceField to CLC.namespaceOf(CL.public),
                CFEP.slots to mapOf(
                    CCT.cfactDef to listOf(mapOf(CCT.name to "x", CCT.group to "g", CCT.description to "d")),
                ),
            ),
        )
    }

    // The rule is keyed on the client, not on how the role arrived: a fixture admin placed in `public` is
    // confined exactly as a registrant is.
    "an administrator placed in public by any route is confined the same way" {
        val placed = TestUser.create(cxt, "pubadmin-placed@other.test", level = ROLE.admin, userClient = CL.public)
        listed(placed) shouldBe listOf(placed.userId)
    }
})
