package com.dynamicruntime.common.sql

import com.dynamicruntime.common.context.ENV
import com.dynamicruntime.common.context.KdrCxt
import com.dynamicruntime.common.exception.ACT
import com.dynamicruntime.common.exception.EXC
import com.dynamicruntime.common.exception.KdrException
import com.dynamicruntime.common.exception.SRC
import com.dynamicruntime.common.util.toJsonMap
import java.sql.Driver
import java.sql.DriverManager
import java.sql.SQLException
import java.util.Properties

/**
 * Builds a [SqlDatabase] from a database-configuration map (the [DBC.db] config value), and resolves which
 * configuration to use at startup. Once the database *type* is known, everything else can be defaulted (for
 * PostgreSQL from the `KDR_DB_*` environment variables plus the password secret), so a deployment needs only
 * to select the type and populate its secrets file.
 *
 * Rewritten from the prior-art `SqlDbBuilder` — config-driven rather than reading scattered `db.<name>.*`
 * keys, with the shard dimension dropped and passwords resolved via [SecretsUtil].
 */
@Suppress("ConstPropertyName")
object SqlDbBuilder {
    const val defaultDbName = "kdr"
    const val defaultDbUser = "kdr"
    const val defaultLocalDbHost = "localhost"
    const val defaultPasswordSecretKey = "dbPassword"
    const val defaultPostgresPort = 5432

    /** The default file path for a file-backed H2 database named [defaultDbName]. */
    const val defaultH2FilePath = "h2Database/$defaultDbName.dat"

    /** The file path for a file-backed H2 database named [name] (relative to the workspace directory). */
    fun h2FilePathFor(name: String): String = "h2Database/$name.dat"

    /**
     * Resolves the database configuration for this instance:
     *  - If [isInMemory], the database type is **forced** to in-memory H2, overriding everything else;
     *  - Otherwise an explicit [DBC.db] config (e.g., from [DatabaseConfigBuilder]) wins;
     *  - Otherwise the config is built from the `KDR_DB_*` environment variables, with the type coming from
     *    `KDR_DB_TYPE` and defaulting to file-backed H2.
     */
    fun resolveDbConfig(cxt: KdrCxt, isInMemory: Boolean): Map<String, Any?> {
        if (isInMemory) {
            return linkedMapOf(DBC.dbType to DbType.h2Memory.name, DBC.dbName to dbNameOf(cxt))
        }
        val explicit = cxt.instanceConfig.get(DBC.db)
        if (explicit is Map<*, *>) {
            return explicit.toJsonMap()
        }
        val type = cxt.getEnvVar(DbEnv.dbType)?.let { DbType.parse(it) } ?: DbType.h2File
        return envConfigFor(cxt, type)
    }

    /** Builds a configuration for [type] from the `KDR_DB_*` environment variables. */
    fun envConfigFor(cxt: KdrCxt, type: DbType): Map<String, Any?> {
        val name = dbNameOf(cxt)
        return when (type) {
            DbType.h2Memory -> linkedMapOf(DBC.dbType to DbType.h2Memory.name, DBC.dbName to name)
            DbType.h2File -> linkedMapOf(
                DBC.dbType to DbType.h2File.name,
                DBC.dbName to name,
                DBC.filePath to h2FilePathFor(name),
            )
            DbType.postgres -> {
                val cfg = linkedMapOf<String, Any?>(
                    DBC.dbType to DbType.postgres.name,
                    DBC.dbName to name,
                    DBC.database to name,
                    // The username is effectively always "kdr"; the env var exists only as an override.
                    DBC.user to (cxt.getEnvVar(DbEnv.dbUser) ?: defaultDbUser),
                    DBC.passwordSecretKey to defaultPasswordSecretKey,
                )
                // Host carries an optional ":port" suffix. It is only defaulted to localhost in the local
                // environment; every other environment must set KDR_DB_HOST explicitly (a safety guard
                // against a deployed instance silently connecting to a local database).
                val hostSpec = cxt.getEnvVar(DbEnv.dbHost)
                    ?: if (cxt.instanceConfig.env == ENV.local) {
                        defaultLocalDbHost
                    } else {
                        throw mkConfigError(
                            "PostgreSQL requires ${DbEnv.dbHost} to be set in the " +
                                "'${cxt.instanceConfig.env}' environment; it is only defaulted to " +
                                "$defaultLocalDbHost in the '${ENV.local}' environment.",
                        )
                    }
                val (host, port) = parseHostPort(hostSpec, defaultPostgresPort)
                cfg[DBC.host] = host
                cfg[DBC.port] = port
                cfg
            }
        }
    }

