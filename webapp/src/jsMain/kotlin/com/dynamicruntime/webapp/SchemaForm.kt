package com.dynamicruntime.webapp

import react.ChildrenBuilder
import react.FC
import react.Props
import react.dom.html.ReactHTML.div
import react.dom.html.ReactHTML.p
import react.dom.html.ReactHTML.span
import web.cssom.ClassName

/**
 * Read-only renderer (Phase 2) for a set of [FieldSpec]s — the generic display engine's display half. Each
 * field draws its label (with a required marker), description, and a disabled widget showing its value; a
 * nested map ([ObjectField]) recurses into an indented sub-form, and a cyclic ref renders as a collapsed
 * marker rather than expanding forever. Every widget here is the read-only face of the one Phase 3 will make
 * editable, so the two views share one dispatch and cannot drift.
 */
external interface SchemaFormProps : Props {
    /** The fields to render, from `toFields(objectSchema, defs)`. */
    var fields: List<FieldSpec>
    /** Current values by field name (empty when displaying a bare input schema). */
    var values: Map<String, Any?>
}

val SchemaForm = FC<SchemaFormProps> { props ->
    div {
        className = ClassName("schema-form")
        if (props.fields.isEmpty()) {
            p {
                className = ClassName("type-hint")
                +"(no parameters)"
            }
        }
        props.fields.forEach { field ->
            renderField(field, props.values[field.name])
        }
    }
}

/** Renders one field: an object header + indented sub-form for nested maps, else a labeled widget row. */
private fun ChildrenBuilder.renderField(field: FieldSpec, value: Any?) {
    if (field is ObjectField) {
        div {
            className = ClassName("row")
            labelSpan(field)
        }
        field.description?.let { desc(it) }
        if (field.cyclic) {
            p {
                className = ClassName("type-hint")
                +"↻ ${field.typeName} (recursive)"
            }
        } else {
            div {
                className = ClassName("nested")
                SchemaForm {
                    fields = field.fields
                    values = value.asMap()
                }
            }
        }
        return
    }

    div {
        className = ClassName("row")
        labelSpan(field)
        widget(field, value)
    }
    field.description?.let { desc(it) }
}

/** The field name plus a red `*` when required. */
private fun ChildrenBuilder.labelSpan(field: FieldSpec) {
    span {
        className = ClassName("field-label")
        +field.name
        if (field.required) {
            span {
                className = ClassName("field-required")
                +" *"
            }
        }
    }
}

private fun ChildrenBuilder.desc(text: String) {
    p {
        className = ClassName("subtitle")
        +text
    }
}

/** The disabled read-only widget for a scalar / choice / array field. */
private fun ChildrenBuilder.widget(field: FieldSpec, value: Any?) {
    when (field) {
        is BoolField -> Checkbox {
            checked = value == true
            disabled = true
        }
        is OptionField -> Select {
            disabled = true
            mode = if (field.multi) "multiple" else null
            options = optionsToJs(field.options)
            this.value = if (field.multi) value.asList().toTypedArray() else value
            placeholder = "(choose)"
            style = js("({ minWidth: 180 })")
        }
        is ArrayField -> {
            val items = value.asList()
            span {
                className = ClassName("type-hint")
                +if (items.isEmpty()) "(list)" else items.joinToString(", ") { displayValue(it) }
            }
        }
        // StringField / NumberField / DateField / UnknownField: a disabled text box showing the value, hinting
        // the expected shape via the placeholder when empty.
        else -> Input {
            disabled = true
            this.value = displayValue(value)
            placeholder = typeHint(field)
        }
    }
}

/** A short label of the expected value shape, used as an empty widget's placeholder. */
private fun typeHint(field: FieldSpec): String = when (field) {
    is NumberField -> if (field.integer) SK.integer else SK.number
    is DateField -> if (field.withTime) SK.dateTime else SK.date
    is StringField -> SK.string
    else -> "value"
}

/** Renders a value for read-only display; null becomes empty. */
private fun displayValue(value: Any?): String = value?.toString() ?: ""

/** Converts [Opt]s to the `{ label, value }` JS objects antd's Select `options` prop expects. */
private fun optionsToJs(options: List<Opt>): Array<dynamic> = options.map { opt ->
    val obj: dynamic = js("({})")
    obj.label = opt.label
    obj.value = opt.value
    obj
}.toTypedArray()
