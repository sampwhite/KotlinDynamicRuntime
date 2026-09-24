package com.dynamicruntime.common.gedra.workflow

import com.dynamicruntime.common.context.KdrCxtBase
import com.dynamicruntime.common.exception.KdrException
import com.dynamicruntime.common.gedra.GE
import com.dynamicruntime.common.schema.SCT
import com.dynamicruntime.common.schema.SchType
import com.dynamicruntime.common.schema.coerceAndValidate
import com.dynamicruntime.common.schema.parseSchemaTypes
import com.dynamicruntime.common.schema.schemaDefs
import com.dynamicruntime.common.uiblock.UIB
import com.dynamicruntime.common.util.isVariableName
import com.dynamicruntime.common.util.toJsonListOfMaps
import com.dynamicruntime.common.util.toJsonMapOrEmpty
import com.dynamicruntime.common.util.toOptStr
import kotlin.time.Instant

/**
 * The field names of a workflow definition's JSON, and the names of the schema types that describe it
 * (issue #533). Each name matches its value.
 *
 * A definition is **data first**: it is authored as a JSON map -- by the `workflow(...)` builder in source, or
 * arriving as a row, a form, or a generated blob -- validated against [WfDefSchema], and only then read into
 * the Kotlin model. That is what lets every author travel one path, and it is why the string-encoded forms
 * (`"required": "true"`, `"order": "name,expenseReport"`) are accepted: the schema layer's coercion turns them
 * into the boolean and the list, exactly as it does for every other kd2 type.
 */
@Suppress("ConstPropertyName")
object WFD {
    /** The namespace the definition schema's types live in. */
    const val namespace = "wfdef"

    const val defType = "WfDef"
    const val taskType = "WfTask"
    const val traitRefType = "WfTraitRef"
    const val saveType = "WfSave"
    const val layoutType = "WfLayout"
    const val eligibilityType = "WfEligibility"
    const val singletonType = "WfSingleton"
    const val approvalType = "WfApproval"
    const val windowType = "WfWindow"
    const val lockType = "WfLock"

    const val workflowId = "workflowId"
    const val entry = "entry"
    const val tasks = "tasks"

    /** The workflow function usages a def (global events) or a task (task events) declares (issue #677). */
    const val functions = "functions"

    /** The discriminator on a function usage: which function kind it is (like `traitId` for an entry). */
    const val fn = "fn"

    /** A function usage's run order within its scope's list (issue #677). */
    const val priority = "priority"

    const val id = "id"
    const val label = "label"
    const val traits = "traits"
    const val saves = "saves"
    const val layout = "layout"

    const val traitId = "traitId"
    const val required = "required"

    const val kind = "kind"

    const val order = "order"
    const val edit = "edit"

    /**
     * A normal workflow's ordered eligibility tests (issue #783): each an [id], a cfact [test] the form must
     * meet, and the [explanation] shown when it does not.
     */
    const val eligibility = "eligibility"

    /** On an eligibility entry: the cfact expression the form's cfacts must match. */
    const val test = "test"

    /** On an eligibility entry: why the form is not eligible when [test] fails -- a template, like a label. */
    const val explanation = "explanation"

    /**
     * A normal workflow's singleton-cfact rules (issue #784): each emits a framework singleton cfact ([cfact],
     * one of [WSC.all]) about the form when its [kWhen] expression matches the workflow's current cfacts.
     */
    const val singletons = "singletons"

    /** On a singleton rule: the framework singleton cfact it emits. */
    const val cfact = "cfact"

    /** On a singleton rule: the cfact expression over the workflow's current cfacts that emits it. */
    const val kWhen = "when"

    /**
     * On a task: makes it an **approval task** (issue #787) -- a button a reviewer presses rather than traits a
     * person fills in. Holds the [cfact] an approval emits, and the [prompt] and [button] copy.
     */
    const val approval = "approval"

    /** On an approval: the text shown above the button -- a template, evaluated in two passes like a label. */
    const val prompt = "prompt"

    /** On an approval: the button's own text -- a template, like a label. */
    const val button = "button"

    /**
     * On a task: how it is shown (issue #788) -- one display branch ([WDSP]), or, usually, a UiBlock **selector**
     * (`select`) choosing among several by the caller's task facts. What turns an approval task into "approved by
     * ...", "waiting on earlier work", the approve button, or "wait for a reviewer", as the case may be.
     */
    const val display = "display"

    /**
     * A normal workflow's three nested, optional time windows (issue #790), outermost first: outside its
     * [lifetime] the workflow is as good as not configured; outside [relevancy] nothing is calculated for it;
     * outside [engagement] no form may newly engage. See [WfWindows].
     */
    const val lifetime = "lifetime"
    const val relevancy = "relevancy"
    const val engagement = "engagement"

    /** On a time window: its first instant, inclusive. */
    const val start = "start"

    /** On a time window: the instant it closes, exclusive. */
    const val end = "end"

    /**
     * On a normal workflow's task (issue #856): who may save it -- a cfact expression over the same facts the task's
     * display chooses on (the request's, the task's own, and the viewer's from its `viewerCfacts` functions).
     * Enforced by the save endpoint; absent means anyone who can see the form.
     */
    const val saveWhen = "saveWhen"

    /**
     * A normal workflow's trait locks (issue #857): each a [traitId] the form's data may not change -- by any path --
     * while the form is engaged with the workflow and the lock's [kWhen] condition holds, except by whoever may save
     * its [writableVia] task (issue #856's rule), or by a write that overrides it where [overrideWhen] allows.
     */
    const val locks = "locks"

    /** On a lock: the task, in the same workflow and collecting the locked trait, whose savers the lock exempts. */
    const val writableVia = "writableVia"

    /** On a lock: who may override it -- a cfact expression over the writer's request facts; absent means nobody. */
    const val overrideWhen = "overrideWhen"

    /** Separates a bundle id from a workflow id in a [WfRef]'s text form. */
    const val refSep = '#'
}

/**
 * The field names of the **resolved** workflow view a `/gedra/<client>/workflow/view` call returns (issue
 * #534) -- the shape the creation page renders. It reuses [WFD]'s names where the meaning is the same
 * (`workflowId`, `entry`, `tasks`, `id`, `label`, `traits`, `traitId`, `required`, `saves`, `kind`, `layout`,
 * `order`, `edit`), and adds only what resolution produces: whether a workflow was found, the workflow's
 * stored [WfRef], each trait's data `$ref` ([schemaRef]) into the `$defs` the view **carries** (a closure of
 * just the types it references), whether the task list shows, and the target
 * facts assembled about each task.
 */
@Suppress("ConstPropertyName")
object WVF {
    /** Whether the call resolved a workflow at all -- false when the client has no such (or no creation) one. */
    const val found = "found"

    /**
     * A normal workflow's [WfPhase] at the moment of the view (issue #790), by name -- so a page can draw a frozen
     * workflow (`lifetimeOnly`) read-only and offer engaging only when `engageable`. Absent for creation and survey.
     */
    const val phase = "phase"

    /**
     * For a normal workflow viewed against a form (issue #791): whether the form is engaged with it -- so the page
     * offers to engage an eligible form, and draws the tasks as work under way once it is. Absent otherwise.
     */
    const val engaged = "engaged"

    /**
     * On each task of a normal workflow's view (issue #856): whether this caller may save it, by the task's rule for
     * who may ([WFD.saveWhen]) -- the rule the save endpoint enforces, so the page offers Save only where it works.
     */
    const val canSave = "canSave"

    /**
     * On a view resolved against a form, and the locks endpoint's answer (issue #857): the traits locked for this
     * caller, each with its `traitId`, the `workflowId` and `label` of the workflow locking it, and [canOverride] --
     * so the page draws them read-only and offers an override only to whoever may make one.
     */
    const val lockedTraits = "lockedTraits"
    const val canOverride = "canOverride"

    /**
     * For a normal workflow viewed against a form it is not engaged with (issue #791): whether the form passes its
     * eligibility tests -- so the page offers Engage only when engaging can succeed -- and, when it does not, the
     * [ineligibleReasons], resolved.
     */
    const val eligible = "eligible"
    const val ineligibleReasons = "ineligibleReasons"

    /** The workflow's stored reference, as [WfRef] text -- what a created gedra records under `creationWorkflowId`. */
    const val ref = "ref"

    /**
     * Beside a trait: the JSON-Schema `$ref` into this client's schema for the trait's **data** -- what a
     * field edits. Named apart from [ref] on purpose: the two are both strings and unrelated (a workflow
     * reference against a schema pointer), and one word for both is the collision [ref] would invite.
     */
    const val schemaRef = "schemaRef"

