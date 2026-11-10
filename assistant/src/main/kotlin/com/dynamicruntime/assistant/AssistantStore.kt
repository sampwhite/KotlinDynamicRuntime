package com.dynamicruntime.assistant

import com.dynamicruntime.common.annotation.KdrPrivate
import com.dynamicruntime.common.assistant.ACNV
import com.dynamicruntime.common.assistant.ASST
import com.dynamicruntime.common.context.KdrCxt
import com.dynamicruntime.common.sql.KdrTable
import com.dynamicruntime.common.sql.PF
import com.dynamicruntime.common.sql.SqlCxt
import com.dynamicruntime.common.sql.SqlStatement
import com.dynamicruntime.common.sql.SqlStmtUtil
import com.dynamicruntime.common.sql.SqlTopicService
import com.dynamicruntime.common.sql.SqlTopicUtil
import com.dynamicruntime.common.util.toJsonMapOrEmpty
import com.dynamicruntime.common.util.toOptLong
import kotlin.time.Instant

/**
 * The assistant's conversations in the database (issue #1526): reads by key, inserts, and the few updates there are,
 * each one statement made **conditional on the state it changes**, so two writers -- a turn's worker and a cancel, or a
 * worker and the recovery of its expired lease -- cannot both succeed: whichever is second changes no row, and knows it.
 *
 * Every call is outside a transaction: a turn's worker calls the model between them, and a statement here locks nothing
 * past itself.
 */
object AssistantStore {
    // --- Conversations ---

    fun conversation(cxt: KdrCxt, conversationId: String): Map<String, Any?>? =
        queryOne(cxt, ATBL.conversations, mapOf(ACNV.conversationId to conversationId))

    /** [userId]'s conversations in [client], most recently changed first. */
    fun conversationsOf(cxt: KdrCxt, userId: Long, client: String): List<Map<String, Any?>> = query(
        cxt, ATBL.conversations, "qAssistantConversationsOf",
        "select * from t:${ATBL.conversations} where c:${PF.userId} = :${PF.userId} and c:${ASST.client} = :${ASST.client} " +
            "order by c:${PF.updatedAt} desc",
        mapOf(PF.userId to userId, ASST.client to client),
    )

    fun insertConversation(cxt: KdrCxt, row: Map<String, Any?>) = insert(cxt, ATBL.conversations, row)

    /** Marks [turnId] the turn running in the conversation, if none is: false when one already is. */
    fun claimConversation(cxt: KdrCxt, conversationId: String, turnId: String): Boolean = update(
        cxt, ATBL.conversations, "uAssistantConversationClaim", "c:${ATBL.runningTurnId} = :${ATBL.runningTurnId}",
        "c:${ACNV.conversationId} = :${ACNV.conversationId} and c:${ATBL.runningTurnId} is null",
        mapOf(ACNV.conversationId to conversationId, ATBL.runningTurnId to turnId),
    ) > 0

    /** Marks the conversation read-only: it cannot go on. */
    fun markReadOnly(cxt: KdrCxt, conversationId: String) {
        update(
            cxt, ATBL.conversations, "uAssistantConversationReadOnly", "c:${ACNV.readOnly} = :${ACNV.readOnly}",
            "c:${ACNV.conversationId} = :${ACNV.conversationId}", mapOf(ACNV.conversationId to conversationId, ACNV.readOnly to true),
        )
    }

    /** Removes a conversation this request just made, whose first turn could not be recorded: it holds nothing. */
    fun deleteNewConversation(cxt: KdrCxt, conversationId: String) {
        execute(cxt, ATBL.conversations, "dAssistantConversationNew", "delete from t:${ATBL.conversations} where c:${ACNV.conversationId} = :${ACNV.conversationId}", conversationId)
    }

    /**
     * Deletes a conversation its administrator asked to (issue #1755), which the caller has claimed so that no turn starts
     * in it meanwhile: its transcript, and the conversation with its title, which is the start of their first message.
     * Its turns stay, as the record the month's allowance and the hour's limit are reckoned from, with what the
     * administrator wrote in each blanked. The turns' text first: a delete stopped part way leaves the conversation, to
     * be deleted again, rather than words with no conversation left to delete them from.
     */
    fun deleteConversation(cxt: KdrCxt, conversationId: String) {
        update(
            cxt, ATBL.turns, "uAssistantTurnsForget", "c:${ACNV.text} = ''",
            "c:${ACNV.conversationId} = :${ACNV.conversationId}", mapOf(ACNV.conversationId to conversationId),
        )
        execute(cxt, ATBL.messages, "dAssistantMessagesOf", "delete from t:${ATBL.messages} where c:${ACNV.conversationId} = :${ACNV.conversationId}", conversationId)
        execute(cxt, ATBL.conversations, "dAssistantConversation", "delete from t:${ATBL.conversations} where c:${ACNV.conversationId} = :${ACNV.conversationId}", conversationId)
    }

