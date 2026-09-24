package com.dynamicruntime.sample.gedra

import com.dynamicruntime.common.context.KdrCxt
import com.dynamicruntime.common.context.ReadScope
import com.dynamicruntime.common.exception.EXC
import com.dynamicruntime.common.exception.KdrException
import com.dynamicruntime.common.gedra.GE
import com.dynamicruntime.common.gedra.GT
import com.dynamicruntime.common.gedra.GedraDataRow
import com.dynamicruntime.common.gedra.GedraDataService
import com.dynamicruntime.common.gedra.GedraDataType
import com.dynamicruntime.common.gedra.GedraEdit
import com.dynamicruntime.common.gedra.GedraEditAction
import com.dynamicruntime.common.gedra.GedraId
import com.dynamicruntime.common.gedra.GedraPatchTarget
import com.dynamicruntime.common.gedra.workflow.SVY
import com.dynamicruntime.common.util.toJsonMapOrEmpty
import com.dynamicruntime.common.util.toOptLong
import com.dynamicruntime.common.util.toOptStr
import com.dynamicruntime.kdn.Startup
import com.dynamicruntime.sample.SampleComponent
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe

/**
 * Phase D (issue #599): initial derived state on create, and the state→cfact bridge.
 *
 * The `traitPresenceByYear` derivation (registered by `SampleComponent`) runs inside the create transaction for
 * a client that opted in via `testFeatures` (acme) on a test instance, and is skipped otherwise. And a form's
 * stored `cfacts` state flows into `CFactRegistry.assemble` as target facts. Its own clients, as the other
 * gedra sample tests do.
 */
class GedraStateDerivationTest : StringSpec({
    val cxt = Startup.mkTestBootCxt(
        "gedraStateDeriv", "gedraStateDerivTest", mapOf("KDR_LOAD_SAMPLE" to "true"),
        additionalComponents = listOf(SampleComponent()),
    )

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
        // acme also carries survey state now (issue #657), so assert this deriver's entry specifically rather
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
            listOf(mapOf(GE.traitId to GT.name, GE.data to mapOf(GT.name to "Bridge form"))),
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

        // The recompute rebuilds every derived entry (acme's survey completion and its year presence) and keeps
        // the asserted one untouched.
        service().recomputeDerivedState(acme, gid, scope)
        val ids = traitIds(service().readState(acme, gid, scope))
        ids shouldContain ST.externalId          // asserted: preserved
        ids shouldContain ST.traitPresenceByYear // derived: recomputed
        ids shouldContain SVY.surveyCompletion   // derived: recomputed
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
})
