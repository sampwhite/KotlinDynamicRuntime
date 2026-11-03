package com.dynamicruntime.common.gedra.workflow

import com.dynamicruntime.common.cfact.CFactDef
import com.dynamicruntime.common.context.KdrCxt
import com.dynamicruntime.common.gedra.GCFG
import com.dynamicruntime.common.gedra.GE
import com.dynamicruntime.common.gedra.GT
import com.dynamicruntime.common.gedra.GU
import com.dynamicruntime.common.gedra.GedraConfig
import com.dynamicruntime.common.gedra.GedraDataRow
import com.dynamicruntime.common.gedra.GedraDataType
import com.dynamicruntime.common.gedra.GedraStateContext
import com.dynamicruntime.common.gedra.GedraStateDeriver
import com.dynamicruntime.common.gedra.StateTraitClass
import com.dynamicruntime.common.gedra.gedraConfig
import com.dynamicruntime.common.schema.SCT
import com.dynamicruntime.common.schema.SchFailCode
import com.dynamicruntime.common.schema.SchFailure
import com.dynamicruntime.common.schema.validate
import com.dynamicruntime.common.startup.SchemaCollector
import com.dynamicruntime.common.startup.SchemaService
import com.dynamicruntime.common.util.toOptStr

// `object CDF` (the core details state/cfact wire names) now lives in `base:kernel`
// (`workflow/CoreDetailsConstants.kt`, same package) so the frontend shares it (issue #694). This file keeps the
// producer: the state config and the deriver below.

/**
 * The core details workflow's `derived` state trait (issue #657), declared globally like the state schema always is (a
 * state union is manufactured once, never per client). `coreDetailsCompletion` is **form-singleton** (unkeyed): there
 * is one "is this form's core details workflow done?" fact per form, not one per workflow, since the creation and core
 * details workflow paths are the one-per-form front end of a form's data, not the many-per-form normal workflows.
 */
fun coreDetailsStateConfig(cxt: KdrCxt): GedraConfig = gedraConfig(cxt, CDF.stateBundle, GCFG.globalNamespace) {
    stateTrait(
        CDF.coreDetailsCompletionEntry, CDF.coreDetailsCompletion, setOf(GedraDataType.formDoc),
        StateTraitClass.derived,
        "Whether a form's core details are complete and valid, and what still blocks it -- a derived, form-singleton " +
            "projection of the form's data against its client's core details workflow.",
    ) {
        property(CDF.complete, "Whether every trait the core details workflow requires has an entry present.") { type = SCT.boolean }
        property(CDF.valid, "Whether the present core details trait data passes its schema, ignoring missing required values.") {
            type = SCT.boolean
        }
        property(CDF.missingTraits, "The core details workflow's required trait ids that have no entry yet, in declaration order.") {
            type = SCT.array
            items { type = SCT.string }
        }
        property(CDF.invalidTraits, "The core details workflow's trait ids whose present data fails its schema on content, not absence.") {
            type = SCT.array
            items { type = SCT.string }
        }
        property(CDF.computedAgainstCoreDetailsRef, "The core details workflow revision this was computed against, as WfRef text.")
    }
}

/**
 * Declares the two core details cfacts (issue #657). Unlike [WFC]'s target facts, neither has a request-scoped
 * source: a form asserts them through its stored `derived` state, which `GedraDataService.assembleFormCfacts`
 * turns into target facts -- so an eligibility test (issue #783) can gate on "the core details are done".
 * `toFrontend`, so the workflow view and the forms list can read them client-side.
 */
fun addCoreDetailsCFacts(collector: SchemaCollector) {
    collector.addCFact(
        CFactDef(
            CDF.coreDetailsComplete, CDF.group,
            "True, about a form, when every trait its client's core details workflow requires has an entry present -- asserted " +
                "by the form's stored core details state. Presence, not content.",
            toFrontend = true,
        ),
    )
    collector.addCFact(
        CFactDef(
            CDF.coreDetailsValid, CDF.group,
            "True, about a form, when the present core details trait data passes its schema (ignoring missing required " +
                "values) and meets the core details form's own requirements (a field its layout requires, a choice it " +
                "offers) -- asserted by the form's stored core details state.",
            toFrontend = true,
        ),
    )
}

/**
 * Computes a form's `derived` core details state from its current data (issue #657). Registered as a production
 * [GedraStateDeriver] (no `featureName`, so it runs everywhere), it runs inside the write transaction of every
 * create, patch and import (the post-write recompute, issue #675) -- so a form records its core details state on the
 * creation workflow, a plain `formDoc/create`, and an import alike, and keeps it current as its data is edited.
 * A form whose client has no core details workflow produces nothing.
 *
 * It writes two entries, both form-singleton: the structured [CDF.coreDetailsCompletion] (what the UI status and
 * CTA read) and its **contribution** to the shared [GT.cfacts] set (the two core details facts, for the eligibility
 * bridge). A contribution rather than the whole set: the per-workflow deriver contributes too (issue #784), and
 * the recompute merges the two, so neither producer has to know the other's facts.
 */
