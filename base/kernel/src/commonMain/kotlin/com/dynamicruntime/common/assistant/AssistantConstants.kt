package com.dynamicruntime.common.assistant

import com.dynamicruntime.common.http.request.SECT

/**
 * The workflow assistant's wire vocabulary (issue #1525): the names the `assistant` module's endpoints use and the
 * webapp's pages read, in the kernel both sides compile. The module itself is optional -- nothing compile-depends on
 * it -- so these are the only part of it core and the webapp can name. Each field name matches its value.
 */
@Suppress("ConstPropertyName")
object ASST {
    /** The module's own root (its schema namespace and its cfacts live under it). */
    const val root = "assistant"

    /**
     * The cfact true when the assistant is available to the caller's client: this node can reach the model, and an
     * operator has switched the client on (a sandbox goes by its parent). A menu entry for the assistant asks it.
     */
    const val onCFact = "$root:on"

    /** The webapp page the assistant's menu entry opens (issue #1527). */
    const val page = "assistant"

    // --- Operator endpoints ---

    /** Every client, with its switch, budget, and this month's use; the summary says whether the node can reach the model. */
    const val clientsPath = "/${SECT.operator}/assistant/clients"

    /** Switches a client on or off and sets its monthly budget. */
    const val clientSetPath = "/${SECT.operator}/assistant/client/set"

    // --- Type names ---
    const val clientType = "AssistantClient"
    const val statusType = "AssistantStatus"

    // --- Field names ---
    const val client = "client"
    const val name = "name"
    const val enabled = "enabled"
    const val monthlyBudget = "monthlyBudget"
    const val budgetIsDefault = "budgetIsDefault"
    const val month = "month"
    const val used = "used"
    const val inputTokens = "inputTokens"
    const val cacheWriteTokens = "cacheWriteTokens"
    const val cacheReadTokens = "cacheReadTokens"
    const val outputTokens = "outputTokens"
    const val calls = "calls"
    const val lastUsedAt = "lastUsedAt"
    const val cacheHitRatio = "cacheHitRatio"
    const val noticeAcknowledgedAt = "noticeAcknowledgedAt"
    const val noticeAcknowledgedBy = "noticeAcknowledgedBy"
    const val acknowledgeDataNotice = "acknowledgeDataNotice"
    const val available = "available"
    const val provider = "provider"
    const val model = "model"
    const val reason = "reason"
    const val missingSecret = "missingSecret"
    const val defaultMonthlyBudget = "defaultMonthlyBudget"
    const val dataNotice = "dataNotice"
}
