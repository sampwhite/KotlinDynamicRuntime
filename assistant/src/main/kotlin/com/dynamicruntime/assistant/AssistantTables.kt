package com.dynamicruntime.assistant

import com.dynamicruntime.common.assistant.ACNV
import com.dynamicruntime.common.assistant.ASST
import com.dynamicruntime.common.context.KdrCxt
import com.dynamicruntime.common.exception.KdrException
import com.dynamicruntime.common.schema.SCT
import com.dynamicruntime.common.sql.KdrTable
import com.dynamicruntime.common.sql.PF
import com.dynamicruntime.common.sql.tableModule

/** The assistant's SQL topic and table names. */
@Suppress("ConstPropertyName")
object ATBL {
    const val topic = "assistant"
    const val clients = "AssistantClients"
    const val usage = "AssistantUsage"

    const val conversations = "AssistantConversations"
    const val messages = "AssistantMessages"
    const val turns = "AssistantTurns"

    /** The usage row's month: `month` itself is a reserved word in H2. Shown on the wire as `month`. */
    const val yearMonth = "yearMonth"

    // --- Columns of the conversation tables that do not travel on the wire ---
    const val toolsetVersion = "toolsetVersion"
    const val runningTurnId = "runningTurnId"
    const val message = "message"
    const val sandboxUserId = "sandboxUserId"
    const val sandboxIdentityId = "sandboxIdentityId"
    const val sandboxClient = "sandboxClient"
    const val leaseUntil = "leaseUntil"
    const val cancelRequested = "cancelRequested"

    fun table(cxt: KdrCxt, name: String): KdrTable =
        cxt.getGlobalSchema().tables[name] ?: throw KdrException("The $name table is not registered in the schema store.")
}

/**
 * The assistant's tables (issue #1525).
 *
 * `AssistantClients` is the operator's switch per client, and **not** part of the client's definition: a definition
 * change is a draft on a client with a sandbox, and what a deployment lets a client spend is not configuration. The row's
 * protocol `enabled` column *is* the switch -- so the table cache, which holds enabled rows only, holds exactly the
 * clients the assistant is on for -- and `updatedBy`/`updatedAt` say who set it and when.
 *
 * `AssistantUsage` is a client's use, one row per calendar month (UTC), added to atomically on every model call. Kept
 * by kind as well as weighted, so the operator can see how often the cache hits. A sandbox's use is its parent's row.
 *
 * The conversations (issue #1526): `AssistantConversations` (one per conversation, naming the turn running in it, if
 * any), `AssistantMessages` (the transcript, append-only, stored exactly as the model's API reads it), and
 * `AssistantTurns` (one per message sent, with what it cost and how it ended). All in the database, so a poll reaching
 * any node finds the same conversation. None is cached: each is read by key, and rarely.
 */
