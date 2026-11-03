package com.dynamicruntime.webapp

import com.dynamicruntime.common.assistant.ACNV
import com.dynamicruntime.common.assistant.ASST
import com.dynamicruntime.common.endpoint.EP
import com.dynamicruntime.common.util.currencyText
import com.dynamicruntime.common.util.getOptStr
import com.dynamicruntime.common.util.toJsonListOfMaps
import com.dynamicruntime.common.util.toJsonMapOrEmpty
import com.dynamicruntime.common.util.toOptDoubleOrNull
import com.dynamicruntime.common.util.toOptLongOrNull
import kotlin.js.Date
import kotlin.random.Random

/** What the assistant page shows before anything is sent (`ACNV.statusPath`). */
class AssistantStatus(
    /** The client the assistant works for: the person's, or the one whose sandbox they are in. */
    val client: String,
    val on: Boolean,
    val hasSandbox: Boolean,
    /** Whether the client is defined in source code, where its sandbox is turned on -- not from this page. */
    val sandboxInSource: Boolean,
    val inSandbox: Boolean,
    val allowance: AllowanceView,
    val dataNotice: String,
)

/** One of the person's conversations, for the list. */
class ConversationSummary(
    val conversationId: String,
    val title: String,
    val running: Boolean,
    val readOnly: Boolean,
    val updatedAt: String?,
)

/** A conversation read: the conversation, its messages after the point asked for, and all its turns. */
class ConversationRead(val summary: ConversationSummary, val messages: List<StoredMessage>, val turns: List<TurnView>)

/** [results], the status endpoint's answer, as the page reads it. Pure. */
fun parseAssistantStatus(results: Map<String, Any?>): AssistantStatus = AssistantStatus(
    client = results.getOptStr(ASST.client).orEmpty(),
    on = results[ACNV.on] == true,
    hasSandbox = results[ACNV.hasSandbox] == true,
    sandboxInSource = results[ACNV.sandboxInSource] == true,
    inSandbox = results[ACNV.inSandbox] == true,
    allowance = AllowanceView(
        results[ACNV.usedShare].toOptDoubleOrNull() ?: 0.0, results[ACNV.spent] == true, results.getOptStr(ACNV.resetsAt).orEmpty(),
    ),
    dataNotice = results.getOptStr(ASST.dataNotice).orEmpty(),
)

/** One conversation summary. Pure. */
fun parseConversationSummary(map: Map<String, Any?>): ConversationSummary = ConversationSummary(
    map.getOptStr(ACNV.conversationId).orEmpty(), map.getOptStr(ACNV.title).orEmpty(), map[ACNV.running] == true,
    map[ACNV.readOnly] == true, map.getOptStr(ACNV.updatedAt),
)

/** [results], a conversation read's answer. Pure. */
fun parseConversationRead(results: Map<String, Any?>): ConversationRead = ConversationRead(
    parseConversationSummary(results[ACNV.conversation].toJsonMapOrEmpty()),
    results[ACNV.messages].toJsonListOfMaps().map {
        StoredMessage(it[ACNV.seq].toOptLongOrNull() ?: 0, it.getOptStr(ACNV.role).orEmpty(), blocksOf(it[ACNV.content]))
    },
    results[ACNV.turns].toJsonListOfMaps().map {
        TurnView(
            it.getOptStr(ACNV.turnId).orEmpty(), it.getOptStr(ACNV.text).orEmpty(), it.getOptStr(ACNV.status).orEmpty(),
            it.getOptStr(ACNV.note),
        )
    },
)

/** A new id for a message, unique enough that two the page sends never share one. */
fun newMessageId(): String = "m${Date.now().toLong().toString(36)}${Random.nextInt(0, 1 shl 30).toString(36)}"

/** One client's line on the operator's page (`ASST.clientType`). */
class AssistantClientLine(
    val client: String,
    val name: String?,
    val enabled: Boolean,
    val budgetDollars: Double,
    val budgetIsDefault: Boolean,
    val usedDollars: Double,
    val calls: Long,
    val lastUsedAt: String?,
    val cacheHitRatio: Double?,
)

/** What the operator's page says about this node, beside the clients (`ASST.statusType`). */
class AssistantNodeStatus(
    val available: Boolean,
    val provider: String,
    val model: String,
    val reason: String?,
    val defaultBudgetDollars: Double,
    val dataNotice: String,
)

/** The operator's listing: the node's status and every client's line. */
class AssistantClientsRead(val status: AssistantNodeStatus, val clients: List<AssistantClientLine>)

