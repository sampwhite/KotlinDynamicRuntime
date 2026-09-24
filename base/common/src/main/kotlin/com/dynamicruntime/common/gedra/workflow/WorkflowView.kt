package com.dynamicruntime.common.gedra.workflow

import com.dynamicruntime.common.util.toJsonListOfMaps
import com.dynamicruntime.common.util.toJsonMapOrEmpty
import com.dynamicruntime.common.content.MarkdownFragmentService
import com.dynamicruntime.common.context.KdrCxt
import com.dynamicruntime.common.exception.KdrException
import com.dynamicruntime.common.gedra.GE
import com.dynamicruntime.common.gedra.GedraDataType
import com.dynamicruntime.common.gedra.GedraTrait
import com.dynamicruntime.common.gedra.deriveEntryData
import com.dynamicruntime.common.schema.SCH
import com.dynamicruntime.common.schema.collectDefClosure
import com.dynamicruntime.common.schema.refName
import com.dynamicruntime.common.schema.resolveDeliveredLayouts
import com.dynamicruntime.common.schema.toWireMap
import com.dynamicruntime.common.startup.SchemaService
import com.dynamicruntime.common.uiblock.filterByCFacts
import com.dynamicruntime.common.user.UserService
import com.dynamicruntime.common.util.toOptLong
import com.dynamicruntime.common.util.toOptStr

/**
 * Resolves a declared workflow into the **view** the creation page renders (issue #534): tasks with their
 * traits in task-layout order, each trait a `$ref` into *this client's* schema with the workflow's required flag
 * beside it, saves with resolved labels, and the target facts about each task -- run through the same content
 * pipeline every other rendered surface uses, so the page renders JSON and never learns what a task is.
 *
 * The resolution is the pipeline the design settled on:
 *  - **Labels** get `MarkdownFragmentService.backendPass`: a `%{...}` block resolves here -- a fragment pull
 *    with this client's overlays, a parameter, a `?:` default -- and a `${...}` block is left for the
 *    frontend, so a label can be part backend copy and part live value.
 *  - **Each task is filtered by its own cfacts** with `filterByCFacts`, the target facts about *that task*
 *    ([WfTaskFacts]) assembled beside the request's. The model carries no `cfactExpression` yet (selectors
 *    are deferred), so nothing is dropped today -- but the seam is real and per-task, which is what a
 *    single-set filter over the whole view could not be: "this shows when the task is complete" is a fact
 *    about one task, and the first row's answer must not decide the second's.
 *
 * The view is **self-contained**: each trait's `schemaRef` resolves against a `$defs` the view carries, a
 * closure of exactly the types the workflow references and their dependencies ([collectDefClosure]) -- not the
 * client's endpoint catalog. That is what lets a page render a workflow with one call rather than fetching
 * hundreds of unrelated endpoint types to resolve a few fields, and it is what a workflow that **narrows** a
 * trait's schema will need: the reachable body is then the workflow's own, with nowhere else it could live.
 *
 * [entriesByTask] supplies the entries a running workflow already holds, so a task's completeness is real; a
 * creation workflow has none, so it defaults empty. The resolver stays a pure function of the definition,
 * the client's schema, and its fragments -- no gedra is read here.
 */