    /** Splits a `host[:port]` spec, defaulting the port to [defaultPort] when no suffix is present. */
    fun parseHostPort(spec: String, defaultPort: Int): Pair<String, Int> {
        val idx = spec.lastIndexOf(':')
        if (idx < 0) {
            return spec to defaultPort
        }
        val host = spec.substring(0, idx)
        val port = spec.substring(idx + 1).toIntOrNull() ?: defaultPort
        return host to port
    }

    /** The database name from the `KDR_DB_NAME` env var, or [defaultDbName]. */
    fun dbNameOf(cxt: KdrCxt): String = cxt.getEnvVar(DbEnv.dbName) ?: defaultDbName

    /** The database name from a resolved [config], or [defaultDbName]. */
    fun dbNameOf(config: Map<String, Any?>): String = config[DBC.dbName] as? String ?: defaultDbName

    /** Creates and connection-tests a [SqlDatabase] named [dbName] from [config]. */
    fun createDatabase(cxt: KdrCxt, dbName: String, config: Map<String, Any?>): SqlDatabase {
        val type = DbType.parse(config[DBC.dbType])
        val options = SqlDbOptions()
        val properties = Properties()
        val connStr: String
        var numConnections = 4

        when (type) {
            DbType.h2Memory ->
                // DB_CLOSE_DELAY=-1 keeps the database alive for the life of the JVM between pooled connections.
                connStr = "jdbc:h2:mem:$dbName;DB_CLOSE_DELAY=-1"

            DbType.h2File -> {
                val relPath = config[DBC.filePath] as? String ?: h2FilePathFor(dbName)
                val file = AppPaths.resolve(relPath)
                file.parentFile?.mkdirs()
                connStr = "jdbc:h2:file:${file.absolutePath}"
            }

            DbType.postgres -> {
                val host = config[DBC.host] as? String
                    ?: throw mkConfigError("PostgreSQL configuration for database $dbName is missing ${DBC.host}.")
                val database = config[DBC.database] as? String
                    ?: throw mkConfigError("PostgreSQL configuration for database $dbName is missing ${DBC.database}.")
                val user = config[DBC.user] as? String
                    ?: throw mkConfigError("PostgreSQL configuration for database $dbName is missing ${DBC.user}.")
                val port = (config[DBC.port] as? Number)?.toInt() ?: defaultPostgresPort
                val secretKey = config[DBC.passwordSecretKey] as? String ?: defaultPasswordSecretKey
                // Resolve the password from the secrets file BEFORE attempting to connect, so a missing
                // secret fails fast (and deterministically) as a startup/config error.
                val password = SecretsUtil.getReqSecret(secretKey)
                properties.setProperty("user", user)
                properties.setProperty("password", password)
                connStr = "jdbc:postgresql://$host:$port/$database"
                options.hasSerialType = true
                options.storesLowerCaseIdentifiersInSchema = true
                numConnections = 20
            }
        }

        (config[DBC.numConnections] as? Number)?.toInt()?.let { numConnections = it }

        val driver = getDriver(dbName, connStr)
        testConnection(dbName, driver, connStr, properties)
        LogSql.debug(cxt) { "Connecting to database $dbName using connection string $connStr." }
        return SqlDatabase(dbName, driver, connStr, properties, emptyMap(), options, numConnections)
    }

    private fun getDriver(dbName: String, connStr: String): Driver =
        try {
            DriverManager.getDriver(connStr)
        } catch (e: SQLException) {
            throw KdrException(
                "Cannot get driver for database $dbName using connection string $connStr.",
                e, EXC.notSupported, SRC.database, ACT.connection,
            )
        }

    private fun testConnection(dbName: String, driver: Driver, connStr: String, properties: Properties) {
        try {
            driver.connect(connStr, properties).close()
        } catch (e: SQLException) {
            throw KdrException(
                "Cannot connect to database $dbName using $connStr.",
                e, EXC.internalError, SRC.database, ACT.connection,
            )
        }
    }

    private fun mkConfigError(message: String): KdrException =
        KdrException(message, null, EXC.notSupported, SRC.config, ACT.code)
}
