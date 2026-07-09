package com.dynamicruntime.common.sql

import com.dynamicruntime.common.config.KdrConfigData
import com.dynamicruntime.common.context.ENV
import com.dynamicruntime.common.context.KdrCxt
import com.dynamicruntime.common.context.KdrInstanceConfig
import com.dynamicruntime.common.exception.KdrException
import com.dynamicruntime.common.exception.SRC
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import java.io.File
import java.nio.file.Files

/** Runs [block] with the workspace directory pointed at a fresh temp dir, restoring it afterward. */
private fun <T> withTempWorkspaceDir(block: (File) -> T): T {
    val dir = Files.createTempDirectory("kdrDbTest").toFile()
    val prev = System.getProperty(AppPaths.workspaceDirProperty)
    System.setProperty(AppPaths.workspaceDirProperty, dir.absolutePath)
    try {
        return block(dir)
    } finally {
        if (prev == null) {
            System.clearProperty(AppPaths.workspaceDirProperty)
        } else {
            System.setProperty(AppPaths.workspaceDirProperty, prev)
        }
        dir.deleteRecursively()
    }
}

/**
 * Stage-3 proof (issue #33): the database config builder produces the `db` config for each database kind;
 * the builder defaults the type from in-memory-ness; databases are built (and connection-tested) for the H2
 * kinds; and the secrets file indirection resolves (or fails fast on) a PostgreSQL password.
 */
