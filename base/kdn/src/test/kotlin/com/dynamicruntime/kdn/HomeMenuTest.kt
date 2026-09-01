package com.dynamicruntime.kdn

import com.dynamicruntime.common.app.APP
import com.dynamicruntime.common.app.EnvAuthOp
import com.dynamicruntime.common.endpoint.EP
import com.dynamicruntime.common.home.HEP
import com.dynamicruntime.common.home.HFLD
import com.dynamicruntime.common.home.HACT
import com.dynamicruntime.common.home.HMENU
import com.dynamicruntime.common.uiblock.UIB
import com.dynamicruntime.common.content.UIC
import com.dynamicruntime.common.http.request.ROLE
import com.dynamicruntime.common.http.request.TestHttpClient
import com.dynamicruntime.common.user.ENVA
import com.dynamicruntime.common.user.TestUser
import com.dynamicruntime.common.util.toJsonListOfMaps
import com.dynamicruntime.common.util.toJsonMap
import com.dynamicruntime.common.util.toJsonMapOrEmpty
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldNotContain
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
 * faithful rather than merely plausible. It has since changed exactly once, in #483, and in one enumerable
 * way: `page: "x"` became `action: "x"` and `action: "logout"` became `action: ["logout"]`. Nothing else about
 * the response has moved across five rounds of refactoring, which is the property this test exists to have.
 */
class HomeMenuTest : StringSpec({

    val cxt = Startup.mkTestBootCxt("homeMenu", "homeMenuTest")

    /** A route item. Since #483 the field is `action` and a **string** is what makes it a navigation. */
    fun page(id: String, label: String, page: String) =
        mapOf(HFLD.id to id, HFLD.label to label, UIB.action to page)

    /** A call item: an **array**, whose head names a registered frontend function. */
    fun call(id: String, label: String, function: String, vararg args: String) =
        mapOf(HFLD.id to id, HFLD.label to label, UIB.action to listOf(function) + args.toList())

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
            call(HMENU.logout, "Log out", HACT.logout.name),
        )
    }

    "a client-scoped administrator gets Users and Client facts, and not the deployment's Environment" {
        // The pair that makes the conditions distinguishable: `users` follows the admin level, `cfactReference`
        // the `clientOperator` section (which an admin satisfies without `allClients`), while `envReference`
        // follows the deployment `operator` section, which has required `allClients` since #464 (issue #488).
        val admin = TestUser.create(cxt, "menu-admin@example.com", level = ROLE.admin)
        menuIn(admin.getData(HEP.homeUiConfig)) shouldBe listOf(
            page(HMENU.catalog, "Endpoint catalog", HMENU.pageCatalog),
            page(HMENU.users, "Users", HMENU.pageUsers),
            page(HMENU.cfactReference, "Client facts", HMENU.pageCfacts),
            page(HMENU.forms, "My forms", HMENU.pageForms),
            page(HMENU.profile, "Profile", HMENU.pageProfile),
            call(HMENU.logout, "Log out", HACT.logout.name),
        )
    }

    "a deployment operator gets Environment and Client facts, and not Users" {
        val operator = TestUser.createOperator(cxt, "menu-ops@example.com")
        menuIn(operator.getData(HEP.homeUiConfig)) shouldBe listOf(
            page(HMENU.catalog, "Endpoint catalog", HMENU.pageCatalog),
            page(HMENU.envReference, "Environment", HMENU.pageEnv),
            page(HMENU.cfactReference, "Client facts", HMENU.pageCfacts),
            page(HMENU.forms, "My forms", HMENU.pageForms),
            page(HMENU.profile, "Profile", HMENU.pageProfile),
            call(HMENU.logout, "Log out", HACT.logout.name),
        )
    }

    // ---- the Debug menu, driven by env auth (issue #517) ----------------------------------------------

    /** The menu the anonymous client sees with whatever env-auth state the client currently carries. */
    fun rawMenu(client: TestHttpClient): List<Map<String, Any?>> =
        menuIn(client.sendJsonGetRequest(HEP.homeUiConfig)[EP.results].toJsonMapOrEmpty())

    fun ids(menu: List<Map<String, Any?>>) = menu.map { it[HFLD.id] }

    "the Debug affordance is absent without env auth, a flat call when env-authed, a drill-down in debug" {
        // A fresh env-auth context so the header/cookie transitions here do not touch the shared `cxt`.
        val ecxt = Startup.mkTestBootCxt("homeMenuDebug", "homeMenuDebugTest")
        val client = TestHttpClient(ecxt.instanceConfig)

        // No env auth: neither the enable call nor the drill-down parent exists (both new cfacts are false).
        ids(rawMenu(client)).let { bare ->
            bare shouldNotContain HMENU.debugEnable
            bare shouldNotContain HMENU.debug
        }

        // Env-authed, not yet in debug (`canEnableDebug`): a single flat "Debug" call that turns debug on.
        client.setHeader(ENVA.header, "envauth.dev@gyassa.com")
        val enable = rawMenu(client)
        ids(enable).let { withAuth ->
            withAuth shouldContain HMENU.debugEnable
            withAuth shouldNotContain HMENU.debug // mutually exclusive: enable XOR the drill-down
            withAuth shouldNotContain HMENU.debugPages
        }
        enable.single { it[HFLD.id] == HMENU.debugEnable }[UIB.action] shouldBe
            listOf(HACT.setEnvDebug.name, "true")

        // In debug (`isEnvDebug`): the enable call is gone, replaced by a parent and its children.
        client.sendJsonPostRequest(APP.envAuthPath, mapOf(APP.envAuthOp to EnvAuthOp.debug.name))
        val debug = rawMenu(client)
        ids(debug).let { inDebug ->
            inDebug shouldNotContain HMENU.debugEnable
            inDebug shouldContain HMENU.debug
            inDebug shouldContain HMENU.debugPages
            inDebug shouldContain HMENU.debugOff
        }
        // The parent is a pure header -- no action of its own (the boot check refuses one).
        debug.single { it[HFLD.id] == HMENU.debug }.containsKey(UIB.action) shouldBe false
        // The children name the parent on the wire and carry their own actions.
        val pages = debug.single { it[HFLD.id] == HMENU.debugPages }
        pages[UIB.parentId] shouldBe HMENU.debug
        pages[UIB.action] shouldBe HMENU.pageDebug
        val off = debug.single { it[HFLD.id] == HMENU.debugOff }
        off[UIB.parentId] shouldBe HMENU.debug
        off[UIB.action] shouldBe listOf(HACT.setEnvDebug.name, "false")
    }
})
