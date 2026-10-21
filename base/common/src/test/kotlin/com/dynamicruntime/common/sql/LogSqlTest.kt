package com.dynamicruntime.common.sql

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain

/** The line `_debug=logSql` writes for a statement (issue #1334): its name, its SQL, and its bound values redacted. */
class LogSqlTest : StringSpec({

    "a statement's line names it and gives its SQL and bound values" {
        SQLDBG.statementLine("qUser", "  select * from users where userId = ?  ", mapOf("userId" to 5)) shouldBe
            "SQL qUser: select * from users where userId = ? with {userId=5}"
        SQLDBG.statementLine("qAll", "select 1", emptyMap()) shouldBe "SQL qAll: select 1"
    }

    "a secret among the bound values is redacted, at any depth, and an address masked" {
        val line = SQLDBG.statementLine(
            "uIdentity", "update identities set identityData = ? where identityId = ?",
            mapOf("identityData" to mapOf("encodedPassword" to "pbkdf2-secret"), "email" to "pat@example.com"),
        )
        line shouldNotContain "pbkdf2-secret"
        line shouldContain "encodedPassword=<redacted>"
        line shouldNotContain "pat@example.com"
    }
})
