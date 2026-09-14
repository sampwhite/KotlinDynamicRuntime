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

// `object SVY` (the survey state/cfact wire names) now lives in `base:kernel`
// (`workflow/SurveyConstants.kt`, same package) so the frontend shares it (issue #694). This file keeps the
// producer: the state config and the deriver below.

/**
 * The survey's `derived` state trait (issue #657), declared globally like the state schema always is (a state
 * union is manufactured once, never per client). `surveyCompletion` is **form-singleton** (unkeyed): there is
 * one "is this form's survey done?" fact per form, not one per workflow, since the creation and survey paths are
 * the one-per-form front end of a form's data, not the many-per-form normal workflows.
 */
fun surveyStateConfig(cxt: KdrCxt): GedraConfig = gedraConfig(cxt, SVY.stateBundle, GCFG.globalNamespace) {
    stateTrait(
        SVY.surveyCompletionEntry, SVY.surveyCompletion, setOf(GedraDataType.formDoc),
        StateTraitClass.derived,
        "Whether a form's survey is complete and valid, and what still blocks it -- a derived, form-singleton " +
            "projection of the form's data against its client's survey workflow.",
    ) {
        property(SVY.complete, "Whether every trait the survey requires has an entry present.") { type = SCT.boolean }
        property(SVY.valid, "Whether the present survey-trait data passes its schema, ignoring missing required values.") {
            type = SCT.boolean
        }
        property(SVY.missingTraits, "The survey's required trait ids that have no entry yet, in declaration order.") {
            type = SCT.array
            items { type = SCT.string }
        }
        property(SVY.invalidTraits, "The survey's trait ids whose present data fails its schema on content, not absence.") {
            type = SCT.array
            items { type = SCT.string }
        }
        property(SVY.computedAgainstSurveyRef, "The survey workflow revision this was computed against, as WfRef text.")
    }
}

/**
 * Declares the two survey cfacts (issue #657). Unlike [WFC]'s target facts, neither has a request-scoped
 * source: a form asserts them through its stored `derived` state, which `GedraDataService.assembleFormCfacts`
 * turns into target facts -- so an eligibility expression (a later phase) can gate on "the survey is done".
 * `toFrontend`, so the workflow view and the forms list can read them client-side.
 */
fun addSurveyCFacts(collector: SchemaCollector) {
    collector.addCFact(
        CFactDef(
            SVY.surveyComplete, SVY.group,
            "True, about a form, when every trait its client's survey requires has an entry present -- asserted " +
                "by the form's stored survey state. Presence, not content.",
            toFrontend = true,
        ),
    )
    collector.addCFact(
        CFactDef(
            SVY.surveyValid, SVY.group,
            "True, about a form, when the present survey-trait data passes its schema (ignoring missing required " +
                "values) -- asserted by the form's stored survey state.",
            toFrontend = true,
        ),
    )
}

/**
 * Computes a form's `derived` survey state from its current data (issue #657). Registered as a production
 * [GedraStateDeriver] (no `featureName`, so it runs everywhere), it runs inside the create/import transaction
 * `GedraDataService` already opens -- so a form records its survey state on the creation workflow, a plain
 * `formDoc/create`, and an import alike. A form whose client has no survey produces nothing.
 *
 * It writes two entries, both form-singleton: the structured [SVY.surveyCompletion] (what the UI status and
 * CTA read) and the shared [GT.cfacts] list (the two survey facts, for the eligibility bridge). It is the sole
 * `cfacts` producer today; when a second one arrives (a cross-workflow rollup, say) the `cfacts` list becomes
 * an aggregation point rather than one deriver's to own.
 */
object SurveyStateDeriver : GedraStateDeriver {
    override val appliesTo: Set<GedraDataType> = setOf(GedraDataType.formDoc)

