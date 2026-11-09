package com.dynamicruntime.sample.gedra

import com.dynamicruntime.common.context.CL
import com.dynamicruntime.common.context.KdrCxt
import com.dynamicruntime.common.context.ReadScope
import com.dynamicruntime.common.endpoint.EP
import com.dynamicruntime.common.endpoint.clientPath
import com.dynamicruntime.common.exception.EXC
import com.dynamicruntime.common.exception.KdrException
import com.dynamicruntime.common.gedra.GD
import com.dynamicruntime.common.gedra.GDBG
import com.dynamicruntime.common.gedra.GDF
import com.dynamicruntime.common.gedra.GE
import com.dynamicruntime.common.gedra.GEP
import com.dynamicruntime.common.gedra.GT
import com.dynamicruntime.common.gedra.GedraDataRow
import com.dynamicruntime.common.gedra.GedraDataService
import com.dynamicruntime.common.gedra.GedraDataType
import com.dynamicruntime.common.gedra.GedraEdit
import com.dynamicruntime.common.gedra.GedraEditAction
import com.dynamicruntime.common.gedra.GedraId
import com.dynamicruntime.common.gedra.GedraPatchTarget
import com.dynamicruntime.common.gedra.GedraStatesCache
import com.dynamicruntime.common.gedra.mergeCfactContributions
import com.dynamicruntime.common.gedra.workflow.CDF
import com.dynamicruntime.common.gedra.workflow.CTAK
import com.dynamicruntime.common.gedra.workflow.WFC
import com.dynamicruntime.common.gedra.workflow.WFD
import com.dynamicruntime.common.gedra.workflow.WFS
import com.dynamicruntime.common.gedra.workflow.WSC
import com.dynamicruntime.common.gedra.workflow.WVF
import com.dynamicruntime.common.gedra.workflow.WorkflowEngagement
import com.dynamicruntime.common.http.request.ROLE
import com.dynamicruntime.common.sql.PF
import com.dynamicruntime.common.user.TestUser
import com.dynamicruntime.common.util.toJsonListOfMaps
import com.dynamicruntime.common.util.toJsonListOrEmpty
import com.dynamicruntime.common.util.toJsonMapOrEmpty
import com.dynamicruntime.common.util.toOptLong
import com.dynamicruntime.common.util.toOptStr
import com.dynamicruntime.kdn.Startup
import com.dynamicruntime.sample.SampleComponent
import com.dynamicruntime.sample.SampleTestInstances
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.assertions.withClue
import io.kotest.core.spec.style.StringSpec
import io.kotest.core.spec.style.stringSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.collections.shouldNotContain
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotBeBlank
import kotlin.time.Instant

/**
 * A form's state on the sample's clients (issue #1736 gathered these): the gedra states a form derives and shows
 * (issues #615, #637, #648, #651), the states cache, and the workflow state built on them (issue #794). Each part
 * below is one of the specs that used to be a file of its own, kept whole, on the shared sample instance.
 * `StateRecomputeJobTest` stays apart: it boots a fixture component of its own.
 */
class FormStateTest : StringSpec({
    include(gedraStateTests())
    include(gedraStateDerivationTests())
    include(gedraStateSurfaceTests())
    include(gedraStatesCacheTests())
    include(workflowStateFoundationTests())
})

/**
 * The `GedraDataStates` companion table with state now written as **trait-modeled data** (issue #597, phase B
 * of #595): a state entry is a real trait instance, stamped with the stored envelope, validated against the
 * global state union for the gedra's kind, and keyed by the trait's own `primaryKey` -- the same path a data
 * write takes. Exercised through `sampleTraits`' `traitPresenceByYear`, a *derived* state trait keyed by
 * `[<year>]` (its derivation is phase D; here we write it by hand).
 *
 * It lives in `sample` rather than beside the phase-A plumbing because the interesting cases need a real
 * declared state trait, and the state union that validates one is built from the sample config's state traits.
 * Its own client, so its assertions see only the entries written here.
 */
private fun gedraStateTests() = stringSpec {
    val cxt = SampleTestInstances.default("gedraState")

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
        listOf(mapOf(GE.traitId to GT.name, GE.data to mapOf(GT.nameField to name))),
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
}

/**
 * Phase D (issue #599): initial derived state on create, and the state→cfact bridge.
 *
 * The `traitPresenceByYear` derivation (registered by `SampleComponent`) runs inside the create transaction for
 * a client that opted in via `testFeatures` (acme) on a test instance, and is skipped otherwise. And a form's
 * stored `cfacts` state flows into `CFactRegistry.assemble` as target facts. Its own clients, as the other
 * gedra sample tests do.
 */
