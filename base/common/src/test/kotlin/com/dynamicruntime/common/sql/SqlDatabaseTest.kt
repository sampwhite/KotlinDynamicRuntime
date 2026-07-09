package com.dynamicruntime.common.sql

import com.dynamicruntime.common.context.KdrCxt
import com.dynamicruntime.common.schema.SCT
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldContainAll
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
})
