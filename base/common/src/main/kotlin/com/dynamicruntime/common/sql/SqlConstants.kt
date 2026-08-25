package com.dynamicruntime.common.sql

// Constants for the SQL/database subsystem. Per the code guide, they live in upper-cased acronym objects,
// referenced qualified (e.g. `PF.createdAt`), with the const-naming inspection suppressed at the object
// level. Each name matches its string value (these are schema/column keys).

/**
 * Protocol column names. Every table carries the four audit columns; the feature columns are added only
 * when a table opts into the corresponding feature (see [TableFeature]).
 *
 * Note the deliberate departure from the prior-art `dn` spellings: audit columns are `createdBy`/`updatedBy`
 * (numeric userId) and `createdAt`/`updatedAt` (dates), rather than dn's `createdDate`/`modifiedDate`; and a
 * single `client` column replaces dn's `userGroup`/`userAccount` pair.
 */
@Suppress("ConstPropertyName")
object PF {
    /** Numeric userId that created the row. */
    const val createdBy = "createdBy"

    /** Numeric userId that last updated the row. */
    const val updatedBy = "updatedBy"

    /** Date the row was created. */
    const val createdAt = "createdAt"

    /** Date the row was last updated. */
    const val updatedAt = "updatedAt"

    /**
     * Soft-delete flag present on every table unless suppressed (see [TableBuilder.withoutEnabled]). A row
     * whose value is not boolean `true` -- including a null or absent value -- is treated as disabled, i.e.,
     * as if it were not there; application reads therefore go through [SqlDatabase.queryOneEnabled]. The
     * standard write (see [SqlTopicUtil.prepForStdExecute]) sets it `true`, so "creating" over a disabled
     * row re-enables it.
     */
    const val enabled = "enabled"

    /** Owning the client (added by the [TableFeature.client] feature). */
    const val client = "client"

    /**
     * Owning organization within the [client] (added by the [TableFeature.org] feature, issue #225).
     *
     * **Nullable on purpose, and null is not a missing value**: it means the row belongs to the client as a
     * whole rather than to any one organization, and such a row stays visible to everybody in the client (see
     * `ReadScope.admitsOrg`). Organizations are a per-client choice, so a client that uses none writes null
     * here forever, and a client that adopts them keeps everything it wrote beforehand.
     */
    const val org = "org"

    /** Owning user's numeric id (added by the [TableFeature.user] feature). */
    const val userId = "userId"

    /**
     * Transaction-lock bookkeeping date, updated to take the topic lock (added by the
     * [TableFeature.transactions] feature). Taking the lock by updating this date — rather than
     * `SELECT ... FOR UPDATE` — plays well with load-balanced database clusters and doubles as an audit of
     * when the row was last touched.
     */
    const val touchedAt = "touchedAt"

    /** ID of the last transaction applied to a transaction-lock row (added by [TableFeature.transactions]). */
    const val lastTranId = "lastTranId"
}

/**
 * Database-configuration keys. The [db] entry names the top-level application-config key under which a
 * database configuration map is stored; the rest are keys within that map. Each name matches its value.
 */
@Suppress("ConstPropertyName")
object DBC {
    /** Top-level application-config key holding the database configuration map. */
    const val db = "db"

    /** The [DbType] name. */
    const val dbType = "dbType"

    /** Logical database name (used in the connection string / for identity). */
    const val dbName = "dbName"

    /** File path for a file-backed H2 database (resolved against the workspace directory). */
    const val filePath = "filePath"

    // PostgreSQL connection settings.
    const val host = "host"
    const val port = "port"
    const val database = "database"
    const val user = "user"

    /**
     * The name of the property in the secrets file to read the database password from — an indirection so
     * the password itself never lives in application config. Defaults to `dbPassword`.
     */
    const val passwordSecretKey = "passwordSecretKey"

    /** Size of the connection pool. */
    const val numConnections = "numConnections"
}

/**
 * Topic names shared by more than one subsystem.
 *
 * A topic is normally declared beside the tables that belong to it -- `authTopic` in `UserTables`,
 * `gedraDataTopic` in `GedraTables`. This object is for the exception: a topic whose tables are contributed by
 * several places, where declaring it next to any one of them would make the others look like they were
 * borrowing it.
 */
@Suppress("ConstPropertyName")
object TOPIC {
    /**
     * Instance-scoped coordination: the configuration a deployment keeps for itself (including the key its
     * cookies are encrypted with) and the cache state its nodes publish so the others learn what changed.
     *
     * **Named for the instance rather than the node, because that is whose data it is.** A topic is the unit
     * of database assignment and of transaction scope, and both belong to the instance -- a node has no
     * database of its own, it shares the instance's. Some of these rows *describe* nodes, and a registry of
     * live ones will join them, but rows about nodes are still the instance's rows: it is the instance's
     * registry of its nodes.
     *
     * This is also the topic an **edge** server uses, and the only one it needs -- which is why the cache
     * state lives here rather than in a topic of its own (issue #435). Both are the same kind of thing: rows
     * written so a deployment stays coherent, rather than application data.
     *
     * Distinct from the `node` endpoint *namespace* (which `/health` uses) and from `NodeService`, both of
     * which really are about this process. The line is: this names the scope of the data, those name the
     * identity of the node.
     */
    const val instance = "instance"
}
