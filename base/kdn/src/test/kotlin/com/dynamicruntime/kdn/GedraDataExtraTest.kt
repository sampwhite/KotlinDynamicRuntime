package com.dynamicruntime.kdn

import com.dynamicruntime.common.context.KdrCxt
import com.dynamicruntime.common.context.ReadScope
import com.dynamicruntime.common.gedra.GD
import com.dynamicruntime.common.gedra.GDT
import com.dynamicruntime.common.gedra.GE
import com.dynamicruntime.common.gedra.GT
import com.dynamicruntime.common.gedra.GedraDataService
import com.dynamicruntime.common.gedra.GedraDataType
import com.dynamicruntime.common.gedra.GedraEdit
import com.dynamicruntime.common.gedra.GedraEditAction
import com.dynamicruntime.common.gedra.GedraPatchTarget
import com.dynamicruntime.common.gedra.GedraService
import com.dynamicruntime.common.gedra.gedraDataTopic
import com.dynamicruntime.common.sql.PF
import com.dynamicruntime.common.sql.SqlStmtUtil
import com.dynamicruntime.common.sql.SqlTopicService
import com.dynamicruntime.common.sql.SqlTopicUtil
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain

/**
 * The forward-compatibility promise `GedraDataRow.extra` makes: a key under a gedra's `data` map that **this
 * node does not know** survives a round trip through a patch untouched (issue #533 review).
 *
 * Nothing in the codebase writes such a key yet -- the `#381` workflow keys that used to were retired -- so the
 * merge that keeps the promise (`row.extra + entries` in the patch's write) looks like dead code to anyone
 * reading it, and every test stays green if it is "simplified" to `mapOf(entries)`. This test is what makes
 * that simplification fail. The stray key is planted by raw SQL for exactly that reason: there is no producer to
 * plant it any other way, which is the whole point.
 *
 * Its own client, as `GedraDataCacheTest` explains: every test shares one in-memory database, and another spec
 * asserts exhaustively over the shared client's documents.
 */
class GedraDataExtraTest : StringSpec({
    val cxt = Startup.mkTestBootCxt("gedraExtra", "gedraExtraTest")

    val client = "gextraclient"
    val ownerId = 90101L
    val kind = GedraDataType.formDoc
    val scope = ReadScope.ofClient(client)

    fun service(): GedraDataService = GedraDataService.get(cxt)

    /** A context acting as the owner inside this spec's client. */
    fun asOwner(): KdrCxt = cxt.mkSubContext("gextra", client).also { it.userId = ownerId }

    "a data key this node does not know survives a patch untouched" {
        val id = service().createGedra(
            asOwner(), kind,
            listOf(mapOf(GE.traitId to GT.name, GE.data to mapOf(GT.name to "Before patch"))),
        ).gedraId.fullId
        val stored = service().queryGedra(cxt, id, kind, scope).shouldNotBeNull()

        // Plant a key a newer node might write, beside the entries. `updatedAt` is advanced with the same
        // monotonic bump the patch uses, so the cache's incremental reload sees the planted row rather than
        // serving the pre-plant copy it already holds.
        val stray = "futureKey"
        val strayValue = mapOf("from" to "a newer node")
        val sqlCxt = SqlTopicService.mkSqlCxt(cxt, gedraDataTopic)
        val table = cxt.getSchema().tables.getValue(GDT.gedraData)
        val plant = SqlStmtUtil.prepareSql(
            sqlCxt, "plantExtraKey", table.columns,
            "update t:${GDT.gedraData} set c:${GD.data} = :${GD.data}, c:${PF.updatedAt} = :${PF.updatedAt} " +
                "where c:${GD.gedraId} = :${GD.gedraId}",
        )
        sqlCxt.sqlDb.withSession(cxt) {
            sqlCxt.sqlDb.executeStatement(
                cxt, plant,
                mapOf(
                    GD.gedraId to id,
                    GD.data to mapOf(GD.entries to stored.entries, stray to strayValue),
                    PF.updatedAt to SqlTopicUtil.nextUpdatedAt(cxt, stored.updatedAt),
                ),
            ) shouldBe 1
        }
        service().dataCache.shouldNotBeNull().checkRefresh(cxt)

        // The read half: `extra` is the stored map minus the promoted `entries`, so the planted key is here
        // and the entries are not.
        val planted = service().queryGedra(cxt, id, kind, scope).shouldNotBeNull()
        planted.extra[stray] shouldBe strayValue
        planted.extra.containsKey(GD.entries) shouldBe false

        // The write half: a patch that knows only about entries must carry the key it does not know.
        val target = GedraPatchTarget(
            GedraService.get(cxt).readId(id),
            listOf(GedraEdit(GedraEditAction.addOrMerge, GT.name, data = mapOf(GT.name to "After patch"))),
        )
        service().patchGedras(cxt, mapOf(kind to listOf(target)), scope)

        val after = service().queryGedra(cxt, id, kind, scope).shouldNotBeNull()
        after.entries.single()[GE.data].toString() shouldContain "After patch"
        after.extra[stray] shouldBe strayValue
        after.extra.containsKey(GD.entries) shouldBe false
    }
})