private fun gedraStateDerivationTests() = stringSpec {
    val cxt = SampleTestInstances.default("gedraStateDeriv")

    fun service(): GedraDataService = GedraDataService.get(cxt)

    fun asUser(client: String, userId: Long): KdrCxt = cxt.mkSubContext("gderiv", client).also { it.userId = userId }

    /** An expenseReport data entry -- acme includes the trait, and it carries the `year` the derivation reads. */
    fun expense(year: Int): Map<String, Any?> = mapOf(GE.traitId to ST.expenseReport, GE.data to mapOf(ST.year to year))

    fun traitIds(entries: List<Map<String, Any?>>): List<String?> = entries.map { it[GE.traitId].toOptStr() }

    /** The years the `traitPresenceByYear` derivation recorded, which follow the expenseReport's `year`. */
    fun derivedYears(entries: List<Map<String, Any?>>): List<Long?> =
        entries.filter { it[GE.traitId].toOptStr() == ST.traitPresenceByYear }.map { it[GE.data].toJsonMapOrEmpty()[ST.year].toOptLong() }

    /**
     * A form of [acme] created for 2024, the copy of it a caller would hold from reading it, and then an edit
     * moving it to 2025 -- which commits, with its own recompute, *after* that read. The copy is what a batch job
     * or a workflow act would still be holding when it takes the lock (issue #862).
     */
    fun staleAfterEdit(acme: KdrCxt, scope: ReadScope): Pair<GedraId, GedraDataRow> {
        val gid = service().createGedra(acme, GedraDataType.formDoc, listOf(expense(2024))).gedraId
        val stale = checkNotNull(service().queryGedra(acme, gid.fullId, GedraDataType.formDoc, scope))
        val edit = GedraEdit(GedraEditAction.addOrReplace, ST.expenseReport, data = mapOf(ST.year to 2025))
        service().patchGedras(acme, mapOf(GedraDataType.formDoc to listOf(GedraPatchTarget(gid, listOf(edit)))), scope)
        derivedYears(service().readState(acme, gid, scope)) shouldContainExactly listOf(2025L)
        return gid to stale
    }

    "initial derived state is computed on create for an opted-in client" {
        val acme = asUser(SC.acme, 90501L)
        val gid = service().createGedra(acme, GedraDataType.formDoc, listOf(expense(2024))).gedraId

        // The derivation ran in the create transaction: the form now has traitPresenceByYear state for 2024,
        // recording that its expenseReport trait carried data that year -- read back with no separate write.
        // acme also carries core details state now (issue #657), so assert this deriver's entry specifically rather
        // than the whole set.
        val state = service().readState(acme, gid, ReadScope.ofClient(SC.acme))
        traitIds(state) shouldContain ST.traitPresenceByYear
        val data = state.first { it[GE.traitId].toOptStr() == ST.traitPresenceByYear }[GE.data].toJsonMapOrEmpty()
        data[ST.year].toOptLong() shouldBe 2024L
        (data[ST.presentTraits] as? List<*>).orEmpty().map { it.toOptStr() } shouldContainExactly listOf(ST.expenseReport)
    }

    "no derived state is written for a client that did not opt in" {
        // An undeclared client: ClientService has no ClientDef for it, so the feature is off and the derivation
        // is skipped -- the form is created with no state, even though its data carries a year.
        val other = asUser("gderivoff", 90502L)
        val gid = service().createGedra(other, GedraDataType.formDoc, listOf(expense(2024))).gedraId
        service().readState(other, gid, ReadScope.ofClient("gderivoff")).shouldBeEmpty()
    }

    "a form's stored-state cfacts flow into assemble via the bridge" {
        val ctx = asUser("gderivbridge", 90503L)
        val scope = ReadScope.ofClient("gderivbridge")
        val gid = service().createGedra(
            ctx, GedraDataType.formDoc,
            listOf(mapOf(GE.traitId to GT.name, GE.data to mapOf(GT.nameField to "Bridge form"))),
        ).gedraId

        // The form's state asserts the demo cfact through the core `cfacts` state trait.
        service().writeState(ctx, gid, listOf(mapOf(GE.traitId to GT.cfacts, GE.data to mapOf(GT.facts to listOf(ST.sampleFormReady)))))

        // The read half of the bridge returns exactly the asserted names; the whole bridge unions them into the
        // assembled cfacts, so an eligibility expression evaluating over the form would see sampleFormReady.
        service().formCfacts(ctx, gid, scope) shouldBe setOf(ST.sampleFormReady)
        service().assembleFormCfacts(ctx, gid, scope) shouldContain ST.sampleFormReady

        // A name whose definition is gone must be dropped, not thrown on: state is durable but cfact
        // declarations are not. Overwrite state to assert an undeclared name beside the declared one; the
        // bridge returns only the declared one and assemble does not choke on the stale name.
        service().writeState(
            ctx, gid,
            listOf(mapOf(GE.traitId to GT.cfacts, GE.data to mapOf(GT.facts to listOf(ST.sampleFormReady, "aCfactNoLongerDeclared")))),
        )
        service().formCfacts(ctx, gid, scope) shouldBe setOf(ST.sampleFormReady)
        service().assembleFormCfacts(ctx, gid, scope) shouldContain ST.sampleFormReady
    }

    "recompute rebuilds derived state while preserving asserted state (issue #658)" {
        val acme = asUser(SC.acme, 90504L)
        val scope = ReadScope.ofClient(SC.acme)
        val gid = service().createGedra(acme, GedraDataType.formDoc, listOf(expense(2024))).gedraId

        // Assert an external-sync marker (asserted). `writeState` whole-replaces, so the derived state is
        // momentarily gone -- exactly what a later data edit's recompute must recover from without losing the
        // assertion, since an external fact is not a function of the form's data.
        service().writeState(
            acme, gid,
            listOf(mapOf(GE.traitId to ST.externalId, GE.data to mapOf(ST.externalSource to "salesforce", ST.externalRef to "SF-1"))),
        )

        // The recompute rebuilds every derived entry (acme's core details completion and its year presence) and keeps
        // the asserted one untouched.
        service().recomputeDerivedState(acme, gid, scope)
        val ids = traitIds(service().readState(acme, gid, scope))
        ids shouldContain ST.externalId          // asserted: preserved
        ids shouldContain ST.traitPresenceByYear // derived: recomputed
        ids shouldContain CDF.coreDetailsCompletion   // derived: recomputed
    }

    "a recompute handed an older copy derives from the data under the lock (issue #862)" {
        val acme = asUser(SC.acme, 90505L)
        val scope = ReadScope.ofClient(SC.acme)
        val (gid, stale) = staleAfterEdit(acme, scope)

        // Deriving from the copy would put 2024 back over the edit's 2025 until the form's next write.
        service().recomputeDerivedState(acme, stale)
        derivedYears(service().readState(acme, gid, scope)) shouldContainExactly listOf(2025L)
    }

    "a state change handed an older copy derives from the data under the lock (issue #862)" {
        val acme = asUser(SC.acme, 90506L)
        val scope = ReadScope.ofClient(SC.acme)
        val (gid, stale) = staleAfterEdit(acme, scope)

        // A change that alters nothing: what comes back, and what is stored, is only the recompute's work -- both
        // recomputes, before and after the change, are the ones that would read the copy.
        val entries = service().changeState(acme, stale) { it }
        derivedYears(entries) shouldContainExactly listOf(2025L)
        derivedYears(service().readState(acme, gid, scope)) shouldContainExactly listOf(2025L)
    }

    "a form deleted after the caller's read is skipped by a recompute and refused by a state change" {
        val acme = asUser(SC.acme, 90507L)
        val scope = ReadScope.ofClient(SC.acme)
        val gid = service().createGedra(acme, GedraDataType.formDoc, listOf(expense(2024))).gedraId
        val stale = checkNotNull(service().queryGedra(acme, gid.fullId, GedraDataType.formDoc, scope))
        service().deleteGedra(acme, gid.fullId, GedraDataType.formDoc, scope) shouldBe true

        // Nothing is left to derive for, so the recompute does nothing rather than failing ...
        service().recomputeDerivedState(acme, stale)
        // ... while an act on the form cannot be carried out on a form that is gone.
        shouldThrow<KdrException> { service().changeState(acme, stale) { it } }.code shouldBe EXC.notFound
    }
}

