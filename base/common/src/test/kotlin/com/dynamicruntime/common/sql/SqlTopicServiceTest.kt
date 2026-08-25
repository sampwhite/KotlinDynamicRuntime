package com.dynamicruntime.common.sql

import com.dynamicruntime.common.context.CL
import com.dynamicruntime.common.context.KdrCxt
import com.dynamicruntime.common.context.KdrSchemaStore
import com.dynamicruntime.common.schema.SCT
import com.dynamicruntime.common.exception.KdrException
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.string.shouldContain
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldContainAll
import io.kotest.matchers.collections.shouldNotBeEmpty
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
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
        val sqlTopic = SqlTopicService.get(cxt).getOrCreateTopic(cxt, "acct").shouldNotBeNull()
        val db = sqlTopic.sqlDb
        db.withSession(cxt) {
            val row = db.queryOneStatement(cxt, sqlTopic.tranFor(null, "reread").queryLock, mapOf("stateKey" to "s1")).shouldNotBeNull()
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
        val service = SqlTopicService.get(cxt)

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
            sqlCxt.sqlDb.queryOneStatement(cxt, sqlCxt.sqlTopic!!.tranFor(null, "reread").queryLock, mapOf("stateKey" to "n1"))
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

/**
 * Several transactional tables in one topic (issue #435).
 *
 * The property that matters is that a transaction locks the table it named **and no other**. A lock taken on
 * the wrong table still produces correct results -- the work runs, the data is right -- it merely serializes
 * transactions that have nothing to do with each other. So the failure is slow rather than broken, which is
 * exactly the kind that survives a test suite unless something asserts the row it must not have touched.
 */
class SqlTopicMultiTranTest : StringSpec({

    fun twoTranTables() = tableModule(cxt = KdrCxt.mkSimpleCxt("def"), namespace = "twoNs", topic = "two") {
        table("AlphaState", "First transactional table.") {
            column("stateKey", "Key of the state row.")
            column("counter", "A counter value.") { type = SCT.integer }
            primaryKey("stateKey")
            withTransactions()
        }
        table("BetaState", "Second transactional table, unrelated to the first.") {
            column("stateKey", "Key of the state row.")
            column("counter", "A counter value.") { type = SCT.integer }
            primaryKey("stateKey")
            withTransactions()
        }
    }

    fun bootCxt(name: String): KdrCxt {
        val cxt = KdrCxt.mkSimpleCxt(name)
        val tables = twoTranTables()
        cxt.instanceConfig.put(KdrSchemaStore.key, KdrSchemaStore(tables = tables.associateBy { it.tableName }))
        val service = SqlTopicService()
        cxt.instanceConfig.put(SqlTopicService.serviceName, service)
        service.checkInit(cxt)
        return cxt
    }

    // The limit this issue removed: a second transactional table used to fail the topic's construction, so a
    // second transactional concern needed a topic of its own -- and a topic is also the unit of database
    // assignment, which forced two unrelated decisions together.
    "a topic accepts more than one transactional table" {
        val topic = SqlTopicService.get(bootCxt("multiInit")).getOrCreateTopic(bootCxt("multiInit"), "two")
        topic.shouldNotBeNull().tranTables.map { it.tableName }.sorted() shouldBe listOf("AlphaState", "BetaState")
    }

    "each transactional table gets its own lock queries" {
        val cxt = bootCxt("multiQueries")
        val topic = SqlTopicService.get(cxt).getOrCreateTopic(cxt, "two").shouldNotBeNull()
        val alpha = topic.tranFor("AlphaState", "t").queryLock
        val beta = topic.tranFor("BetaState", "t").queryLock
        alpha shouldNotBe beta
        topic.tranFor("AlphaState", "t").table.tableName shouldBe "AlphaState"
        topic.tranOrNull("NoSuchState") shouldBe null
    }

    /**
     * The heart of it. A transaction on Alpha must leave Beta's lock row alone -- if it did not, the two
     * would contend, and nothing about the result would look wrong.
     */
    "a transaction locks only the table it named" {
        val cxt = bootCxt("multiIsolation")
        val sqlCxt = SqlTopicService.mkSqlCxt(cxt, "two")
        val topic = sqlCxt.sqlTopic.shouldNotBeNull()

        SqlTopicTranProvider.executeTopicTran(
            sqlCxt, "bumpAlpha", null, mapOf("stateKey" to "k1"), tranTableName = "AlphaState",
        ) {
            sqlCxt.tranData["counter"] = 7L
        }

        sqlCxt.sqlDb.withSession(cxt) {
            val alphaRow = sqlCxt.sqlDb
                .queryOneStatement(cxt, topic.tranFor("AlphaState", "t").queryLock, mapOf("stateKey" to "k1"))
            alphaRow.shouldNotBeNull()["counter"] shouldBe 7L

            // Beta was never touched: no lock row was inserted there at all.
            val betaRow = sqlCxt.sqlDb
                .queryOneStatement(cxt, topic.tranFor("BetaState", "t").queryLock, mapOf("stateKey" to "k1"))
            betaRow shouldBe null
        }
    }

    "the two tables' transactions do not disturb each other" {
        val cxt = bootCxt("multiBoth")
        val sqlCxt = SqlTopicService.mkSqlCxt(cxt, "two")
        val topic = sqlCxt.sqlTopic.shouldNotBeNull()

        SqlTopicTranProvider.executeTopicTran(
            sqlCxt, "bumpAlpha", null, mapOf("stateKey" to "k1"), tranTableName = "AlphaState",
        ) { sqlCxt.tranData["counter"] = 1L }
        val alphaTranId = sqlCxt.tranData[PF.lastTranId]

        SqlTopicTranProvider.executeTopicTran(
            sqlCxt, "bumpBeta", null, mapOf("stateKey" to "k1"), tranTableName = "BetaState",
        ) { sqlCxt.tranData["counter"] = 2L }

        sqlCxt.sqlDb.withSession(cxt) {
            val alphaRow = sqlCxt.sqlDb
                .queryOneStatement(cxt, topic.tranFor("AlphaState", "t").queryLock, mapOf("stateKey" to "k1"))
            // Alpha still carries its own transaction's id and value -- Beta's run did not write over it.
            alphaRow.shouldNotBeNull()["counter"] shouldBe 1L
            alphaRow[PF.lastTranId] shouldBe alphaTranId

            val betaRow = sqlCxt.sqlDb
                .queryOneStatement(cxt, topic.tranFor("BetaState", "t").queryLock, mapOf("stateKey" to "k1"))
            betaRow.shouldNotBeNull()["counter"] shouldBe 2L
            betaRow[PF.lastTranId] shouldNotBe alphaTranId
        }
    }

    /**
     * Naming no table where several exist is refused rather than guessed. Picking the first would be a
     * working transaction against the wrong lock -- see the class comment for why that is the bad outcome.
     */
    "a transaction that names no table on a multi-table topic is refused" {
        val cxt = bootCxt("multiAmbiguous")
        val sqlCxt = SqlTopicService.mkSqlCxt(cxt, "two")
        val e = shouldThrow<KdrException> {
            SqlTopicTranProvider.executeTopicTran(sqlCxt, "nameless", null, mapOf("stateKey" to "k1")) {}
        }
        e.fullMessage() shouldContain "must name the one it locks"
        e.fullMessage() shouldContain "AlphaState"
    }

    "naming a table the topic does not have is refused, and says what it does have" {
        val cxt = bootCxt("multiWrongName")
        val sqlCxt = SqlTopicService.mkSqlCxt(cxt, "two")
        val e = shouldThrow<KdrException> {
            SqlTopicTranProvider.executeTopicTran(
                sqlCxt, "wrong", null, mapOf("stateKey" to "k1"), tranTableName = "GammaState",
            ) {}
        }
        e.fullMessage() shouldContain "GammaState"
        e.fullMessage() shouldContain "AlphaState"
    }
})
