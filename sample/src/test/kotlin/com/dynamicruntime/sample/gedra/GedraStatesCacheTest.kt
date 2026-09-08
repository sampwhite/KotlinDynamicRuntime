package com.dynamicruntime.sample.gedra

import com.dynamicruntime.common.context.KdrCxt
import com.dynamicruntime.common.context.ReadScope
import com.dynamicruntime.common.gedra.GD
import com.dynamicruntime.common.gedra.GE
import com.dynamicruntime.common.gedra.GT
import com.dynamicruntime.common.gedra.GedraDataService
import com.dynamicruntime.common.gedra.GedraDataType
import com.dynamicruntime.common.gedra.GedraId
import com.dynamicruntime.common.gedra.GedraStatesCache
import com.dynamicruntime.common.sql.PF
import com.dynamicruntime.common.util.toJsonListOfMaps
import com.dynamicruntime.common.util.toJsonMapOrEmpty
import com.dynamicruntime.common.util.toOptStr
import com.dynamicruntime.kdn.Startup
import com.dynamicruntime.sample.SampleComponent
import io.kotest.assertions.withClue
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe

/**
 * The `GedraDataStates` cache (issue #598, phase C): that it holds what was written, indexes it by client, and
 * -- the assertion the design rests on -- **answers a scoped by-id state read identically to the SQL path it
 * replaces**. A cache over user content is safe only if it cannot widen an answer, so the scope cases are the
 * point. Its own client, as `GedraStateTest` explains: every test shares one in-memory database.
 */
class GedraStatesCacheTest : StringSpec({
    val cxt = Startup.mkTestBootCxt(
        "gedraStatesCache", "gedraStatesCacheTest", mapOf("KDR_LOAD_SAMPLE" to "true"),
        additionalComponents = listOf(SampleComponent()),
    )

    val client = "gstatecacheclient"
    val amyId = 90101L
    val benId = 90102L

    fun service(): GedraDataService = GedraDataService.get(cxt)

    fun asUser(userId: Long): KdrCxt = cxt.mkSubContext("gscache", client).also { it.userId = userId }

    fun aForm(userId: Long, name: String): GedraId = service().createGedra(
        asUser(userId), GedraDataType.formDoc,
        listOf(mapOf(GE.traitId to GT.name, GE.data to mapOf(GT.name to name))),
    ).gedraId

    fun presence(year: Int): Map<String, Any?> =
        mapOf(GE.traitId to ST.traitPresenceByYear, GE.data to mapOf(ST.year to year, ST.presentTraits to emptyList<String>()))

    fun externalId(source: String, ref: String): Map<String, Any?> =
        mapOf(GE.traitId to ST.externalId, GE.data to mapOf(ST.externalSource to source, ST.externalRef to ref))

    fun traitIds(entries: List<Map<String, Any?>>): List<String?> = entries.map { it[GE.traitId].toOptStr() }

    var amyGid: GedraId? = null

    "the states cache loads a state row and indexes it by client" {
        val amy = aForm(amyId, "Amy's form").also { amyGid = it }
        service().writeState(asUser(amyId), amy, listOf(presence(2024), externalId("salesforce", "SF-1")))

        val cache = service().statesCache.shouldNotBeNull()
        cache.checkRefresh(cxt)

        // Present in the client index and reachable by its primary key (the gedra id).
        GedraStatesCache.rowsForClient(cache, client).map { it.value[GD.gedraId].toOptStr() } shouldContain amy.fullId
        val row = cache.snapshot.get(cache.idOf(amy.fullId)).shouldNotBeNull()
        row.value[PF.client] shouldBe client
        row.value[PF.userId] shouldBe amyId
        traitIds(row.value[GD.data].toJsonMapOrEmpty()[GD.entries].toJsonListOfMaps()) shouldContainExactlyInAnyOrder
            listOf(ST.traitPresenceByYear, ST.externalId)
    }

    /** For every scope shape, the cached state read equals the SQL read -- including on refusal. */
    "a scoped state read answers the same from cache as from SQL" {
        val amy = amyGid.shouldNotBeNull()
        val svc = service()
        val scopes = listOf(
            "unrestricted" to ReadScope.unrestricted,
            "own client" to ReadScope(client = client),
            "owner" to ReadScope(client = client, userId = amyId),
            "another user" to ReadScope(client = client, userId = benId),
            "another client" to ReadScope(client = "someOtherClient"),
        )
        for ((name, scope) in scopes) {
            val fromCache = svc.readState(cxt, amy, scope)
            val held = svc.statesCache
            svc.statesCache = null
            val fromSql = try {
                svc.readState(cxt, amy, scope)
            } finally {
                svc.statesCache = held
            }
            withClue(name) { traitIds(fromCache).sortedBy { it } shouldBe traitIds(fromSql).sortedBy { it } }
        }

        // Spelled out, so a failure above reads as something concrete: the owner sees the state, an out-of-scope
        // caller sees none -- and never another owner's.
        traitIds(svc.readState(cxt, amy, ReadScope(client = client, userId = amyId))) shouldContainExactlyInAnyOrder
            listOf(ST.traitPresenceByYear, ST.externalId)
        svc.readState(cxt, amy, ReadScope(client = client, userId = benId)) shouldBe emptyList()
        svc.readState(cxt, amy, ReadScope(client = "someOtherClient")) shouldBe emptyList()
    }

    "a state row written after the first load is picked up; an unwritten gedra is empty" {
        val svc = service()
        val ben = aForm(benId, "Ben's form")
        svc.writeState(asUser(benId), ben, listOf(presence(2023)))

        val cache = svc.statesCache.shouldNotBeNull()
        cache.checkRefresh(cxt)
        cache.snapshot.get(cache.idOf(ben.fullId)).shouldNotBeNull()

        // A gedra that never had state written has no cached row and reads empty.
        val stateless = aForm(benId, "No state here")
        cache.snapshot.get(cache.idOf(stateless.fullId)) shouldBe null
        svc.readState(cxt, stateless, ReadScope.ofClient(client)) shouldBe emptyList()
    }

    /**
     * A state upsert landing in the same millisecond as the previous write must still be picked up by the cache
     * -- the same monotonicity the patch path relies on. `writeState` advances `updatedAt` strictly past the
     * value read under the lock, so the frozen clock here cannot hide the second write from cached reads.
     */
    "a state upsert in the same millisecond stays visible to the cache" {
        val svc = service()
        val scope = ReadScope.ofClient(client)
        val clock = cxt.instanceConfig.clock
        clock.freeze()
        try {
            val gid = aForm(amyId, "Frozen-clock state")
            svc.writeState(asUser(amyId), gid, listOf(presence(2020)))
            traitIds(svc.readState(cxt, gid, scope)) shouldBe listOf(ST.traitPresenceByYear)

            // Replace the entries, clock still frozen at the first write's millisecond.
            svc.writeState(asUser(amyId), gid, listOf(externalId("hubspot", "HS-7")))

            // The cached read reflects the upsert -- it would still show traitPresenceByYear if updatedAt had not
            // advanced past the frozen first-write stamp.
            traitIds(svc.readState(cxt, gid, scope)) shouldBe listOf(ST.externalId)
        } finally {
            clock.unfreeze()
        }
    }
})
