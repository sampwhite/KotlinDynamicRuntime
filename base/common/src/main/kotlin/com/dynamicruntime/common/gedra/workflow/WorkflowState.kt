package com.dynamicruntime.common.gedra.workflow

import com.dynamicruntime.common.context.KdrCxt
import com.dynamicruntime.common.gedra.GCFG
import com.dynamicruntime.common.gedra.GE
import com.dynamicruntime.common.gedra.GedraStateContext
import com.dynamicruntime.common.gedra.GedraStateDeriver
import com.dynamicruntime.common.gedra.GedraConfig
import com.dynamicruntime.common.gedra.GedraDataType
import com.dynamicruntime.common.gedra.StateTraitClass
import com.dynamicruntime.common.gedra.gedraConfig
import com.dynamicruntime.common.cfact.CFactDef
import com.dynamicruntime.common.gedra.GT
import com.dynamicruntime.common.schema.SCT
import com.dynamicruntime.common.startup.SchemaCollector
import com.dynamicruntime.common.startup.SchemaService
import com.dynamicruntime.common.util.toJsonMapOrEmpty
import com.dynamicruntime.common.util.toOptStr

/**
 * A form's **per-workflow** state (issue #794), declared globally as state schema always is: the derived
 * [WFS.workflowState] projection and the asserted [WFS.workflowEngagement] beside it, both keyed by
 * [WFD.workflowId].
 *
 * Both are **keyed**, where the survey's [SVY.surveyCompletion] is form-singleton, and for the reason that file
 * gives: creation and survey are the one-per-form front end of a form's data, while a normal workflow is
 * many-per-form, so its state is about *a* workflow rather than about the form.
 *
 * Splitting engagement out as its own asserted trait is what keeps the recompute rule simple. Every recompute
 * preserves asserted entries verbatim and replaces derived ones wholesale, so an engagement survives with no
 * field-level carve-out, and a [WFS.workflowState] entry the deriver stops emitting is implicitly deleted by
 * the same whole-replace -- which is exactly the batch-job rule the design asks for, arrived at without a batch
 * job.
 */
fun workflowStateConfig(cxt: KdrCxt): GedraConfig = gedraConfig(cxt, WFS.stateBundle, GCFG.globalNamespace) {
    // A **named** interior type rather than an object inlined under `items`: the codebase's rule for a structure
    // inside a trait is to name it and `$ref` it -- the reason `siteVisit` names `SiteAddress` instead of
    // inlining an address -- so a client can narrow it later and the shape has somewhere to be referenced from.
    type(WFS.workflowEventEntry) {
        type = SCT.kObject
        description = "One thing that happened between a form and a workflow."
        property(WFS.kind, "What happened, e.g. 'engaged'.", required = true)
        property(WFS.at, "When it happened.", required = true) { dateTime() }
        property(WFS.by, "Numeric userId of whoever did it, when a person did.") { type = SCT.integer }
        property(WFS.note, "Anything the producer wanted recorded beside the milestone.")
        // Open for the same reason the entry is: a later milestone carries its own detail.
        additionalProperties = true
    }

    // Named for the same reason: a failure is a structure inside the entry, and one a later slice extends.
    type(WFS.workflowEligibilityFailure) {
        type = SCT.kObject
        description = "One eligibility test a form fails, named by id; its explanation is found again on the definition."
        property(WFD.id, "The failing test's id, unique within its workflow.", required = true)
        property(WFS.captured, "Values captured when the test was evaluated, for its explanation to reference.") {
            type = SCT.kObject
            additionalProperties = true
        }
        additionalProperties = true
    }

    stateTrait(
        WFS.workflowStateEntry, WFS.workflowState, setOf(GedraDataType.formDoc),
        StateTraitClass.derived,
        "What a normal workflow currently computes about one form -- a derived projection, one entry per " +
            "workflow, rebuilt whole on every recompute.",
        primaryKey = listOf(WFD.workflowId),
    ) {
        property(WFD.workflowId, "The workflow this entry is about; the entry's primary key.", required = true)
        property(WFS.computedAgainstRef, "The workflow revision this was computed against, as WfRef text.")
        property(WFS.cfacts, "The workflow's own cfacts, as its cfactCalc functions concluded them from the form's data.") {
            type = SCT.array
            items { type = SCT.string }
        }
        property(WFS.singletonCfacts, "The framework singleton cfacts this workflow contributes to the form; empty unless engaged.") {
            type = SCT.array
            items { type = SCT.string }
        }
        property(WFS.eligible, "Whether the form meets every eligibility test of the workflow.") { type = SCT.boolean }
        property(WFS.eligibilityFailures, "The eligibility tests the form fails, in the workflow's order; empty when eligible.") {
            type = SCT.array
            items { ref(WFS.workflowEligibilityFailure) }
        }
        // Open, because this entry is the one every later slice adds to: eligibility failures (#783), the CTA
        // task and its status (#785), and whatever the time windows (#790) need to record. Declaring it open
        // now means those slices add a field rather than reshaping a closed type every consumer has parsed.
        additionalProperties = true
    }

    stateTrait(
        WFS.workflowEngagementEntry, WFS.workflowEngagement, setOf(GedraDataType.formDoc),
        StateTraitClass.asserted,
        "That a person put this form into a workflow, and the trail of what has happened to it since -- an " +
            "asserted fact a recompute or a batch job must never compute away.",
        primaryKey = listOf(WFD.workflowId),
    ) {
        property(WFD.workflowId, "The workflow this form is engaged with; the entry's primary key.", required = true)
        property(WFS.engaged, "Whether the form is engaged with the workflow right now.", required = true) {
            type = SCT.boolean
        }
        property(WFS.lastEngagedAt, "When the form was last engaged with this workflow.") { dateTime() }
        property(WFS.lastEngagedBy, "Numeric userId of whoever last engaged it.") { type = SCT.integer }
        property(
            WFS.events,
            "What has happened between this form and this workflow, oldest first -- an append-only trail.",
        ) {
            type = SCT.array
            items { ref(WFS.workflowEventEntry) }
        }
        // Open so a later slice can record against this entry without reshaping a type every consumer parsed.
        additionalProperties = true
    }
}