    /** Clears [turnId] as the conversation's running turn, if it still is. */
    fun releaseConversation(cxt: KdrCxt, conversationId: String, turnId: String) {
        update(
            cxt, ATBL.conversations, "uAssistantConversationRelease", "c:${ATBL.runningTurnId} = null",
            "c:${ACNV.conversationId} = :${ACNV.conversationId} and c:${ATBL.runningTurnId} = :${ATBL.runningTurnId}",
            mapOf(ACNV.conversationId to conversationId, ATBL.runningTurnId to turnId),
        )
    }

    // --- Messages ---

    /** The conversation's messages after [afterSeq], in order, as stored rows. */
    fun messages(cxt: KdrCxt, conversationId: String, afterSeq: Long = 0): List<Map<String, Any?>> = query(
        cxt, ATBL.messages, "qAssistantMessagesAfter",
        "select * from t:${ATBL.messages} where c:${ACNV.conversationId} = :${ACNV.conversationId} and c:${ACNV.seq} > :${ACNV.seq} " +
            "order by c:${ACNV.seq}",
        mapOf(ACNV.conversationId to conversationId, ACNV.seq to afterSeq),
    )

    /** A stored row's message: its role and content blocks. */
    fun messageOf(row: Map<String, Any?>): Map<String, Any?> = row[ATBL.message].toJsonMapOrEmpty()

    /** Appends [message] at [seq]. A second writer at the same place fails on the key rather than overwriting. */
    fun appendMessage(cxt: KdrCxt, conversationId: String, seq: Long, turnId: String, message: Map<String, Any?>) = insert(
        cxt, ATBL.messages,
        mapOf(ACNV.conversationId to conversationId, ACNV.seq to seq, ACNV.turnId to turnId, ATBL.message to message),
    )

    // --- Turns ---

    fun turn(cxt: KdrCxt, turnId: String): Map<String, Any?>? = queryOne(cxt, ATBL.turns, mapOf(ACNV.turnId to turnId))

    /** The conversation's turns, oldest first. */
    fun turnsOf(cxt: KdrCxt, conversationId: String): List<Map<String, Any?>> = query(
        cxt, ATBL.turns, "qAssistantTurnsOf",
        "select * from t:${ATBL.turns} where c:${ACNV.conversationId} = :${ACNV.conversationId} order by c:${PF.createdAt}",
        mapOf(ACNV.conversationId to conversationId),
    )

    /** The turn [userId] started for the page's message [clientMessageId], if one was. */
    fun turnForMessage(cxt: KdrCxt, userId: Long, clientMessageId: String): Map<String, Any?>? = query(
        cxt, ATBL.turns, "qAssistantTurnForMessage",
        "select * from t:${ATBL.turns} where c:${PF.userId} = :${PF.userId} and c:${ACNV.clientMessageId} = :${ACNV.clientMessageId}",
        mapOf(PF.userId to userId, ACNV.clientMessageId to clientMessageId),
    ).firstOrNull()

    /** How many turns [userId] has started since [since]. */
    fun turnsStartedSince(cxt: KdrCxt, userId: Long, since: Instant): Int = query(
        cxt, ATBL.turns, "qAssistantTurnsSince",
        "select * from t:${ATBL.turns} where c:${PF.userId} = :${PF.userId} and c:${PF.createdAt} >= :${PF.createdAt}",
        mapOf(PF.userId to userId, PF.createdAt to since),
    ).size

    /**
     * How many turns are running in [client] at [now]: a turn whose lease lapsed -- its node stopped -- is not, though
     * it is marked interrupted only when its conversation is next looked at.
     */
    fun runningTurns(cxt: KdrCxt, client: String, now: Instant): Int = query(
        cxt, ATBL.turns, "qAssistantTurnsRunning",
        "select * from t:${ATBL.turns} where c:${ASST.client} = :${ASST.client} and c:${ACNV.status} = :${ACNV.status} " +
            "and c:${ATBL.leaseUntil} >= :${ATBL.leaseUntil}",
        mapOf(ASST.client to client, ACNV.status to ACNV.statusRunning, ATBL.leaseUntil to now),
    ).size

