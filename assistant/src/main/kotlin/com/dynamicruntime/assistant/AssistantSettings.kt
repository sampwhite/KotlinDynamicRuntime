package com.dynamicruntime.assistant

import com.dynamicruntime.common.annotation.KdrPrivate
import com.dynamicruntime.common.assistant.ASST
import com.dynamicruntime.common.context.ENVGRP
import com.dynamicruntime.common.context.EnvVarDef
import com.dynamicruntime.common.context.KdrCxt
import com.dynamicruntime.common.exception.KdrException

/** The assistant's environment variables (issue #1525), declared once, as every `KDR_*` variable is. */
@Suppress("ConstPropertyName")
object AENV {
    const val anthropicProvider = "anthropic"
    const val fakeProvider = "fake"
    const val defaultModel = "claude-sonnet-5-5"
    const val defaultEffort = "medium"
    const val defaultMaxSteps = 20
    const val defaultMaxTokens = 16_000
    const val defaultTurnsPerHour = 20
    const val defaultConcurrentTurns = 3
    const val defaultMonthlyBudget = 10_000_000L
    const val defaultKeySecret = "anthropicApiKey"

    /**
     * The most tokens a call may be asked to write: what a model writes within one call's timeout
     * (`TURN.callTimeoutMinutes`) without streaming. A call that times out is billed, but no usage comes back to count
     * against the budget, so a larger allowance would be spent unseen.
     */
    const val maxOutputTokens = 32_000

    /** The efforts a request may ask for. */
    val efforts = listOf("low", "medium", "high", "xhigh", "max")

    /**
     * The models the assistant may ask: those that accept everything its request asks for -- adaptive thinking with
     * progress updates shown (`display: "updates"`) and server-side fallbacks. Another would answer every call with a 400.
     */
    val models = listOf("claude-sonnet-5-5", "claude-opus-5-5", "claude-fable-5-1", "claude-fable-5", "claude-mythos-5-1")

    val provider = EnvVarDef(
        "KDR_ASSISTANT_PROVIDER", group = ENVGRP.assistant, defaultDoc = anthropicProvider,
        description = "What answers the workflow assistant: `$anthropicProvider` (Claude, through the Anthropic API, when " +
            "the deployment holds a key) or `$fakeProvider` (a built-in demo that answers with canned text, for trying the " +
            "page locally with no key and no spending). `$fakeProvider` is refused at boot on a node that is not a test instance.",
    )
    val model = EnvVarDef(
        "KDR_ASSISTANT_MODEL", group = ENVGRP.assistant, defaultDoc = defaultModel,
        description = "The Claude model the assistant asks: one of ${models.joinToString(", ")}, the models that accept " +
            "what its request asks for (adaptive thinking with progress updates, and server-side fallbacks). Another refuses the boot.",
    )
    val effort = EnvVarDef(
        "KDR_ASSISTANT_EFFORT", group = ENVGRP.assistant, defaultDoc = defaultEffort,
        description = "How hard the model thinks: one of ${efforts.joinToString(", ")}. Higher costs more per turn.",
    )
    val maxSteps = EnvVarDef(
        "KDR_ASSISTANT_MAX_STEPS", group = ENVGRP.assistant, defaultDoc = "$defaultMaxSteps",
        description = "The most model calls one turn may make (each tool round is one) before it stops and says so.",
    )
    val maxTokens = EnvVarDef(
        "KDR_ASSISTANT_MAX_TOKENS", group = ENVGRP.assistant, defaultDoc = "$defaultMaxTokens",
        description = "The most tokens one model call may write, thinking included; at most $maxOutputTokens.",
    )
    val turnsPerHour = EnvVarDef(
        "KDR_ASSISTANT_TURNS_PER_HOUR", group = ENVGRP.assistant, defaultDoc = "$defaultTurnsPerHour",
        description = "The most turns (messages sent to the assistant) one user may start in an hour.",
    )
    val concurrentTurns = EnvVarDef(
        "KDR_ASSISTANT_CONCURRENT_TURNS", group = ENVGRP.assistant, defaultDoc = "$defaultConcurrentTurns",
        description = "The most turns that may run at once in one client, across its administrators and nodes.",
    )
    val monthlyBudget = EnvVarDef(
        "KDR_ASSISTANT_MONTHLY_BUDGET", group = ENVGRP.assistant, defaultDoc = "$defaultMonthlyBudget",
        description = "A client's monthly budget in weighted tokens (input 1, cache write 1.25, cache read 0.1, output 5, " +
            "at Sonnet's prices), when an operator has set none of its own. A sandbox counts against its parent's.",
    )
    val keySecret = EnvVarDef(
        ASST.keySecretEnvVar, group = ENVGRP.assistant, defaultDoc = defaultKeySecret,
        description = "The name of the secret in `private/secrets.properties` holding the Anthropic API key. With no such " +
            "secret the assistant is unavailable, and the operator's view names the one it looked for. A test's node " +
            "reads `${ASST.noKeyInUnitTests}`, which no secrets file holds, unless the test names another.",
    )
}