    /** On the view's top level: whether a page shows the task list (more than one task); see [WfDef.showTaskList]. */
    const val showTaskList = "showTaskList"

    /** Beside a trait ref: the target facts assembled about the task (`wfTaskComplete`, `wfTaskAvailable`). */
    const val facts = "facts"

    /**
     * On a task: the form's **current entries** for that task's traits, present only when the view resolved
     * against an existing gedra (a survey edit, issue #658) -- what seeds each field with its stored value. A
     * creation view carries none, since there is no form yet. Each is a stored `{traitId, data, ...}` entry.
     */
    const val entries = "entries"

    /**
     * On the view's top level: the caller's **frontend-delivered cfacts** (issue #569), `name -> present` over
     * the whole `toFrontend` vocabulary -- what the page evaluates a trait property's `g-visibleWhen` against, so
     * an admin-only field is hidden from an ordinary caller in a creation workflow exactly as it is on the
     * endpoint form. The same wire name and shape as the endpoint catalog's `cfacts` (`EI.cfacts`), so the
     * frontend gate reads either surface identically.
     */
    const val cfacts = "cfacts"

    /**
     * On the view's top level: the **per-type field layouts** (issue #585), `{ typeName -> g-layout block }` over
     * exactly the types the view's `$defs` closure carries -- the third parallel closure beside the schema and
     * the cfacts, joined to a trait's data type by name on the frontend. The task layout (`WfLayout`) is a
     * different thing, and is not sent: its order is applied to each task's traits before the view is built. The
     * same wire name and shape as the endpoint catalog's `fieldLayouts` (`EI.fieldLayouts`), so a page reads
     * either surface identically.
     */
    const val fieldLayouts = "fieldLayouts"

    /**
     * On a task: its **status** for the task rail (issue #700) -- `complete` / `valid` / `missingTraits` /
     * `invalidTraits` under the survey's own words (`SVY`), plus [problems]. Computed by the resolver from the
     * task's entries and the client's entry union with the same presence and content rules the survey's
     * stored state uses, so the rail and the forms list's status column cannot disagree.
     */
    const val status = "status"

    /**
     * Under [status]: the content failures of the task's present entries, for the rail's invalid-task tooltip.
     * Each is the kernel's one failure wire shape (`SchFailure.toWireMap()`: path, code, message, and the schema
     * author's `userMessage` when the field declares one) plus the `traitId` it belongs to -- so whatever reads a
     * reported failure reads these too, and a tooltip can later point at the field. Empty when the task's data
     * passes its schema.
     */
    const val problems = "problems"

    /**
     * On the view's top level: the id of the **earliest task still needing action** (issue #700) -- the first,
     * in task order, whose [status] is incomplete or invalid; absent when every task is complete and valid. The
     * task rail opens on it when the URL names no task, so a status-chip link lands on the work.
     */
    const val focusTask = "focusTask"

    /**
     * On an **approval task**'s view (issue #787): its approval, resolved for the page -- the [WFD.cfact], the
     * backend-passed [WFD.prompt] and [WFD.button] copy, whether it is [approved], and when so `approvedAt` and the
     * approver's [approvedByName] -- their public name, what "approved by ..." shows. Not their user id or private
     * name: the view reaches the form's owner, who could not read the reviewer's user row.
     */
    const val approval = "approval"

    /** Under [approval]: whether the task has been approved. */
    const val approved = "approved"

    /** Under [approval]: the approver's display name, for "approved by ...". */
    const val approvedByName = "approvedByName"
}

/**
 * The field names of a **workflow-save result** (issue #535) -- the shape `/gedra/<client>/workflow/save`
 * returns, either way. A save is not all-or-nothing at the HTTP level: an incomplete one is a **result**
 * ([saved] false, [unmetTraits] naming what is missing), not an error, the soft-validation shape
 * `gedra-patch.md` draws. A satisfied one carries the created gedra under [item], the shape `formDoc/create`
 * returns.
 */
@Suppress("ConstPropertyName")
object WSF {
    /** Whether the save happened. False means a required trait was missing; see [unmetTraits]. */
    const val saved = "saved"

    /** When not [saved]: the required trait ids no entry satisfied, in the order the task declares them. */
    const val unmetTraits = "unmetTraits"

    /** When [saved]: the created gedra, as `formDoc/create` returns it. */
    const val item = "item"

    /**
     * When [saved] by a survey `edit`: the **refreshed workflow view** (issue #700), re-resolved against the
     * updated form -- every task's entries and [WVF.status], and [WVF.focusTask] -- so the save is the refresh
     * and the task rail needs no second call. Absent on a create save.
     */
    const val view = "view"
}

/**
 * The cfact names reported about the task being rendered -- **target facts**, passed to the registry's
 * `assemble` rather than computed from the request (issue #533). Each is declared because something produces
 * it: `complete` and `isCta` by the status engine ([WfTaskFacts], [WFC.isCta] since #785), `reviewer` by a
 * `viewerCfacts` function such as `userHasLabel` (#786), and `available` as a placeholder that is always present
 * until availability rules exist. Eligibility and validity are not declared as task facts -- the cfact registry
 * is additive, so a name costs nothing once something produces it, while a declared name nothing produces
 * reads as a capability the deployment does not have.
 *
 * Every value carries the `wf` prefix: these are declared globally, and a global name is one no client may
 * declare for itself, so the prefix keeps the plain words (`reviewer`, `current`) free for clients.
 */
@Suppress("ConstPropertyName")
object WFC {
    /** The task's required traits are all present. */
    const val taskComplete = "wfTaskComplete"

    /** The task may be worked on now. **Always present today** -- availability rules are a later step. */
    const val taskAvailable = "wfTaskAvailable"

    /**
     * The task is the workflow's **CTA** (call to action, issue #785): the earliest task in the list that is not
     * both complete and valid -- where a person's next piece of work is. What a layout tests to draw a task as
     * current, or a later one as waiting on it.
     */
    const val isCta = "wfIsCta"

    /**
     * The person viewing the task may review it (issue #786): the framework's name for the approval authority a
     * `viewerCfacts` function concludes -- typically `userHasLabel` over a `reviewer` *label*. **Hardwired**, since
     * the approve endpoint (#787) asks for exactly this cfact -- as the needsReview listing will -- though a workflow
     * may still emit other cfacts of its own choosing.
     * Prefixed like its neighbours, so a client remains free to declare a cfact called plain `reviewer`.
     */
    const val reviewer = "wfReviewer"
}

/**
 * How a workflow is entered. A closed set, so an enum. [creation] (issue #533) and [survey] (issue #656) are
 * built; a definition declaring [normal] is refused at boot rather than accepted and inert.
 */
@Suppress("EnumEntryName")
enum class WfEntry {
    /** Runs when a form document is created: one task, one save, and the save creates the document. */
    creation,

    /**
     * An owner revisits a form's global data outside any one workflow -- the second face of the create/edit
     * paradigm (issue #656). One to three tasks (`WfDef.surveyMaxTasks`), saves of kind [WfSaveKind.edit], and
     * at most one per scope.
     */
    survey,

    /**
     * Chosen by the user from the workflows a form is eligible for (issue #794). Many per form, unlike the
     * one-per-form [creation] and [survey]: a form carries per-workflow state for each one it is evaluated
     * against. Multi-stage, so no task ceiling, and its saves are [WfSaveKind.edit] since the form already
     * exists. A task may offer no save at all -- an approval task advances through its own endpoint.
     */
    normal,
}

/**
 * What a save does. [create] makes a new form (a creation workflow); [edit] updates an existing one (a survey
 * workflow, issue #656). The seam later saves -- submit, approve, export -- land on.
 */
@Suppress("EnumEntryName")
enum class WfSaveKind {
    /** Creates the form document from the entries the task collected. */
    create,

    /** Updates an existing form document with the entries the task collected -- how a survey edits a form. */
    edit,
}

/** How a task's traits are edited. Only [inline] exists; a pop-up editor is a later variation. */
@Suppress("EnumEntryName")
enum class WfEditMode { inline }

/**
 * One trait a task collects, and whether an entry of it must be present for the task to be complete.
 * **Requiredness lives here, on the workflow**, never on the trait's schema: the trait marks its fields
 * optional and the workflow says which entries it needs -- the soft-validation seam `gedra-patch.md` draws.
 */
class WfTraitRef(val traitId: String, val required: Boolean = true)

