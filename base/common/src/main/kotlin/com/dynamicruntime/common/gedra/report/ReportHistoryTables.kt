package com.dynamicruntime.common.gedra.report

import com.dynamicruntime.common.context.KdrCxt
import com.dynamicruntime.common.schema.SCT
import com.dynamicruntime.common.sql.KdrTable
import com.dynamicruntime.common.sql.PF
import com.dynamicruntime.common.sql.tableModule

/**
 * The report-history topic (issue #1034): one append-only table, never locked -- a snapshot is written once and read
 * back in time order -- so it is a topic of its own rather than a lodger in the gedra data's or the jobs' topic.
 */
const val reportHistoryTopic = "reportHistory"

/** The report-snapshot table's name and column names (issue #1034). Each name matches its value. */
@Suppress("ConstPropertyName")
object RHT {
    const val reportSnapshot = "ReportSnapshot"

    /** The snapshot's place in the order snapshots were written: the primary key, and the second half of a cursor. */
    const val snapshotSeq = "snapshotSeq"

    /** When the run was taken, on the instance clock -- or the moment a simulation backdates it to. */
    const val takenAt = "takenAt"

    /** What took it: a [ReportSnapshotTrigger] name. */
    const val trigger = "trigger"

    /** The job launch that took it, so a resumed launch finds it; null for one taken by hand. */
    const val launchName = "launchName"

    /** The run's query id -- the report as bound -- so a snapshot taken under an older definition is told apart. */
    const val queryId = "queryId"

    const val scanned = "scanned"
    const val excluded = "excluded"
    const val groupCount = "groupCount"

    /** The run itself: its grouping, its columns as bound, and its rows. */
    const val data = "data"
}

/**
 * The report-history table (issue #1034): a row per snapshot of a report's grouped run over a client's forms, holding
 * the run's rows as JSON in the shape the run endpoint pages them. One row per snapshot rather than per group: the
 * chart reads a whole snapshot at a time, and a group's key is several values of several kinds, which a key column
 * would only have to serialize again. Append-only: written once, pruned by count, never updated.
 */
fun reportHistoryTables(cxt: KdrCxt): List<KdrTable> =
    tableModule(cxt, namespace = "reportHistory", topic = reportHistoryTopic) {
        table(RHT.reportSnapshot, "One snapshot of a report's grouped run over a client's forms (issue #1034).") {
            column(RHT.snapshotSeq, "The snapshot's place in the order snapshots were written.", required = true, autoIncrement = true) {
                type = SCT.integer
            }
            column(RRUN.reportId, "The report snapshotted.", required = true)
            column(RHT.takenAt, "When the run was taken.", required = true) { dateTime() }
            column(RHT.trigger, "What took it: scheduled, or manual.", required = true)
            column(RHT.launchName, "The job launch that took it; absent for a manual one.")
            column(RHT.queryId, "The run's query id: the report as it was bound.")
            column(RHT.scanned, "How many of the client's forms the run read.", required = true) { type = SCT.integer }
            column(RHT.excluded, "How many of them were left out for a missing value.", required = true) { type = SCT.integer }
            column(RHT.groupCount, "How many groups the run had, before any cut.", required = true) { type = SCT.integer }
            column(RHT.data, "The run: groupBy, columns as bound, and rows.", required = true) { type = SCT.kObject }
            forClient()
            primaryKey(RHT.snapshotSeq)
            index(PF.client, RRUN.reportId, RHT.takenAt)
            index(PF.client, RRUN.reportId, RHT.launchName)
        }
    }