class SqlDbBuilderTest : StringSpec({

    val cxt = KdrCxt.mkSimpleCxt("test")

    "DatabaseConfigBuilder writes a db config map per database kind" {
        val mem = KdrConfigData(cxt).also { DatabaseConfigBuilder(it).inMemoryH2() }
        (mem.data[DBC.db] as Map<*, *>)[DBC.dbType] shouldBe DbType.h2Memory.name

        val file = KdrConfigData(cxt).also { DatabaseConfigBuilder(it).fileH2(filePath = "x/y.dat") }
        val fileCfg = (file.data[DBC.db] as Map<*, *>)
        fileCfg[DBC.dbType] shouldBe DbType.h2File.name
        fileCfg[DBC.filePath] shouldBe "x/y.dat"

        val pg = KdrConfigData(cxt).also { DatabaseConfigBuilder(it).postgres(host = "h", database = "d", user = "u") }
        val pgCfg = (pg.data[DBC.db] as Map<*, *>)
        pgCfg[DBC.dbType] shouldBe DbType.postgres.name
        pgCfg[DBC.host] shouldBe "h"
        pgCfg[DBC.passwordSecretKey] shouldBe SqlDbBuilder.defaultPasswordSecretKey // dbPassword
    }

    "resolveDbConfig defaults by in-memory-ness and honors an explicit config" {
        SqlDbBuilder.resolveDbConfig(cxt, isInMemory = true)[DBC.dbType] shouldBe DbType.h2Memory.name

        val fileDefault = SqlDbBuilder.resolveDbConfig(cxt, isInMemory = false)
        fileDefault[DBC.dbType] shouldBe DbType.h2File.name
        fileDefault[DBC.filePath] shouldBe SqlDbBuilder.defaultH2FilePath

        val explicitCxt = KdrCxt.mkSimpleCxt("explicit")
        explicitCxt.instanceConfig.put(DBC.db, mapOf(DBC.dbType to DbType.h2Memory.name, DBC.dbName to "custom"))
        val resolved = SqlDbBuilder.resolveDbConfig(explicitCxt, isInMemory = false)
        resolved[DBC.dbType] shouldBe DbType.h2Memory.name
        SqlDbBuilder.dbNameOf(resolved) shouldBe "custom"
    }

    "createDatabase builds a working in-memory H2" {
        val db = SqlDbBuilder.createDatabase(cxt, "sdb_mem", mapOf(DBC.dbType to DbType.h2Memory.name))
        db.dbName shouldBe "sdb_mem"
        db.connectionUrl shouldContain "jdbc:h2:mem:sdb_mem"
    }

    "createDatabase builds a file-backed H2 under the workspace directory" {
        withTempWorkspaceDir { dir ->
            val cfg = mapOf(DBC.dbType to DbType.h2File.name, DBC.filePath to SqlDbBuilder.defaultH2FilePath)
            val db = SqlDbBuilder.createDatabase(cxt, "sdb_file", cfg)
            db.connectionUrl shouldContain File(dir, SqlDbBuilder.defaultH2FilePath).absolutePath
            // Connecting created the H2 data file at the resolved path.
            File(dir, "${SqlDbBuilder.defaultH2FilePath}.mv.db").isFile shouldBe true
        }
    }

    "the secrets file resolves a value, and a missing required secret fails" {
        withTempWorkspaceDir { dir ->
            shouldThrow<KdrException> { SecretsUtil.getReqSecret("dbPassword") }

            File(dir, "private").mkdirs()
            File(dir, SecretsUtil.secretsPath).writeText("dbPassword=s3cret\n")
            SecretsUtil.getSecret("dbPassword") shouldBe "s3cret"
            SecretsUtil.getReqSecret("dbPassword") shouldBe "s3cret"
        }
    }

    "a PostgreSQL database fails fast (as a config error) when its password secret is missing" {
        withTempWorkspaceDir {
            val cfg = mapOf(
                DBC.dbType to DbType.postgres.name,
                DBC.host to "localhost",
                DBC.database to "d",
                DBC.user to "u",
            )
            // No secrets file is present, so the password lookup throws before any connection is attempted.
            val ex = shouldThrow<KdrException> { SqlDbBuilder.createDatabase(cxt, "pg", cfg) }
            ex.source shouldBe SRC.config
        }
    }

    "in-memory mode forces in-memory H2, overriding an explicit config" {
        val c = KdrCxt.mkSimpleCxt("forced")
        c.instanceConfig.put(DBC.db, mapOf(DBC.dbType to DbType.postgres.name, DBC.host to "h"))
        SqlDbBuilder.resolveDbConfig(c, isInMemory = true)[DBC.dbType] shouldBe DbType.h2Memory.name
    }

    "env vars fully configure a PostgreSQL database, parsing an optional :port on the host" {
        val c = KdrCxt.mkSimpleCxt("pgenv")
        c.instanceConfig.put(DbEnv.dbType, "postgres")
        c.instanceConfig.put(DbEnv.dbHost, "db.example.com:5433")
        c.instanceConfig.put(DbEnv.dbName, "orders")
        c.instanceConfig.put(DbEnv.dbUser, "svc")
        val cfg = SqlDbBuilder.resolveDbConfig(c, isInMemory = false)
        cfg[DBC.dbType] shouldBe DbType.postgres.name
        cfg[DBC.host] shouldBe "db.example.com"
        cfg[DBC.port] shouldBe 5433
        cfg[DBC.database] shouldBe "orders"
        cfg[DBC.user] shouldBe "svc"
        cfg[DBC.passwordSecretKey] shouldBe SqlDbBuilder.defaultPasswordSecretKey
    }

    "a host with no :port suffix uses the database's default port" {
        val c = KdrCxt.mkSimpleCxt("pgport")
        c.instanceConfig.put(DbEnv.dbType, "postgres")
        c.instanceConfig.put(DbEnv.dbHost, "localhost")
        val cfg = SqlDbBuilder.resolveDbConfig(c, isInMemory = false)
        cfg[DBC.host] shouldBe "localhost"
        cfg[DBC.port] shouldBe SqlDbBuilder.defaultPostgresPort
    }

    "the default (non-in-memory) type is file H2 with a name-derived path" {
        val c = KdrCxt.mkSimpleCxt("fileenv")
        c.instanceConfig.put(DbEnv.dbName, "mydb")
        val cfg = SqlDbBuilder.resolveDbConfig(c, isInMemory = false)
        cfg[DBC.dbType] shouldBe DbType.h2File.name
        cfg[DBC.filePath] shouldBe "h2Database/mydb.dat"
    }

    "resolveInMemoryOnly defaults to true and honors KDR_IN_MEMORY_ONLY" {
        DbEnv.resolveInMemoryOnly(KdrCxt.mkSimpleCxt("d1")) shouldBe true
        val c = KdrCxt.mkSimpleCxt("d2")
        c.instanceConfig.put(DbEnv.inMemoryOnly, "false")
        DbEnv.resolveInMemoryOnly(c) shouldBe false
    }

    "in the local environment, PostgreSQL defaults the host to localhost and the user to kdr" {
        val c = KdrCxt("pglocal", KdrInstanceConfig("pglocal", ENV.local, ENV.liveSource))
        c.instanceConfig.put(DbEnv.dbType, "postgres")
        val cfg = SqlDbBuilder.resolveDbConfig(c, isInMemory = false)
        cfg[DBC.host] shouldBe SqlDbBuilder.defaultLocalDbHost
        cfg[DBC.port] shouldBe SqlDbBuilder.defaultPostgresPort
        cfg[DBC.user] shouldBe SqlDbBuilder.defaultDbUser
        cfg[DBC.database] shouldBe SqlDbBuilder.defaultDbName
    }

    "outside the local environment, PostgreSQL requires an explicit host" {
        val c = KdrCxt.mkSimpleCxt("pgnonlocal") // env = unit
        c.instanceConfig.put(DbEnv.dbType, "postgres")
        val ex = shouldThrow<KdrException> { SqlDbBuilder.resolveDbConfig(c, isInMemory = false) }
        ex.source shouldBe SRC.config
    }
})
