package com.dynamicruntime.kdn

import com.dynamicruntime.common.app.APP
import com.dynamicruntime.common.content.UIC
import com.dynamicruntime.common.context.ACFG
import com.dynamicruntime.common.context.ENV
import com.dynamicruntime.common.context.KdrCxt
import com.dynamicruntime.common.endpoint.EP
import com.dynamicruntime.common.endpoint.HttpMethod
import com.dynamicruntime.common.exception.EXC
import com.dynamicruntime.common.exception.KdrException
import com.dynamicruntime.common.home.HCARD
import com.dynamicruntime.common.home.HEP
import com.dynamicruntime.common.home.HFLD
import com.dynamicruntime.common.home.HMENU
import com.dynamicruntime.common.http.request.ROLE
import com.dynamicruntime.common.http.request.TestHttpClient
import com.dynamicruntime.common.mail.MAIL
import com.dynamicruntime.common.mail.MCOPY
import com.dynamicruntime.common.mail.MailCopy
import com.dynamicruntime.common.mail.MailDelivery
import com.dynamicruntime.common.mail.MailService
import com.dynamicruntime.common.test.SIM
import com.dynamicruntime.common.user.AEP
import com.dynamicruntime.common.user.AFLD
import com.dynamicruntime.common.user.ENVA
import com.dynamicruntime.common.user.PTD
import com.dynamicruntime.common.user.PublicSignIn
import com.dynamicruntime.common.user.PublicTestDeployment
import com.dynamicruntime.common.user.TestUser
import com.dynamicruntime.common.user.UserService
import com.dynamicruntime.common.util.toJsonListOfMaps
import com.dynamicruntime.common.util.toJsonMapOrEmpty
import com.dynamicruntime.common.node.NodeUtil
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain

/**
 * Public test deployments (issue #1150): a test instance in `dev` the public can reach, clearly marked as for testing.
 * The boot refuses every setting that would let someone no edge vouched for reach the test surface; a caller without
 * env auth is refused sign-in, or mailed for real and never captured; mail follows the caller; and on an ephemeral
 * instance with no edge an administrator's login is what carries env auth.
 *
 * Each instance is its own (issue #1075): the environment (`dev`) and the deployment's settings are the setup under
 * test, and no shared entry has them. **No mail can leave**: the provider's key is looked up under a secret name no
 * secrets file holds -- a developer's secrets may hold a real one, which these settings would otherwise load -- and the
 * admin domain is under the reserved `.test`.
 */
