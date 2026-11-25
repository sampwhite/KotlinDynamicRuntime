package com.dynamicruntime.common.schema

import com.dynamicruntime.common.util.analyzeTemplate
import com.dynamicruntime.common.util.humanizeFieldName

/*
 * What a person calls a field (issue #1918): one rule, so a message or a listing names a field as the form beside it does.
 *
 * The answer differs by place, as it should: a layout belongs to a client, and a workflow may change it on its own pages.
 * So a place that names a field passes the layout it draws from, and no name is fixed earlier than that. The endpoint
 * catalog, which documents the wire, shows the field's name as it is and does not ask.
 */

/**
 * The label of the field [name], declared as [prop]: [layoutLabel] when its place has one, else the property's own title,
 * else its name in words. Never its type's title, which every field referring to that type shares. [layoutLabel] is as the
 * place shows it, its copy filled in; a place that cannot fill one in passes [plainLayoutLabel].
 */
fun fieldLabelOf(name: String, prop: SchProperty?, layoutLabel: String? = null): String =
    layoutLabel ?: prop?.title?.trim()?.takeIf { it.isNotEmpty() } ?: humanizeFieldName(name)

/** [fieldLabelOf] under [layout], for a place with no data or copy to fill a template in with: a template reads as none. */
fun fieldLabelOf(layout: SchLayout?, name: String, prop: SchProperty?): String =
    fieldLabelOf(name, prop, plainLayoutLabel(layout?.fieldFor(name)?.label))

/** A layout's [label] as it reads with nothing filled in; null when it is blank, or a template (`%{...}`, `${'$'}{...}`). */
fun plainLayoutLabel(label: String?): String? = label?.trim()?.takeIf { it.isNotEmpty() && "%{" !in it && $$"${" !in it }

/**
 * What a place's form calls the field named, of an object of the type given holding the data given (issue #1918): its
 * layout's label, or null when it has none, and the field's title then stands.
 */
typealias SchLabelSource = (owner: SchType, field: String, data: Map<*, *>) -> String?

/** The labels of [layouts], keyed by type name, as they read with nothing filled in ([plainLayoutLabel]). */
fun plainLabels(layouts: Map<String, SchLayout>): SchLabelSource =
    { owner, field, _ -> plainLayoutLabel(owner.name?.let { layouts[it] }?.fieldFor(field)?.label) }

/**
 * An authored message that gives the field's label (issue #1918), kept with its failure: its [template], and what it
 * resolves against but for the label. The failure's `userMessage` is it as the check that found the failure names the
 * field; a page that shows the failure under the field words it again with its own heading.
 */
data class SchWording(val template: String, val context: Map<String, Any?>) {
    /** The message with the field called [label]; the template as written when it cannot be resolved. */
    fun text(label: String): String =
        template.analyzeTemplate(evaluateWith = context + (LayoutErrCtx.label to label)).value ?: template
}
