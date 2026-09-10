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
import com.dynamicruntime.common.schema.validate
import com.dynamicruntime.common.startup.SchemaCollector
import com.dynamicruntime.common.startup.SchemaService
import com.dynamicruntime.common.util.toOptStr

/**
 * The names the survey's state and cfacts are stored and reported under (issue #657). Kept apart from [WFC]:
 * those are per-**task** target facts computed at render time; these are **form-singleton** facts about the
 * whole form, stored as `derived` state and read back through the state->cfact bridge.
 */
@Suppress("ConstPropertyName")
object SVY {
    /**
     * A form's survey has an entry for every trait it requires. Positive on purpose (present == good): a form
     * with no computed state yet carries neither survey fact, which reads correctly as "not ready" rather than
     * -- as a negated `surveyIncomplete` would -- falsely reading as complete. See the survey design doc.
     */
    const val surveyComplete = "surveyComplete"

    /** A form's present survey-trait data passes its schema, ignoring missing required values. Positive, as [surveyComplete]. */
    const val surveyValid = "surveyValid"

    /** The friendly group the two survey cfacts present under. */
    const val group = "Survey"

    /** The `surveyCompletion` state trait's entry type: `globalconfig.SurveyCompletionEntry`. */
    const val surveyCompletionEntry = "SurveyCompletionEntry"

    /** The `surveyCompletion` state trait id -- a derived, form-singleton (unkeyed) projection. */
    const val surveyCompletion = "surveyCompletion"

    const val complete = "complete"
    const val valid = "valid"
    const val missingTraits = "missingTraits"
    const val invalidTraits = "invalidTraits"

    /** Under a [surveyCompletion] entry: the survey revision the projection was computed against, as `WfRef` text. */
    const val computedAgainstSurveyRef = "computedAgainstSurveyRef"

    /** The config bundle the survey state trait is declared in. */
    const val stateBundle = "surveyState"
}

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
        val union = SchemaService.get(cxt).storeFor(row.client)
            .types["${GCFG.globalNamespace}.${GU.unionName(GedraDataType.formDoc)}"]
        val invalidTraits = if (union == null) {
            emptyList()
        } else {
            entries
                .filter { it[GE.traitId].toOptStr() in surveyTraitIds && it[GE.data] != null }
                .filter { entry -> validate(union, entry).any { it.code != SchFailCode.missingRequired } }
                .mapNotNull { it[GE.traitId].toOptStr() }
                .distinct()
        }
        val valid = invalidTraits.isEmpty()

        val facts = buildList {
            if (complete) add(SVY.surveyComplete)
            if (valid) add(SVY.surveyValid)
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
