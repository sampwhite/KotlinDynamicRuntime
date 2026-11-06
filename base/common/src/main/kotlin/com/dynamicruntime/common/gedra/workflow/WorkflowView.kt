package com.dynamicruntime.common.gedra.workflow

import com.dynamicruntime.common.util.toJsonListOfMaps
import com.dynamicruntime.common.util.toJsonMapOrEmpty
import com.dynamicruntime.common.content.MarkdownFragmentService
import com.dynamicruntime.common.context.KdrCxt
import com.dynamicruntime.common.exception.KdrException
import com.dynamicruntime.common.gedra.DSV
import com.dynamicruntime.common.gedra.DesignView
import com.dynamicruntime.common.gedra.GE
import com.dynamicruntime.common.gedra.GedraTrait
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
 * Resolves a declared workflow into the **view** a workflow page renders (issue #534) -- a creation, a core details
 * workflow, or a normal workflow against its form (issue #794): tasks with their traits in task-layout order, each
 * trait a `$ref` into *this client's* schema with the workflow's required flag beside it, saves with resolved labels,
 * and the target facts about each task -- run through the same content pipeline every other rendered surface uses, so
 * the page renders JSON and never learns what a task is.
 *
 * The resolution is the pipeline the design settled on:
 *  - **Labels** get `MarkdownFragmentService.backendPass`: a `%{...}` block resolves here -- a fragment pull
 *    with this client's overlays, a parameter, a `?:` default -- and a `${...}` block is left for the
 *    frontend, so a label can be part backend copy and part live value.
 *  - **Each task is filtered by its own cfacts** with `filterByCFacts`, the target facts about *that task*
 *    ([WfTaskFacts]) assembled beside the request's -- today what resolves a task's display selector (issue
 *    #788) to its one applicable branch. The seam is per-task, which is what a single-set filter over the
 *    whole view could not be: "this shows when the task is complete" is a fact about one task, and the first
 *    row's answer must not decide the second's.
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
 *
 * Theme: #TPLPS.
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
    /** The form's traits locked for this caller (issue #857), as [TraitLocks.describe] gives them; empty for none. */
    lockedTraits: List<Map<String, Any?>> = emptyList(),
    /** Whether the form is kept to its owner from this caller (issue #1433), so no task of it can be saved; see [OwnerOnly]. */
    keptToOwner: Boolean = false,
    /** All of the form's entries (issue #1595): what the CTA's filter is concluded from. Empty for a creation view. */
    formEntries: List<Map<String, Any?>> = emptyList(),
    /** The caller's relation to the form (issue #1595), as [RowCfacts.of] works it out: `kdr:isOwner` for its owner. */
    rowFacts: Set<String> = emptySet(),
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
    // The workflow's own view of those types (issue #984): the client's, with the workflow's type alterations applied --
    // its own wording for the forms it draws. The client's store itself when the workflow alters nothing.
    val store = workflowSchemaStore(clientStore, declared)
    // The trait data types the workflow references, collected as they are rendered; the seeds of the closure.
    val seedRefs = LinkedHashSet<String>()
    // The request-scoped cfacts, computed once: they are the same for every task, so only each task's own
    // target facts are unioned onto them below (each cfact source can do real work -- e.g., a section check). The
    // caller's relation to the form (issue #1595) is the form's, not the request's, so it is handed to the judge apart.
    val requestFacts = cfacts.assemble(cxt)
    // Each task's status and facts for this caller and this form (issues #700, #785, #786, #856, #1595) -- the one
    // computation the save and approve endpoints judge by too, so the page's buttons and the endpoints' refusals agree.
    val judge = WorkflowTaskJudge(cxt, client, declared, entriesByTask, approvals, requestFacts, rowFacts, formEntries)

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
    // (`WorkflowTaskStatus`), so the rail, `focusTask`, the `kdr:isCta` fact and the forms list cannot disagree.
    val statusById = judge.statusById
    // The CTA (issue #785): the earliest task not both complete and valid. The rail opens on it when the URL names
    // no task (`focusTask`, issue #700), and its task carries the `kdr:isCta` fact a selector can choose on.
    val ctaTaskId = judge.ctaTaskId

    // The task's status for the task rail: complete/valid in the core details workflow's words, plus each problem as
    // the kernel's own failure wire map with its trait, so the page reads it by the same rule it reads any reported
    // failure (the author's wording first).
    fun taskStatus(status: WfTaskStatus): Map<String, Any?> {
        val problems = status.failures.flatMap { (traitId, fs) -> fs.map { f -> f.toWireMap() + (GE.traitId to traitId) } }
        return status.toStateMap() + (WVF.problems to problems)
    }

    // An approval task's approval, resolved for the page (issue #787): its copy through the backend pass like any
    // label, and -- once approved -- when, and by whom as another person sees the reviewer called (`shownName`, issue
    // #1430): the name on their profile, else their public name, which is their email while they have chosen no
    // username. Not their internal user id: this view reaches the form's owner, who could not read the reviewer's user
    // row. The workflow's own text decides what to show (`contains(approvedByName, "@")` finds an email), and the
    // page's default line leaves out a name that is one.
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
                ?.let { out[WVF.approvedByName] = it.shownName() }
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
        val taskFacts = judge.taskFacts(task)
        val raw = linkedMapOf<String, Any?>(
            WFD.id to task.id,
            WFD.label to label(task.label),
            WFD.traits to orderedTraits,
            WFD.saves to task.saves.map { linkedMapOf(WFD.id to it.id, WFD.label to label(it.label), WFD.kind to it.kind.name) },
            WVF.facts to taskFacts.toList(),
            WVF.status to taskStatus(statusById.getValue(task.id)),
        )
        // Whether this caller may save the task (issue #856), by the rule the save endpoint enforces -- so the page
        // offers Save only where it can succeed. A normal workflow's tasks only; the others have no such rule.
        // A form kept to its owner from this caller (issue #1433) can be saved in no task, whichever kind of workflow.
        if (keptToOwner) {
            raw[WVF.canSave] = false
        } else if (declared.def.entry == WfEntry.normal) {
            raw[WVF.canSave] = judge.maySave(task, taskFacts)
        }
        // Who the CTA waits on, when its filter is what keeps this caller from acting (issue #1595) -- said even of a
        // form kept to its owner, since it is who the step is for, not why this caller may not change the form.
        if (declared.def.entry == WfEntry.normal) judge.waitingOn(task)?.let { raw[WVF.waitingOn] = label(it) }
        task.approval?.let {
            raw[WVF.approval] = approvalView(it, approvals[task.id])
            // Whether this caller may approve it, by the rule the approve endpoint enforces -- so the page offers the
            // button only where it can succeed.
            raw[WVF.canApprove] = judge.mayApprove(task, taskFacts)
        }
        // How the task is shown (issue #788): usually a selector, which the filter below resolves against this
        // caller's task facts -- so what arrives is the one branch that applies, never the others or their tests.
        task.display?.let { raw[WFD.display] = it }
        // The entries the page seeds each field from: the task's stored ones (a core details edit; a creation view has
        // none), their computed values already stored by the traits' `g-onWrite` functions (issue #1501), decorated
        // with any prefillData defaults (issue #679). A default enriches only the *presented* set, never the
        // `entries` above that `taskFacts` and `taskStatus` judge completeness and validity from -- a person never
        // entered it, so it must not count toward requiredness. A real value always wins over a default.
        val presented = runPrefillData(cxt, task, ownerAttributes, entries)
        if (presented.isNotEmpty()) raw[WVF.entries] = presented
        // The content pipeline, per task: the request facts (hoisted) plus this task's own, then drop anything
        // gated on a cfact they do not satisfy, and put each selector's chosen branch in its place.
        val shown = filterByCFacts(raw, judge.callerFacts + taskFacts, cfacts::parse)
        // The chosen display branch's text through the backend pass, as a label is -- after the choice, so only the
        // branch that is shown is evaluated. `${'$'}{…}` survives for the frontend, per the layout rule.
        val display = shown[WFD.display] as? Map<*, *> ?: return shown
        val text = display[WDSP.text].toOptStr() ?: return shown
        return shown + (WFD.display to display.entries.associate { it.key.toString() to it.value } + (WDSP.text to label(text)))
    }

    // Tasks first: rendering them collects the trait refs the closure needs.
    val taskViews = declared.def.tasks.map { taskView(it) }
    // The self-contained schema: exactly the types the trait refs reach, and their dependencies.
    val defs = collectDefClosure(seedRefs, store.servedDefs)
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
        WVF.fieldLayouts to resolveDeliveredLayouts(cxt, store.layoutsFor(defs)),
    )
    focusTask?.let { view[WVF.focusTask] = it }
    // Help under the title (issue #1221): copy outside the definition, so whoever explains a workflow need not change
    // it. Through the backend pass like a label, so it may pull other copy; blank is no help.
    fragments.resolveFragment(cxt, WVF.helpFile, client, declared.def.workflowId)
        ?.let { label(it) }?.takeIf { it.isNotBlank() }
        ?.let { view[WVF.help] = it }
    // Design View (issue #972): where the workflow and each type the page draws were declared. Added beside an
    // otherwise identical view, and only for a caller entitled to it, so an ordinary response never carries it.
    if (DesignView.isOn(cxt)) view[DSV.designBlock] = DesignView.workflowBlock(cxt, declared, defs, clientStore, store)
    if (lockedTraits.isNotEmpty()) view[WVF.lockedTraits] = lockedTraits
    if (keptToOwner) view[WVF.keptToOwner] = true
    if (declared.def.entry == WfEntry.normal) view[WVF.phase] = WorkflowPhases.of(cxt, client, declared.def).name
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

/**
 * What each task of [declared] is judged on for one caller and one form (issues #700, #785, #786, #856, #1595): the
 * tasks' statuses and the CTA among them, each task's facts -- its own, and what its `viewerCfacts` functions conclude
 * about the caller -- and whether the caller may save or approve it. Shared by the view, which shows the answers, and
 * the save and approve endpoints, which enforce them, so a page and an endpoint cannot disagree.
 *
 * ### The CTA's filter rule, step by step
 *
 * A CTA filter is a **rule** (a cfact expression), not a status, and the indirection is kept in plain sight here:
 *
 *  1. **The rule is worked out** from the form's data as it stands ([cta], by [ctaAssignment] -- what the state's
 *     deriver stores as `WFS.ctaFilterRule`, so the two agree whenever the state is current).
 *  2. **It is evaluated once per task for this caller** (`passesFilter`, by [passesCtaFilterRule]), and the answer is
 *     a fact of its own, [WFC.passesCtaFilter], in [taskFacts]. No other fact changes: `kdr:reviewer` still means only
 *     what the task's functions say.
 *  3. **Each permission asks that fact**: [maySave] for a task that offers a save, [mayApprove] for an approval, and
 *     [waitingOn] for the reason a page gives. The filter only narrows the task's own rule, never widens it.
 *
 * `requestFacts` are the request's alone, the same for every form; `rowFacts` the caller's relation to **this** form
 * ([RowCfacts]); [callerFacts] both, which every rule here is judged over. Kept apart so a set built once for a page
 * can never carry one row's `kdr:isOwner` to the next.
 */
class WorkflowTaskJudge(
    cxt: KdrCxt,
    client: String,
    val declared: WfDeclared,
    /** The form's entries each task collects ([taskEntriesOf]): what a task's completeness is judged on. */
    private val entriesByTask: Map<String, List<Map<String, Any?>>>,
    /** The form's approvals of this workflow, by task id. */
    private val approvals: Map<String, Map<String, Any?>>,
    /** The request's cfacts alone, assembled once by whoever already holds them: the same for every form. */
    requestFacts: Set<String>,
    /** The caller's relation to this form ([RowCfacts.of]): `kdr:isOwner` for its owner. Per form, never shared. */
    rowFacts: Set<String>,
    /** All of the form's entries: what the CTA's filter rule is worked out from. Empty for a creation view. */
    formEntries: List<Map<String, Any?>>,
    /** The caller's viewer facts -- one shared by a caller judging many forms, so each is worked out once. */
    private val viewer: ViewerCfacts = ViewerCfacts(cxt, client),
) {
    private val cfacts = SchemaService.get(cxt).cfactsFor(client)

    /** The request's facts and the caller's relation to this form: what every rule here is judged over. */
    val callerFacts: Set<String> = requestFacts + rowFacts

    val statuses: List<Pair<WfTask, WfTaskStatus>> = declared.def.tasks.map {
        it to WorkflowTaskStatus.of(cxt, client, it, entriesByTask[it.id] ?: emptyList(), approved = it.id in approvals, declared = declared)
    }
    val statusById: Map<String, WfTaskStatus> = statuses.associate { (task, status) -> task.id to status }

    /** The CTA: the earliest task not both complete and valid, or null when every task is done. */
    private val ctaTask: WfTask? = WorkflowTaskStatus.cta(statuses)?.first
    val ctaTaskId: String? = ctaTask?.id

    /**
     * Step 1: what the CTA carries (issue #1595) -- its keyword, and its filter **rule** -- worked out from the form's
     * data now. Lazy, since most judgments (a task with no filter function, a check of a task that is not the CTA)
     * never ask.
     */
    val cta: CtaAssignment? by lazy { ctaTask?.let { ctaAssignment(cxt, cfacts, declared.def.workflowId, it, formEntries) } }

    /**
     * Step 2: whether the caller passes [task]'s CTA filter rule (issue #1595). True for every task but the CTA, and for
     * a CTA carrying no rule.
     */
    private fun passesFilter(task: WfTask): Boolean =
        task.id != ctaTaskId || passesCtaFilterRule(cfacts, cta?.filterRule, callerFacts + viewer.forTask(task))

    /**
     * The workflow's fact for its CTA's search keyword (issue #1596): `kdr:ctaReview` while the CTA carries `review`, on
     * every task, since it is about the workflow -- what the state's deriver stores in its own cfacts.
     */
    private val keywordFacts: Set<String> by lazy { setOfNotNull(CTAK.of(cta?.keyword)?.workflowCfact) }

    /**
     * [task]'s own facts, the caller's viewer facts for it, the workflow's CTA keyword fact, and [WFC.passesCtaFilter]
     * when the caller passes its CTA filter rule.
     */
    fun taskFacts(task: WfTask): Set<String> {
        val own = WfTaskFacts.of(task, entriesByTask[task.id] ?: emptyList(), isCta = task.id == ctaTaskId, approved = task.id in approvals)
        return buildSet {
            addAll(own)
            addAll(viewer.forTask(task))
            addAll(keywordFacts)
            if (passesFilter(task)) add(WFC.passesCtaFilter)
        }
    }

    /**
     * Step 3, a save (issues #856, #1595): [task]'s rule over [callerFacts] and [facts] -- no rule admits anyone --
     * **and**, on a task that offers a save, [WFC.passesCtaFilter]. An approval task offers none; its filter is
     * [mayApprove]'s to apply.
     */
    fun maySave(task: WfTask, facts: Set<String> = taskFacts(task)): Boolean =
        (task.saveWhen?.let { cfacts.parse(it).matches(callerFacts + facts) } ?: true) &&
            (task.saves.isEmpty() || WFC.passesCtaFilter in facts)

    /**
     * Step 3, an approval (issue #1595): [task] is an approval task whose rule ([WfApproval.effectiveApproveWhen], by
     * default a reviewer who does not own the form) passes over [callerFacts] and [facts], **and** the caller has
     * [WFC.passesCtaFilter]. Whether the task is current and not yet approved is the approve endpoint's to refuse and
     * the display's to show.
     */
    fun mayApprove(task: WfTask, facts: Set<String> = taskFacts(task)): Boolean {
        val approval = task.approval ?: return false
        return cfacts.parse(approval.effectiveApproveWhen).matches(callerFacts + facts) && WFC.passesCtaFilter in facts
    }

    /**
     * Step 3, the reason (issue #1595): why the caller may not act on [task] for its CTA filter rule, or null when the
     * rule is no obstacle. The rule's label (a template, for the caller's backend pass) or, when it has none, a plain
     * sentence: what a page gives as the reason, and what the endpoints refuse with.
     */
    fun waitingOn(task: WfTask): String? =
        if (passesFilter(task)) null else cta?.filterLabel ?: "This step is waiting on someone else."
}

/**
 * A form's [entries] split by the task of [declared] that collects each one (issues #700, #857) -- what a task's
 * completeness and facts are judged on. A trait two tasks collect is in both.
 */
fun taskEntriesOf(declared: WfDeclared, entries: List<Map<String, Any?>>): Map<String, List<Map<String, Any?>>> =
    declared.def.tasks.associate { task ->
        val traitIds = task.traits.map { it.traitId }.toSet()
        task.id to entries.filter { it[GE.traitId].toOptStr() in traitIds }
    }
