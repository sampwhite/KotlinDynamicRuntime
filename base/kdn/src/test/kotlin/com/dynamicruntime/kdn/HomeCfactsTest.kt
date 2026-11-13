package com.dynamicruntime.kdn

import com.dynamicruntime.common.cfact.CFACTS
import com.dynamicruntime.common.cfact.CallerCfacts
import com.dynamicruntime.common.cfact.pageCfactNames
import com.dynamicruntime.common.content.UIC
import com.dynamicruntime.common.context.CL
import com.dynamicruntime.common.context.ENV
import com.dynamicruntime.common.context.UserProfile
import com.dynamicruntime.common.endpoint.EP
import com.dynamicruntime.common.exception.KdrException
import com.dynamicruntime.common.gedra.ClientAudience
import com.dynamicruntime.common.gedra.ClientDef
import com.dynamicruntime.common.gedra.ClientUsageType
import com.dynamicruntime.common.gedra.GedraConfigReload
import com.dynamicruntime.common.gedra.GedraConfigService
import com.dynamicruntime.common.gedra.gedraConfig
import com.dynamicruntime.common.gedra.workflow.WSC
import com.dynamicruntime.common.home.HCARD
import com.dynamicruntime.common.home.HEP
import com.dynamicruntime.common.home.HFLD
import com.dynamicruntime.common.home.HMENU
import com.dynamicruntime.common.http.request.ROLE
import com.dynamicruntime.common.http.request.TestHttpClient
import com.dynamicruntime.common.naming.clientNamespace
import com.dynamicruntime.common.startup.SchemaService
import com.dynamicruntime.common.uiblock.UiBlockService
import com.dynamicruntime.common.user.TestUser
import com.dynamicruntime.common.util.toJsonMapOrEmpty
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldNotContain
import io.kotest.matchers.shouldBe

/**
 * The shell config sends the caller's frontend cfacts (issue #1793), which a page's widget fences are judged on in the
 * browser: every fact about the request that is marked to be sent, each to whether it holds for this caller. A fact
 * about a form -- a workflow's state, or a client's own -- is never among them, marked or not.
 */
class HomeCfactsTest : StringSpec({
    val cxt = TestInstances.default("homeCfacts")

    fun cfactsIn(results: Map<String, Any?>): Map<String, Any?> = results[UIC.state].toJsonMapOrEmpty()[HFLD.cfacts].toJsonMapOrEmpty()

    "a visitor and a signed-in administrator are each sent the delivered facts, as they hold for them" {
        val anonymous = cfactsIn(TestHttpClient(cxt.instanceConfig).sendJsonGetRequest(HEP.homeUiConfig)[EP.results].toJsonMapOrEmpty())
        anonymous.keys shouldBe SchemaService.get(cxt).cfactsFor(null).pageCfactNames()
        // Sent to the frontend for a form's own page, a workflow's state is about the form, and never sent for a page.
        anonymous.keys shouldNotContain WSC.needsReview
        anonymous[CFACTS.loggedIn] shouldBe false
        anonymous[CFACTS.anonymous] shouldBe true
        anonymous[CFACTS.hasAdminLevel] shouldBe false

        val admin = cfactsIn(TestUser.create(cxt, "cfacts-admin@example.com", level = ROLE.admin).getData(HEP.homeUiConfig))
        admin[CFACTS.loggedIn] shouldBe true
        admin[CFACTS.anonymous] shouldBe false
        admin[CFACTS.hasAdminLevel] shouldBe true
    }

    "a client's own facts are about its forms, so the shell config sends none, marked or not" {
        val client = "homecfacts1793"
        val config = gedraConfig(cxt, "${client}cfg", clientNamespace(client), client) {
            defineClient(
                ClientDef(
                    clientId = client, name = client, usageType = ClientUsageType.dev, audience = ClientAudience.internal,
                    enabledEnvironments = setOf(ENV.unit, ENV.local),
                ),
            )
            cfact("sentFact", "Page", "A fact the frontend is sent.", toFrontend = true)
            cfact("keptFact", "Page", "A fact the frontend is not sent.", toFrontend = false)
        }
        GedraConfigService.get(cxt).writeConfig(cxt.mkSubContext("homeCfacts", client), config)
        GedraConfigReload.reloadClient(cxt, client)
        val sent = cfactsIn(TestUser.create(cxt, "cfacts-member@$client.test", userClient = client).getData(HEP.homeUiConfig))
        // Marked to be sent, but nothing about a request sets it: a page that tested it would never draw its fence.
        sent.keys shouldNotContain "sentFact"
        sent.keys shouldNotContain "keptFact"
        sent[CFACTS.loggedIn] shouldBe true
    }

    "blocks are resolved on the facts assembled for their own request, and refused another's" {
        fun admin(name: String) = cxt.mkSubContext(name, CL.hub).also {
            it.userProfile = UserProfile(authId = "someone", roles = setOf(ROLE.user, ROLE.admin), client = CL.hub)
        }
        val first = admin("cfactsResolve1")
        val service = UiBlockService.get(cxt)
        val blocks = listOf(HMENU.block, HCARD.block)
        service.resolveAll(first, blocks, CallerCfacts.assemble(first)) shouldBe service.resolveAll(first, blocks)
        shouldThrow<KdrException> { service.resolveAll(admin("cfactsResolve2"), blocks, CallerCfacts.assemble(first)) }
    }
})
