package com.dynamicruntime.sample.gedra

import com.dynamicruntime.common.context.KdrCxt
import com.dynamicruntime.common.context.ReadScope
import com.dynamicruntime.common.gedra.GE
import com.dynamicruntime.common.gedra.GT
import com.dynamicruntime.common.gedra.GedraDataService
import com.dynamicruntime.common.gedra.GedraDataType
import com.dynamicruntime.common.gedra.workflow.SVY
import com.dynamicruntime.common.util.toJsonMapOrEmpty
import com.dynamicruntime.common.util.toOptLong
import com.dynamicruntime.common.util.toOptStr
import com.dynamicruntime.kdn.Startup
import com.dynamicruntime.sample.SampleComponent
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
 * gedra sample tests explain: every test shares one in-memory database.
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

    "a first state write owns the row to the gedra's owner, not the acting context (issue #675)" {
        // A client with no derivers, so the create writes no state -- the next write INSERTs the first state row,
        // the one case whose ownership is stamped rather than preserved.
        val client = "obown675"
        val ownerId = 90601L
        val actorId = 90602L
        val ownerCxt = asUser(client, ownerId)
        val gid = service().createGedra(
            ownerCxt, GedraDataType.formDoc,
            listOf(mapOf(GE.traitId to GT.name, GE.data to mapOf(GT.name to "Owned"))),
        ).gedraId
        service().readState(ownerCxt, gid, ReadScope.ofClient(client)).shouldBeEmpty()

        // A DIFFERENT actor (an admin, say) writes the first state row, supplying the gedra as the owner.
        val actorCxt = asUser(client, actorId)
        val ownerRow = service().queryGedra(actorCxt, gid.fullId, GedraDataType.formDoc, ReadScope.ofClient(client))
        service().writeState(
            actorCxt, gid,
            listOf(mapOf(GE.traitId to GT.cfacts, GE.data to mapOf(GT.facts to emptyList<String>()))),
            ownerRow = ownerRow,
        )

        // The state row belongs to the owner, not the actor: the owner's own-scope read sees it, the actor's does not.
        traitIds(service().readState(ownerCxt, gid, ReadScope(client = client, userId = ownerId))) shouldContain GT.cfacts
        service().readState(actorCxt, gid, ReadScope(client = client, userId = actorId)).shouldBeEmpty()
    }
})
