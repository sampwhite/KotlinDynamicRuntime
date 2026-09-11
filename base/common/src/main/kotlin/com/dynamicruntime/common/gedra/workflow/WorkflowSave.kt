package com.dynamicruntime.common.gedra.workflow

import com.dynamicruntime.common.context.KdrCxt
import com.dynamicruntime.common.exception.EXC
import com.dynamicruntime.common.exception.KdrException
import com.dynamicruntime.common.gedra.GDF
import com.dynamicruntime.common.gedra.GE
import com.dynamicruntime.common.gedra.GedraDataService
import com.dynamicruntime.common.gedra.GedraDataType
import com.dynamicruntime.common.gedra.GedraEdit
import com.dynamicruntime.common.gedra.GedraEditAction
import com.dynamicruntime.common.gedra.GedraPatchTarget
import com.dynamicruntime.common.gedra.GedraService
import com.dynamicruntime.common.user.ReadScopeRules
import com.dynamicruntime.common.util.toJsonMapOrEmpty
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
 * A `create` save makes the gedra ([GedraDataService.createGedra]), stamping the workflow reference under
 * `creationWorkflowId`, and answers with the stored row the way `formDoc/create` does -- under [WSF.item]. An
 * `edit` save (a survey, issue #658) updates an existing form named by [gedraId] and answers with the updated
 * row under [WSF.item]. Only `create` and `edit` exist as save kinds; the `when` on [WfSaveKind] covers both, so
 * a later kind (submit/approve/export) is a **compile error** until it is handled here -- which is what keeps a
 * new kind from silently behaving as an unintended write, without a runtime "unknown kind" branch.
 */
fun saveWorkflow(
    cxt: KdrCxt,
    declared: WfDeclared,
    taskId: String,
    saveId: String,
    entries: List<Map<String, Any?>>,
    gedraId: String? = null,
): Map<String, Any?> {
    val task = declared.def.task(taskId)
        ?: throw KdrException.mkInput("Workflow '${declared.ref}' has no task '$taskId'.")
    val save = task.save(saveId)
        ?: throw KdrException.mkInput("Task '$taskId' of '${declared.ref}' has no save '$saveId'.")

    // A mistake, not a soft outcome: an entry naming a trait the task does not collect is a client error, so
    // it is refused loudly rather than folded into the completeness reasons. Applies to every save kind.
    val declaredTraits = task.traits.map { it.traitId }.toSet()
    entries.mapNotNull { it[GE.traitId].toOptStr() }.firstOrNull { it !in declaredTraits }?.let {
        throw KdrException.mkInput("Task '$taskId' does not collect the trait '$it'.")
    }

    return when (save.kind) {
        WfSaveKind.create -> {
            // The gate: a required trait with no entry present is not an error, it is an unfinished form.
            val unmet = WfEngine.missingTraits(task.requiredTraitIds, entries)
            if (unmet.isNotEmpty()) {
                return linkedMapOf<String, Any?>(WSF.saved to false, WSF.unmetTraits to unmet)
            }
            val row = GedraDataService.get(cxt)
                .createGedra(cxt, GedraDataType.formDoc, entries, creationWorkflowId = declared.ref)
            linkedMapOf<String, Any?>(WSF.saved to true, WSF.item to row.toJsonMap())
        }
        WfSaveKind.edit -> editForm(cxt, declared, gedraId, entries)
    }
}

/**
 * A survey's `edit` save (issue #658): fold the collected entries into the existing form.
 *
 * There is **no completeness gate** here, unlike a create: a survey may be saved part-finished, and its
 * incompleteness is recorded as state (for the CTA), not refused -- so this always saves. Each collected trait
 * is replaced wholesale ([GedraEditAction.addOrReplace]) with what the task supplied, through the ordinary
 * patch fold, so client-scope and validation are the patch endpoint's, unchanged. The form's derived survey
 * state is recomputed by the patch's own post-write hook (issue #675), inside the same transaction, so this
 * does not recompute it explicitly; it simply returns the updated row under [WSF.item].
 */
private fun editForm(
    cxt: KdrCxt,
    declared: WfDeclared,
    gedraId: String?,
    entries: List<Map<String, Any?>>,
): Map<String, Any?> {
    val fullId = gedraId
        ?: throw KdrException.mkInput("A '${declared.ref}' edit save needs a ${GDF.gedraId}: the form it updates.")
    val svc = GedraDataService.get(cxt)
    val id = GedraService.get(cxt).readId(fullId)
    val scope = ReadScopeRules.forCaller(cxt)
    val edits = entries.map { entry ->
        val traitId = entry[GE.traitId].toOptStr()
            ?: throw KdrException.mkInput("A survey edit entry has no ${GE.traitId}.")
        GedraEdit(GedraEditAction.addOrReplace, traitId, data = entry[GE.data].toJsonMapOrEmpty())
    }
    svc.patchGedras(cxt, mapOf(GedraDataType.formDoc to listOf(GedraPatchTarget(id, edits))), scope)
    val updated = svc.queryGedra(cxt, fullId, GedraDataType.formDoc, scope)
        ?: throw KdrException("The form '$fullId' could not be read back after its survey edit.", code = EXC.notFound)
    return linkedMapOf<String, Any?>(WSF.saved to true, WSF.item to updated.toJsonMap())
}