/** One way a task can be saved: what the button says, and what pressing it does. */
class WfSave(val id: String, val label: String, val kind: WfSaveKind)

/**
 * One **eligibility test** of a normal workflow (issue #783): a form is eligible when every test's cfact
 * expression matches the form's cfacts, and each one that does not contributes its [explanation] to the list of
 * reasons shown for why the form is not eligible.
 *
 * The test is a **requirement**, written the way the form should be (`surveyComplete`), so a failing test is
 * the reason. Several small tests rather than one blanket expression, because one expression can only say
 * "not eligible"; an array can say *why* -- Cedar's ran past eight entries.
 *
 * [id] is what a stored failure records, and what finds this entry again to explain it, so it is unique within
 * the workflow; ids tend to say what kind of check they are. [explanation] is a template evaluated in two
 * passes like a label, so it can pull a fragment.
 */
class WfEligibility(val id: String, val test: String, val explanation: String)

/**
 * One **singleton-cfact rule** of a normal workflow (issue #784): emit the framework singleton [cfact] -- one of
 * [WSC.all], never a name a workflow made up -- about the form while [whenExpr] matches the workflow's current
 * cfacts (the form's own, plus what the workflow's `cfactCalc` functions conclude). What turns a workflow's
 * private state into the form-level `Needs Review` / `Finished` a listing shows. Only an engaged workflow's
 * rules contribute.
 */
class WfSingleton(val cfact: String, val whenExpr: String)

/**
 * What makes a task an **approval task** (issue #787): instead of collecting traits, it asks a reviewer to approve
 * the form at this point in the workflow. Approving records the fact (who, when) in the form's state, and from then
 * on the workflow's own cfacts include [cfact] -- the name is the task's choice, so a workflow with several approval
 * points can tell them apart (`siteApproved`, `financeApproved`), and one that names `finished` feeds the Finished
 * status through an ordinary singleton rule. [prompt] is the text above the button and [button] the button's own;
 * both are templates evaluated like a label.
 */
class WfApproval(val cfact: String, val prompt: String, val button: String)

/**
 * A task's **task layout** -- as opposed to a *field* layout (`SchLayout`, `g-layout`), which presents the fields
 * within one trait's data type (issue #834). [order] names traits to draw first, in that order; every trait of
 * the task it leaves out follows in declaration order (`WfTask.displayOrder`), applied on the backend when the
 * workflow view is built. [edit] has one value, `inline`, and no consumer.
 *
 * **A placeholder, not a design.** How a task arranges its traits has not been designed -- unlike a task's
 * *display* (issue #788), which is, and is chosen by a selector. This holds only what a creation workflow could
 * not do without, and is no declaration of what that design will contain. It has no `mode` -- membership at this
 * level already *is* `WfTask.traits`, and [order] behaves like a field layout's `reorder` -- and it should be
 * neither extended nor renamed ahead of that design.
 */
class WfLayout(val order: List<String>, val edit: WfEditMode = WfEditMode.inline)

/**
 * One **task**: a unit of work in a workflow, collecting some traits and offering some saves -- or, as an
 * **approval task** ([approval], issue #787), collecting nothing and completed by a reviewer's approval.
 *
 * Structural coherence is checked here, where the definition is written: two traits with one id, two saves
 * with one id, or a layout ordering a trait the task does not collect are authoring mistakes with no coherent
 * reading, and are refused rather than collected as a boot problem. Whether the traits exist for the client,
 * and whether the labels' fragments resolve, needs the rest of the deployment and is checked at boot instead.
 */
class WfTask(
    val id: String,
    /** What the task is called -- a template, run through the two-pass evaluation; a literal is a template too. */
    val label: String,
    val traits: List<WfTraitRef>,
    val saves: List<WfSave>,
    val layout: WfLayout? = null,
    functionUsages: List<WfFunctionUsage> = emptyList(),
    /** Present on an **approval task** (issue #787), which collects no traits and offers no saves. */
    val approval: WfApproval? = null,
    /**
     * How the task is shown (issue #788): a display branch ([WDSP]) or a UiBlock selector choosing among several.
     * Kept as data -- the resolver chooses per caller, and a boot check holds its conditions and copy to account.
     */
    val display: Map<String, Any?>? = null,
    /**
     * Who may save the task (issue #856): a cfact expression over its facts and the viewer's, or null for anyone who
     * can see the form. Only a normal workflow's task may declare one, and only one that offers a save.
     */
    val saveWhen: String? = null,
) {
    /** The task-scoped function **usages** this task declares (e.g. `prefillData`), in priority order (issue #677). */
    val functionUsages: List<WfFunctionUsage> = functionUsages.sortedBy { it.priority }

    /**
     * The resolved task-scoped functions, filled in place by the `base:common` second pass once the creation
     * registry is complete (issue #677); empty until then -- the two-pass initialization the design describes.
     */
    var resolvedFunctions: List<WfFunction> = emptyList()

    init {
        if (id.isEmpty()) {
            throw KdrException.mkConv("A workflow task has no id.")
        }
        val traitIds = traits.map { it.traitId }
        traitIds.groupingBy { it }.eachCount().filterValues { it > 1 }.keys.firstOrNull()?.let {
            throw KdrException.mkConv("Task '$id' collects the trait '$it' twice.")
        }
        saves.map { it.id }.groupingBy { it }.eachCount().filterValues { it > 1 }.keys.firstOrNull()?.let {
            throw KdrException.mkConv("Task '$id' declares the save '$it' twice.")
        }
        layout?.order?.firstOrNull { it !in traitIds }?.let {
            throw KdrException.mkConv(
                "Task '$id' orders the trait '$it' in its layout but does not collect it. A layout arranges " +
                    "what the task collects; it cannot add to it.",
            )
        }
        if (approval != null) {
            // An approval is pressed, not filled in: its completion is the approval itself (issue #787), so a
            // trait or a save on it would be a second, competing idea of what finishing the task means.
            if (traits.isNotEmpty() || saves.isNotEmpty()) {
                throw KdrException.mkConv(
                    "Approval task '$id' collects traits or offers saves; an approval task does neither -- it is " +
                        "complete when a reviewer approves it.",
                )
            }
            if (approval.cfact.isBlank()) {
                throw KdrException.mkConv("Approval task '$id' names no cfact for its approval to emit.")
            }
        }
    }

    /** The traits an entry must be present for -- what [WfEngine.taskComplete] checks. */
    val requiredTraitIds: List<String> get() = traits.filter { it.required }.map { it.traitId }

    /**
     * The traits in the order a page draws them: the layout's order first, then any the layout did not mention
     * in declaration order, so a layout may say "these first" without having to name everything.
     */
    val displayOrder: List<String>
        get() {
            val ordered = layout?.order ?: emptyList()
            return ordered + traits.map { it.traitId }.filter { it !in ordered }
        }

    /** The trait named, or null. */
    fun trait(traitId: String): WfTraitRef? = traits.firstOrNull { it.traitId == traitId }

    /** The save named, or null. */
    fun save(saveId: String): WfSave? = saves.firstOrNull { it.id == saveId }
}

/**
 * A workflow definition: how it is entered, and the tasks it is made of (issue #533).
 *
 * There are **no states and no transitions**. A task's status is derived from the entries present
 * ([WfEngine]), "who may act" is a cfact, and a form's engagement with a workflow will live in a companion
 * states table -- the model `kdr-design/thoughts-workflow-poc.md` (private `sampwhite/Actions`) describes,
 * replacing the state machine #381
 * shipped and #533 retired. Pure Kotlin in `base/kernel`, so the frontend can read the same definition the
 * backend enforces.
 *
 * A definition is built from validated JSON by [parseWfDef]; constructing one directly is for tests and for
 * code that already holds the parts. It checks what is decidable from itself alone: a legal id, at least one
 * task, unique task ids, and -- for a [WfEntry.creation] workflow -- exactly one task with exactly one save,
 * which is what "the save creates the document" needs to mean anything.
 */
