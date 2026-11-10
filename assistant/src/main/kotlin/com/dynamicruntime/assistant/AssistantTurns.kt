package com.dynamicruntime.assistant

import com.dynamicruntime.common.annotation.KdrPrivate
import com.dynamicruntime.common.assistant.ACNV
import com.dynamicruntime.common.assistant.ASST
import com.dynamicruntime.common.context.KdrCxt
import com.dynamicruntime.common.exception.EXC
import com.dynamicruntime.common.exception.KdrException
import com.dynamicruntime.common.gedra.CFEP
import com.dynamicruntime.common.gedra.sandboxOf
import com.dynamicruntime.common.gedra.sandboxParentOf
import com.dynamicruntime.common.http.request.InProcessPrincipal
import com.dynamicruntime.common.logging.KdrLogger
import com.dynamicruntime.common.sql.PF
import com.dynamicruntime.common.user.AdminRules
import com.dynamicruntime.common.user.AuthUserRow
import com.dynamicruntime.common.user.SandboxAccess
import com.dynamicruntime.common.user.UserService
import com.dynamicruntime.common.util.mkUniqueId
import com.dynamicruntime.common.util.toOptInstantOrNull
import com.dynamicruntime.common.util.toOptLong
import com.dynamicruntime.common.util.toOptStr
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes

/** The assistant's log: turns starting and ending, every write, refusals, and model errors -- never the model's text. */
object LogAssistant : KdrLogger("assistant")

/** The limits of a turn that are not settings. */
@Suppress("ConstPropertyName")
object TURN {
    /**
     * How long a turn's node vouches for it between renewals; it must outlast a step's model call, its retry included
     * ([callTimeoutMinutes] times two, with room to spare).
     */
    const val leaseMinutes = 25L

    /** One model call's timeout, per attempt: what a non-streaming answer of up to `AENV.maxOutputTokens` needs. */
    const val callTimeoutMinutes = 10L

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
 * -- because only such a request may claim their user in the sandbox, which the turn's tools then act as; signed in to
 * the sandbox already, they act as the user the session has there ([personOf]). It checks, in
 * order: the assistant is on for the client, the client has a sandbox, its budget is not spent, the administrator has
 * turns left this hour, the client has room for another running turn, and no turn is running in the conversation. Then
 * it records the turn, hands it to a worker, and answers at once.
 *
 * **A worker** ([TurnRunner]) holds a lease on its turn, renewed at every step. A turn whose lease lapsed -- its node
 * stopped -- is marked interrupted by whoever next looks at the conversation ([recoverLapsed]).
 */
object AssistantTurns {
    /** Starts a turn for [text], in [conversationId] or a new conversation, for the person on [cxt] ([personOf]). */
    fun start(cxt: KdrCxt, service: AssistantService, conversationId: String?, clientMessageId: String, text: String): Map<String, Any?> {
        val person = personOf(cxt)
        val client = person.client
        val userId = person.userId
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
        AssistantStore.turnForMessage(cxt, userId, clientMessageId)?.let { return sameMessage(it, conversationId, message) }
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
        val version = AssistantPrompt.versionOf(cxt, client)
        val turnId = cxt.mkUniqueId()
        // Claimed before anything is written for the turn: a message refused as busy writes nothing.
        val newConversation = conversationId == null
        val conversation = if (conversationId != null) {
            val found = ownConversation(cxt, person, conversationId)
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
            val sandboxUser = person.sandboxUser ?: sandboxUserOf(cxt, client)
            if (newConversation) {
                AssistantStore.insertConversation(
                    cxt,
                    mapOf(
                        ACNV.conversationId to conversation, ASST.client to client, PF.userId to userId,
                        ACNV.title to message.take(TURN.titleChars), ATBL.toolsetVersion to version, ATBL.runningTurnId to turnId,
                    ),
                )
            }
            AssistantStore.insertTurn(
                cxt,
                mapOf(
                    ACNV.turnId to turnId, ACNV.conversationId to conversation, ASST.client to client, PF.userId to userId,
                    ACNV.clientMessageId to clientMessageId, ACNV.text to message,
                    ATBL.sandboxUserId to sandboxUser.userId, ATBL.sandboxIdentityId to sandboxUser.identityId,
                    ATBL.sandboxClient to sandboxUser.client, ACNV.status to ACNV.statusRunning, ACNV.steps to 0L,
                    ASST.inputTokens to 0L, ASST.cacheWriteTokens to 0L, ASST.cacheReadTokens to 0L, ASST.outputTokens to 0L,
                    ACNV.weighted to 0L, ATBL.cancelRequested to false, ATBL.leaseUntil to now + TURN.leaseMinutes.minutes,
                ),
            )
        } catch (e: KdrException) {
            if (newConversation) {
                AssistantStore.deleteNewConversation(cxt, conversation)
            } else {
                AssistantStore.releaseConversation(cxt, conversation, turnId)
            }
            // The same message sent twice at once: the other send recorded the turn first, and this one is that turn.
            AssistantStore.turnForMessage(cxt, userId, clientMessageId)?.let { return sameMessage(it, conversationId, message) }
            throw e
        }
        // Launched only once everything is recorded, so a failure above never strands a turn that runs.
        LogAssistant.info(cxt, "Turn $turnId started in conversation $conversation for user $userId of '$client'.")
        service.launch(cxt, turnId)
        return mapOf(ACNV.conversationId to conversation, ACNV.turnId to turnId)
    }

