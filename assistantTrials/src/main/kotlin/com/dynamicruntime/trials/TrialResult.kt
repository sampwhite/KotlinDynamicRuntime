package com.dynamicruntime.trials

import com.dynamicruntime.common.assistant.ABLK
import com.dynamicruntime.common.assistant.ACNV
import com.dynamicruntime.common.assistant.ASST
import com.dynamicruntime.common.assistant.ATOOL
import com.dynamicruntime.common.gedra.CFEP
import com.dynamicruntime.common.util.toJsonListOfMaps
import com.dynamicruntime.common.util.toJsonMapOrEmpty
import com.dynamicruntime.common.util.toJsonStr
import com.dynamicruntime.common.util.toOptLong
import com.dynamicruntime.common.util.toOptStr
import kotlin.time.Instant

/** One tool call the assistant made in a turn: its input, and what came back. */
class ToolCall(val turnId: String, val name: String, val input: Map<String, Any?>, val isError: Boolean, val result: String) {
    /** Whether the call reads, rather than changes, the client: the read tools are named for reading, listing, or viewing. */
    val isRead: Boolean get() = readPrefixes.any { name.startsWith(it) }

    /** Whether the call asked for a dry run; null when its input says nothing of one. */
    val dryRun: Boolean? get() = input[CFEP.dryRun] as? Boolean

    /** The call in one line: its name and input. */
    val summary: String get() = "$name ${input.toJsonStr(compact = true).take(summaryChars)}"

    companion object {
        val readPrefixes = listOf("read_", "list_", "view_")
        const val summaryChars = 240
    }
}

/**
 * One conversation a trial held: its turns, as the page reads them, and its messages, from which [calls] and each turn's
 * [reply] are read.
 */
class TrialConversation(val conversationId: String, val turns: List<Map<String, Any?>>, val messages: List<Map<String, Any?>>) {
    /** Every tool call, in order, with its result matched by the call's id. */
    val calls: List<ToolCall> by lazy {
        val results = messages.flatMap { blocksOf(it) }.filter { it[ABLK.type] == ABLK.toolResult }
            .associateBy { it[ABLK.toolUseId].toOptStr() }
        messages.filter { it[ACNV.role] == ABLK.assistant }.flatMap { message ->
            blocksOf(message).filter { it[ABLK.type] == ABLK.toolUse }.map { use ->
                val result = results[use[ABLK.id].toOptStr()]
                ToolCall(
                    message[ACNV.turnId].toOptStr().orEmpty(), use[ABLK.name].toOptStr().orEmpty(), use[ABLK.input].toJsonMapOrEmpty(),
                    result?.get(ABLK.isError) == true, resultText(result?.get(ABLK.content)),
                )
            }
        }
    }

    /** What the assistant last wrote in [turnId]: its reply to the administrator. */
    fun reply(turnId: String): String = messages.filter { it[ACNV.role] == ABLK.assistant && it[ACNV.turnId] == turnId }
        .lastOrNull { message -> blocksOf(message).any { it[ABLK.type] == ABLK.text } }
        ?.let { message ->
            blocksOf(message).filter { it[ABLK.type] == ABLK.text }.joinToString("\n\n") { it[ABLK.text].toOptStr().orEmpty() }
        }
        .orEmpty()

    companion object {
        fun blocksOf(message: Map<String, Any?>): List<Map<String, Any?>> = message[ACNV.content].toJsonListOfMaps()

        /** A tool result's content as text: a string as it is, a list of blocks as their text. */
        fun resultText(content: Any?): String = when (content) {
            null -> ""
            is String -> content
            is List<*> -> content.toJsonListOfMaps().joinToString("\n") { it[ABLK.text].toOptStr() ?: it.toJsonStr(compact = true) }
            else -> content.toJsonStr(compact = true)
        }
    }
}

/** One turn as a report shows it: what was sent, how it ended, and what it cost. */
class TurnInfo(val turn: Map<String, Any?>) {
    val turnId: String get() = turn[ACNV.turnId].toOptStr().orEmpty()
    val text: String get() = turn[ACNV.text].toOptStr().orEmpty()
    val status: String get() = turn[ACNV.status].toOptStr().orEmpty()
    val stopReason: String? get() = turn[ACNV.stopReason].toOptStr()
    val note: String? get() = turn[ACNV.note].toOptStr()
    val steps: Long get() = turn[ACNV.steps].toOptLong() ?: 0
    val weighted: Long get() = turn[ACNV.weighted].toOptLong() ?: 0
    val model: String? get() = turn[ASST.model].toOptStr()
}

/**
 * What one trial did: the client it made, the conversations it held, the client's configuration at the end, and
 * [problems], what went wrong on the harness's side -- a message refused, a turn that never ended -- which the report
 * shows beside the expectations rather than stopping the run.
 */
class TrialResult(
    val trial: Trial,
    val variant: TrialVariant,
    val client: String?,
    val sandbox: String?,
    val conversations: List<TrialConversation>,
    /** Every configuration bundle of the client, by name, as `GET` [CFEP.bundle] answers it in portable form. */
    val config: Map<String, Map<String, Any?>>,
    val problems: List<String>,
    val startedAt: Instant,
    val endedAt: Instant,
) {
    val view: ConfigView by lazy { ConfigView(config) }
    val calls: List<ToolCall> by lazy { conversations.flatMap { it.calls } }
    val turns: List<TurnInfo> by lazy { conversations.flatMap { c -> c.turns.map { TurnInfo(it) } } }
    val weighted: Long get() = turns.sumOf { it.weighted }

    /** The model that answered, when every turn names the same one. */
    val model: String? get() = turns.mapNotNull { it.model }.distinct().singleOrNull()

    /** Each expectation of the trial, then those every trial is held to, with its outcome. */
    fun outcomes(): List<Pair<Expectation, Outcome>> = (trial.expectations + MethodChecks.all).map { expectation ->
        expectation to try {
            expectation.check(this)
        } catch (e: Exception) {
            Outcome.varied("The check itself failed: ${e.message ?: e::class.simpleName}.")
        }
    }

    /** Whether the assistant ever called [tool]. */
    fun called(tool: String): Boolean = calls.any { it.name == tool }

    /** The calls that read a document named [doc]. */
    fun docReads(doc: String): List<ToolCall> = calls.filter { it.name == ATOOL.readDoc && it.input[ATOOL.doc] == doc }
}
