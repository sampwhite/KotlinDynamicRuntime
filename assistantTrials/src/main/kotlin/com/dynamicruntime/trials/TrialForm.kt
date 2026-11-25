package com.dynamicruntime.trials

import com.dynamicruntime.common.cfact.CFactParser
import com.dynamicruntime.common.gedra.GE
import com.dynamicruntime.common.gedra.workflow.WFD
import com.dynamicruntime.common.gedra.workflow.WVF
import com.dynamicruntime.common.schema.SCH
import com.dynamicruntime.common.schema.SCT
import com.dynamicruntime.common.schema.SFMT
import com.dynamicruntime.common.schema.SchLayoutField
import com.dynamicruntime.common.schema.SchType
import com.dynamicruntime.common.schema.isDataFile
import com.dynamicruntime.common.schema.offeredChoices
import com.dynamicruntime.common.schema.orderedFieldNames
import com.dynamicruntime.common.schema.parseDeliveredLayouts
import com.dynamicruntime.common.schema.parseSchemaTypes
import com.dynamicruntime.common.schema.refName
import com.dynamicruntime.common.schema.validate
import com.dynamicruntime.common.util.toJsonListOfMaps
import com.dynamicruntime.common.util.toJsonMapOrEmpty
import com.dynamicruntime.common.util.toOptStr
import kotlin.math.ceil
import kotlin.math.floor

/**
 * Fills a workflow's form from its resolved [view] (issue #1915), as a person at the page would: the fields the page shows
 * the caller, by the kernel's own rules for which those are, each with a value its schema takes. Nothing here knows a
 * trait's or a field's name, so a form is filled whatever the assistant called it.
 *
 * A field no value can be made for -- a file, text held to a pattern, one of several shapes -- is left out and named in
 * [skipped]. When it is required, the save says so, which is what the report then shows. [tag] is in every text, so a form
 * made again has no value a unique field would refuse.
 */
class TrialForm(view: Map<String, Any?>, private val tag: String) {
    private val types = parseSchemaTypes(view[SCH.dDefs].toJsonMapOrEmpty())
    private val layouts = parseDeliveredLayouts(view[WVF.fieldLayouts])
    private val facts = view[WVF.cfacts].toJsonMapOrEmpty()

    /** The fields left out because no value could be made for them, as `trait.field`. */
    val skipped = mutableListOf<String>()

    /** An entry for each trait of [task], a task of the view, filled in. */
    fun entries(task: Map<String, Any?>): List<Map<String, Any?>> = task[WFD.traits].toJsonListOfMaps().map { trait ->
        val traitId = trait[WFD.traitId].toOptStr().orEmpty()
        val type = trait[WVF.schemaRef].toOptStr()?.let { refName(it) }?.let { types[it] }
        mapOf(GE.traitId to traitId, GE.data to type?.let { fillObject(it, traitId, depth = 0) }.orEmpty())
    }

    private fun fillObject(type: SchType, path: String, depth: Int): Map<String, Any?> {
        val layout = type.name?.let { layouts[it] }
        val data = linkedMapOf<String, Any?>()
        for (name in orderedFieldNames(type, layout)) {
            val prop = type.properties[name] ?: continue
            // What the page does not ask the caller for: a value made elsewhere, and a field hidden from them.
            if (prop.valueType.derived || prop.valueType.counter != null || !shown(prop.visibleWhen)) continue
            val value = value(prop.valueType, layout?.fieldFor(name), "$path.$name", depth)
                ?.takeIf { validate(prop.valueType, it).isEmpty() }
            if (value == null) skipped.add("$path.$name") else data[name] = value
        }
        // A field the answers given withdraw is not on the page.
        type.condition?.let { condition -> data.keys.removeAll(condition.forbiddenWhen(condition.holds(data))) }
        return data
    }

    private fun shown(visibleWhen: String?): Boolean = visibleWhen == null ||
        CFactParser.analyze(visibleWhen, facts.keys, facts.filterValues { it == true }.keys).value == true

    /** A value of [type], or null when none can be made. A choice is the first offered. */
    private fun value(type: SchType, layout: SchLayoutField?, path: String, depth: Int): Any? {
        if (depth > maxDepth) return null
        type.constValue?.let { return it }
        offeredChoices(type, layout)?.takeIf { it.isNotEmpty() }?.let { return it.first().value }
        return when {
            type.isDataFile() || type.variants != null -> null
            type.jsonType == SCT.boolean -> true
            type.jsonType == SCT.integer -> number(type).toLong()
            type.jsonType == SCT.number -> number(type)
            type.jsonType == SCT.array -> type.itemType?.let { item ->
                value(item, null, path, depth + 1)?.let { one ->
                    // Each entry a value of its own only where entries may repeat: one is what a list is asked for.
                    if (type.uniqueItems) listOf(one) else List((type.minBound ?: 1.0).toInt().coerceAtLeast(1)) { one }
                }
            }
            type.jsonType == SCT.kObject || type.properties.isNotEmpty() -> fillObject(type, path, depth + 1)
            type.format == SFMT.date -> sampleDate
            type.format == SFMT.dateTime -> "${sampleDate}T17:00:00Z"
            type.format == SFMT.binary -> null
            type.format == emailFormat -> "walk-$tag@trials.test"
            else -> text(type, path)
        }
    }

    private fun number(type: SchType): Double {
        val low = type.minBound?.let { if (type.minExclusive) floor(it) + 1 else ceil(it) }
        val high = type.maxBound?.let { if (type.maxExclusive) ceil(it) - 1 else floor(it) }
        var n = sampleNumber
        if (high != null && n > high) n = high
        if (low != null && n < low) n = low
        return n
    }

    private fun text(type: SchType, path: String): String {
        val sample = "Sample ${path.substringAfterLast('.')} $tag"
        val padded = sample.padEnd(type.minBound?.toInt() ?: 0, '.')
        return type.maxBound?.let { padded.take(it.toInt()) } ?: padded
    }

    @Suppress("ConstPropertyName")
    companion object {
        private const val maxDepth = 8
        private const val sampleNumber = 500.0
        private const val sampleDate = "2027-03-01"
        private const val emailFormat = "email"
    }
}
