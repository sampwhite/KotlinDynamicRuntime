package com.dynamicruntime.common.gedra.workflow

import com.dynamicruntime.common.context.KdrCxtBase
import com.dynamicruntime.common.exception.KdrException
import com.dynamicruntime.common.overlay.KeyedElement
import com.dynamicruntime.common.overlay.MCH
import com.dynamicruntime.common.overlay.MergeRule
import com.dynamicruntime.common.overlay.MergeSpec
import com.dynamicruntime.common.overlay.OnNew
import com.dynamicruntime.common.overlay.diffOverlay
import com.dynamicruntime.common.overlay.mergeOverlay
import com.dynamicruntime.common.schema.SCH
import com.dynamicruntime.common.schema.schemaTypeMergeSpec
import com.dynamicruntime.common.util.Problem
import com.dynamicruntime.common.util.toJsonListOfMaps
import com.dynamicruntime.common.util.toJsonMapOrEmpty
import com.dynamicruntime.common.util.toOptStr

/*
 * Workflows made from templates (issue #1120).
 *
 * A **template** is a global workflow: declared in source by a component, with a rooted id (`kdr:survey`). No client
 * runs one as it is. A client takes a workflow from it by naming it ([WFD.fromTemplate]) in a workflow of its own, and
 * what the client stores is an **overlay** of the template -- only what it changed -- which [expandWorkflowTemplate]
 * merges over the template's current definition whenever the workflow is read. A fix to a template therefore reaches
 * every workflow made from it, and a template change the overlay no longer fits is a problem in that client's
 * workflow, found where any other is.
 *
 * The overlay is merged by declared rules ([workflowTemplateMergeSpec]): a task is targeted by its id, never by
 * restating the list, and its traits restated (or, if the overlay asks, merged by trait id); a type's layout is merged
 * by the schema type's own rules. A workflow made from a template has its template's structure -- the tasks, their
 * saves, cfacts, and rules -- so an overlay may say only what a client chooses ([templateOverlayProblems]): the
 * workflow's name and label, and per task its label, traits, and save labels, and how its pages lay types out.
 *
 * The other direction is a diff ([workflowTemplateOverlay]): an editor changes the expanded workflow, and what is
 * stored is the overlay that makes the template into it.
 */

/** The workflow templates core ships (issue #1120), each a global workflow in the config [configName]. */
@Suppress("ConstPropertyName")
object WFT {
    const val configName = "coreWorkflowTemplates"

    /** A creation workflow: one task naming the form, whose save creates it. */
    const val createForm = "kdr:createForm"

    /** A survey: one task an owner fills in and saves. */
    const val survey = "kdr:survey"

    /** A normal workflow with one data-entry task. */
    const val collect = "kdr:collect"
}

/** The deployment's workflow templates, by rooted id (issue #1120): what a workflow made from one is read over. */
fun interface WfTemplateLookup {
    fun template(templateId: String): WfDef?
}

/** How a task of a workflow made from a template merges: by its own rules, as a resource (issue #1120). */
val workflowTaskMergeSpec: MergeSpec = MergeSpec(
    rules = mapOf(
        // Restated by default: the overlay's traits are the task's, which is what choosing a task's traits means.
        WFD.traits to MergeRule.RestateKeyed(WFD.traitId),
        WFD.saves to MergeRule.Keyed(
            WFD.id,
            KeyedElement.merge,
            OnNew.refuse,
            refusalHint = "A workflow made from a template saves as its template does.",
        ),
    ),
    directiveKey = WFD.merge,
    choices = mapOf(
        WFD.traits to mapOf(
            MCH.restate to MergeRule.RestateKeyed(WFD.traitId),
            MCH.merge to MergeRule.Keyed(WFD.traitId, KeyedElement.merge, OnNew.append),
        ),
    ),
)

