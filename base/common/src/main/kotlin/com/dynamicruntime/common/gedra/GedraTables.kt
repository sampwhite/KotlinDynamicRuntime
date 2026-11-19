package com.dynamicruntime.common.gedra

import com.dynamicruntime.common.context.KdrCxt
import com.dynamicruntime.common.schema.SCT
import com.dynamicruntime.common.sql.KdrTable
import com.dynamicruntime.common.sql.PF
import com.dynamicruntime.common.sql.tableModule

/** The SQL topic the gedra **data** tables belong to; config storage is a topic of its own, [gedraConfigTopic]. */
const val gedraDataTopic = "gedraData"

/** Gedra data table names. Each name matches its value. */
@Suppress("ConstPropertyName")
object GDT {
    const val gedraDataTran = "GedraDataTran"
    const val gedraData = "GedraData"

    /**
     * The companion **state** table (issue #596): one row per gedra holding its state entries, a sibling to
     * [gedraData] under the same [gedraDataTran] lock. State changes in batches and stays small where data can
     * grow large and is pruned, so a separate row keeps a state change off the data row and lets state have its
     * own always-resident cache (issue #598).
     */
    const val gedraDataStates = "GedraDataStates"

    /**
     * An upload under way (issue #1837): one row per data file still taking its chunks, under the same
     * [gedraDataTran] lock as the file's record, and deleted once the upload finishes or is aborted. Never cached --
     * every chunk reads and writes it under the lock.
     */
    const val gedraFileUploads = "GedraFileUploads"

    /**
     * Which forms use which data files (issue #1838): one row per file field of a form's entries that names a file,
     * written by `DataFileLinkHook` in the form's own write. It answers which forms use a file, refuses removing a file
     * a live form uses, and lets a caller read a file through a form they can read. A form soft-deleted keeps its rows;
     * only a live form's count. Not cached: read under a lock, or once per question asked of it.
     */
    const val gedraFileLinks = "GedraFileLinks"
}

/** Column names of [GDT.gedraFileLinks]. Each name matches its value. */
@Suppress("ConstPropertyName")
object GDL {
    /** The data file a form's field names. */
    const val fileId = "fileId"

    /** The form whose field names it: its gedra id. */
    const val holderId = "holderId"

    /** The trait of the entry holding the field. */
    const val traitId = "traitId"

    /** The entry holding the field: its entry id. */
    const val entryKey = "entryKey"

    /** Where in the entry's data the field is, `photos[2].file` style (`DataFileUse.path`). */
    const val fieldPath = "fieldPath"
}

/** Column names of [GDT.gedraFileUploads], and the keys inside its [progress]. */
@Suppress("ConstPropertyName")
object GDU {
    /** Where the chunks are staged in the file store, until the upload finishes and the file is named by its hash. */
    const val stagingKey = "stagingKey"

    /** The file store's token for the staged upload. */
    const val token = "token"

    /** How many bytes each chunk is, all but the last. */
    const val chunkSize = "chunkSize"

    /** How many bytes the file said it would be, which the chunks must add up to. */
    const val declaredSize = "declaredSize"

    /** Where the next chunk begins: the bytes taken so far. */
    const val nextOffset = "nextOffset"

    /** When the upload is no longer taken. */
    const val expiresAt = "expiresAt"

    /** A JSON map: the [parts] taken, and the running hash's saved state, [hashState]. */
    const val progress = "progress"

    /** Under [progress]: each chunk taken, in order, as the file store took it, with its own SHA-256. */
    const val parts = "parts"

    /** Under [progress]: the SHA-256 of every byte taken so far, as `Sha256.toState` saves it. */
    const val hashState = "hashState"

    /**
     * Under [progress]: true once the file store has put the chunks together, so a finish that failed after it takes up
     * from the file rather than asking the store to complete an upload it already has.
     */
    const val completed = "completed"

    /**
     * Under [progress]: when the request finishing the upload last claimed or renewed its claim (issue #1840), so a
     * second -- the last chunk sent again while the first still works -- waits rather than finishing it twice. A claim
     * not renewed within `DFILE.finishLeaseMs` was left by a request that stopped, and may be taken over.
     */
    const val finishingAt = "finishingAt"

    /** Under [progress]: which request holds the claim ([finishingAt]), so only it renews or gives it up. */
    const val finishingBy = "finishingBy"

    // Each part's keys.
    const val number = "number"
    const val size = "size"
    const val tag = "tag"
    const val sha256 = "sha256"
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

