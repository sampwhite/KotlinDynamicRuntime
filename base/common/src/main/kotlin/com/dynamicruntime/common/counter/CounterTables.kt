package com.dynamicruntime.common.counter

import com.dynamicruntime.common.context.KdrCxt
import com.dynamicruntime.common.gedra.gedraDataTopic
import com.dynamicruntime.common.schema.SCT
import com.dynamicruntime.common.sql.KdrTable
import com.dynamicruntime.common.sql.PF
import com.dynamicruntime.common.sql.tableModule

/**
 * The SQL topic of the counts themselves (issue #1849): a topic of its own, so issuing an id takes one short lock on its
 * counter's row and never waits on a form's. The claims are in [gedraDataTopic], since one is written in the
 * transaction of the form that stores its id.
 */
const val formCounterTopic = "formCounter"

/** Table and column names for counters (issue #1849). Each name matches its value. */
@Suppress("ConstPropertyName")
object CNT {
    /** One row per client, counter, scope, and period: the last count issued. */
    const val formCounters = "FormCounters"

    /** One row per id a form stores: which form holds it, so it is never stored twice. */
    const val formCounterClaims = "FormCounterClaims"

    /** Which counter a row is for, in its client (the row's `client`). */
    const val counterId = "counterId"

    /**
     * What the counter is kept apart by within its client: [none] for now. The place for counters kept per store,
     * filled from the caller, without changing the table (see `deferred-work.md`).
     */
    const val scopeKey = "scopeKey"

    /** The period the count runs in: [none] for a counter that never resets, and the client's day for a daily one. */
    const val periodKey = "periodKey"

    /** On [formCounters]: the last count issued in the period, so the next is one more. */
    const val lastCount = "lastCount"

    /** On [formCounterClaims]: the count the stored id was issued with. */
    const val issuedCount = "issuedCount"

    /** On [formCounterClaims]: the id as the form holds it. */
    const val counterValue = "counterValue"

    /** On [formCounterClaims]: the form that holds the id. */
    const val gedraId = "gedraId"

    /** On [formCounterClaims]: the trait whose entry holds the id. */
    const val traitId = "traitId"

    /** On [formCounterClaims]: the field of the entry's data that holds the id. */
    const val fieldName = "fieldName"

    /** A [scopeKey] or [periodKey] that is not one: a key column holds a value, and an empty one is not portable. */
    const val none = "-"

    /** The transaction an id is issued in. */
    const val tranIssue = "issueCounterId"
}

/** The table of counts (issue #1849), each row the lock its counter takes as it issues an id. */
fun counterTables(cxt: KdrCxt): List<KdrTable> = tableModule(cxt, namespace = "formCounter", topic = formCounterTopic) {
    table(CNT.formCounters, "The last count a counter issued, per client, scope, and period (issue #1849).") {
        column(CNT.counterId, "The counter, by its id in its client.", required = true)
        column(CNT.scopeKey, "What the counter is kept apart by within its client; '-' for none.", required = true)
        column(CNT.periodKey, "The period the count runs in: the client's day for a daily counter, '-' for one that never resets.", required = true)
        column(CNT.lastCount, "The last count issued in the period; absent before the first.") { type = SCT.integer }
        forClient()
        primaryKey(PF.client, CNT.counterId, CNT.scopeKey, CNT.periodKey)
        withTransactions()
    }
}

/** The table of claims (issue #1849): written in the transaction of the form that stores an id, so an id is stored once. */
fun counterClaimTables(cxt: KdrCxt): List<KdrTable> = tableModule(cxt, namespace = "formCounterClaims", topic = gedraDataTopic) {
    table(CNT.formCounterClaims, "Which form holds each id a counter issued (issue #1849).") {
        column(CNT.counterId, "The counter that issued the id.", required = true)
        column(CNT.scopeKey, "The scope it was issued in; '-' for none.", required = true)
        column(CNT.periodKey, "The period it was issued in; '-' for a counter that never resets.", required = true)
        column(CNT.issuedCount, "The count it was issued with.", required = true) { type = SCT.integer }
        column(CNT.counterValue, "The id, as the form holds it.", required = true)
        column(CNT.gedraId, "The form that holds it.", required = true)
        column(CNT.traitId, "The trait whose entry holds it.", required = true)
        column(CNT.fieldName, "The field of the entry's data that holds it.", required = true)
        forClient()
        primaryKey(PF.client, CNT.counterId, CNT.scopeKey, CNT.periodKey, CNT.issuedCount)
        index(CNT.gedraId)
    }
}