/**
 * The gedra-state inspection & admin surfaces (issue #600, phase E): the `withStates` parameter that attaches a
 * form's state to the listing, and the global admin endpoints that read and replace a gedra's state directly.
 * Driven over HTTP through `TestHttpClient`, since these are endpoints; `sample` because the state entries it
 * writes (`externalId`) are sample-declared. Its own users, as the other sample gedra tests do.
 */
private fun gedraStateSurfaceTests() = stringSpec {
    val cxt = SampleTestInstances.default("gedraStateSurface")

    val user = TestUser.create(cxt, "user@statesurface.test")
    // Deployment-wide (admin + allClients): the section the /admin state endpoints gate on.
    val admin = TestUser.createFullAdmin(cxt, "admin@statesurface.test")

    fun createForm(name: String): String =
        user.postItem(
            GEP.formDocCreate,
            mapOf(GDF.entries to listOf(mapOf(GE.traitId to GT.name, GE.data to mapOf(GT.nameField to name)))),
        )[GDF.gedraId].toOptStr().orEmpty()

    fun externalId(source: String, ref: String): Map<String, Any?> =
        mapOf(GE.traitId to ST.externalId, GE.data to mapOf(ST.externalSource to source, ST.externalRef to ref))

    fun traitIds(states: Any?): List<String?> = states.toJsonListOfMaps().map { it[GE.traitId].toOptStr() }

    "an admin replaces a gedra's state and reads it back" {
        val gid = createForm("Admin state form")

        // Replace (whole-set upsert): the response echoes the stored entries under `states`.
        val written = admin.postItem(GEP.adminGedraState, mapOf(GDF.gedraId to gid, GDF.states to listOf(externalId("salesforce", "SF-1"))))
        written[GDF.gedraId] shouldBe gid
        traitIds(written[GDF.states]) shouldContainExactly listOf(ST.externalId)

        // And it reads back the same, whoever owns the gedra (the admin is unrestricted).
        val read = admin.getItem(GEP.adminGedraState, mapOf(GDF.gedraId to gid))
        read[GDF.gedraId] shouldBe gid
        traitIds(read[GDF.states]) shouldContainExactly listOf(ST.externalId)
    }

    "withStates attaches the owner's state to the listing, and is absent without it" {
        val gid = createForm("Listed with state")
        admin.postItem(GEP.adminGedraState, mapOf(GDF.gedraId to gid, GDF.states to listOf(externalId("hubspot", "HS-9"))))

        // The owner lists their own forms with withStates: the row for this gedra carries its state entries.
        val withStates = user.getItems(GEP.formDocs, mapOf(GDF.withStates to true)).first { it[GDF.gedraId] == gid }
        traitIds(withStates[GDF.states]) shouldContainExactly listOf(ST.externalId)

        // Without the flag, the field is absent (not empty) -- the read is not paid for where it is not asked.
        val without = user.getItems(GEP.formDocs).first { it[GDF.gedraId] == gid }
        without.containsKey(GDF.states) shouldBe false
    }

    "the admin state endpoint is refused to a non-admin caller" {
        val gid = createForm("Guarded")
        // The /admin section requires admin + allClients; an ordinary user is refused by the section gate.
        user.expectError(403, GEP.adminGedraState, args = mapOf(GDF.gedraId to gid))
    }

    "the stateFromSql debug tag reads state via SQL and agrees with the cache" {
        val gid = createForm("SQL-debug state")
        admin.postItem(GEP.adminGedraState, mapOf(GDF.gedraId to gid, GDF.states to listOf(externalId("stripe", "ST-3"))))

        // The default read trusts the resident cache; `_debug=stateFromSql` activates the otherwise-dormant SQL
        // path. Both answer the same -- the diagnostic exists to prove exactly that when a stale read is feared.
        val viaCache = admin.getItem(GEP.adminGedraState, mapOf(GDF.gedraId to gid))
        val viaSql = admin.getItem(GEP.adminGedraState, mapOf(GDF.gedraId to gid, EP.debug to GDBG.stateFromSql.name))
        traitIds(viaSql[GDF.states]) shouldContainExactly listOf(ST.externalId)
        traitIds(viaSql[GDF.states]) shouldContainExactly traitIds(viaCache[GDF.states])
    }

    "the admin state read 404s a gedra that does not exist" {
        // A well-formed id that names nothing: the read resolves the gedra first, so absence is a real 404 --
        // not a 200 with an empty state list, which an item response could not distinguish from "no state".
        admin.expectError(404, GEP.adminGedraState, args = mapOf(GDF.gedraId to "gd.fd.${CL.public}.uNoSuchGedra"))
    }
}

