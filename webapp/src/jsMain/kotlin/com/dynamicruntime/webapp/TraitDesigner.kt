package com.dynamicruntime.webapp

import com.dynamicruntime.common.gedra.CIT
import com.dynamicruntime.common.gedra.CLD
import com.dynamicruntime.common.gedra.CPY
import com.dynamicruntime.common.gedra.IMP
import com.dynamicruntime.common.gedra.TraitChoiceDesign
import com.dynamicruntime.common.gedra.TraitDesign
import com.dynamicruntime.common.gedra.TraitFieldDesign
import com.dynamicruntime.common.gedra.TraitFieldKind
import com.dynamicruntime.common.gedra.schemaAttributeCount
import com.dynamicruntime.common.gedra.traitDesignFromInfo
import com.dynamicruntime.common.gedra.traitDesignProblems
import com.dynamicruntime.common.gedra.withKeys
import com.dynamicruntime.common.schema.SCH
import com.dynamicruntime.common.schema.resolveExtensions
import com.dynamicruntime.common.util.toJsonMapOrEmpty
import com.dynamicruntime.common.util.toOptStr

/**
 * The traits editor's draft (issue #1118, `TraitDesignEditor`): a [TraitDesign] being changed, and what the editor
 * decides about it -- what each change does to the draft, which kinds a field may take, what is wrong with it, what
 * the plan allows, and the save it sends. The schema and the keys are the kernel's ([TraitDesign.toDataSchema],
 * [withKeys]), so the preview is drawn from exactly what the backend will write.
 *
 * Everything here is pure, and covered under `jsNodeTest`.
 */

/** A new trait's draft: no name yet, and one text field to start from. */
fun newTraitDraft(): TraitDesign = TraitDesign(null, "", null, listOf(TraitFieldDesign(null, "", TraitFieldKind.text)))

/** This design with [field] in place of the one at [index]. */
fun TraitDesign.withField(index: Int, field: TraitFieldDesign): TraitDesign =
    copyFields(fields.mapIndexed { i, f -> if (i == index) field else f })

/** This design with a new, empty text field at the end. */
fun TraitDesign.withNewField(): TraitDesign = copyFields(fields + TraitFieldDesign(null, "", TraitFieldKind.text))

/** This design without the field at [index]. */
fun TraitDesign.withoutField(index: Int): TraitDesign = copyFields(fields.filterIndexed { i, _ -> i != index })

/** This design with the field at [index] moved by [by] places, kept within the list. */
fun TraitDesign.withFieldMoved(index: Int, by: Int): TraitDesign {
    val to = (index + by).coerceIn(0, fields.lastIndex)
    if (to == index) return this
    val list = fields.toMutableList()
    list.add(to, list.removeAt(index))
    return copyFields(list)
}

/** This design with [label] as its name and [description] -- blank, none. */
fun TraitDesign.withHeading(label: String, description: String): TraitDesign =
    copy(label = label, description = description.ifBlank { null })

private fun TraitDesign.copyFields(list: List<TraitFieldDesign>) = copy(fields = list)

/**
 * A new trait's draft extending [base] -- a global trait's id (issue #1119) -- or, with null, a trait of its own. Only a
 * trait not yet saved may change what it extends; its fields are kept either way.
 */
fun TraitDesign.extending(base: String?): TraitDesign = TraitDesign(traitId, label, description, fields, base)

/** This field relabeled. */
fun TraitFieldDesign.withLabel(label: String) = TraitFieldDesign(key, label, kind, description, required, choices)

/** This field required in the data, or not. */
fun TraitFieldDesign.withRequired(on: Boolean) = TraitFieldDesign(key, label, kind, description, on, choices)

/**
 * This field as one of [kind]: a choice field keeps its choices when it becomes the other kind of choice, and starts with
 * one empty choice when it becomes one at all; any other kind has none.
 */
fun TraitFieldDesign.withKind(kind: TraitFieldKind): TraitFieldDesign {
    val choices = when {
        !kind.hasChoices -> emptyList()
        this.kind.hasChoices -> choices
        else -> listOf(TraitChoiceDesign(null, ""))
    }
    return TraitFieldDesign(key, label, kind, description, required, choices)
}

/** This field with its choices as [choices]. */
fun TraitFieldDesign.withChoices(choices: List<TraitChoiceDesign>) = TraitFieldDesign(key, label, kind, description, required, choices)

/**
 * The kinds the field may take: any, for a field not saved yet; for one saved -- in [baseline] -- only what keeps its
 * data, which is its own kind, or between text and long text.
 */
fun kindsFor(field: TraitFieldDesign, baseline: TraitDesign?): List<TraitFieldKind> {
    val saved = baseline?.fields?.firstOrNull { it.key != null && it.key == field.key } ?: return TraitFieldKind.entries
    return TraitFieldKind.entries.filter { saved.kind.keepsDataAs(it) }
}

