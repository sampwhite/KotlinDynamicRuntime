package com.dynamicruntime.kdn

import com.dynamicruntime.common.context.KdrCxt
import com.dynamicruntime.common.context.ReadScope
import com.dynamicruntime.common.gedra.GE
import com.dynamicruntime.common.gedra.GT
import com.dynamicruntime.common.gedra.GedraDataService
import com.dynamicruntime.common.gedra.GedraDataType
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe

/**
 * The `GedraDataStates` companion table and its write/read (issue #596, the first phase of #595): a gedra's
 * state entries are written under the same `GedraDataTran` lock as its data, upserted, and read back
 * scope-checked. Entries are raw maps here -- validating them as traits is issue #597.
 *
 * Its own client, as `GedraDataExtraTest` explains: every test shares one in-memory database.
 */
class GedraStateTest : StringSpec({
    val cxt = Startup.mkTestBootCxt("gedraState", "gedraStateTest")

    val client = "gstateclient"
    val ownerId = 90301L
    val kind = GedraDataType.formDoc
    val scope = ReadScope.ofClient(client)

    fun service(): GedraDataService = GedraDataService.get(cxt)

    /** A context acting as the owner inside this spec's client. */
    fun asOwner(): KdrCxt = cxt.mkSubContext("gstate", client).also { it.userId = ownerId }

    fun stateEntry(trait: String, count: Int): Map<String, Any?> =
        mapOf("stateTrait" to trait, "data" to mapOf("count" to count))

    fun aForm(name: String) = service().createGedra(
        asOwner(), kind,
        listOf(mapOf(GE.traitId to GT.name, GE.data to mapOf(GT.name to name))),
    ).gedraId

    "state is written, read back, and upserted" {
        val gid = aForm("A form with state")
        val own = asOwner()

        // No state written yet.
        service().readState(own, gid, scope).shouldBeEmpty()

        // First write inserts.
        val initial = listOf(stateEntry("sync", 1))
        service().writeState(own, gid, initial)
        service().readState(own, gid, scope) shouldBe initial

        // A second write upserts, replacing the entries.
        val updated = listOf(stateEntry("sync", 2), stateEntry("review", 0))
        service().writeState(own, gid, updated)
        service().readState(own, gid, scope) shouldBe updated
    }

    "a caller out of scope reads no state, rather than leaking another's" {
        val gid = aForm("Owned by gstateclient")
        service().writeState(asOwner(), gid, listOf(stateEntry("sync", 5)))

        val outsider = cxt.mkSubContext("gstate2", "otherclient").also { it.userId = 99999L }
        service().readState(outsider, gid, ReadScope.ofClient("otherclient")).shouldBeEmpty()
    }
})
