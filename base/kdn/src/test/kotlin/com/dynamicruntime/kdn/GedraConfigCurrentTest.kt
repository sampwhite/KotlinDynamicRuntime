package com.dynamicruntime.kdn

import com.dynamicruntime.common.context.KdrCxt
import com.dynamicruntime.common.gedra.ConfigCurrentRevisions
import com.dynamicruntime.common.gedra.GC
import com.dynamicruntime.common.gedra.GCT
import com.dynamicruntime.common.gedra.GCX
import com.dynamicruntime.common.gedra.GedraConfigCache
import com.dynamicruntime.common.gedra.GedraConfigLoadService
import com.dynamicruntime.common.gedra.GedraConfigService
import com.dynamicruntime.common.gedra.GedraConfigType
import com.dynamicruntime.common.gedra.GedraId
import com.dynamicruntime.common.gedra.gedraConfig
import com.dynamicruntime.common.gedra.gedraConfigTopic
import com.dynamicruntime.common.sql.PF
import com.dynamicruntime.common.sql.SqlStmtUtil
import com.dynamicruntime.common.sql.SqlTopicService
import com.dynamicruntime.common.util.toOptInstant
import com.dynamicruntime.common.util.toOptLong
import com.dynamicruntime.common.util.toOptStr
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.comparables.shouldBeGreaterThan
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe

/**
 * Only a config's **current** revisions -- its latest and its latest published -- are read by the loads that start
 * from nothing (issue #875); the history every publish leaves behind is flagged and skipped. The flag narrows what
 * is read and never decides which revision a client runs, so each case checks both: the flags, and that the reads
 * still answer with the right revision.
 */
