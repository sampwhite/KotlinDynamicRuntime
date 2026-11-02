package com.dynamicruntime.assistant

import com.dynamicruntime.common.annotation.KdrPrivate
import com.dynamicruntime.common.assistant.ACNV
import com.dynamicruntime.common.assistant.ASST
import com.dynamicruntime.common.context.KdrCxt
import com.dynamicruntime.common.exception.EXC
import com.dynamicruntime.common.exception.KdrException
import com.dynamicruntime.common.gedra.CFEP
import com.dynamicruntime.common.gedra.isSandboxClient
import com.dynamicruntime.common.gedra.sandboxOf
import com.dynamicruntime.common.http.request.InProcessPrincipal
import com.dynamicruntime.common.logging.KdrLogger
import com.dynamicruntime.common.sql.PF
import com.dynamicruntime.common.user.AdminRules
import com.dynamicruntime.common.user.AuthUserRow
import com.dynamicruntime.common.user.SandboxAccess
import com.dynamicruntime.common.user.UserService
import com.dynamicruntime.common.util.mkUniqueId
import com.dynamicruntime.common.util.toOptLong
import com.dynamicruntime.common.util.toOptStr
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes

/** The assistant's log: turns starting and ending, every write, refusals, and model errors -- never the model's text. */
object LogAssistant : KdrLogger("assistant")

/** The limits of a turn that are not settings. */
@Suppress("ConstPropertyName")
object TURN {
    /** How long a turn's node vouches for it between steps; it must outlast a step's model call, retries included. */
    const val leaseMinutes = 15L

    /** One model call's timeout, per attempt. */
    const val callTimeoutMinutes = 5L

    /** How often a failed model call is tried again. */
    const val callRetries = 1

    /** The longest message an administrator may send. */
    const val maxTextChars = 20_000

    /** How much of a conversation's first message is its title. */
    const val titleChars = 80
}

/**
 * The assistant's turns (issue #1526): starting one for a message the administrator sent, running it on a thread of its
 * own, and the recovery, cancel, and reads around it.
 *
 * **Starting** ([start]) runs in the administrator's own signed-in request -- every assistant endpoint is session-only
 * -- because only such a request may claim their user in the sandbox, which the turn's tools then act as. It checks, in
 * order: the assistant is on for the client, the client has a sandbox, its budget is not spent, the administrator has
 * turns left this hour, the client has room for another running turn, and no turn is running in the conversation. Then
 * it records the turn, hands it to a worker, and answers at once.
 *
 * **A worker** ([TurnRunner]) holds a lease on its turn, renewed at every step. A turn whose lease lapsed -- its node
 * stopped -- is marked interrupted by whoever next looks at the conversation ([recoverLapsed]).
 */
