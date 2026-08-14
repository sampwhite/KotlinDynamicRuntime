package com.dynamicruntime.common.gedra

import com.dynamicruntime.common.context.KdrCxt
import com.dynamicruntime.common.schema.SCT
import com.dynamicruntime.common.sql.KdrTable
import com.dynamicruntime.common.sql.PF
import com.dynamicruntime.common.sql.tableModule

/** The SQL topic the gedra **data** tables belong to; config storage will be a topic of its own. */
const val gedraDataTopic = "gedraData"

/** Gedra data table names. Each name matches its value. */
@Suppress("ConstPropertyName")
object GDT {
    const val gedraDataTran = "GedraDataTran"
    const val gedraData = "GedraData"
}

/** Column names for the gedra data tables, and the keys inside [GD.data]. */
@Suppress("ConstPropertyName")
object GD {
    /** The gedra's id in its text form -- the primary key of both tables. */
    const val gedraId = "gedraId"

    /**
     * Which [GedraDataType] this row holds, by enum name (`formDoc`), not the id's two-letter abbreviation.
     *
     * A denormalization of the id's second segment, and it exists purely to be a **predicate**: "every form
     * document this user owns" otherwise has to be `gedraId like 'gd.fd.%'`, which reads as a trick, welds the
     * query to the id format, and cannot use an index alongside the ownership columns. The id stays the
     * authority -- see `GedraDataRow.extract`, which reads the kind from the id and never from here, so a
     * column that somehow disagreed could not produce a mislabeled row.
     */
    const val gedraKind = "gedraKind"

    /**
     * Everything the gedra holds, as a map.
     *
     * Only [entries] is defined today, and the map rather than a bare column is the whole point: ACL grants,
     * relationships between gedras, and an organization-style id for a gedra scoped below the client are all
     * expected here, and each of them arriving as a key costs no migration. It is the same push-down the entry
     * envelope makes one level up -- see `GE.data`.
     */
    const val data = "data"

    /** Under [data]: the gedra's entries, each an instance of the trait its `traitId` names. */
    const val entries = "entries"
}

/**
 * The gedra data tables (issue #310), in two tiers.
 *
 * [GDT.gedraDataTran] is the transaction root and [GDT.gedraData] holds the content, both keyed by the same
 * [GD.gedraId]. The split is not bookkeeping for its own sake: a topic's transaction is taken by locking one
 * row on one table (see `SqlTopicTranProvider`), and the number of tables under this root is expected to
 * grow -- along with transactions that reach past the database entirely, into a file store. A lock that lives
 * on the content table could not span those.
 *
 * The root carries no payload of its own yet. An "outbox" state column is the likely first one, which is the
 * other half of why it is a table rather than a flag: a transaction that has to be completed elsewhere needs
 * somewhere to record that it is outstanding.
 *
 * ### Ownership, and why the content table carries one column the root does not
 *
 * Both are user-owned, so both carry [PF.userId] and [PF.client]. Only [GDT.gedraData] also takes
 * `forOrg`, and that is a correctness requirement rather than a nicety: an administrator who has a primary
 * organization gets a `ReadScope` constraining it, and `SqlScopeUtil` **throws** on a table that cannot
 * express a constrained dimension rather than quietly widening the answer. Without the column, the
 * client-scoped listing would fail outright for exactly those administrators. The root needs none of it,
 * because it is only ever addressed by primary key and never scanned by scope.
 *
 * That asymmetry is the rule `ReadScope` already states from the other side: content gets a real organization
 * column and filters in SQL, while a *user's* organization lives in a JSON blob and cannot. A form document is
 * content.
 */
fun gedraDataTables(cxt: KdrCxt): List<KdrTable> = tableModule(cxt, namespace = "gedra", topic = gedraDataTopic) {
    table(GDT.gedraDataTran, "Transaction root for one gedra: what a write takes its lock on.") {
        column(GD.gedraId, "Id of the gedra this transaction row governs.", required = true)
        primaryKey(GD.gedraId)
        forUsers()
        withTransactions()
    }
    table(GDT.gedraData, "The content of one gedra: its entries, and whatever else it comes to hold.") {
        column(GD.gedraId, "Id of the gedra.", required = true)
        column(GD.gedraKind, "Which kind of gedra this is, by name (e.g. 'formDoc').", required = true)
        column(GD.data, "Everything the gedra holds; today, its entries.") { type = SCT.kObject }
        primaryKey(GD.gedraId)
        forUsers()
        forOrg()
        // The two shapes a scoped listing takes, each with the kind that every such listing also constrains:
        // an ordinary user reads their own rows, an administrator reads their client's.
        index(PF.userId, GD.gedraKind)
        index(PF.client, GD.gedraKind)
    }
}
