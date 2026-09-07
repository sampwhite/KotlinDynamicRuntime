package com.dynamicruntime.sample.gedra

import com.dynamicruntime.common.context.KdrCxt
import com.dynamicruntime.common.context.ReadScope
import com.dynamicruntime.common.exception.KdrException
import com.dynamicruntime.common.gedra.GE
import com.dynamicruntime.common.gedra.GT
import com.dynamicruntime.common.gedra.GedraDataService
import com.dynamicruntime.common.gedra.GedraDataType
import com.dynamicruntime.common.util.toJsonMapOrEmpty
import com.dynamicruntime.common.util.toOptLong
import com.dynamicruntime.common.util.toOptStr
import com.dynamicruntime.kdn.Startup
import com.dynamicruntime.sample.SampleComponent
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotBeBlank

/**
 * The `GedraDataStates` companion table with state now written as **trait-modeled data** (issue #597, phase B
 * of #595): a state entry is a real trait instance, stamped with the stored envelope, validated against the
 * global state union for the gedra's kind, and keyed by the trait's own `primaryKey` -- the same path a data
 * write takes. Exercised through `sampleTraits`' `traitPresenceByYear`, a *derived* state trait keyed by
 * `[<year>]` (its derivation is phase D; here we write it by hand).
 *
 * It lives in `sample` rather than beside the phase-A plumbing because the interesting cases need a real
 * declared state trait, and the state union that validates one is built from the sample config's state traits.
 * Its own client, as `GedraPatchTest` explains: every test shares one in-memory database.
 */