    fun insertTurn(cxt: KdrCxt, row: Map<String, Any?>) = insert(cxt, ATBL.turns, row)

    /**
     * Records a running turn's [progress] -- its steps, its use, the model that last answered -- and renews its lease to
     * [leaseUntil]. False when the turn is no longer running (its lease lapsed and it was marked interrupted): its
     * worker then stops, and writes nothing more.
     */
    fun recordProgress(cxt: KdrCxt, turnId: String, progress: TurnProgress, leaseUntil: Instant): Boolean = update(
        cxt, ATBL.turns, "uAssistantTurnProgress",
        "c:${ACNV.steps} = :${ACNV.steps}, c:${ASST.inputTokens} = :${ASST.inputTokens}, " +
            "c:${ASST.cacheWriteTokens} = :${ASST.cacheWriteTokens}, c:${ASST.cacheReadTokens} = :${ASST.cacheReadTokens}, " +
            "c:${ASST.outputTokens} = :${ASST.outputTokens}, c:${ACNV.weighted} = :${ACNV.weighted}, c:${ASST.model} = :${ASST.model}, " +
            "c:${ACNV.stopReason} = :${ACNV.stopReason}, c:${ATBL.leaseUntil} = :${ATBL.leaseUntil}",
        "c:${ACNV.turnId} = :${ACNV.turnId} and c:${ACNV.status} = :${ACNV.status}",
        progress.toRow() + mapOf(ACNV.turnId to turnId, ACNV.status to ACNV.statusRunning, ATBL.leaseUntil to leaseUntil),
    ) > 0

    /** Ends a running turn with [status] and [note]. False when it had already ended. */
    fun endTurn(cxt: KdrCxt, turnId: String, status: String, note: String?, at: Instant): Boolean = update(
        cxt, ATBL.turns, "uAssistantTurnEnd",
        "c:${ACNV.status} = :${ACNV.status}, c:${ACNV.note} = :${ACNV.note}, c:${ACNV.endedAt} = :${ACNV.endedAt}",
        "c:${ACNV.turnId} = :${ACNV.turnId} and c:${ACNV.status} = '${ACNV.statusRunning}'",
        mapOf(ACNV.turnId to turnId, ACNV.status to status, ACNV.note to note, ACNV.endedAt to at),
    ) > 0

    /** Asks a running turn to stop. False when it is not running. */
    fun requestCancel(cxt: KdrCxt, turnId: String): Boolean = update(
        cxt, ATBL.turns, "uAssistantTurnCancel", "c:${ATBL.cancelRequested} = :${ATBL.cancelRequested}",
        "c:${ACNV.turnId} = :${ACNV.turnId} and c:${ACNV.status} = :${ACNV.status}",
        mapOf(ACNV.turnId to turnId, ACNV.status to ACNV.statusRunning, ATBL.cancelRequested to true),
    ) > 0

    /**
     * Marks a running turn interrupted when its lease lapsed before [now]: the node running it stopped vouching for it.
     * False when it is not running, or its lease has not lapsed -- so only one caller ever recovers a turn.
     */
    fun interruptLapsed(cxt: KdrCxt, turnId: String, note: String, now: Instant): Boolean = update(
        cxt, ATBL.turns, "uAssistantTurnInterrupt",
        "c:${ACNV.status} = '${ACNV.statusInterrupted}', c:${ACNV.note} = :${ACNV.note}, c:${ACNV.endedAt} = :${ACNV.endedAt}",
        "c:${ACNV.turnId} = :${ACNV.turnId} and c:${ACNV.status} = '${ACNV.statusRunning}' and c:${ATBL.leaseUntil} < :${ATBL.leaseUntil}",
        mapOf(ACNV.turnId to turnId, ACNV.note to note, ACNV.endedAt to now, ATBL.leaseUntil to now),
    ) > 0

    // --- Plumbing ---

    @KdrPrivate
    fun sql(cxt: KdrCxt): SqlCxt = SqlTopicService.mkSqlCxt(cxt, ATBL.topic)

    @KdrPrivate
    fun queryOne(cxt: KdrCxt, tableName: String, key: Map<String, Any?>): Map<String, Any?>? {
        val sqlCxt = sql(cxt)
        val stmt = SqlTopicUtil.mkTableSelectStmt(sqlCxt, ATBL.table(cxt, tableName))
        var row: Map<String, Any?>? = null
        sqlCxt.sqlDb.withSession(cxt) { row = sqlCxt.sqlDb.queryOneStatement(cxt, stmt, key) }
        return row
    }

