package com.dynamicruntime.common.gedra.workflow

import com.dynamicruntime.common.gedra.GedraDataRow
import com.dynamicruntime.common.context.KdrCxt
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
import com.dynamicruntime.common.util.toOptStr
import com.dynamicruntime.common.gedra.entryDataOf

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
 * `edit` save (a survey, issue #658) updates an existing form named by [gedraId] and answers with only that id: like
 * a patch, it says the form's state may have moved rather than carrying it (issue #827), and a page re-reads the
 * workflow view to show what the save changed. Only `create` and `edit` exist as save kinds; the `when` on [WfSaveKind] covers both, so
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
    /** An edit save's check on the form, run under its lock before the write (issue #856); see [GedraPatchTarget.underLock]. */
    underLock: ((GedraDataRow, List<Map<String, Any?>>) -> Unit)? = null,
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
    // What this workflow's form asks for beyond the schema (issue #1022): refused here as the page refuses it, so a
    // call made around the page is held to the same form. Never asked of a general data edit.
    WorkflowFormRules.requireMet(cxt, declared, declaredTraits, entries)

    return when (save.kind) {
        WfSaveKind.create -> {
            // A create makes a new form (issue #817): one named by id is a caller mistake -- a creation workflow
            // opened against an existing form -- and ignoring the id would quietly make a second form.
            if (gedraId != null) {
                throw KdrException.mkInput(
                    "Save '$saveId' of task '$taskId' creates a new form, so it takes no ${GDF.gedraId}; " +
                        "an existing form is changed by an edit save.",
                )
            }
            // The gate: a required trait with no entry present is not an error, it is an unfinished form.
            val unmet = WfEngine.missingTraits(task.requiredTraitIds, entries)
            if (unmet.isNotEmpty()) {
                return linkedMapOf<String, Any?>(WSF.saved to false, WSF.unmetTraits to unmet)
            }
            val row = GedraDataService.get(cxt)
                .createGedra(cxt, GedraDataType.formDoc, entries, creationWorkflowId = declared.ref)
            linkedMapOf<String, Any?>(WSF.saved to true, WSF.item to row.toJsonMap())
        }
        WfSaveKind.edit -> editForm(cxt, declared, gedraId, entries, underLock)
    }
}

/**
 * A survey's `edit` save (issue #658): fold the collected entries into the existing form.
 *
 * There is **no completeness gate** here, unlike a create: a survey may be saved part-finished, and its
 * incompleteness is recorded as state (for the CTA), not refused -- so this always saves. Each collected trait
 * is replaced wholesale ([GedraEditAction.addOrReplace]) with what the task supplied, through the ordinary
 * patch fold, so client-scope and validation are the patch endpoint's, unchanged -- except a trait whose form shows
 * only the fields its layout lists (`authoritative`, issue #1071): that form's answers are **merged** over the stored
 * entry, the fields it shows written and every other one left as stored, so workflows filling different parts of one
 * form leave each other's answers alone; a field it does not show is refused. The merged entry is validated whole. The form's derived survey
 * state is recomputed by the patch's own post-write hook (issue #675), inside the same transaction, so this
 * does not recompute it explicitly. It answers with the form's id and nothing of its data (issue #827): the view
 * is the one place a page reads a form's workflow state from, so a save that returned it too would be a second.
 */
private fun editForm(
    cxt: KdrCxt,
    declared: WfDeclared,
    gedraId: String?,
    entries: List<Map<String, Any?>>,
    underLock: ((GedraDataRow, List<Map<String, Any?>>) -> Unit)?,
): Map<String, Any?> {
    val fullId = gedraId
        ?: throw KdrException.mkInput("A '${declared.ref}' edit save needs a ${GDF.gedraId}: the form it updates.")
    val svc = GedraDataService.get(cxt)
    val id = GedraService.get(cxt).readId(fullId)
    val scope = ReadScopeRules.forCaller(cxt)
    // The traits whose form shows only the fields its layout lists (issue #1071): a save writes those, and no others.
    val shown = WorkflowFormRules.shownFields(cxt, cxt.client, declared, entries.mapNotNull { it[GE.traitId].toOptStr() }.toSet())
    val edits = entries.map { entry ->
        val traitId = entry[GE.traitId].toOptStr()
            ?: throw KdrException.mkInput("A survey edit entry has no ${GE.traitId}.")
        // Absent data is not `{}` (issue #818): replacing an entry with nothing would store an empty one. The endpoint's
        // schema check already refuses it over HTTP; this holds for a caller reaching here from code.
        val data = entryDataOf(
            entry, traitId,
            missingHint = " An edit save replaces each entry with the data it carries; to remove an entry, delete it " +
                "(a patch with ${GedraEditAction.deleteOrNoOp.name}).",
        )
        val form = shown[traitId] ?: return@map GedraEdit(GedraEditAction.addOrReplace, traitId, data = data)
        // A field the form does not show is not this save's to write. A derived one is the server's, dropped as it
        // always is, so it is let through to be.
        val outside = data.keys.filter { it !in form.fields && form.type.properties[it]?.valueType?.derived != true }
        if (outside.isNotEmpty()) {
            throw KdrException.mkInput(
                "This form does not show ${outside.joinToString(", ") { "'$it'" }} of '$traitId', so its save does not " +
                    "write ${if (outside.size == 1) "it" else "them"}. It shows ${form.fields.joinToString(", ") { "'$it'" }}; " +
                    "change another field through the form that shows it.",
            )
        }
        // Merged over what is stored: the fields this form shows are its answers -- one not sent was left empty -- and
        // every other field is another form's, kept as it is.
        GedraEdit(GedraEditAction.addOrMerge, traitId, data = data, owns = form.fields)
    }
    svc.patchGedras(cxt, mapOf(GedraDataType.formDoc to listOf(GedraPatchTarget(id, edits, underLock))), scope)
    return linkedMapOf<String, Any?>(WSF.saved to true, GDF.gedraId to id.fullId)
}
