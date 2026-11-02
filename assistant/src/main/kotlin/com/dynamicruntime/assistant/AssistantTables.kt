package com.dynamicruntime.assistant

import com.dynamicruntime.common.assistant.ASST
import com.dynamicruntime.common.context.KdrCxt
import com.dynamicruntime.common.exception.KdrException
import com.dynamicruntime.common.schema.SCT
import com.dynamicruntime.common.sql.KdrTable
import com.dynamicruntime.common.sql.tableModule

/** The assistant's SQL topic and table names. */
@Suppress("ConstPropertyName")
object ATBL {
    const val topic = "assistant"
    const val clients = "AssistantClients"
    const val usage = "AssistantUsage"

    /** The usage row's month: `month` itself is a reserved word in H2. Shown on the wire as `month`. */
    const val yearMonth = "yearMonth"

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
}
