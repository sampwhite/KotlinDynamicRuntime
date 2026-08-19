package com.dynamicruntime.common.sql.cache

import com.dynamicruntime.common.context.KdrCxt
import com.dynamicruntime.common.context.KdrSchemaStore
import com.dynamicruntime.common.exception.KdrException
import com.dynamicruntime.common.sql.KdrTable
import com.dynamicruntime.common.sql.PF
import com.dynamicruntime.common.sql.SqlTopicService
import com.dynamicruntime.common.sql.SqlTopicUtil
import com.dynamicruntime.common.sql.tableModule
import com.dynamicruntime.common.util.toOptStr
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import kotlin.time.Duration.Companion.seconds

/**
 * Holds `.claude/skills/kdr-table-cache` to the code, by building its worked examples and checking the claims
 * it makes about them.
 *
 * A skill is read *instead of* working the API out, so a wrong one is followed rather than noticed. That risk
 * is sharper for this subsystem than most: its failures are silent by construction -- a cache that answers
 * from a stale snapshot returns a plausible row, and a consumer that never hears a row left keeps it forever.
 * Documentation that has quietly drifted would be followed straight into exactly those.
 *
 * **What this does not do:** it cannot read the Markdown, so it cannot prove the prose is right. It pins the
 * *examples and the behavioral claims* -- if you change the subsystem and land here, the skill needs the same
 * edit. Keep the code below a faithful transcription of what the skill shows; do not "improve" it past that.
 *
 * The subsystem itself is covered by [SqlTableCacheTest]. This is about the documentation.
 */
