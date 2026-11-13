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

    /**
     * The cfact true when the caller can talk to the assistant (issue #1527): [onCFact], and -- signed in to the client's
     * sandbox -- they are there as a user opened from one of the client's own, whom the assistant answers for. The menu
     * entry asks it, so the page is never offered to a caller every assistant endpoint would refuse.
     */
    const val usableCFact = "$root:usable"

    /**
     * The cfact true when this deployment offers the assistant to the clients people create for themselves (issue
     * #1636): this node can reach the model, and self-serve clients may switch it on. Whether the caller has made one is
     * not part of it. A public administrator's home card asks it, so it is offered only where creating a client leads to
     * the assistant.
     */
    const val selfServeOfferedCFact = "$root:selfServeOffered"

    /** The home card the module adds for a client's administrator (issue #1636), offered as [menuItem] is. */
    const val homeCard = "assistant"

    /**
     * The home card the module adds for a public administrator (issue #1636), where [selfServeOfferedCFact] holds: a
     * client they create comes with the assistant. The create card asks whether it is there, to say so too.
     */
    const val createHomeCard = "assistantCreate"

    /** The live body of [homeCard]: it reads whether the assistant is on, or offered to be switched on. */
    const val cardKind = "assistant"

    /** The webapp page the assistant's menu entry opens (issue #1527). */
    const val page = "assistant"

    /** The operator's page of the clients the assistant is on for, their budgets, and their use (issue #1527). */
    const val operatorPage = "operatorAssistant"

    /**
     * The menu entry the module adds for the page (issue #1527), offered to a client's administrator while [usableCFact]
     * holds. An item's id, not a page's: it is what a client's menu edit names, and stays when the page is renamed.
     */
    const val menuItem = "assistant"

    /** The menu entry for [operatorPage], under the Operator group; an item's id, as [menuItem] is. */
    const val operatorMenuItem = "operatorAssistant"

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

    /** The choice of clients an operator may switch on: every client, its sandbox left out (it goes by its parent). */
    const val clientOptions = "assistantClients"

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

    /**
     * Deletes one of the caller's conversations (issue #1755): its transcript and what they wrote in its turns, keeping
     * the turns' counts and cost, from which the allowance is reckoned. Refused while a turn runs in it.
     */
    const val deletePath = "/${SECT.clientAdmin}/assistant/conversation/delete"

    /**
     * What the page shows before anything is sent (issue #1527): whether the assistant is on for the client, whether it
     * has a sandbox, how much of the month's allowance is used, and what is sent to Anthropic.
     */
    const val statusPath = "/${SECT.clientAdmin}/assistant/status"

    /**
     * Switches the assistant on for the caller's own client, where it is offered (issue #1568): a self-serve client, on
     * a deployment that offers it to them. The client's administrator acknowledges the data notice, as an operator does.
     */
    const val switchOnPath = "/${SECT.clientAdmin}/assistant/switchOn"

    // --- Type names ---
    const val statusType = "AssistantClientStatus"
    const val startedType = "AssistantTurnStarted"
    const val conversationType = "AssistantConversation"
    const val summaryType = "AssistantConversationSummary"
    const val messageType = "AssistantMessage"
    const val turnType = "AssistantTurn"
    const val cancelType = "AssistantCancel"
    const val deletedType = "AssistantConversationDeleted"

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
    const val deleted = "deleted"

    // A row's protocol date, as a summary carries it: the backend's `PF.updatedAt`, which the kernel cannot name.
    const val updatedAt = "updatedAt"
    const val on = "on"

    /** Whether the client's administrator may switch the assistant on themselves ([switchOnPath], issue #1568). */
    const val offered = "offered"

    /** The client's display name, which a person who named a self-serve client knows it by (issue #1568). */
    const val clientName = "clientName"
    const val hasSandbox = "hasSandbox"
    const val definedInCode = "definedInCode"
    const val inSandbox = "inSandbox"
    const val usedShare = "usedShare"
    const val spent = "spent"
    const val resetsAt = "resetsAt"

    // --- A turn's status ---
    const val statusRunning = "running"
    const val statusDone = "done"
    const val statusStopped = "stopped"
    const val statusCanceled = "canceled"
    const val statusInterrupted = "interrupted"
    const val statusFailed = "failed"

    // --- Error codes a refused message carries (`errorCode`), for the page to act on ---
    const val notOnCode = "assistantNotOn"
    const val notOfferedCode = "assistantNotOffered"
    const val inSandboxCode = "assistantInSandbox"
    const val noSandboxCode = "noSandbox"
    const val budgetSpentCode = "budgetSpent"
    const val turnLimitCode = "turnLimit"
    const val busyCode = "turnRunning"
    const val readOnlyCode = "conversationReadOnly"
    const val messageIdUsedCode = "messageIdUsed"
}

