package com.dynamicruntime.assistant

import com.dynamicruntime.common.assistant.ACNV
import com.dynamicruntime.common.assistant.ASST
import com.dynamicruntime.common.context.KdrCxt
import com.dynamicruntime.common.endpoint.HttpMethod
import com.dynamicruntime.common.endpoint.ListPage
import com.dynamicruntime.common.endpoint.SchModule
import com.dynamicruntime.common.endpoint.schemaModule
import com.dynamicruntime.common.exception.KdrException
import com.dynamicruntime.common.gedra.ClientService
import com.dynamicruntime.common.schema.SCT
import com.dynamicruntime.common.sql.PF
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
        property(ACNV.readOnly, "Whether it was begun with an earlier version of the assistant, and so cannot go on.", required = true) {
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
        AssistantTurns.recoverLapsed(c, AssistantTurns.ownConversation(c, conversationId))
        val conversation = AssistantTurns.ownConversation(c, conversationId)
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
        "The caller's conversations with the workflow assistant in the client they are signed in to, most recent first.",
        outputRef = ACNV.summaryType,
        noLimit = true,
        sessionOnly = true,
    ) { c, _ ->
        val items = AssistantStore.conversationsOf(c, c.userProfile.userId, c.userProfile.client).map { summaryOf(c, it) }
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
    val clientName = ClientService.getOrNull(cxt)?.known(client)?.name ?: client
    return mapOf(
        ACNV.conversationId to row[ACNV.conversationId],
        ACNV.title to row[ACNV.title],
        ACNV.running to (row[ATBL.runningTurnId] != null),
        ACNV.readOnly to (row[ATBL.toolsetVersion] != AssistantPrompt.versionOf(cxt, client, clientName)),
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
