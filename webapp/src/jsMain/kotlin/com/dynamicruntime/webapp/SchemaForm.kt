package com.dynamicruntime.webapp

import com.dynamicruntime.common.schema.SCT
import com.dynamicruntime.common.schema.SchOption
import com.dynamicruntime.common.schema.SchProperty
import com.dynamicruntime.common.schema.SchType
import com.dynamicruntime.common.schema.isDateFormat
import com.dynamicruntime.common.util.toJsonMap
import com.dynamicruntime.common.util.toJsonStr
import react.ChildrenBuilder
import react.FC
import react.Props
import react.dom.html.ReactHTML.div
import react.dom.html.ReactHTML.p
import react.dom.html.ReactHTML.pre
import react.dom.html.ReactHTML.span
import web.cssom.ClassName

/**
 * Renders a kernel [SchType] as a form — the generic display engine. It dispatches each field to a widget by
 * the field's parsed schema (`jsonType` / `format` / `options` / `itemType` / nested `properties`), marking
 * required fields from the parent type's `required` set. A nested object recurses into an indented sub-form;
 * a self-referential type ([SchType.name] already seen on the path) renders a collapsed marker instead of
 * expanding forever.
 *
 * One dispatch serves both faces: with [editable] false, widgets render disabled (the read-only view); with
 * [editable] true, they call back through [onChange], which threads an immutable value update up to the top.
 * The kernel validator ([EndpointCatalog]) checks the assembled values with the exact backend logic.
 */
external interface SchemaFormProps : Props {
    /** The object type whose properties to render. */
    var type: SchType
    /** Current values by field name (a nested Kotlin Map/List tree). */
    var values: Map<String, Any?>
    /** When true, widgets are editable and call [onChange]; when false, they render disabled. */
    var editable: Boolean
    /** Called with the full new values map for this object whenever a field changes. */
    var onChange: (Map<String, Any?>) -> Unit
}

val SchemaForm = FC<SchemaFormProps> { props ->
    div {
        className = ClassName("schema-form")
        renderObject(props.type, props.values, emptySet(), props.editable, props.onChange)
    }
}

/** Renders an object type's fields, threading the cycle-guard [seen] set (visited `$ref` type names). */
private fun ChildrenBuilder.renderObject(
    type: SchType,
    values: Map<String, Any?>,
    seen: Set<String>,
    editable: Boolean,
    onChange: (Map<String, Any?>) -> Unit,
) {
    if (type.properties.isEmpty()) {
        p {
            className = ClassName("type-hint")
            +"(no parameters)"
        }
        return
    }
    type.properties.forEach { (name, prop) ->
        renderField(name, prop, name in type.required, values[name], seen, editable) { newValue ->
            onChange(values + (name to newValue))
        }
    }
}

/** Renders one field: a nested sub-form for object fields, else a labeled widget row. [emit] reports this
 *  field's new value up to its parent object. */
private fun ChildrenBuilder.renderField(
    name: String,
    prop: SchProperty,
    required: Boolean,
    value: Any?,
    seen: Set<String>,
    editable: Boolean,
    emit: (Any?) -> Unit,
) {
    val vt = prop.valueType
    if (vt.jsonType == SCT.kObject && vt.properties.isNotEmpty()) {
        div {
            className = ClassName("row")
            labelSpan(name, required)
        }
        prop.description?.let { desc(it) }
        val typeName = vt.name
        if (typeName != null && typeName in seen) {
            p {
                className = ClassName("type-hint")
                +"↻ $typeName (recursive)"
            }
        } else {
            div {
                className = ClassName("nested")
                val childSeen = if (typeName != null) seen + typeName else seen
                renderObject(vt, value.asKMap(), childSeen, editable) { newSub -> emit(newSub) }
            }
        }
        return
    }

    div {
        className = ClassName("row")
        labelSpan(name, required)
        widget(vt, value, editable, emit)
    }
    prop.description?.let { desc(it) }
}

/**
 * A field's value cell. In read-only mode ([editable] false — the response view and the read-only input view)
 * it is plain text: the value, annotated with the field's type in words, with no form control. In edit mode it
 * is the control appropriate to the field's kind, reporting changes through [emit].
 */
