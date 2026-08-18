package com.dynamicruntime.common.sql

import com.dynamicruntime.common.context.CL
import com.dynamicruntime.common.context.KdrCxt
import com.dynamicruntime.common.context.KdrSchemaStore
import com.dynamicruntime.common.schema.SCT
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldContainAll
import io.kotest.matchers.collections.shouldNotBeEmpty
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Instant

/**
 * Stage-2 proof (issue #33): the topic service resolves a topic's tables from the schema store, creates
 * them, and drives a standard topic transaction (insert-lock-execute) whose protocol columns are populated
 * from the context — owner (`client`) from the bound owner, audit (`createdBy`/`updatedBy`) from the actor.
 * Also checks the `/operator/db/tables` list handler reads the table catalog from the store.
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
            table("ClientState", "Per-client transactional state") {
                column("stateKey", "Key of the state row.")
                column("counter", "A counter value.") { type = SCT.integer }
                primaryKey("stateKey")
                forClient()
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
        sqlCxt.tranData[PF.client] shouldBe CL.hub // owner: cxt.client
        sqlCxt.tranData[PF.createdBy] shouldBe 0L // actor: system user
        sqlCxt.tranData[PF.lastTranId] shouldNotBe SqlTopicUtil.initialInsertTranId

        // And it is genuinely persisted: re-query the lock row.
        val sqlTopic = SqlTopicService.get(cxt).shouldNotBeNull().getOrCreateTopic(cxt, "acct").shouldNotBeNull()
        val db = sqlTopic.sqlDb
        db.withSession(cxt) {
            val row = db.queryOneStatement(cxt, sqlTopic.qTranLockQuery!!, mapOf("stateKey" to "s1")).shouldNotBeNull()
            row["counter"] shouldBe 1L
            row[PF.client] shouldBe CL.hub
            row[PF.lastTranId] shouldNotBe SqlTopicUtil.initialInsertTranId
        }
    }

    /**
     * The data layer's write notification ([SqlWriteListener]). It exists so the SQL layer can publish what it
     * wrote without naming who cares -- the table caches subscribe to it, rather than `SqlDatabase` reaching
     * into the cache subsystem directly.
     *
     * The assertion that matters is the *read* one: a listener that also fired on selects would make every
     * query look like a change, which for a cache subscriber means reloading the table on every read.
     */
    "a write notifies the registered listeners with its tables, and a read does not" {
        val tables = tableModule(cxt = KdrCxt.mkSimpleCxt("def"), namespace = "notifyNs", topic = "notify") {
            table("NotifyState", "Per-client transactional state") {
                column("stateKey", "Key of the state row.")
                primaryKey("stateKey")
                withTransactions()
            }
        }
        val cxt = bootCxt("notifyTest", tables)
        val service = SqlTopicService.get(cxt).shouldNotBeNull()

        val seen = mutableListOf<List<String>>()
        val listener = SqlWriteListener { _, tableNames -> seen.add(tableNames) }
        service.addWriteListener(listener)
        // Registration is idempotent by identity, so a service whose checkInit runs twice cannot double up.
        service.addWriteListener(listener)
        service.writeListeners.size shouldBe 1

        val sqlCxt = SqlTopicService.mkSqlCxt(cxt, "notify")
        SqlTopicTranProvider.executeTopicTran(sqlCxt, "touch", null, mapOf("stateKey" to "n1")) {}
        seen.shouldNotBeEmpty()
        seen.all { it == listOf("NotifyState") } shouldBe true

        // Reads publish nothing: queryStatement never calls publishWrite.
        val countAfterWrite = seen.size
        sqlCxt.sqlDb.withSession(cxt) {
            sqlCxt.sqlDb.queryOneStatement(cxt, sqlCxt.sqlTopic!!.qTranLockQuery!!, mapOf("stateKey" to "n1"))
        }
        seen.size shouldBe countAfterWrite
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
        (widget[TI.features] as List<String>) shouldContainAll listOf("user", "client")
    }

    /**
     * [SqlTopicUtil.nextUpdatedAt] forces the write date strictly past the row's current one, whatever the
     * clock says. This is the guarantee a scoped update -- which stamps `updatedAt` itself rather than handing
     * a whole row to `prepDates` -- leans on so the incremental table caches, which skip a row stamped at or
     * before the version they hold, cannot miss it. A same-millisecond re-write is the case that matters, so
     * it is the one asserted.
     */
    "nextUpdatedAt advances strictly past the prior stamp" {
        val cxt = KdrCxt.mkSimpleCxt("nextUpdatedAt")
        cxt.instanceConfig.clock.freeze() // now no longer moves: two calls read the same instant
        val now = cxt.instanceNow()
        val nowMs = now.toEpochMilliseconds()

        // No prior: just now, untouched (sub-millisecond precision and all).
        SqlTopicUtil.nextUpdatedAt(cxt, null) shouldBe now
        // Prior at the same millisecond as now (the same-millisecond re-write): bumped to one ms past it. The
        // bump lands on a whole millisecond -- the stored precision -- rather than carrying now's sub-ms part.
        SqlTopicUtil.nextUpdatedAt(cxt, now) shouldBe Instant.fromEpochMilliseconds(nowMs + 1)
        // Prior ahead of a lagging clock: still strictly past the prior, however far behind now is.
        val ahead = Instant.fromEpochMilliseconds(nowMs + 5000)
        SqlTopicUtil.nextUpdatedAt(cxt, ahead) shouldBe Instant.fromEpochMilliseconds(nowMs + 5001)
        // Prior safely behind: the clock wins, no artificial bump.
        SqlTopicUtil.nextUpdatedAt(cxt, Instant.fromEpochMilliseconds(nowMs - 5000)) shouldBe now
    }
})
