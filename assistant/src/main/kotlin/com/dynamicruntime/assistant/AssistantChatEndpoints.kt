package com.dynamicruntime.assistant

import com.dynamicruntime.common.assistant.ACNV
import com.dynamicruntime.common.assistant.ASST
import com.dynamicruntime.common.context.KdrCxt
import com.dynamicruntime.common.endpoint.HttpMethod
import com.dynamicruntime.common.endpoint.ListPage
import com.dynamicruntime.common.endpoint.SchModule
import com.dynamicruntime.common.endpoint.schemaModule
import com.dynamicruntime.common.exception.EXC
import com.dynamicruntime.common.exception.KdrException
import com.dynamicruntime.common.gedra.ClientService
import com.dynamicruntime.common.gedra.GedraConfigOrigin
import com.dynamicruntime.common.schema.SCT
import com.dynamicruntime.common.sql.PF
import com.dynamicruntime.common.user.SandboxAccess
import com.dynamicruntime.common.util.toOptLong
import com.dynamicruntime.common.util.toOptStr

/**
 * A client administrator's conversations with the assistant (issue #1526): send a message, poll for what came of it,
 * list the conversations, and stop a running turn. In the `clientAdmin` section, confined to the caller's own client,
 * and each **session-only**: an API key -- and the assistant's own calls, which go by a key's rules -- can neither
 * start a turn nor spend the client's budget.
 */