class TableCacheSkillExamplesTest : StringSpec({

    // --- a table to cache, standing in for the skill's AuthUsers ------------------------------------------

    val keyField = "recordKey"
    val ownerField = "owner"
    val labelField = "label"

    fun tables(tableName: String, topic: String): List<KdrTable> =
        tableModule(cxt = KdrCxt.mkSimpleCxt("def"), namespace = "skillNs", topic = topic) {
            table(tableName, "A record.") {
                column(keyField, "Key of the record.")
                column(ownerField, "Who owns it.")
                column(labelField, "Its unique label.")
                primaryKey(keyField)
                index(PF.updatedAt)
            }
        }

    fun bootCxt(cxtName: String, tables: List<KdrTable>): KdrCxt {
        val cxt = KdrCxt.mkSimpleCxt(cxtName)
        cxt.instanceConfig.put(KdrSchemaStore.key, KdrSchemaStore(tables = tables.associateBy { it.tableName }))
        val service = SqlTopicService()
        cxt.instanceConfig.put(SqlTopicService.serviceName, service)
        service.checkInit(cxt)
        return cxt
    }

    fun write(
        cxt: KdrCxt, table: KdrTable, key: String, owner: String, label: String,
        enabled: Boolean = true, isUpdate: Boolean = false,
    ) {
        cxt.instanceConfig.clock.advanceBy(1.seconds)
        val sqlCxt = SqlTopicService.mkSqlCxt(cxt, table.topic)
        val stmt = if (isUpdate) SqlTopicUtil.mkTableUpdateStmt(sqlCxt, table)
        else SqlTopicUtil.mkTableInsertStmt(sqlCxt, table)
        val row = mutableMapOf<String, Any?>(keyField to key, ownerField to owner, labelField to label)
        SqlTopicUtil.prepForStdExecute(cxt, table, row)
        row[PF.enabled] = enabled
        sqlCxt.sqlDb.withSession(cxt) { sqlCxt.sqlDb.executeStatement(cxt, stmt, row) }
    }

    /**
     * Transcribed from the skill's "Declaring a cache" section: the raw row map as payload, a unique index and
     * a non-unique one, keys read with `toOptStr`.
     */
    fun params(table: KdrTable) = SqlCacheParams(
        topic = table.topic,
        tableName = table.tableName,
        extract = { _, data -> data },
        indexes = listOf(
            SqlCacheIndex(labelField, unique = true) { it[labelField].toOptStr() },
            SqlCacheIndex(ownerField) { it[ownerField].toOptStr() },
        ),
    )

    "the declaring and reading examples compile and answer as the skill says" {
        val tbl = tables("SkillRead", "skillRead")
        val table = tbl.single()
        val cxt = bootCxt("skillRead", tbl)
        write(cxt, table, "r1", owner = "acme", label = "L1")
        write(cxt, table, "r2", owner = "acme", label = "L2")
        write(cxt, table, "r3", owner = "other", label = "L3", enabled = false)

        val cache = SqlTableCache(params(table))

        // "Refresh, then read the snapshot" -- verbatim from the skill's reading example.
        cache.checkRefresh(cxt)
        val snapshot = cache.snapshot
        snapshot.get(cache.idOf("r1")).shouldNotBeNull()
        snapshot.byIndex(labelField, "L2").shouldNotBeNull().id shouldBe cache.idOf("r2")
        snapshot.allByIndex(ownerField, "acme").map { it.id } shouldContainExactly
            listOf(cache.idOf("r1"), cache.idOf("r2"))

        // "Disabled rows are absent from every lookup."
        snapshot.get(cache.idOf("r3")).shouldBeNull()
        snapshot.byIndex(labelField, "L3").shouldBeNull()
        snapshot.allByIndex(ownerField, "other") shouldBe emptyList()

        // "A misspelled index name throws rather than returning null."
        shouldThrow<KdrException> { snapshot.byIndex("noSuchIndex", "L1") }
        shouldThrow<KdrException> { snapshot.allByIndex("noSuchIndex", "acme") }

        // "keyOf returning null leaves the row out of *that* index only."
        val partial = SqlTableCache(
            SqlCacheParams(
                topic = table.topic, tableName = table.tableName, extract = { _, data -> data },
                indexes = listOf(SqlCacheIndex("neverIndexed", unique = true) { null }),
            ),
        )
        partial.checkRefresh(cxt)
        partial.snapshot.byIndex("neverIndexed", "L1").shouldBeNull()
        partial.snapshot.get(partial.idOf("r1")).shouldNotBeNull() // still present by id
    }

    "the cursor example consumes changes once, with tombstones and departures" {
        val tbl = tables("SkillCursor", "skillCursor")
        val table = tbl.single()
        val cxt = bootCxt("skillCursor", tbl)
        write(cxt, table, "r1", owner = "acme", label = "C1")

        val cache = SqlTableCache(params(table))
        val cursor = SqlCacheCursor(cache)
        val scoped = SqlCacheCursor(cache, ownerField, "acme")

        // The skill's add/remove loop, transcribed.
        val derived = mutableMapOf<String, Map<String, Any?>>()
        for (row in cursor.nextChanges(cxt)) {
            if (row.enabled) derived[row.id] = row.value else derived.remove(row.id)
        }
        derived.keys shouldContainExactly listOf(cache.idOf("r1"))
        cursor.nextChanges(cxt) shouldBe emptyList() // "exactly once"

        scoped.nextChanges(cxt).map { it.id } shouldContainExactly listOf(cache.idOf("r1"))

        // "a row whose key changes arrives under its old key as a disabled copy"
        write(cxt, table, "r1", owner = "elsewhere", label = "C1", isUpdate = true)
        val departure = scoped.nextChanges(cxt)
        departure.single().id shouldBe cache.idOf("r1")
        departure.single().enabled shouldBe false
        // ...and the whole-cache cursor sees it as a live row, since it did not go away.
        cursor.nextChanges(cxt).single().enabled shouldBe true

        // "a removed row arrives with enabled = false"
        write(cxt, table, "r1", owner = "elsewhere", label = "C1", enabled = false, isUpdate = true)
        for (row in cursor.nextChanges(cxt)) {
            if (row.enabled) derived[row.id] = row.value else derived.remove(row.id)
        }
        derived shouldBe emptyMap()

        // "an index name without a key (or the reverse) throws"
        shouldThrow<KdrException> { SqlCacheCursor(cache, ownerField, null) }
        shouldThrow<KdrException> { SqlCacheCursor(cache, null, "acme") }
    }

    /**
     * The skill's third gating question: the reload **skips a row stamped at or before the version it already
     * holds**. This is the claim a would-be cacher checks their write path against, so it is pinned here.
     */
    "a row must advance updatedAt to be seen, which is what prepDates guarantees" {
        val tbl = tables("SkillStamp", "skillStamp")
        val table = tbl.single()
        val cxt = bootCxt("skillStamp", tbl)
        write(cxt, table, "r1", owner = "acme", label = "S1")

        val cache = SqlTableCache(params(table))
        cache.checkRefresh(cxt)
        cache.snapshot.byIndex(labelField, "S1").shouldNotBeNull()

        // A read-modify-write carries the prior updatedAt, so prepDates advances it and the reload sees it --
        // even with the clock held still, which is the case the guarantee exists for.
        val sqlCxt = SqlTopicService.mkSqlCxt(cxt, table.topic)
        val current = cache.snapshot.get(cache.idOf("r1")).shouldNotBeNull().value.toMutableMap()
        current[labelField] = "S1x"
        SqlTopicUtil.prepForStdExecute(cxt, table, current)
        sqlCxt.sqlDb.withSession(cxt) {
            sqlCxt.sqlDb.executeStatement(cxt, SqlTopicUtil.mkTableUpdateStmt(sqlCxt, table), current)
        }
        cache.checkRefresh(cxt)
        cache.snapshot.byIndex(labelField, "S1x").shouldNotBeNull().id shouldBe cache.idOf("r1")
        cache.snapshot.byIndex(labelField, "S1").shouldBeNull()
    }

    /**
     * The skill's "Is it current right now?" example. The claim that carries it is that **asking does not
     * refresh** -- without that the check could not be used in the middle of checking something else, and a
     * test using it would be measuring its own question.
     */
    "the refresh-state example answers without refreshing" {
        val tbl = tables("SkillState", "skillState") + SqlTableCacheService.tables(KdrCxt.mkSimpleCxt("def"))
        val table = tbl.first { it.tableName == "SkillState" }
        val cxt = bootCxt("skillState", tbl)
        val service = SqlTableCacheService()
        cxt.instanceConfig.put(SqlTableCacheService.serviceName, service)
        service.checkInit(cxt)
        val cache = service.register(params(table))
        write(cxt, table, "r1", owner = "acme", label = "T1")

        // The skill's `SqlTableCacheService.get(cxt).refreshState(cxt)` -- get throws if the service is absent.
        val state = SqlTableCacheService.get(cxt).refreshState(cxt)
        state.need shouldBe SqlCacheRefreshNeed.neverRefreshed
        state.isRefreshed shouldBe false
        state.needsRefresh shouldBe true
        cache.isLoaded shouldBe false // "Asking does not refresh."

        // "To act on the answer, service.checkRefresh(cxt) sweeps unconditionally."
        service.checkRefresh(cxt)
        cache.snapshot.byIndex(labelField, "T1").shouldNotBeNull()

        // "`changed` after your own write is the expected reading" -- the edit-and-check loop itself.
        SqlTableCacheService.getAndRefresh(cxt)
        service.refreshState(cxt).need shouldBe SqlCacheRefreshNeed.current
        write(cxt, table, "r2", owner = "acme", label = "T2")
        val afterWrite = service.refreshState(cxt)
        afterWrite.need shouldBe SqlCacheRefreshNeed.changed
        afterWrite.pendingTables shouldContainExactly listOf("SkillState")

        // "needsRefresh is not !isRefreshed": switched off, neither holds.
        service.isDisabled = true
        service.refreshState(cxt).isRefreshed shouldBe false
        service.refreshState(cxt).needsRefresh shouldBe false
    }
})