/**
 * How a workflow made from a template merges its overlay over the template (issue #1120): tasks targeted by id, each a
 * resource of its own ([workflowTaskMergeSpec]); the types it alters each merged by a schema type's rules, so its
 * layout entries are targeted by field; and what each altered layout entry replaced ([WFD.typeBasis]) per field.
 */
val workflowTemplateMergeSpec: MergeSpec = MergeSpec(
    rules = mapOf(
        WFD.tasks to MergeRule.Keyed(
            WFD.id,
            KeyedElement.merge,
            OnNew.refuse,
            refusalHint = "A workflow made from a template has its template's tasks.",
            elementSpec = { _, _ -> workflowTaskMergeSpec },
        ),
        WFD.types to MergeRule.Resources { _, _ -> schemaTypeMergeSpec },
        WFD.typeBasis to MergeRule.Resources { _, _ -> MergeSpec(emptyMap()) },
    ),
)

/** What an overlay may say of the workflow as a whole; anything else is the template's. */
private val workflowOverlayKeys = setOf(
    WFD.workflowId, WFD.fromTemplate, WFD.label, SCH.dComment, WFD.tasks, WFD.types, WFD.typeBasis,
    // A normal workflow's time windows are the client's to set (issue #1121); the definition refuses them on any other.
    WFD.lifetime, WFD.relevancy, WFD.engagement,
)

/** What an overlay may say of one of the template's tasks. */
private val taskOverlayKeys = setOf(WFD.id, WFD.label, WFD.traits, WFD.saves, SCH.dComment, WFD.merge)

/** What an overlay may say of one of a task's saves: what its button is called. */
private val saveOverlayKeys = setOf(WFD.id, WFD.label)

/**
 * What [overlay] says that a workflow made from a template may not (issue #1120), in words: anything of the template's
 * structure -- its entry, its rules and functions, a task's functions or approval, a save's kind. Empty when nothing.
 */
fun templateOverlayProblems(overlay: Map<String, Any?>): List<String> = buildList {
    fun fixed(what: String) = add("$what is the template's, and a workflow made from it keeps it.")
    (overlay.keys - workflowOverlayKeys).forEach { fixed("'$it'") }
    for (task in overlay[WFD.tasks].toJsonListOfMaps()) {
        val id = task[WFD.id].toOptStr() ?: "(no id)"
        (task.keys - taskOverlayKeys).forEach { fixed("Task '$id''s '$it'") }
        for (save in task[WFD.saves].toJsonListOfMaps()) {
            (save.keys - saveOverlayKeys).forEach { fixed("Task '$id''s save '${save[WFD.id].toOptStr()}''s '$it'") }
        }
    }
}

/**
 * The workflow [overlay] makes of [template] (issue #1120): the overlay merged over the template's definition by
 * [workflowTemplateMergeSpec], read as any definition is. The result names its template ([WfDef.fromTemplate]) and keeps
 * the overlay it came from ([WfDef.templateOverlay]), which is what a store writes back.
 *
 * Refused as a conversion fault -- what a definition that does not read is -- when the overlay says what only the
 * template may ([templateOverlayProblems]), targets a task or save the template does not have, or the result is not a
 * workflow.
 */
fun expandWorkflowTemplate(cxt: KdrCxtBase, template: WfDef, overlay: Map<String, Any?>): WfDef {
    val id = overlay[WFD.workflowId].toOptStr() ?: "(no id)"
    fun refuse(why: String): Nothing = throw KdrException.mkConv("Workflow '$id' cannot be made from template '${template.workflowId}': $why")
    if (template.fromTemplate != null) refuse("a template is a workflow written whole, and this one is made from '${template.fromTemplate}'.")
    templateOverlayProblems(overlay).takeIf { it.isNotEmpty() }?.let { refuse(it.joinToString(" ")) }
    val outcome = mergeOverlay(workflowTemplateMergeSpec, template.toJsonMap(), overlay)
    if (outcome.problems.isNotEmpty()) refuse(problemsText(outcome.problems))
    return parseWfDef(cxt, withoutNulls(outcome.value), overlay)
}

