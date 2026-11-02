package com.dynamicruntime.assistant

import com.dynamicruntime.common.context.KdrCxt
import com.dynamicruntime.common.util.toJsonListOfMaps
import com.dynamicruntime.common.util.toJsonMapOrEmpty
import com.dynamicruntime.common.util.toOptLong
import com.dynamicruntime.common.util.toOptStr

/**
 * The model the assistant talks to (issue #1525): one request in, one response out, over **our own maps in the Messages
 * API's wire shape**. So the turn loop, the stored conversation, and the tests never touch a provider's types: the real
 * model is [AnthropicModel], a test's is a [ScriptedModel].
 *
 * Called on a worker's thread, never inside a database transaction (a call takes seconds). A failure to reach the
 * model is a [com.dynamicruntime.common.exception.KdrException] whose code says whether trying again later could help.
 */
interface AssistantModel {
    /** What this model is, in words, for the operator's view. */
    val label: String

    fun respond(cxt: KdrCxt, request: ModelRequest): ModelResponse
}

/** Wire names of a Messages API request and response. Each matches its value. */
@Suppress("ConstPropertyName")
object MSG {
    const val model = "model"
    const val maxTokens = "max_tokens"
    const val system = "system"
    const val tools = "tools"
    const val messages = "messages"
    const val thinking = "thinking"
    const val outputConfig = "output_config"
    const val effort = "effort"
    const val fallbacks = "fallbacks"
    const val cacheControl = "cache_control"
    const val type = "type"
    const val display = "display"
    const val role = "role"
    const val content = "content"
    const val text = "text"
    const val stopReason = "stop_reason"
    const val usage = "usage"
    const val inputTokens = "input_tokens"
    const val outputTokens = "output_tokens"
    const val cacheCreationInputTokens = "cache_creation_input_tokens"
    const val cacheReadInputTokens = "cache_read_input_tokens"

    // Values.
    const val adaptive = "adaptive"
    const val updates = "updates"
    const val ephemeral = "ephemeral"
    const val default = "default"
    const val user = "user"
    const val assistant = "assistant"

    // Stop reasons.
    const val endTurn = "end_turn"
    const val toolUse = "tool_use"
}

/**
 * One request to the model, in the Messages API's shape: [system] blocks, [tools] definitions, and the conversation's
 * [messages] exactly as stored, so thinking blocks go back unchanged. [effort] and [maxTokens] come from the
 * deployment's settings ([AssistantSettings]).
 *
 * Its defaults (proposed in #1525): adaptive thinking with progress updates shown (`display: "updates"`), tool choice
 * left to the model -- forced tool use is refused by the models this targets -- server-side fallbacks on a refusal, and
 * automatic prompt caching of the conversation's prefix.
 */
class ModelRequest(
    val model: String,
    val maxTokens: Int,
    val system: List<Map<String, Any?>>,
    val tools: List<Map<String, Any?>>,
    val messages: List<Map<String, Any?>>,
    val effort: String?,
) {
    /** The request body, as the Messages API reads it. */
    fun toWire(): Map<String, Any?> = buildMap {
        put(MSG.model, model)
        put(MSG.maxTokens, maxTokens)
        if (system.isNotEmpty()) put(MSG.system, system)
        if (tools.isNotEmpty()) put(MSG.tools, tools)
        put(MSG.messages, messages)
        put(MSG.thinking, mapOf(MSG.type to MSG.adaptive, MSG.display to MSG.updates))
        if (effort != null) put(MSG.outputConfig, mapOf(MSG.effort to effort))
        put(MSG.fallbacks, MSG.default)
        put(MSG.cacheControl, mapOf(MSG.type to MSG.ephemeral))
    }
}

/**
 * What the model answered: the assistant [message] to append to the conversation as it came (role and content
 * blocks, nothing dropped), why it stopped ([stopReason]; null only if the response named none), the [model] that
 * answered -- with fallbacks it may not be the one asked -- and the [usage] it was billed. [raw] is the whole response.
 */
class ModelResponse(
    val message: Map<String, Any?>,
    val stopReason: String?,
    val model: String,
    val usage: ModelUsage,
    val raw: Map<String, Any?>,
) {
    /** The content blocks of [message]. */
    val content: List<Map<String, Any?>> get() = message[MSG.content].toJsonListOfMaps()

    companion object {
        /** Reads a Messages API response body. */
        fun fromWire(raw: Map<String, Any?>): ModelResponse = ModelResponse(
            message = mapOf(MSG.role to (raw[MSG.role].toOptStr() ?: MSG.assistant), MSG.content to raw[MSG.content].toJsonListOfMaps()),
            stopReason = raw[MSG.stopReason].toOptStr(),
            model = raw[MSG.model].toOptStr().orEmpty(),
            usage = ModelUsage.fromWire(raw[MSG.usage].toJsonMapOrEmpty()),
            raw = raw,
        )
    }
}

/**
 * The tokens one response was billed for, by kind. [weighted] is what a client's monthly budget counts (#1525):
 * proportional to cost at Sonnet prices, and steady whether or not the cache hits, so a budget means the same thing
 * from one month to the next.
 */
class ModelUsage(
    val inputTokens: Long = 0,
    val cacheWriteTokens: Long = 0,
    val cacheReadTokens: Long = 0,
    val outputTokens: Long = 0,
) {
    /**
     * The weighted tokens, at the [WEIGHT] table's rates, rounded up, and scaled by what [model] costs beside Sonnet
     * (a fallback may answer on a dearer model). An unknown model is counted at the dearest rate known, so a budget
     * errs toward stopping early.
     */
    fun weighted(model: String): Long {
        val hundredths = inputTokens * WEIGHT.input + cacheWriteTokens * WEIGHT.cacheWrite +
            cacheReadTokens * WEIGHT.cacheRead + outputTokens * WEIGHT.output
        return (hundredths * WEIGHT.modelFactor(model) + 9_999) / 10_000
    }

    companion object {
        fun fromWire(usage: Map<String, Any?>): ModelUsage = ModelUsage(
            inputTokens = usage[MSG.inputTokens].toOptLong() ?: 0,
            cacheWriteTokens = usage[MSG.cacheCreationInputTokens].toOptLong() ?: 0,
            cacheReadTokens = usage[MSG.cacheReadInputTokens].toOptLong() ?: 0,
            outputTokens = usage[MSG.outputTokens].toOptLong() ?: 0,
        )
    }
}

/**
 * The weights of the budget unit, in hundredths of an input token at Sonnet's price (issue #1525): input 1, cache
 * write 1.25, cache read 0.1, output 5. And each model's price beside Sonnet's, in hundredths, for a response a
 * fallback answered.
 */
@Suppress("ConstPropertyName")
object WEIGHT {
    const val input = 100L
    const val cacheWrite = 125L
    const val cacheRead = 10L
    const val output = 500L

    /** Sonnet's own price (Sonnet 5 and 5.5 cost the same): a factor of 1. */
    const val sonnet = 100L

    /** The dearest model a fallback is known to answer on (Opus 4.8 and Opus 5, at 2.5 times Sonnet's input price). */
    const val dearest = 250L

    /** [model]'s price beside Sonnet's, in hundredths. */
    fun modelFactor(model: String): Long = when {
        model.startsWith("claude-sonnet-5") -> sonnet
        model.startsWith("claude-opus-5-5") -> 200L
        else -> dearest
    }
}