fun assistantChatSchema(cxt: KdrCxt): SchModule = schemaModule(cxt, "${ASST.root}.chat") {
    type(ACNV.startedType) {
        type = SCT.kObject
        description = "The turn a message started, and its conversation."
        property(ACNV.conversationId, "The conversation.", required = true)
        property(ACNV.turnId, "The turn.", required = true)
    }
    type(ACNV.messageType) {
        type = SCT.kObject
        description = "One message of a conversation, as the model's API reads it."
        property(ACNV.seq, "Its place in the conversation, from 1.", required = true) { type = SCT.integer }
        property(ACNV.turnId, "The turn that wrote it.", required = true)
        property(ACNV.role, "'user' (the administrator, or the tools' results) or 'assistant' (the model).", required = true)
        property(ACNV.content, "Its content blocks: text, thinking, tool calls, and tool results.", required = true) {
            type = SCT.array
            items {
                type = SCT.kObject
                additionalProperties = true
            }
        }
    }
    type(ACNV.turnType) {
        type = SCT.kObject
        description = "One turn: a message the administrator sent, and what answering it did."
        property(ACNV.turnId, "The turn.", required = true)
        property(ACNV.text, "What the administrator wrote.", required = true)
        property(ACNV.status, "running, done, stopped, canceled, interrupted, or failed.", required = true)
        property(ACNV.stopReason, "Why the model last stopped.")
        property(ACNV.note, "What the administrator is told about how it ended, when that is not the model's own words.")
        property(ACNV.steps, "Model calls made.", required = true) { type = SCT.integer }
        property(ACNV.weighted, "Weighted tokens charged to the client's budget.", required = true) { type = SCT.integer }
        property(ASST.model, "The model that last answered.")
        property(ACNV.startedAt, "When it started.", required = true) { dateTime() }
        property(ACNV.endedAt, "When it ended; absent while it runs.") { dateTime() }
    }
    type(ACNV.summaryType) {
        type = SCT.kObject
        description = "One conversation, for a listing."
        property(ACNV.conversationId, "The conversation.", required = true)
        property(ACNV.title, "The start of its first message.", required = true)
        property(ACNV.running, "Whether a turn is running in it.", required = true) { type = SCT.boolean }
        property(ACNV.readOnly, "Whether it cannot go on: the model declined to, or it began with an earlier assistant.", required = true) {
            type = SCT.boolean
        }
        property(PF.createdAt, "When it began.", required = true) { dateTime() }
        property(PF.updatedAt, "When it last changed.", required = true) { dateTime() }
    }
    type(ACNV.conversationType) {
        type = SCT.kObject
        description = "A conversation's messages after a point, and all its turns."
        property(ACNV.conversation, "The conversation.", required = true) { ref(ACNV.summaryType) }
        property(ACNV.messages, "Its messages after the one asked for, in order.", required = true) {
            type = SCT.array
            items { ref(ACNV.messageType) }
        }
        property(ACNV.turns, "Its turns, oldest first.", required = true) {
            type = SCT.array
            items { ref(ACNV.turnType) }
        }
    }

    type(ACNV.statusType) {
        type = SCT.kObject
        description = "What the assistant page shows before anything is sent: whether it can be used, and how much is left."
        property(ASST.client, "The client the assistant works for: the caller's, or the one whose sandbox they are in.", required = true)
        property(ACNV.on, "Whether the assistant is on for the client.", required = true) { type = SCT.boolean }
        property(
            ACNV.offered,
            "Whether the client's administrator may switch the assistant on themselves (a self-serve client, on a deployment " +
                "that offers it): false once it is on, or an operator has set it.",
            required = true,
        ) { type = SCT.boolean }
        property(ACNV.hasSandbox, "Whether the client has a sandbox, which the assistant drafts in.", required = true) {
            type = SCT.boolean
        }
        property(
            ACNV.sandboxInSource,
            "Whether the client is defined in source code, where its sandbox is turned on, rather than from the Clients page.",
            required = true,
        ) { type = SCT.boolean }
        property(ACNV.inSandbox, "Whether the caller is signed in to the sandbox rather than the client.", required = true) {
            type = SCT.boolean
        }
        property(
            ACNV.usedShare, "This month's use as a share of the client's monthly budget: 1 or more when it is spent.", required = true,
        ) { type = SCT.number }
        property(ACNV.spent, "Whether this month's budget is used up, so no message can be sent until it resets.", required = true) {
            type = SCT.boolean
        }
        property(ACNV.resetsAt, "When the month's use starts again from nothing: the start of next month, in UTC.", required = true) {
            dateTime()
        }
        property(ASST.dataNotice, "What is sent to Anthropic when the assistant is used.", required = true)
    }

    generalEndpoint(
        ACNV.statusPath,
        "Whether the workflow assistant can be used for the caller's client -- it is on, and the client has a sandbox -- " +
            "how much of the month's allowance is used, and what using it sends to Anthropic.",
        HttpMethod.GET,
        outputRef = ACNV.statusType,
        sessionOnly = true,
    ) { c, _ -> clientStatus(c, AssistantService.get(c), AssistantTurns.personOf(c)) }

    generalEndpoint(
        ACNV.switchOnPath,
        "Switches the workflow assistant on for the caller's own client, where it is offered: a self-serve client, on a " +
            "deployment that offers it to them, which no operator has set. The administrator acknowledges what using it " +
            "sends to Anthropic (the status's dataNotice), as an operator does; the client starts with the self-serve " +
            "budget. Refused from inside the client's sandbox, and with the errorCode " + ACNV.notOfferedCode + " where it " +
            "is not offered. Answers the status after.",
        HttpMethod.POST,
        outputRef = ACNV.statusType,
        inputFields = {
            field(ASST.acknowledgeDataNotice, "That the administrator has read the data notice.", required = true) {
                type = SCT.boolean
            }
        },
        sessionOnly = true,
    ) { c, request ->
        val service = AssistantService.get(c)
        val person = AssistantTurns.personOf(c)
        if (person.sandboxUser != null) {
            throw AssistantTurns.refused(
                "Switch the workflow assistant on from '${person.client}' itself, not from its sandbox.",
                EXC.badInput, ACNV.notOfferedCode,
            )
        }
        service.switchOn(c, person.client, request[ASST.acknowledgeDataNotice] == true)
        clientStatus(c, service, person)
    }

    generalEndpoint(
        ACNV.messagePath,
        "Sends the workflow assistant a message, starting a turn, and answers at once: poll the conversation for what " +
            "comes of it. A new conversation when no conversationId is given. Refused, with an errorCode, when the " +
            "assistant is not on for the client, it has no sandbox, its allowance is spent, the caller has sent as many " +
            "messages this hour as the assistant takes, or a turn is still running in the conversation.",
        HttpMethod.POST,
        outputRef = ACNV.startedType,
        inputFields = {
            field(ACNV.conversationId, "The conversation to go on with; absent to begin one.")
            field(ACNV.clientMessageId, "The page's own id for this message, so sending it again starts no second turn.", required = true)
            field(ACNV.text, "What the administrator writes.", required = true)
        },
        sessionOnly = true,
    ) { c, request ->
        AssistantTurns.start(
            c, AssistantService.get(c), request[ACNV.conversationId].toOptStr()?.ifBlank { null },
            request[ACNV.clientMessageId].toOptStr()?.trim()?.ifEmpty { null }
                ?: throw KdrException.mkInput("A ${ACNV.clientMessageId} is required."),
            request[ACNV.text].toOptStr().orEmpty(),
        )
    }

    generalEndpoint(
        ACNV.conversationPath,
        "One of the caller's conversations: its messages after afterSeq (all of them when absent), and its turns -- what " +
            "the assistant page polls while a turn runs.",
        HttpMethod.GET,
        outputRef = ACNV.conversationType,
        inputFields = {
            field(ACNV.conversationId, "The conversation.", required = true)
            field(ACNV.afterSeq, "Only the messages after this one.") { type = SCT.integer }
        },
        polled = true,
        sessionOnly = true,
    ) { c, request ->
        val conversationId = request[ACNV.conversationId].toOptStr().orEmpty()
        val person = AssistantTurns.personOf(c)
        AssistantTurns.recoverLapsed(c, AssistantTurns.ownConversation(c, person, conversationId))
        val conversation = AssistantTurns.ownConversation(c, person, conversationId)
        mapOf(
            ACNV.conversation to summaryOf(c, conversation),
            ACNV.messages to AssistantStore.messages(c, conversationId, request[ACNV.afterSeq].toOptLong() ?: 0).map { row ->
                val message = AssistantStore.messageOf(row)
                mapOf(
                    ACNV.seq to row[ACNV.seq], ACNV.turnId to row[ACNV.turnId], ACNV.role to message[MSG.role],
                    ACNV.content to AssistantHistory.blocksOf(message),
                )
            },
            ACNV.turns to AssistantStore.turnsOf(c, conversationId).map(::turnInfo),
        )
    }

    listEndpoint(
        ACNV.conversationsPath,
        "The caller's conversations with the workflow assistant in the client they are signed in to -- or, signed in to its " +
            "sandbox, in the client itself -- most recent first.",
        outputRef = ACNV.summaryType,
        noLimit = true,
        sessionOnly = true,
    ) { c, _ ->
        val person = AssistantTurns.personOf(c)
        val items = AssistantStore.conversationsOf(c, person.userId, person.client).map { summaryOf(c, it) }
        ListPage(items, items.size, false)
    }

    generalEndpoint(
        ACNV.cancelPath,
        "Asks the turn running in one of the caller's conversations to stop: it stops at its next step, answering any " +
            "tool call it had not run. Says whether there was a turn to stop.",
        HttpMethod.POST,
        outputRef = ACNV.cancelType,
        inputFields = { field(ACNV.conversationId, "The conversation.", required = true) },
        sessionOnly = true,
    ) { c, request -> AssistantTurns.cancel(c, request[ACNV.conversationId].toOptStr().orEmpty()) }
    type(ACNV.cancelType) {
        type = SCT.kObject
        description = "Whether a running turn was asked to stop."
        property(ACNV.conversationId, "The conversation.", required = true)
        property(ACNV.canceled, "Whether a turn was running, and was asked to stop.", required = true) { type = SCT.boolean }
    }
}