    override fun derive(cxt: KdrCxt, row: GedraDataRow): List<Map<String, Any?>> {
        val survey = WorkflowService.get(cxt).forClient(row.client).survey ?: return emptyList()
        return evaluate(cxt, row, survey)
    }

    /**
     * The state entries a form's survey stands at, exposed apart from [derive] so the recompute-on-edit path
     * (a later phase) and a test can compute the same projection without going through the deriver registry.
     */
    fun evaluate(cxt: KdrCxt, row: GedraDataRow, survey: WfDeclared): List<Map<String, Any?>> {
        val def = survey.def
        val entries = row.entries

        // Complete: every required trait across the survey's tasks has a present entry. Presence, not content --
        // the same check the save gate uses, reused here so the two never disagree about "done".
        val requiredTraitIds = def.tasks.flatMap { it.requiredTraitIds }.distinct()
        val missingTraits = WfEngine.missingTraits(requiredTraitIds, entries)
        val complete = missingTraits.isEmpty()

        // Valid: the survey's own traits' present data passes its schema, ignoring missing required values (a
        // missing required value is *incomplete*, not *invalid*). Validated against the client's own entry union,
        // the same union the write path checks against, so validity here means what it means on a save. A form
        // created cleanly is always valid at create -- the interesting cases are a lenient import and a schema
        // narrowed after capture (the recompute path).
        val surveyTraitIds = def.tasks.flatMap { task -> task.traits.map { it.traitId } }.toSet()
        val invalidTraits = surveyContentFailures(cxt, row.client, surveyTraitIds, entries).keys.toList()
        val valid = invalidTraits.isEmpty()

        // The survey's own two facts, plus whatever the survey workflow's cfactCalc functions emit from the same
        // data (issue #678). Folding them into this one form-singleton `cfacts` entry -- rather than a second
        // producer emitting a competing entry -- is the aggregation point this deriver's doc anticipated.
        val facts = buildList {
            if (complete) add(SVY.surveyComplete)
            if (valid) add(SVY.surveyValid)
            addAll(runCfactCalc(cxt, def, entries, row.client).sorted())
        }
        return listOf(
            mapOf(
                GE.traitId to SVY.surveyCompletion,
                GE.data to mapOf(
                    SVY.complete to complete,
                    SVY.valid to valid,
                    SVY.missingTraits to missingTraits,
                    SVY.invalidTraits to invalidTraits,
                    SVY.computedAgainstSurveyRef to survey.ref.text,
                ),
            ),
            mapOf(GE.traitId to GT.cfacts, GE.data to mapOf(GT.facts to facts)),
        )
    }
}

/**
 * The **content** failures of the [entries] whose trait is in [traitIds], keyed by trait id -- the survey's
 * one rule for "invalid" (issue #657), shared with the task rail's per-task status (issue #700) so the two
 * cannot disagree. Each present entry is validated against the client's formDoc entry union, the same union
 * the write path checks against, keeping only failures that are not `missingRequired`: a missing required
 * value is *incomplete*, not *invalid*. A trait with no such failures is absent from the map; the map is empty
 * when the client has no union at all (no formDoc traits).
 */
fun surveyContentFailures(
    cxt: KdrCxt,
    client: String,
    traitIds: Set<String>,
    entries: List<Map<String, Any?>>,
): Map<String, List<SchFailure>> {
    val union = SchemaService.get(cxt).storeFor(client)
        .types["${GCFG.globalNamespace}.${GU.unionName(GedraDataType.formDoc)}"] ?: return emptyMap()
    return entries
        .filter { it[GE.traitId].toOptStr() in traitIds && it[GE.data] != null }
        .mapNotNull { entry ->
            val traitId = entry[GE.traitId].toOptStr() ?: return@mapNotNull null
            val content = validate(union, entry).filter { it.code != SchFailCode.missingRequired }
            if (content.isEmpty()) null else traitId to content
        }
        .groupBy({ it.first }, { it.second })
        .mapValues { (_, lists) -> lists.flatten() }
}