object AssistantTurns {
    /** Starts a turn for [text], in [conversationId] or a new conversation, as the administrator on [cxt]. */
    fun start(cxt: KdrCxt, service: AssistantService, conversationId: String?, clientMessageId: String, text: String): Map<String, Any?> {
        AdminRules.requireClientAdministrator(cxt)
        val client = cxt.userProfile.client
        val userId = cxt.userProfile.userId
        if (isSandboxClient(client)) {
            throw KdrException.mkInput("Use the assistant from '${service.payerOf(client)}' itself: it works in the sandbox for you.")
        }
        val message = text.trim()
        if (message.isEmpty()) throw KdrException.mkInput("A message needs some text.")
        if (message.length > TURN.maxTextChars) throw KdrException.mkInput("A message may be at most ${TURN.maxTextChars} characters.")
        if (!service.isOn(cxt, client)) {
            throw refused("The workflow assistant is not on for '$client'.", EXC.notAuthorized, ACNV.notOnCode)
        }
        if (!SandboxAccess.hasSandbox(cxt, client)) {
            throw refused(
                "'$client' has no sandbox. Turn one on from the Clients page: the assistant drafts there, for you to try.",
                EXC.conflict, ACNV.noSandboxCode,
            )
        }
        // A message the page sends again -- a retry after a lost answer -- is the turn it already started.
        AssistantStore.turnForMessage(cxt, userId, clientMessageId)?.let { return sameMessage(it, conversationId) }
        val budget = service.budgetOf(service.clientRow(cxt, client))
        if (service.usedThisMonth(cxt, client) >= budget) {
            throw refused("'$client' has used this month's allowance for the assistant.", EXC.tooManyRequests, ACNV.budgetSpentCode)
        }
        val now = cxt.now()
        if (AssistantStore.turnsStartedSince(cxt, userId, now - 1.hours) >= service.settings.turnsPerHour) {
            throw refused(
                "You have sent the assistant ${service.settings.turnsPerHour} messages in the last hour, as many as it takes.",
                EXC.tooManyRequests, ACNV.turnLimitCode,
            )
        }
        if (AssistantStore.runningTurns(cxt, client, now) >= service.settings.concurrentTurns) {
            throw refused(
                "The assistant is already working on ${service.settings.concurrentTurns} messages for '$client'; try again shortly.",
                EXC.tooManyRequests, ACNV.turnLimitCode,
            )
        }
        val version = AssistantPrompt.versionOf(client)
        val turnId = cxt.mkUniqueId()
        // Claimed before anything is written for the turn: a message refused as busy writes nothing.
        val newConversation = conversationId == null
        val conversation = if (conversationId != null) {
            val found = ownConversation(cxt, conversationId)
            if (found[ACNV.readOnly] == true || found[ATBL.toolsetVersion] != version) {
                throw refused(readOnlyReason(found), EXC.conflict, ACNV.readOnlyCode)
            }
            recoverLapsed(cxt, found)
            if (!AssistantStore.claimConversation(cxt, conversationId, turnId)) {
                throw refused("The assistant is still answering in this conversation.", EXC.conflict, ACNV.busyCode)
            }
            conversationId
        } else {
            cxt.mkUniqueId()
        }
        try {
            // The administrator's own user in the sandbox, which the tools act as.
            val sandboxUser = sandboxUserOf(cxt, client)
            if (newConversation) {
                AssistantStore.insertConversation(
                    cxt,
                    mapOf(
                        ACNV.conversationId to conversation, ASST.client to client, PF.userId to userId,
                        ACNV.title to message.take(TURN.titleChars), ATBL.toolsetVersion to version, ATBL.runningTurnId to turnId,
                    ),
                )
            }
            val row = mapOf(
                ACNV.turnId to turnId, ACNV.conversationId to conversation, ASST.client to client, PF.userId to userId,
                ACNV.clientMessageId to clientMessageId, ACNV.text to message,
                ATBL.sandboxUserId to sandboxUser.userId, ATBL.sandboxIdentityId to sandboxUser.identityId,
                ATBL.sandboxClient to sandboxUser.client, ACNV.status to ACNV.statusRunning, ACNV.steps to 0L,
                ASST.inputTokens to 0L, ASST.cacheWriteTokens to 0L, ASST.cacheReadTokens to 0L, ASST.outputTokens to 0L,
                ACNV.weighted to 0L, ATBL.cancelRequested to false, ATBL.leaseUntil to now + TURN.leaseMinutes.minutes,
            )
            AssistantStore.insertTurn(cxt, row)
            LogAssistant.info(cxt, "Turn $turnId started in conversation $conversation for user $userId of '$client'.")
            service.launch(cxt, turnId)
            return started(row)
        } catch (e: KdrException) {
            if (newConversation) {
                AssistantStore.deleteNewConversation(cxt, conversation)
            } else {
                AssistantStore.releaseConversation(cxt, conversation, turnId)
            }
            // The same message sent twice at once: the other send recorded the turn first, and this one is that turn.
            AssistantStore.turnForMessage(cxt, userId, clientMessageId)?.let { return sameMessage(it, conversationId) }
            throw e
        }
    }