class WfDef(
    /** The base name; its client scope comes from the bundle that declares it, and [WfRef] pairs the two. */
    val workflowId: String,
    val entry: WfEntry,
    tasks: List<WfTask>,
    functionUsages: List<WfFunctionUsage> = emptyList(),
    /**
     * What the workflow is called (issue #719) -- a page's title over its form, written like a task's label: a
     * template evaluated in two passes, so it can pull from a fragment file. Empty when the definition gives
     * none, and a page then falls back to its own generic title ("Edit form", "New form").
     */
    val label: String = "",
    eligibility: List<WfEligibility> = emptyList(),
    singletons: List<WfSingleton> = emptyList(),
    /** The time windows (issue #790); only a [WfEntry.normal] workflow may declare any. */
    val windows: WfWindows = WfWindows.none,
    locks: List<WfLock> = emptyList(),
) {
    /** The trait locks (issue #857), in declaration order; only a [WfEntry.normal] workflow may declare any. */
    val locks: List<WfLock> = locks.toList()

    /** The tasks, in the order they are presented. */
    val tasks: List<WfTask> = tasks.toList()

    /** Tasks by id. */
    val tasksById: Map<String, WfTask> = tasks.associateBy { it.id }

    /**
     * The eligibility tests, in declaration order (issue #783) -- the order the reasons are listed in. Only a
     * [WfEntry.normal] workflow has any: creation and survey are not chosen, so there is nothing to be eligible for.
     */
    val eligibility: List<WfEligibility> = eligibility.toList()

    /** The singleton-cfact rules (issue #784); like [eligibility], only a [WfEntry.normal] workflow has any. */
    val singletons: List<WfSingleton> = singletons.toList()

    /** The workflow-global function **usages** this def declares (e.g. `cfactCalc`), in priority order (issue #677). */
    val functionUsages: List<WfFunctionUsage> = functionUsages.sortedBy { it.priority }

    /**
     * The resolved workflow-global functions, filled in place by the `base:common` second pass once the creation
     * registry is complete (issue #677); empty until then -- the two-pass initialization the design describes.
     */
    var resolvedFunctions: List<WfFunction> = emptyList()

    init {
        if (!workflowId.isVariableName()) {
            throw KdrException.mkConv(
                "'$workflowId' cannot be a workflow id: it has to be usable as a variable name, since a " +
                    "workflow is addressed by this name from code, from data and from a stored reference.",
            )
        }
        if (tasks.isEmpty()) {
            throw KdrException.mkConv("Workflow '$workflowId' has no tasks.")
        }
        if (tasksById.size != tasks.size) {
            throw KdrException.mkConv("Workflow '$workflowId' has two tasks with the same id.")
        }
        if (entry == WfEntry.creation) {
            val task = tasks.singleOrNull()
                ?: throw KdrException.mkConv(
                    "Creation workflow '$workflowId' has ${tasks.size} tasks; a creation workflow has exactly " +
                        "one, since it runs once, when the form is created.",
                )
            val save = task.saves.singleOrNull()
                ?: throw KdrException.mkConv(
                    "Creation workflow '$workflowId' has ${task.saves.size} saves on its task; it has exactly " +
                        "one, and that save creates the form.",
                )
            if (save.kind != WfSaveKind.create) {
                throw KdrException.mkConv(
                    "Creation workflow '$workflowId' saves with '${save.kind}'; its one save has to be " +
                        "'${WfSaveKind.create}', which is what makes it a creation workflow.",
                )
            }
        }
        if (entry == WfEntry.survey) {
            // A survey may have several tasks (a creation workflow may not), but a small, fixed few: it is a
            // questionnaire an owner finishes in one sitting, not a multi-stage process. More than the ceiling
            // is refused rather than silently working, since the design does not group a survey's tasks.
            if (tasks.size > surveyMaxTasks) {
                throw KdrException.mkConv(
                    "Survey workflow '$workflowId' has ${tasks.size} tasks; a survey has at most " +
                        "$surveyMaxTasks, since it is a short questionnaire an owner completes in one sitting.",
                )
            }
            tasks.firstOrNull { it.saves.isEmpty() }?.let {
                throw KdrException.mkConv(
                    "Survey workflow '$workflowId' task '${it.id}' has no save; every survey task needs a save, " +
                        "since a task with no way to persist its edits cannot advance the survey.",
                )
            }
            // A survey edits an existing form; a `create` save would make a second one. So every survey save is
            // an `edit`, the mirror of the creation-only `create` gate above.
            tasks.flatMap { it.saves }.firstOrNull { it.kind != WfSaveKind.edit }?.let {
                throw KdrException.mkConv(
                    "Survey workflow '$workflowId' has a save '${it.id}' of kind '${it.kind}'; a survey edits an " +
                        "existing form, so its saves are '${WfSaveKind.edit}'.",
                )
            }
        }
        if (entry == WfEntry.normal) {
            // A normal workflow runs against a form that already exists -- it is chosen from the workflows that
            // form is eligible for -- so a `create` save would make a second form. Same reasoning as the survey
            // rule above.
            tasks.flatMap { it.saves }.firstOrNull { it.kind != WfSaveKind.edit }?.let {
                throw KdrException.mkConv(
                    "Normal workflow '$workflowId' has a save '${it.id}' of kind '${it.kind}'; a normal workflow " +
                        "runs against an existing form, so its saves are '${WfSaveKind.edit}'.",
                )
            }
            // Deliberately no task ceiling (unlike a survey) and no save-per-task rule (unlike a survey): a
            // normal workflow is a multi-stage process, and a task can legitimately offer no save -- an approval
            // task advances through its own endpoint rather than by persisting collected entries (issue #787).
        }
        if (eligibility.isNotEmpty() && entry != WfEntry.normal) {
            throw KdrException.mkConv(
                "${entry.name.replaceFirstChar { it.uppercase() }} workflow '$workflowId' declares eligibility " +
                    "tests; only a normal workflow has them, since only a normal workflow is chosen for a form.",
            )
        }
        val seenEligibility = HashSet<String>()
        for (e in eligibility) {
            // The id is what a stored failure records and what finds this entry again to explain it -- from
            // code, from data and from a stored state entry -- so it follows the workflow id's naming rule.
            if (!e.id.isVariableName()) {
                throw KdrException.mkConv(
                    "'${e.id}' cannot be an eligibility id in workflow '$workflowId': it has to be usable as a " +
                        "variable name, since a stored failure refers to it by name.",
                )
            }
            if (!seenEligibility.add(e.id)) {
                throw KdrException.mkConv(
                    "Workflow '$workflowId' has two eligibility tests with the id '${e.id}'; a stored failure " +
                        "names its test by id, so two with one id could not be told apart.",
                )
            }
            if (e.test.isBlank()) {
                throw KdrException.mkConv(
                    "Eligibility test '${e.id}' in workflow '$workflowId' has no cfact test. Write '#always' " +
                        "for one that always passes, so the intent is explicit.",
                )
            }
        }
        tasks.firstOrNull { it.approval != null }?.let {
            if (entry != WfEntry.normal) {
                throw KdrException.mkConv(
                    "${entry.name.replaceFirstChar { c -> c.uppercase() }} workflow '$workflowId' has an approval " +
                        "task '${it.id}'; only a normal workflow has approval tasks, since an approval is a step a " +
                        "form reaches after it exists and has been put into the workflow.",
                )
            }
        }
        for (task in tasks) {
            val rule = task.saveWhen ?: continue
            // Survey and creation saves keep their own ownership rules (issue #856); a rule there would be ignored,
            // so it is refused rather than left looking as though it protects something.
            if (entry != WfEntry.normal) {
                throw KdrException.mkConv(
                    "${entry.name.replaceFirstChar { it.uppercase() }} workflow '$workflowId' task '${task.id}' says who " +
                        "may save it; only a normal workflow's tasks do.",
                )
            }
            if (rule.isBlank()) {
                throw KdrException.mkConv(
                    "Task '${task.id}' of workflow '$workflowId' has a blank rule for who may save it. Leave it out " +
                        "for anyone who can see the form.",
                )
            }
            if (task.saves.isEmpty()) {
                throw KdrException.mkConv(
                    "Task '${task.id}' of workflow '$workflowId' says who may save it, but offers no save.",
                )
            }
        }
        if (locks.isNotEmpty() && entry != WfEntry.normal) {
            throw KdrException.mkConv(
                "${entry.name.replaceFirstChar { it.uppercase() }} workflow '$workflowId' locks traits; only a normal " +
                    "workflow does, since a lock holds while a form is engaged with it.",
            )
        }
        val lockedTraits = HashSet<String>()
        for (lock in locks) {
            if (!lockedTraits.add(lock.traitId)) {
                throw KdrException.mkConv("Workflow '$workflowId' locks trait '${lock.traitId}' twice; write one lock.")
            }
            val via = tasksById[lock.writableVia]
                ?: throw KdrException.mkConv(
                    "The lock on trait '${lock.traitId}' in workflow '$workflowId' is writable via task " +
                        "'${lock.writableVia}', which the workflow does not have.",
                )
            if (via.traits.none { it.traitId == lock.traitId }) {
                throw KdrException.mkConv(
                    "The lock on trait '${lock.traitId}' in workflow '$workflowId' is writable via task '${via.id}', " +
                        "which does not collect it -- the task a lock names is the one that owns the trait.",
                )
            }
            // A task anyone may save exempts everyone, so the lock would hold for nobody: a mistake, not an intent.
            if (via.saveWhen == null) {
                throw KdrException.mkConv(
                    "The lock on trait '${lock.traitId}' in workflow '$workflowId' is writable via task '${via.id}', " +
                        "which says nothing about who may save it -- so the lock would hold for nobody. Give the task " +
                        "a saveWhen rule.",
                )
            }
            if (lock.whenExpr.isBlank()) {
                throw KdrException.mkConv(
                    "The lock on trait '${lock.traitId}' in workflow '$workflowId' has no condition. Write '#always' " +
                        "for one that holds whenever the form is engaged, so the intent is explicit.",
                )
            }
        }
        if (singletons.isNotEmpty() && entry != WfEntry.normal) {
            throw KdrException.mkConv(
                "${entry.name.replaceFirstChar { it.uppercase() }} workflow '$workflowId' declares singleton " +
                    "cfacts; only a normal workflow emits them, since only an engaged workflow contributes one.",
            )
        }
        if (!windows.isEmpty) {
            // A creation or survey workflow is how every form is made and kept -- switching one off by date would
            // strand the forms, and nothing chooses to engage with them, so the windows have nothing to govern.
            if (entry != WfEntry.normal) {
                throw KdrException.mkConv(
                    "${entry.name.replaceFirstChar { it.uppercase() }} workflow '$workflowId' declares time " +
                        "windows; only a normal workflow has them, since only a normal workflow is chosen for a form.",
                )
            }
            windows.check(workflowId)
        }
        val seenSingletons = HashSet<String>()
        for (r in singletons) {
            // The list is hardwired because each name carries code behavior; a workflow cannot invent one, and a
            // client's own cfacts are not candidates -- nothing would know what to do with them.
            if (r.cfact !in WSC.all) {
                throw KdrException.mkConv(
                    "Workflow '$workflowId' emits '${r.cfact}' as a singleton cfact; a workflow may emit only the " +
                        "framework's ${WSC.all.sorted()}, since each carries behavior in code.",
                )
            }
            if (!seenSingletons.add(r.cfact)) {
                throw KdrException.mkConv(
                    "Workflow '$workflowId' has two rules emitting '${r.cfact}'; write one, with the conditions " +
                        "joined by '|'.",
                )
            }
            if (r.whenExpr.isBlank()) {
                throw KdrException.mkConv(
                    "The '${r.cfact}' rule in workflow '$workflowId' has no condition. Write '#always' for one " +
                        "that always emits, so the intent is explicit.",
                )
            }
        }
    }

    /** The task named, or null. */
    fun task(id: String): WfTask? = tasksById[id]

    /** Where the workflow stands in its time windows at [now] (issue #790); always engageable when it has none. */
    fun phaseAt(now: Instant): WfPhase = windows.phaseAt(now)

    /**
     * Whether a page shows the list of tasks: **behavior, not configuration** -- a workflow with one task has
     * no list to show, so the list appears when there is more than one, and no attribute overrides it until
     * something asks for one.
     */
    val showTaskList: Boolean get() = tasks.size > 1

    override fun toString(): String = "$workflowId (${entry.name}, ${tasks.size} task(s))"

    @Suppress("ConstPropertyName")
    companion object {
        /**
         * The most tasks a [WfEntry.survey] workflow may have. The design expects a survey to be a short
         * questionnaire an owner finishes in one sitting -- "I do not expect there to ever be more than three"
         * -- so a survey with more is refused rather than silently split into task groups it does not have.
         */
        const val surveyMaxTasks = 3
    }
}

