package com.dynamicruntime.edge

import com.dynamicruntime.common.content.UIC
import com.dynamicruntime.common.context.ACFG
import com.dynamicruntime.common.context.BOOT
import com.dynamicruntime.common.context.KdrCxt
import com.dynamicruntime.common.context.UserProfile
import com.dynamicruntime.common.home.HEP
import com.dynamicruntime.common.home.HFLD
import com.dynamicruntime.common.home.HMENU
import com.dynamicruntime.common.startup.InstanceRegistry
import com.dynamicruntime.common.uiblock.UiBlockService
import com.dynamicruntime.kdn.Startup
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe

/**
 * What an edge offers in its menu (issue #446).
 *
 * **This is a correctness test, not a presentation one.** The account, forms and profile surfaces are
 * contributed application-only (#432), so every item an edge showed for them was a page whose endpoints are
 * not on the node serving the menu. An anonymous caller was offered "Log in" and "Register" -- the
 * account-creation surface #432 existed to remove -- and an env-authed one, who holds `admin`, was offered
 * Users, My forms, Profile and Log out. Six items, all 404.
 *
 * Nothing here is an edge-shaped branch in the menu's code: the items say `app`, the boot role is a cfact, and
 * an edge simply fails to match them.
 */
class EdgeMenuTest : StringSpec({

    InstanceRegistry.register(listOf(EdgeComponent()))
    val cxt = Startup.mkTestBootCxt("edgeMenu", "edgeMenuTest", mapOf(ACFG.bootRole to BOOT.edge))

    fun menuIdsFor(profile: UserProfile): List<String?> {
        val scope: KdrCxt = cxt.mkSubContext("edgeCaller")
        scope.userProfile = profile
        return (UiBlockService.get(scope).resolve(scope, HMENU.block)?.get(HFLD.menu) as? List<*>)
            ?.map { (it as Map<*, *>)[HFLD.id] as String? } ?: emptyList()
    }

    "an anonymous edge caller is not offered the application's account pages" {
        // The bug this fixes: "Log in" and "Register" both 404 on an edge, and the second invites exactly the
        // account creation that removing the auth endpoints from an edge was meant to prevent.
        menuIdsFor(UserProfile()) shouldBe listOf(HMENU.catalog)
    }

    "an env-authed edge caller is not offered the application's signed-in pages" {
        // `UserProfile.envAuthed` holds `admin`, so before #446 this caller was shown Users, My forms, Profile
        // and Log out -- four items, none of which exist on an edge.
        menuIdsFor(UserProfile.envAuthed("someone@gyassa.com")) shouldBe listOf(HMENU.catalog)
    }

    "the boot role is what decides it, so the same data serves an application" {
        // The point of the boot role being a cfact: this is one list, and an application matches the items an
        // edge does not. Asserted from the *same* block rather than a second one.
        val app = Startup.mkTestBootCxt("edgeMenuApp", "edgeMenuAppTest")
        val scope = app.mkSubContext("appCaller")
        scope.userProfile = UserProfile(authId = "someone@example.com")
        val ids = (UiBlockService.get(scope).resolve(scope, HMENU.block)?.get(HFLD.menu) as? List<*>)
            ?.map { (it as Map<*, *>)[HFLD.id] as String? }
        ids shouldBe listOf(HMENU.catalog, HMENU.forms, HMENU.profile, HMENU.logout)
    }

    "the endpoint an edge does serve reports the same menu" {
        // Through `home/ui/config` rather than the service, since that is what a browser fetches, and the home
        // group is one of the few an edge carries.
        val results = com.dynamicruntime.common.http.request.TestHttpClient(cxt.instanceConfig)
            .sendJsonGetRequest(HEP.homeUiConfig)
        val state = (results[com.dynamicruntime.common.endpoint.EP.results] as Map<*, *>)[UIC.state] as Map<*, *>
        (state[HFLD.menu] as List<*>).map { (it as Map<*, *>)[HFLD.id] } shouldBe listOf(HMENU.catalog)
    }
})
