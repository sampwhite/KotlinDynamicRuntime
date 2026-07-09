package com.dynamicruntime.script

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import java.io.File
import java.nio.file.Files

/**
 * Covers the pure parts of the PostgreSQL setup step (issue #37): the create SQL and the Linux sudo setup
 * script. The interactive/OS-executed parts (prompts, socket probe, Homebrew on macOS, running the script on
 * Linux, the live connection, the secrets write) are exercised by running the installer, not here.
 */
class InstallTest : StringSpec({

    "createDbSql creates the user and database, SQL-escaping the password" {
        val s = createDbSql(dbName = "kdr", dbUser = "kdr", password = "p@ss'word")
        s shouldContain "CREATE USER kdr WITH ENCRYPTED PASSWORD 'p@ss''word';" // single quote doubled for SQL
        s shouldContain "CREATE DATABASE kdr OWNER kdr;"
    }

    "createDatabaseSql creates only the database (role assumed to exist)" {
        val s = createDatabaseSql(dbName = "myfeature", dbUser = "kdr")
        s shouldContain "CREATE DATABASE myfeature OWNER kdr;"
        s shouldNotContain "CREATE USER"
    }

    "the Linux script installs PostgreSQL and runs the create SQL under sudo" {
        val s = linuxSetupScript(install = true, createSql = createDbSql("kdr", "kdr", "pw"))
        s shouldContain "sudo apt install -y postgresql"
        s shouldContain "sudo systemctl start postgresql"
        s shouldContain "sudo -u postgres psql <<'EOF'"
        s shouldContain "CREATE DATABASE kdr OWNER kdr;"
    }

    "when PostgreSQL is already running, the Linux script omits the install/start section" {
        val s = linuxSetupScript(install = false, createSql = createDatabaseSql("myfeature", "kdr"))
        s shouldNotContain "apt install"
        s shouldNotContain "systemctl"
        s shouldContain "sudo -u postgres psql <<'EOF'"
        s shouldContain "CREATE DATABASE myfeature OWNER kdr;"
    }

    "upsertProperties replaces an existing key, appends a new one, and leaves other lines undisturbed" {
        val file = Files.createTempFile("kdr-defaults", ".properties").toFile()
        try {
            file.writeText("# header comment\nKDR_DB_TYPE=h2File\nKDR_LOG_LEVEL=info\n")
            upsertProperties(file, linkedMapOf("KDR_DB_TYPE" to "postgres", "KDR_IN_MEMORY_ONLY" to "false"))
            val out = file.readText()
            out shouldContain "# header comment"
            out shouldContain "KDR_DB_TYPE=postgres"
            out shouldNotContain "KDR_DB_TYPE=h2File"
            out shouldContain "KDR_LOG_LEVEL=info"
            out shouldContain "KDR_IN_MEMORY_ONLY=false"
        } finally {
            file.delete()
        }
    }

    "upsertProperties creates the file (and parent) when absent" {
        val dir = Files.createTempDirectory("kdr-defaults-dir").toFile()
        try {
            val file = File(dir, "sub/default-environment-variables.properties")
            upsertProperties(file, linkedMapOf("KDR_DB_TYPE" to "postgres"))
            file.readText() shouldContain "KDR_DB_TYPE=postgres"
        } finally {
            dir.deleteRecursively()
        }
    }
})