/**
 * The `GedraDataStates` cache (issue #598, phase C): that it holds what was written, indexes it by client, and
 * -- the assertion the design rests on -- **answers a scoped by-id state read identically to the SQL path it
 * replaces**. A cache over user content is safe only if it cannot widen an answer, so the scope cases are the
 * point. Its own client, like `gedraStateTests` above.
 */
private fun gedraStatesCacheTests() = stringSpec {
    val cxt = SampleTestInstances.default("gedraStatesCache")

    val client = "gstatecacheclient"
    val amyId = 90101L
    val benId = 90102L

    fun service(): GedraDataService = GedraDataService.get(cxt)

    fun asUser(userId: Long): KdrCxt = cxt.mkSubContext("gscache", client).also { it.userId = userId }

    fun aForm(userId: Long, name: String): GedraId = service().createGedra(
        asUser(userId), GedraDataType.formDoc,
        listOf(mapOf(GE.traitId to GT.name, GE.data to mapOf(GT.nameField to name))),
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
}

/**
 * The per-workflow state foundation (issue #794): a `normal` workflow is admitted, a form carries one derived
 * [WFS.workflowState] entry per such workflow, and an asserted [WFS.workflowEngagement] beside it survives
 * every recompute.
 *
 * Acme declares the sample's one normal workflow (`auditReview`), so a form in acme is the vehicle. The two
 * state classes are what this pins: the derived entry is rebuilt wholesale on each recompute, while the
 * engagement -- a person's choice -- is preserved verbatim, which is what makes "an entry the recompute does
 * not emit is implicitly deleted" safe to rely on.
 */
private fun workflowStateFoundationTests() = stringSpec {
    val cxt = SampleTestInstances.default("wfState794")

    val create = clientPath(GEP.formDocCreate, SC.acme)
    val engage = clientPath(GEP.workflowEngage, SC.acme)
    val recompute = clientPath(GEP.formDocRecomputeState, SC.acme)

    fun auditEntries(findings: String = "seen") = listOf(
        mapOf(GE.traitId to SC.siteAudit, GE.data to mapOf(SC.auditor to "A Person", SC.findings to findings)),
    )

    // What acme's review core details workflow requires (an expense report and the owner's details), so a form carrying
    // these has the `coreDetailsComplete` cfact -- which is what `auditReview`'s eligibility asks for (issue #783).
    fun coreDetailsEntries() = listOf(
        mapOf(GE.traitId to ST.expenseReport, GE.data to mapOf(ST.year to 2026)),
        mapOf(GE.traitId to SC.userInfo, GE.data to mapOf(SC.userName to "A Person")),
    )

    /**
     * A form in acme; eligible for `auditReview` unless [coreDetailsDone] is false. [findings] of
     * [SC.findingsOpen] gives the workflow its own `acmeUnderAudit` cfact (issue #784).
     */
    fun newForm(user: TestUser, coreDetailsDone: Boolean = true, findings: String = "seen"): String {
        val entries = auditEntries(findings) + if (coreDetailsDone) coreDetailsEntries() else emptyList()
        return user.postItem(create, mapOf(GDF.entries to entries))[GDF.gedraId].toOptStr()!!
    }

    fun entriesOf(states: List<Map<String, Any?>>, traitId: String) =
        states.filter { it[GE.traitId].toOptStr() == traitId }.map { it[GE.data].toJsonMapOrEmpty() }

    fun statesOf(result: Map<String, Any?>) = result[GDF.states].toJsonListOfMaps()

    fun auditState(states: List<Map<String, Any?>>) =
        entriesOf(states, WFS.workflowState).single { it[WFD.workflowId].toOptStr() == SW.auditReview }

    fun failureIds(entry: Map<String, Any?>) =
        entry[WFS.eligibilityFailures].toJsonListOfMaps().map { it[WFD.id].toOptStr() }

    "a form carries a derived workflowState entry for each normal workflow, computed against its revision" {
        val user = TestUser.create(cxt, "wfs-create@acme.test", userClient = SC.acme)
        val gid = newForm(user)
        // Created through the ordinary create path: the deriver runs in that write's own transaction, so the
        // entry is there without anything asking for a recompute.
        val states = statesOf(user.postData(recompute, mapOf(GDF.gedraId to gid)))
        val wf = entriesOf(states, WFS.workflowState).single { it[WFD.workflowId].toOptStr() == SW.auditReview }
        wf[WFS.computedAgainstRef].toOptStr().shouldNotBeNull()
    }

    "engaging writes an asserted engagement with a trail, and the recompute preserves it" {
        val user = TestUser.create(cxt, "wfs-engage@acme.test", userClient = SC.acme)
        val gid = newForm(user)
        val after = statesOf(user.postData(engage, mapOf(GDF.gedraId to gid, GDF.workflowId to SW.auditReview)))
        val engagement = entriesOf(after, WFS.workflowEngagement).single()
        engagement[WFS.engaged] shouldBe true
        engagement[WFS.lastEngagedBy].toString() shouldBe user.userId.toString()
        engagement[WFS.events].toJsonListOfMaps().map { it[WFS.kind].toOptStr() } shouldContainExactly
            listOf(WFS.engagedEvent)

        // A recompute rebuilds the derived entries and must leave the asserted engagement exactly as it was --
        // the whole reason engagement is its own asserted trait rather than a field inside the derived entry.
        val recomputed = statesOf(user.postData(recompute, mapOf(GDF.gedraId to gid)))
        entriesOf(recomputed, WFS.workflowEngagement).single()[WFS.engaged] shouldBe true
        entriesOf(recomputed, WFS.workflowState).count { it[WFD.workflowId].toOptStr() == SW.auditReview } shouldBe 1
    }

    "disengaging keeps the entry and extends its trail rather than erasing the history" {
        val user = TestUser.create(cxt, "wfs-disengage@acme.test", userClient = SC.acme)
        val gid = newForm(user)
        user.postData(engage, mapOf(GDF.gedraId to gid, GDF.workflowId to SW.auditReview))
        val after = statesOf(
            user.postData(engage, mapOf(GDF.gedraId to gid, GDF.workflowId to SW.auditReview, WFS.engaged to false)),
        )
        val engagement = entriesOf(after, WFS.workflowEngagement).single()
        engagement[WFS.engaged] shouldBe false
        engagement[WFS.events].toJsonListOfMaps().map { it[WFS.kind].toOptStr() } shouldContainExactly
            listOf(WFS.engagedEvent, WFS.disengagedEvent)
        // `lastEngagedBy` records who put it in, and survives the disengage that followed.
        engagement[WFS.lastEngagedBy].toString() shouldBe user.userId.toString()
    }

    "engaging with something that is not a normal workflow of the client is refused" {
        val user = TestUser.create(cxt, "wfs-bad@acme.test", userClient = SC.acme)
        val gid = newForm(user)
        // A typo, and the core details workflow -- neither is a normal workflow this form may be put into.
        user.expectError(EXC.badInput, engage, data = mapOf(GDF.gedraId to gid, GDF.workflowId to "nosuchworkflow"))
        user.expectError(EXC.badInput, engage, data = mapOf(GDF.gedraId to gid, GDF.workflowId to SW.reviewForm))
    }

    "a form engaged by someone else stays its owner's, with the engager recorded as the actor" {
        val owner = TestUser.create(cxt, "wfs-owner@acme.test", userClient = SC.acme)
        val acmeAdmin = TestUser.create(cxt, "wfs-admin@acme.test", userClient = SC.acme, level = ROLE.admin)
        val gid = newForm(owner)
        acmeAdmin.postData(engage, mapOf(GDF.gedraId to gid, GDF.workflowId to SW.auditReview))
        // The owner reads in their own-user scope: had the state row been re-owned by the admin, it would be
        // invisible to them here and the engagement would read as absent.
        val seen = entriesOf(statesOf(owner.postData(recompute, mapOf(GDF.gedraId to gid))), WFS.workflowEngagement)
        seen.single()[WFS.lastEngagedBy].toString() shouldBe acmeAdmin.userId.toString()
    }

    "a workflow retired from configuration shows nothing, keeps the form's engagement, and can still be disengaged" {
        val user = TestUser.create(cxt, "wfs-retired@acme.test", userClient = SC.acme)
        val admin = TestUser.createFullAdmin(cxt, "wfs-retired-admin@example.com")
        val gid = newForm(user)
        val retired = "retiredAudit"
        // Stands in for a form engaged with a workflow the client has since removed: no engage call could make
        // this (it refuses an undeclared workflow), so the state is written the way an operator would.
        admin.postItem(
            GEP.adminGedraState,
            mapOf(
                GDF.gedraId to gid,
                GDF.states to listOf(
                    mapOf(GE.traitId to WFS.workflowEngagement, GE.data to mapOf(WFD.workflowId to retired, WFS.engaged to true)),
                ),
            ),
        )
        // Deleted is past its lifetime (issue #1354): the deriver calculates nothing for it, and nothing about it is
        // shown -- the recompute's answer has neither a state nor the engagement for it.
        val before = statesOf(user.postData(recompute, mapOf(GDF.gedraId to gid)))
        entriesOf(before, WFS.workflowState).map { it[WFD.workflowId].toOptStr() } shouldContainExactly listOf(SW.auditReview, SW.siteFollowUp)
        entriesOf(before, WFS.workflowEngagement).filter { it[WFD.workflowId] == retired }.shouldBeEmpty()
        // The engagement is still stored, for when the workflow comes back.
        fun stored() = statesOf(admin.getItem(GEP.adminGedraState, mapOf(GDF.gedraId to gid)))
        entriesOf(stored(), WFS.workflowEngagement).single { it[WFD.workflowId] == retired }[WFS.engaged] shouldBe true

        // Re-engaging it is still refused -- the form cannot be put *into* a workflow that no longer exists...
        user.expectError(EXC.badInput, engage, data = mapOf(GDF.gedraId to gid, GDF.workflowId to retired))
        // ...but it can be taken out, and the engagement keeps its trail.
        user.postData(engage, mapOf(GDF.gedraId to gid, GDF.workflowId to retired, WFS.engaged to false))
        entriesOf(stored(), WFS.workflowState).map { it[WFD.workflowId].toOptStr() } shouldContainExactly
            listOf(SW.auditReview, SW.siteFollowUp)
        entriesOf(stored(), WFS.workflowEngagement).single { it[WFD.workflowId] == retired }[WFS.engaged] shouldBe false
    }

    // --- eligibility (issue #783) -------------------------------------------------------------------------

    "a form's workflow state records whether it is eligible, and which tests it fails" {
        val user = TestUser.create(cxt, "wfs-elig@acme.test", userClient = SC.acme)
        val admin = TestUser.createFullAdmin(cxt, "wfs-elig-admin@example.com")
        // Read through the admin state endpoint, which reads and does not recompute: what is checked is what the
        // create itself wrote. Before derivers saw each other's output in the same pass, a create computed
        // eligibility against the cfacts as they stood *before* the write -- none -- and a complete form read
        // as ineligible until its next write.
        fun stored(gid: String) = statesOf(admin.getItem(GEP.adminGedraState, mapOf(GDF.gedraId to gid)))

        val done = auditState(stored(newForm(user)))
        done[WFS.eligible] shouldBe true
        failureIds(done).shouldBeEmpty()

        // Not done: the core details are incomplete, so `coreDetailsDone` fails -- by id, with no explanation
        // text stored. `coreDetailsClean` passes, since nothing present is invalid; every test is evaluated, not the
        // first failure.
        val notDone = auditState(stored(newForm(user, coreDetailsDone = false)))
        notDone[WFS.eligible] shouldBe false
        failureIds(notDone) shouldContainExactly listOf(SW.coreDetailsDone)
    }

    "engaging a form that is not eligible is refused, with the reasons" {
        val user = TestUser.create(cxt, "wfs-refused@acme.test", userClient = SC.acme)
        val gid = newForm(user, coreDetailsDone = false)
        val err = user.expectError(
            EXC.badInput, engage, data = mapOf(GDF.gedraId to gid, GDF.workflowId to SW.auditReview),
        )
        // The explanation is pulled from the acmeWf fragment file, so this is the evaluated text, not the template.
        err.toString() shouldContain "Finish reviewing your form first"
        err.toString() shouldContain SW.coreDetailsDone
    }

    "the engage gate decides on freshly recomputed cfacts, not on what was last stored" {
        val user = TestUser.create(cxt, "wfs-fresh@acme.test", userClient = SC.acme)
        val admin = TestUser.createFullAdmin(cxt, "wfs-fresh-admin@example.com")
        val gid = newForm(user)
        // Clear the stored state, the stand-in for a form whose stored cfacts predate the configuration (one
        // created before its client had a core details workflow, say). The data still completes the core details workflow.
        admin.postItem(GEP.adminGedraState, mapOf(GDF.gedraId to gid, GDF.states to emptyList<Any?>()))
        // Judged on the stored state, this form has no `coreDetailsComplete` and would be refused. Recomputed first,
        // its data says it is complete, so it is eligible and the engagement lands.
        val after = statesOf(user.postData(engage, mapOf(GDF.gedraId to gid, GDF.workflowId to SW.auditReview)))
        entriesOf(after, WFS.workflowEngagement).single()[WFS.engaged] shouldBe true
        auditState(after)[WFS.eligible] shouldBe true
    }

    // --- singleton cfacts as a contributed set (issue #784) --------------------------------------------------

    "an engaged workflow contributes its singleton to the form's one cfacts set, beside the core details workflow's" {
        val user = TestUser.create(cxt, "wfs-single@acme.test", userClient = SC.acme)
        val gid = newForm(user, findings = SC.findingsOpen)
        fun formFacts(states: List<Map<String, Any?>>) =
            entriesOf(states, GT.cfacts).single()[GT.facts].toJsonListOrEmpty().map { it.toOptStr() }

        // Not engaged: the workflow has concluded its own cfact from the data, and keeps it on its own entry --
        // but contributes nothing, so the form's set is the core details workflow's alone.
        val before = statesOf(user.postData(recompute, mapOf(GDF.gedraId to gid)))
        auditState(before)[WFS.cfacts].toJsonListOrEmpty().map { it.toOptStr() } shouldContainExactly listOf(SC.underAudit)
        auditState(before)[WFS.singletonCfacts].toJsonListOrEmpty().shouldBeEmpty()
        formFacts(before) shouldContainExactly listOf(CDF.coreDetailsComplete, CDF.coreDetailsValid)

        // Engaged: its rule emits `needsReview`, attributed on its entry and merged into the one form set -- after
        // the core details workflow's facts, which the merge keeps rather than one producer clobbering the other.
        val engaged = statesOf(user.postData(engage, mapOf(GDF.gedraId to gid, GDF.workflowId to SW.auditReview)))
        auditState(engaged)[WFS.singletonCfacts].toJsonListOrEmpty().map { it.toOptStr() } shouldContainExactly
            listOf(WSC.needsReview)
        formFacts(engaged) shouldContainExactly listOf(CDF.coreDetailsComplete, CDF.coreDetailsValid, WSC.needsReview)

        // Disengaged: the contribution goes with the engagement.
        val out = statesOf(
            user.postData(engage, mapOf(GDF.gedraId to gid, GDF.workflowId to SW.auditReview, WFS.engaged to false)),
        )
        formFacts(out) shouldContainExactly listOf(CDF.coreDetailsComplete, CDF.coreDetailsValid)
    }

    "a workflow is gated by its peers' singletons, never by its own" {
        val user = TestUser.create(cxt, "wfs-peers@acme.test", userClient = SC.acme)
        val gid = newForm(user, findings = SC.findingsOpen)
        fun state(states: List<Map<String, Any?>>, workflowId: String) =
            entriesOf(states, WFS.workflowState).single { it[WFD.workflowId].toOptStr() == workflowId }

        // Engaging the review raises `needsReview`. Both workflows test `~needsReview`: the review, whose own
        // singleton it is, stays eligible -- it must not read as ineligible for itself -- while the follow-up, a
        // peer, is held off by it.
        val states = statesOf(user.postData(engage, mapOf(GDF.gedraId to gid, GDF.workflowId to SW.auditReview)))
        state(states, SW.auditReview)[WFS.eligible] shouldBe true
        state(states, SW.siteFollowUp)[WFS.eligible] shouldBe false
        failureIds(state(states, SW.siteFollowUp)) shouldContainExactly listOf(SW.noOpenReview)

        // The gate agrees with the stored verdict, being the same computation: the follow-up is refused...
        user.expectError(EXC.badInput, engage, data = mapOf(GDF.gedraId to gid, GDF.workflowId to SW.siteFollowUp))
            .toString() shouldContain "Wait for the pending review"
        // ...and re-engaging the review, which its own `needsReview` must not block, is not.
        val again = statesOf(user.postData(engage, mapOf(GDF.gedraId to gid, GDF.workflowId to SW.auditReview)))
        state(again, SW.auditReview)[WFS.eligible] shouldBe true
    }

    "a workflow's own cfacts follow the form's data and its call to action's keyword, and Needs Review the keyword alone" {
        val user = TestUser.create(cxt, "wfs-quiet@acme.test", userClient = SC.acme)
        val gid = newForm(user)   // findings "seen": the audit is closed, so no acmeUnderAudit
        val states = statesOf(user.postData(engage, mapOf(GDF.gedraId to gid, GDF.workflowId to SW.auditReview)))
        // No fact from the data; the review keyword's own (issue #1596), since recording the audit is review work.
        auditState(states)[WFS.cfacts].toJsonListOrEmpty().map { it.toOptStr() } shouldContainExactly listOf(CTAK.reviewCfact)
        auditState(states)[WFS.ctaKeyword] shouldBe CTAK.review
        // And the form needs review because of it, whatever the data says.
        auditState(states)[WFS.singletonCfacts].toJsonListOrEmpty().map { it.toOptStr() } shouldContainExactly
            listOf(WSC.needsReview)
        entriesOf(states, GT.cfacts).single()[GT.facts].toJsonListOrEmpty().map { it.toOptStr() } shouldContain
            WSC.needsReview
    }

    // --- the CTA task (issue #785) -------------------------------------------------------------------------

    "an engaged workflow stores its CTA: the earliest task not both complete and valid" {
        val user = TestUser.create(cxt, "wfs-cta@acme.test", userClient = SC.acme)
        val contact = mapOf(GE.traitId to SC.userInfo, GE.data to mapOf(SC.userName to "Site Lead"))
        // The follow-up's own record (issue #856): what completes its second task.
        val followUpRecord = mapOf(GE.traitId to SC.siteFollowUpTrait, GE.data to mapOf(SC.followUpBy to "Site Lead"))
        fun form(vararg entries: Map<String, Any?>): String =
            user.postItem(create, mapOf(GDF.entries to entries.toList()))[GDF.gedraId].toOptStr()!!
        fun engagedFollowUp(gid: String) = entriesOf(
            statesOf(user.postData(engage, mapOf(GDF.gedraId to gid, GDF.workflowId to SW.siteFollowUp))),
            WFS.workflowState,
        ).single { it[WFD.workflowId].toOptStr() == SW.siteFollowUp }

        // Only the follow-up record present: that completes the *second* task, yet the CTA is the first -- the
        // earliest task not done, not whichever one has something missing -- with the status that says why.
        val firstOpen = engagedFollowUp(form(followUpRecord))
        firstOpen[WFS.ctaTask] shouldBe SW.confirmContact
        firstOpen[WFS.tasksDone] shouldBe false
        val status = firstOpen[WFS.ctaStatus].toJsonMapOrEmpty()
        status[CDF.complete] shouldBe false
        status[CDF.valid] shouldBe true
        status[CDF.missingTraits] shouldBe listOf(SC.userInfo)

        // The first task done: the CTA moves on to the second.
        engagedFollowUp(form(contact))[WFS.ctaTask] shouldBe SW.recordFollowUp

        // Both done: no CTA, and that is said outright rather than left to an absent key.
        val done = engagedFollowUp(form(contact, followUpRecord))
        done[WFS.tasksDone] shouldBe true
        done[WFS.ctaTask].shouldBeNull()
    }

    "a workflow the form has not entered carries no CTA" {
        val user = TestUser.create(cxt, "wfs-nocta@acme.test", userClient = SC.acme)
        val states = statesOf(user.postData(recompute, mapOf(GDF.gedraId to newForm(user))))
        val followUp = entriesOf(states, WFS.workflowState).single { it[WFD.workflowId].toOptStr() == SW.siteFollowUp }
        followUp[WFS.ctaTask].shouldBeNull()
        followUp[WFS.tasksDone].shouldBeNull()
    }

    "the workflow view agrees with the stored CTA: its focus task, and the kdr:isCta fact on that task alone" {
        val user = TestUser.create(cxt, "wfs-ctaview@acme.test", userClient = SC.acme)
        val gid = user.postItem(
            create,
            mapOf(GDF.entries to listOf(mapOf(GE.traitId to SC.userInfo, GE.data to mapOf(SC.userName to "Site Lead")))),
        )[GDF.gedraId].toOptStr()!!
        val stored = entriesOf(
            statesOf(user.postData(engage, mapOf(GDF.gedraId to gid, GDF.workflowId to SW.siteFollowUp))),
            WFS.workflowState,
        ).single { it[WFD.workflowId].toOptStr() == SW.siteFollowUp }

        val view = user.getData(
            clientPath(GEP.workflowView, SC.acme),
            mapOf(GDF.workflowId to SW.siteFollowUp, GDF.gedraId to gid),
        )
        view[WVF.focusTask] shouldBe stored[WFS.ctaTask]
        val factsByTask = view[WFD.tasks].toJsonListOfMaps().associate {
            it[WFD.id].toOptStr() to it[WVF.facts].toJsonListOrEmpty().map { f -> f.toOptStr() }
        }
        factsByTask.getValue(SW.recordFollowUp) shouldContain WFC.isCta
        factsByTask.getValue(SW.confirmContact) shouldNotContain WFC.isCta
    }

    "cfacts contributions merge into one entry, in first-seen order, leaving other entries alone" {
        fun contribution(vararg facts: String) = mapOf(GE.traitId to GT.cfacts, GE.data to mapOf(GT.facts to facts.toList()))
        val other = mapOf(GE.traitId to WFS.workflowState, GE.data to mapOf(WFD.workflowId to "x"))
        val merged = mergeCfactContributions(listOf(contribution("a", "b"), other, contribution("b", "c")))
        merged.map { it[GE.traitId] } shouldContainExactly listOf(GT.cfacts, WFS.workflowState)
        merged.first()[GE.data].toJsonMapOrEmpty()[GT.facts] shouldBe listOf("a", "b", "c")
        // A lone contribution passes through untouched.
        mergeCfactContributions(listOf(contribution("a"))).single()[GE.data].toJsonMapOrEmpty()[GT.facts] shouldBe listOf("a")
    }

    "the engagement merge creates an entry, then extends it, leaving other entries alone" {
        val at = Instant.parse("2026-01-01T00:00:00Z")
        val other = mapOf(GE.traitId to WFS.workflowState, GE.data to mapOf(WFD.workflowId to "somethingElse"))

        val first = WorkflowEngagement.withEngagement(listOf(other), SW.auditReview, true, at, 7L)
        // The unrelated entry rides through, and the new engagement is created with its first event.
        first.count { it[GE.traitId].toOptStr() == WFS.workflowState } shouldBe 1
        val created = first.single { it[GE.traitId].toOptStr() == WFS.workflowEngagement }[GE.data].toJsonMapOrEmpty()
        created[WFS.engaged] shouldBe true
        created[WFS.events].toJsonListOfMaps().size shouldBe 1

        val second = WorkflowEngagement.withEngagement(first, SW.auditReview, false, at, 9L)
        val extended = second.single { it[GE.traitId].toOptStr() == WFS.workflowEngagement }[GE.data].toJsonMapOrEmpty()
        extended[WFS.engaged] shouldBe false
        extended[WFS.events].toJsonListOfMaps().size shouldBe 2
        // Disengaging does not move who engaged it, so "who put it here" survives.
        extended[WFS.lastEngagedBy].toString() shouldBe "7"
    }
}