/** [row], a stored conversation, as a listing shows it. */
fun summaryOf(cxt: KdrCxt, row: Map<String, Any?>): Map<String, Any?> {
    val client = row[ASST.client].toString()
    return mapOf(
        ACNV.conversationId to row[ACNV.conversationId],
        ACNV.title to row[ACNV.title],
        ACNV.running to (row[ATBL.runningTurnId] != null),
        ACNV.readOnly to (row[ACNV.readOnly] == true || row[ATBL.toolsetVersion] != AssistantPrompt.versionOf(client)),
        PF.createdAt to row[PF.createdAt],
        PF.updatedAt to row[PF.updatedAt],
    )
}

/** [row], a stored turn, as the page reads it. */
fun turnInfo(row: Map<String, Any?>): Map<String, Any?> = buildMap {
    put(ACNV.turnId, row[ACNV.turnId])
    put(ACNV.text, row[ACNV.text])
    put(ACNV.status, row[ACNV.status])
    row[ACNV.stopReason]?.let { put(ACNV.stopReason, it) }
    row[ACNV.note]?.let { put(ACNV.note, it) }
    put(ACNV.steps, row[ACNV.steps].toOptLong() ?: 0)
    put(ACNV.weighted, row[ACNV.weighted].toOptLong() ?: 0)
    row[ASST.model]?.let { put(ASST.model, it) }
    put(ACNV.startedAt, row[PF.createdAt])
    row[ACNV.endedAt]?.let { put(ACNV.endedAt, it) }
}

/**
 * What the page shows [person] before anything is sent (issue #1527). The allowance as a share, never its amount: what
 * the budget is in money is the operator's, and a share says what the page needs -- how close the client is to its end.
 */
fun clientStatus(cxt: KdrCxt, service: AssistantService, person: AssistantPerson): Map<String, Any?> {
    val client = person.client
    val budget = service.budgetOf(service.clientRow(cxt, client))
    val used = service.usedThisMonth(cxt, client)
    return mapOf(
        ASST.client to client,
        ACNV.on to service.isOn(cxt, client),
        ACNV.offered to (person.sandboxUser == null && service.offeredTo(cxt, client)),
        ACNV.hasSandbox to SandboxAccess.hasSandbox(cxt, client),
        ACNV.sandboxInSource to (ClientService.get(cxt).originOf(client) == GedraConfigOrigin.source),
        ACNV.inSandbox to (person.sandboxUser != null),
        ACNV.usedShare to if (budget > 0) used.toDouble() / budget else 1.0,
        ACNV.spent to (used >= budget),
        ACNV.resetsAt to AssistantService.nextMonthStart(cxt.now()),
        ASST.dataNotice to DATA.notice,
    )
}
