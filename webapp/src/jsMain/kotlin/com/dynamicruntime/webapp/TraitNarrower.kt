package com.dynamicruntime.webapp

import com.dynamicruntime.common.gedra.CIT
import com.dynamicruntime.common.gedra.CLD
import com.dynamicruntime.common.gedra.CPY
import com.dynamicruntime.common.gedra.IMP
import com.dynamicruntime.common.gedra.TraitNarrowing
import com.dynamicruntime.common.gedra.baseChoicesOf
import com.dynamicruntime.common.gedra.traitNarrowingFromInfo
import com.dynamicruntime.common.gedra.traitNameOf
import com.dynamicruntime.common.gedra.traitNarrowingProblems
import com.dynamicruntime.common.schema.SCH
import com.dynamicruntime.common.schema.SchLayout
import com.dynamicruntime.common.schema.SchType
import com.dynamicruntime.common.schema.collectLayouts
import com.dynamicruntime.common.schema.overlayType
import com.dynamicruntime.common.schema.parseSchemaTypes
import com.dynamicruntime.common.schema.refName
import com.dynamicruntime.common.util.humanizeFieldName
import com.dynamicruntime.common.util.toJsonListOfStrings
import com.dynamicruntime.common.util.toJsonMapOrEmpty
import com.dynamicruntime.common.util.toOptStr

/**
 * Narrowing a global trait on a client's page (issue #1119, `TraitNarrowEditor`): what the editor shows of the trait's
 * fields, which moves each allows and why not, the name it gives the trait (issue #1534), the preview's schema, and the
 * save. The narrowing and the alteration it
 * writes are the kernel's ([TraitNarrowing]), so the preview is exactly what the backend will write.
 *
 * Everything here is pure, and covered under `jsNodeTest`.
 */

/**
 * A global trait's data type, as the catalog serves it: its qualified name, its body, and its layout -- and the
 * catalog's other types, [defs], which a field of the body may refer to (issue #1564: Site visit's address).
 */
class TraitBaseView(
    val typeName: String,
    val body: Map<String, Any?>,
    val layout: SchLayout?,
    val defs: Map<String, Any?> = emptyMap(),
) {
    /** The body with its layout, as the platform declares the type -- what an alteration merges over. */
    val bodyWithLayout: Map<String, Any?> get() = layout?.let { body + (SCH.layout to it.toJsonMap()) } ?: body

    /** The fields the type requires. */
    val required: Set<String> get() = body[SCH.required].toJsonListOfStrings().toSet()
}

/**
 * What the platform calls the trait [traitId] whose data type is [base] (issue #1534), by the rule every page names a
 * trait by ([traitNameOf]): its layout `label`, its title, its id in words.
 */
fun platformTraitName(traitId: String, base: TraitBaseView): String =
    traitNameOf(traitId, base.layout?.label, base.body[SCH.title].toOptStr())

/** [trait]'s data type from [catalog], or null when it has none the catalog serves. */
fun traitBaseOf(catalog: TraitCatalogView, trait: TraitChoiceView): TraitBaseView? {
    val typeName = trait.dataRef?.let(::refName) ?: return null
    val body = catalog.defs[typeName].toJsonMapOrEmpty().ifEmpty { return null }
    return TraitBaseView(typeName, body, catalog.layouts[typeName], catalog.defs)
}

/** One of a global trait's fields as the narrowing editor lists it. */
class NarrowFieldRow(
    val field: String,
    /** The field's label: the layout's, else the schema's title, else its key in words. */
    val label: String,
    /** Whether the trait itself requires it. */
    val baseRequired: Boolean,
    /** Its choices, value to label, or null for a field with none. */
    val choices: List<Pair<String, String>>?,
)

/** [base]'s fields, in the type's order, as the editor lists them. */
fun narrowFieldRows(base: TraitBaseView): List<NarrowFieldRow> =
    base.body[SCH.properties].toJsonMapOrEmpty().map { (field, raw) ->
        val prop = raw.toJsonMapOrEmpty()
        NarrowFieldRow(
            field = field,
            label = base.layout?.fieldFor(field)?.label ?: prop[SCH.title].toOptStr() ?: humanizeFieldName(field),
            baseRequired = field in base.required,
            choices = baseChoicesOf(prop),
        )
    }

/** This narrowing with what is given changed, and the rest kept: every move goes through it, so none drops another's. */
private fun TraitNarrowing.changed(
    hidden: Set<String> = this.hidden,
    required: Set<String> = this.required,
    choices: Map<String, List<String>> = this.choices,
    name: String? = this.name,
    introduction: String? = this.introduction,
) = TraitNarrowing(traitId, hidden, required, choices, name, introduction)

/** This narrowing with [field] hidden from forms, or shown. */
fun TraitNarrowing.withHidden(field: String, hide: Boolean) = changed(hidden = if (hide) hidden + field else hidden - field)

/** This narrowing with [field] required in the data, or not. */
fun TraitNarrowing.withRequired(field: String, require: Boolean) =
    changed(required = if (require) required + field else required - field)

/**
 * This narrowing keeping [kept] of [field]'s choices, of its [offered] ones: all of them, in any order, is no
 * restriction at all, so the field is left out.
 */