    /**
     * The turn a resent message already started -- the same text, into the same conversation -- or a refusal when the
     * message id was used for another message: different text, or another conversation. Answering that one's turn would
     * look like a reply to this message, and nothing would tell the sender it never went.
     */
    @KdrPrivate
    fun sameMessage(turn: Map<String, Any?>, conversationId: String?, text: String): Map<String, Any?> {
        val id = turn[ACNV.clientMessageId]
        if (conversationId != null && turn[ACNV.conversationId] != conversationId) {
            throw refused("Message id '$id' was already sent in another conversation; give this message an id of its own.",
                EXC.conflict, ACNV.messageIdUsedCode)
        }
        if (turn[ACNV.text] != text) {
            throw refused("Message id '$id' was already used for another message; give this message an id of its own.",
                EXC.conflict, ACNV.messageIdUsedCode)
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
     * The administrator's own user in the client's sandbox, which the tools act as. Their enabled one when it was opened
     * from the user they are now, as it stands; otherwise claimed -- made on first use, or its opener brought up to
     * date, since what a sandbox user may do is what its opener may -- in their own signed-in request, as opening the
     * sandbox does. One another administrator disabled is refused rather than enabled again.
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
            existing != null && !existing.enabled -> throw KdrException(
                "Your user in the sandbox has been disabled, so the assistant cannot act there.", code = EXC.notAuthorized,
            )
            existing != null && existing.openedBy == own.userId -> existing
            else -> users.authFormHandler.claimOwnSandboxUser(cxt, null)
        }
    }

    /** What [start] answers: the conversation and the turn. */
    @KdrPrivate
    fun started(turn: Map<String, Any?>): Map<String, Any?> =
        mapOf(ACNV.conversationId to turn[ACNV.conversationId], ACNV.turnId to turn[ACNV.turnId])

    @KdrPrivate
    fun refused(message: String, code: Int, errorCode: String): KdrException =
        KdrException(message, code = code, extraData = mutableMapOf(KdrException.errorCodeKey to errorCode))

    /**
     * Who is talking to the assistant (issue #1527): a client administrator, outside the client's sandbox -- the caller,
     * or, when they are signed in to the sandbox, the user outside it their sandbox user was opened from, whose
     * conversations these are. So the page goes on working after "Try it in the sandbox" moved the session there. A
     * sandbox user nobody opened, or one opened from another client, has no one outside to speak for.
     *
     * Theme: #SBXAU.
     */
    fun personOf(cxt: KdrCxt): AssistantPerson {
        AdminRules.requireClientAdministrator(cxt)
        return personOrNull(cxt) ?: throw KdrException.mkInput(
            "Use the assistant from '${sandboxParentOf(cxt.userProfile.client)}' itself, as one of its administrators.",
        )
    }

