package com.dynamicruntime.kdn

import com.dynamicruntime.common.context.CL
import com.dynamicruntime.common.exception.EXC
import com.dynamicruntime.common.http.request.ROLE
import com.dynamicruntime.common.user.ADEP
import com.dynamicruntime.common.user.ADF
import com.dynamicruntime.common.user.AEP
import com.dynamicruntime.common.user.AFLD
import com.dynamicruntime.common.user.TestUser
import com.dynamicruntime.common.user.UADEP
import com.dynamicruntime.common.util.toJsonListOfMaps
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe

/**
 * The person behind a user (issue #770): the admin read the Users page's editor shows above the editable data --
 * the identity's facts and its other users -- confined to what the caller may administer.
 */
class AdminIdentityTest : StringSpec({
    val cxt = Startup.mkTestBootCxt("adminIdentity", "adminIdentityTest")
    val full = TestUser.createFullAdmin(cxt, "identity-full@example.com")

    fun userIds(info: Map<String, Any?>): List<Any?> = info[ADF.users].toJsonListOfMaps().map { it[ADF.userId] }

    "a full-scope administrator sees every user of the person, across clients, and the identity's facts" {
        val address = "identity-person@other.test"
        val inPublic = TestUser.create(cxt, address, userClient = CL.public)
        val inHub = TestUser.create(cxt, address, userClient = CL.hub)
        val info = full.getData(ADEP.userIdentity, mapOf(ADF.userId to inHub.userId))
        userIds(info) shouldBe listOf(inPublic.userId, inHub.userId)
        info[ADF.hasPassword] shouldBe false
        // The last login was as the hub user, so an unnamed login lands there.
        info[ADF.signsInAsUserId] shouldBe inHub.userId
        // Asked from either user, it is the same person.
        userIds(full.getData(ADEP.userIdentity, mapOf(ADF.userId to inPublic.userId))) shouldBe userIds(info)
    }

    "a client administrator sees only the person's users in their client, and nothing points past it" {
        val address = "identity-split@other.test"
        val inHub = TestUser.create(cxt, address, userClient = CL.hub)
        // Acted as last, so it is where an unnamed login lands -- and it is outside the hub admin's scope.
        TestUser.create(cxt, address, userClient = CL.public)
        val hubAdmin = TestUser.create(cxt, "identity-hub-admin@example.com", level = ROLE.admin, userClient = CL.hub)
        val info = hubAdmin.getData(UADEP.userIdentity, mapOf(ADF.userId to inHub.userId))
        userIds(info) shouldBe listOf(inHub.userId)
        info[ADF.signsInAsUserId].shouldBeNull()
    }

    "a user outside the caller's scope is not found" {
        val publicOnly = TestUser.create(cxt, "identity-outside@other.test", userClient = CL.public)
        val hubAdmin = TestUser.create(cxt, "identity-hub-admin2@example.com", level = ROLE.admin, userClient = CL.hub)
        hubAdmin.expectError(EXC.notFound, UADEP.userIdentity, args = mapOf(ADF.userId to publicOnly.userId))
    }

    "an unclaimed user's address is unproven, and a claimed one's is proven" {
        val fresh = "identity-invited@other.test"
        val made = full.postData(ADEP.userCreate, mapOf(ADF.primaryId to fresh, ADF.client to CL.hub))
        full.getData(ADEP.userIdentity, mapOf(ADF.userId to made[ADF.userId]))[ADF.verifiedAt].shouldBeNull()
        val registered = TestUser.register(cxt, "identity-registered@other.test", "identityregistered")
        full.getData(ADEP.userIdentity, mapOf(ADF.userId to registered.userId))[ADF.verifiedAt].shouldNotBeNull()
    }

    "a permanently deleted user is nobody's any more, so it is not listed" {
        val address = "identity-tomb@other.test"
        val keep = TestUser.create(cxt, address, userClient = CL.hub)
        val gone = TestUser.create(cxt, address, userClient = CL.public)
        // Removing their own public user is the permanent delete, done by the person (issue #752).
        gone.postData(AEP.removePublicUser, mapOf(AFLD.userId to gone.userId))
        userIds(full.getData(ADEP.userIdentity, mapOf(ADF.userId to keep.userId))) shouldBe listOf(keep.userId)
    }
})