    /**
     * The turn a resent message already started: the same conversation's, or a refusal when the page sent one message
     * id into two conversations.
     */
    @KdrPrivate
    fun sameMessage(turn: Map<String, Any?>, conversationId: String?): Map<String, Any?> {
        if (conversationId != null && turn[ACNV.conversationId] != conversationId) {
            throw KdrException("Message id '${turn[ACNV.clientMessageId]}' was already sent in another conversation.", code = EXC.conflict)
        }
        return started(turn)
    }

    /** Why a conversation cannot go on, in the administrator's terms. */
    fun readOnlyReason(conversation: Map<String, Any?>): String = if (conversation[ACNV.readOnly] == true) {
        "The model declined to go on with this conversation, and would again. Start a new one."
    } else {
        "This conversation was begun with an earlier version of the assistant; start a new one to go on."
    }

    /**
     * The administrator's own user in the client's sandbox, which the tools act as. Their enabled one when they have it;
     * claimed -- made, on first use -- only when they have none, in their own signed-in request, as opening the sandbox
     * does. One another administrator disabled is refused rather than enabled again.
     */
    @KdrPrivate
    fun sandboxUserOf(cxt: KdrCxt, client: String): AuthUserRow {
        val users = UserService.get(cxt)
        val own = users.queryByUserId(cxt, cxt.userProfile.userId)
            ?: throw KdrException("Your user could not be found.", code = EXC.notFound)
        val existing = users.usersOfIdentity(cxt, own.identityId).firstOrNull {
            it.client == sandboxOf(client) && it.persona == own.persona && it.personaSuffix == own.personaSuffix && !it.isDeleted
        }
        return when {
            existing == null -> users.authFormHandler.claimOwnSandboxUser(cxt, null)
            existing.enabled -> existing
            else -> throw KdrException(
                "Your user in the sandbox has been disabled, so the assistant cannot act there.", code = EXC.notAuthorized,
            )
        }
    }

    /** What [start] answers: the conversation and the turn. */
    @KdrPrivate
    fun started(turn: Map<String, Any?>): Map<String, Any?> =
        mapOf(ACNV.conversationId to turn[ACNV.conversationId], ACNV.turnId to turn[ACNV.turnId])

    @KdrPrivate
    fun refused(message: String, code: Int, errorCode: String): KdrException =
        KdrException(message, code = code, extraData = mutableMapOf(KdrException.errorCodeKey to errorCode))

    /** The caller's own conversation [conversationId] in the client they are signed in to; 404 for any other. */
    fun ownConversation(cxt: KdrCxt, conversationId: String): Map<String, Any?> {
        val row = AssistantStore.conversation(cxt, conversationId)
        if (row == null || row[PF.userId].toOptLong() != cxt.userProfile.userId || row[ASST.client] != cxt.userProfile.client) {
            throw KdrException("No conversation '$conversationId' of yours.", code = EXC.notFound)
        }
        return row
    }

    /**
     * Marks the conversation's running turn interrupted when its lease lapsed -- its node stopped mid-turn -- answers
     * any tool call it left unanswered, so the history stays one the model can be sent, and frees the conversation.
     * Only one caller ever recovers a turn: the update that marks it is conditional on the lease having lapsed.
     */
    fun recoverLapsed(cxt: KdrCxt, conversation: Map<String, Any?>) {
        val conversationId = conversation[ACNV.conversationId].toString()
        val turnId = conversation[ATBL.runningTurnId].toOptStr() ?: return
        val turn = AssistantStore.turn(cxt, turnId)
        if (turn != null && turn[ACNV.status] == ACNV.statusRunning) {
            val note = "The assistant stopped part way: the server running it went away. Send your message again to go on."
            if (!AssistantStore.interruptLapsed(cxt, turnId, note, cxt.now())) return
            LogAssistant.warn(cxt, "Turn $turnId in conversation $conversationId was interrupted: its lease lapsed.")
            val rows = AssistantStore.messages(cxt, conversationId)
            val last = rows.lastOrNull()
            val pending = last?.let { AssistantHistory.toolUses(AssistantStore.messageOf(it)) }.orEmpty()
            if (last != null && AssistantStore.messageOf(last)[MSG.role] == MSG.assistant && pending.isNotEmpty()) {
                val answers = TurnRunner.answerAll(pending, "Not run: the assistant was interrupted.")
                AssistantStore.appendMessage(cxt, conversationId, last[ACNV.seq].toOptLong()!! + 1, turnId, answers)
            }
        }
        AssistantStore.releaseConversation(cxt, conversationId, turnId)
    }

