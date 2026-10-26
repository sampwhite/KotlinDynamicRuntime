package com.dynamicruntime.kdn

import com.dynamicruntime.common.app.APP
import com.dynamicruntime.common.content.UIC
import com.dynamicruntime.common.context.ENV
import com.dynamicruntime.common.exception.EXC
import com.dynamicruntime.common.gedra.CLD
import com.dynamicruntime.common.gedra.ClientAudience
import com.dynamicruntime.common.gedra.ClientService
import com.dynamicruntime.common.gedra.ClientUsageType
import com.dynamicruntime.common.http.request.ROLE
import com.dynamicruntime.common.user.ADEP
import com.dynamicruntime.common.user.SessionLifetime
import com.dynamicruntime.common.user.TestUser
import com.dynamicruntime.common.user.UADEP
import com.dynamicruntime.common.util.toJsonMapOrEmpty
import com.dynamicruntime.common.endpoint.HttpMethod
import com.dynamicruntime.common.gedra.ClientConfigIssues
import com.dynamicruntime.common.gedra.ClientDef
import com.dynamicruntime.common.gedra.GCFG
import com.dynamicruntime.common.gedra.GedraConfigReload
import com.dynamicruntime.common.gedra.GedraConfigService
import com.dynamicruntime.common.gedra.gedraConfig
import com.dynamicruntime.common.http.request.TestHttpClient
import com.dynamicruntime.common.naming.clientNamespace
import com.dynamicruntime.common.startup.BootCheckMode
import com.dynamicruntime.common.endpoint.EP
import com.dynamicruntime.common.node.NodeService
import com.dynamicruntime.common.user.AEP
import com.dynamicruntime.common.user.AFLD
import com.dynamicruntime.common.user.AUTHC
import com.dynamicruntime.common.util.toOptLong
import com.dynamicruntime.common.util.toOptStr
import io.kotest.matchers.string.shouldContain
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import kotlin.time.Instant
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import kotlin.time.Duration
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes

/**
 * A client's own session lifetime and refresh interval (issue #1308): the refresh interval served per caller and set
 * by the platform only; the session lifetime set by the client's administrator, no longer than the deployment's, and
 * held to at every request so shortening it ends longer sessions at once. Its own instance: it moves the instance
 * clock, which is instance-wide.
 */
