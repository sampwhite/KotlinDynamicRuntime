package com.dynamicruntime.common.gedra

import com.dynamicruntime.common.context.KdrCxt
import com.dynamicruntime.common.schema.SCT
import com.dynamicruntime.common.sql.KdrTable
import com.dynamicruntime.common.sql.PF
import com.dynamicruntime.common.sql.tableModule

/**
 * The SQL topic the gedra **config** tables belong to (issue #612), apart from [gedraDataTopic]: a config write
 * must never contend with a data write for a lock, and the two families will part ways further -- a file store
 * behind config, different retention -- so they do not share a transaction root.
 */
const val gedraConfigTopic = "gedraConfig"

/** Gedra config table names. Each name matches its value. */
@Suppress("ConstPropertyName")
object GCT {
    const val gedraConfigTran = "GedraConfigTran"
    const val gedraConfig = "GedraConfig"
}

/** Column names for the gedra config tables. */
@Suppress("ConstPropertyName")
object GC {
    /** The **versioned** id (`gc.cd.acme.main~3`) -- the primary key of the content row. */
    const val gedraId = "gedraId"

    /**
     * The **revision class** (`gc.cd.acme.main`): the id with no revision, which every revision of one config
     * shares. The primary key of the root, and on the content row a denormalized **predicate** -- "every
     * revision of this config" is otherwise `gedraId like 'gc.cd.acme.main~%'`, which reads as a trick, welds
     * the query to the id format, and cannot use an index. The same reason [GD.gedraKind] exists. The id stays
     * the authority: a reader derives the class from the id (`GedraId.revisionClass`), never from here.
     *
     * This and [version] are **derivations of [gedraId], not independent facts.** A writer (#613) fills them
     * from the id -- `GedraId.revisionClass()` for this, `GedraId.revision` for [version] -- never from separate
     * inputs, so the row cannot store a class or version that disagrees with its own id; the schema does not
     * enforce the agreement, the writer keeping to the id as the source of truth does.
     */
    const val configId = "configId"

    /**
     * The revision number, the same one the id's suffix carries, as a column so "latest" is an order-by.
     * Derived from the id (`GedraId.revision`), never accepted on its own -- see [configId] on why.
     */
    const val version = "version"

    /**
     * When this revision was **published**, or null while it is the editable latest. One column is both the
     * flag (#611's "published") and the audit fact of *when* -- the accounting a stored config is for.
     */
    const val publishedAt = "publishedAt"

    /** Everything the revision holds, as a map: its config traits (#613), plus whatever later keys arrive. */
    const val data = "data"

    /**
     * A key **inside** the [data] map (not a column): the namespace the config's generated types live in (issue
     * #614). A config is stored as already-qualified type names, so the namespace is otherwise unrecoverable for
     * a config that declares no types -- and the boot loader must reassemble the config faithfully, including
     * the namespace ownership its collector claims. Written by the config write path from `GedraConfig.namespace`,
     * read back by [GedraConfigRow].
     */
    const val namespace = "namespace"
}

/**
 * The gedra config tables (issue #612), in two tiers like the data tables ([gedraDataTables]) and for the same
 * reason: a topic's transaction is taken by locking one row on one table, and the tables under a root are
 * expected to grow.
 *
 * ### The root is keyed by the revision class, not the version
 *
 * This is the one place the config tables deliberately differ from the data tables, which key both tiers by
 * the same id. #611's two rules -- *edit the latest revision until it is published* and *the next edit after
 * publishing creates a new row and version* -- are statements about a config's whole revision class, and they
 * race across nodes unless something serialises writes to that class: two nodes could each mint `~4`. Locking
 * `gc.cd.acme.main` on [GCT.gedraConfigTran] is that something. The content row keeps the versioned key, so a
 * revision is addressed exactly and a class is a predicate ([GC.configId]).
 *
 * ### Owned by a client, and nothing narrower
 *
 * `forClient()` only -- not `forUsers` / `forOrg` as the data tables take. A configuration belongs to a client,
 * never to a person or an organization inside it, and the choice is load-bearing rather than cosmetic:
 * `SqlScopeUtil` **throws** on a table that cannot express a constrained scope dimension rather than quietly
 * widening the answer, so a client-scoped read must find its column here and a user-scoped one must not.
 *
 * ### Nothing reads or writes these yet
 *
 * Declaration and storage only: the tables exist, and that is all. The write path, its diff-before-stamp rule
 * and the split into traits are #613; loading at boot is #614; the two-revision cache is #615.
 */
fun gedraConfigTables(cxt: KdrCxt): List<KdrTable> =
    tableModule(cxt, namespace = "gedraConfig", topic = gedraConfigTopic) {
        table(
            GCT.gedraConfigTran,
            "Transaction root for one config across all of its revisions: what a write takes its lock on.",
        ) {
            column(GC.configId, "Revision class of the config this row governs -- its id with no revision.", required = true)
            primaryKey(GC.configId)
            forClient()
            withTransactions()
        }
        table(GCT.gedraConfig, "One revision of one config: its traits as a map, its version, and when it was published.") {
            column(GC.gedraId, "The versioned id of this revision.", required = true)
            column(
                GC.configId,
                "The revision class -- the id with no revision -- so every revision of one config is a predicate.",
                required = true,
            )
            column(GC.version, "The revision number, also carried in the id's suffix.", required = true) {
                type = SCT.integer
            }
            column(GC.publishedAt, "When this revision was published; null while it is the editable latest.") {
                dateTime()
            }
            column(GC.data, "A JSON map: the config's traits, plus whatever keys later capabilities add.") {
                type = SCT.kObject
            }
            primaryKey(GC.gedraId)
            forClient()
            // The shape every reader of a class takes -- the latest revision, the latest published one (#615),
            // the row an edit lands on (#613) -- is "this class, ordered by version".
            index(GC.configId, GC.version)
            // The in-memory cache reloads by asking for the rows changed since it last looked (#615), a
            // predicate on `updatedAt` run on every node; without this index that is a full scan.
            index(PF.updatedAt)
        }
    }