/** One client's line. Pure. */
fun parseAssistantClientLine(map: Map<String, Any?>): AssistantClientLine = AssistantClientLine(
    client = map.getOptStr(ASST.client).orEmpty(),
    name = map.getOptStr(ASST.name),
    enabled = map[ASST.enabled] == true,
    budgetDollars = map[ASST.monthlyBudgetDollars].toOptDoubleOrNull() ?: 0.0,
    budgetIsDefault = map[ASST.budgetIsDefault] == true,
    usedDollars = map[ASST.usedDollars].toOptDoubleOrNull() ?: 0.0,
    calls = map[ASST.calls].toOptLongOrNull() ?: 0,
    lastUsedAt = map.getOptStr(ASST.lastUsedAt),
    cacheHitRatio = map[ASST.cacheHitRatio].toOptDoubleOrNull(),
)

/** [envelope], the operator's listing. Pure. */
fun parseAssistantClients(envelope: Map<String, Any?>): AssistantClientsRead {
    val summary = envelope[EP.summary].toJsonMapOrEmpty()
    return AssistantClientsRead(
        AssistantNodeStatus(
            available = summary[ASST.available] == true,
            provider = summary.getOptStr(ASST.provider).orEmpty(),
            model = summary.getOptStr(ASST.model).orEmpty(),
            reason = summary.getOptStr(ASST.reason),
            defaultBudgetDollars = summary[ASST.defaultMonthlyBudgetDollars].toOptDoubleOrNull() ?: 0.0,
            dataNotice = summary.getOptStr(ASST.dataNotice).orEmpty(),
        ),
        envelope[EP.items].toJsonListOfMaps().map(::parseAssistantClientLine),
    )
}

/** Dollars as the operator's page shows them, by the kernel's own currency rule: "$1,234.50". Pure. */
fun dollarsText(dollars: Double): String = currencyText(dollars, "USD")

/**
 * A budget the operator typed, in dollars, or null when it is not an amount: "25", "$25", "1,000.50" are; "NaN", "0x10",
 * "-5", and "" are not. The backend holds it to its bounds.
 */
fun budgetDollarsOf(text: String): Double? {
    val plain = text.trim().removePrefix("$").replace(",", "")
    return if (budgetPattern.matches(plain)) plain.toDouble() else null
}

private val budgetPattern = Regex("""\d+(\.\d+)?""")

/** The calls behind the workflow assistant's page and the operator's (issue #1527). */
object AssistantApi {
    suspend fun status(): AssistantStatus = parseAssistantStatus(Http.getApi(ACNV.statusPath)[EP.results].toJsonMapOrEmpty())

    suspend fun conversations(): List<ConversationSummary> =
        Http.getApi(ACNV.conversationsPath)[EP.items].toJsonListOfMaps().map(::parseConversationSummary)

    /** [conversationId]'s messages after [afterSeq] (all of them at 0), and its turns. */
    suspend fun conversation(conversationId: String, afterSeq: Long): ConversationRead = parseConversationRead(
        Http.getApi(ACNV.conversationPath + queryString(mapOf(ACNV.conversationId to conversationId, ACNV.afterSeq to afterSeq)))[
            EP.results,
        ].toJsonMapOrEmpty(),
    )

    /** Sends [text] as message [clientMessageId], into [conversationId] or a new conversation: the conversation and the turn. */
    suspend fun send(conversationId: String?, clientMessageId: String, text: String): Pair<String, String> {
        val body = buildMap {
            conversationId?.let { put(ACNV.conversationId, it) }
            put(ACNV.clientMessageId, clientMessageId)
            put(ACNV.text, text)
        }
        val started = Http.sendApi("POST", ACNV.messagePath, body)[EP.results].toJsonMapOrEmpty()
        return started.getOptStr(ACNV.conversationId).orEmpty() to started.getOptStr(ACNV.turnId).orEmpty()
    }

    suspend fun cancel(conversationId: String) {
        Http.sendApi("POST", ACNV.cancelPath, mapOf(ACNV.conversationId to conversationId))
    }

    suspend fun clients(): AssistantClientsRead = parseAssistantClients(Http.getApi(ASST.clientsPath))

    /**
     * Sets [client]'s switch ([enabled], null to leave it) and budget ([budgetDollars], or [useDefault]); switching it
     * on [acknowledges] the data notice.
     */
    suspend fun setClient(
        client: String,
        enabled: Boolean? = null,
        budgetDollars: Double? = null,
        useDefault: Boolean = false,
        acknowledges: Boolean = false,
    ) {
        val body = buildMap {
            put(ASST.client, client)
            enabled?.let { put(ASST.enabled, it) }
            budgetDollars?.let { put(ASST.monthlyBudgetDollars, it) }
            if (useDefault) put(ASST.budgetIsDefault, true)
            if (acknowledges) put(ASST.acknowledgeDataNotice, true)
        }
        Http.sendApi("POST", ASST.clientSetPath, body)
    }
}