/**
 * The schema a workflow definition is validated against before it becomes a [WfDef] (issue #533) -- the
 * "definition is schema-validated JSON" half of the design. One set of types for the source builder and for
 * a definition arriving as data, so nothing hand-parses and the two cannot disagree.
 *
 * The array-valued properties declare `allowCoerce`, because it defaults off for arrays and on for scalars: a
 * `"tasks"` given as JSON text, or an `"order"` given as `"a,b,c"`, is meant to be accepted, and the schema
 * has to say so where the default says otherwise.
 */
object WfDefSchema {
    /** The `$defs` of the definition schema, under [WFD.namespace]. */
    fun defs(cxt: KdrCxtBase): Map<String, Any?> = schemaDefs(cxt, WFD.namespace) {
        type(WFD.traitRefType) {
            type = SCT.kObject
            description = "One trait a task collects, and whether an entry of it is required for completeness."
            property(WFD.traitId, "The trait this task collects.", required = true)
            property(WFD.required, "Whether an entry of this trait must be present for the task to be complete; true when absent.") {
                type = SCT.boolean
            }
        }
        type(WFD.saveType) {
            type = SCT.kObject
            description = "One way a task is saved: the button's label, and what pressing it does."
            property(WFD.id, "Stable id of this save, unique within the task.", required = true)
            property(WFD.label, "What the save is called -- a template, evaluated in two passes.", required = true)
            property(WFD.kind, "What the save does.", required = true) { options(WfSaveKind.entries) }
        }
        type(WFD.layoutType) {
            type = SCT.kObject
            description = "The minimum a page needs to draw a task."
            property(WFD.order, "Trait ids in the order they are drawn; traits not named follow in declaration order.") {
                type = SCT.array
                allowCoerce = true
                items { type = SCT.string }
            }
            property(WFD.edit, "How the task's traits are edited.") { options(WfEditMode.entries) }
        }
        type(WFD.eligibilityType) {
            type = SCT.kObject
            description = "One eligibility test of a normal workflow: a cfact requirement and the reason shown when it is not met."
            property(WFD.id, "Stable id of this test, unique within the workflow; what a stored failure records.", required = true)
            property(WFD.test, "The cfact expression a form's cfacts must match for this test to pass.", required = true)
            property(WFD.explanation, "Why the form is not eligible when the test fails -- a template, evaluated in two passes.", required = true)
        }
        type(WFD.singletonType) {
            type = SCT.kObject
            description = "One singleton-cfact rule of a normal workflow: a framework singleton cfact, and when it is emitted."
            property(WFD.cfact, "The framework singleton cfact emitted (needsReview, finished).", required = true)
            property(WFD.kWhen, "The cfact expression over the workflow's current cfacts that emits it.", required = true)
        }
        type(WFD.approvalType) {
            type = SCT.kObject
            description = "What makes a task an approval task: the cfact an approval emits, and the copy around its button."
            property(WFD.cfact, "The cfact the workflow gains once this task is approved.", required = true)
            property(WFD.prompt, "The text above the approve button -- a template, evaluated in two passes.", required = true)
            property(WFD.button, "The approve button's own text -- a template, evaluated in two passes.", required = true)
        }
        type(WFD.windowType) {
            type = SCT.kObject
            description = "A time window: from its start (inclusive) to its end (exclusive); a missing bound falls back to the enclosing window's."
            property(WFD.start, "When the window opens; absent, the enclosing window's start, or no limit.") { dateTime() }
            property(WFD.end, "When the window closes; absent, the enclosing window's end, or no limit.") { dateTime() }
        }
        type(WFD.lockType) {
            type = SCT.kObject
            description = "A trait lock: while the form is engaged and the condition holds, only the named task's savers may change the trait, unless a write overrides it."
            property(WFD.traitId, "The trait locked.", required = true)
            property(WFD.kWhen, "When it holds, beyond the form being engaged: a cfact expression over the form's and the workflow's facts.", required = true)
            property(WFD.writableVia, "The task, collecting the trait, whose savers the lock exempts.", required = true)
            property(WFD.overrideWhen, "Who may override it: a cfact expression over the writer's request facts. Absent means nobody.")
        }
        type(WFD.taskType) {
            type = SCT.kObject
            description = "One task of a workflow: the traits it collects and the saves it offers -- or, with an approval, none, completed by a reviewer."
            property(WFD.id, "Stable id of this task, unique within the workflow.", required = true)
            property(WFD.label, "What the task is called -- a template, evaluated in two passes.", required = true)
            property(WFD.traits, "The traits this task collects.", required = true) {
                type = SCT.array
                allowCoerce = true
                items { ref(WFD.traitRefType) }
            }
            property(WFD.saves, "The saves this task offers.", required = true) {
                type = SCT.array
                allowCoerce = true
                items { ref(WFD.saveType) }
            }
            property(WFD.layout, "How the task is drawn; declaration order and inline editing when absent.") {
                ref(WFD.layoutType)
            }
            property(WFD.functions, "Task-scoped function usages (e.g. prefillData); each a { fn, ... } block validated by its own kind.") {
                type = SCT.array
                allowCoerce = true
                items { type = SCT.kObject }
            }
            property(WFD.approval, "Makes this an approval task: it collects no traits and is complete when a reviewer approves it.") {
                ref(WFD.approvalType)
            }
            property(WFD.display, "How the task is shown: a display branch (mode, text, disabled), or a selector choosing among several by the caller's task facts.") {
                type = SCT.kObject
                additionalProperties = true
            }
            property(WFD.saveWhen, "A normal workflow task's rule for who may save it: a cfact expression over the caller's task and viewer facts. Absent means anyone who can see the form.")
        }
        type(WFD.defType) {
            type = SCT.kObject
            description = "A workflow definition: how it is entered, and its tasks."
            property(WFD.workflowId, "The workflow's base name; its client comes from the bundle declaring it.", required = true)
            property(WFD.entry, "How the workflow is entered.", required = true) { options(WfEntry.entries) }
            property(WFD.label, "What the workflow is called -- a page's title, a template evaluated in two passes like a task's label. Optional; absent, a page uses its own generic title.")
            property(WFD.tasks, "The tasks, in presentation order.", required = true) {
                type = SCT.array
                allowCoerce = true
                items { ref(WFD.taskType) }
            }
            property(WFD.functions, "Workflow-global function usages (e.g. cfactCalc); each a { fn, ... } block validated by its own kind.") {
                type = SCT.array
                allowCoerce = true
                items { type = SCT.kObject }
            }
            property(WFD.eligibility, "A normal workflow's eligibility tests, in the order their reasons are listed. Every one is evaluated; an empty list of failures means eligible.") {
                type = SCT.array
                allowCoerce = true
                items { ref(WFD.eligibilityType) }
            }
            property(WFD.singletons, "A normal workflow's singleton-cfact rules: the framework cfacts it contributes to the form while engaged.") {
                type = SCT.array
                allowCoerce = true
                items { ref(WFD.singletonType) }
            }
            property(WFD.lifetime, "A normal workflow's lifetime: outside it, the workflow is as good as not configured.") {
                ref(WFD.windowType)
            }
            property(WFD.relevancy, "Inside the lifetime: when the workflow is calculated. Outside it, an engaged form's state is frozen.") {
                ref(WFD.windowType)
            }
            property(WFD.engagement, "Inside relevancy: when a form may engage with the workflow.") {
                ref(WFD.windowType)
            }
            property(WFD.locks, "A normal workflow's trait locks: what the form's data may not change while it is engaged, and who is exempt or may override.") {
                type = SCT.array
                allowCoerce = true
                items { ref(WFD.lockType) }
            }
        }
    }

