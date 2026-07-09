package com.dynamicruntime.common.sql

import com.dynamicruntime.common.context.KdrCxt
import com.dynamicruntime.common.context.KdrSchemaStore
import com.dynamicruntime.common.schema.SCT
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldContainAll
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe

/**
 * Stage-2 proof (issue #33): the topic service resolves a topic's tables from the schema store, creates
 * them, and drives a standard topic transaction (insert-lock-execute) whose protocol columns are populated
 * from the context — owner (`account`) from the bound owner, audit (`createdBy`/`updatedBy`) from the actor.
 * Also checks the `/db/tables` list handler reads the table catalog from the store.
 */
class SqlTopicServiceTest : StringSpec({

    /** Publishes a schema store holding [tables] and a live [SqlTopicService] on a fresh context. */
    fun bootCxt(cxtName: String, tables: List<KdrTable>): KdrCxt {
        val cxt = KdrCxt.mkSimpleCxt(cxtName)
        cxt.instanceConfig.put(KdrSchemaStore.key, KdrSchemaStore(tables = tables.associateBy { it.tableName }))
        val service = SqlTopicService()
        cxt.instanceConfig.put(SqlTopicService.serviceName, service)
        service.checkInit(cxt)
        return cxt
    }

    "a topic transaction inserts, locks, and writes back a row with populated protocol columns" {
        val tables = tableModule(cxt = KdrCxt.mkSimpleCxt("def"), namespace = "acctNs", topic = "acct") {
            table("AccountState", "Per-account transactional state") {
                column("stateKey", "Key of the state row.")
                column("counter", "A counter value.") { type = SCT.integer }
                primaryKey("stateKey")
                forAccount()
                withTransactions()
            }
        }
        val cxt = bootCxt("tranTest", tables)

        val sqlCxt = SqlTopicService.mkSqlCxt(cxt, "acct")
        SqlTopicTranProvider.executeTopicTran(sqlCxt, "bumpCounter", null, mapOf("stateKey" to "s1")) {
            sqlCxt.tranData["counter"] = 1L
        }

        // The final written row is left on the context.
        sqlCxt.tranData["counter"] shouldBe 1L
        sqlCxt.tranData[PF.account] shouldBe "local" // owner: cxt.account
        sqlCxt.tranData[PF.createdBy] shouldBe 0L // actor: system user
        sqlCxt.tranData[PF.lastTranId] shouldNotBe SqlTopicUtil.initialInsertTranId

        // And it is genuinely persisted: re-query the lock row.
        val sqlTopic = SqlTopicService.get(cxt).shouldNotBeNull().getOrCreateTopic(cxt, "acct").shouldNotBeNull()
        val db = sqlTopic.sqlDb
        db.withSession(cxt) {
            val row = db.queryOneStatement(cxt, sqlTopic.qTranLockQuery!!, mapOf("stateKey" to "s1")).shouldNotBeNull()
            row["counter"] shouldBe 1L
            row[PF.account] shouldBe "local"
            row[PF.lastTranId] shouldNotBe SqlTopicUtil.initialInsertTranId
        }
    }

    "listTables dumps the registered tables from the store" {
        val tables = tableModule(cxt = KdrCxt.mkSimpleCxt("def"), namespace = "widgetNs", topic = "widget") {
            table("Widget", "A widget") {
                column("widgetKey", "Key of the widget.")
                primaryKey("widgetKey")
                forUsers()
            }
        }
        val cxt = bootCxt("listTest", tables)

        val dump = SqlTopicService.listTables(cxt)
        dump.map { it[TI.tableName] } shouldBe listOf("Widget")
        val widget = dump.single()
        widget[TI.topic] shouldBe "widget"
        @Suppress("UNCHECKED_CAST")
        (widget[TI.features] as List<String>) shouldContainAll listOf("user", "account")
    }
})
