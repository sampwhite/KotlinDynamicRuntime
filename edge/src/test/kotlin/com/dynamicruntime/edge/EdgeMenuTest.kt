package com.dynamicruntime.edge

import com.dynamicruntime.common.content.MarkdownFragmentService
import com.dynamicruntime.common.content.UIC
import com.dynamicruntime.common.home.HFRAG
import com.dynamicruntime.common.user.ENVA
import com.dynamicruntime.common.user.EnvAuthRules
import com.dynamicruntime.common.context.ACFG
import com.dynamicruntime.common.context.BOOT
import com.dynamicruntime.common.context.KdrCxt
import com.dynamicruntime.common.context.UserProfile
import com.dynamicruntime.common.home.HACT
import com.dynamicruntime.common.home.HEP
import com.dynamicruntime.common.home.HFLD
import com.dynamicruntime.common.home.HMENU
import com.dynamicruntime.common.http.request.ContextRoot
import com.dynamicruntime.common.startup.InstanceRegistry
import com.dynamicruntime.common.uiblock.UIB
import com.dynamicruntime.common.uiblock.UiBlockService
import com.dynamicruntime.kdn.Startup
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldNotContain
import io.kotest.matchers.ints.shouldBeGreaterThan
import io.kotest.matchers.shouldBe