    /** Asks the running turn of the caller's conversation [conversationId] to stop at its next step. */
    fun cancel(cxt: KdrCxt, conversationId: String): Map<String, Any?> {
        // A turn whose node stopped is recovered rather than asked to stop: nothing would hear it.
        recoverLapsed(cxt, ownConversation(cxt, conversationId))
        val conversation = ownConversation(cxt, conversationId)
        val turnId = conversation[ATBL.runningTurnId].toOptStr()
        val asked = turnId != null && AssistantStore.requestCancel(cxt, turnId)
        if (asked) LogAssistant.info(cxt, "Turn $turnId in conversation $conversationId was asked to stop.")
        return mapOf(ACNV.conversationId to conversationId, ACNV.canceled to asked)
    }
}

/**
 * Runs one turn (issue #1526), on a worker's thread with a context of its own. Each step: check the turn is still
 * wanted (not canceled, within its steps, within the client's budget) and the history sound, ask the model, record what
 * it cost, append its answer exactly, and act on why it stopped:
 * - `tool_use`: run every call, and answer them all in one message;
 * - `end_turn`: done;
 * - `max_tokens`: the answer was cut off, so a call in it is answered as not run, and the turn ends;
 * - `pause_turn`: ask again;
 * - `refusal`: the turn ends, saying so plainly; an empty answer is not stored, since it could not be sent back.
 *
 * Every write is conditional on the turn still running, so a worker whose lease lapsed -- and whose turn another node
 * marked interrupted -- stops at its next step having written nothing more.
 */
class TurnRunner(@KdrPrivate val service: AssistantService, @KdrPrivate val cxt: KdrCxt, @KdrPrivate val turnId: String) {
    @KdrPrivate
    val turn = AssistantStore.turn(cxt, turnId) ?: throw KdrException("No turn '$turnId'.")

    @KdrPrivate
    val conversationId = turn[ACNV.conversationId].toString()

    @KdrPrivate
    val client = turn[ASST.client].toString()

    @KdrPrivate
    val progress = TurnProgress.of(turn)

    @KdrPrivate
    val history = mutableListOf<Map<String, Any?>>()

    @KdrPrivate
    var nextSeq = 1L

    fun run() {
        try {
            AssistantStore.messages(cxt, conversationId).forEach {
                history.add(AssistantStore.messageOf(it))
                nextSeq = (it[ACNV.seq].toOptLong() ?: 0) + 1
            }
            append(mapOf(MSG.role to MSG.user, MSG.content to listOf(mapOf(MSG.type to MSG.text, MSG.text to turn[ACNV.text]))))
            loop()
        } catch (e: Exception) {
            LogAssistant.error(cxt, "Turn $turnId in conversation $conversationId failed.", e)
            // A call left unanswered would leave a history the model can never be sent again.
            runCatching { answerPending("Not run: the assistant ran into a problem.") }
                .onFailure { LogAssistant.error(cxt, "Turn $turnId: its pending tool calls could not be answered.", it) }
            end(ACNV.statusFailed, "The assistant ran into a problem and stopped. Try your message again.")
        }
    }

