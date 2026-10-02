package com.dynamicruntime.webapp

import com.dynamicruntime.common.schema.SCT
import com.dynamicruntime.common.schema.SchOption
import com.dynamicruntime.common.schema.SchProperty
import com.dynamicruntime.common.schema.SchType
import com.dynamicruntime.common.schema.SchVariants
import react.ChildrenBuilder
import react.FC
import react.Props
import react.dom.html.ReactHTML.div
import react.dom.html.ReactHTML.input
import react.dom.html.ReactHTML.p
import react.dom.html.ReactHTML.span
import web.cssom.ClassName

// --- output-schema outline --------------------------------------------------------------------------------

/**
 * A read-only structural view of a [SchType] — the shape, not any data. Each field shows its name (with a
 * required marker) and type in words; an object expands its fields, an array of objects expands its element
 * type, and a choice field lists its options. Used for the endpoint page's output-schema view (the input side
 * is the interactive form). A self-referential type renders a collapsed marker rather than expanding forever.
 */
external interface SchemaOutlineProps : Props {
    var type: SchType
}

val SchemaOutline = FC<SchemaOutlineProps> { props ->
    div {
        className = ClassName("schema-form")
        outlineObject(props.type, emptySet())
    }
}

private fun ChildrenBuilder.outlineObject(type: SchType, seen: Set<String>) {
    type.variants?.let { variants ->
        outlineVariant(variants, seen)
        return
    }
    if (type.properties.isEmpty()) {
        p {
            className = ClassName("type-hint")
            +"(no fields)"
        }
        return
    }
    type.properties.forEach { (name, prop) -> outlineField(name, prop, name in type.required, seen) }
}

/**
 * A union in the structural view: which property chooses, then every branch expanded under the value that
 * selects it.
 *
 * All of them, not just one — this surface documents the wire, and "what can come back" is the whole answer
 * for a union. The interactive form shows one branch because someone is filling in one entry; a reader here is
 * asking what the shape can be.
 */
private fun ChildrenBuilder.outlineVariant(variants: SchVariants, seen: Set<String>) {
    div {
        className = ClassName("row")
        labelSpan(variants.discriminator, required = true)
        span {
            className = ClassName("field-type")
            +"(chooses the shape)"
        }
    }
    variants.byValue.forEach { (value, branch) ->
        div {
            className = ClassName("row")
            span {
                className = ClassName("field-label")
                +"${variants.discriminator} = $value"
            }
        }
        div {
            className = ClassName("nested")
            // The branch's own discriminator is skipped: its value is the heading above, and repeating it as a
            // field with one permitted value says nothing the reader does not already have.
            branch.properties.forEach { (name, prop) ->
                if (name != variants.discriminator) {
                    outlineField(name, prop, name in branch.required, seen)
                }
            }
        }
    }
    variants.defaultBranch?.let {
        p {
            className = ClassName("type-hint")
            +"Any other ${variants.discriminator} is carried through unchanged."
        }
    }
}

private fun ChildrenBuilder.outlineField(name: String, prop: SchProperty, required: Boolean, seen: Set<String>) {
    val vt = prop.valueType
    div {
        className = ClassName("row")
        labelSpan(name, required)
        span {
            className = ClassName("field-type")
            +"(${typeWord(vt)})"
        }
    }
    prop.description?.let { desc(it) }
    boundHint(vt)

    // Expand structure: an object's fields, an array-of-object's element fields, or a choice field's options.
    val element = if (vt.jsonType == SCT.array) vt.itemType else null
    when {
        // `isStructuredObject` rather than a properties check, so a union expands here too: its fields live on
        // its branches, so it has none of its own and would otherwise render as a bare "(object)".
        isStructuredObject(vt) -> outlineNested(vt, seen)
        element != null && isStructuredObject(element) -> outlineNested(element, seen)
        vt.options != null -> optionList(vt.options!!)
        element?.options != null -> optionList(element.options!!)
    }
}

/** Renders a nested object's structure indented, guarding against a self-/mutually-referential type. */
private fun ChildrenBuilder.outlineNested(type: SchType, seen: Set<String>) {
    val typeName = type.name
    if (typeName != null && typeName in seen) {
        p {
            className = ClassName("type-hint")
            +"↻ $typeName (recursive)"
        }
        return
    }
    div {
        className = ClassName("nested")
        outlineObject(type, if (typeName != null) seen + typeName else seen)
    }
}

private fun ChildrenBuilder.optionList(options: List<SchOption>) {
    p {
        className = ClassName("type-hint")
        +"one of: ${options.joinToString(", ") { it.value }}"
    }
}