fun TraitNarrowing.withChoices(field: String, kept: List<String>, offered: List<String>): TraitNarrowing {
    val inOrder = offered.filter { it in kept }
    val restricted = if (inOrder.size == offered.size) choices - field else choices + (field to inOrder)
    return changed(choices = restricted)
}

/**
 * This narrowing naming the trait [typed] (issue #1534): trimmed, and no name of its own when it is blank or the
 * [platformName] -- a client that names a trait as the platform does keeps following the platform's name.
 */
fun TraitNarrowing.withName(typed: String, platformName: String): TraitNarrowing {
    return changed(name = typed.trim().takeIf { it.isNotEmpty() && it != platformName })
}

/** The introduction the platform gives the trait whose data type is [base] (issue #1534): its layout's description. */
fun platformTraitIntro(base: TraitBaseView): String = base.layout?.description.orEmpty()

/**
 * This narrowing introducing the trait with [typed] (issue #1534): none of its own when it is the [platformIntro] --
 * so a client that keeps the platform's keeps following it -- and an empty one, showing nothing, when the platform
 * has one and [typed] is blank.
 */
fun TraitNarrowing.withIntroduction(typed: String, platformIntro: String): TraitNarrowing =
    changed(introduction = typed.trim().takeUnless { it == platformIntro.trim() })

/** Why [row]'s field cannot be hidden, or null when it can: a form must show a field the data requires. */
fun hideLockedReason(row: NarrowFieldRow, narrowing: TraitNarrowing): String? = when {
    row.baseRequired -> "The trait requires it, so every form shows it."
    row.field in narrowing.required -> "Required here, so every form shows it."
    else -> null
}

/** Why [row]'s field cannot be made required, or not, here; null when it can. */
fun requireLockedReason(row: NarrowFieldRow, narrowing: TraitNarrowing): String? = when {
    row.baseRequired -> "The trait requires it."
    row.field in narrowing.hidden -> "Hidden from forms; show it to require it."
    else -> null
}

/** What is wrong with [narrowing] of [base], in the kernel's words. */
fun narrowProblems(narrowing: TraitNarrowing, base: TraitBaseView): List<String> = traitNarrowingProblems(narrowing, base.body)

/** Whether [narrowing] differs from the [baseline] it was opened from. */
fun narrowingChanged(narrowing: TraitNarrowing, baseline: TraitNarrowing): Boolean = narrowing.toInfo() != baseline.toInfo()

/** The preview's schema, by the type's own name: the trait as this client would take it. */
fun narrowPreviewDefs(base: TraitBaseView, narrowing: TraitNarrowing): Map<String, Any?> {
    val alteration = narrowing.toAlteration(base.body)
    // With the catalog's other types, which a field may refer to: parsed alone, such a field fails the whole preview.
    return base.defs + (base.typeName to (alteration?.let { overlayType(base.bodyWithLayout, it) } ?: base.bodyWithLayout))
}

/** A trait editor's preview (issue #1564): the type its form draws and the layouts it reads, or why it cannot draw one. */
class TraitPreview(val type: SchType?, val layouts: Map<String, SchLayout>, val problem: String?)

/**
 * The preview of [typeName] in [defs], as both trait editors draw it. A schema the kernel refuses is a [TraitPreview.problem]
 * the editor shows, not an empty preview: a preview missing its fields reads as a trait that has none.
 */
fun traitPreviewOf(defs: Map<String, Any?>, typeName: String): TraitPreview = try {
    TraitPreview(parseSchemaTypes(defs)[typeName], collectLayouts(defs), null)
} catch (e: Exception) {
    TraitPreview(null, emptyMap(), e.message ?: "The schema could not be read.")
}

/** The save's request: the narrowing, how the page expects it to take effect, and whether an impact is acknowledged. */
fun traitNarrowRequest(clientId: String, narrowing: TraitNarrowing, expected: String, acknowledge: Boolean): Map<String, Any?> = buildMap {
    put(CLD.client, clientId)
    put(CIT.narrowing, narrowing.toInfo())
    put(CPY.mode, expected)
    if (acknowledge) put(IMP.acknowledgeImpact, true)
}

/** What a narrowing's save did: the configuration it is in, the narrowing now, how it took effect, and a draft's impact. */
class TraitNarrowResult(
    val configName: String,
    val narrowing: TraitNarrowing,
    val mode: String,
    val draftImpact: ImpactReportView?,
    val forms: FormStatesAuto? = null,
)

fun parseTraitNarrowResult(raw: Map<String, Any?>): TraitNarrowResult = TraitNarrowResult(
    configName = raw[CIT.configName].toOptStr().orEmpty(),
    narrowing = traitNarrowingFromInfo(raw[CIT.narrowing].toJsonMapOrEmpty()),
    mode = raw[CPY.mode].toOptStr().orEmpty(),
    draftImpact = raw[CIT.draftImpact]?.toJsonMapOrEmpty()?.let(::parseImpactReport),
    forms = formStatesAutoOf(raw),
)

/**
 * What a draft save says of its impact (issue #1119): the report of publishing it, run against the live client --
 * for information, since publishing asks again. Null when there is none.
 */
fun draftImpactText(report: ImpactReportView?): String? {
    report ?: return null
    val findings = report.findings.joinToString(" ") { impactFindingText(it) }
    return "${impactSummary(report, "Publishing it")} $findings Publishing it will ask you to acknowledge that.".trim()
}