    /** Answers, as not run, the tool calls of the model's last message when nothing has answered them yet. */
    @KdrPrivate
    fun answerPending(text: String) {
        val last = history.lastOrNull() ?: return
        val calls = AssistantHistory.toolUses(last)
        if (last[MSG.role] == MSG.assistant && calls.isNotEmpty()) append(answerAll(calls, text))
    }

    @KdrPrivate
    fun loop() {
        val system = AssistantPrompt.system(client)
        val tools = AssistantTools.definitions()
        val principal = InProcessPrincipal(
            turn[ATBL.sandboxUserId].toOptLong()!!, turn[ATBL.sandboxIdentityId].toString(), turn[ATBL.sandboxClient].toString(),
            "assistant:$turnId", AssistantTools.scope,
        )
        val toolRun = ToolRun(cxt, principal, cxt.traceId ?: turnId)
        val settings = service.settings
        val model = service.model ?: return end(ACNV.statusFailed, "The workflow assistant is not available on this server.")
        // Whether the last answer was paused, so the next request continues it rather than answering it.
        var continuing = false
        while (true) {
            val current = AssistantStore.turn(cxt, turnId) ?: return
            if (current[ACNV.status] != ACNV.statusRunning) return
            if (current[ATBL.cancelRequested] == true) return end(ACNV.statusCanceled, "Stopped, as you asked.")
            if (progress.steps >= settings.maxSteps) {
                return end(ACNV.statusStopped, "The assistant stopped after ${settings.maxSteps} steps. Send a message to let it go on.")
            }
            if (service.usedThisMonth(cxt, client) >= service.budgetOf(service.clientRow(cxt, client))) {
                return end(ACNV.statusStopped, "The assistant stopped: '$client' has used this month's allowance.")
            }
            val problems = AssistantHistory.historyProblems(history, continuing)
            if (problems.isNotEmpty()) {
                LogAssistant.error(cxt, "Turn $turnId: the history cannot be sent: ${problems.joinToString("; ")}")
                return end(ACNV.statusFailed, "The conversation cannot go on. Start a new one.")
            }
            val messages = AssistantHistory.forRequest(history)
            val request = ModelRequest(settings.model, settings.maxTokens, system, tools, messages, settings.effort)
            val response = try {
                model.respond(cxt, request)
            } catch (e: KdrException) {
                LogAssistant.error(cxt, "Turn $turnId: the model call failed (${e.code}).", e)
                return end(ACNV.statusFailed, "The assistant could not reach the model. Try again in a little while.")
            }
            val weighted = service.recordUsage(cxt, client, response.model, response.usage)
            progress.add(response.usage, weighted, response.model, response.stopReason)
            // Renewed, and still ours: a turn marked interrupted meanwhile writes nothing more.
            if (!AssistantStore.recordProgress(cxt, turnId, progress, cxt.now() + TURN.leaseMinutes.minutes)) return
            val calls = AssistantHistory.toolUses(response.message)
            continuing = false
            when (response.stopReason) {
                MSG.refusal -> {
                    if (response.content.isNotEmpty()) {
                        append(response.message)
                        if (calls.isNotEmpty()) append(TurnRunner.answerAll(calls, "Not run: the model declined to go on."))
                    }
                    // A refusal stays in the history, and the next request would meet it again: this conversation ends.
                    AssistantStore.markReadOnly(cxt, conversationId)
                    return end(ACNV.statusStopped, "The model declined to go on with this request. Start a new conversation.")
                }
                MSG.toolUse -> {
                    append(response.message)
                    if (AssistantStore.turn(cxt, turnId)?.get(ATBL.cancelRequested) == true) {
                        append(TurnRunner.answerAll(calls, "Not run: the administrator stopped the assistant."))
                        return end(ACNV.statusCanceled, "Stopped, as you asked.")
                    }
                    // Null when the turn's lease was given up part way: another node has answered for it.
                    append(runTools(toolRun, calls) ?: return)
                }
                MSG.maxTokens -> {
                    if (response.content.isNotEmpty()) append(response.message)
                    if (calls.isNotEmpty()) append(TurnRunner.answerAll(calls, "Not run: the answer was cut off before it was complete."))
                    return end(ACNV.statusStopped, "The assistant's answer ran out of room. Ask it to go on, in smaller steps.")
                }
                MSG.pauseTurn -> if (response.content.isNotEmpty()) {
                    append(response.message)
                    continuing = true
                }
                else -> {
                    // `end_turn`, or a reason this code does not know: a call in such an answer is not run, but answered.
                    if (response.content.isNotEmpty()) append(response.message)
                    if (calls.isNotEmpty()) append(TurnRunner.answerAll(calls, "Not run: the answer stopped (${response.stopReason})."))
                    return end(ACNV.statusDone, null)
                }
            }
        }
    }