class PublicTestDeploymentTest : StringSpec({
    val adminDomain = "ptd1150.test"

    /** A node in `dev` with [settings] over a public test deployment's: in memory, no provider key, trust stated. */
    fun boot(name: String, settings: Map<String, Any?> = emptyMap(), publicDeployment: Boolean = true): KdrCxt =
        Startup.mkBootCxt(
            name, "${name}Inst",
            buildMap {
                put(ACFG.env, ENV.dev)
                put(ACFG.inMemoryOnly, true)
                put(ACFG.validateResponseSchema, true)
                put(MAIL.mailgunApiKeySecretKey, "noSuchSecretForPublicTestDeploymentTest")
                put(ACFG.adminEmailDomain, adminDomain)
                if (publicDeployment) {
                    put(PTD.publicTestDeploymentEnvVar.name, "true")
                    put(ACFG.trustEnvAuthHeader, false)
                }
                putAll(settings)
            },
        )

    fun refusal(name: String, settings: Map<String, Any?>, publicDeployment: Boolean = true): String =
        shouldThrow<KdrException> { boot(name, settings, publicDeployment) }.message.orEmpty()

    fun features(client: TestHttpClient): Map<String, Any?> =
        client.sendJsonGetRequest(APP.uiConfig)[EP.results].toJsonMapOrEmpty()[UIC.features].toJsonMapOrEmpty()
    fun settings(client: TestHttpClient): Map<String, Any?> =
        client.sendJsonGetRequest(APP.uiConfig)[EP.results].toJsonMapOrEmpty()[UIC.settings].toJsonMapOrEmpty()

    "a test instance outside local and unit starts only as a public test deployment in dev" {
        refusal("ptdNotOptedIn", emptyMap(), publicDeployment = false) shouldContain PTD.publicTestDeploymentEnvVar.name
        // Opted in, in dev, with the trust stated: it starts.
        boot("ptdStarts").instanceConfig.isTestInstance shouldBe true
    }

    "a public test deployment must state its header trust, assume nothing, and name a sign-in it knows" {
        refusal("ptdTrustUnset", mapOf(ACFG.trustEnvAuthHeader to null)) shouldContain ENVA.trustEnvAuthHeaderEnvVar.name
        refusal("ptdAssumes", mapOf(ACFG.assumeEnvAuth to true)) shouldContain ENVA.assumeEnvAuthEnvVar.name
        refusal("ptdBadSignIn", mapOf(PTD.publicSignInEnvVar.name to "everyone")) shouldContain "everyone"
        refusal("ptdNotTest", mapOf(ACFG.isTestInstance to false)) shouldContain "not a test instance"
    }

    "it may assume env auth only when it listens on loopback alone, and then only for a request no proxy forwarded" {
        val assume = mapOf(ACFG.assumeEnvAuth to true)
        refusal("ptdAssumesEverywhere", assume + (NodeUtil.bindAddress.name to "0.0.0.0")) shouldContain NodeUtil.bindAddress.name
        refusal("ptdAssumesByName", assume + (NodeUtil.bindAddress.name to "example.org")) shouldContain NodeUtil.bindAddress.name
        val cxt = boot("ptdAssumesLoopback", assume + (NodeUtil.bindAddress.name to "127.0.0.1"))
        // A request from the node itself carries no forwarded-for address, and is env-authed (issue #1245)...
        features(TestHttpClient(cxt.instanceConfig))[APP.hasTestAccess] shouldBe true
        // ...while one that came through the proxy, which always says whom it came from, is not.
        val proxied = TestHttpClient(cxt.instanceConfig).also { it.setHeader("X-Forwarded-For", "203.0.113.7") }
        features(proxied)[APP.hasTestAccess] shouldBe false
    }

    "env auth from an administrator's login needs a public deployment, no edge, and an admin domain of its own" {
        refusal("ptdLoginAlone", mapOf(PTD.envAuthFromAdminLoginEnvVar.name to "true"), publicDeployment = false) shouldContain
            PTD.publicTestDeploymentEnvVar.name
        refusal("ptdLoginTrusts", mapOf(PTD.envAuthFromAdminLoginEnvVar.name to "true", ACFG.trustEnvAuthHeader to true)) shouldContain
            "must be off"
        refusal("ptdLoginGmail", mapOf(PTD.envAuthFromAdminLoginEnvVar.name to "true", ACFG.adminEmailDomain to "gmail.com")) shouldContain
            "consumer mail domain"
        refusal("ptdLoginNoDomain", mapOf(PTD.envAuthFromAdminLoginEnvVar.name to "true", ACFG.adminEmailDomain to "")) shouldContain
            "needs an admin domain"
    }

    "by default a caller without env auth is refused sign-in, and every page says what the deployment is" {
        val cxt = boot("ptdBlocked")
        val plain = TestHttpClient(cxt.instanceConfig)
        // Asked by the login id, before any lookup, so a known user and an unknown one are refused alike.
        for (loginId in listOf("someone@public.test", "nobody@public.test")) {
            val refused = plain.sendEditRequest(
                AEP.loginByPassword, null, mapOf(AFLD.loginId to loginId, AFLD.password to "pw"), HttpMethod.POST,
            )
            refused.rptStatusCode shouldBe EXC.notAuthorized
            refused.rptResponseData.orEmpty() shouldContain "testing and development only"
        }
        // A registration is refused at its first step, the code.
        val token = plain.sendJsonGetRequest(AEP.createToken)[EP.results].toJsonMapOrEmpty()[AFLD.formAuthToken]
        plain.sendEditRequest(
            AEP.newContactSendVerify, null,
            mapOf(AFLD.contactAddress to "someone@public.test", AFLD.contactType to "email", AFLD.formAuthToken to token),
            HttpMethod.POST,
        ).rptStatusCode shouldBe EXC.notAuthorized
        val features = features(plain)
        features[APP.publicTestDeployment] shouldBe true
        features[APP.hasTestAccess] shouldBe false
        // Its words, the home copy's, served with the flag (issue #1697): core's, with no flavor rewording them.
        settings(plain)[APP.testDeploymentBand] shouldBe APP.defaultTestDeploymentBand
        // A node that is not a public test deployment draws no band, and is sent no words for one.
        val noBand = TestHttpClient(TestInstances.default("ptdNoBand").instanceConfig)
        features(noBand)[APP.publicTestDeployment] shouldBe false
        settings(noBand).containsKey(APP.testDeploymentBand) shouldBe false
    }

    // Issue #1652: the home page offers sign-in and registration exactly where the flow would take the caller. The rule
    // turns on the address, so the menu (Register, Log in) is offered where some address may sign in, and the Register
    // card, which invites anyone, where every address may.
    "an offer to sign in or register is made where the sign-in flow would take the caller" {
        /** What an anonymous caller of [cxt]'s home config is offered: the Account entries, and the cards. */
        fun offered(cxt: KdrCxt): Pair<List<Any?>, List<Any?>> {
            val state = TestHttpClient(cxt.instanceConfig).sendJsonGetRequest(HEP.homeUiConfig)[EP.results].toJsonMapOrEmpty()[UIC.state]
                .toJsonMapOrEmpty()
            val menu = state[HFLD.menu].toJsonListOfMaps().map { it[HFLD.id] }.filter { it == HMENU.login || it == HMENU.register }
            return menu to state[HCARD.cards].toJsonListOfMaps().map { it[HFLD.id] }
        }

        /** The two facts agree with the rule the flows refuse by, for an address of the public's and the admin domain's. */
        fun factsAgree(cxt: KdrCxt) {
            val anonymous = cxt.mkSubContext("anonymous")
            val open = listOf("visitor@public.test", "ops@$adminDomain").map { PublicTestDeployment.signInOpenTo(anonymous, it) }
            PublicTestDeployment.signInOpenToEveryone(anonymous) shouldBe open.all { it }
            PublicTestDeployment.signInOpenToSome(anonymous) shouldBe open.any { it }
        }

        // Blocked, the default: nobody without env auth may sign in, so neither entry nor the card is offered.
        val blocked = boot("ptdOffersBlocked")
        factsAgree(blocked)
        offered(blocked) shouldBe (emptyList<Any?>() to emptyList())

        // Blocked, but an administrator's login carries env auth: the admin domain still signs in, and registers first,
        // so the menu offers both; the card, which invites the public, is not offered.
        val adminLogin = boot("ptdOffersAdminLogin", mapOf(PTD.envAuthFromAdminLoginEnvVar.name to "true"))
        factsAgree(adminLogin)
        offered(adminLogin) shouldBe (listOf<Any?>(HMENU.login, HMENU.register) to emptyList())

        // Real mail: anyone may sign in, so the menu and the card both offer it.
        val realMail = boot("ptdOffersRealMail", mapOf(PTD.publicSignInEnvVar.name to PublicSignIn.realMail.name))
        factsAgree(realMail)
        offered(realMail) shouldBe (listOf<Any?>(HMENU.login, HMENU.register) to listOf<Any?>(HCARD.register))
    }

    "mail follows the caller: captured for an env-authed one, for real and never captured for the public" {
        // Behind an edge, so env auth comes from the header; the public may sign in, mailed for real.
        val cxt = boot("ptdRealMail", mapOf(ACFG.trustEnvAuthHeader to true, PTD.publicSignInEnvVar.name to PublicSignIn.realMail.name))
        val mail = MailService.get(cxt)
        val plain = cxt.mkSubContext("plain")
        mail.deliveryFor(plain, "visitor@public.test") shouldBe MailDelivery(capture = false, transmit = true)
        val envAuthed = cxt.mkSubContext("envAuthed").also { it.envAuthEmail = "ops@$adminDomain" }
        mail.deliveryFor(envAuthed, "invitee@public.test") shouldBe MailDelivery(capture = true, transmit = false)

        // The public signs in for real: a registration completes, and its code was never held where anyone could read it.
        val visitor = TestUser.register(cxt, "visitor@public.test", "visitor1150")
        (visitor.userId > 0) shouldBe true // registered and signed in
        mail.lastEmailTo("visitor@public.test").shouldBeNull()
        // An env-authed caller's mail is held for read-back, as on any test instance.
        mail.sendEmail(envAuthed, "invitee@public.test", "Hi", "Captured.").simulated shouldBe true
        mail.lastEmailTo("invitee@public.test").shouldNotBeNull()
    }

    "every mail says the deployment is for testing, beneath the footer, in both parts" {
        val cxt = boot("ptdMailNote")
        val rendered = MailCopy.render(cxt, null, MCOPY.verifyCode, mapOf(MCOPY.codeParam to "123456"))
        rendered.text shouldContain "testing and development only"
        rendered.html shouldContain "testing and development only"
        MailCopy.render(TestInstances.default("ptdNoMailNote"), null, MCOPY.verifyCode, mapOf(MCOPY.codeParam to "123456"))
            .text shouldNotContain "testing and development only"
    }

    "on an ephemeral instance an administrator's login carries env auth, for as long as they hold allClients" {
        val cxt = boot("ptdEphemeral", mapOf(PTD.envAuthFromAdminLoginEnvVar.name to "true"))
        val mail = MailService.get(cxt)
        // An admin-domain address may sign in although it has no env auth yet, and its code goes for real, uncaptured.
        mail.deliveryFor(cxt.mkSubContext("signingIn"), "chief@$adminDomain") shouldBe MailDelivery(capture = false, transmit = true)
        val chief = TestUser.register(cxt, "chief@$adminDomain", "chief1150")
        mail.lastEmailTo("chief@$adminDomain").shouldBeNull()
        // Provisioned from the admin domain, so an administrator of every client -- and env-authed once signed in.
        TestUser.rolesOf(chief.userInfo).contains(ROLE.allClients) shouldBe true
        features(chief.client)[APP.isEnvAuthed] shouldBe true
        features(chief.client)[APP.hasTestAccess] shouldBe true
        chief.getData(SIM.list).shouldNotBeNull()

        // Read live: once allClients is taken away, the next request is not env-authed.
        val users = UserService.get(cxt)
        val row = users.queryByUserId(cxt, chief.userId).shouldNotBeNull()
        row.roles = listOf(ROLE.user, ROLE.admin)
        users.updateUser(cxt, row)
        features(chief.client)[APP.hasTestAccess] shouldBe false

        // Anyone else is refused, as on the blocked default: there is no edge to vouch for them.
        val outsider = TestHttpClient(cxt.instanceConfig)
        shouldThrow<IllegalStateException> { TestUser.register(cxt, "visitor@public.test", "visitor1150", asClient = outsider) }
        features(outsider)[APP.isEnvAuthed] shouldBe false
    }
})