fun resolveWorkflowView(
    cxt: KdrCxt,
    declared: WfDeclared,
    entriesByTask: Map<String, List<Map<String, Any?>>> = emptyMap(),
    ownerAttributes: Map<String, Any?> = emptyMap(),
    /** The form's approvals of this workflow's approval tasks (issue #787), task id to entry data; see WorkflowApprovals.forView. */
    approvals: Map<String, Map<String, Any?>> = emptyMap(),
    /** For a normal workflow viewed against a form: where the form stands with it (issue #791); see [WorkflowFormFacts]. */
    formFacts: WorkflowFormFacts? = null,
): Map<String, Any?> {
    val client: String = cxt.client
    @Suppress("VariableInitializerIsRedundant2")
    val fragments = MarkdownFragmentService.get(cxt)
    val cfacts = SchemaService.get(cxt).cfactsFor(client)
    // Every trait an admitted workflow names is one the client can see (the boot check in #533 guaranteed it),
    // so this map is total over the workflow's traits.
    val traitsById: Map<String, GedraTrait> =
        SchemaService.get(cxt).gedraTraitsFor(client).associateBy { it.traitId }
    // The client's schema store (its variant, so a narrowed type is that client's) -- what the returned closure
    // and its layouts are drawn from. The trait pointers the view hands out are resolved against this store's
    // `$defs` subset, not the catalog. The *served* form (issue #584): each type's `g-layout` already stripped,
    // since a layout is delivered out-of-band (below) and never rides in a served schema.
    val clientStore = SchemaService.get(cxt).storeFor(client)
    // The trait data types the workflow references, collected as they are rendered; the seeds of the closure.
    val seedRefs = LinkedHashSet<String>()
    // The request-scoped cfacts, computed once: they are the same for every task, so only each task's own
    // target facts are unioned onto them below (each cfact source can do real work -- e.g., a section check).
    val requestFacts = cfacts.assemble(cxt)
    // What is about the *viewer* (issue #786), evaluated per task but looked up once per view -- the same reason
    // the request facts above are hoisted.
    val viewerCfacts = ViewerCfacts(cxt, client)

    fun label(text: String): String = fragments.backendPass(cxt, text)

    fun traitView(ref: WfTraitRef): Map<String, Any?> {
        val trait = traitsById[ref.traitId]
            ?: throw KdrException(
                "Workflow '${declared.ref}' collects trait '${ref.traitId}', which client '$client' cannot see. " +
                    "A boot check should have refused this workflow.",
            )
        // The trait's own *data* pointer -- what a field edits, not its entry envelope: the page fills in the
        // data and the save wraps it into `{traitId, data}`. Read from the trait rather than re-derived: a
        // trait declared with a `dataType` `$ref` has no manufactured `<Name>Data` type, so re-spelling one
        // would dangle. `GedraTrait.dataSchema` is always a `$ref` -- the author's own, or the one generated
        // for inline data -- so it is the pointer in both authoring styles.
        val dataRef = trait.dataSchema[SCH.dRef].toOptStr()
            ?: throw KdrException($$"Trait '$${ref.traitId}' has no data $ref to render against.")
        refName(dataRef)?.let { seedRefs.add(it) }
        return linkedMapOf(
            WFD.traitId to ref.traitId,
            WFD.required to ref.required,
            WVF.schemaRef to dataRef,
        )
    }

    // Each task's status (issues #700, #785), from the one computation the stored CTA also uses
    // (`WorkflowTaskStatus`), so the rail, `focusTask`, the `wfIsCta` fact and the forms list cannot disagree.
    val statuses = declared.def.tasks.map {
        it to WorkflowTaskStatus.of(cxt, client, it, entriesByTask[it.id] ?: emptyList(), approved = it.id in approvals)
    }
    val statusById = statuses.associate { (task, status) -> task.id to status }
    // The CTA (issue #785): the earliest task not both complete and valid. The rail opens on it when the URL names
    // no task (`focusTask`, issue #700), and its task carries the `wfIsCta` fact a selector can choose on.
    val ctaTaskId = WorkflowTaskStatus.cta(statuses)?.first?.id

    // The task's status for the task rail: complete/valid in the survey's words, plus each problem as the
    // kernel's own failure wire map with its trait, so the page reads it by the same rule it reads any reported
    // failure (the author's wording first).
    fun taskStatus(status: WfTaskStatus): Map<String, Any?> {
        val problems = status.failures.flatMap { (traitId, fs) -> fs.map { f -> f.toWireMap() + (GE.traitId to traitId) } }
        return status.toStateMap() + (WVF.problems to problems)
    }

    // An approval task's approval, resolved for the page (issue #787): its copy through the backend pass like any
    // label, and -- once approved -- when, and by whom as the name the reviewer shows others (`publicName`). Not
    // their private full name nor their internal user id: this view reaches the form's owner, who could not read
    // the reviewer's user row, and "approved by ..." needs neither.
    fun approvalView(approval: WfApproval, record: Map<String, Any?>?): Map<String, Any?> {
        val out = linkedMapOf<String, Any?>(
            WFD.cfact to approval.cfact,
            WFD.prompt to label(approval.prompt),
            WFD.button to label(approval.button),
            WVF.approved to (record != null),
        )
        if (record != null) {
            out[WFS.approvedAt] = record[WFS.approvedAt]
            record[WFS.approvedBy].toOptLong()
                ?.let { UserService.getOrNull(cxt)?.queryByUserId(cxt, it) }
                ?.let { out[WVF.approvedByName] = it.publicName() }
        }
        return out
    }

    fun taskView(task: WfTask): Map<String, Any?> {
        val entries = entriesByTask[task.id] ?: emptyList()
        // Draw the traits in the page's order, each already a ref+flag; the task layout named the order, and any
        // trait it left out follows in declaration order (`WfTask.displayOrder`). Today that order is all the task
        // layout does, so the layout itself is not sent: nothing on the page would read it (issue #834).
        val byId = task.traits.associateBy { it.traitId }
        val orderedTraits = task.displayOrder.mapNotNull { byId[it] }.map { traitView(it) }
        // The task's own facts, plus what its viewerCfacts functions conclude about the person looking at it
        // (issue #786) -- a reviewer, say. Temporary by nature: they are about this viewer, so never stored.
        val taskFacts = WfTaskFacts.of(task, entries, isCta = task.id == ctaTaskId, approved = task.id in approvals) +
            viewerCfacts.forTask(task)
        val raw = linkedMapOf<String, Any?>(
            WFD.id to task.id,
            WFD.label to label(task.label),
            WFD.traits to orderedTraits,
            WFD.saves to task.saves.map { linkedMapOf(WFD.id to it.id, WFD.label to label(it.label), WFD.kind to it.kind.name) },
            WVF.facts to taskFacts.toList(),
            WVF.status to taskStatus(statusById.getValue(task.id)),
        )
        task.approval?.let { raw[WVF.approval] = approvalView(it, approvals[task.id]) }
        // How the task is shown (issue #788): usually a selector, which the filter below resolves against this
        // caller's task facts -- so what arrives is the one branch that applies, never the others or their tests.
        task.display?.let { raw[WFD.display] = it }
        // The entries the page seeds each field from: the task's stored ones (a survey edit; a creation view has
        // none), each trait's g-derived data values computed on read (issue #712) and then decorated with any
        // prefillData defaults (issue #679). Both enrich only the *presented* set, never the `entries` above that
        // `taskFacts` and `taskStatus` judge completeness and validity from -- a derived value is for display, so
        // it must not count toward requiredness (a person never entered it), exactly as a prefill default does
        // not. A real value always wins over a default. The workflow gedra kind is formDoc, as the save path
        // (`WorkflowSave`) itself hardcodes -- the only kind a workflow collects today. The gedra's client is
        // `cxt.client`: the survey read (`surveyFormRow`) confines the form to it, and a creation view has none
        // yet, so it is the caller's own client either way.
        val derived = deriveEntryData(cxt, GedraDataType.formDoc, entries, cxt.client)
        val presented = runPrefillData(cxt, task, ownerAttributes, derived)
        if (presented.isNotEmpty()) raw[WVF.entries] = presented
        // The content pipeline, per task: the request facts (hoisted) plus this task's own, then drop anything
        // gated on a cfact they do not satisfy, and put each selector's chosen branch in its place.
        val shown = filterByCFacts(raw, requestFacts + taskFacts, cfacts::parse)
        // The chosen display branch's text through the backend pass, as a label is -- after the choice, so only the
        // branch that is shown is evaluated. `${'$'}{…}` survives for the frontend, per the layout rule.
        val display = shown[WFD.display] as? Map<*, *> ?: return shown
        val text = display[WDSP.text].toOptStr() ?: return shown
        return shown + (WFD.display to display.entries.associate { it.key.toString() to it.value } + (WDSP.text to label(text)))
    }

    // Tasks first: rendering them collects the trait refs the closure needs.
    val taskViews = declared.def.tasks.map { taskView(it) }
    // The self-contained schema: exactly the types the trait refs reach, and their dependencies.
    val defs = collectDefClosure(seedRefs, clientStore.servedDefs)
    // The earliest task still needing action -- the CTA, computed once above. Absent when every task is done.
    val focusTask = ctaTaskId
    val view = linkedMapOf<String, Any?>(
        WVF.found to true,
        WFD.workflowId to declared.def.workflowId,
        WVF.ref to declared.ref.text,
        WFD.entry to declared.def.entry.name,
        // The workflow's own label (issue #719), resolved like a task's; empty when the definition gives none,
        // and the page then titles itself.
        WFD.label to label(declared.def.label),
        WVF.showTaskList to declared.def.showTaskList,
        WFD.tasks to taskViews,
        SCH.dDefs to defs,
        // The caller's frontend-delivered cfacts (issue #569), so the page evaluates a rendered trait's
        // `g-visibleWhen` -- an admin-only field is hidden from an ordinary caller here as on the endpoint form.
        // The served schema keeps the field for everyone; only the page hides it, against this map. Built from
        // `requestFacts`, already assembled above for the per-task filter, so the cfact sources run once.
        WVF.cfacts to cfacts.deliveredCfacts(requestFacts),
        // The third parallel closure (issue #585): the `g-layout` of each type in `$defs` that has one, joined
        // to a trait's data type by name on the page. From the same store the closure came from, so a client
        // that narrowed a trait's type gets the layout pruned to what it kept. These are field layouts; the task
        // layout is a different thing, already applied to each task's trait order above.
        WVF.fieldLayouts to resolveDeliveredLayouts(cxt, clientStore.layoutsFor(defs)),
    )
    focusTask?.let { view[WVF.focusTask] = it }
    if (declared.def.entry == WfEntry.normal) view[WVF.phase] = WorkflowPhases.of(cxt, declared.def).name
    formFacts?.let { f ->
        view[WVF.engaged] = f.engaged
        if (!f.engaged) {
            view[WVF.eligible] = f.eligible
            if (f.reasons.isNotEmpty()) view[WVF.ineligibleReasons] = f.reasons
        }
    }
    return view
}