    /**
     * Runs every call in [calls] and answers them all in one message, in order; null when the turn's lease was lost
     * part way, and the turn is no longer this worker's to write.
     */
    @KdrPrivate
    fun runTools(toolRun: ToolRun, calls: List<Map<String, Any?>>): Map<String, Any?>? {
        val results = mutableListOf<Map<String, Any?>>()
        for (call in calls) {
            // Renewed before each call: a step of many slow calls must not outlast its lease while it works.
            if (!AssistantStore.recordProgress(cxt, turnId, progress, cxt.now() + TURN.leaseMinutes.minutes)) return null
            val name = call[TOOL.name].toString()
            val input = (call[TOOL.input] as? Map<*, *>)?.entries?.associate { it.key.toString() to it.value }.orEmpty()
            val result = AssistantTools.run(toolRun, name, input)
            val tool = AssistantTools.byName[name]
            if (tool?.method != null && tool.method.name != "GET") {
                LogAssistant.info(cxt, "Turn $turnId: $name ${if (result.isError) "refused" else "applied"}${dryRunNote(input)}.")
            } else if (result.isError) {
                LogAssistant.info(cxt, "Turn $turnId: $name refused.")
            }
            results.add(TurnRunner.resultBlock(call[TOOL.id].toString(), result))
        }
        toolRun.endStep()
        return mapOf(MSG.role to MSG.user, MSG.content to results)
    }

    @KdrPrivate
    fun dryRunNote(input: Map<String, Any?>): String = if (input[CFEP.dryRun] == true) " (dry run)" else ""

    /** Appends [message] to the history and the stored transcript. */
    @KdrPrivate
    fun append(message: Map<String, Any?>) {
        AssistantStore.appendMessage(cxt, conversationId, nextSeq, turnId, message)
        history.add(message)
        nextSeq += 1
    }

    /** Ends the turn, if it is still running, and frees its conversation. */
    @KdrPrivate
    fun end(status: String, note: String?) {
        if (AssistantStore.endTurn(cxt, turnId, status, note, cxt.now())) {
            LogAssistant.info(
                cxt, "Turn $turnId ended $status after ${progress.steps} steps, ${progress.weighted} weighted tokens" +
                    (progress.stopReason?.let { ", the model last stopping on $it" } ?: "") + ".",
            )
        }
        AssistantStore.releaseConversation(cxt, conversationId, turnId)
    }

    companion object {
        /** One message answering every call in [calls] with the same error [text]. */
        fun answerAll(calls: List<Map<String, Any?>>, text: String): Map<String, Any?> = mapOf(
            MSG.role to MSG.user,
            MSG.content to calls.map { resultBlock(it[TOOL.id].toString(), ToolResult(text, isError = true)) },
        )

        /** The `tool_result` block answering call [id] with [result]. */
        fun resultBlock(id: String, result: ToolResult): Map<String, Any?> = buildMap {
            put(MSG.type, TOOL.toolResult)
            put(TOOL.toolUseId, id)
            put(TOOL.content, result.text)
            if (result.isError) put(TOOL.isError, true)
        }
    }
}
