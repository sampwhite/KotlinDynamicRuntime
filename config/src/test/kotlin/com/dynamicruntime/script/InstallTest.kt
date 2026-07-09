package com.dynamicruntime.script

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain

/**
 * Covers the pure part of the PostgreSQL setup step (issue #37): the generated install/setup script. The
 * interactive flow (prompts, socket probe, live connection, secrets write) is exercised by running the
 * installer, not here.
 */
class InstallTest : StringSpec({

    "the Linux script installs PostgreSQL and creates the db/user, SQL-escaping the password" {
        val s = postgresSetupScript(isMac = false, dbName = "kdr", dbUser = "kdr", password = "p@ss'word", install = true)
        s shouldContain "sudo apt install -y postgresql"
        s shouldContain "sudo systemctl start postgresql"
        s shouldContain "sudo -u postgres psql <<'EOF'"
        s shouldContain "CREATE USER kdr WITH ENCRYPTED PASSWORD 'p@ss''word';" // single quote doubled for SQL
        s shouldContain "CREATE DATABASE kdr OWNER kdr;"
    }

    "the macOS script uses Homebrew and psql" {
        val s = postgresSetupScript(isMac = true, dbName = "kdr", dbUser = "kdr", password = "pw", install = true)
        s shouldContain "brew install postgresql@16"
        s shouldContain "brew services start postgresql@16"
        s shouldContain "psql\" postgres <<'EOF'"
    }

    "when PostgreSQL is already running, the install/start section is omitted" {
        val s = postgresSetupScript(isMac = false, dbName = "kdr", dbUser = "kdr", password = "pw", install = false)
        s shouldNotContain "apt install"
        s shouldNotContain "systemctl"
        s shouldContain "sudo -u postgres psql <<'EOF'"
        s shouldContain "CREATE DATABASE kdr OWNER kdr;"
    }
})
