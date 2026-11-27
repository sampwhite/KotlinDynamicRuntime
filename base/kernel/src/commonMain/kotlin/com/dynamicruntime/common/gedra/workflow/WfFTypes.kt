package com.dynamicruntime.common.gedra.workflow

import com.dynamicruntime.common.gedra.FTypeDef

/*
 * What a task that collects the traits of its form's type ([WfTask.ftypeTraits], issue #1903) collects, said once: the
 * view, the save, and the status go by [forFtype], the checks that ask without a form by [traitIdsOverFtypes], and the
 * workflow editor by [ftypeBrings].
 */

/** Whether a task of this workflow collects the traits of its form's type, so what it collects depends on the form. */
val WfDef.collectsFtype: Boolean get() = tasks.any { it.ftypeTraits }

/**
 * What a form [type] brings to a task that collects its form's type's traits (issue #1903), in the type's order: each
 * trait it lists that neither the task ([own]) nor another task of the workflow ([elsewhere]) does -- and each the task
 * lists as optional that the type requires, now required.
 *
 * An explicit list takes its traits for its own task, so one another task lists is left to that task, required there or
 * not as it says.
 */
fun ftypeBrings(own: List<WfTraitRef>, elsewhere: Set<String>, type: FTypeDef): List<WfTraitRef> {
    val listed = own.associateBy { it.traitId }
    return type.traits.filter { ref ->
        when (val mine = listed[ref.traitId]) {
            null -> ref.traitId !in elsewhere
            else -> ref.required && !mine.required
        }
    }
}

/** The traits the tasks of this workflow other than [task] list: those an ftype does not bring to [task]. */
private fun WfDef.listedElsewhere(task: WfTask): Set<String> =
    tasks.filter { it.id != task.id }.flatMap { t -> t.traits.map { it.traitId } }.toSet()

/**
 * This workflow as it is for a form of [type] (issue #1903), null for a form with no type or one no longer defined: the
 * task that collects the form's type's traits lists them, after its own, as though it had been written so. This
 * definition itself when nothing changes.
 *
 * What results is for drawing, saving, and judging one form. What an editor changes, and what a stamp is made of, is the
 * definition as written.
 */
fun WfDef.forFtype(type: FTypeDef?): WfDef {
    if (type == null || !collectsFtype) return this
    val resolved = tasks.map { task ->
        if (!task.ftypeTraits) return@map task
        val brought = ftypeBrings(task.traits, listedElsewhere(task), type).associateBy { it.traitId }
        val own = task.traits.map { brought[it.traitId] ?: it }
        val added = brought.values.filter { task.trait(it.traitId) == null }
        task.collecting(own + added)
    }
    return withTasks(resolved)
}

/**
 * The traits [task] of this workflow may collect from some form, over every type of [types] (issue #1903): its own, then
 * what each type brings to it. What is asked without a form: a check that they exist, a function's, where a type is used.
 */
fun WfDef.traitIdsOverFtypes(task: WfTask, types: Collection<FTypeDef>): List<String> {
    val own = task.traits.map { it.traitId }
    if (!task.ftypeTraits) return own
    val elsewhere = listedElsewhere(task)
    return (own + types.flatMap { type -> ftypeBrings(task.traits, elsewhere, type).map { it.traitId } }).distinct()
}

/** [traitIdsOverFtypes] of every task of this workflow, in task order. */
fun WfDef.traitIdsOverFtypes(types: Collection<FTypeDef>): List<String> = tasks.flatMap { traitIdsOverFtypes(it, types) }.distinct()
