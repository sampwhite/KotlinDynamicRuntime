package com.dynamicruntime.common.http.request

import com.dynamicruntime.common.context.ACFG
import com.dynamicruntime.common.context.ENV
import com.dynamicruntime.common.context.KdrCxt
import com.dynamicruntime.common.context.KdrInstanceConfig
import com.dynamicruntime.common.context.KdrSchemaStore
import com.dynamicruntime.common.endpoint.EP
import com.dynamicruntime.common.endpoint.EndpointKind
import com.dynamicruntime.common.endpoint.HttpMethod
import com.dynamicruntime.common.endpoint.KdrEndpoint
import com.dynamicruntime.common.exception.KdrException
import com.dynamicruntime.common.node.NodeService
import com.dynamicruntime.common.schema.SCH
import com.dynamicruntime.common.schema.SCT
import com.dynamicruntime.common.startup.buildClientEndpoints
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain

/**
 * An endpoint may hold its own responses to its output schema in every environment (issue #1085), where the
 * `validateResponseSchema` flag does it for all endpoints and only where it is set -- in tests. So everything here
 * runs with the flag **off**, as a real deployment has it, except the case that says the flag still works.
 */
class ValidateOutputTest : StringSpec({

    /**
     * An output schema with something to get wrong: `results.count` is a whole number. Open at the envelope, which
     * a served response fills with protocol fields (the request's address, its duration) this test is not about.
     */
    val output = mapOf(
        SCH.type to SCT.kObject,
        SCH.additionalProperties to true,
        SCH.properties to mapOf(
            EP.results to mapOf(
                SCH.type to SCT.kObject,
                SCH.properties to mapOf("count" to mapOf(SCH.type to SCT.integer)),
            ),
        ),
    )

    fun endpoint(validateOutput: Boolean, outputSchema: Map<String, Any?> = output, clientShaped: Boolean = false) = KdrEndpoint(
        path = "/user/thing", method = HttpMethod.GET, kind = EndpointKind.general, namespace = "t",
        description = "d", inputFields = null, inputTypeRef = null, includeLimit = false,
        outputSchema = outputSchema, handler = { _, _ -> emptyMap<String, Any?>() }, clientShaped = clientShaped,
        validateOutput = validateOutput,
    )

    /** A context whose instance config holds the flag as [flag] says, or not at all for null -- a deployment's default. */
    fun cxtWith(flag: Boolean?): KdrCxt {
        val config = KdrInstanceConfig("validateOutput", ENV.unit, ENV.liveSource, null)
        flag?.let { config.put(ACFG.validateResponseSchema, it) }
        return KdrCxt("validateOutput", config)
    }

    val store = KdrSchemaStore()
    val good = mapOf(EP.results to mapOf("count" to 3))
    val bad = mapOf(EP.results to mapOf("count" to "several"))

    /** A fresh service per call, so no case reads a type another compiled. */
    fun check(cxt: KdrCxt, endpoint: KdrEndpoint, envelope: Map<String, Any?>) =
        RequestService().validateResponse(cxt, store, endpoint, envelope)

    "an endpoint that asks is held to its output schema with the flag off" {
        for (flag in listOf(null, false)) {
            val refused = shouldThrow<KdrException> { check(cxtWith(flag), endpoint(validateOutput = true), bad) }
            // The 500 the flag gives: it names the endpoint and says what did not conform.
            // Each failure as its path and the validator's message -- this reaches a real caller.
            refused.message shouldBe "Response for '/user/thing:GET' failed output-schema validation: " +
                "results.count: 'several' is not a valid integer."
            // And a response that conforms is let through.
            check(cxtWith(flag), endpoint(validateOutput = true), good)
        }
    }

    "an endpoint that does not ask is not checked with the flag off" {
        check(cxtWith(null), endpoint(validateOutput = false), bad)
        check(cxtWith(false), endpoint(validateOutput = false), bad)
    }

    "the flag still checks every endpoint, asked or not" {
        shouldThrow<KdrException> { check(cxtWith(true), endpoint(validateOutput = false), bad) }
        shouldThrow<KdrException> { check(cxtWith(true), endpoint(validateOutput = true), bad) }
    }

    "an endpoint with no output schema has nothing to be held to" {
        check(cxtWith(null), endpoint(validateOutput = true, outputSchema = emptyMap()), bad)
        check(cxtWith(true), endpoint(validateOutput = true, outputSchema = emptyMap()), bad)
    }

    // The cases above ask the check itself. This one asks what serves a request: that it reaches the check for an
    // endpoint that asked, with the flag off, and that a refused response is never sent.
    "serving a request holds the response to the schema before anything is sent" {
        fun serve(validateOutput: Boolean, payload: Map<String, Any?>): RequestHandler {
            val config = KdrInstanceConfig("validateOutputServe", ENV.unit, ENV.liveSource, null)
            // What serving reads besides the endpoint: the schema it resolves input against, and the node, asked
            // for on the way to the auth cookie this request never sets.
            config.put(KdrSchemaStore.key, store)
            config.put(NodeService.serviceName, NodeService())
            val handler = RequestHandler(config, "GET", "/kda/user/thing", emptyMap(), mutableMapOf())
            val served = KdrEndpoint(
                path = "/user/thing", method = HttpMethod.GET, kind = EndpointKind.general, namespace = "t",
                description = "d", inputFields = null, inputTypeRef = null, includeLimit = false,
                outputSchema = output, handler = { _, _ -> payload }, validateOutput = validateOutput,
            )
            RequestService().executeEndpoint(KdrCxt("validateOutputServe", config), handler, served)
            return handler
        }
        val refused = shouldThrow<KdrException> { serve(validateOutput = true, payload = mapOf("count" to "several")) }
        refused.message shouldContain "'/user/thing:GET' failed output-schema validation: results.count:"
        // A conforming response goes out, and so does a non-conforming one from an endpoint that did not ask.
        serve(validateOutput = true, payload = mapOf("count" to 3)).rptResponseData.orEmpty() shouldContain "\"count\":3"
        serve(validateOutput = false, payload = mapOf("count" to "several")).rptResponseData.orEmpty() shouldContain "several"
    }

    "a client's copy of an endpoint keeps the promise" {
        val cxt = cxtWith(null)
        val copies = buildClientEndpoints(cxt, listOf(endpoint(validateOutput = true, clientShaped = true)), listOf("acme"))
        copies.map { it.path to it.validateOutput } shouldBe listOf("/user/acme/thing" to true)
        // Checked as the shared one is, under its own path.
        shouldThrow<KdrException> { check(cxt, copies.single(), bad) }.message shouldContain "'/user/acme/thing:GET'"
        // And a copy of one that did not ask does not start asking.
        buildClientEndpoints(cxt, listOf(endpoint(validateOutput = false, clientShaped = true)), listOf("acme")).single().validateOutput shouldBe false
    }
})