    // Parsed once and kept: the schema is a constant of the runtime, and a definition arriving mid-run should
    // not reparse it. A benign race on first use produces the same value twice.
    private var parsed: Map<String, SchType>? = null

    /** The compiled types, keyed by qualified name (`wfdef.WfDef`). */
    fun types(cxt: KdrCxtBase): Map<String, SchType> =
        parsed ?: parseSchemaTypes(defs(cxt)).also { parsed = it }

    /** The compiled definition type. */
    fun defType(cxt: KdrCxtBase): SchType = types(cxt).getValue("${WFD.namespace}.${WFD.defType}")
}

/**
 * A workflow definition back as its JSON form (issue #613): the inverse of [parseWfDef], so a definition built
 * in source can be stored and read back through the same parser. It mirrors what [WfDefBuilder]/[WfTaskBuilder]
 * emit exactly -- the same keys, `enum.name` for every enum -- because that shape is the one [parseWfDef]
 * accepts; a divergence would store a definition that no longer round-trips. Pure over the model, so it lives
 * beside the parser rather than in a service.
 */
fun WfDef.toJsonMap(): Map<String, Any?> = buildMap {
    put(WFD.workflowId, workflowId)
    put(WFD.entry, entry.name)
    // Emitted when set, as the builder does -- a stored definition that dropped it would lose its page title.
    if (label.isNotEmpty()) put(WFD.label, label)
    put(
        WFD.tasks,
        tasks.map { task ->
            buildMap {
                put(WFD.id, task.id)
                put(WFD.label, task.label)
                put(WFD.traits, task.traits.map { linkedMapOf(WFD.traitId to it.traitId, WFD.required to it.required) })
                put(WFD.saves, task.saves.map { linkedMapOf(WFD.id to it.id, WFD.label to it.label, WFD.kind to it.kind.name) })
                task.layout?.let { put(WFD.layout, linkedMapOf(WFD.order to it.order, WFD.edit to it.edit.name)) }
                // A usage re-emits its own initialization data, so a code-built and a stored definition
                // round-trip identically (issue #677).
                if (task.functionUsages.isNotEmpty()) put(WFD.functions, task.functionUsages.map { it.toJsonMap() })
                task.approval?.let {
                    put(WFD.approval, linkedMapOf(WFD.cfact to it.cfact, WFD.prompt to it.prompt, WFD.button to it.button))
                }
                task.display?.let { put(WFD.display, it) }
                task.saveWhen?.let { put(WFD.saveWhen, it) }
            }
        },
    )
    if (functionUsages.isNotEmpty()) put(WFD.functions, functionUsages.map { it.toJsonMap() })
    if (eligibility.isNotEmpty()) {
        put(
            WFD.eligibility,
            eligibility.map { linkedMapOf(WFD.id to it.id, WFD.test to it.test, WFD.explanation to it.explanation) },
        )
    }
    if (singletons.isNotEmpty()) {
        put(WFD.singletons, singletons.map { linkedMapOf(WFD.cfact to it.cfact, WFD.kWhen to it.whenExpr) })
    }
    // As declared, not resolved: a stored definition keeps which bounds were written and which fall back.
    if (!windows.lifetime.isEmpty) put(WFD.lifetime, windows.lifetime.toJsonMap())
    if (!windows.relevancy.isEmpty) put(WFD.relevancy, windows.relevancy.toJsonMap())
    if (!windows.engagement.isEmpty) put(WFD.engagement, windows.engagement.toJsonMap())
    if (locks.isNotEmpty()) put(WFD.locks, locks.map { it.toJsonMap() })
}

/**
 * Reads a workflow definition from its JSON form: validates and coerces [raw] against [WfDefSchema], refusing
 * it with every failure named, then builds the [WfDef] -- whose own constructor checks the structural rules
 * a schema cannot state (unique ids, the creation shape).
 */
