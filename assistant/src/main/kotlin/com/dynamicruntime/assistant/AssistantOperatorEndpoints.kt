package com.dynamicruntime.assistant

import com.dynamicruntime.common.assistant.ASST
import com.dynamicruntime.common.context.KdrCxt
import com.dynamicruntime.common.endpoint.ETAG
import com.dynamicruntime.common.endpoint.HttpMethod
import com.dynamicruntime.common.endpoint.ListPage
import com.dynamicruntime.common.endpoint.SchModule
import com.dynamicruntime.common.endpoint.schemaModule
import com.dynamicruntime.common.exception.KdrException
import com.dynamicruntime.common.gedra.ClientService
import com.dynamicruntime.common.gedra.clientAttribute
import com.dynamicruntime.common.gedra.isSandboxClient
import com.dynamicruntime.common.schema.SCT
import com.dynamicruntime.common.sql.PF
import com.dynamicruntime.common.util.getOptBool
import com.dynamicruntime.common.util.getOptLong
import com.dynamicruntime.common.util.toOptDouble
import com.dynamicruntime.common.util.toOptLong
import com.dynamicruntime.common.util.toOptStr

/**
 * The operator's controls over the assistant (issue #1525): every client with its switch, budget, and this month's
 * use, and the call that switches a client on or off and sets its budget. In the `operator` section (the operator
 * level and `allClients`): what a deployment lets its clients spend is the deployment's to decide.
 */
fun assistantOperatorSchema(cxt: KdrCxt): SchModule = schemaModule(cxt, ASST.root) {
    type(ASST.statusType) {
        type = SCT.kObject
        description = "Whether this node can reach the model, and what a client is switched on to."
        property(ASST.available, "Whether this node has a model to ask.", required = true) { type = SCT.boolean }
        property(ASST.provider, "What answers: the Anthropic API, the demo, or a test's model.", required = true)
        property(ASST.model, "The model asked.", required = true)
        property(ASST.reason, "Why the assistant is unavailable; absent when it is available.")
        property(ASST.missingSecret, "The secret holding the API key that the node looked for and did not find.")
        property(ASST.defaultMonthlyBudget, "A client's monthly budget in weighted tokens when it has none of its own.", required = true) {
            type = SCT.integer
        }
        property(
            ASST.defaultMonthlyBudgetDollars,
            "The default budget in dollars, at Sonnet's prices (${PRICE.tokensPerDollar} weighted tokens to the dollar).",
            required = true,
        ) { type = SCT.number }
        property(ASST.dataNotice, "What is sent to Anthropic, which switching a client on acknowledges.", required = true)
    }
    type(ASST.clientType) {
        type = SCT.kObject
        description = "One client's assistant switch, budget, and use this month."
        property(ASST.client, "The client.", required = true)
        property(ASST.name, "The client's name; absent for a client this node no longer carries.")
        property(ASST.enabled, "Whether an operator has switched the assistant on for it.", required = true) { type = SCT.boolean }
        property(ASST.monthlyBudget, "Its monthly budget in weighted tokens.", required = true) { type = SCT.integer }
        property(ASST.monthlyBudgetDollars, "Its monthly budget in dollars, at Sonnet's prices.", required = true) { type = SCT.number }
        property(ASST.budgetIsDefault, "Whether the budget is the deployment's default rather than its own.", required = true) {
            type = SCT.boolean
        }
        property(ASST.month, "The month the use is for, as YYYY-MM in UTC.", required = true)
        property(ASST.used, "Weighted tokens used this month, its sandbox's included.", required = true) { type = SCT.integer }
        property(ASST.usedDollars, "What was used this month, in dollars at Sonnet's prices.", required = true) { type = SCT.number }
        property(ASST.inputTokens, "Input tokens neither read from the cache nor written to it.", required = true) { type = SCT.integer }
        property(ASST.cacheWriteTokens, "Input tokens written to the prompt cache.", required = true) { type = SCT.integer }
        property(ASST.cacheReadTokens, "Input tokens read from the prompt cache.", required = true) { type = SCT.integer }
        property(ASST.outputTokens, "Output tokens, thinking included.", required = true) { type = SCT.integer }
        property(ASST.calls, "Model calls made this month.", required = true) { type = SCT.integer }
        property(ASST.lastUsedAt, "When the model last answered for it; absent when it has not this month.") { dateTime() }
        property(ASST.cacheHitRatio, "The share of input tokens read from the cache; absent before any.") { type = SCT.number }
        property(ASST.noticeAcknowledgedAt, "When an operator last switched it on, acknowledging what is sent.") { dateTime() }
        property(ASST.noticeAcknowledgedBy, "The operator who did.") { type = SCT.integer }
        property(PF.updatedAt, "When its switch or budget was last set; absent when never.") { dateTime() }
        property(PF.updatedBy, "Who set it.") { type = SCT.integer }
    }
    listEndpoint(
        ASST.clientsPath,
        "Every client this node carries -- and any it no longer does that an operator once set -- with its assistant " +
            "switch, monthly budget, and this month's use; the summary says whether the node can reach the model at all.",
        outputRef = ASST.clientType,
        noLimit = true,
        summaryRef = ASST.statusType,
        tags = setOf(ETAG.internal),
    ) { c, _ ->
        val service = AssistantService.get(c)
        val month = AssistantService.monthOf(c.now())
        val rows = service.clientRows(c).associateBy { it[ASST.client].toString() }
        val usage = service.usageIn(c, month)
        val names = ClientService.getOrNull(c)?.presentClients.orEmpty()
            .filterNot { isSandboxClient(it.clientId) }.associate { it.clientId to it.name }
        val items = (names.keys + rows.keys).sorted().map { client ->
            clientInfo(service, client, names[client], rows[client], usage[client], month)
        }
        ListPage(items, items.size, false, summary = service.status())
    }
    itemEndpoint(
        ASST.clientSetPath,
        "Switches the assistant on or off for a client and sets its monthly budget. Switching it on from off needs " +
            "${ASST.acknowledgeDataNotice}: what is sent to Anthropic is in the listing's ${ASST.dataNotice}.",
        HttpMethod.POST,
        outputRef = ASST.clientType,
        inputFields = {
            // Drawn as a choice of the clients the caller sees, kept current as clients come and go.
            field(ASST.client, "The client. Not a sandbox: a sandbox goes by its parent.", required = true) { clientAttribute() }
            field(ASST.enabled, "On or off; absent to leave it as it is.") { type = SCT.boolean }
            field(
                ASST.monthlyBudgetDollars,
                "Its own monthly budget in dollars, at Sonnet's prices (${PRICE.tokensPerDollar} weighted tokens to the dollar).",
            ) { type = SCT.number }
            field(ASST.monthlyBudget, "Its own monthly budget in weighted tokens, instead of in dollars.") { type = SCT.integer }
            field(ASST.budgetIsDefault, "True to drop its own budget for the deployment's default.") { type = SCT.boolean }
            field(ASST.acknowledgeDataNotice, "True to acknowledge what is sent to Anthropic; needed to switch it on.") {
                type = SCT.boolean
            }
        },
        tags = setOf(ETAG.internal),
    ) { c, request ->
        val service = AssistantService.get(c)
        val client = request[ASST.client].toOptStr()?.trim()?.ifEmpty { null }
            ?: throw KdrException.mkInput("A ${ASST.client} is required.")
        val row = service.setClient(
            c, client,
            enabled = request.getOptBool(ASST.enabled),
            monthlyBudget = budgetAsked(request),
            useDefaultBudget = request.getOptBool(ASST.budgetIsDefault) == true,
            acknowledgedNotice = request.getOptBool(ASST.acknowledgeDataNotice) == true,
        )
        val month = AssistantService.monthOf(c.now())
        val name = ClientService.getOrNull(c)?.known(client)?.name
        clientInfo(service, client, name, row, service.usageIn(c, month)[client], month)
    }
}

