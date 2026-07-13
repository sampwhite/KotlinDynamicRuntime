package com.dynamicruntime.common.sql

import com.dynamicruntime.common.context.KdrCxt
import com.dynamicruntime.common.schema.SCT
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldContainAll
import io.kotest.matchers.collections.shouldNotContain
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe

/**
 * Stage-1 proof of the SQL layer (issue #33): build a [KdrTable] with the builder, create it in an
 * in-memory H2 database, then insert and query a row back, checking the value round-trip (including a JSON
 * map column) and the auto-injected audit protocol columns.
 */
class SqlDatabaseTest : StringSpec({

    "InstanceConfig table: build, create, insert, and query round-trips including audit columns" {
        val cxt = KdrCxt.mkSimpleCxt("test")
        val db = SqlDatabase.mkInMemoryH2("test_sqldb_instanceconfig")
        val topic = "node"

        val tables = tableModule(cxt, topic) {
            table("InstanceConfig", "Stores private instance data") {
                column("instanceName", "Unique identifier of instance of application.")
                column("configType", "The type of configuration held in this row.")
                column("configName", "The name of the configuration data.")
                column("configData", "The configuration data for this entry row.") { type = SCT.kObject }
                primaryKey("instanceName", "configName")
                index("configType")
            }
        }
        val table = tables.single()

        // The four audit columns are injected automatically alongside the declared columns.
        table.columnsByName.keys shouldContainAll setOf(
            "instanceName", "configType", "configName", "configData",
            PF.createdBy, PF.updatedBy, PF.createdAt, PF.updatedAt,
        )

        val sqlCxt = SqlCxt(cxt, db, topic)
        val userId = cxt.userProfile.userId // system user (0) until real auth exists

        val row = linkedMapOf<String, Any?>(
            "instanceName" to "inst1",
            "configType" to "system",
            "configName" to "settings",
            "configData" to linkedMapOf("a" to 1L, "b" to "two"),
            PF.createdBy to userId,
            PF.updatedBy to userId,
            PF.createdAt to cxt.now(),
            PF.updatedAt to cxt.now(),
        )

        db.withSession(cxt) {
            // First creation acts (returns true); a second call would be a no-op.
            SqlTableUtil.checkCreateTable(sqlCxt, table) shouldBe true

            val insertSql = SqlStmtUtil.mkInsertQuery(table.tableName, table.columns, null)
            val insertStmt = SqlStmtUtil.prepareSql(sqlCxt, "iInstanceConfig", table.columns, insertSql)
            db.executeStatement(cxt, insertStmt, row) shouldBe 1

            val selectSql = SqlStmtUtil.mkSelectQuery(table.tableName, listOf("instanceName", "configName"))
            val selectStmt = SqlStmtUtil.prepareSql(sqlCxt, "qInstanceConfig", table.columns, selectSql)
            val result = db.queryOneStatement(cxt, selectStmt, row).shouldNotBeNull()

            result["instanceName"] shouldBe "inst1"
            result["configType"] shouldBe "system"
            result["configData"] shouldBe linkedMapOf("a" to 1L, "b" to "two")
            result[PF.createdBy] shouldBe 0L
        }
    }

    "a user-owned table gains userId and account columns" {
        val cxt = KdrCxt.mkSimpleCxt("test")
        val tables = tableModule(cxt, "app") {
            table("UserThing", "A user-owned thing") {
                column("thingName", "Name of the thing.")
                primaryKey("thingName")
                forUsers()
            }
        }
        val t = tables.single()
        t.features shouldBe setOf(TableFeature.user, TableFeature.account)
        t.columnsByName.keys shouldContainAll setOf(PF.userId, PF.account)
    }

    "the enabled column is injected and queryOneEnabled hides non-enabled rows (issue #48)" {
        val cxt = KdrCxt.mkSimpleCxt("test")
        val db = SqlDatabase.mkInMemoryH2("test_sqldb_enabled")
        val topic = "app"
        val table = tableModule(cxt, topic) {
            table("Item", "An item") {
                column("itemKey", "Key of the item.")
                primaryKey("itemKey")
            }
        }.single()

        // The enabled flag is injected automatically alongside the declared and audit columns.
        table.columnsByName.keys shouldContain PF.enabled

        val sqlCxt = SqlCxt(cxt, db, topic)
        db.withSession(cxt) {
            SqlTableUtil.checkCreateTable(sqlCxt, table) shouldBe true
            val insert = SqlTopicUtil.mkTableInsertStmt(sqlCxt, table)
            val select = SqlTopicUtil.mkTableSelectStmt(sqlCxt, table)

            // A standard "write" stamps enabled = true, so queryOneEnabled returns the row.
            val liveRow = mutableMapOf<String, Any?>("itemKey" to "live")
            SqlTopicUtil.prepForStdExecute(cxt, table, liveRow)
            liveRow[PF.enabled] shouldBe true
            db.executeStatement(cxt, insert, liveRow) shouldBe 1
            db.queryOneEnabled(cxt, select, mapOf("itemKey" to "live")).shouldNotBeNull()

            // An explicitly disabled row is treated as if it were not there...
            db.executeStatement(cxt, insert, mapOf("itemKey" to "off", PF.enabled to false)) shouldBe 1
            db.queryOneEnabled(cxt, select, mapOf("itemKey" to "off")) shouldBe null
            // ...though the plain query still finds it.
            db.queryOneStatement(cxt, select, mapOf("itemKey" to "off")).shouldNotBeNull()

            // A null (here: absent) enabled value also counts as disabled.
            db.executeStatement(cxt, insert, mapOf("itemKey" to "nul")) shouldBe 1
            db.queryOneEnabled(cxt, select, mapOf("itemKey" to "nul")) shouldBe null
        }
    }

    "withoutEnabled suppresses the enabled column" {
        val cxt = KdrCxt.mkSimpleCxt("test")
        val table = tableModule(cxt, "app") {
            table("Ledger", "A table where every row is unconditionally live") {
                column("entryKey", "Key of the entry.")
                primaryKey("entryKey")
                withoutEnabled()
            }
        }.single()
        table.columnsByName.keys shouldNotContain PF.enabled
    }
})