private fun ChildrenBuilder.widget(vt: SchType, value: Any?, editable: Boolean, emit: (Any?) -> Unit) {
    if (!editable) {
        readOnlyValue(vt, value)
        return
    }
    val arrayOptions = if (vt.jsonType == SCT.array) vt.itemType?.options else null
    val singleOptions = vt.options
    when {
        // Multi-select: an array of choices.
        arrayOptions != null -> Select {
            mode = "multiple"
            options = optionsToJs(arrayOptions)
            this.value = value.asKList().map { it.toString() }.toTypedArray()
            placeholder = "(choose)"
            style = js("({ minWidth: 200 })")
            onChange = { v -> emit(jsToList(v)) }
        }
        // Single choice.
        singleOptions != null -> Select {
            options = optionsToJs(singleOptions)
            this.value = value?.toString()
            placeholder = "(choose)"
            allowClear = true
            style = js("({ minWidth: 200 })")
            onChange = { v -> emit(v as? String) }
        }
        vt.jsonType == SCT.boolean -> Checkbox {
            checked = value == true
            onChange = { e -> emit(e.target.checked as Boolean) }
        }
        // Date string field: a DatePicker (antd hands back the formatted string).
        vt.jsonType == SCT.string && isDateFormat(vt.format) -> DatePicker {
            onChange = { _, dateString -> emit(dateString) }
        }
        // string / integer / number / non-choice array / unknown: a text box. The kernel validator coerces
        // the entered string to the declared type (and splits a comma list into an array) on validation.
        else -> Input {
            this.value = displayValue(value)
            placeholder = typeHint(vt)
            onChange = { e -> emit(e.target.value as String) }
        }
    }
}

/**
 * Read-only presentation of a field: its value as text (nothing when absent) followed by the field's type
 * named in words. No form control — this is a value being shown, not an input.
 */
private fun ChildrenBuilder.readOnlyValue(vt: SchType, value: Any?) {
    // A JSON structure (a generic object, or an array with structured elements) reads far better as pretty
    // JSON than a flattened toString; the kernel's JsonUtil formats it (indented, non-compact by default).
    if (value is Map<*, *> || (value is List<*> && value.any { it is Map<*, *> || it is List<*> })) {
        pre {
            className = ClassName("code json-value")
            +value.toJsonStr()
        }
        return
    }
    val text = displayValue(value)
    if (text.isNotEmpty()) {
        span {
            className = ClassName("field-value")
            +text
        }
    }
    span {
        className = ClassName("field-type")
        +"(${typeWord(vt)})"
    }
}

/** The field's type named in words, e.g. "string", "boolean", "date", "choice", "list". */
private fun typeWord(vt: SchType): String = when {
    vt.options != null -> "choice"
    vt.jsonType == SCT.array && vt.itemType?.options != null -> "choices"
    vt.jsonType == SCT.array -> "list"
    vt.jsonType == SCT.string && isDateFormat(vt.format) -> vt.format ?: SCT.string
    vt.jsonType == SCT.boolean -> "boolean"
    vt.jsonType == SCT.integer -> "integer"
    vt.jsonType == SCT.number -> "number"
    vt.jsonType == SCT.string -> "string"
    else -> vt.jsonType ?: "value"
}

/** The field name plus a red `*` when required. */
private fun ChildrenBuilder.labelSpan(name: String, required: Boolean) {
    span {
        className = ClassName("field-label")
        +name
        if (required) {
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

/** A short label of the expected value shape, used as an empty text widget's placeholder. */
private fun typeHint(vt: SchType): String = when (vt.jsonType) {
    SCT.string if isDateFormat(vt.format) -> vt.format ?: SCT.string
    SCT.array -> "list (comma-separated)"
    else -> vt.jsonType ?: "value"
}

/** Renders a value for display; null becomes empty. A list is shown comma-joined. */
private fun displayValue(value: Any?): String = when (value) {
    null -> ""
    is List<*> -> value.joinToString(", ") { it?.toString() ?: "" }
    else -> value.toString()
}

/** Converts [SchOption]s to the `{ label, value }` JS objects antd's Select `options` prop expects. */
private fun optionsToJs(options: List<SchOption>): Array<dynamic> = options.map { opt ->
    val obj: dynamic = js("({})")
    obj.label = opt.label
    obj.value = opt.value
    obj
}.toTypedArray()

/** Converts a JS array (antd multi-select value) into a Kotlin list the kernel validator accepts. */
private fun jsToList(v: dynamic): List<Any?> {
    if (v == null) return emptyList()
    val n = v.length as? Int ?: return emptyList()
    val out = ArrayList<Any?>(n)
    for (i in 0 until n) out.add(v[i])
    return out
}

/** Null-tolerant view of a value-tree node as a `Map`, via the kernel's `toJsonMap` coercion. */
private fun Any?.asKMap(): Map<String, Any?> = if (this is Map<*, *>) toJsonMap() else emptyMap()

/** Null-tolerant view of a value-tree node as a `List`. */
private fun Any?.asKList(): List<Any?> = this as? List<*> ?: emptyList()

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
    if (type.properties.isEmpty()) {
        p {
            className = ClassName("type-hint")
            +"(no fields)"
        }
        return
    }
    type.properties.forEach { (name, prop) -> outlineField(name, prop, name in type.required, seen) }
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

    // Expand structure: an object's fields, an array-of-object's element fields, or a choice field's options.
    val element = if (vt.jsonType == SCT.array) vt.itemType else null
    when {
        vt.jsonType == SCT.kObject && vt.properties.isNotEmpty() -> outlineNested(vt, seen)
        element != null && element.jsonType == SCT.kObject && element.properties.isNotEmpty() -> outlineNested(element, seen)
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
