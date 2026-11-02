package com.dynamicruntime.assistant

import com.anthropic.client.AnthropicClient
import com.anthropic.client.okhttp.AnthropicOkHttpClient
import com.anthropic.core.jsonMapper
import com.anthropic.errors.AnthropicException
import com.anthropic.errors.AnthropicServiceException
import com.anthropic.models.messages.Message
import com.anthropic.models.messages.MessageCreateParams
import com.dynamicruntime.common.context.ENV
import com.dynamicruntime.common.context.KdrCxt
import com.dynamicruntime.common.exception.EXC
import com.dynamicruntime.common.exception.KdrException
import com.dynamicruntime.common.exception.SRC
import com.dynamicruntime.common.util.jsonMap
import com.dynamicruntime.common.util.toJsonStr
import java.time.Duration

/**
 * The real model: Claude, through the official Anthropic Java SDK (issue #1525). **The only file that imports the
 * SDK**, as `S3Api.kt` is for AWS's, so the rest of the module -- and a module that later wants another provider --
 * speaks our own maps ([ModelRequest], [ModelResponse]).
 *
 * The conversion goes through the SDK's own JSON mapping both ways, never field by field: the request body is read
 * into the SDK's body type, which keeps any field it has no type for, and the response is written back out whole. So
 * what is stored is what was sent and answered, thinking blocks included -- they must go back unchanged -- and a
 * field the SDK learns later costs no change here.
 *
 * **Refuses under `ENV.unit` itself**: the SDK makes its own HTTP calls, past the guard `OutboundHttpService` keeps,
 * so a unit test can never spend money or reach the network through it, whatever its configuration says.
 *
 * The client is made on first use (holding the key, not a connection, until then), and closed at shutdown; a call after
 * that is refused rather than making a client nothing would close.
 */
class AnthropicModel(private val apiKey: String, private val betas: List<String> = defaultBetas) : AssistantModel, AutoCloseable {
    override val label: String = "Anthropic API"

    @Volatile
    private var client: AnthropicClient? = null

    /** Set at shutdown: a call still running then must not make a client nothing will close. */
    @Volatile
    private var closed = false

    private fun client(): AnthropicClient = client ?: synchronized(this) {
        if (closed) throw KdrException("The Anthropic model is closed: this node is shutting down.", code = EXC.notResponding)
        // One retry, and a five-minute timeout per attempt: a turn's lease (TURN.leaseMinutes) must outlast a step's
        // worst case, which the SDK's defaults (two retries, ten minutes) would not.
        client ?: AnthropicOkHttpClient.builder().apiKey(apiKey).timeout(Duration.ofMinutes(TURN.callTimeoutMinutes))
            .maxRetries(TURN.callRetries).build().also { client = it }
    }

    override fun respond(cxt: KdrCxt, request: ModelRequest): ModelResponse {
        if (cxt.instanceConfig.env == ENV.unit) {
            throw KdrException(
                "The Anthropic model is not available in the unit test environment -- a test hands in a " +
                    "ScriptedModel under '${AssistantService.modelConfigKey}' rather than calling the real one.",
            )
        }
        val params = MessageCreateParams.builder().body(sdkBody(request.toWire())).apply {
            if (betas.isNotEmpty()) putAdditionalHeader(betaHeader, betas.joinToString(","))
        }.build()
        val message = try {
            client().messages().create(params)
        } catch (e: AnthropicServiceException) {
            // A 429 or a 5xx may pass; anything else (a 400 for a request this code built wrong, a 401 for a bad key)
            // will not, and says so by its code.
            val code = when (val status = e.statusCode()) {
                EXC.tooManyRequests -> EXC.tooManyRequests
                in 500..599 -> EXC.notResponding
                else -> if (status in 400..499) EXC.internalError else EXC.notResponding
            }
            throw KdrException("The model answered ${e.statusCode()}: ${e.message}", e, code, SRC.network)
        } catch (e: AnthropicException) {
            throw KdrException("The model could not be reached: ${e.message}", e, EXC.notResponding, SRC.network)
        }
        return ModelResponse.fromWire(fromSdk(message))
    }

    override fun close() {
        synchronized(this) {
            closed = true
            client?.close()
            client = null
        }
    }

    @Suppress("ConstPropertyName")
    companion object {
        const val betaHeader = "anthropic-beta"

        /** Server-side fallbacks named by `"default"` (a refusal is retried on another model). */
        const val fallbackBeta = "server-side-fallback-2026-07-01"

        /** Thinking's `display: "updates"`: the model's progress notes between tool calls, shown. */
        const val displayUpdatesBeta = "thinking-display-updates-2026-08-18"

        val defaultBetas = listOf(fallbackBeta, displayUpdatesBeta)

        /** A request body read into the SDK's own body type, which keeps a field it has no type for. */
        fun sdkBody(wire: Map<String, Any?>): MessageCreateParams.Body = try {
            jsonMapper().readValue(wire.toJsonStr(), MessageCreateParams.Body::class.java)
        } catch (e: Exception) {
            throw KdrException("The request could not be put in the model's form: ${e.message}", e)
        }

        /** An SDK value written out whole, as our own map. */
        fun fromSdk(value: Any): Map<String, Any?> = jsonMapper().writeValueAsString(value).jsonMap()
            ?: throw KdrException("The model's response could not be read back as JSON.")

        /**
         * [wire] through the SDK's request type and back, with no network: what [respond] would send. For a test to
         * show that nothing the request asks for is dropped on the way.
         */
        fun requestThroughSdk(wire: Map<String, Any?>): Map<String, Any?> = fromSdk(sdkBody(wire))

        /** A response body through the SDK's response type and back, with no network: what [respond] would store. */
        fun responseThroughSdk(raw: Map<String, Any?>): Map<String, Any?> =
            fromSdk(jsonMapper().readValue(raw.toJsonStr(), Message::class.java))
    }
}
