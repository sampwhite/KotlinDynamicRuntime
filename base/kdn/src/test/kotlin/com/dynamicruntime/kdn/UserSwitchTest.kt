package com.dynamicruntime.kdn

import com.dynamicruntime.common.context.CL
import com.dynamicruntime.common.context.UPF
import com.dynamicruntime.common.exception.EXC
import com.dynamicruntime.common.http.request.ROLE
import com.dynamicruntime.common.user.ADEP
import com.dynamicruntime.common.user.ADF
import com.dynamicruntime.common.user.AEP
import com.dynamicruntime.common.user.AFLD
import com.dynamicruntime.common.user.TestUser
import com.dynamicruntime.common.user.UCF
import com.dynamicruntime.common.user.UserService
import com.dynamicruntime.common.util.toJsonListOfMaps
import com.dynamicruntime.common.util.toJsonListOfStrings
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe

/**
 * The switcher (issue #749): a signed-in person sees every registered, enabled user under their identity and
 * can become any of them, strictly within the identity. Driven through the endpoints, since the session cookie
 * the switch reissues is the thing under test.
 */
class UserSwitchTest : StringSpec({
    val cxt = Startup.mkTestBootCxt("userSwitch", "userSwitchTest")
    val users = UserService.get(cxt)

    fun choices(user: TestUser): List<Map<String, Any?>> = user.getData(AEP.selfUsers)[AFLD.users].toJsonListOfMaps()
    fun current(list: List<Map<String, Any?>>): Long? = list.firstOrNull { it[UCF.isCurrent] == true }?.get(UCF.userId) as? Long

    "switching to a sibling reissues the session as that user, with its client and roles" {
        val address = "switch-sib@example.com"
        // Two users of one person: an ordinary one in public, and an administrator in hub.
        val ordinary = TestUser.create(cxt, address)
        val hubAdmin = TestUser.create(cxt, address, level = ROLE.admin, userClient = CL.hub)
        val listed = choices(ordinary)
        listed shouldHaveSize 2
        current(listed) shouldBe ordinary.userId

        val switched = ordinary.postData(AEP.switchUser, mapOf(AFLD.userId to hubAdmin.userId))
        switched[UPF.userId] shouldBe hubAdmin.userId
        switched[UPF.client] shouldBe CL.hub
        switched[UPF.roles].toJsonListOfStrings() shouldContain ROLE.admin
        // The cookie in this browser is now the administrator's: the next call is made as them.
        ordinary.getData(AEP.selfInfo)[UPF.userId] shouldBe hubAdmin.userId
        current(choices(ordinary)) shouldBe hubAdmin.userId
        // And the identity remembers who was acted as last, which is what an unnamed login falls back to.
        users.queryIdentityByAddress(cxt, address).shouldNotBeNull().lastUsedUserId shouldBe hubAdmin.userId
    }

    "a user of another identity, or a disabled sibling, is refused with one message" {
        val address = "switch-refuse@example.com"
        val me = TestUser.create(cxt, address)
        val sibling = TestUser.create(cxt, address, personId = "2")
        val stranger = TestUser.create(cxt, "switch-stranger@example.com")
        val admin = TestUser.createFullAdmin(cxt, "switch-admin@example.com")

        // Another person's user: refused as bad input, not as forbidden -- the endpoint confirms nothing
        // about users that are not the caller's.
        me.expectError(EXC.badInput, AEP.switchUser, mapOf(AFLD.userId to stranger.userId))
        me.expectError(EXC.badInput, AEP.setDefaultUser, mapOf(AFLD.userId to stranger.userId))
        // A disabled sibling: off the list, and refused the same way.
        admin.postData(ADEP.userSetEnabled, mapOf(ADF.userId to sibling.userId, ADF.enabled to false))
        choices(me).map { it[UCF.userId] } shouldBe listOf(me.userId)
        me.expectError(EXC.badInput, AEP.switchUser, mapOf(AFLD.userId to sibling.userId))
        // Re-enabled, it is back.
        admin.postData(ADEP.userSetEnabled, mapOf(ADF.userId to sibling.userId, ADF.enabled to true))
        choices(me) shouldHaveSize 2
    }

    "the chosen default wins over the most recently used user when an address logs in unnamed" {
        val address = "switch-default@example.com"
        val first = TestUser.create(cxt, address)
        val second = TestUser.create(cxt, address, personId = "B")
        // `second` was acted as last, so an unnamed login lands there -- until a default is chosen.
        TestUser.create(cxt, address).userId shouldBe second.userId
        val listed = first.postData(AEP.setDefaultUser, mapOf(AFLD.userId to first.userId))[AFLD.users].toJsonListOfMaps()
        listed.single { it[UCF.isDefault] == true }[UCF.userId] shouldBe first.userId
        TestUser.create(cxt, address).userId shouldBe first.userId
        // Acting as `second` again does not displace the choice.
        TestUser.create(cxt, address, personId = "B").userId shouldBe second.userId
        TestUser.create(cxt, address).userId shouldBe first.userId
    }
})
