package com.dynamicruntime.common.gedra.workflow

import com.dynamicruntime.common.context.KdrCxt
import com.dynamicruntime.common.exception.KdrException
import com.dynamicruntime.common.gedra.GE
import com.dynamicruntime.common.gedra.GedraDataService
import com.dynamicruntime.common.gedra.GedraDataType
import com.dynamicruntime.common.util.toOptStr

/**
 * Saves the entries a workflow task collected (issue #535) -- the guarded write the creation page posts to.
 *
 * The two kinds of "no" are kept apart, deliberately:
 *  - A **mistake** is a loud [KdrException] (a 400): an unknown task or save, a save kind this batch does not
 *    support, or an entry whose trait the task never declared. These are programming or client errors, not a
 *    person's incomplete form, and a caller cannot make progress by "trying harder".
 *  - An **incomplete form** is a *result*, not an error: [WSF.saved] false with [WSF.unmetTraits] naming the
 *    required traits no entry satisfied. That is the soft-validation seam `gedra-patch.md` draws -- the gate
 *    stops the save without failing the call, and it says exactly what to go fill in.
 *
 * A satisfied save creates the gedra ([GedraDataService.createGedra]), stamping the workflow reference under
 * `creationWorkflowId`, and answers with the stored row the way `formDoc/create` does -- under [WSF.item].
 *
 * Only `create` exists as a save kind. A revise/update save (editing an existing gedra through the patch
 * fold) arrives with the normal workflow; refusing an unknown kind loudly here is what keeps a re-POST from
 * silently behaving as an unintended update before that path is built.
 */
fun saveWorkflow(
    cxt: KdrCxt,
    declared: WfDeclared,
    taskId: String,
    saveId: String,
    entries: List<Map<String, Any?>>,
): Map<String, Any?> {
    val task = declared.def.task(taskId)
        ?: throw KdrException.mkInput("Workflow '${declared.ref}' has no task '$taskId'.")
    val save = task.save(saveId)
        ?: throw KdrException.mkInput("Task '$taskId' of '${declared.ref}' has no save '$saveId'.")
    if (save.kind != WfSaveKind.create) {
        throw KdrException.mkInput(
            "Save '$saveId' is a '${save.kind}' save, which is not supported yet; only '${WfSaveKind.create}' is.",
        )
    }

    // A mistake, not a soft outcome: an entry naming a trait the task does not collect is a client error, so
    // it is refused loudly rather than folded into the completeness reasons.
    val declaredTraits = task.traits.map { it.traitId }.toSet()
    entries.mapNotNull { it[GE.traitId].toOptStr() }.firstOrNull { it !in declaredTraits }?.let {
        throw KdrException.mkInput("Task '$taskId' does not collect the trait '$it'.")
    }

    // The gate: a required trait with no entry present is not an error, it is an unfinished form.
    val unmet = WfEngine.missingTraits(task.requiredTraitIds, entries)
    if (unmet.isNotEmpty()) {
        return linkedMapOf<String, Any?>(WSF.saved to false, WSF.unmetTraits to unmet)
    }

    val row = GedraDataService.get(cxt)
        .createGedra(cxt, GedraDataType.formDoc, entries, creationWorkflowId = declared.ref)
    return linkedMapOf<String, Any?>(WSF.saved to true, WSF.item to row.toJsonMap())
}
