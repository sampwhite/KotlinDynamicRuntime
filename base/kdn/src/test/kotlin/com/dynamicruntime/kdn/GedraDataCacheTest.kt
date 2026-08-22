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
import com.dynamicruntime.common.gedra.GedraEdit
import com.dynamicruntime.common.gedra.GedraEditAction
import com.dynamicruntime.common.gedra.GedraPatchTarget
import com.dynamicruntime.common.gedra.GedraService
import com.dynamicruntime.common.sql.PF
import com.dynamicruntime.common.util.toOptStr
import io.kotest.assertions.withClue
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldContainAll
import io.kotest.matchers.collections.shouldNotContain
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import kotlin.time.Duration.Companion.seconds

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

    fun service(): GedraDataService = GedraDataService.get(cxt)

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

    // --- slice 2: the listing, served from the clientKind index (issue #363) ------------------------------

    /**
     * Lists gedras of [kind] within [scope], through whichever path is wired: with the cache in place it is
     * cache-first, and nulling `dataCache` around a second call forces the SQL it must equal.
     */
    fun listIds(kind: GedraDataType, scope: ReadScope, limit: Int, viaSql: Boolean = false): List<String> {
        val svc = service()
        if (!viaSql) return svc.listGedras(cxt, kind, scope, limit).rows.map { it.gedraId.fullId }
        val held = svc.dataCache
        svc.dataCache = null
        return try {
            svc.listGedras(cxt, kind, scope, limit).rows.map { it.gedraId.fullId }
        } finally {
            svc.dataCache = held
        }
    }

    /**
     * The whole of slice 2: a client-scoped listing served from the `clientKind` index must equal the SQL
     * listing it replaces **row for row and in order**, because the page a caller sees is
     * `order by createdAt desc, gedraId desc` and then a cap -- a cache that returned the same set in another
     * order would be a different, wrong page.
     */
    "a client-scoped listing matches SQL row for row and in order" {
        val kind = GedraDataType.formDoc
        val scope = ReadScope.ofClient(client)

        val fromCache = listIds(kind, scope, 100)
        val fromSql = listIds(kind, scope, 100, viaSql = true)

        fromCache shouldBe fromSql
        fromCache shouldContainAll listOf(caraDocId, cyrusDocId)

        // Independently of SQL: the order really is newest-first, id breaking a tie. Proven against the rows'
        // own dates rather than trusting the reference, since "match SQL" and "be right" should both hold.
        val rows = service().listGedras(cxt, kind, scope, 100).rows
        val resorted = rows.sortedWith(
            compareByDescending<com.dynamicruntime.common.gedra.GedraDataRow> { it.createdAt }
                .thenByDescending { it.gedraId.fullId },
        )
        rows.map { it.gedraId.fullId } shouldBe resorted.map { it.gedraId.fullId }
    }

    /**
     * The one question slice 2 had to answer: the cap applies **after** the scope filter, not before. A scope
     * naming a client and a user is cache-served (the client keys the index) and drops the other user's rows,
     * so it is the case that tells the two orders apart -- if the cap ran first, the newest few rows overall
     * could be the wrong user's and the caller would get fewer of their own, or none.
     */
    "the limit caps the scoped list, and the scope is applied first" {
        val kind = GedraDataType.formDoc
        // Interleave the two users, stepping the clock between so createdAt strictly orders them and Cyrus's
        // is unambiguously newest -- the tie-break is Test C's job, not this one's.
        val clock = cxt.instanceConfig.clock
        createDoc(caraId, "Cara A"); clock.advanceBy(1.seconds)
        createDoc(cyrusId, "Cyrus A"); clock.advanceBy(1.seconds)
        createDoc(caraId, "Cara B"); clock.advanceBy(1.seconds)
        val newestCyrus = createDoc(cyrusId, "Cyrus B")
        service().dataCache.shouldNotBeNull().checkRefresh(cxt)

        // The client-wide newest row is Cyrus's -- the interleaving is real, so "cap then filter" would drop
        // a Cara row the caller is owed.
        listIds(kind, ReadScope.ofClient(client), 1) shouldBe listOf(newestCyrus)

        // The Cara-scoped newest two are therefore both Cara's only if the userId filter runs before the cap.
        val caraScope = ReadScope(client = client, userId = caraId)
        val caraNewest2 = service().listGedras(cxt, kind, caraScope, 2).rows
        caraNewest2.size shouldBe 2
        caraNewest2.map { it.userId }.toSet() shouldBe setOf(caraId)

        // Cache equals SQL, and equals the front of the full Cara-scoped list -- the cap is just a prefix.
        caraNewest2.map { it.gedraId.fullId } shouldBe listIds(kind, caraScope, 2, viaSql = true)
        caraNewest2.map { it.gedraId.fullId } shouldBe listIds(kind, caraScope, 100).take(2)
    }

    /**
     * Two gedras sharing a `createdAt` -- the case the id tiebreak exists for -- page by id, the same way from
     * cache and from SQL. The clock is frozen so the pair genuinely collides; without the tiebreak their order
     * would be whichever each side happened to produce.
     */
    "gedras sharing a createdAt page by id, identically from cache and SQL" {
        val kind = GedraDataType.formDoc
        val clock = cxt.instanceConfig.clock
        clock.freeze()
        try {
            val a = createDoc(caraId, "Tie one")
            val b = createDoc(caraId, "Tie two")
            service().dataCache.shouldNotBeNull().checkRefresh(cxt)
            val earlier = maxOf(a, b) // order by gedraId desc, so the lexically larger id comes first
            val later = minOf(a, b)

            val fromCache = listIds(kind, ReadScope.ofClient(client), 100)
            val fromSql = listIds(kind, ReadScope.ofClient(client), 100, viaSql = true)
            fromCache shouldBe fromSql
            (fromCache.indexOf(earlier) < fromCache.indexOf(later)) shouldBe true
        } finally {
            clock.unfreeze()
        }
    }

    /**
     * A scope the `clientKind` index cannot key on -- an `allClients` administrator over every client, or an
     * ordinary user whose scope carries only a `userId` -- falls back to SQL rather than being served wrong.
     * The point is that the fallback still answers, and answers correctly.
     */
    "a scope with no client falls back to SQL and still lists correctly" {
        val kind = GedraDataType.formDoc

        // Unrestricted: every client, so this client's rows are a subset of what comes back.
        service().listGedras(cxt, kind, ReadScope.unrestricted, 500).rows.map { it.gedraId.fullId } shouldContainAll
            listOf(caraDocId, cyrusDocId)

        // Own-user, client-less: only Cara's rows, and never Cyrus's.
        val caraOnly = service().listGedras(cxt, kind, ReadScope.ofUser(caraId), 500).rows
        caraOnly.map { it.userId }.toSet() shouldBe setOf(caraId)
        caraOnly.map { it.gedraId.fullId } shouldNotContain cyrusDocId
    }

    // --- updatedAt monotonicity: a write must stay visible to the cache even within one millisecond ---------

    /**
     * A patch landing in the same millisecond as the gedra's previous write must still be picked up by the
     * cache. The cache reloads by walking `updatedAt` forward and skips a row stamped at or before the version
     * it holds, so without `SqlTopicUtil.nextUpdatedAt` forcing the advance, the clock frozen here would make
     * the patch invisible to cached reads until the gedra's next write.
     */
    "a patch in the same millisecond stays visible to the cache" {
        val svc = service()
        val kind = GedraDataType.formDoc
        val scope = ReadScope.ofClient(client)
        val clock = cxt.instanceConfig.clock
        clock.freeze()
        try {
            val id = createDoc(caraId, "Before patch")
            svc.queryGedra(cxt, id, kind, scope).shouldNotBeNull().entries
                .first()[GE.data].toString() shouldContain "Before patch"

            // Patch the name, with the clock still frozen at the create's millisecond.
            val target = GedraPatchTarget(
                GedraService.get(cxt).readId(id),
                listOf(GedraEdit(GedraEditAction.addOrMerge, GT.name, data = mapOf(GT.name to "After patch"))),
            )
            svc.patchGedras(cxt, mapOf(kind to listOf(target)), scope)

            // The cached read reflects the patch -- it would still show "Before patch" if updatedAt had not
            // advanced past the frozen create stamp.
            svc.queryGedra(cxt, id, kind, scope).shouldNotBeNull().entries
                .first()[GE.data].toString() shouldContain "After patch"
        } finally {
            clock.unfreeze()
        }
    }

    /**
     * The delete case, which is worse than the patch case: a disabled gedra never gets a later write to
     * correct a missed `updatedAt`, so a delete that did not advance it would leave the gedra readable from
     * cache **forever**. Frozen clock forces the same-millisecond collision the fix has to survive.
     */
    "a delete in the same millisecond removes the gedra from the cache" {
        val svc = service()
        val kind = GedraDataType.formDoc
        val scope = ReadScope.ofClient(client)
        val clock = cxt.instanceConfig.clock
        clock.freeze()
        try {
            val id = createDoc(caraId, "To be deleted")
            svc.queryGedra(cxt, id, kind, scope).shouldNotBeNull()

            svc.deleteGedra(cxt, id, kind, scope) shouldBe true

            // Gone from the cached read too, not just from SQL: the disable advanced updatedAt, so the cache
            // saw the tombstone rather than skipping a same-stamped row.
            svc.queryGedra(cxt, id, kind, scope).shouldBeNull()
        } finally {
            clock.unfreeze()
        }
    }

    /**
     * `listGedras` pages by limit + offset and reports the total the scope admits (issue #408), the same from
     * the cache (a client scope) and from SQL (a client-less own-user scope). Its own user so the exact page
     * assertions do not depend on the other blocks' documents; the clock advances between creates so the order
     * is unambiguous newest-first (pen3, pen2, pen1).
     */
    "listGedras pages with limit + offset and reports the total available" {
        val kind = GedraDataType.formDoc
        val clock = cxt.instanceConfig.clock
        val penId = 90009L
        val pen1 = createDoc(penId, "Pen one"); clock.advanceBy(1.seconds)
        val pen2 = createDoc(penId, "Pen two"); clock.advanceBy(1.seconds)
        val pen3 = createDoc(penId, "Pen three")
        service().dataCache.shouldNotBeNull().checkRefresh(cxt)

        // Cache path: a client scope keys the clientKind index. First page of two, then the remainder; the
        // total is 3 both times, and hasMore is the caller's `offset + page < numAvailable`.
        val penScope = ReadScope(client = client, userId = penId)
        val p1 = service().listGedras(cxt, kind, penScope, 2, 0)
        p1.rows.map { it.gedraId.fullId } shouldBe listOf(pen3, pen2)
        p1.numAvailable shouldBe 3
        val p2 = service().listGedras(cxt, kind, penScope, 2, 2)
        p2.rows.map { it.gedraId.fullId } shouldBe listOf(pen1)
        p2.numAvailable shouldBe 3

        // SQL path: a client-less own-user scope cannot key the index, so it falls back -- and pages the same.
        val ownScope = ReadScope.ofUser(penId)
        val held = service().dataCache
        service().dataCache = null
        try {
            val s1 = service().listGedras(cxt, kind, ownScope, 2, 0)
            s1.rows.map { it.gedraId.fullId } shouldBe listOf(pen3, pen2)
            s1.numAvailable shouldBe 3
            service().listGedras(cxt, kind, ownScope, 2, 2).rows.map { it.gedraId.fullId } shouldBe listOf(pen1)
        } finally {
            service().dataCache = held
        }
    }
})
