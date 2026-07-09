package com.dynamicruntime.common.sql

import com.dynamicruntime.common.context.KdrCxt
import com.dynamicruntime.common.util.toOptBool

/**
 * Environment-variable names that configure the database, plus the resolver for the in-memory default. All
 * use the project's `KDR_` prefix. Values are read through [KdrCxt.getEnvVar], so instance config (and tests)
 * can override them without touching the real process environment.
 *
 * The database can be configured entirely from these variables (plus the password in the secrets file): the
 * type from [dbType]; for PostgreSQL, the host (with an optional `:port` suffix) from [dbHost] and the user
 * from [dbUser]; and the database name from [dbName] (used by file-backed H2 and PostgreSQL). In practice
 * only [dbType] and [dbName] (and [dbHost] outside the local environment) are usually set — the host
 * defaults to localhost locally and the user is effectively always `kdr`.
 */
@Suppress("ConstPropertyName")
object DbEnv {
    /** Boolean default for `inMemoryOnly` when it is not explicitly configured (default true). */
    const val inMemoryOnly = "KDR_IN_MEMORY_ONLY"

    /** Selects the [DbType]; ignored when in-memory mode forces in-memory H2. */
    const val dbType = "KDR_DB_TYPE"

    /**
     * PostgreSQL host, with an optional `:port` suffix (else the default port). Defaulted to
     * [SqlDbBuilder.defaultLocalDbHost] only in the local environment; other environments must set it.
     */
    const val dbHost = "KDR_DB_HOST"

    /** Database name (file-backed H2 and PostgreSQL); defaults to [SqlDbBuilder.defaultDbName] (`kdr`). */
    const val dbName = "KDR_DB_NAME"

    /**
     * PostgreSQL username. Postgres only (the H2 variants use a hardcoded user); defaults to
     * [SqlDbBuilder.defaultDbUser] (`kdr`) and is rarely, if ever, set.
     */
    const val dbUser = "KDR_DB_USER"

    /** Resolves the default for `inMemoryOnly`: the [inMemoryOnly] env var if set (parsed loosely), else true. */
    fun resolveInMemoryOnly(cxt: KdrCxt): Boolean = cxt.getEnvVar(inMemoryOnly)?.toOptBool() ?: true
}
