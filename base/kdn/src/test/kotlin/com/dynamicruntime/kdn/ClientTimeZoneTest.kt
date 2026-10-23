package com.dynamicruntime.kdn

import com.dynamicruntime.common.content.UIC
import com.dynamicruntime.common.context.ENV
import com.dynamicruntime.common.context.KdrCxt
import com.dynamicruntime.common.exception.EXC
import com.dynamicruntime.common.gedra.CLD
import com.dynamicruntime.common.gedra.CUST
import com.dynamicruntime.common.gedra.ClientAudience
import com.dynamicruntime.common.gedra.ClientDef
import com.dynamicruntime.common.gedra.ClientService
import com.dynamicruntime.common.gedra.ClientUsageType
import com.dynamicruntime.common.gedra.GedraConfigReload
import com.dynamicruntime.common.gedra.GDF
import com.dynamicruntime.common.gedra.GE
import com.dynamicruntime.common.gedra.GEP
import com.dynamicruntime.common.gedra.GedraConfigService
import com.dynamicruntime.common.gedra.GedraDataType
import com.dynamicruntime.common.gedra.UF
import com.dynamicruntime.common.gedra.clientTimeZone
import com.dynamicruntime.common.gedra.gedraConfig
import com.dynamicruntime.common.home.HEP
import com.dynamicruntime.common.home.HFLD
import com.dynamicruntime.common.http.request.ROLE
import com.dynamicruntime.common.naming.clientNamespace
import com.dynamicruntime.common.user.ADEP
import com.dynamicruntime.common.user.TestUser
import com.dynamicruntime.common.user.UADEP
import com.dynamicruntime.common.util.genericZoneLabel
import com.dynamicruntime.common.util.serverTimeZone
import com.dynamicruntime.common.util.toJsonListOfMaps
import com.dynamicruntime.common.util.toJsonMapOrEmpty
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import kotlinx.datetime.TimeZone

/**
 * A client's time zone (issue #1115): the zone its days mean something in, chosen at creation, passed on to a client
 * its root makes, edited by its administrator, and held to being a place's IANA zone. Over the shared instance.
 */