fun assistantTables(cxt: KdrCxt): List<KdrTable> = tableModule(cxt, namespace = ASST.root, topic = ATBL.topic) {
    table(ATBL.clients, "Which clients an operator has switched the workflow assistant on for, and their budgets (#1525).") {
        column(ASST.client, "The client. Never a sandbox: a sandbox goes by its parent.", required = true)
        column(ASST.monthlyBudget, "The client's monthly budget in weighted tokens; absent for the deployment's default.") {
            type = SCT.integer
        }
        column(ASST.noticeAcknowledgedAt, "When an operator last switched it on, acknowledging what is sent to the model.") {
            dateTime()
        }
        column(ASST.noticeAcknowledgedBy, "The operator who did.") { type = SCT.integer }
        primaryKey(ASST.client)
    }
    table(ATBL.usage, "A client's use of the workflow assistant in one calendar month (#1525).") {
        column(ASST.client, "The client that pays: a sandbox's parent.", required = true)
        column(ATBL.yearMonth, "The month, as YYYY-MM in UTC.", required = true)
        column(ASST.used, "Weighted tokens used, which the budget counts.", required = true) { type = SCT.integer }
        column(ASST.inputTokens, "Input tokens not read from the cache or written to it.", required = true) { type = SCT.integer }
        column(ASST.cacheWriteTokens, "Input tokens written to the prompt cache.", required = true) { type = SCT.integer }
        column(ASST.cacheReadTokens, "Input tokens read from the prompt cache.", required = true) { type = SCT.integer }
        column(ASST.outputTokens, "Output tokens, thinking included.", required = true) { type = SCT.integer }
        column(ASST.calls, "Model calls made.", required = true) { type = SCT.integer }
        column(ASST.lastUsedAt, "When the model last answered for the client.") { dateTime() }
        primaryKey(ASST.client, ATBL.yearMonth)
        index(ATBL.yearMonth)
    }
    table(ATBL.conversations, "A workflow-assistant conversation of one client administrator's (#1526).") {
        column(ACNV.conversationId, "The conversation's id.", required = true)
        column(ASST.client, "The client the administrator was signed in to; never a sandbox.", required = true)
        column(PF.userId, "The administrator's own user.", required = true) { type = SCT.integer }
        column(ACNV.title, "The start of its first message, for a listing.", required = true)
        column(ATBL.toolsetVersion, "The tools and system prompt it was begun with; another makes it read-only.", required = true)
        column(ATBL.runningTurnId, "The turn running in it; absent when none is.")
        primaryKey(ACNV.conversationId)
        index(PF.userId, ASST.client)
    }
    table(ATBL.messages, "One message of a workflow-assistant conversation, as the model's API reads it (#1526).") {
        column(ACNV.conversationId, "The conversation.", required = true)
        column(ACNV.seq, "Its place in the conversation, from 1.", required = true) { type = SCT.integer }
        column(ACNV.turnId, "The turn that wrote it.", required = true)
        column(ATBL.message, "The message: its role and content blocks, exactly as sent or answered.", required = true) {
            type = SCT.kObject
        }
        primaryKey(ACNV.conversationId, ACNV.seq)
    }
    table(ATBL.turns, "One turn of a workflow-assistant conversation: a message sent, and what answering it did (#1526).") {
        column(ACNV.turnId, "The turn's id.", required = true)
        column(ACNV.conversationId, "The conversation.", required = true)
        column(ASST.client, "The client the administrator was signed in to.", required = true)
        column(PF.userId, "The administrator's own user.", required = true) { type = SCT.integer }
        column(ACNV.clientMessageId, "The page's id for the message, so a resend starts no second turn.", required = true)
        column(ACNV.text, "What the administrator wrote.", required = true)
        column(ATBL.sandboxUserId, "The administrator's user in the sandbox, which the tools act as.", required = true) {
            type = SCT.integer
        }
        column(ATBL.sandboxIdentityId, "That user's person.", required = true)
        column(ATBL.sandboxClient, "The sandbox.", required = true)
        column(ACNV.status, "Running, or how it ended.", required = true)
        column(ACNV.stopReason, "Why the model last stopped.")
        column(ACNV.steps, "Model calls made.", required = true) { type = SCT.integer }
        column(ASST.inputTokens, "Input tokens neither read from the cache nor written to it.", required = true) { type = SCT.integer }
        column(ASST.cacheWriteTokens, "Input tokens written to the prompt cache.", required = true) { type = SCT.integer }
        column(ASST.cacheReadTokens, "Input tokens read from the prompt cache.", required = true) { type = SCT.integer }
        column(ASST.outputTokens, "Output tokens, thinking included.", required = true) { type = SCT.integer }
        column(ACNV.weighted, "Weighted tokens charged to the client's budget.", required = true) { type = SCT.integer }
        column(ASST.model, "The model that last answered.")
        column(ACNV.note, "What the administrator is told about how it ended, when that is not the model's own words.")
        column(ATBL.leaseUntil, "When the node running it stops vouching for it, unless it renews.") { dateTime() }
        column(ATBL.cancelRequested, "Whether the administrator asked it to stop.", required = true) { type = SCT.boolean }
        column(ACNV.endedAt, "When it ended.") { dateTime() }
        primaryKey(ACNV.turnId)
        index(ACNV.conversationId)
        index(PF.userId, ACNV.clientMessageId)
        index(ASST.client, ACNV.status)
        index(PF.userId, PF.createdAt)
    }
}
