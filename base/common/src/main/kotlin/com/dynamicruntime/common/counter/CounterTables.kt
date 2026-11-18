package com.dynamicruntime.common.counter

import com.dynamicruntime.common.context.KdrCxt
import com.dynamicruntime.common.schema.SCT
import com.dynamicruntime.common.sql.KdrTable
import com.dynamicruntime.common.sql.PF
import com.dynamicruntime.common.sql.tableModule

/**
 * The SQL topic of the counts themselves (issue #1849): a topic of its own, so issuing an id takes one short lock on its
 * counter's row and never waits on a form's. The claims of its ids are value claims, in the gedra data topic, since one
 * is written in the transaction of the form that stores its id (`ValueClaims`).
 */
const val formCounterTopic = "formCounter"

/** Table and column names for counters (issue #1849). Each name matches its value. */
@Suppress("ConstPropertyName")
object CNT {
    /** One row per client, counter, scope, and period: the last count issued. */
    const val formCounters = "FormCounters"

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

    /** A [scopeKey] or [periodKey] that is not one: a key column holds a value, and an empty one is not portable. */
    const val none = "-"

    /** What a counter's ids are claimed as, before its id: `counter:receipt` ([com.dynamicruntime.common.gedra.VCL.claimKind]). */
    const val claimKindPrefix = "counter:"

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