    /**
     * [personOf] without the administrator check, or null when a sandbox caller has nobody outside to speak for: what the
     * `assistant:usable` cfact asks. Outside a sandbox it reads nothing.
     */
    fun personOrNull(cxt: KdrCxt): AssistantPerson? {
        val client = cxt.userProfile.client
        val parent = sandboxParentOf(client) ?: return AssistantPerson(cxt.userProfile.userId, client, sandboxUser = null)
        val own = UserService.get(cxt).queryByUserId(cxt, cxt.userProfile.userId) ?: return null
        val opener = SandboxAccess.standingOf(cxt, own).actsAs?.takeIf { it.client == parent } ?: return null
        return AssistantPerson(opener.userId, parent, sandboxUser = own)
    }

    /** The person's own conversation [conversationId]; 404 for any other. */
    fun ownConversation(cxt: KdrCxt, person: AssistantPerson, conversationId: String): Map<String, Any?> {
        val row = AssistantStore.conversation(cxt, conversationId)
        if (row == null || row[PF.userId].toOptLong() != person.userId || row[ASST.client] != person.client) {
            throw KdrException("No conversation '$conversationId' of yours.", code = EXC.notFound)
        }
        return row
    }

    /**
     * Marks the conversation's running turn interrupted when its lease lapsed -- its node stopped mid-turn -- answers
     * any tool call it left unanswered, so the history stays one the model can be sent, and frees the conversation.
     * Only one caller ever recovers a turn: the update that marks it is conditional on the lease having lapsed. A claim
     * whose turn is not recorded yet is left alone while a message could still be starting.
     */
    fun recoverLapsed(cxt: KdrCxt, conversation: Map<String, Any?>) {
        val conversationId = conversation[ACNV.conversationId].toString()
        val turnId = conversation[ATBL.runningTurnId].toOptStr() ?: return
        val turn = AssistantStore.turn(cxt, turnId)
        // A claim with no turn yet is a message being started, until it is as old as a lease: then its start failed.
        val claimedAt = conversation[PF.updatedAt].toOptInstantOrNull()
        if (turn == null && claimedAt != null && claimedAt > cxt.now() - TURN.leaseMinutes.minutes) return
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

    /**
     * Deletes the person's conversation [conversationId] (issue #1755): its transcript, and what they wrote in its turns,
     * whose counts and cost stay. Refused while a turn runs in it -- stop it first. The conversation is claimed for the
     * delete, as a message claims it for its turn, so no turn starts in it part way; a delete that stopped part way
     * left its claim, and may be made again.
     */
    fun delete(cxt: KdrCxt, conversationId: String): Map<String, Any?> {
        val person = personOf(cxt)
        recoverLapsed(cxt, ownConversation(cxt, person, conversationId))
        val running = ownConversation(cxt, person, conversationId)[ATBL.runningTurnId].toOptStr()
        val claimed = running == deletingClaim || (running == null && AssistantStore.claimConversation(cxt, conversationId, deletingClaim))
        if (!claimed) {
            throw refused("The assistant is still answering in this conversation: stop it, then delete it.", EXC.conflict, ACNV.busyCode)
        }
        AssistantStore.deleteConversation(cxt, conversationId)
        LogAssistant.info(cxt, "Conversation $conversationId was deleted by its administrator.")
        return mapOf(ACNV.conversationId to conversationId, ACNV.deleted to true)
    }

    /** The running turn a conversation names while it is being deleted: no turn's id, so no turn is ever mistaken for it. */
    @KdrPrivate
    const val deletingClaim = "deleting"

    /** Asks the running turn of the person's conversation [conversationId] to stop at its next step. */
    fun cancel(cxt: KdrCxt, conversationId: String): Map<String, Any?> {
        val person = personOf(cxt)
        // A turn whose node stopped is recovered rather than asked to stop: nothing would hear it.
        recoverLapsed(cxt, ownConversation(cxt, person, conversationId))
        val conversation = ownConversation(cxt, person, conversationId)
        val turnId = conversation[ATBL.runningTurnId].toOptStr()
        val asked = turnId != null && AssistantStore.requestCancel(cxt, turnId)
        if (asked) LogAssistant.info(cxt, "Turn $turnId in conversation $conversationId was asked to stop.")
        return mapOf(ACNV.conversationId to conversationId, ACNV.canceled to asked)
    }
}

/**
 * The administrator a conversation belongs to ([AssistantTurns.personOf]): their [userId] in [client], outside any
 * sandbox, and -- when they are signed in to the sandbox -- [sandboxUser], the caller's own row there, which the tools
 * then act as.
 */
class AssistantPerson(val userId: Long, val client: String, val sandboxUser: AuthUserRow?)

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
        // The kind of client, worked out once: the prompt sent is the one the conversation's version names, or none is.
        val line = AssistantPrompt.clientLine(cxt, client)
        val conversation = AssistantStore.conversation(cxt, conversationId) ?: return
        if (conversation[ATBL.toolsetVersion] != AssistantPrompt.versionFor(client, line)) {
            return end(ACNV.statusFailed, AssistantTurns.readOnlyReason(conversation))
        }
        val system = AssistantPrompt.systemFor(client, line)
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
                    // A refusal stored in the history would meet the next request again: then this conversation ends.
                    if (response.content.isNotEmpty()) AssistantStore.markReadOnly(cxt, conversationId)
                    val next = if (response.content.isNotEmpty()) "Start a new conversation." else "Try asking another way."
                    return end(ACNV.statusStopped, "The model declined to go on with this request. $next")
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
     * part way, and the turn is no longer this worker's to write. A failure between calls answers the calls already run
     * with their results and the rest as not run, records that, and then ends the turn.
     */
    @KdrPrivate
    fun runTools(toolRun: ToolRun, calls: List<Map<String, Any?>>): Map<String, Any?>? {
        val results = mutableListOf<Map<String, Any?>>()
        var failure: Exception? = null
        for (call in calls) {
            val id = call[TOOL.id].toString()
            if (failure != null) {
                // Each call is answered with what happened to it: those not reached are not run, and say so.
                results.add(TurnRunner.resultBlock(id, ToolResult("Not run: the assistant ran into a problem.", isError = true)))
                continue
            }
            try {
                // Renewed before each call: a step of many slow calls must not outlast its lease while it works.
                if (!AssistantStore.recordProgress(cxt, turnId, progress, cxt.now() + TURN.leaseMinutes.minutes)) return null
            } catch (e: Exception) {
                failure = e
                results.add(TurnRunner.resultBlock(id, ToolResult("Not run: the assistant ran into a problem.", isError = true)))
                continue
            }
            val name = call[TOOL.name].toString()
            val input = (call[TOOL.input] as? Map<*, *>)?.entries?.associate { it.key.toString() to it.value }.orEmpty()
            val result = AssistantTools.run(toolRun, name, input)
            val tool = AssistantTools.byName[name]
            if (tool?.method != null && tool.method.name != "GET") {
                LogAssistant.info(cxt, "Turn $turnId: $name ${if (result.isError) "refused" else "applied"}${dryRunNote(input)}.")
            } else if (result.isError) {
                LogAssistant.info(cxt, "Turn $turnId: $name refused.")
            }
            results.add(TurnRunner.resultBlock(id, result))
        }
        toolRun.endStep()
        val answered = mapOf(MSG.role to MSG.user, MSG.content to results)
        if (failure != null) {
            // What ran is recorded as it ran before the turn ends, so the model is never told a write did not happen.
            append(answered)
            throw failure
        }
        return answered
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
