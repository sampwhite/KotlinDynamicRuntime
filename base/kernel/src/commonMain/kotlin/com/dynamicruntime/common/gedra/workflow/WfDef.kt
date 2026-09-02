package com.dynamicruntime.common.gedra.workflow

import com.dynamicruntime.common.context.KdrCxtBase
import com.dynamicruntime.common.exception.KdrException
import com.dynamicruntime.common.gedra.GE
import com.dynamicruntime.common.schema.SCT
import com.dynamicruntime.common.schema.SchType
import com.dynamicruntime.common.schema.coerceAndValidate
import com.dynamicruntime.common.schema.parseSchemaTypes
import com.dynamicruntime.common.schema.schemaDefs
import com.dynamicruntime.common.util.isVariableName
import com.dynamicruntime.common.util.toJsonListOfMaps
import com.dynamicruntime.common.util.toJsonMapOrEmpty
import com.dynamicruntime.common.util.toOptStr

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

    const val workflowId = "workflowId"
    const val entry = "entry"
    const val tasks = "tasks"

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

    /** Separates a bundle id from a workflow id in a [WfRef]'s text form. */
    const val refSep = '#'
}

/**
 * The field names of the **resolved** workflow view a `/gedra/<client>/workflow/view` call returns (issue
 * #534) -- the shape the creation page renders. It reuses [WFD]'s names where the meaning is the same
 * (`workflowId`, `entry`, `tasks`, `id`, `label`, `traits`, `traitId`, `required`, `saves`, `kind`, `layout`,
 * `order`, `edit`), and adds only what resolution produces: whether a workflow was found, the workflow's
 * stored [WfRef], each trait's data `$ref` into this client's schema ([schemaRef]), whether the task list shows,
 * and the target
 * facts assembled about each task.
 */
@Suppress("ConstPropertyName")
object WVF {
    /** Whether the call resolved a workflow at all -- false when the client has no such (or no creation) one. */
    const val found = "found"

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
}

/**
 * The cfact names a task's statuses are reported under -- **target facts** about the task being rendered,
 * passed to the registry's `assemble` rather than computed from the request (issue #533). Only these two are
 * declared: `complete` has a real producer ([WfTaskFacts]), `available` is a placeholder that is always
 * present until availability rules exist. Eligibility and validity are not declared at all -- the cfact
 * registry is additive, so a name costs nothing when something produces it, and a declared name nothing
 * produces reads as a capability.
 */
@Suppress("ConstPropertyName")
object WFC {
    /** The task's required traits are all present. */
    const val taskComplete = "wfTaskComplete"

    /** The task may be worked on now. **Always present today** -- availability rules are a later step. */
    const val taskAvailable = "wfTaskAvailable"
}

/**
 * How a workflow is entered. A closed set, so an enum. Only [creation] is built (issue #533); a definition
 * declaring another is refused at boot rather than accepted and inert.
 */
@Suppress("EnumEntryName")
enum class WfEntry {
    /** Runs when a form document is created: one task, one save, and the save creates the document. */
    creation,

    /** An initial survey the owner completes before other workflows apply. Not built yet. */
    survey,

    /** Chosen by the user from the workflows a form is eligible for. Not built yet. */
    normal,
}

/** What a save does. Only [create] exists; the seam later saves (submit, approve, export) land on. */
@Suppress("EnumEntryName")
enum class WfSaveKind {
    /** Creates the form document from the entries the task collected. */
    create,
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
 * The minimum a page needs to draw a task: the order its traits appear in, and how they are edited. The
 * fuller layout family -- summaries, pop-ups, headers, static text -- is deferred; this is only what a creation
 * workflow cannot do without.
 */
class WfLayout(val order: List<String>, val edit: WfEditMode = WfEditMode.inline)

/**
 * One **task**: a unit of work in a workflow, collecting some traits and offering some saves.
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
) {
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
 * states table -- the model `kd2-design/thoughts-workflow-poc.md` (private `sampwhite/Actions`) describes,
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
) {
    /** The tasks, in the order they are presented. */
    val tasks: List<WfTask> = tasks.toList()

    /** Tasks by id. */
    val tasksById: Map<String, WfTask> = tasks.associateBy { it.id }

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
    }

    /** The task named, or null. */
    fun task(id: String): WfTask? = tasksById[id]

    /**
     * Whether a page shows the list of tasks: **behavior, not configuration** -- a workflow with one task has
     * no list to show, so the list appears when there is more than one, and no attribute overrides it until
     * something asks for one.
     */
    val showTaskList: Boolean get() = tasks.size > 1

    override fun toString(): String = "$workflowId (${entry.name}, ${tasks.size} task(s))"
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
        type(WFD.taskType) {
            type = SCT.kObject
            description = "One task of a workflow: the traits it collects and the saves it offers."
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
        }
        type(WFD.defType) {
            type = SCT.kObject
            description = "A workflow definition: how it is entered, and its tasks."
            property(WFD.workflowId, "The workflow's base name; its client comes from the bundle declaring it.", required = true)
            property(WFD.entry, "How the workflow is entered.", required = true) { options(WfEntry.entries) }
            property(WFD.tasks, "The tasks, in presentation order.", required = true) {
                type = SCT.array
                allowCoerce = true
                items { ref(WFD.taskType) }
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
    val m = result.value.toJsonMapOrEmpty()
    return WfDef(
        workflowId = m[WFD.workflowId].toOptStr() ?: "",
        entry = enumNamed(WfEntry.entries, m[WFD.entry]),
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
            )
        },
    )
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

    /** Declares a task. */
    fun task(id: String, label: String, build: WfTaskBuilder.() -> Unit) {
        tasks.add(WfTaskBuilder(id, label).apply(build).build())
    }

    /** The definition as JSON, ready for [parseWfDef]. */
    fun build(): Map<String, Any?> = linkedMapOf(
        WFD.workflowId to workflowId,
        WFD.entry to entry.name,
        WFD.tasks to tasks.toList(),
    )
}

/** Authors one task's JSON; see [WfDefBuilder]. */
class WfTaskBuilder(private val id: String, private val label: String) {
    private val traits = mutableListOf<Map<String, Any?>>()
    private val saves = mutableListOf<Map<String, Any?>>()
    private var layout: Map<String, Any?>? = null

    /** A trait this task collects; required unless said otherwise. */
    fun trait(traitId: String, required: Boolean = true) {
        traits.add(linkedMapOf(WFD.traitId to traitId, WFD.required to required))
    }

    /** A save this task offers. */
    fun save(id: String, label: String, kind: WfSaveKind = WfSaveKind.create) {
        saves.add(linkedMapOf(WFD.id to id, WFD.label to label, WFD.kind to kind.name))
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
        return out
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
 * The target facts about one task (issue #533) -- what a view passes to the cfact registry's `assemble`
 * beside the request's own facts, so a task's layout can select on them.
 *
 * [WFC.taskAvailable] is **always present**: a placeholder that keeps the shape visible until availability
 * rules (dates, prior tasks) exist. Said here so nobody reads it as computed.
 */
object WfTaskFacts {
    fun of(task: WfTask, entries: List<Map<String, Any?>>): Set<String> {
        val facts = LinkedHashSet<String>()
        facts.add(WFC.taskAvailable)
        if (WfEngine.taskComplete(task, entries)) {
            facts.add(WFC.taskComplete)
        }
        return facts
    }
}