/**
 * What an edge offers in its menu (issue #446).
 *
 * **This is a correctness test, not a presentation one.** The account, forms, and profile surfaces are
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

    /** The `action` wire value of the item with [id], as this [profile] sees it. */
    fun actionOf(profile: UserProfile, id: String): Any? {
        val scope = cxt.mkSubContext("edgeCaller")
        scope.userProfile = profile
        val menu = UiBlockService.get(scope).resolve(scope, HMENU.block)?.get(HFLD.menu) as List<*>
        return menu.map { it as Map<*, *> }.first { it[HFLD.id] == id }[UIB.action]
    }

    "the edge's own items sort clear of the base menu's auto-numbered range" {
        // The regression guard for issue #498. `homeMenu`'s items are auto-numbered contiguously from
        // UIB.orderStep, so the base occupies 100, 200, 300, ... -- and `openAppOrder` was 200, exactly the
        // base's `users` slot. A tie is decided by nothing, and this one was invisible because `users` is
        // `,app`-gated: a fact about today's cfacts, not about the numbering.
        val edgeOrders = listOf(EDGEUI.openAppOrder, EDGEUI.loginOrder, EDGEUI.logoutOrder)

        // Headroom rather than the base's current length: a base of N items tops out at orderStep * N, so
        // this says a base could grow to 99 items (9900) and still not reach the edge's first slot.
        edgeOrders.forEach { it shouldBeGreaterThan UIB.orderStep * 99 }

        // Distinct and ascending, so the relative order is decided here rather than by a tie-break.
        edgeOrders.toSet().size shouldBe edgeOrders.size
        edgeOrders shouldBe edgeOrders.sorted()
    }

    "an anonymous edge caller is offered the catalog, the app, and a way in" {
        // Not the application's account pages ("Log in"/"Register" 404 on an edge, and the second invited
        // exactly the account creation #432 removed). What an edge adds: a way into the app, and Log in --
        // not Log out (#493).
        menuIdsFor(UserProfile()) shouldBe listOf(HMENU.catalog, EDGEUI.openAppItem, EDGEUI.loginItem)
    }

    "an env-authed edge caller is offered the catalog, the app, and a way out" {
        // `UserProfile.envAuthed` holds `admin`, so before #446 this caller was shown Users, My forms, Profile
        // and Log out -- four items, none of which exist on an edge. The application's items still fail to
        // match; the edge's own are Open application and its env logout (#493), which it genuinely serves.
        menuIdsFor(UserProfile.envAuthed("someone@gyassa.com")) shouldBe
            listOf(HMENU.catalog, EDGEUI.openAppItem, EDGEUI.logoutItem)
    }

    "Log in and Log out are one slot seen from opposite sides of a session" {
        // Opposite cfacts (`anonymous` / `loggedIn`), so exactly one shows: offering "Log out" to an anonymous
        // caller, or "Log in" to a signed-in one, would be incoherent (#493, #486).
        menuIdsFor(UserProfile()).let {
            it shouldContain EDGEUI.loginItem
            it shouldNotContain EDGEUI.logoutItem
        }
        menuIdsFor(UserProfile.envAuthed("someone@gyassa.com")).let {
            it shouldContain EDGEUI.logoutItem
            it shouldNotContain EDGEUI.loginItem
        }
    }

    "Open application is an openPath call to the app reached through the edge" {
        // Shown to everyone (no cfact): an env-authed operator passes straight through the proxy, and an
        // anonymous caller's navigation is challenged to sign-in by EdgeProxyHandler -- no code here (#493).
        actionOf(UserProfile(), EDGEUI.openAppItem) shouldBe listOf(HACT.openPath.name, "/${ContextRoot.wa}")
    }

    "Log in is an openPath call to the sign-in page, returning to the landing" {
        val loginUrl = "/${EdgeRoot.ec}${EDGEP.loginPage}?${EnvAuthReturn.param}=" +
            java.net.URLEncoder.encode("/${EdgeRoot.ew}", Charsets.UTF_8)
        actionOf(UserProfile(), EDGEUI.loginItem) shouldBe listOf(HACT.openPath.name, loginUrl)
    }

    "the edge's sign-out clears the cookie, then lands on the edge landing rather than the sign-in page" {
        // Revises #486: now that an edge has a landing, signing out drops you there rather than at a bare
        // sign-in page (#493). Two args, both the edge's to know -- the clear-cookie path, and the landing.
        actionOf(UserProfile.envAuthed("someone@gyassa.com"), EDGEUI.logoutItem) shouldBe
            listOf(HACT.envLogout.name, EAEP.logout, "/${EdgeRoot.ew}")
    }

    "the boot role is what decides it, so the same data serves an application" {
        // The point of the boot role being a cfact: this is one list, and the items an edge does not match are
        // exactly the ones an application shows. Asserted from the *same* block rather than a second one.
        val app = Startup.mkTestBootCxt("edgeMenuApp", "edgeMenuAppTest")
        val scope = app.mkSubContext("appCaller")
        scope.userProfile = UserProfile(authId = "someone@example.com")
        val ids = (UiBlockService.get(scope).resolve(scope, HMENU.block)?.get(HFLD.menu) as? List<*>)
            ?.map { (it as Map<*, *>)[HFLD.id] as String? }
        ids shouldBe listOf(HMENU.catalog, HMENU.forms, HMENU.profile, HMENU.logout)
    }

    "the endpoint an edge does serve reports the same menu, and no Documents for the anonymous landing" {
        // Through `home/ui/config` rather than the service, since that is what a browser fetches, and the home
        // group is one of the few an edge carries. An anonymous caller sees the anonymous menu, and an empty
        // `links` -- the edge landing is a marketing page, not a document index (#493).
        val results = com.dynamicruntime.common.http.request.TestHttpClient(cxt.instanceConfig)
            .sendJsonGetRequest(HEP.homeUiConfig)
        val state = (results[com.dynamicruntime.common.endpoint.EP.results] as Map<*, *>)[UIC.state] as Map<*, *>
        (state[HFLD.menu] as List<*>).map { (it as Map<*, *>)[HFLD.id] } shouldBe
            listOf(HMENU.catalog, EDGEUI.openAppItem, EDGEUI.loginItem)
        (state[HFLD.links] as List<*>) shouldBe emptyList<Any?>()
    }

    // --- what else an edge says about itself ------------------------------------------------------------

    "an edge marks itself in the shell's wordmark, and overrides the landing hero" {
        // A fragment overlay, not a frontend conditional: the shell renders the copy it is handed. The overlay
        // needs no cfact because this component loads only on an edge, and it overrides the hero heading and
        // body as well as the wordmark (#493) -- edge-wide, so it holds after login too.
        val home = MarkdownFragmentService.get(cxt).effectiveFragments(cxt, HFRAG.home)?.content?.get(HFRAG.home)
        home?.get(EDGEUI.brandKey) shouldBe EDGEUI.brand
        home?.get(EDGEUI.titleKey) shouldBe EDGEUI.landingTitle
        home?.get(EDGEUI.introKey) shouldBe EDGEUI.landingIntro
    }

    "an application is left unmarked" {
        // Only the edge is marked. An application is the ordinary case, and in a real deployment its brand is
        // the customer's -- where a marker would be wrong exactly where it matters. Its hero copy is the base's.
        val app = Startup.mkTestBootCxt("edgeBrandApp", "edgeBrandAppTest")
        val home = MarkdownFragmentService.get(app).effectiveFragments(app, HFRAG.home)?.content?.get(HFRAG.home)
        home?.get(EDGEUI.brandKey) shouldBe "KDR"
        home?.get(EDGEUI.titleKey) shouldBe "Welcome"
    }

    "an edge does not offer the env-auth toggle, and ignores the cookie behind it" {
        // The two halves that used to disagree (issue #446): `EdgeService.extractEnvAuth` binds the profile
        // from the cookie regardless of suppression, so honoring `kdrEnvOff` gave a UI curating itself as
        // signed-out and a caller who was still `admin`. One rule now decides both.
        EnvAuthRules.suppressionOffered(cxt.instanceConfig) shouldBe false
        EnvAuthRules.resolve(
            cxt.instanceConfig, null, mapOf(ENVA.suppressCookie to "1"), null,
        ).suppressed shouldBe false
    }

    "an application still offers it, which is what makes it a preview rather than a hole" {
        val app = Startup.mkTestBootCxt("edgeSuppressApp", "edgeSuppressAppTest")
        EnvAuthRules.suppressionOffered(app.instanceConfig) shouldBe true
        EnvAuthRules.resolve(
            app.instanceConfig, null, mapOf(ENVA.suppressCookie to "1"), null,
        ).suppressed shouldBe true
    }
})
