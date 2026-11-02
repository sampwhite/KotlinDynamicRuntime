package com.dynamicruntime.common.assistant

import com.dynamicruntime.common.http.request.SECT

/**
 * The workflow assistant's wire vocabulary (issue #1525): the names the `assistant` module's endpoints use and the
 * webapp's pages read, in the kernel both sides compile. The module itself is optional -- nothing compile-depends on
 * it -- so these are the only part of it core and the webapp can name. Each field-name constant matches its value.
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

    /**
     * The environment variable naming the secret that holds the model's API key. Here, not only in the module, so the
     * boot helpers a test uses can point it at [noKeyInUnitTests], as they do Mailgun's: a configured value wins over
     * the process environment, so a developer's real key reaches no test, whatever their shell exports.
     */
    const val keySecretEnvVar = "KDR_ASSISTANT_KEY_SECRET"

    /** The secret a test node's model key is looked up under: one no secrets file holds. */
    const val noKeyInUnitTests = "noAnthropicKeyInUnitTests"

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
    const val monthlyBudgetDollars = "monthlyBudgetDollars"
    const val usedDollars = "usedDollars"
    const val defaultMonthlyBudgetDollars = "defaultMonthlyBudgetDollars"
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

/**
 * The assistant's conversations on the wire (issue #1526): what a client's administrator sends and polls, which the
 * webapp's page (#1527) reads by these names. In the `clientAdmin` section, and every one is session-only: no key -- and
 * not the assistant itself, whose calls go by a key's rules -- may start a turn or spend the budget.
 */
@Suppress("ConstPropertyName")
object ACNV {
    /** Sends a message, starting a turn; answers at once, before the model does. */
    const val messagePath = "/${SECT.clientAdmin}/assistant/message"

    /** One conversation's messages after a point, and its turns: what the page polls. */
    const val conversationPath = "/${SECT.clientAdmin}/assistant/conversation"

    /** The caller's conversations, most recent first. */
    const val conversationsPath = "/${SECT.clientAdmin}/assistant/conversations"

    /** Asks a running turn to stop at its next step. */
    const val cancelPath = "/${SECT.clientAdmin}/assistant/cancel"

    // --- Type names ---
    const val startedType = "AssistantTurnStarted"
    const val conversationType = "AssistantConversation"
    const val summaryType = "AssistantConversationSummary"
    const val messageType = "AssistantMessage"
    const val turnType = "AssistantTurn"
    const val cancelType = "AssistantCancel"

    // --- Field names (each matches its value) ---
    const val conversation = "conversation"
    const val conversationId = "conversationId"
    const val clientMessageId = "clientMessageId"
    const val text = "text"
    const val turnId = "turnId"
    const val seq = "seq"
    const val afterSeq = "afterSeq"
    const val role = "role"
    const val content = "content"
    const val title = "title"
    const val readOnly = "readOnly"
    const val running = "running"
    const val messages = "messages"
    const val turns = "turns"
    const val status = "status"
    const val stopReason = "stopReason"
    const val steps = "steps"
    const val weighted = "weighted"
    const val note = "note"
    const val startedAt = "startedAt"
    const val endedAt = "endedAt"
    const val canceled = "canceled"

    // --- A turn's status ---
    const val statusRunning = "running"
    const val statusDone = "done"
    const val statusStopped = "stopped"
    const val statusCanceled = "canceled"
    const val statusInterrupted = "interrupted"
    const val statusFailed = "failed"

    // --- Error codes a refused message carries (`errorCode`), for the page to act on ---
    const val notOnCode = "assistantNotOn"
    const val noSandboxCode = "noSandbox"
    const val budgetSpentCode = "budgetSpent"
    const val turnLimitCode = "turnLimit"
    const val busyCode = "turnRunning"
    const val readOnlyCode = "conversationReadOnly"
}