class GedraStateTest : StringSpec({
    val cxt = Startup.mkTestBootCxt(
        "gedraState", "gedraStateTest", mapOf("KDR_LOAD_SAMPLE" to "true"),
        additionalComponents = listOf(SampleComponent()),
    )

    val client = "gstateclient"
    val ownerId = 90301L
    val kind = GedraDataType.formDoc
    val scope = ReadScope.ofClient(client)

    fun service(): GedraDataService = GedraDataService.get(cxt)

    /** A context acting as the owner inside this spec's client. */
    fun asOwner(): KdrCxt = cxt.mkSubContext("gstate", client).also { it.userId = ownerId }

    /** A `traitPresenceByYear` state entry: keyed by [year], recording which trait ids have data that year. */
    fun presence(year: Int, vararg traits: String): Map<String, Any?> =
        mapOf(GE.traitId to ST.traitPresenceByYear, GE.data to mapOf(ST.year to year, ST.presentTraits to traits.toList()))

    /** An `externalId` state entry: the id a third-party [source] returned, keyed by that source. */
    fun externalId(source: String, ref: String): Map<String, Any?> =
        mapOf(GE.traitId to ST.externalId, GE.data to mapOf(ST.externalSource to source, ST.externalRef to ref))

    fun aForm(name: String) = service().createGedra(
        asOwner(), kind,
        listOf(mapOf(GE.traitId to GT.name, GE.data to mapOf(GT.name to name))),
    ).gedraId

    /** A wfData gedra (no data entries) -- a second gedra kind, to exercise the applicability guard. */
    fun aWfData() = service().createGedra(asOwner(), GedraDataType.wfData, emptyList()).gedraId

    fun traitIdsOf(entries: List<Map<String, Any?>>): List<String?> = entries.map { it[GE.traitId].toOptStr() }

    /** The `(traitId, year)` of each read-back state entry -- year via [toOptLong] so an Int/Long round-trip is moot. */
    fun addressesOf(entries: List<Map<String, Any?>>): List<Pair<String?, Long?>> =
        entries.map { it[GE.traitId].toOptStr() to it[GE.data].toJsonMapOrEmpty()[ST.year].toOptLong() }

    fun yearOf(entry: Map<String, Any?>): Long? = entry[GE.data].toJsonMapOrEmpty()[ST.year].toOptLong()

    "state is written as a validated trait, read back, and upserted" {
        val gid = aForm("A form with state")
        val own = asOwner()

        // No state written yet.
        service().readState(own, gid, scope).shouldBeEmpty()

        // First write inserts one keyed entry, with the stored envelope: a real trait instance, not a raw map.
        service().writeState(own, gid, listOf(presence(2023, ST.expenseReport)))
        addressesOf(service().readState(own, gid, scope)) shouldContainExactly listOf(ST.traitPresenceByYear to 2023L)
        val firstId2023 = service().readState(own, gid, scope).first { yearOf(it) == 2023L }[GE.entryId].toOptStr()
        firstId2023.shouldNotBeBlank()

        // A second write upserts (full replace), and a second year coexists as its own keyed entry.
        service().writeState(own, gid, listOf(presence(2023, ST.expenseReport, ST.yearly), presence(2024)))
        addressesOf(service().readState(own, gid, scope)) shouldContainExactlyInAnyOrder
            listOf(ST.traitPresenceByYear to 2023L, ST.traitPresenceByYear to 2024L)

        // The re-sent 2023 entry keeps its identity across the rewrite; the new 2024 entry gets its own.
        val after = service().readState(own, gid, scope)
        after.first { yearOf(it) == 2023L }[GE.entryId].toOptStr() shouldBe firstId2023
        after.first { yearOf(it) == 2024L }[GE.entryId].toOptStr().shouldNotBeBlank()
    }

    "an unknown state trait id is refused on write, not stored on the open branch" {
        val gid = aForm("Form with a mistyped state trait")
        val own = asOwner()

        // A misspelling of traitPresenceByYear: the data path rejects an unknown trait, and so does state -- it
        // is not silently accepted on the union's open default branch (that branch is for reads, not writes).
        val typo = mapOf(GE.traitId to "traitPresenceByYaer", GE.data to mapOf(ST.year to 2023, ST.presentTraits to emptyList<String>()))
        shouldThrow<KdrException> { service().writeState(own, gid, listOf(typo)) }
        service().readState(own, gid, scope).shouldBeEmpty()
    }

    "an invalid state entry is refused, and nothing is stored" {
        val gid = aForm("Form rejecting bad state")
        val own = asOwner()

        // `year` is a required key field; an entry omitting it does not conform to the trait.
        val bad = listOf(mapOf(GE.traitId to ST.traitPresenceByYear, GE.data to mapOf(ST.presentTraits to listOf("x"))))
        shouldThrow<KdrException> { service().writeState(own, gid, bad) }
        service().readState(own, gid, scope).shouldBeEmpty()
    }

    "two entries of the keyed state trait for one year are refused" {
        val gid = aForm("Form with a state key collision")
        val own = asOwner()

        shouldThrow<KdrException> {
            service().writeState(own, gid, listOf(presence(2023, ST.expenseReport), presence(2023, ST.yearly)))
        }
        service().readState(own, gid, scope).shouldBeEmpty()
    }

    "a cross-kind state trait shares the one state union with a kind-specific one" {
        val gid = aForm("Synced to a third party")
        val own = asOwner()

        // externalId (cross-kind) and traitPresenceByYear (formDoc-only) are both in the single StateEntry union,
        // written together and read back together -- state is not partitioned by gedra kind.
        service().writeState(own, gid, listOf(externalId("salesforce", "SF-123"), presence(2024)))
        traitIdsOf(service().readState(own, gid, scope)) shouldContainExactlyInAnyOrder
            listOf(ST.externalId, ST.traitPresenceByYear)
    }

    "a state trait is refused on a gedra kind its appliesTo does not name" {
        val own = asOwner()
        val wf = aWfData()

        // externalId applies to wfData, so it is accepted there.
        service().writeState(own, wf, listOf(externalId("salesforce", "SF-9")))
        traitIdsOf(service().readState(own, wf, scope)) shouldContainExactly listOf(ST.externalId)

        // traitPresenceByYear applies only to formDoc, so the guard refuses it on a wfData.
        shouldThrow<KdrException> { service().writeState(own, wf, listOf(presence(2024))) }
    }

    "a caller out of scope reads no state, rather than leaking another's" {
        val gid = aForm("Owned by gstateclient")
        service().writeState(asOwner(), gid, listOf(presence(2022)))

        val outsider = cxt.mkSubContext("gstate2", "otherclient").also { it.userId = 99999L }
        service().readState(outsider, gid, ReadScope.ofClient("otherclient")).shouldBeEmpty()
    }
})