object CoreDetailsStateDeriver : GedraStateDeriver {
    override val appliesTo: Set<GedraDataType> = setOf(GedraDataType.formDoc)

    override fun derive(cxt: KdrCxt, state: GedraStateContext): List<Map<String, Any?>> {
        val row = state.row
        val coreDetails = WorkflowService.get(cxt).forClient(row.client).coreDetails ?: return emptyList()
        return evaluate(cxt, row, coreDetails)
    }

    /**
     * The state entries a form's core details workflow stands at, exposed apart from [derive] so a caller (a test, say) can
     * compute the same projection without going through the deriver registry. The recompute on every write
     * (issue #675) reaches it through [derive].
     */
    fun evaluate(cxt: KdrCxt, row: GedraDataRow, coreDetails: WfDeclared): List<Map<String, Any?>> {
        val def = coreDetails.def
        val entries = row.entries

        // Complete: every required trait across the core details workflow's tasks has a present entry. Presence, not content --
        // the same check the save gate uses, reused here so the two never disagree about "done".
        val requiredTraitIds = def.tasks.flatMap { it.requiredTraitIds }.distinct()
        val missingTraits = WfEngine.missingTraits(requiredTraitIds, entries)
        val complete = missingTraits.isEmpty()

        // Valid: the core details workflow's own traits' present data passes its schema, ignoring missing required values (a
        // missing required value is *incomplete*, not *invalid*). Validated against the client's own entry union,
        // the same union the write path checks against, so validity here means what it means on a save. A form
        // created cleanly is always valid at create -- the interesting cases are a lenient import and a schema
        // narrowed after capture (the recompute path).
        val coreDetailsTraitIds = def.tasks.flatMap { task -> task.traits.map { it.traitId } }.toSet()
        val invalidTraits = coreDetailsContentFailures(cxt, row.client, coreDetailsTraitIds, entries, coreDetails).keys.toList()
        val valid = invalidTraits.isEmpty()

        // The core details workflow's own two facts, plus whatever the core details workflow's cfactCalc functions emit from the same
        // data (issue #678). Folding them into this one form-singleton `cfacts` entry -- rather than a second
        // producer emitting a competing entry -- is the aggregation point this deriver's doc anticipated.
        val facts = buildList {
            if (complete) add(CDF.coreDetailsComplete)
            if (valid) add(CDF.coreDetailsValid)
            addAll(runCfactCalc(cxt, def, entries, row.client).sorted())
        }
        return listOf(
            mapOf(
                GE.traitId to CDF.coreDetailsCompletion,
                GE.data to mapOf(
                    CDF.complete to complete,
                    CDF.valid to valid,
                    CDF.missingTraits to missingTraits,
                    CDF.invalidTraits to invalidTraits,
                    CDF.computedAgainstCoreDetailsRef to coreDetails.ref.text,
                ),
            ),
            mapOf(GE.traitId to GT.cfacts, GE.data to mapOf(GT.facts to facts)),
        )
    }
}

/**
 * The **content** failures of the [entries] whose trait is in [traitIds], keyed by trait id -- the core details workflow's
 * one rule for "invalid" (issue #657), shared with the task rail's per-task status (issue #700) so the two
 * cannot disagree. Each present entry is validated against the client's formDoc entry union, the same union
 * the write path checks against, keeping only failures that are not `missingRequired`: a missing required
 * value is *incomplete*, not *invalid*. A trait with no such failures is absent from the map; the map is empty
 * when the client has no union at all (no formDoc traits).
 *
 * With [declared], the workflow whose form this is, its **form requirements** count too (issue #1022,
 * [WorkflowFormRules]) -- a field its layout requires included. Unlike a schema-required value, which a write
 * refuses and so only an older schema could leave missing, a form-required value is missing from data the schema
 * accepted elsewhere: this form would not accept it, so its task is not done.
 */
fun coreDetailsContentFailures(
    cxt: KdrCxt,
    client: String,
    traitIds: Set<String>,
    entries: List<Map<String, Any?>>,
    declared: WfDeclared? = null,
): Map<String, List<SchFailure>> {
    val union = SchemaService.get(cxt).storeFor(client)
        .types["${GCFG.globalNamespace}.${GU.unionName(GedraDataType.formDoc)}"] ?: return emptyMap()
    val content = entries
        .filter { it[GE.traitId].toOptStr() in traitIds && it[GE.data] != null }
        .mapNotNull { entry ->
            val traitId = entry[GE.traitId].toOptStr() ?: return@mapNotNull null
            val content = validate(union, entry).filter { it.code != SchFailCode.missingRequired }
            if (content.isEmpty()) null else traitId to content
        }
        .groupBy({ it.first }, { it.second })
        .mapValues { (_, lists) -> lists.flatten() }
    val form = declared?.let { WorkflowFormRules.failures(cxt, client, it, traitIds, entries) }.orEmpty()
    if (form.isEmpty()) return content
    return (content.keys + form.keys).associateWith { content[it].orEmpty() + form[it].orEmpty() }
}