/**
 * The budget a set asks for, in weighted tokens: given in dollars ([ASST.monthlyBudgetDollars], the operator's own
 * terms) or in tokens ([ASST.monthlyBudget]), not both; null when neither.
 */
fun budgetAsked(request: Map<String, Any?>): Long? {
    val tokens = request.getOptLong(ASST.monthlyBudget)
    val dollars = request[ASST.monthlyBudgetDollars].toOptDouble() ?: return tokens
    if (tokens != null) {
        throw KdrException.mkInput("Give the budget in ${ASST.monthlyBudgetDollars} or in ${ASST.monthlyBudget}, not both.")
    }
    if (dollars < 0.01) throw KdrException.mkInput("A monthly budget must be at least a cent, not $dollars dollars.")
    return PRICE.weightedOf(dollars)
}

/** One client's line of the listing, from its row and its [usage] in [month], either of which may be absent. */
fun clientInfo(
    service: AssistantService,
    client: String,
    name: String?,
    row: Map<String, Any?>?,
    usage: Map<String, Any?>?,
    month: String,
): Map<String, Any?> = buildMap {
    fun count(key: String): Long = usage?.get(key).toOptLong() ?: 0L
    put(ASST.client, client)
    name?.let { put(ASST.name, it) }
    put(ASST.enabled, row?.get(PF.enabled) == true)
    put(ASST.monthlyBudget, service.budgetOf(row))
    put(ASST.monthlyBudgetDollars, PRICE.dollarsOf(service.budgetOf(row)))
    put(ASST.budgetIsDefault, row?.get(ASST.monthlyBudget) == null)
    put(ASST.month, month)
    for (key in listOf(ASST.used, ASST.inputTokens, ASST.cacheWriteTokens, ASST.cacheReadTokens, ASST.outputTokens, ASST.calls)) {
        put(key, count(key))
    }
    put(ASST.usedDollars, PRICE.dollarsOf(count(ASST.used)))
    usage?.get(ASST.lastUsedAt)?.let { put(ASST.lastUsedAt, it) }
    val allInput = count(ASST.inputTokens) + count(ASST.cacheWriteTokens) + count(ASST.cacheReadTokens)
    if (allInput > 0) put(ASST.cacheHitRatio, count(ASST.cacheReadTokens).toDouble() / allInput)
    for (key in listOf(ASST.noticeAcknowledgedAt, ASST.noticeAcknowledgedBy, PF.updatedAt, PF.updatedBy)) {
        row?.get(key)?.let { put(key, it) }
    }
}