/**
 * What a workflow made from [template] stores to be [edited] (issue #1120): the overlay that [expandWorkflowTemplate]
 * makes back into it, stating only what differs from the template -- the workflow's own id and its template first.
 * Refused as bad input when [edited] changes what an overlay cannot state, or what only the template may say.
 */
fun workflowTemplateOverlay(template: WfDef, edited: WfDef): Map<String, Any?> {
    val diff = diffOverlay(workflowTemplateMergeSpec, template.toJsonMap(), edited.toJsonMap())
    val problems = diff.problems.map { located(it) } + templateOverlayProblems(diff.overlay)
    if (problems.isNotEmpty()) {
        throw KdrException.mkInput("Workflow '${edited.workflowId}' cannot be kept as a change to template '${template.workflowId}': ${problems.joinToString(" ")}")
    }
    return linkedMapOf<String, Any?>(WFD.workflowId to edited.workflowId, WFD.fromTemplate to template.workflowId) +
        diff.overlay.filterKeys { it != WFD.workflowId && it != WFD.fromTemplate }
}

/**
 * Whether a task is one a workflow made from a template chooses the traits and label of (issue #1120): any task but an
 * approval, which collects nothing -- a reviewer's decision, whose wording is the template's.
 */
val WfTask.isDataEntry: Boolean get() = approval == null

/** [raw] with every null-valued entry gone, at any depth: what an overlay's removals leave behind in a merge. */
private fun withoutNulls(raw: Map<String, Any?>): Map<String, Any?> = raw.filterValues { it != null }.mapValues { (_, v) -> withoutNullsIn(v) }

private fun withoutNullsIn(value: Any?): Any? = when (value) {
    is Map<*, *> -> withoutNulls(value.toJsonMapOrEmpty())
    is List<*> -> value.map { withoutNullsIn(it) }
    else -> value
}

private fun located(p: Problem): String = p.location?.path?.takeIf { it.isNotEmpty() }?.let { "$it: ${p.message}" } ?: p.message

private fun problemsText(problems: List<Problem>): String = problems.joinToString(" ") { located(it) }

/**
 * A workflow made from a template that a **stored** config holds and that does not read (issue #1120): its template is
 * gone, or has changed so the overlay no longer fits it. Like an unread report, it costs only itself -- left out of the
 * config's workflows and reported, rather than failing the config with everything else in it -- and is kept verbatim
 * and written back on every write, so an edit of anything else never deletes it.
 */
class UnreadWorkflow(val workflowId: String, val definition: Map<String, Any?>, val why: String)

/** One data-entry task of a workflow made from a template, as a client chooses it (issue #1120): its label and its traits. */
class WfTaskSlot(val taskId: String, val label: String, val traits: List<WfTraitRef>)

/**
 * What a client chooses of a workflow made from a template (issue #1120) -- its **slots**: the workflow's name, and per
 * data-entry task ([isDataEntry]) its label and the traits it collects. Everything else is the template's. [workflowId]
 * is null for a workflow not made yet, which gets one from its name.
 *
 * On the wire it has an overlay's shape, restricted to the slots ([toInfo]), so what the slot form sends reads as what
 * is stored.
 */
class WfTemplateSlots(val workflowId: String?, val templateId: String, val label: String, val tasks: List<WfTaskSlot>) {
    fun task(taskId: String): WfTaskSlot? = tasks.firstOrNull { it.taskId == taskId }

    fun toInfo(): Map<String, Any?> = buildMap {
        workflowId?.let { put(WFD.workflowId, it) }
        put(WFD.fromTemplate, templateId)
        put(WFD.label, label)
        put(
            WFD.tasks,
            tasks.map { t ->
                linkedMapOf(
                    WFD.id to t.taskId,
                    WFD.label to t.label,
                    WFD.traits to t.traits.map { linkedMapOf(WFD.traitId to it.traitId, WFD.required to it.required) },
                )
            },
        )
    }
}