/**
 * Declares the framework singleton cfacts ([WSC]) globally (issue #784), so every scope's expressions -- an
 * eligibility test, a `g-visibleWhen`, a search -- may name them, and so the frontend receives them. Declared by
 * the framework rather than by any workflow, since the list is hardwired: a workflow may only emit these.
 */
fun addWorkflowSingletonCFacts(collector: SchemaCollector) {
    collector.addCFact(
        CFactDef(
            WSC.needsReview, WSC.group,
            "True, about a form, when a workflow it is engaged with is waiting on a review -- contributed by that " +
                "workflow's singleton rule.",
            toFrontend = true,
        ),
    )
    collector.addCFact(
        CFactDef(
            WSC.finished, WSC.group,
            "True, about a form, when a workflow it is engaged with has finished -- contributed by that " +
                "workflow's singleton rule.",
            toFrontend = true,
        ),
    )
}

/**
 * Computes a form's per-workflow [WFS.workflowState] entries (issue #794) -- one per normal workflow the form
 * is being evaluated against. Registered as a production [GedraStateDeriver] (no `featureName`), so it runs
 * inside the same create/patch/import transaction the survey's deriver does, and on the standalone recompute.
 *
 * ### What it emits, and what that makes the implicit delete
 *
 * An entry for every [WfEntry.normal] workflow in the form's client registry, **plus** one for any workflow the
 * form is *engaged* with. Everything else is simply not emitted -- and since a recompute replaces the derived
 * entries wholesale, not emitting is what deletes. That is the design's "a `workflowId` entry the batch job does
 * not refresh is implicitly deleted", arrived at with no batch job and no delete statement.
 *
 * The engagement half is why this deriver needs [GedraStateContext.currentState]: engagement is an *asserted*
 * entry ([WFS.workflowEngagement]) that a recompute preserves but a deriver cannot otherwise see. A form engaged
 * with a workflow whose definition this node no longer carries still keeps a row -- bare, with no
 * [WFS.computedAgainstRef], since there is no definition left to compute against -- rather than silently losing
 * the workflow it is engaged with because configuration changed underneath it.
 *
 * A declared workflow's entry also carries, in this order of computation:
 *
 *  1. **Its own cfacts** (issue #784): what its `cfactCalc` functions conclude from the form's data --
 *     [WFS.cfacts], per workflow and so kept on its entry, not in the form's set.
 *  2. **The singleton cfacts it contributes** ([WFS.singletonCfacts]): each of its [WfDef.singletons] rules
 *     whose condition matches its *current* cfacts -- the form's (from the derivers before this one) plus its
 *     own. Only an **engaged** workflow contributes; the union is emitted as a [GT.cfacts] contribution, which
 *     the recompute merges into the form's one set beside the survey's.
 *  3. **Its eligibility** (issue #783): [WFS.eligible] and the ids of the tests the form fails, against the
 *     form's cfacts **plus the singletons of every *other* engaged workflow** -- so one workflow can gate on
 *     another (a form already `finished` somewhere else, say). Never its own: a workflow's own `finished` must
 *     not make it ineligible for itself once engaged, which would read as "ineligible" where "finished" is
 *     meant (issue #784 review). No cycle either way: a singleton never reads eligibility.
 *
 * See [WorkflowEligibility] for why only the form's own cfacts, never the caller's. A retired-but-engaged
 * workflow's bare entry has none of these, having no definition left to compute against. The CTA task and its
 * status (#785) and the time windows (#790) add their own fields to the open entry.
 */