fun parseWfDef(cxt: KdrCxtBase, raw: Map<String, Any?>): WfDef {
    val result = coerceAndValidate(WfDefSchema.defType(cxt), raw)
    if (result.failures.isNotEmpty()) {
        val id = raw[WFD.workflowId].toOptStr() ?: "(no id)"
        throw KdrException.mkConv(
            "Workflow definition '$id' is not valid: " +
                result.failures.joinToString("; ") { "${it.path.ifEmpty { "(root)" }}: ${it.message}" },
        )
    }
    // A function's own `{fn, ...}` shape is validated by its kind in `base:common`, not here: the kernel keeps
    // each usage as data and the common second pass resolves it once the creation registry is complete (#677).
    fun usagesOf(raw: Any?): List<WfFunctionUsage> = raw.toJsonListOfMaps().map { WfFunctionUsage(it) }
    val m = result.value.toJsonMapOrEmpty()
    return WfDef(
        workflowId = m[WFD.workflowId].toOptStr() ?: "",
        entry = enumNamed(WfEntry.entries, m[WFD.entry]),
        label = m[WFD.label].toOptStr() ?: "",
        tasks = m[WFD.tasks].toJsonListOfMaps().map { t ->
            WfTask(
                id = t[WFD.id].toOptStr() ?: "",
                label = t[WFD.label].toOptStr() ?: "",
                traits = t[WFD.traits].toJsonListOfMaps().map { r ->
                    // Absent reads as required: a workflow names a trait because it wants it, and saying so
                    // twice would be the common case.
                    WfTraitRef(r[WFD.traitId].toOptStr() ?: "", (r[WFD.required] as? Boolean) ?: true)
                },
                saves = t[WFD.saves].toJsonListOfMaps().map { s ->
                    WfSave(s[WFD.id].toOptStr() ?: "", s[WFD.label].toOptStr() ?: "", enumNamed(WfSaveKind.entries, s[WFD.kind]))
                },
                layout = (t[WFD.layout] as? Map<*, *>)?.let { l ->
                    val lm = l.toJsonMapOrEmpty()
                    WfLayout(
                        order = (lm[WFD.order] as? List<*>)?.mapNotNull { it.toOptStr() } ?: emptyList(),
                        edit = lm[WFD.edit]?.let { enumNamed(WfEditMode.entries, it) } ?: WfEditMode.inline,
                    )
                },
                functionUsages = usagesOf(t[WFD.functions]),
                approval = (t[WFD.approval] as? Map<*, *>)?.let { a ->
                    val am = a.toJsonMapOrEmpty()
                    WfApproval(am[WFD.cfact].toOptStr() ?: "", am[WFD.prompt].toOptStr() ?: "", am[WFD.button].toOptStr() ?: "")
                },
                display = (t[WFD.display] as? Map<*, *>)?.toJsonMapOrEmpty(),
                // A blank rule arrives absent -- the schema layer reads a blank optional string that way -- which is
                // "anyone who can see the form", the same as leaving it out.
                saveWhen = t[WFD.saveWhen].toOptStr(),
            )
        },
        functionUsages = usagesOf(m[WFD.functions]),
        eligibility = m[WFD.eligibility].toJsonListOfMaps().map { e ->
            WfEligibility(
                e[WFD.id].toOptStr() ?: "",
                e[WFD.test].toOptStr() ?: "",
                e[WFD.explanation].toOptStr() ?: "",
            )
        },
        singletons = m[WFD.singletons].toJsonListOfMaps().map { r ->
            WfSingleton(r[WFD.cfact].toOptStr() ?: "", r[WFD.kWhen].toOptStr() ?: "")
        },
        windows = WfWindows(
            lifetime = WfWindow.fromJson(m[WFD.lifetime]),
            relevancy = WfWindow.fromJson(m[WFD.relevancy]),
            engagement = WfWindow.fromJson(m[WFD.engagement]),
        ),
        locks = m[WFD.locks].toJsonListOfMaps().map { l ->
            WfLock(
                traitId = l[WFD.traitId].toOptStr() ?: "",
                whenExpr = l[WFD.kWhen].toOptStr() ?: "",
                writableVia = l[WFD.writableVia].toOptStr() ?: "",
                overrideWhen = l[WFD.overrideWhen].toOptStr(),
            )
        },
    )
}

/**
 * A trait lock of a normal workflow (issue #857); see [WFD.locks]. [whenExpr] is when it holds beyond the form being
 * engaged; [writableVia] the task whose savers it exempts; [overrideWhen] who may override it, or null for nobody.
 */
class WfLock(val traitId: String, val whenExpr: String, val writableVia: String, val overrideWhen: String? = null) {
    fun toJsonMap(): Map<String, Any?> = buildMap {
        put(WFD.traitId, traitId)
        put(WFD.kWhen, whenExpr)
        put(WFD.writableVia, writableVia)
        overrideWhen?.let { put(WFD.overrideWhen, it) }
    }
}

/** The entry of [entries] whose name is [value]; the schema's closed option list has already admitted it. */
private fun <E : Enum<E>> enumNamed(entries: List<E>, value: Any?): E {
    val name = value.toOptStr()
    return entries.firstOrNull { it.name == name }
        ?: throw KdrException.mkConv("'$name' is not one of ${entries.map { it.name }}.")
}

/**
 * Authors a workflow definition in source (issue #533). It produces the **JSON map**, not the model: the map
 * goes through [parseWfDef] like a definition from any other source, so the builder is a convenience over
 * the data form rather than a second path into the model.
 */
class WfDefBuilder(private val workflowId: String, private val entry: WfEntry) {
    private val tasks = mutableListOf<Map<String, Any?>>()
    private val functions = mutableListOf<Map<String, Any?>>()
    private val eligibility = mutableListOf<Map<String, Any?>>()
    private val singletons = mutableListOf<Map<String, Any?>>()
    private val windows = linkedMapOf<String, Map<String, Any?>>()
    private val locks = mutableListOf<Map<String, Any?>>()

    /**
     * What the workflow is called (issue #719): a page's title over its form. A template like a task's label,
     * so `%{@t("file.namespace.key")}` pulls it from a fragment file and the boot checks the pull resolves.
     * Leave unset for a page's own generic title.
     */
    var label: String? = null

    /** Declares a task. */
    fun task(id: String, label: String, build: WfTaskBuilder.() -> Unit) {
        tasks.add(WfTaskBuilder(id, label).apply(build).build())
    }

    /**
     * Declares a workflow-global function usage (a `cfactCalc`, e.g.). Takes the function's **initialization
     * data** -- the `{fn, ...}` map a per-`fn` builder in `base:common` emits (issue #677) -- since this builder
     * produces the JSON form and the function's own kind validates it at parse.
     */
    fun function(initData: Map<String, Any?>) {
        functions.add(initData)
    }

    /**
     * An eligibility test (issue #783), in the order the reasons are listed: the form must meet the cfact [test],
     * and [explanation] -- a template, like a label -- says why it is not eligible when it does not.
     */
    fun eligibility(id: String, test: String, explanation: String) {
        eligibility.add(linkedMapOf(WFD.id to id, WFD.test to test, WFD.explanation to explanation))
    }

    /**
     * A singleton-cfact rule (issue #784): emit the framework singleton [cfact] (one of [WSC.all]) about the
     * form while [whenExpr] matches the workflow's current cfacts -- contributed only while the form is engaged.
     */
    fun singleton(cfact: String, whenExpr: String) {
        singletons.add(linkedMapOf(WFD.cfact to cfact, WFD.kWhen to whenExpr))
    }

    /**
     * The workflow's lifetime (issue #790): outside it, the workflow is as good as not configured. Bounds are
     * ISO-8601 instants (`2026-10-01T00:00:00Z`); leave one out for no limit on that side.
     */
    fun lifetime(start: String? = null, end: String? = null) = window(WFD.lifetime, start, end)

    /**
     * When the workflow is calculated, inside its [lifetime] (issue #790). A missing bound falls back to the
     * lifetime's. Outside it, a form engaged with the workflow keeps its last state, read-only.
     */
    fun relevancy(start: String? = null, end: String? = null) = window(WFD.relevancy, start, end)

    /** When a form may engage with the workflow, inside its [relevancy] (issue #790); a missing bound falls back. */
    fun engagement(start: String? = null, end: String? = null) = window(WFD.engagement, start, end)

    /**
     * Locks [traitId] on a form engaged with this workflow (issue #857): while [whenCfacts] -- a cfact expression over
     * the form's facts and the workflow's own -- holds, only whoever may save task [writableVia] (which collects the
     * trait and says who may save it) may change it, by any path. [overrideWhen], over the writer's request facts,
     * says who may override it with an explicit reason; null means nobody.
     */
    fun lock(traitId: String, writableVia: String, whenCfacts: String = "#always", overrideWhen: String? = null) {
        locks.add(WfLock(traitId, whenCfacts, writableVia, overrideWhen).toJsonMap())
    }

    private fun window(name: String, start: String?, end: String?) {
        windows[name] = buildMap {
            start?.let { put(WFD.start, it) }
            end?.let { put(WFD.end, it) }
        }
    }

    /** The definition as JSON, ready for [parseWfDef]. */
    fun build(): Map<String, Any?> = buildMap {
        put(WFD.workflowId, workflowId)
        put(WFD.entry, entry.name)
        label?.let { put(WFD.label, it) }
        put(WFD.tasks, tasks.toList())
        if (functions.isNotEmpty()) put(WFD.functions, functions.toList())
        if (eligibility.isNotEmpty()) put(WFD.eligibility, eligibility.toList())
        if (singletons.isNotEmpty()) put(WFD.singletons, singletons.toList())
        putAll(windows)
        if (locks.isNotEmpty()) put(WFD.locks, locks.toList())
    }
}

/** Authors one task's JSON; see [WfDefBuilder]. */
class WfTaskBuilder(private val id: String, private val label: String) {
    private val traits = mutableListOf<Map<String, Any?>>()
    private val saves = mutableListOf<Map<String, Any?>>()
    private var layout: Map<String, Any?>? = null
    private val functions = mutableListOf<Map<String, Any?>>()
    private var approval: Map<String, Any?>? = null
    private var display: Map<String, Any?>? = null
    private var saveWhen: String? = null

