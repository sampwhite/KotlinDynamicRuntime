package com.dynamicruntime.common.sql.cache

import com.dynamicruntime.common.context.KdrCxt
import com.dynamicruntime.common.exception.KdrException
import com.dynamicruntime.common.context.KdrSchemaStore
import com.dynamicruntime.common.sql.KdrTable
import com.dynamicruntime.common.sql.PF
import com.dynamicruntime.common.sql.SqlStmtUtil
import com.dynamicruntime.common.sql.SqlTopicService
import com.dynamicruntime.common.sql.SqlTopicUtil
import com.dynamicruntime.common.sql.tableModule
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

/** The payload a test cache holds; nothing about it is special, which is the point (issue: table caches). */
private class Gadget(val key: String, val owner: String, val label: String)

private const val gadgetKeyField = "gadgetKey"
private const val ownerField = "owner"
private const val labelField = "label"

/**
 * Coverage for [SqlTableCache]: the incremental reload, the snapshot's lookups and indexes, the tombstone a
 * disabled row leaves for [SqlCacheCursor], and the shared state row [SqlTableCacheService] coordinates on.
 *
 * Each case declares its **own table and topic**. Every test in a module's run shares one in-memory H2
 * database (the URL carries `DB_CLOSE_DELAY=-1`), so a shared table name would let one case's rows show up in
 * another's assertions.
 *
 * The caches here are left **detached**, so each `checkRefresh` queries directly rather than going through the
 * service -- which is what makes the reload deterministic under test instead of gated on the state row.
 */