object WorkflowStateDeriver : GedraStateDeriver {
    override val appliesTo: Set<GedraDataType> = setOf(GedraDataType.formDoc)

    override fun derive(cxt: KdrCxt, state: GedraStateContext): List<Map<String, Any?>> {
        val declared = WorkflowService.get(cxt).forClient(state.row.client).workflows.values
            .filter { it.def.entry == WfEntry.normal }
            .associateBy { it.def.workflowId }
        // The workflows this form is engaged with, read off the asserted entries the recompute preserves.
        val engaged = engagedWorkflowIds(state.currentState)
        // Declared first, in registry order, then any engaged workflow the registry no longer offers -- so the
        // ordering is stable and a vanished-but-engaged workflow lands at the end rather than reordering the rest.
        val ids = declared.keys + engaged.filterNot { it in declared.keys }
        val registry = SchemaService.get(cxt).cfactsFor(state.row.client)
        val formFacts = WorkflowEligibility.formFacts(state.derivedThisPass)

        // Each declared workflow's own cfacts, then the singletons its rules emit over its current cfacts.
        val own = declared.mapValues { (_, w) -> runCfactCalc(cxt, w.def, state.row.entries, state.row.client) }
        val singletons = declared.mapValues { (id, w) ->
            if (id in engaged) WorkflowSingletons.emitted(registry, w.def, formFacts + own.getValue(id)) else emptyList()
        }
        // What the engaged workflows contribute to the form's set, in declaration order, once each.
        val contributed = LinkedHashSet<String>().apply { singletons.values.forEach { addAll(it) } }
        // Eligibility sees the earlier derivers' facts and what the *other* engaged workflows contribute -- a
        // workflow is gated by its peers, never by itself.
        fun eligibilityFacts(workflowId: String): Set<String> =
            formFacts + singletons.filterKeys { it != workflowId }.values.flatten()

        val out = ids.map { workflowId ->
            val data = linkedMapOf<String, Any?>(WFD.workflowId to workflowId)
            declared[workflowId]?.let {
                data[WFS.computedAgainstRef] = it.ref.text
                data[WFS.cfacts] = own.getValue(workflowId).sorted()
                data[WFS.singletonCfacts] = singletons.getValue(workflowId)
                val failures = WorkflowEligibility.failures(registry, it.def, eligibilityFacts(workflowId))
                data[WFS.eligible] = failures.isEmpty()
                data[WFS.eligibilityFailures] = WorkflowEligibility.failureEntries(failures)
            }
            mapOf(GE.traitId to WFS.workflowState, GE.data to data)
        }
        // The contribution to the form's one cfacts set; the recompute merges it with the survey's.
        return if (contributed.isEmpty()) out else out + mapOf(GE.traitId to GT.cfacts, GE.data to mapOf(GT.facts to contributed.toList()))
    }
}

/**
 * The workflow ids [entries] say this form is currently engaged with (issue #794) -- read from the asserted
 * [WFS.workflowEngagement] entries, in the order they appear. A disengaged entry ([WFS.engaged] false) keeps its
 * trail but is not engaged, so it is left out.
 */
fun engagedWorkflowIds(entries: List<Map<String, Any?>>): List<String> = entries
    .filter { it[GE.traitId].toOptStr() == WFS.workflowEngagement }
    .map { it[GE.data].toJsonMapOrEmpty() }
    .filter { it[WFS.engaged] == true }
    .mapNotNull { it[WFD.workflowId].toOptStr() }