class ClientTimeZoneTest : StringSpec({
    val cxt = TestInstances.default("timeZone1115")
    val operator = TestUser.createFullAdmin(cxt, "operator@tz1115.test")
    fun asClient(client: String): KdrCxt = cxt.mkSubContext("tz", client).also { it.userId = 11150L }

    fun createBody(clientId: String, vararg more: Pair<String, Any?>) = mapOf(
        CLD.clientId to clientId, CLD.name to "Client $clientId", CLD.usageType to ClientUsageType.dev.name,
        CLD.audience to ClientAudience.customer.name, CLD.enabledEnvironments to listOf(ENV.unit, ENV.local),
    ) + more

    "a client's zone is the one it names, and the shell says it" {
        val client = "chicago1115"
        val config = gedraConfig(cxt, "${client}cfg", clientNamespace(client), client) {
            defineClient(
                ClientDef(
                    clientId = client, name = client, usageType = ClientUsageType.dev, audience = ClientAudience.internal,
                    enabledEnvironments = setOf(ENV.unit, ENV.local), timeZone = "America/Chicago",
                ),
            )
        }
        GedraConfigService.get(cxt).writeConfig(asClient(client), config)
        GedraConfigReload.reloadClient(cxt, client)
        ClientService.get(cxt).timeZoneOf(client) shouldBe TimeZone.of("America/Chicago")
        // Where the crossings read it (`ClientZoneCrossingTest` asks each with a zone): a request in the client.
        cxt.mkSubContext("tz", client).clientTimeZone shouldBe TimeZone.of("America/Chicago")
        // And the shell says where the client is, so the webapp shows its moments there, named as the backend names it.
        val state = TestUser.create(cxt, "u@$client.test", userClient = client).getData(HEP.homeUiConfig)[UIC.state].toJsonMapOrEmpty()
        state[HFLD.timeZone] shouldBe "America/Chicago"
        state[HFLD.timeZoneLabel] shouldBe "CT"
    }

    "a zone's label is its generic name, or none where that would read as an offset (issue #1321)" {
        genericZoneLabel("America/Chicago") shouldBe "CT"
        genericZoneLabel("Asia/Kolkata") shouldBe "IST"
        // Named only by an offset; and London, which Java calls GMT all year, though summer is an hour ahead of it.
        genericZoneLabel("America/Sao_Paulo") shouldBe null
        genericZoneLabel("Europe/London") shouldBe null
        genericZoneLabel("UTC") shouldBe null
        genericZoneLabel("Mars/Olympus") shouldBe null
    }

    "a listing column's dates read in the client's zone and are labeled with it (issue #1321)" {
        val client = "chicago1321"
        val config = gedraConfig(cxt, "${client}cfg", clientNamespace(client), client) {
            defineClient(
                ClientDef(
                    clientId = client, name = client, usageType = ClientUsageType.dev, audience = ClientAudience.internal,
                    enabledEnvironments = setOf(ENV.unit, ENV.local), timeZone = "America/Chicago",
                ),
            )
            trait("DueEntry", "due", setOf(GedraDataType.formDoc), "When it is due.") { property("at", "The moment.") }
            traitUsage("due", "Due", $$"""${formatDate(at, "moment")} / ${formatDay(at)}""")
        }
        GedraConfigService.get(cxt).writeConfig(asClient(client), config)
        GedraConfigReload.reloadClient(cxt, client)
        val user = TestUser.create(cxt, "u@$client.test", userClient = client)
        // 2:30 AM on the 18th in Chicago, and still the 17th in the deployment's UTC-8.
        val entry = mapOf(GE.traitId to "due", GE.data to mapOf("at" to "2026-10-18T07:30:00.000Z"))
        user.postItem(GEP.formDocCreate, mapOf(GDF.entries to listOf(entry)))
        val shown = user.getItems(GEP.formDocs).single()[GDF.displayValues].toJsonListOfMaps().single()[UF.value]
        shown shouldBe "Oct 18, 2026, 2:30 AM CT / 2026-10-18"
    }

    "a zone is a place's IANA zone, or the client stays the deployment's" {
        operator.expectError(EXC.badInput, ADEP.clientCreate, createBody("offset1115", CLD.timeZone to "Etc/GMT+5"))
            .toString() shouldContain "is an offset or an abbreviation; name the place's zone"
        operator.expectError(EXC.badInput, ADEP.clientCreate, createBody("nowhere1115", CLD.timeZone to "Mars/Olympus"))
            .toString() shouldContain "is not an IANA time zone"
        // A client naming none is in the deployment's zone, and the shell says nothing.
        operator.postData(ADEP.clientCreate, createBody("plain1115"))
        ClientService.get(cxt).timeZoneOf("plain1115") shouldBe serverTimeZone
        TestUser.create(cxt, "u@plain1115.test", userClient = "plain1115")
            .getData(HEP.homeUiConfig)[UIC.state].toJsonMapOrEmpty().containsKey(HFLD.timeZone) shouldBe false
    }

    "a client its root makes starts in the root's zone, and its administrator changes it" {
        val root = "paris1115"
        operator.postData(
            ADEP.clientCreate,
            createBody(root, CLD.timeZone to "Europe/Paris", CLD.newCustomer to mapOf(CUST.name to "Paris Co")),
        )
        val rootAdmin = TestUser.create(cxt, "admin@paris1115.test", level = ROLE.admin, userClient = root)
        val made = rootAdmin.postData(UADEP.clientCreate, mapOf(CLD.clientId to "${root}_lyon", CLD.name to "Lyon"))
        made[CLD.definition].toJsonMapOrEmpty()[CLD.timeZone] shouldBe "Europe/Paris"

        rootAdmin.postData(UADEP.clientDefinitionSet, mapOf(CLD.timeZone to "Asia/Tokyo"))
        ClientService.get(cxt).timeZoneOf(root) shouldBe TimeZone.of("Asia/Tokyo")
        // Blank clears it, back to the deployment's.
        rootAdmin.postData(UADEP.clientDefinitionSet, mapOf(CLD.timeZone to ""))
        ClientService.get(cxt).timeZoneOf(root) shouldBe serverTimeZone
    }

    "a client naming no zone is shown in the deployment's display default, and its days stay the official day" {
        // An instance of its own: the display default is instance configuration.
        val own = Startup.mkTestBootCxt("tzDisplay1115", "tzDisplay1115Test", mapOf(ClientService.displayTimeZoneEnvVar.name to "America/Denver"))
        val ownOperator = TestUser.createFullAdmin(own, "operator@tzd1115.test")
        ownOperator.postData(ADEP.clientCreate, createBody("plaind1115"))
        ownOperator.postData(ADEP.clientCreate, createBody("tokyod1115", CLD.timeZone to "Asia/Tokyo"))
        fun shown(client: String) = TestUser.create(own, "u@$client.test", userClient = client)
            .getData(HEP.homeUiConfig)[UIC.state].toJsonMapOrEmpty()[HFLD.timeZone]
        shown("plaind1115") shouldBe "America/Denver"
        ClientService.get(own).timeZoneOf("plaind1115") shouldBe serverTimeZone
        // A client naming its own zone reads and shows its days in it, whatever the default.
        shown("tokyod1115") shouldBe "Asia/Tokyo"
    }
})