/** A kind of field as the editor's choice names it. */
fun kindLabel(kind: TraitFieldKind): String = when (kind) {
    TraitFieldKind.text -> "Text"
    TraitFieldKind.longText -> "Long text"
    TraitFieldKind.number -> "Number"
    TraitFieldKind.integer -> "Whole number"
    TraitFieldKind.yesNo -> "Yes / no"
    TraitFieldKind.date -> "Date"
    TraitFieldKind.choice -> "Choice"
    TraitFieldKind.choices -> "Several choices"
}

/**
 * [draft] with its keys, as the backend will give them: the preview's, and what the rules are asked of. An extension's
 * fields avoid its [base]'s keys, as the backend's do.
 */
fun keyedDraft(draft: TraitDesign, takenTraitIds: Set<String>, base: TraitBaseView? = null): TraitDesign =
    withKeys(draft, takenTraitIds, base?.body?.get(SCH.properties).toJsonMapOrEmpty().keys)

/** What is wrong with [draft], in the kernel's words, against the [baseline] it was opened from (null for a new trait). */
fun draftProblems(draft: TraitDesign, baseline: TraitDesign?, base: TraitBaseView? = null): List<String> =
    traitDesignProblems(keyedDraft(draft, emptySet(), base), baseline)

/** Whether [draft] differs from the [baseline] it was opened from; a new trait always does. */
fun draftChanged(draft: TraitDesign, baseline: TraitDesign?): Boolean = baseline == null || draft.toInfo() != baseline.toInfo()

/** How many attributes the plan counts in [draft]'s schema (issue #1109): the measure its per-schema limit is of. */
fun draftAttributeCount(draft: TraitDesign): Int = schemaAttributeCount(keyedDraft(draft, emptySet()).toDataSchema())

/** The qualified name the preview's schema is parsed under, so its layout joins to it. */
const val previewTypeName = "preview.TraitDraftData"

/**
 * The preview's schema, by [previewTypeName]: the draft as it will be written, over the [stored] schema it edits. An
 * extension's is resolved onto its [base], as the backend resolves it (issue #1119), so the preview shows the base's
 * fields, labeled by its layout, and the ones the draft adds.
 */
fun previewDefs(draft: TraitDesign, stored: Map<String, Any?>? = null, base: TraitBaseView? = null): Map<String, Any?> {
    val keyed = keyedDraft(draft, emptySet(), base)
    if (base == null) return mapOf(previewTypeName to keyed.toDataSchema(stored))
    val defs = mapOf(base.typeName to base.bodyWithLayout, previewTypeName to keyed.toExtension(base.typeName, base.required.toList(), stored))
    return resolveExtensions(defs).defs
}

/**
 * What the plan allows, said beside the fields (issue #1109): the attributes [count] against the per-schema limit,
 * and -- for a new trait -- the schemas the client has against its limit. Null for a client on no plan.
 */
fun draftQuotaText(quotas: TraitQuotasView?, count: Int, isNew: Boolean): String? {
    quotas ?: return null
    val parts = buildList {
        quotas.attributesLimit?.let { add("$count of $it fields") }
        if (isNew) {
            val used = quotas.schemasUsed
            val limit = quotas.schemasLimit
            if (used != null && limit != null) add("${used + 1} of $limit schemas with this one")
        }
    }
    if (parts.isEmpty()) return null
    return "The ${quotas.plan} plan: ${parts.joinToString("; ")}."
}

/** Why no field can be added, or null when one can: the draft has as many as the plan allows one schema. */
fun addFieldRefusal(quotas: TraitQuotasView?, count: Int): String? {
    val limit = quotas?.attributesLimit ?: return null
    return if (count >= limit) "The ${quotas.plan} plan allows $limit fields in one trait." else null
}

/** Why no new trait can be made, or null when one can: the client declares as many schemas as its plan allows. */
fun newTraitRefusal(quotas: TraitQuotasView?): String? {
    val used = quotas?.schemasUsed ?: return null
    val limit = quotas.schemasLimit ?: return null
    return if (used >= limit) "The ${quotas.plan} plan allows $limit schemas, and this client declares $used." else null
}

/** The save's request: the draft, how the page expects it to take effect, and whether an impact is acknowledged. */
fun traitDesignRequest(clientId: String, draft: TraitDesign, expected: String, acknowledge: Boolean): Map<String, Any?> = buildMap {
    put(CLD.client, clientId)
    put(CIT.design, draft.toInfo())
    put(CPY.mode, expected)
    if (acknowledge) put(IMP.acknowledgeImpact, true)
}

/**
 * What a design's save did: the configuration the trait is in, the trait as designed now, how it took effect, and --
 * for a draft -- what publishing it would do to the live client's forms (issue #1119).
 */
class TraitDesignResult(val configName: String, val design: TraitDesign, val mode: String, val draftImpact: ImpactReportView? = null)

fun parseTraitDesignResult(raw: Map<String, Any?>): TraitDesignResult = TraitDesignResult(
    configName = raw[CIT.configName].toOptStr().orEmpty(),
    design = traitDesignFromInfo(raw[CIT.design].toJsonMapOrEmpty()),
    mode = raw[CPY.mode].toOptStr().orEmpty(),
    draftImpact = raw[CIT.draftImpact]?.toJsonMapOrEmpty()?.let(::parseImpactReport),
)
