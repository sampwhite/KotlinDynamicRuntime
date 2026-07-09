package com.dynamicruntime.common.sql

import com.dynamicruntime.common.config.KdrConfigData

/**
 * Configures the instance's database from the side: each convenience method writes a database-configuration
 * map under the [DBC.db] key of the supplied application config, so that at startup [SqlDbBuilder] can build
 * the [SqlDatabase] from it. A deployment typically calls one of these from its `applyAppConfig`.
 *
 * Takes a [KdrConfigData] rather than the concrete `AppConfigBuilder`: the builder lives in the same package
 * as [SqlDatabase] (`common`), which cannot depend on the `config` module, and `AppConfigBuilder` is a
 * `KdrConfigData`, so a deployment passes its `AppConfigBuilder` here unchanged.
 */
class DatabaseConfigBuilder(val config: KdrConfigData) {
    /** Configures an in-memory H2 database (nothing else needs setting). */
    fun inMemoryH2(dbName: String = SqlDbBuilder.defaultDbName) {
        config.data[DBC.db] = linkedMapOf(
            DBC.dbType to DbType.h2Memory.name,
            DBC.dbName to dbName,
        )
    }

    /** Configures a file-backed H2 database at [filePath] (resolved against the workspace directory). */
    fun fileH2(dbName: String = SqlDbBuilder.defaultDbName, filePath: String = SqlDbBuilder.h2FilePathFor(dbName)) {
        config.data[DBC.db] = linkedMapOf(
            DBC.dbType to DbType.h2File.name,
            DBC.dbName to dbName,
            DBC.filePath to filePath,
        )
    }

    /**
     * Configures a PostgreSQL database. The password is not supplied here: [passwordSecretKey] names the
     * property to read it from in the secrets file (see [SecretsUtil]), defaulting to `dbPassword`.
     */
    fun postgres(
        host: String,
        database: String = SqlDbBuilder.defaultDbName,
        user: String = SqlDbBuilder.defaultDbUser,
        dbName: String = SqlDbBuilder.defaultDbName,
        port: Int = SqlDbBuilder.defaultPostgresPort,
        passwordSecretKey: String = SqlDbBuilder.defaultPasswordSecretKey,
    ) {
        config.data[DBC.db] = linkedMapOf(
            DBC.dbType to DbType.postgres.name,
            DBC.dbName to dbName,
            DBC.host to host,
            DBC.port to port,
            DBC.database to database,
            DBC.user to user,
            DBC.passwordSecretKey to passwordSecretKey,
        )
    }
}
