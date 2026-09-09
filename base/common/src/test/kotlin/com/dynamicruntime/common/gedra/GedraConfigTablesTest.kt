package com.dynamicruntime.common.gedra

import com.dynamicruntime.common.context.CL
import com.dynamicruntime.common.context.KdrCxt
import com.dynamicruntime.common.context.KdrSchemaStore
import com.dynamicruntime.common.schema.SCH
import com.dynamicruntime.common.schema.SCT
import com.dynamicruntime.common.schema.SFMT
import com.dynamicruntime.common.sql.PF
import com.dynamicruntime.common.sql.SqlTopicService
import com.dynamicruntime.common.sql.SqlTopicTranProvider
import com.dynamicruntime.common.sql.SqlTopicUtil
import com.dynamicruntime.common.sql.TableFeature
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe

/**
 * The gedra config tables (issue #612): their shape, and -- the part a declaration test cannot show -- that a
 * topic transaction really does lock the **revision class** on the root while the content row lives under the
 * **versioned** key, on a live in-memory database.
 */
class GedraConfigTablesTest : StringSpec({

    val tables = gedraConfigTables(KdrCxt.mkSimpleCxt("gedraConfigTablesDef"))
    fun table(name: String) = tables.single { it.tableName == name }

    "three tables, on a topic of their own" {
        tables.map { it.tableName } shouldContainExactly
            listOf(GCT.gedraConfigTran, GCT.gedraConfig, GCT.gedraConfigControl)
        tables.map { it.topic }.toSet() shouldBe setOf(gedraConfigTopic)
        // Apart from the data topic on purpose: a config write must never contend with a data write.
        gedraConfigTopic shouldNotBe gedraDataTopic
    }

    // The protection-tier control table (issue #617): one row per (client, environment), owned by the client,
    // and NOT transactional -- a second transactional table in this topic would force every config write to
    // name the lock it takes, and a tier toggle is a plain keyed upsert with no revision race to serialize.
    "the control table is keyed by client and environment, owned by a client, and not transactional" {
        val control = table(GCT.gedraConfigControl)
        control.primaryKey shouldBe listOf(PF.client, GC.environment)
        control.isTransactional shouldBe false
        control.features shouldBe setOf(TableFeature.client)
        control.columnsByName.getValue(GC.publishedOnly).schema[SCH.type] shouldBe SCT.boolean
    }

    "the root is keyed by the revision class, carries the lock, and is owned by a client and nothing narrower" {
        val root = table(GCT.gedraConfigTran)
        root.primaryKey shouldBe listOf(GC.configId)
        root.isTransactional shouldBe true
        // Exactly these: no user or org column, so a user- or org-scoped read faults rather than widening.
        root.features shouldBe setOf(TableFeature.client, TableFeature.transactions)
        root.columnsByName.getValue(PF.client).required shouldBe true
    }

    "the content row is keyed by the versioned id, with the class and version as an indexed predicate" {
        val content = table(GCT.gedraConfig)
        content.primaryKey shouldBe listOf(GC.gedraId)
        content.isTransactional shouldBe false
        content.features shouldBe setOf(TableFeature.client)
        content.columnsByName.getValue(GC.version).schema[SCH.type] shouldBe SCT.integer
        // `publishedAt` is the published flag and the audit date in one: a nullable timestamp.
        content.columnsByName.getValue(GC.publishedAt).schema[SCH.format] shouldBe SFMT.dateTime
        content.columnsByName.getValue(GC.publishedAt).required shouldBe false
        content.columnsByName.getValue(GC.data).schema[SCH.type] shouldBe SCT.kObject
        content.indexes.map { it.fieldNames } shouldContainExactly
            listOf(listOf(GC.configId, GC.version), listOf(PF.updatedAt))
    }

    // The point of the two-key design, proven rather than described: one transaction locks the class row on
    // the root and writes a revision row under a different key on the content table.
    "a write locks the revision class on the root and stores a revision under its own versioned key" {
        val cxt = KdrCxt.mkSimpleCxt("gedraConfigTablesTran")
        cxt.instanceConfig.put(KdrSchemaStore.key, KdrSchemaStore(tables = tables.associateBy { it.tableName }))
        val service = SqlTopicService()
        cxt.instanceConfig.put(SqlTopicService.serviceName, service)
        service.checkInit(cxt)

        val classId = GedraId.of(GedraConfigType.configDoc, "acme", "main")
        val rev3 = classId.withRevision(3)
        val content = cxt.getSchema().tables.getValue(GCT.gedraConfig)
        val sqlCxt = SqlTopicService.mkSqlCxt(cxt, gedraConfigTopic)
        // The lock is taken on the class; the row written inside it is keyed by the revision.
        SqlTopicTranProvider.executeTopicTran(sqlCxt, "storeRevision", null, mapOf(GC.configId to classId.fullId)) {
            val row = mutableMapOf<String, Any?>(
                GC.gedraId to rev3.fullId,
                GC.configId to classId.fullId,
                GC.version to 3L,
                GC.publishedAt to null,
                GC.data to mapOf("traits" to emptyMap<String, Any?>()),
            )
            SqlTopicUtil.prepForStdExecute(cxt, content, row)
            sqlCxt.sqlDb.executeStatement(cxt, SqlTopicUtil.mkTableInsertStmt(sqlCxt, content), row)
        }

        val topic = SqlTopicService.get(cxt).getOrCreateTopic(cxt, gedraConfigTopic).shouldNotBeNull()
        topic.sqlDb.withSession(cxt) {
            // The lock row exists under the class key, stamped with the owning client...
            val lock = topic.sqlDb
                .queryOneStatement(cxt, topic.tranFor(null, "reread").queryLock, mapOf(GC.configId to classId.fullId))
                .shouldNotBeNull()
            lock[PF.client] shouldBe CL.hub
            // ...and the content row under the versioned key, carrying the class as a plain column.
            val stored = topic.sqlDb
                .queryOneEnabled(cxt, SqlTopicUtil.mkTableSelectStmt(sqlCxt, content), mapOf(GC.gedraId to rev3.fullId))
                .shouldNotBeNull()
            stored[GC.configId] shouldBe classId.fullId
            stored[GC.version] shouldBe 3L
            stored[GC.publishedAt].shouldBeNull()
            stored[PF.client] shouldBe CL.hub
        }
    }
})