    /**
     * Declares a task-scoped function usage (a `prefillData`, e.g.) -- the `{fn, ...}` initialization data a
     * per-`fn` builder in `base:common` emits (issue #677), validated by its kind at parse.
     */
    fun function(initData: Map<String, Any?>) {
        functions.add(initData)
    }

    /** A trait this task collects; required unless said otherwise. */
    fun trait(traitId: String, required: Boolean = true) {
        traits.add(linkedMapOf(WFD.traitId to traitId, WFD.required to required))
    }

    /** A save this task offers. */
    fun save(id: String, label: String, kind: WfSaveKind = WfSaveKind.create) {
        saves.add(linkedMapOf(WFD.id to id, WFD.label to label, WFD.kind to kind.name))
    }

    /**
     * Makes this an **approval task** (issue #787): no traits and no saves -- a reviewer approves it, which emits
     * [cfact] into the workflow's cfacts. [prompt] is the text above the button and [button] the button's; both are
     * templates, like a label.
     */
    fun approval(cfact: String, prompt: String, button: String) {
        approval = linkedMapOf(WFD.cfact to cfact, WFD.prompt to prompt, WFD.button to button)
    }

    /**
     * Who may save the task (issue #856): [cfacts], a cfact expression over the caller's task facts and viewer facts
     * -- `wfReviewer` from a `userHasLabel` function on this task, say. The save endpoint refuses anyone it does not
     * admit, and the view tells the page whether the caller may, so the page offers Save only where it can succeed.
     * A normal workflow's task only.
     */
    fun saveWhen(cfacts: String) {
        saveWhen = cfacts
    }

    /**
     * How the task is shown (issue #788): the branches [WfDisplayBuilder] collects, tried **in order** against the
     * caller's task facts -- the first whose condition holds is what the view delivers.
     */
    fun display(build: WfDisplayBuilder.() -> Unit) {
        display = WfDisplayBuilder().apply(build).build()
    }

    /** How the task is drawn; traits not named in [order] follow in declaration order. */
    fun layout(order: List<String>, edit: WfEditMode = WfEditMode.inline) {
        layout = linkedMapOf(WFD.order to order, WFD.edit to edit.name)
    }

    fun build(): Map<String, Any?> {
        val out = linkedMapOf<String, Any?>(
            WFD.id to id,
            WFD.label to label,
            WFD.traits to traits.toList(),
            WFD.saves to saves.toList(),
        )
        layout?.let { out[WFD.layout] = it }
        if (functions.isNotEmpty()) out[WFD.functions] = functions.toList()
        approval?.let { out[WFD.approval] = it }
        display?.let { out[WFD.display] = it }
        saveWhen?.let { out[WFD.saveWhen] = it }
        return out
    }
}

/**
 * Authors a task's display selector (issue #788): branches tried in the order they are declared, the first whose
 * cfact expression the caller's task facts satisfy winning. It produces the UiBlock selector shape --
 * `{select: [{cfactExpression, mode, text, disabled}, ...]}` -- so the resolver needs no workflow knowledge.
 */
class WfDisplayBuilder {
    private val branches = mutableListOf<Map<String, Any?>>()

    /** A branch shown when [cfacts] -- a cfact expression -- matches the caller's task facts. */
    fun whenCfacts(cfacts: String, build: WfDisplayBranchBuilder.() -> Unit) {
        branches.add(WfDisplayBranchBuilder(cfacts).apply(build).build())
    }

    /** The unguarded last branch: shown when nothing before it matched. */
    fun otherwise(build: WfDisplayBranchBuilder.() -> Unit) {
        branches.add(WfDisplayBranchBuilder(null).apply(build).build())
    }

    fun build(): Map<String, Any?> = linkedMapOf(UIB.select to branches.toList())
}

/** One display branch; see [WfDisplayBuilder]. */
class WfDisplayBranchBuilder(private val cfacts: String?) {
    private var mode: String = WDSP.defaultMode
    private var text: String? = null

    /** Show the task greyed, and its rail link not clickable. */
    var disabled: Boolean = false

    /** Show [template] in place of the task's own rendering. */
    fun text(template: String) {
        mode = WDSP.textMode
        text = template
    }

    /** Show the task's own rendering -- the default, and what an approval's reviewer is given. */
    fun defaultRendering() {
        mode = WDSP.defaultMode
        text = null
    }

    fun build(): Map<String, Any?> = linkedMapOf<String, Any?>().apply {
        cfacts?.let { put(UIB.cfactExpression, it) }
        put(WDSP.mode, mode)
        text?.let { put(WDSP.text, it) }
        if (disabled) put(WDSP.disabled, true)
    }
}

/**
 * Task status, derived and never stored (issue #533) -- the one idea kept from the #381 engine.
 *
 * Completeness is **presence of an entry, not a judgment of its content**: a required trait is satisfied when
 * an entry of that trait is present with a non-null [GE.data], whatever shape that data takes. A trait whose
 * payload is legitimately empty must be able to satisfy a gate, or the save is blocked forever; judging
 * content is what validity (deferred) and review are for. Pure over plain maps, so the frontend can run the
 * identical check against a draft.
 */
object WfEngine {
    /** The [requiredTraitIds] no entry in [entries] satisfies, in the order given. */
    fun missingTraits(requiredTraitIds: List<String>, entries: List<Map<String, Any?>>): List<String> {
        val satisfied = entries.mapNotNull { entry ->
            if (entry[GE.data] != null) entry[GE.traitId].toOptStr() else null
        }.toSet()
        return requiredTraitIds.filter { it !in satisfied }
    }

    /** Whether every required trait of [task] is present in [entries]. */
    fun taskComplete(task: WfTask, entries: List<Map<String, Any?>>): Boolean =
        missingTraits(task.requiredTraitIds, entries).isEmpty()
}

/**
 * The keys of one task **display branch** (issue #788) -- what a [WFD.display] selector chooses among, and what the
 * view delivers for the frontend to draw.
 */
@Suppress("ConstPropertyName")
object WDSP {
    /** How the task is drawn: [textMode] or [defaultMode]; [defaultMode] when absent. */
    const val mode = "mode"

    /** [mode]: draw [text] in place of the task's own rendering. */
    const val textMode = "text"

    /** [mode]: draw the task's own rendering -- its traits and saves, or an approval task's button. */
    const val defaultMode = "default"

    /** The modes a branch may name. */
    val modes: Set<String> = setOf(textMode, defaultMode)

    /**
     * The text a [textMode] branch shows -- a template like a label: `%{…}` pulls resolve on the backend, and
     * `${'$'}{…}` is left for the frontend to substitute from the task view's own data (an approval's
     * `approvedByName`, say), per the layout rule that frontend substitution lives only in a layout.
     */
    const val text = "text"

    /** Whether the task is shown disabled -- greyed, and its rail link not clickable. */
    const val disabled = "disabled"
}

/**
 * The target facts about one task (issue #533) -- what a view passes to the cfact registry's `assemble`
 * beside the request's own facts, so a selector can choose on them. Today that is a task's display selector
 * (issue #788); selectors are to come down to field layouts too, choosing which one a task's traits render with.
 *
 * [WFC.taskAvailable] is **always present**: a placeholder that keeps the shape visible until availability
 * rules (dates, prior tasks) exist. Said here so nobody reads it as computed. [WFC.isCta] is the caller's to
 * say (issue #785): which task is the CTA is a judgment over the whole task list, and content validity, which
 * needs the client's schema -- neither is this one task's to decide. So is `approved`, for an **approval task**
 * (issue #787): it collects no traits, so trait presence would call it complete before anyone approved it --
 * its completion is the approval, which lives in the form's state. An approved approval task also carries its
 * **own approval cfact** (issue #788), so a display selector can say `acmeAuditApproved` rather than the
 * generic `wfTaskComplete`.
 */
object WfTaskFacts {
    fun of(task: WfTask, entries: List<Map<String, Any?>>, isCta: Boolean = false, approved: Boolean = false): Set<String> {
        val facts = LinkedHashSet<String>()
        facts.add(WFC.taskAvailable)
        val complete = if (task.approval != null) approved else WfEngine.taskComplete(task, entries)
        if (complete) {
            facts.add(WFC.taskComplete)
        }
        if (approved) {
            task.approval?.let { facts.add(it.cfact) }
        }
        if (isCta) {
            facts.add(WFC.isCta)
        }
        return facts
    }
}