/**
 * A stored message's content blocks, by the Messages API's own names (issue #1527): what the page reads to draw a
 * transcript, and what the module writes. Each matches its value.
 */
@Suppress("ConstPropertyName")
object ABLK {
    // --- A message's roles ---
    const val user = "user"
    const val assistant = "assistant"

    // --- Block types ---
    const val text = "text"
    const val thinking = "thinking"
    const val toolUse = "tool_use"
    const val toolResult = "tool_result"

    // --- Block fields ---
    const val type = "type"
    const val id = "id"
    const val name = "name"
    const val input = "input"
    const val toolUseId = "tool_use_id"
    const val content = "content"
    const val isError = "is_error"
}

/** The assistant's tools by name (issues #1526, #1527): what the model calls, and what the page labels its calls by. */
@Suppress("ConstPropertyName")
object ATOOL {
    const val readDoc = "read_doc"
    const val listConfigs = "list_configs"
    const val readConfig = "read_config"
    const val readConfigEntries = "read_config_entries"
    const val listWorkflows = "list_workflows"
    const val listTraits = "list_traits"
    const val readCfacts = "read_cfacts"
    const val viewWorkflow = "view_workflow"
    const val patchConfig = "patch_config"
    const val setIncludedTraits = "set_included_traits"

    // The reads of the client as its sandbox runs it (issue #1762).
    const val readClient = "read_client"
    const val readMenu = "read_menu"
    const val readCopy = "read_copy"
    const val readOverrides = "read_overrides"
    const val readFormsList = "read_forms_list"
    const val listReports = "list_reports"
    const val readReportPaths = "read_report_paths"
    const val readWebAssets = "read_web_assets"
    const val readFileRefs = "read_file_refs"
    const val listDrafts = "list_drafts"
    const val readImpact = "read_impact"
    const val readChecklist = "read_checklist"
    const val readDefinition = "read_definition"

    // The targeted writes of the client's wording and menu (issue #1763), over the copy and menu editors' endpoints.
    const val setCopy = "set_copy"
    const val applyCopy = "apply_copy"
    const val resetCopy = "reset_copy"
    const val setMenuItem = "set_menu_item"
    const val resetMenuItem = "reset_menu_item"

    // The targeted writes of the client's definition and forms list (issue #1764), each but the layout dry-run first.
    const val setClientDefinition = "set_client_definition"
    const val saveFormsListColumn = "save_forms_list_column"
    const val removeFormsListColumn = "remove_forms_list_column"
    const val setFormsListLayout = "set_forms_list_layout"

    // The targeted writes of Design View's edits (issue #1765): a form's copy, headings, labels, choices, and fields.
    const val setFieldCopy = "set_field_copy"
    const val setSharedField = "set_shared_field"
    const val setHeading = "set_heading"
    const val setSharedHeading = "set_shared_heading"
    const val setWorkflowLabel = "set_workflow_label"
    const val setShownFields = "set_shown_fields"

    // The targeted writes of the client's template workflows, traits, and reports (issue #1766), each dry-run first.
    const val saveTemplateWorkflow = "save_template_workflow"
    const val removeTemplateWorkflow = "remove_template_workflow"
    const val setWorkflowParams = "set_workflow_params"
    const val designTrait = "design_trait"
    const val narrowTrait = "narrow_trait"
    const val createReport = "create_report"
    const val removeReport = "remove_report"

    // A tool input that is not an endpoint's own field: the document `read_doc` reads.
    const val doc = "doc"

    // `read_copy`'s inputs, which narrow its answer rather than being sent (issue #1762): a fragment file, and a namespace
    // in it.
    const val file = "file"
    const val namespace = "namespace"

    // `set_field_copy`'s input saying it removes the workflow's own copy (issue #1765), which a missing entry once did.
    const val remove = "remove"

    // `read_copy`'s page (issue #1799): the keys whose copy note says a person sees them there.
    const val page = "page"
}
