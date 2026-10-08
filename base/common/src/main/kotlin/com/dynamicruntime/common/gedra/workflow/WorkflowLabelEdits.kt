package com.dynamicruntime.common.gedra.workflow

import com.dynamicruntime.common.exception.KdrException
import com.dynamicruntime.common.schema.SCH
import com.dynamicruntime.common.schema.SL
import com.dynamicruntime.common.util.toJsonMapOrEmpty

/*
 * Page-level copy edited from Design View (issue #1070): the labels a workflow owns -- its own, its tasks', its
 * saves' -- written in its definition in place, and a workflow's own heading for a type its pages draw, written as a
 * layout alteration of the type beside its field copy (#984). Pure over a definition's JSON form, so a test pins them.
 */

/**
 * [definition] -- a workflow definition's JSON form -- with one of its labels set to [label] (issue #1070): the
 * workflow's own when [taskId] is null, a task's when only [taskId] is given, and a save's when [saveId] is too. A
 * label the workflow owns is edited in place: there is no shared label beneath it to fall back to.
 *
 * The workflow's own label is optional, so a null or blank [label] removes it and the page falls back to its generic
 * title. A task's and a save's are required, so a blank one is refused, as is a task or save the definition does not
 * have.
 */
fun withWorkflowLabel(definition: Map<String, Any?>, taskId: String?, saveId: String?, label: String?): Map<String, Any?> {
    val text = label?.trim()?.takeIf { it.isNotEmpty() }
    val out = LinkedHashMap(definition)
    if (taskId == null) {
        if (saveId != null) throw KdrException.mkInput("A save is named within its task; name the task too.")
        if (text == null) out.remove(WFD.label) else out[WFD.label] = text
        return out
    }
    val what = if (saveId == null) "A task" else "A save"
    text ?: throw KdrException.mkInput("$what needs a label; it is what the page shows for it.")
    val tasks = (definition[WFD.tasks] as? List<*>).orEmpty().map { LinkedHashMap(it.toJsonMapOrEmpty()) }
    val task = tasks.firstOrNull { it[WFD.id] == taskId }
        ?: throw KdrException.mkInput("The workflow has no task '$taskId'.")
    if (saveId == null) {
        task[WFD.label] = text
    } else {
        val saves = (task[WFD.saves] as? List<*>).orEmpty().map { LinkedHashMap(it.toJsonMapOrEmpty()) }
        val save = saves.firstOrNull { it[WFD.id] == saveId }
            ?: throw KdrException.mkInput("Task '$taskId' has no save '$saveId'.")
        save[WFD.label] = text
        task[WFD.saves] = saves
    }
    out[WFD.tasks] = tasks
    return out
}

/**
 * The key in a workflow's `typeBasis` (issue #1070) under which a heading override records the shared heading it
 * replaced: the layout keyword's own name, beside the field entries' bases, which are keyed by field.
 */
const val headingBasisKey: String = SCH.layout

/**
 * [definition] with the workflow's own heading for [typeName] set to [label], or removed when [label] is null or blank
 * (issue #1070): the `label` of its layout alteration of the type, which merges over the client's layout as a block
 * key -- replacing the shared heading -- while its field entries merge by field. [inherited] -- the client's heading
 * for the type, or null -- is recorded as the override's basis, and removed with it. An emptied alteration leaves no
 * trace ([withLayoutAlteration], which [withLayoutEntry] shares).
 */
fun withLayoutHeading(
    definition: Map<String, Any?>,
    typeName: String,
    label: String?,
    inherited: String?,
): Map<String, Any?> = withLayoutAlteration(definition, typeName) { layout, typeBasis ->
    val text = label?.trim()?.takeIf { it.isNotEmpty() }
    if (text == null) layout.remove(SL.label) else layout[SL.label] = text
    if (text != null) {
        typeBasis[headingBasisKey] = linkedMapOf<String, Any?>().also { b -> inherited?.let { b[SL.label] = it } }
    } else {
        typeBasis.remove(headingBasisKey)
    }
}

/** The heading of [typeName]'s `g-layout` as [defs] have it, or null when it has none (issue #1070). */
fun layoutHeadingOf(defs: Map<String, Any?>, typeName: String): String? =
    ((defs[typeName] as? Map<*, *>)?.get(SCH.layout) as? Map<*, *>)?.get(SL.label) as? String