/**
 * The deployment's assistant settings, read once at boot ([read]) from [AENV]'s variables. A value that cannot be
 * one -- an unknown provider or effort, a count that is not a positive number -- refuses the boot rather than being
 * quietly replaced, so a mistyped setting is found when it is made.
 */
class AssistantSettings(
    val provider: String,
    val model: String,
    val effort: String,
    val maxSteps: Int,
    val maxTokens: Int,
    val turnsPerHour: Int,
    val concurrentTurns: Int,
    val defaultMonthlyBudget: Long,
    val keySecret: String,
) {
    companion object {
        fun read(cxt: KdrCxt): AssistantSettings {
            val provider = text(cxt, AENV.provider) ?: AENV.anthropicProvider
            if (provider != AENV.anthropicProvider && provider != AENV.fakeProvider) {
                throw configError("${AENV.provider.name} is '$provider'; it must be '${AENV.anthropicProvider}' or '${AENV.fakeProvider}'.")
            }
            val effort = text(cxt, AENV.effort) ?: AENV.defaultEffort
            if (effort !in AENV.efforts) {
                throw configError("${AENV.effort.name} is '$effort'; it must be one of ${AENV.efforts.joinToString(", ")}.")
            }
            val model = text(cxt, AENV.model) ?: AENV.defaultModel
            if (model !in AENV.models) {
                throw configError("${AENV.model.name} is '$model'; it must be one of ${AENV.models.joinToString(", ")}.")
            }
            return AssistantSettings(
                provider = provider,
                model = model,
                effort = effort,
                maxSteps = count(cxt, AENV.maxSteps, AENV.defaultMaxSteps.toLong()).toInt(),
                maxTokens = count(cxt, AENV.maxTokens, AENV.defaultMaxTokens.toLong(), max = AENV.maxOutputTokens.toLong()).toInt(),
                turnsPerHour = count(cxt, AENV.turnsPerHour, AENV.defaultTurnsPerHour.toLong()).toInt(),
                concurrentTurns = count(cxt, AENV.concurrentTurns, AENV.defaultConcurrentTurns.toLong()).toInt(),
                defaultMonthlyBudget = count(cxt, AENV.monthlyBudget, AENV.defaultMonthlyBudget, max = Long.MAX_VALUE / 2),
                keySecret = text(cxt, AENV.keySecret) ?: AENV.defaultKeySecret,
            )
        }

        @KdrPrivate
        fun text(cxt: KdrCxt, def: EnvVarDef): String? = cxt.getEnvVar(def)?.trim()?.ifEmpty { null }

        @KdrPrivate
        fun count(cxt: KdrCxt, def: EnvVarDef, default: Long, max: Long = Int.MAX_VALUE.toLong()): Long {
            val value = text(cxt, def) ?: return default
            return value.toLongOrNull()?.takeIf { it in 1..max }
                ?: throw configError("${def.name} is '$value'; it must be a whole number from 1 to $max.")
        }

        @KdrPrivate
        fun configError(message: String) = KdrException("Refusing to start: $message")
    }
}
