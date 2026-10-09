package com.dynamicruntime.common.gedra.workflow

import com.dynamicruntime.common.context.KdrCxt
import com.dynamicruntime.common.exception.KdrException
import com.dynamicruntime.common.gedra.GE
import com.dynamicruntime.common.schema.SCH
import com.dynamicruntime.common.schema.SchFailure
import com.dynamicruntime.common.schema.SchLayoutMode
import com.dynamicruntime.common.schema.SchType
import com.dynamicruntime.common.schema.formRequirementFailures
import com.dynamicruntime.common.schema.refName
import com.dynamicruntime.common.startup.SchemaService
import com.dynamicruntime.common.util.toOptStr

/**
 * A workflow's **form requirements** (issue #1022): what its pages ask for beyond the schema -- a field its layout
 * requires, a shorter list of choices -- judged over a form's entries by the kernel's [formRequirementFailures]
 * under the layouts the workflow's pages draw ([workflowSchemaStore]: the client's, with the workflow's own
 * alterations merged in).
 *
 * A workflow does not alter schema, so these never change what data is valid. They decide what *this form*
 * accepts: its save refuses entries that miss them ([requireMet]), and its task status counts them
 * ([surveyContentFailures]), so data saved elsewhere that this form would not accept leaves its task unfinished.
 */
object WorkflowFormRules {
    /**
     * By trait id, the form requirements of [declared] that [entries] -- those whose trait is in [traitIds] and that
     * carry data -- do not meet; a trait with none is absent. Empty when the workflow's layouts ask for nothing more
     * than the schema does. Failure paths start at the entry (`data.venue`), as the validator's do.
     */
    fun failures(
        cxt: KdrCxt,
        client: String,
        declared: WfDeclared,
        traitIds: Set<String>,
        entries: List<Map<String, Any?>>,
    ): Map<String, List<SchFailure>> {
        val schema = SchemaService.get(cxt)
        val store = workflowSchemaStore(schema.storeFor(client), declared)
        val layouts = store.layouts
        if (layouts.values.none { layout -> layout.fields.any { it.required || it.choices != null } }) return emptyMap()
        val traits = schema.gedraTraitsFor(client).associateBy { it.traitId }
        val out = LinkedHashMap<String, MutableList<SchFailure>>()
        for (entry in entries) {
            val traitId = entry[GE.traitId].toOptStr()?.takeIf { it in traitIds } ?: continue
            val data = entry[GE.data] as? Map<*, *> ?: continue
            val typeName = traits[traitId]?.dataSchema?.get(SCH.dRef).toOptStr()?.let { refName(it) } ?: continue
            val type = store.types[typeName] ?: continue
            val failures = formRequirementFailures(type, layouts, data, GE.data)
            if (failures.isNotEmpty()) out.getOrPut(traitId) { mutableListOf() }.addAll(failures)
        }
        return out
    }

    /**
     * By trait id, the fields [declared]'s form shows for each of [traitIds] whose data type's layout -- as the
     * workflow's pages draw it, its own alterations merged in -- is **authoritative** (issue #1071): the fields it
     * lists, the only ones its form shows and so the only ones its edit save writes. A trait whose layout does not
     * choose its fields is absent. Each comes with the type, which the save needs to tell a derived field apart.
     */
    fun shownFields(cxt: KdrCxt, client: String, declared: WfDeclared, traitIds: Set<String>): Map<String, ShownFields> {
        val schema = SchemaService.get(cxt)
        val store = workflowSchemaStore(schema.storeFor(client), declared)
        if (store.layouts.values.none { it.mode == SchLayoutMode.authoritative }) return emptyMap()
        val traits = schema.gedraTraitsFor(client).associateBy { it.traitId }
        return traitIds.mapNotNull { traitId ->
            val typeName = traits[traitId]?.dataSchema?.get(SCH.dRef).toOptStr()?.let { refName(it) } ?: return@mapNotNull null
            val layout = store.layouts[typeName]?.takeIf { it.mode == SchLayoutMode.authoritative } ?: return@mapNotNull null
            val type = store.types[typeName] ?: return@mapNotNull null
            traitId to ShownFields(layout.fieldNames.toSet(), type)
        }.toMap()
    }

    /** The fields an authoritative layout shows for a trait ([shownFields]), with the trait's data [type]. */
    class ShownFields(val fields: Set<String>, val type: SchType)

    /**
     * Refuses a save through [declared] whose [entries] miss the workflow's form requirements -- the save's half of
     * the rule the page runs before it sends, so a call made around the page is held to the same form. [traitIds]
     * are the saving task's traits.
     */
    fun requireMet(cxt: KdrCxt, declared: WfDeclared, traitIds: Set<String>, entries: List<Map<String, Any?>>) {
        val failures = failures(cxt, cxt.client, declared, traitIds, entries)
        if (failures.isEmpty()) return
        throw KdrException.mkInput(
            "This form asks for more than was given: " +
                failures.entries.joinToString("; ") { (traitId, list) ->
                    "$traitId: " + list.joinToString(", ") { "${it.path}: ${it.message}" }
                } + ".",
        )
    }
}
