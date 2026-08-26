package com.dynamicruntime.common.sql

import com.dynamicruntime.common.context.ENVGRP
import com.dynamicruntime.common.context.EnvVarDef
import com.dynamicruntime.common.context.KdrCxt

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
    val inMemoryOnly = EnvVarDef(
        "KDR_IN_MEMORY_ONLY", group = ENVGRP.database, defaultDoc = "true",
        description = "Default for `inMemoryOnly` mode (parsed loosely as a boolean). When true, the runtime " +
            "uses in-memory state and the database type is forced to in-memory H2.",
    )

    /** Selects the [DbType]; ignored when in-memory mode forces in-memory H2. */
    val dbType = EnvVarDef(
        "KDR_DB_TYPE", group = ENVGRP.database, defaultDoc = "in-memory H2",
        description = "Selects the database type; ignored when in-memory mode forces in-memory H2.",
    )

    val dbHost = EnvVarDef(
        "KDR_DB_HOST", group = ENVGRP.database, defaultDoc = "localhost in `local`, else required",
        description = "PostgreSQL host, with an optional `:port` suffix (else the default port). Defaulted to " +
            "localhost only in the local environment; other environments must set it.",
    )

    val dbName = EnvVarDef(
        "KDR_DB_NAME", group = ENVGRP.database, defaultDoc = "`kdr`",
        description = "Database name, used by file-backed H2 and PostgreSQL.",
    )

    val dbUser = EnvVarDef(
        "KDR_DB_USER", group = ENVGRP.database, defaultDoc = "`kdr`",
        description = "PostgreSQL username. Postgres only (the H2 variants use a hardcoded user); rarely set.",
    )

    val allowSchemaDrift = EnvVarDef(
        "KDR_ALLOW_SCHEMA_DRIFT", group = ENVGRP.database, defaultDoc = "off",
        description = "Boots despite **blocking** schema drift -- a `NOT NULL` column with no default that the " +
            "database has and the code does not declare (issue #216). Off by default, so such a deployment " +
            "refuses to start (it cannot write). The escape hatch only downgrades the refusal to a logged " +
            "error; writes still fail. For an operator mid-migration who has a legitimate reason to start.",
    )

    /** Resolves the default for `inMemoryOnly`: the [inMemoryOnly] env var if set (parsed loosely), else true. */
    fun resolveInMemoryOnly(cxt: KdrCxt): Boolean = cxt.getEnvBool(inMemoryOnly) ?: true
}