class ClientSessionSettingsTest : StringSpec({
    val cxt = Startup.mkTestBootCxt("clientSession1308", "clientSession1308")
    val operator = TestUser.createFullAdmin(cxt, "operator@session1308.test")

    fun create(clientId: String) = operator.postData(
        ADEP.clientCreate,
        mapOf(
            CLD.clientId to clientId, CLD.name to "Client $clientId", CLD.usageType to ClientUsageType.dev.name,
            CLD.audience to ClientAudience.internal.name, CLD.enabledEnvironments to listOf(ENV.unit, ENV.local),
            CLD.starterWorkflows to false,
        ),
    )

    fun idleInterval(user: TestUser): Any? = user.getData(APP.uiConfig)[UIC.settings].toJsonMapOrEmpty()[APP.idleBumpIntervalMs]

    // Asked as the idle poll asks, so that asking past half a lifetime does not renew the session (issue #1372).
    fun signedIn(user: TestUser): Boolean = user.isSignedIn()

    "the refresh interval is the caller's client's, else the deployment's, and only the platform sets it" {
        val tuned = "tuned1308"
        val plain = "plain1308"
        create(tuned)
        create(plain)
        val chief = TestUser.create(cxt, "chief@$tuned.test", level = ROLE.admin, userClient = tuned)
        chief.expectError(
            EXC.notAuthorized, UADEP.clientDefinitionSet, mapOf(CLD.client to tuned, CLD.refreshIntervalSeconds to "20"),
        )
        operator.postData(UADEP.clientDefinitionSet, mapOf(CLD.client to tuned, CLD.refreshIntervalSeconds to "20"))
        ClientService.get(cxt).known(tuned)!!.refreshIntervalSeconds shouldBe 20
        idleInterval(chief) shouldBe 20_000
        idleInterval(TestUser.create(cxt, "u@$plain.test", userClient = plain)) shouldBe APP.defaultIdleBumpIntervalMs
        // Out of bounds is refused at the write.
        operator.expectError(EXC.badInput, UADEP.clientDefinitionSet, mapOf(CLD.client to tuned, CLD.refreshIntervalSeconds to "1"))
        // The definition read says what an unset one means.
        val defaults = chief.getItem(UADEP.clientDefinition)[CLD.platformDefaults].toJsonMapOrEmpty()
        defaults[CLD.refreshIntervalSeconds] shouldBe APP.defaultIdleBumpIntervalMs / 1000
        defaults[CLD.sessionHours] shouldBe SessionLifetime.defaultHours
        defaults[CLD.maxSessionHours] shouldBe SessionLifetime.defaultMaxHours
    }

    "a client's shorter session lifetime holds at once, and one past the deployment's limit is refused" {
        val client = "short1308"
        create(client)
        val chief = TestUser.create(cxt, "chief@$client.test", level = ROLE.admin, userClient = client)
        val person = TestUser.create(cxt, "person@$client.test", userClient = client)
        chief.expectError(
            EXC.badInput, UADEP.clientDefinitionSet,
            mapOf(CLD.client to client, CLD.sessionHours to (SessionLifetime.defaultMaxHours + 1).toString()),
        )
        // Three hours into a session made under the deployment's two days, the client shortens its lifetime to two
        // hours: the session ends at its next request, though its cookie was issued for two days.
        cxt.instanceConfig.clock.advanceBy(3.hours)
        signedIn(person) shouldBe true
        chief.postData(UADEP.clientDefinitionSet, mapOf(CLD.client to client, CLD.sessionHours to "2"))
        signedIn(person) shouldBe false
        // A sign-in now lasts the two hours.
        val again = TestUser.create(cxt, "again@$client.test", userClient = client)
        cxt.instanceConfig.clock.advanceBy(1.hours)
        signedIn(again) shouldBe true
        cxt.instanceConfig.clock.advanceBy(2.hours)
        signedIn(again) shouldBe false
        // Longer than the default, up to the limit: a sign-in made since outlasts the deployment's two days. (A
        // cookie's expiry is set when it is issued, so lengthening reaches the sign-ins after it.)
        operator.postData(UADEP.clientDefinitionSet, mapOf(CLD.client to client, CLD.sessionHours to "100"))
        val longer = TestUser.create(cxt, "longer@$client.test", userClient = client)
        cxt.instanceConfig.clock.advanceBy((SessionLifetime.defaultHours + 1).hours)
        signedIn(longer) shouldBe true
        cxt.instanceConfig.clock.advanceBy((100 - SessionLifetime.defaultHours).hours)
        signedIn(longer) shouldBe false
        // Cleared, the deployment's lifetime is back -- by an operator signed in now, the first one's two days being past.
        TestUser.createFullAdmin(cxt, "later@session1308.test").postData(UADEP.clientDefinitionSet, mapOf(CLD.client to client, CLD.sessionHours to ""))
        ClientService.get(cxt).known(client)!!.sessionHours shouldBe null
    }

    "the device cookie keeps its own 60 days, however short the session" {
        // Issue #1308: a short session must not take the familiar device with it.
        // Registered through the real flow, whose last step signs in and so writes both cookies.
        val browser = TestHttpClient(cxt.instanceConfig)
        val address = "device@session1308.test"
        val token = browser.sendJsonGetRequest(AEP.createToken)[EP.results].toJsonMapOrEmpty()[AFLD.formAuthToken].toOptStr()!!
        browser.sendJsonPostRequest(
            AEP.newContactSendVerify,
            mapOf(AFLD.contactAddress to address, AFLD.contactType to "email", AFLD.formAuthToken to token),
        )
        val code = NodeService.get(cxt).computeVerifyCode(token, address)
        val userId = browser.sendJsonPutRequest(
            AEP.createInitial,
            mapOf(AFLD.contactAddress to address, AFLD.contactType to "email", AFLD.formAuthToken to token, AFLD.verifyCode to code),
        )[EP.results].toJsonMapOrEmpty()[AFLD.userId].toOptLong()!!
        val handler = browser.sendEditRequest(
            AEP.setLoginData, null,
            mapOf(AFLD.userId to userId, AFLD.username to "device1308", AFLD.formAuthToken to token, AFLD.verifyCode to code),
            HttpMethod.PUT,
        )
        fun expiresOf(name: String): Instant {
            val header = handler.rptResponseHeaders["set-cookie"].orEmpty().singleOrNull { it.startsWith("$name=") }
                ?: error("No $name cookie in ${handler.rptResponseHeaders} (status ${handler.rptStatusCode})")
            val expires = header.split(';').map { it.trim() }.single { it.startsWith("Expires=") }.substringAfter('=')
            return Instant.fromEpochMilliseconds(
                ZonedDateTime.parse(expires, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant().toEpochMilli(),
            )
        }
        // Within a minute of the expected: a cookie's expiry is written to the second, and taken a moment before this.
        fun near(actual: Instant, expected: Duration) = ((actual - cxt.instanceNow()) - expected).absoluteValue < 1.minutes
        near(expiresOf(AUTHC.authCookie), SessionLifetime.defaultHours.hours) shouldBe true
        near(expiresOf(AUTHC.deviceCookie), 60.days) shouldBe true
    }

    "a stored lifetime past a limit lowered since is dropped at load, and the client's sessions take the default" {
        // Its own instance: a limit of 100 hours, and stored problems reported rather than refused, as a load away
        // from the unit tests does.
        val lowered = Startup.mkTestBootCxt(
            "lowered1308", "lowered1308",
            mapOf(SessionLifetime.sessionMaxHoursEnvVar.name to "100", GCFG.storedCheckEnvVar.name to BootCheckMode.warn.name),
        )
        val client = "lowered1308"
        val config = gedraConfig(lowered, "${client}cfg", clientNamespace(client), client) {
            defineClient(
                ClientDef(
                    clientId = client, name = client, usageType = ClientUsageType.dev, audience = ClientAudience.internal,
                    enabledEnvironments = setOf(ENV.unit, ENV.local), sessionHours = 200,
                ),
            )
        }
        GedraConfigService.get(lowered).writeConfig(lowered.mkSubContext("lowered", client), config)
        GedraConfigReload.reloadClient(lowered, client)
        ClientService.get(lowered).known(client)!!.sessionHours shouldBe null
        ClientConfigIssues.get(lowered).issuesFor(client).single().message shouldContain "limit of 100"
        SessionLifetime.hoursFor(lowered, client) shouldBe SessionLifetime.defaultHours
        SessionLifetime.maxHours(lowered) shouldBe 100
    }
})