/** The "no workflow" answer -- what the view returns when a client has no such (or no creation) workflow. */
fun noWorkflowView(): Map<String, Any?> = linkedMapOf(WVF.found to false)

/**
 * Where a form stands with a normal workflow its view is resolved against (issue #791): [engaged] with it, and when
 * not, whether it is [eligible] and the resolved [reasons] it is not -- what lets the page offer Engage only when
 * engaging can succeed, and say why not otherwise.
 */
class WorkflowFormFacts(val engaged: Boolean, val eligible: Boolean, val reasons: List<String>) {
    companion object {
        /**
         * From the form's stored [states]: engagement off the asserted entry, eligibility off the workflow's derived
         * entry -- the same entry the engage endpoint gates on, so the page and the refusal agree. A form with no
         * entry for the workflow yet counts as not eligible, which is what the engage call would then find.
         */
        fun of(cxt: KdrCxt, def: WfDef, states: List<Map<String, Any?>>): WorkflowFormFacts {
            val engaged = def.workflowId in engagedWorkflowIds(states)
            val entry = states.firstOrNull {
                it[GE.traitId].toOptStr() == WFS.workflowState && it[GE.data].toJsonMapOrEmpty()[WFD.workflowId].toOptStr() == def.workflowId
            }?.get(GE.data)?.toJsonMapOrEmpty()
            val eligible = entry?.get(WFS.eligible) == true
            val failures = entry?.get(WFS.eligibilityFailures).toJsonListOfMaps().mapNotNull { it[WFD.id].toOptStr() }
            val reasons = if (engaged || eligible) emptyList() else WorkflowEligibility.explain(cxt, def, failures)
            return WorkflowFormFacts(engaged, eligible, reasons)
        }
    }
}