class GedraConfigCurrentTest : StringSpec({

    fun writer(on: KdrCxt, client: String): KdrCxt = on.mkSubContext("currentWrite", client).also { it.userId = 8750L }

    fun write(on: KdrCxt, client: String, desc: String) = GedraConfigService.get(on).writeConfig(
        writer(on, client), gedraConfig(on, "main", "${client}config", client) { cfact("ready", "grp", desc) },
    )

    fun publish(on: KdrCxt, client: String) =
        GedraConfigService.get(on).publish(writer(on, client), GedraId.of(GedraConfigType.configDoc, client, "main"))

    fun classId(client: String) = GedraId.of(GedraConfigType.configDoc, client, "main").fullId

    /** Every revision row of [client]'s `main` config, straight from the table: history included. */
    fun rows(on: KdrCxt, client: String): List<Map<String, Any?>> {
        val sqlCxt = SqlTopicService.mkSqlCxt(on, gedraConfigTopic)
        val table = on.getSchema().tables.getValue(GCT.gedraConfig)
        val stmt = SqlStmtUtil.prepareSql(
            sqlCxt, "qCurrentTestRows", table.columns,
            "select * from t:${GCT.gedraConfig} where c:${GC.configId} = :${GC.configId}",
        )
        var out: List<Map<String, Any?>> = emptyList()
        sqlCxt.sqlDb.withSession(on) {
            out = sqlCxt.sqlDb.queryStatement(on, stmt, mapOf(GC.configId to classId(client)))
        }
        return out
    }

    /** The versions of [client]'s `main` config whose flag is [flag] (null for a row with none). */
    fun versionsFlagged(on: KdrCxt, client: String, flag: Boolean?): List<Long> =
        rows(on, client).filter { it[GC.isCurrent] == flag }.mapNotNull { it[GC.version].toOptLong() }

    /** Runs [sql] with [bind] straight against the table, as a hand edit or an older node would. */
    fun plant(on: KdrCxt, name: String, sql: String, bind: Map<String, Any?>) {
        val sqlCxt = SqlTopicService.mkSqlCxt(on, gedraConfigTopic)
        val table = on.getSchema().tables.getValue(GCT.gedraConfig)
        sqlCxt.sqlDb.withSession(on) {
            sqlCxt.sqlDb.executeStatement(on, SqlStmtUtil.prepareSql(sqlCxt, name, table.columns, sql), bind)
        }
    }

    "a publish demotes the published head it supersedes, and nothing else" {
        val cxt = Startup.mkTestBootCxt("currentFlags", "gedraConfigCurrentFlags")
        val client = "current875a"
        write(cxt, client, "v1")
        publish(cxt, client)
        write(cxt, client, "v2")                     // v1 published, v2 draft: both matter
        versionsFlagged(cxt, client, true) shouldContainExactlyInAnyOrder listOf(1L, 2L)
        val v1Before = rows(cxt, client).single { it[GC.version].toOptLong() == 1L }[PF.updatedAt].toOptInstant()

        publish(cxt, client)                         // v2 published: v1 is history
        versionsFlagged(cxt, client, true) shouldContainExactlyInAnyOrder listOf(2L)
        versionsFlagged(cxt, client, false) shouldContainExactlyInAnyOrder listOf(1L)
        // A demotion leaves the published revision's updatedAt alone.
        rows(cxt, client).single { it[GC.version].toOptLong() == 1L }[PF.updatedAt].toOptInstant() shouldBe v1Before

        // Reverting mints an editable copy of the head; the head stays the latest published, so both are current.
        val main = GedraId.of(GedraConfigType.configDoc, client, "main")
        GedraConfigService.get(cxt).revertToEditable(writer(cxt, client), main)
        versionsFlagged(cxt, client, true) shouldContainExactlyInAnyOrder listOf(2L, 3L)
        GedraConfigService.get(cxt).readLatest(writer(cxt, client), main).shouldNotBeNull().version shouldBe 3
    }

    // No delete exists yet, but it is the one thing that can make history current again; `refresh` handles it, and
    // advances the promoted row's updatedAt so a cache that skipped it hears about it.
    "a revision made current again is promoted, with its updatedAt advanced" {
        val cxt = Startup.mkTestBootCxt("currentPromote", "gedraConfigCurrentPromote")
        val client = "current875b"
        write(cxt, client, "v1")
        publish(cxt, client)
        write(cxt, client, "v2")
        publish(cxt, client)
        val v1Before = rows(cxt, client).single { it[GC.version].toOptLong() == 1L }[PF.updatedAt].toOptInstant()
        // v2 is soft-deleted, as a delete would: v1 is the latest again.
        plant(
            cxt, "plantCurrentDelete",
            "update t:${GCT.gedraConfig} set c:${PF.enabled} = false where c:${GC.gedraId} = :${GC.gedraId}",
            mapOf(GC.gedraId to "${classId(client)}~2"),
        )
        val sqlCxt = SqlTopicService.mkSqlCxt(cxt, gedraConfigTopic)
        val table = cxt.getSchema().tables.getValue(GCT.gedraConfig)
        sqlCxt.sqlDb.withSession(cxt) { ConfigCurrentRevisions.refresh(cxt, sqlCxt, table, classId(client)) }

        versionsFlagged(cxt, client, true) shouldContainExactlyInAnyOrder listOf(1L)
        val v1After = rows(cxt, client).single { it[GC.version].toOptLong() == 1L }[PF.updatedAt].toOptInstant()
        v1After.shouldNotBeNull() shouldBeGreaterThan v1Before.shouldNotBeNull()
    }

    // Across a restart: the boot load and the cache's first load read only current revisions, and still answer with
    // the current one. And a database from before the flag -- rows with none -- is backfilled at boot, no reset.
    "a restart skips history, and backfills a database from before the flag" {
        val db = mapOf("KDR_DB_NAME" to "gedraConfigCurrent_restart", "KDR_LOAD_STORED_CONFIG" to "true")
        val client = "current875c"
        val first = Startup.mkTestBootCxt("current1", "gedraConfigCurrent1", db)
        write(first, client, "v1")
        publish(first, client)
        write(first, client, "v2")
        publish(first, client)
        write(first, client, "v3")                   // v1 history, v2 published head, v3 draft

        val second = Startup.mkTestBootCxt("current2", "gedraConfigCurrent2", db)
        val cache = GedraConfigService.get(second).configCache.shouldNotBeNull()
        cache.checkRefresh(second)
        cache.snapshot.allByIndex(GCX.configId, classId(client)).map { it.value[GC.version].toOptLong() }
            .shouldContainExactlyInAnyOrder(listOf(2L, 3L))
        GedraConfigCache.revisionsOf(cache, classId(client)).shouldNotBeNull().let {
            it.latest[GC.version].toOptLong() shouldBe 3L
            it.latestPublished.shouldNotBeNull()[GC.version].toOptLong() shouldBe 2L
        }
        GedraConfigLoadService.get(second).loadedFor(client).single().cfacts.single().description shouldBe "v3"

        // Now as a database from before the flag: every row loses it, and the next boot puts it back.
        plant(
            second, "plantCurrentUnflag",
            "update t:${GCT.gedraConfig} set c:${GC.isCurrent} = null where c:${GC.configId} = :${GC.configId}",
            mapOf(GC.configId to classId(client)),
        )
        versionsFlagged(second, client, null) shouldContainExactlyInAnyOrder listOf(1L, 2L, 3L)
        val third = Startup.mkTestBootCxt("current3", "gedraConfigCurrent3", db)
        versionsFlagged(third, client, true) shouldContainExactlyInAnyOrder listOf(2L, 3L)
        versionsFlagged(third, client, false) shouldContainExactlyInAnyOrder listOf(1L)
        GedraConfigLoadService.get(third).loadedFor(client).single().cfacts.single().description shouldBe "v3"
        rows(third, client).mapNotNull { it[GC.gedraId].toOptStr() }.size shouldBe 3
    }
})
