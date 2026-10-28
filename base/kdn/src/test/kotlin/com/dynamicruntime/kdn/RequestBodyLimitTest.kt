package com.dynamicruntime.kdn

import com.dynamicruntime.common.context.ENV
import com.dynamicruntime.common.endpoint.EP
import com.dynamicruntime.common.endpoint.HttpMethod
import com.dynamicruntime.common.exception.EXC
import com.dynamicruntime.common.gedra.ACEP
import com.dynamicruntime.common.gedra.CCT
import com.dynamicruntime.common.gedra.CFEP
import com.dynamicruntime.common.gedra.ClientAudience
import com.dynamicruntime.common.gedra.ClientDef
import com.dynamicruntime.common.gedra.ClientUsageType
import com.dynamicruntime.common.gedra.GedraConfigReload
import com.dynamicruntime.common.gedra.GedraConfigService
import com.dynamicruntime.common.gedra.gedraConfig
import com.dynamicruntime.common.http.request.BODY
import com.dynamicruntime.common.http.request.RequestService
import com.dynamicruntime.common.http.request.ROLE
import com.dynamicruntime.common.http.request.TestHttpClient
import com.dynamicruntime.common.naming.clientNamespace
import com.dynamicruntime.common.operator.OPS
import com.dynamicruntime.common.user.ATOK
import com.dynamicruntime.common.user.TestUser
import com.dynamicruntime.common.util.jsonMap
import com.dynamicruntime.common.util.toJsonListOfMaps
import com.dynamicruntime.common.util.toOptStr
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain

/**
 * Request body limits (issue #1446): every endpoint takes at most [BODY.defaultMaxBytes] bytes of body, but the few
 * marked as taking large bodies -- whose input is a whole configuration -- take up to the configured maximum; a body
 * past its limit is refused with 413 before it is parsed; and the list of those endpoints, fixed at boot, can be read
 * from the node with their full paths.
 *
 * Its own instance: the large-body maximum is instance configuration, set here to [largeMax] so a body over it is
 * quick to build.
 */
class RequestBodyLimitTest : StringSpec({
    val largeMax = 40_000
    val cxt = Startup.mkTestBootCxt("bodyLimits1446", "bodyLimits1446", mapOf(BODY.largeMaxBytesKey to largeMax))
    val client = "bodies1446"
    GedraConfigService.get(cxt).writeConfig(
        cxt.mkSubContext("setup", client).also { it.userId = 14460L },
        gedraConfig(cxt, "main", clientNamespace(client), client) {
            defineClient(
                ClientDef(
                    clientId = client, name = client, usageType = ClientUsageType.dev, audience = ClientAudience.internal,
                    enabledEnvironments = setOf(ENV.unit, ENV.local),
                ),
            )
        },
    )
    GedraConfigReload.reloadClient(cxt, client)
    val admin = TestUser.create(cxt, "chief@$client.test", level = ROLE.admin, userClient = client)

    /** A bundle of [count] cfact declarations, each description [width] characters: about count × (width + 60) bytes. */
    fun cfacts(count: Int, width: Int, letter: String = "x") = mapOf(
        CFEP.name to "facts", CFEP.namespaceField to clientNamespace(client),
        CFEP.slots to mapOf(
            CCT.cfactDef to (1..count).map { mapOf(CCT.name to "fact$it", CCT.group to "flags", CCT.description to letter.repeat(width)) },
        ),
    )

    fun status(handler: com.dynamicruntime.common.http.request.RequestHandler): Pair<Int, String?> {
        val env = handler.rptResponseData?.jsonMap() ?: emptyMap()
        return handler.rptStatusCode to env[EP.errorMessage].toOptStr()
    }

    "an endpoint that does not take large bodies refuses one over the default with 413, before anything else" {
        // A publish takes a list of names: twenty thousand bytes of them is past the default, and is refused before
        // the caller is even asked who they are.
        val body = mapOf(CFEP.names to (1..2000).map { "name%05d".format(it) })
        val (code, message) = status(TestHttpClient(cxt.instanceConfig).sendEditRequest(CFEP.bundlePublish, null, body, HttpMethod.POST))
        code shouldBe EXC.payloadTooLarge
        message.orEmpty() shouldContain "larger than ${BODY.defaultMaxBytes} bytes"
        message.orEmpty() shouldContain "unless it is one that takes large bodies"
    }

    "the limit is in bytes: text past ASCII counts each of its bytes" {
        // Nine thousand characters, but eighteen thousand bytes.
        val body = mapOf(CFEP.names to listOf("é".repeat(9000)))
        status(admin.client.sendEditRequest(CFEP.bundlePublish, null, body, HttpMethod.POST)).first shouldBe EXC.payloadTooLarge
    }

    "an endpoint that takes large bodies takes one past the default, up to the configured maximum" {
        // About 25,000 bytes: a whole configuration's worth.
        admin.postData(CFEP.bundleWrite, cfacts(200, 60))
        admin.getItem(CFEP.bundle, mapOf(CFEP.name to "facts"))[CFEP.slots].let { it as Map<*, *> }[CCT.cfactDef]
            .toJsonListOfMaps().size shouldBe 200
        // Past the maximum, it too is refused, saying so.
        val (code, message) = status(admin.client.sendEditRequest(CFEP.bundleWrite, null, cfacts(400, 60), HttpMethod.POST))
        code shouldBe EXC.payloadTooLarge
        message.orEmpty() shouldContain "larger than $largeMax bytes, the most this endpoint accepts"
    }

    "a form body is held to the same limit" {
        val (code, _) = status(
            TestHttpClient(cxt.instanceConfig).sendFormRequest(ATOK.token, mapOf(ATOK.grantType to "x".repeat(BODY.defaultMaxBytes + 10))),
        )
        code shouldBe EXC.payloadTooLarge
    }

    "the node lists the endpoints that take large bodies, by method and full path, with the two limits" {
        val operator = TestUser.createOperator(cxt, "ops@bodies1446.test")
        val readout = operator.getItem(OPS.largeBodiesPath)
        readout["defaultMaxBytes"] shouldBe BODY.defaultMaxBytes.toLong()
        readout["largeMaxBytes"] shouldBe largeMax.toLong()
        val root = RequestService.get(cxt).apiContextRoot
        readout["endpoints"].toJsonListOfMaps().map { it["method"] to it["path"] } shouldContainExactlyInAnyOrder listOf(
            "POST" to "/$root${CFEP.bundleWrite}",
            "POST" to "/$root${CFEP.bundlePatch}",
            "POST" to "/$root${ACEP.bundleWrite}",
            "POST" to "/$root${ACEP.bundlePatch}",
            "POST" to "/$root${ACEP.import}",
        )
    }

    "a large-body maximum under the default refuses the start" {
        shouldThrow<Exception> {
            Startup.mkTestBootCxt("bodyLimitsLow1446", "bodyLimitsLow1446", mapOf(BODY.largeMaxBytesKey to 1000))
        }.message.orEmpty() shouldContain BODY.largeMaxBytesKey
    }
})
