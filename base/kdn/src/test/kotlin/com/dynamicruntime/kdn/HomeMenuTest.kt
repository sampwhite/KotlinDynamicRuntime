package com.dynamicruntime.kdn

import com.dynamicruntime.common.endpoint.EP
import com.dynamicruntime.common.home.HEP
import com.dynamicruntime.common.home.HFLD
import com.dynamicruntime.common.home.HMENU
import com.dynamicruntime.common.content.UIC
import com.dynamicruntime.common.http.request.ROLE
import com.dynamicruntime.common.http.request.TestHttpClient
import com.dynamicruntime.common.user.TestUser
import com.dynamicruntime.common.util.toJsonListOfMaps
import com.dynamicruntime.common.util.toJsonMap
import com.dynamicruntime.common.util.toJsonMapOrEmpty
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe

/**
 * The app-bar menu, exactly, for each kind of caller (issue #458).
 *
 * **This is the oracle for turning the menu into data.** The slice changes how the menu is produced, not what
 * it produces, so the whole of "did the refactor preserve behavior?" is whether these lists still match. Every
 * item is asserted whole -- keys and all -- rather than by id, because an extra key (a `displayOrder` that
 * leaked out of the merge, say) is exactly the kind of change that a by-id assertion would not see and that a
 * frontend would.
 *
 * Written against the hardcoded `menuItems()` before it moved, so passing it after the move means the move was
 * faithful rather than merely plausible.
 */
class HomeMenuTest : StringSpec({

    val cxt = Startup.mkTestBootCxt("homeMenu", "homeMenuTest")

    fun page(id: String, label: String, page: String) =
        mapOf(HFLD.id to id, HFLD.label to label, HFLD.page to page)

    /** The menu out of a `results` map. */
    fun menuIn(results: Map<String, Any?>): List<Map<String, Any?>> =
        results.getValue(UIC.state)!!.toJsonMap()[HFLD.menu].toJsonListOfMaps()

    /** The same, for the anonymous call, which goes through the raw client rather than a TestUser. */
    fun anonymousMenu(): List<Map<String, Any?>> =
        menuIn(TestHttpClient(cxt.instanceConfig).sendJsonGetRequest(HEP.homeUiConfig)[EP.results].toJsonMapOrEmpty())

    "a signed-out visitor is offered only what they can open" {
        anonymousMenu() shouldBe listOf(
            page(HMENU.catalog, "Endpoint catalog", HMENU.pageCatalog),
            page(HMENU.login, "Log in", HMENU.pageLogin),
            page(HMENU.register, "Register", HMENU.pageRegister),
        )
    }

    "an ordinary signed-in user gets the forms and profile entries, and logout as an action" {
        val user = TestUser.create(cxt, "menu-user@example.com")
        menuIn(user.getData(HEP.homeUiConfig)) shouldBe listOf(
            page(HMENU.catalog, "Endpoint catalog", HMENU.pageCatalog),
            page(HMENU.forms, "My forms", HMENU.pageForms),
            page(HMENU.profile, "Profile", HMENU.pageProfile),
            mapOf(HFLD.id to HMENU.logout, HFLD.label to "Log out", HFLD.action to HMENU.logout),
        )
    }

    "a client-scoped administrator gets Users, and not the deployment's Environment" {
        // The pair that makes the two conditions distinguishable: `users` follows the admin level, while
        // `envReference` follows the operator *section*, which has required `allClients` since #464.
        val admin = TestUser.create(cxt, "menu-admin@example.com", level = ROLE.admin)
        menuIn(admin.getData(HEP.homeUiConfig)) shouldBe listOf(
            page(HMENU.catalog, "Endpoint catalog", HMENU.pageCatalog),
            page(HMENU.users, "Users", HMENU.pageUsers),
            page(HMENU.forms, "My forms", HMENU.pageForms),
            page(HMENU.profile, "Profile", HMENU.pageProfile),
            mapOf(HFLD.id to HMENU.logout, HFLD.label to "Log out", HFLD.action to HMENU.logout),
        )
    }

    "a deployment operator gets Environment, and not Users" {
        val operator = TestUser.createOperator(cxt, "menu-ops@example.com")
        menuIn(operator.getData(HEP.homeUiConfig)) shouldBe listOf(
            page(HMENU.catalog, "Endpoint catalog", HMENU.pageCatalog),
            page(HMENU.envReference, "Environment", HMENU.pageEnv),
            page(HMENU.forms, "My forms", HMENU.pageForms),
            page(HMENU.profile, "Profile", HMENU.pageProfile),
            mapOf(HFLD.id to HMENU.logout, HFLD.label to "Log out", HFLD.action to HMENU.logout),
        )
    }
})