    @KdrPrivate
    fun query(cxt: KdrCxt, tableName: String, name: String, sql: String, bind: Map<String, Any?>): List<Map<String, Any?>> {
        val sqlCxt = sql(cxt)
        val stmt = SqlStmtUtil.prepareSql(sqlCxt, name, ATBL.table(cxt, tableName).columns, sql)
        var rows: List<Map<String, Any?>> = emptyList()
        sqlCxt.sqlDb.withSession(cxt) { rows = sqlCxt.sqlDb.queryStatement(cxt, stmt, bind) }
        return rows
    }

    @KdrPrivate
    fun insert(cxt: KdrCxt, tableName: String, values: Map<String, Any?>) {
        val sqlCxt = sql(cxt)
        val table: KdrTable = ATBL.table(cxt, tableName)
        val row = LinkedHashMap(values)
        SqlTopicUtil.prepForStdExecute(cxt, table, row)
        sqlCxt.sqlDb.withSession(cxt) { sqlCxt.sqlDb.executeStatement(cxt, SqlTopicUtil.mkTableInsertStmt(sqlCxt, table), row) }
    }

    /** One statement over [tableName] naming the conversation [conversationId], a delete. */
    @KdrPrivate
    fun execute(cxt: KdrCxt, tableName: String, name: String, sql: String, conversationId: String) {
        val sqlCxt = sql(cxt)
        val stmt = SqlStmtUtil.prepareSql(sqlCxt, name, ATBL.table(cxt, tableName).columns, sql)
        sqlCxt.sqlDb.withSession(cxt) { sqlCxt.sqlDb.executeStatement(cxt, stmt, mapOf(ACNV.conversationId to conversationId)) }
    }

    /** One conditional update; the rows it changed. */
    @KdrPrivate
    fun update(cxt: KdrCxt, tableName: String, name: String, set: String, where: String, bind: Map<String, Any?>): Int {
        val sqlCxt = sql(cxt)
        val table = ATBL.table(cxt, tableName)
        val stmt: SqlStatement = SqlTopicUtil.mkPartialUpdateStmt(sqlCxt, table, name, set, where)
        val values = LinkedHashMap(bind)
        SqlTopicUtil.prepForStdUpdate(cxt, table, values, prior = null)
        var changed = 0
        sqlCxt.sqlDb.withSession(cxt) { changed = sqlCxt.sqlDb.executeStatement(cxt, stmt, values) }
        return changed
    }
}

/** A turn's running totals: its model calls, its use by kind and weighted, and the model and stop reason last seen. */
class TurnProgress {
    var steps: Long = 0
    var inputTokens: Long = 0
    var cacheWriteTokens: Long = 0
    var cacheReadTokens: Long = 0
    var outputTokens: Long = 0
    var weighted: Long = 0
    var model: String? = null
    var stopReason: String? = null

    /** Adds one response's [usage], [weightedTokens] of it, from [answeredBy]. */
    fun add(usage: ModelUsage, weightedTokens: Long, answeredBy: String, stop: String?) {
        steps += 1
        inputTokens += usage.inputTokens
        cacheWriteTokens += usage.cacheWriteTokens
        cacheReadTokens += usage.cacheReadTokens
        outputTokens += usage.outputTokens
        weighted += weightedTokens
        model = answeredBy
        stopReason = stop
    }

    fun toRow(): Map<String, Any?> = mapOf(
        ACNV.steps to steps, ASST.inputTokens to inputTokens, ASST.cacheWriteTokens to cacheWriteTokens,
        ASST.cacheReadTokens to cacheReadTokens, ASST.outputTokens to outputTokens, ACNV.weighted to weighted,
        ASST.model to model, ACNV.stopReason to stopReason,
    )

    companion object {
        /** A stored turn's totals, to carry on from. */
        fun of(row: Map<String, Any?>): TurnProgress = TurnProgress().apply {
            steps = row[ACNV.steps].toOptLong() ?: 0
            inputTokens = row[ASST.inputTokens].toOptLong() ?: 0
            cacheWriteTokens = row[ASST.cacheWriteTokens].toOptLong() ?: 0
            cacheReadTokens = row[ASST.cacheReadTokens].toOptLong() ?: 0
            outputTokens = row[ASST.outputTokens].toOptLong() ?: 0
            weighted = row[ACNV.weighted].toOptLong() ?: 0
        }
    }
}
