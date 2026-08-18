package com.dynamicruntime.kdn

import com.dynamicruntime.common.context.KdrCxt
import com.dynamicruntime.common.context.ReadScope
import com.dynamicruntime.common.gedra.GD
import com.dynamicruntime.common.gedra.GDX
import com.dynamicruntime.common.gedra.GE
import com.dynamicruntime.common.gedra.GT
import com.dynamicruntime.common.gedra.GedraDataCache
import com.dynamicruntime.common.gedra.GedraDataService
import com.dynamicruntime.common.gedra.GedraDataType
import com.dynamicruntime.common.sql.PF
import com.dynamicruntime.common.util.toOptStr
import io.kotest.assertions.withClue
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldContainAll
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe

/**
 * The `GedraData` cache: that it holds what it should, indexes it the way the application is organized, and
 * -- the assertion the design rests on -- **answers a scoped by-id read identically to the SQL path it
 * replaces**. A cache over user content is only safe if it cannot widen an answer, so the scope cases are the
 * point rather than the coverage.
 *
 * Everything here is created in this spec's **own client**, not `CL.public`. Every test in a run shares one
 * in-memory database, and `GedraDataEndpointTest` asserts *exhaustively* over the documents a client-scoped
 * administrator sees in `CL.public` -- so a document created here would fail that test instead of this one.
 * Rows are written through `GedraDataService.createGedra` on a sub-context bound to this client, which keeps
 * the ids, the envelope and the storage entirely real while leaving the shared client alone.
 */
class GedraDataCacheTest : StringSpec({
    val cxt = Startup.mkTestBootCxt("gedraCache", "gedraCacheTest")

    val client = "gcacheclient"
    val caraId = 90001L
    val cyrusId = 90002L

    fun service(): GedraDataService = GedraDataService.get(cxt).shouldNotBeNull()

    /** A context acting as [userId] inside this spec's client. */
    fun asUser(userId: Long): KdrCxt = cxt.mkSubContext("gcache", client).also { it.userId = userId }

    fun createDoc(userId: Long, name: String): String =
        service().createGedra(
            asUser(userId),
            GedraDataType.formDoc,
            listOf(mapOf(GE.traitId to GT.name, GE.data to mapOf(GT.name to name))),
        ).gedraId.fullId

    var caraDocId = ""
    var cyrusDocId = ""

    "the cache loads gedras and indexes them by client and by client+kind" {
        caraDocId = createDoc(caraId, "Cara's doc")
        cyrusDocId = createDoc(cyrusId, "Cyrus's doc")

        val cache = service().dataCache.shouldNotBeNull()
        cache.checkRefresh(cxt)

        GedraDataCache.rowsForClient(cache, client).map { it.value[GD.gedraId].toOptStr() } shouldContainAll
            listOf(caraDocId, cyrusDocId)
        GedraDataCache.rowsForClientKind(cache, client, GedraDataType.formDoc.name)
            .map { it.value[GD.gedraId].toOptStr() } shouldContainAll listOf(caraDocId, cyrusDocId)

        // No userId index, deliberately: an owner's gedra is reached by building its id, which is a
        // primary-key hit. The row still carries the owner, which is what the scope check reads.
        val row = cache.snapshot.get(cache.idOf(caraDocId)).shouldNotBeNull()
        row.value[PF.userId] shouldBe caraId
        row.value[PF.client] shouldBe client
    }

    /**
     * For every scope shape, the cached answer equals the SQL answer -- including on refusal. `queryGedra` is
     * cache-first, so the first call exercises the cache; detaching the cache and re-asking exercises SQL.
     */
    "a scoped by-id read answers the same from cache as from SQL" {
        val svc = service()
        val kind = GedraDataType.formDoc
        val scopes = listOf(
            "unrestricted" to ReadScope.unrestricted,
            "own client" to ReadScope(client = client),
            "owner" to ReadScope(client = client, userId = caraId),
            "another user" to ReadScope(client = client, userId = cyrusId),
            "another client" to ReadScope(client = "someOtherClient"),
        )
        for ((name, scope) in scopes) {
            val fromCache = svc.queryGedra(cxt, caraDocId, kind, scope)
            val held = svc.dataCache
            svc.dataCache = null
            val fromSql = try {
                svc.queryGedra(cxt, caraDocId, kind, scope)
            } finally {
                svc.dataCache = held
            }
            withClue(name) { fromCache?.gedraId?.fullId shouldBe fromSql?.gedraId?.fullId }
        }

        // Spelled out, so a failure above reads as something concrete.
        svc.queryGedra(cxt, caraDocId, kind, ReadScope(client = client, userId = caraId)).shouldNotBeNull()
        svc.queryGedra(cxt, caraDocId, kind, ReadScope(client = client, userId = cyrusId)).shouldBeNull()
        svc.queryGedra(cxt, caraDocId, kind, ReadScope(client = "someOtherClient")).shouldBeNull()
    }

    "a document created after the first load is picked up; an unwritten key is empty" {
        val svc = service()
        val laterDocId = createDoc(caraId, "A later doc")

        val cache = svc.dataCache.shouldNotBeNull()
        cache.checkRefresh(cxt)
        cache.snapshot.get(cache.idOf(laterDocId)).shouldNotBeNull()
        GedraDataCache.rowsForClientKind(cache, client, GedraDataType.formDoc.name)
            .map { it.value[GD.gedraId].toOptStr() } shouldContainAll
            listOf(caraDocId, cyrusDocId, laterDocId)

        GedraDataCache.rowsForClient(cache, "noSuchClient") shouldBe emptyList()
        cache.snapshot.allByIndex(GDX.clientKind, GedraDataCache.clientKindKey(client, "noSuchKind")) shouldBe
            emptyList()
    }
})