class SqlTableCacheTest : StringSpec({

    /** Publishes a schema store holding [tables] plus a live [SqlTopicService] on a fresh context. */
    fun bootCxt(cxtName: String, tables: List<KdrTable>): KdrCxt {
        val cxt = KdrCxt.mkSimpleCxt(cxtName)
        cxt.instanceConfig.put(KdrSchemaStore.key, KdrSchemaStore(tables = tables.associateBy { it.tableName }))
        val service = SqlTopicService()
        cxt.instanceConfig.put(SqlTopicService.serviceName, service)
        service.checkInit(cxt)
        return cxt
    }

    /** A one-table module: a key, an owner to index non-uniquely, and a label to index uniquely. */
    fun gadgetTables(tableName: String, topic: String): List<KdrTable> =
        tableModule(cxt = KdrCxt.mkSimpleCxt("def"), namespace = "gadgetNs", topic = topic) {
            table(tableName, "A gadget.") {
                column(gadgetKeyField, "Key of the gadget.")
                column(ownerField, "Who owns the gadget.")
                column(labelField, "The gadget's unique label.")
                primaryKey(gadgetKeyField)
                index(PF.updatedAt)
            }
        }

    fun gadgetCache(table: KdrTable): SqlTableCache<Gadget> = SqlTableCache(
        SqlCacheParams(
            topic = table.topic,
            tableName = table.tableName,
            extract = { _, data ->
                Gadget(
                    data[gadgetKeyField] as String, data[ownerField] as String, data[labelField] as String,
                )
            },
            indexes = listOf(
                SqlCacheIndex(labelField, unique = true) { it.label },
                SqlCacheIndex(ownerField) { it.owner },
            ),
        ),
    )

    /** Inserts or updates a row. The clock is advanced first so `updatedAt` genuinely moves between writes. */
    fun write(
        cxt: KdrCxt,
        table: KdrTable,
        key: String,
        owner: String,
        label: String,
        enabled: Boolean = true,
        isUpdate: Boolean = false,
    ) {
        cxt.instanceConfig.clock.advanceBy(1.seconds)
        val sqlCxt = SqlTopicService.mkSqlCxt(cxt, table.topic)
        val stmt = if (isUpdate) SqlTopicUtil.mkTableUpdateStmt(sqlCxt, table)
        else SqlTopicUtil.mkTableInsertStmt(sqlCxt, table)
        val row = mutableMapOf<String, Any?>(
            gadgetKeyField to key, ownerField to owner, labelField to label,
        )
        SqlTopicUtil.prepForStdExecute(cxt, table, row)
        // prepForStdExecute stamps enabled = true unconditionally (that is its "create" semantics), so a
        // deliberate soft delete has to say so afterwards -- exactly as UserService.updateUser does.
        row[PF.enabled] = enabled
        sqlCxt.sqlDb.withSession(cxt) { sqlCxt.sqlDb.executeStatement(cxt, stmt, row) }
    }

    "the first load takes the enabled rows and builds both kinds of index" {
        val tables = gadgetTables("GadgetLoad", "gcacheLoad")
        val table = tables.single()
        val cxt = bootCxt("cacheLoad", tables)
        write(cxt, table, "g1", owner = "alice", label = "L1")
        write(cxt, table, "g2", owner = "alice", label = "L2")
        write(cxt, table, "g3", owner = "bob", label = "L3", enabled = false)

        val cache = gadgetCache(table)
        cache.checkRefresh(cxt)
        cache.isLoaded shouldBe true

        val snapshot = cache.snapshot
        snapshot.size shouldBe 2 // the disabled row is not there at all
        snapshot.get(cache.idOf("g1")).shouldNotBeNull().value.label shouldBe "L1"
        snapshot.get(cache.idOf("g3")).shouldBeNull()

        // A unique index substitutes for a unique-index SQL lookup; a non-unique one lists every row sharing a key, in load order.
        snapshot.byIndex(labelField, "L2").shouldNotBeNull().id shouldBe cache.idOf("g2")
        snapshot.byIndex(labelField, "L3").shouldBeNull()
        snapshot.allByIndex(ownerField, "alice").map { it.id } shouldContainExactly
            listOf(cache.idOf("g1"), cache.idOf("g2"))
        snapshot.allByIndex(ownerField, "bob") shouldBe emptyList() // the disabled row's key holds nothing
    }

    "an update replaces the prior entry and moves its index keys with it" {
        val tables = gadgetTables("GadgetUpdate", "gcacheUpdate")
        val table = tables.single()
        val cxt = bootCxt("cacheUpdate", tables)
        write(cxt, table, "g1", owner = "alice", label = "L1")

        val cache = gadgetCache(table)
        cache.checkRefresh(cxt)
        cache.snapshot.size shouldBe 1

        // A row added after the first load arrives on the next pass -- the incremental query, not a re-read.
        write(cxt, table, "g2", owner = "bob", label = "L2")
        cache.checkRefresh(cxt)
        cache.snapshot.size shouldBe 2

        // Re-owning and re-labelling g1: it must move, not appear twice.
        write(cxt, table, "g1", owner = "bob", label = "L1x", isUpdate = true)
        cache.checkRefresh(cxt)

        val snapshot = cache.snapshot
        snapshot.size shouldBe 2
        snapshot.ordered.size shouldBe 2 // the superseded entry is gone, not stacked behind the new one
        snapshot.byIndex(labelField, "L1").shouldBeNull() // the old key no longer resolves
        snapshot.byIndex(labelField, "L1x").shouldNotBeNull().id shouldBe cache.idOf("g1")
        snapshot.allByIndex(ownerField, "alice") shouldBe emptyList()
        snapshot.allByIndex(ownerField, "bob").map { it.id } shouldContainExactlyInAnyOrder
            listOf(cache.idOf("g1"), cache.idOf("g2"))
    }

    "disabling a row removes it from lookups but hands a cursor the tombstone, once" {
        val tables = gadgetTables("GadgetCursor", "gcacheCursor")
        val table = tables.single()
        val cxt = bootCxt("cacheCursor", tables)
        write(cxt, table, "g1", owner = "alice", label = "C1")
        write(cxt, table, "g2", owner = "alice", label = "C2")

        val cache = gadgetCache(table)
        val cursor = SqlCacheCursor(cache)
        cursor.nextChanges(cxt).map { it.id } shouldContainExactly
            listOf(cache.idOf("g1"), cache.idOf("g2"))
        // Consumed: a second call with nothing new returns nothing.
        cursor.nextChanges(cxt) shouldBe emptyList()

        write(cxt, table, "g1", owner = "alice", label = "C1", enabled = false, isUpdate = true)
        cache.checkRefresh(cxt)

        // Gone from every lookup -- a soft delete reads as absent...
        cache.snapshot.size shouldBe 1
        cache.snapshot.get(cache.idOf("g1")).shouldBeNull()
        cache.snapshot.byIndex(labelField, "C1").shouldBeNull()
        cache.snapshot.allByIndex(ownerField, "alice").map { it.id } shouldContainExactly listOf(cache.idOf("g2"))

        // ...but a consumer maintaining its own structure is told, rather than left holding a stale entry.
        val removals = cursor.nextChanges(cxt)
        removals.size shouldBe 1
        removals.single().id shouldBe cache.idOf("g1")
        removals.single().enabled shouldBe false
        cursor.nextChanges(cxt) shouldBe emptyList()
    }

    /**
     * The mechanism that makes the announcement unforgettable: a statement records the tables its `t:` markers
     * name, and `SqlDatabase.executeStatement` announces those to the caches. Nothing else in the codebase
     * reads `tableNames`, so without this a refactor of the SQL parser could quietly empty it -- and the only
     * symptom would be caches going stale inside the request that wrote to them.
     */
    "a prepared statement records the tables it touches, however it was built" {
        val tables = gadgetTables("GadgetStmt", "gcacheStmt")
        val table = tables.single()
        val cxt = bootCxt("cacheStmt", tables)
        val sqlCxt = SqlTopicService.mkSqlCxt(cxt, table.topic)

        // The builders every standard write goes through.
        SqlTopicUtil.mkTableInsertStmt(sqlCxt, table).tableNames shouldContainExactly listOf("GadgetStmt")
        SqlTopicUtil.mkTableUpdateStmt(sqlCxt, table).tableNames shouldContainExactly listOf("GadgetStmt")

        // ...and a query written out by hand, which is the case an explicit per-caller announcement missed.
        val handWritten = SqlStmtUtil.prepareSql(
            sqlCxt, "qGadgetStmtByOwner", table.columns,
            "select * from t:GadgetStmt where c:$ownerField = :$ownerField",
        )
        handWritten.tableNames shouldContainExactly listOf("GadgetStmt")
    }

    /**
     * `withMonitoring` is the escape hatch for code that is not a request -- a script, a background job -- and
     * is the only path by which such code announces its writes to the other nodes. It had no test, which left
     * its subtle half (restoring a monitor that was already bound, so nesting is safe) resting on inspection.
     */
    "withMonitoring publishes what its body wrote, and restores an outer monitor on the way out" {
        val tables = gadgetTables("GadgetMonitor", "gcacheMonitor") +
            SqlTableCacheService.tables(KdrCxt.mkSimpleCxt("def"))
        val table = tables.first { it.tableName == "GadgetMonitor" }
        val cxt = bootCxt("cacheMonitor", tables)
        val service = SqlTableCacheService()
        cxt.instanceConfig.put(SqlTableCacheService.serviceName, service)
        service.checkInit(cxt) // also subscribes the write listener to SqlTopicService
        service.register(gadgetCache(table).params)

        // No monitor bound: the write still marks the local cache, but nothing reaches the shared row.
        write(cxt, table, "m1", owner = "alice", label = "M1")
        service.dbQueryState(cxt)["GadgetMonitor"].shouldBeNull()

        // Inside withMonitoring the same write is announced -- end to end, through the real write listener.
        service.withMonitoring(cxt) { write(cxt, table, "m2", owner = "alice", label = "M2") }
        service.dbQueryState(cxt)["GadgetMonitor"].shouldNotBeNull()

        // Nesting: an already-bound monitor is put back afterwards, so an outer request's accounting is not
        // silently dropped by an inner block.
        val outer = SqlTableCacheService.ChangeMonitor()
        cxt.locals[TCH.monitorKey] = outer
        service.withMonitoring(cxt) { write(cxt, table, "m3", owner = "bob", label = "M3") }
        cxt.locals[TCH.monitorKey] shouldBe outer
    }

    "a key-scoped cursor replays only its own key, independently of a whole-cache cursor" {
        val tables = gadgetTables("GadgetMultiCursor", "gcacheMultiCursor")
        val table = tables.single()
        val cxt = bootCxt("cacheMultiCursor", tables)
        write(cxt, table, "g1", owner = "alice", label = "K1")
        write(cxt, table, "g2", owner = "bob", label = "K2")
        write(cxt, table, "g3", owner = "alice", label = "K3")

        val cache = gadgetCache(table)
        val alice = SqlCacheCursor(cache, ownerField, "alice")
        val everything = SqlCacheCursor(cache)

        alice.nextChanges(cxt).map { it.id } shouldContainExactly listOf(cache.idOf("g1"), cache.idOf("g3"))
        alice.nextChanges(cxt) shouldBe emptyList()
        // The two cursors are separate positions over the same cache: draining one leaves the other whole.
        everything.nextChanges(cxt).map { it.id } shouldContainExactly
            listOf(cache.idOf("g1"), cache.idOf("g2"), cache.idOf("g3"))

        // A change under another key is not this one's business.
        write(cxt, table, "g2", owner = "bob", label = "K2x", isUpdate = true)
        alice.nextChanges(cxt) shouldBe emptyList()
        everything.nextChanges(cxt).map { it.id } shouldContainExactly listOf(cache.idOf("g2"))
    }

    /**
     * The case a filter over the whole stream cannot express, and the reason the per-key streams exist: the
     * row is still very much alive, so nothing about it is disabled -- but it is no longer alice's, and a
     * consumer of alice's rows has to be told that or it keeps the row forever.
     */
    "a row moving keys is a departure under the old key and an arrival under the new" {
        val tables = gadgetTables("GadgetMultiMove", "gcacheMultiMove")
        val table = tables.single()
        val cxt = bootCxt("cacheMultiMove", tables)
        write(cxt, table, "g1", owner = "alice", label = "M1")

        val cache = gadgetCache(table)
        val alice = SqlCacheCursor(cache, ownerField, "alice")
        val bob = SqlCacheCursor(cache, ownerField, "bob")
        alice.nextChanges(cxt).map { it.id } shouldContainExactly listOf(cache.idOf("g1"))
        bob.nextChanges(cxt) shouldBe emptyList()

        write(cxt, table, "g1", owner = "bob", label = "M1", isUpdate = true)

        // Alice is told it left -- as a disabled copy, the same signal a soft delete gives.
        val departure = alice.nextChanges(cxt)
        departure.size shouldBe 1
        departure.single().id shouldBe cache.idOf("g1")
        departure.single().enabled shouldBe false
        // Bob is told it arrived, live.
        val arrival = bob.nextChanges(cxt)
        arrival.size shouldBe 1
        arrival.single().id shouldBe cache.idOf("g1")
        arrival.single().enabled shouldBe true

        // And membership agrees with what the cursors just said.
        cache.snapshot.allByIndex(ownerField, "alice") shouldBe emptyList()
        cache.snapshot.allByIndex(ownerField, "bob").map { it.id } shouldContainExactly listOf(cache.idOf("g1"))
        // Consumed exactly once on both sides.
        alice.nextChanges(cxt) shouldBe emptyList()
        bob.nextChanges(cxt) shouldBe emptyList()
    }

    "disabling a row is a departure from its key, and an unknown key or index is caught" {
        val tables = gadgetTables("GadgetMultiOff", "gcacheMultiOff")
        val table = tables.single()
        val cxt = bootCxt("cacheMultiOff", tables)
        write(cxt, table, "g1", owner = "alice", label = "D1")

        val cache = gadgetCache(table)
        val alice = SqlCacheCursor(cache, ownerField, "alice")
        alice.nextChanges(cxt).size shouldBe 1

        write(cxt, table, "g1", owner = "alice", label = "D1", enabled = false, isUpdate = true)
        val removal = alice.nextChanges(cxt)
        removal.single().enabled shouldBe false
        cache.snapshot.allByIndex(ownerField, "alice") shouldBe emptyList()

        // A key nobody has ever written to is empty, not an error -- unlike a misspelled *index*, which is
        // a programming mistake that would otherwise read as "nothing is filed under this key".
        SqlCacheCursor(cache, ownerField, "nobody").nextChanges(cxt) shouldBe emptyList()
        shouldThrow<KdrException> { SqlCacheCursor(cache, "noSuchIndex", "alice").nextChanges(cxt) }
        // Half a key scope is refused outright rather than silently widening to the whole table.
        shouldThrow<KdrException> { SqlCacheCursor(cache, ownerField, null) }
        shouldThrow<KdrException> { SqlCacheCursor(cache, null, "alice") }
    }

    "the shared state row always advances on an announcement, so a lagging clock cannot be silenced" {
        val tables = gadgetTables("GadgetState", "gcacheState") +
            SqlTableCacheService.tables(KdrCxt.mkSimpleCxt("def"))
        val cxt = bootCxt("cacheState", tables)
        val service = SqlTableCacheService()
        cxt.instanceConfig.put(SqlTableCacheService.serviceName, service)
        service.checkInit(cxt)

        val early = Instant.fromEpochMilliseconds(1_700_000_000_000)
        val later = Instant.fromEpochMilliseconds(1_700_000_060_000)

        service.dbMergeState(cxt, mapOf("GadgetState" to later))["GadgetState"] shouldBe later
        service.dbQueryState(cxt)["GadgetState"] shouldBe later

        // A node whose clock lags announces with an "older" date -- but an announcement means "the table
        // changed *again*", so the row's date must still move, or the other nodes would never hear about the
        // lagging node's change. It advances past what the row held rather than adopting the older date.
        service.dbMergeState(cxt, mapOf("GadgetState" to early))
        service.dbQueryState(cxt)["GadgetState"] shouldBe (later + 1.milliseconds)

        // And a second table merges in without disturbing the first -- the reason this is a merge under a
        // topic lock rather than a write of whatever this node happens to know.
        service.dbMergeState(cxt, mapOf("OtherTable" to early))
        val state = service.dbQueryState(cxt)
        state["GadgetState"] shouldBe (later + 1.milliseconds)
        state["OtherTable"] shouldBe early
    }
})