/** Slots read off the wire ([WfTemplateSlots.toInfo]). */
fun templateSlotsFromInfo(raw: Map<String, Any?>): WfTemplateSlots = WfTemplateSlots(
    workflowId = raw[WFD.workflowId].toOptStr()?.trim()?.ifEmpty { null },
    templateId = raw[WFD.fromTemplate].toOptStr().orEmpty(),
    label = raw[WFD.label].toOptStr()?.trim().orEmpty(),
    tasks = raw[WFD.tasks].toJsonListOfMaps().map { t ->
        WfTaskSlot(
            taskId = t[WFD.id].toOptStr().orEmpty(),
            label = t[WFD.label].toOptStr()?.trim().orEmpty(),
            traits = t[WFD.traits].toJsonListOfMaps().map { WfTraitRef(it[WFD.traitId].toOptStr().orEmpty(), it[WFD.required] as? Boolean ?: true) },
        )
    },
)

/**
 * [def]'s slots as they stand (issue #1120): of a template, what a new workflow made from it starts from; of a
 * workflow made from one, its own.
 */
fun templateSlotsOf(def: WfDef): WfTemplateSlots = WfTemplateSlots(
    workflowId = def.workflowId.takeIf { def.fromTemplate != null },
    templateId = def.fromTemplate ?: def.workflowId,
    label = def.label,
    tasks = def.tasks.filter { it.isDataEntry }.map { WfTaskSlot(it.id, it.label, it.traits) },
)

/**
 * [base] -- a template, or a workflow made from it -- with [slots] filled in, as the workflow [workflowId] (issue #1120):
 * its name, and each named task's label and traits. What is not a slot is [base]'s, so a workflow's other changes
 * (Design View's layouts and save labels) survive a change of its slots.
 */
fun withTemplateSlots(cxt: KdrCxtBase, base: WfDef, slots: WfTemplateSlots, workflowId: String): WfDef {
    val json = LinkedHashMap(base.toJsonMap())
    json[WFD.workflowId] = workflowId
    json[WFD.fromTemplate] = slots.templateId
    json[WFD.label] = slots.label
    json[WFD.tasks] = json[WFD.tasks].toJsonListOfMaps().map { task ->
        val slot = slots.task(task[WFD.id].toOptStr().orEmpty()) ?: return@map task
        task + mapOf(
            WFD.label to slot.label,
            WFD.traits to slot.traits.map { linkedMapOf(WFD.traitId to it.traitId, WFD.required to it.required) },
        )
    }
    return parseWfDef(cxt, json)
}

/**
 * What is wrong with [slots] for [template], in words, or nothing (issue #1120): a name, and per task a label and at
 * least one trait, none twice -- for tasks the template has, and that take traits. Whether the client may collect each
 * trait is the load's to say, as for any workflow.
 */
fun templateSlotProblems(template: WfDef, slots: WfTemplateSlots): List<String> = buildList {
    if (slots.templateId != template.workflowId) add("These are the slots of '${slots.templateId}', not of '${template.workflowId}'.")
    if (slots.label.isBlank()) add("The workflow needs a name.")
    for (slot in slots.tasks) {
        val task = template.tasksById[slot.taskId]
        when {
            task == null -> add("Template '${template.workflowId}' has no task '${slot.taskId}'.")
            !task.isDataEntry -> add("Task '${slot.taskId}' is an approval, which collects nothing.")
            else -> {
                if (slot.label.isBlank()) add("Task '${slot.taskId}' needs a label.")
                if (slot.traits.isEmpty()) add("Task '${slot.label.ifBlank { slot.taskId }}' collects at least one trait.")
                slot.traits.groupBy { it.traitId }.filterValues { it.size > 1 }.keys.forEach {
                    add("Task '${slot.label.ifBlank { slot.taskId }}' collects '$it' twice.")
                }
            }
        }
    }
}