    /**
     * Under [data]: the `WfRef` text of the creation workflow that made this gedra, when one did (issue
     * #533). Written once with the row and never rewritten; absent for a gedra created any other way. The
     * first key beside [entries], and the push-down working as designed: no column, no migration. Spelled
     * the same as its wire name in `GDF`, as [entries] is.
     */
    const val creationWorkflowId = "creationWorkflowId"
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
 *
 * ### The three ownership columns are not equally settled
 *
 * [PF.client] is stamped once and is effectively **read-only for the life of the row**. It is the seam the
 * whole stack can be split along, it is baked into the [GD.gedraId] besides, and moving data between clients
 * is an export and an import that mints new ids rather than an update. [PF.userId] is the same kind of fact.
 *
 * [PF.org] is **not**. An organization is a partition *inside* a client, and a gedra can legitimately be
 * reassigned from one to another — or acquire one for the first time, which is what every row written before a
 * client adopted organizations does. So it is mutable state that happens to live in an ownership column, and
 * the one to be careful with: `SqlTopicUtil.prepForStdExecute` fills it put-if-absent, so a "write" meaning to
 * *change* it has to say so explicitly, exactly as one meaning to disable a row has to defend [PF.enabled]
 * against the standard stamp. Reads are unaffected either way -- a scope compares whatever the column now
 * holds.
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
        column(GD.data, "A JSON map: the entries, plus whatever keys later capabilities add with no migration.") {
            type = SCT.kObject
        }
        primaryKey(GD.gedraId)
        forUsers()
        forOrg()
        // The two shapes a scoped listing takes, each with the kind that every such listing also constrains:
        // an ordinary user reads their own rows, an administrator reads their client's.
        index(PF.userId, GD.gedraKind)
        index(PF.client, GD.gedraKind)
        // The in-memory cache (GedraDataCache) reloads by asking for the rows changed since it last looked,
        // which is a predicate on `updatedAt` run on every node. Without this index that is a full scan.
        index(PF.updatedAt)
    }
    table(GDT.gedraDataStates, "The state of one gedra: its state entries, written under the same lock as its data.") {
        // Keyed by the same [GD.gedraId] as the content row, so a state write takes the [GDT.gedraDataTran]
        // lock for that gedra and can share a transaction with the data write (issue #596). No `gedraKind`
        // column: the id carries the kind, and no scoped listing filters state by kind yet.
        column(GD.gedraId, "Id of the gedra whose state this is.", required = true)
        column(GD.data, "A JSON map: the state entries, keyed by each state trait's primary key.") {
            type = SCT.kObject
        }
        primaryKey(GD.gedraId)
        // Scoped like the content: an owner reads their own state, an administrator their client's.
        forUsers()
        forOrg()
        // The state cache (issue #598) lists by client and reloads on `updatedAt`, the same shape GedraData's
        // cache uses -- without these two, both are full scans.
        index(PF.client)
        index(PF.updatedAt)
    }
    table(GDT.gedraFileUploads, "An upload of a data file under way: where its chunks are, and how far it has come.") {
        // Keyed by the data file's [GD.gedraId], so a chunk takes the file's [GDT.gedraDataTran] lock (issue #1837).
        column(GD.gedraId, "Id of the data file being uploaded.", required = true)
        column(GDU.stagingKey, "Where the chunks are staged in the file store.", required = true)
        column(GDU.token, "The file store's token for the staged upload.", required = true)
        column(GDU.chunkSize, "How many bytes each chunk is, all but the last.", required = true) { type = SCT.integer }
        column(GDU.declaredSize, "How many bytes the file said it would be.", required = true) { type = SCT.integer }
        column(GDU.nextOffset, "Where the next chunk begins.", required = true) { type = SCT.integer }
        column(GDU.expiresAt, "When the upload is no longer taken.", required = true) { dateTime() }
        column(GDU.progress, "A JSON map: the chunks taken, and the running hash's state.") { type = SCT.kObject }
        primaryKey(GD.gedraId)
        forUsers()
        // The sweep (issue #1839) finds the uploads past their time.
        index(GDU.expiresAt)
    }
    table(GDT.gedraFileLinks, "Which forms' fields name which data files: one row per field naming a file.") {
        column(GDL.holderId, "Id of the form whose field names the file.", required = true)
        column(GDL.traitId, "The trait of the entry holding the field.", required = true)
        column(GDL.entryKey, "The id of the entry holding the field.", required = true)
        column(GDL.fieldPath, "Where in the entry's data the field is.", required = true)
        column(GDL.fileId, "Id of the data file the field names.", required = true)
        // One file per field. Led by the form, so a form's save reads its own links by the key's first column.
        primaryKey(GDL.holderId, GDL.traitId, GDL.entryKey, GDL.fieldPath)
        forClient()
        // Which forms use a file: a removal's refusal, a read through a form, and `/files/usedBy`.
        index(GDL.fileId)
    }
}
