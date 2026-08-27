package com.dynamicruntime.kdn

import com.dynamicruntime.common.content.UIC
import com.dynamicruntime.common.endpoint.EP
import com.dynamicruntime.common.home.HEP
import com.dynamicruntime.common.home.HFEAT
import com.dynamicruntime.common.home.HFLD
import com.dynamicruntime.common.home.HMENU
import com.dynamicruntime.common.http.request.ROLE
import com.dynamicruntime.common.http.request.TestHttpClient
import com.dynamicruntime.common.user.ADEP
import com.dynamicruntime.common.user.ADF
import com.dynamicruntime.common.user.TestUser
import com.dynamicruntime.common.util.toJsonListOfMaps
import com.dynamicruntime.common.util.toJsonMap
import com.dynamicruntime.common.util.toJsonMapOrEmpty
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldNotContain
import io.kotest.matchers.shouldBe

/**
 * The shell menu served by `/home/ui/config`, which the app bar renders verbatim.
 *
 * The menu is where "what may this user reach?" is answered, so the answer is asserted per caller rather than
 * left to the frontend. In particular the user-administration entry must be absent for anyone without the
 * capability -- a signed-out visitor or an ordinary user -- since the frontend adds nothing of its own and an
 * item that is present is an item that gets rendered.
 */
class HomeMenuTest : StringSpec({

    fun results(resp: Map<String, Any?>): Map<String, Any?> = resp.getValue(EP.results)!!.toJsonMap()

    /** The menu item ids this response offers. */
    fun menuIds(resp: Map<String, Any?>): List<String> =
        results(resp)[UIC.state].toJsonMapOrEmpty()[HFLD.menu].toJsonListOfMaps().map { it[HFLD.id] as String }

    fun canManageUsers(resp: Map<String, Any?>): Boolean =
        results(resp)[UIC.features].toJsonMapOrEmpty()[HFEAT.canManageUsers] == true

    "an anonymous visitor is offered only the signed-out items" {
        val cxt = Startup.mkTestBootCxt("home", "homeMenuAnonTest")
        val resp = TestHttpClient(cxt.instanceConfig).sendJsonGetRequest(HEP.homeUiConfig)

        menuIds(resp) shouldBe listOf(HMENU.catalog, HMENU.login, HMENU.register)
        canManageUsers(resp) shouldBe false
    }

    "an ordinary user gets profile and logout, but no user administration" {
        val cxt = Startup.mkTestBootCxt("home", "homeMenuUserTest")
        val plain = TestUser.create(cxt, "plain@example.com")
        val resp = plain.client.sendJsonGetRequest(HEP.homeUiConfig)

        // Only "My forms" -- creating a form is reached from that list, not a separate menu item (issue #417).
        menuIds(resp) shouldBe listOf(HMENU.catalog, HMENU.forms, HMENU.profile, HMENU.logout)
        menuIds(resp) shouldNotContain HMENU.users
        canManageUsers(resp) shouldBe false
    }

    "an administrator additionally gets the users item" {
        val cxt = Startup.mkTestBootCxt("home", "homeMenuAdminTest")
        val admin = TestUser.createFullAdmin(cxt, "chief@example.com")
        val resp = admin.client.sendJsonGetRequest(HEP.homeUiConfig)

        menuIds(resp) shouldContain HMENU.users
        canManageUsers(resp) shouldBe true
        // The item is a navigation, not an action, and points at the page the frontend routes on.
        val usersItem = results(resp)[UIC.state].toJsonMapOrEmpty()[HFLD.menu].toJsonListOfMaps()
            .first { it[HFLD.id] == HMENU.users }
        usersItem[HFLD.page] shouldBe HMENU.pageUsers
    }

    /**
     * The operator "Environment" entry (issue #371) follows the deployment gate, not the ladder alone. Since
     * #464 the `operator` section demands `allClients` as well as the level, and the menu asks the dispatcher's
     * own predicate -- so a client-scoped admin, who satisfies the operator rung but lacks the capability, is
     * not offered a page the gate would then refuse (the #211 advertise-versus-serve invariant).
     */
    "the Environment item is offered only to who can reach the operator surface" {
        val cxt = Startup.mkTestBootCxt("home", "homeMenuEnvTest")

        // Full-scope admin and deployment operator both hold allClients, so the operator surface is theirs.
        val fullAdmin = TestUser.createFullAdmin(cxt, "env-full@example.com")
        menuIds(fullAdmin.client.sendJsonGetRequest(HEP.homeUiConfig)) shouldContain HMENU.envReference
        val operator = TestUser.createOperator(cxt, "env-op@example.com")
        menuIds(operator.client.sendJsonGetRequest(HEP.homeUiConfig)) shouldContain HMENU.envReference

        // A client-scoped admin satisfies the operator rung but lacks the capability, so it is withheld.
        val scoped = TestUser.create(cxt, "env-scoped@example.com", level = ROLE.admin)
        val scopedMenu = menuIds(scoped.client.sendJsonGetRequest(HEP.homeUiConfig))
        scopedMenu shouldNotContain HMENU.envReference
        scopedMenu shouldContain HMENU.users // ...but the scoped user-administration item still is
    }

    "the menu follows the caller: the same session loses the users item when the role is revoked" {
        val cxt = Startup.mkTestBootCxt("home", "homeMenuRevokeTest")
        val admin = TestUser.createFullAdmin(cxt, "chief2@example.com")
        val deputy = TestUser.createFullAdmin(cxt, "deputy2@example.com")

        menuIds(deputy.client.sendJsonGetRequest(HEP.homeUiConfig)) shouldContain HMENU.users

        // Revoked by the other admin; the deputy's *existing* session must stop being offered the item.
        admin.postData(ADEP.userSetRoles, mapOf(ADF.userId to deputy.userId, ADF.roles to listOf(ROLE.user)))
        menuIds(deputy.client.sendJsonGetRequest(HEP.homeUiConfig)) shouldNotContain HMENU.users
    }
})
