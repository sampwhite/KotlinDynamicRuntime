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
    const val iterations = "iterations"

    // Values.
    const val adaptive = "adaptive"
    const val updates = "updates"
    const val ephemeral = "ephemeral"
    const val default = "default"
    const val user = "user"
    const val assistant = "assistant"

    // Stop reasons; `max_tokens` is [maxTokens], the request field of the same name.
    const val endTurn = "end_turn"
    const val toolUse = "tool_use"
    const val pauseTurn = "pause_turn"
    const val refusal = "refusal"
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
 * The tokens one response was billed for, by kind, summed over its [attempts]. With server-side fallbacks a response
 * may be several attempts -- a model that declined, then the one that answered, each billed at its own model's price --
 * and the top-level `usage` covers only the last, so the attempts come from `usage.iterations` when the response lists
 * them, and are the top-level `usage` alone when it does not.
 *
 * [weighted] is what a client's monthly budget counts (#1525): proportional to cost, in input tokens at Sonnet's price,
 * so a budget means the same thing from one month to the next whether or not the cache hits.
 */
class ModelUsage(val attempts: List<ModelAttempt>) {
    /** One attempt's usage, for a test or a response with a single attempt. */
    constructor(
        inputTokens: Long = 0,
        cacheWriteTokens: Long = 0,
        cacheReadTokens: Long = 0,
        outputTokens: Long = 0,
        model: String? = null,
    ) : this(listOf(ModelAttempt(model, inputTokens, cacheWriteTokens, cacheReadTokens, outputTokens)))

    val inputTokens: Long get() = attempts.sumOf { it.inputTokens }
    val cacheWriteTokens: Long get() = attempts.sumOf { it.cacheWriteTokens }
    val cacheReadTokens: Long get() = attempts.sumOf { it.cacheReadTokens }
    val outputTokens: Long get() = attempts.sumOf { it.outputTokens }

    /**
     * The weighted tokens, rounded up: each attempt at its own model's [PRICE], and an attempt that names no model at
     * the dearest, kind by kind, of [unnamedModels] -- the model asked and the model that answered -- so a budget errs
     * toward stopping early rather than late.
     */
    fun weighted(unnamedModels: List<String>): Long {
        val unnamed = unnamedModels.map { PRICE.of(it) }.reduceOrNull(ModelPrice::dearer) ?: PRICE.dearest
        val cost = attempts.sumOf { (it.model?.let(PRICE::of) ?: unnamed).cost(it) }
        return (cost + PRICE.unit - 1) / PRICE.unit
    }

    companion object {
        /** Reads a response's `usage`: its `iterations` when it lists any, else the top level as one attempt. */
        fun fromWire(usage: Map<String, Any?>): ModelUsage {
            val iterations = usage[MSG.iterations].toJsonListOfMaps()
            return ModelUsage(if (iterations.isNotEmpty()) iterations.map(ModelAttempt::fromWire) else listOf(ModelAttempt.fromWire(usage)))
        }
    }
}

/** One billed attempt within a response: the [model] that ran it, when the response names it, and its tokens by kind. */
class ModelAttempt(
    val model: String?,
    val inputTokens: Long,
    val cacheWriteTokens: Long,
    val cacheReadTokens: Long,
    val outputTokens: Long,
) {
    companion object {
        fun fromWire(usage: Map<String, Any?>): ModelAttempt = ModelAttempt(
            model = usage[MSG.model].toOptStr(),
            inputTokens = usage[MSG.inputTokens].toOptLong() ?: 0,
            cacheWriteTokens = usage[MSG.cacheCreationInputTokens].toOptLong() ?: 0,
            cacheReadTokens = usage[MSG.cacheReadInputTokens].toOptLong() ?: 0,
            outputTokens = usage[MSG.outputTokens].toOptLong() ?: 0,
        )
    }
}

/** A model's price per kind of token, in cents per million tokens; cache writes at the five-minute rate. */
class ModelPrice(val input: Long, val cacheWrite: Long, val cacheRead: Long, val output: Long) {
    /** What [attempt] cost, in cents per million tokens times tokens. */
    fun cost(attempt: ModelAttempt): Long = attempt.inputTokens * input + attempt.cacheWriteTokens * cacheWrite +
        attempt.cacheReadTokens * cacheRead + attempt.outputTokens * output

    /** The dearer of this and [other], kind by kind. */
    fun dearer(other: ModelPrice): ModelPrice = ModelPrice(
        maxOf(input, other.input), maxOf(cacheWrite, other.cacheWrite), maxOf(cacheRead, other.cacheRead), maxOf(output, other.output),
    )
}

/**
 * The prices a budget is counted in (issue #1525), as published for the models the assistant may ask or fall back to.
 * One weighted token is an input token at Sonnet's price ([unit]), which makes Sonnet's weights input 1, cache write
 * 1.25, cache read 0.1, and output 5. A model not listed is counted at [dearest], so a new one cannot spend more than
 * the budget says before it is added here.
 */
@Suppress("ConstPropertyName")
object PRICE {
    /** Sonnet's input price: what one weighted token costs. */
    const val unit = 200L

    /** Weighted tokens to the dollar: 500,000, at [unit]. */
    const val tokensPerDollar = 100L * 1_000_000 / unit

    /** [weighted] tokens in dollars, to the cent. */
    fun dollarsOf(weighted: Long): Double = Math.round(weighted * 100.0 / tokensPerDollar) / 100.0

    /** [dollars] in weighted tokens, to the token. */
    fun weightedOf(dollars: Double): Long = Math.round(dollars * tokensPerDollar)

    val sonnet = ModelPrice(input = 200, cacheWrite = 250, cacheRead = 20, output = 1_000)
    val opus55 = ModelPrice(input = 400, cacheWrite = 500, cacheRead = 20, output = 2_000)
    val opus = ModelPrice(input = 500, cacheWrite = 625, cacheRead = 50, output = 2_500)
    val fable51 = ModelPrice(input = 1_000, cacheWrite = 1_250, cacheRead = 25, output = 5_000)
    val fable5 = ModelPrice(input = 1_000, cacheWrite = 1_250, cacheRead = 100, output = 5_000)

    /** Every listed price's dearest, kind by kind. */
    val dearest: ModelPrice = listOf(sonnet, opus55, opus, fable51, fable5).reduce(ModelPrice::dearer)

    /** [model]'s price: by its id's family, Sonnet 5 and 5.5 alike; [dearest] for a model not listed. */
    fun of(model: String): ModelPrice = when {
        model.startsWith("claude-sonnet-5") -> sonnet
        model.startsWith("claude-opus-5-5") -> opus55
        model.startsWith("claude-opus-5") || model.startsWith("claude-opus-4-8") -> opus
        model.startsWith("claude-fable-5-1") || model.startsWith("claude-mythos-5-1") -> fable51
        model.startsWith("claude-fable-5") || model.startsWith("claude-mythos-5") -> fable5
        else -> dearest
    }
}
