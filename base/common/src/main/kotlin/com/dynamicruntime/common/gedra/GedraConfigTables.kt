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
    const val gedraConfigClientTran = "GedraConfigClientTran"
    const val gedraConfig = "GedraConfig"
    const val gedraConfigControl = "GedraConfigControl"
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
     * Whether the revision is **current** (issue #875): its config's latest, or its latest published -- the only two
     * a read can still want. The rest are history, and the reads that start from nothing (the boot load, the
     * config cache's first load, a client's listing) skip them. Not required: a row written before the flag existed
     * has none until the boot backfills it. See `ConfigCurrentRevisions`.
     */
    const val isCurrent = "isCurrent"

    // --- protection tier (#617), on the control table ---

    /** The environment a control row governs -- a client's tier is per-environment. */
    const val environment = "environment"

    /**
     * Whether this client, in this environment, consumes only its **published** configuration (issue #617). The
     * runtime state, toggled per client per environment (`ClientDef.staticConfig` is not a tier, issue #824).
     * Absent (no row) means the free tier -- the latest revision.
     */
    const val publishedOnly = "publishedOnly"

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
 * ### The root is keyed by the client, not the config
 *
 * This is the one place the config tables deliberately differ from the data tables, which key both tiers by
 * the same id. #611's two rules -- *edit the latest revision until it is published* and *the next edit after
 * publishing creates a new row and version* -- race across nodes unless something serializes writes: two nodes
 * could each mint `~4`. Locking the client's row on [GCT.gedraConfigClientTran] is that something.
 *
 * It locks the **client** rather than one config's revision class (issue #843) because the client is the unit
 * that has to be valid: a client's definition is spread across several configs -- its `clientDef` in one, the
 * traits its workflows collect in another -- and what they must agree on is the client's whole set. A stored
 * config depends on source code and on its own client's other configs, never on another client's, so the client
 * is exactly the set a write must be judged against. Holding its lock, a write sees every other config of the
 * client as it stands, and several configs (a bulk import's bundles for one client) can be written and judged
 * together, all or nothing. Config writes are rare and made by administrators, so serializing a client's configs
 * behind one row costs nothing that matters.
 *
 * The content row keeps the versioned key, so a revision is addressed exactly and a class is a predicate
 * ([GC.configId]).
 *
 * The root was `GedraConfigTran`, keyed by revision class, until #843. It was **renamed** rather than rekeyed
 * because table setup only adds what is missing: an existing database would have kept the old key, and the
 * schema-drift check would have refused the boot. Under the new name it is created fresh, and a database from
 * before keeps an unused `GedraConfigTran` of disposable lock rows, safe to drop.
 *
 * ### Owned by a client, and nothing narrower
 *
 * `forClient()` only -- not `forUsers` / `forOrg` as the data tables take. A configuration belongs to a client,
 * never to a person or an organization inside it, and the choice is load-bearing rather than cosmetic:
 * `SqlScopeUtil` **throws** on a table that cannot express a constrained scope dimension rather than quietly
 * widening the answer, so a client-scoped read must find its column here and a user-scoped one must not.
 */
fun gedraConfigTables(cxt: KdrCxt): List<KdrTable> =
    tableModule(cxt, namespace = "gedraConfig", topic = gedraConfigTopic) {
        table(
            GCT.gedraConfigClientTran,
            "Transaction root for one client's configuration, across all of its configs and their revisions: what a " +
                "config write takes its lock on.",
        ) {
            primaryKey(PF.client)
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
            column(GC.isCurrent, "Whether this is its config's latest or latest published revision, not history.") {
                type = SCT.boolean
            }
            primaryKey(GC.gedraId)
            forClient()
            // The shape every reader of a class takes -- the latest revision, the latest published one (#615),
            // the row an edit lands on (#613) -- is "this class, ordered by version".
            index(GC.configId, GC.version)
            // The in-memory cache reloads by asking for the rows changed since it last looked (#615), a
            // predicate on `updatedAt` run on every node; without this index that is a full scan.
            index(PF.updatedAt)
            // The reads that skip history (issue #875): across every client (the boot load, the cache's first
            // load), and one client's (its listing, the tier-aware read).
            index(GC.isCurrent)
            index(PF.client, GC.isCurrent)
        }
        // The protection tier (#617): one row per (client, environment) saying whether that client, in that
        // environment, consumes only its published configuration. Deployment-shared like the config rows and
        // owned by the client, never narrower -- `forClient()` for the same reason the config tables take it.
        // Absent means the free tier, so a client that has never toggled needs no row.
        table(GCT.gedraConfigControl, "One client's configuration protection tier in one environment (#617).") {
            column(GC.environment, "The environment this tier applies in.", required = true)
            column(GC.publishedOnly, "Whether this client consumes only its published configuration here.") {
                type = SCT.boolean
            }
            primaryKey(PF.client, GC.environment)
            forClient()
        }
    }
