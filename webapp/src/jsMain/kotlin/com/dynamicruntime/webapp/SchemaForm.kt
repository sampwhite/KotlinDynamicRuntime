package com.dynamicruntime.webapp

import com.dynamicruntime.common.schema.SCT
import com.dynamicruntime.common.schema.SchOption
import com.dynamicruntime.common.schema.SchProperty
import com.dynamicruntime.common.schema.SchType
import com.dynamicruntime.common.schema.isBinaryFormat
import com.dynamicruntime.common.schema.isDateFormat
import com.dynamicruntime.common.util.toJsonStr
import react.ChildrenBuilder
import react.FC
import react.Props
import react.dom.html.ReactHTML.div
import react.dom.html.ReactHTML.input
import react.dom.html.ReactHTML.p
import react.dom.html.ReactHTML.pre
import react.dom.html.ReactHTML.span
import web.cssom.ClassName
import web.html.InputType
import com.dynamicruntime.common.util.toJsonMapOrEmpty
import com.dynamicruntime.common.util.toJsonListOfStrings
import com.dynamicruntime.common.util.toJsonListOrEmpty

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
        renderField(
            name, prop, name in type.required, values[name], seen, editable,
            // A removal has to drop the key, not null it: a null against an object/array type fails the plain
            // type check (they do not coerce), so "removed" would read as "present but wrong".
            emit = { newValue -> onChange(values + (name to newValue)) },
            omit = { onChange(values - name) },
        )
    }
}

/**
 * Renders one field, dispatching on its shape: a list of objects, a nested object, or a labeled widget row.
 * [emit] reports this field's new value up to its parent object; [omit] drops the field entirely.
 */
private fun ChildrenBuilder.renderField(
    name: String,
    prop: SchProperty,
    required: Boolean,
    value: Any?,
    seen: Set<String>,
    editable: Boolean,
    emit: (Any?) -> Unit,
    omit: () -> Unit,
) {
    val vt = prop.valueType
    val elementType = objectElementType(vt)
    if (elementType != null) {
        renderObjectList(name, prop, required, value, elementType, seen, editable, emit, omit)
        return
    }
    if (isStructuredObject(vt)) {
        renderNestedObject(name, prop, required, value, vt, seen, editable, emit, omit)
        return
    }

    div {
        className = ClassName("row")
        labelSpan(name, required)
        widget(vt, value, editable, emit)
    }
    prop.description?.let { desc(it) }
}

/** An object type with declared fields, as opposed to a free-form object. */
private fun isStructuredObject(vt: SchType): Boolean = vt.jsonType == SCT.kObject && vt.properties.isNotEmpty()

/** The element type when [vt] is a list *of objects*, else null (a list of scalars stays a text widget). */
private fun objectElementType(vt: SchType): SchType? =
    if (vt.jsonType == SCT.array) vt.itemType?.takeIf { isStructuredObject(it) } else null

/**
 * A nested object field. Expansion follows the **data**, not the schema, once the field is optional or its type
 * has already been seen on this path — which is what lets a self-referential type (`TreeNode.parent`) be built
 * up one level at a time instead of showing a dead recursion marker, and what terminates it: a branch exists
 * only because someone added it.
 *
 * A *required* object still expands unmodified — it has to be filled in either way, so hiding it behind a
 * click would be friction with no gain.
 */
private fun ChildrenBuilder.renderNestedObject(
    name: String,
    prop: SchProperty,
    required: Boolean,
    value: Any?,
    vt: SchType,
    seen: Set<String>,
    editable: Boolean,
    emit: (Any?) -> Unit,
    omit: () -> Unit,
) {
    val typeName = vt.name
    val recursive = typeName != null && typeName in seen
    val present = value is Map<*, *>
    val dataDriven = recursive || !required

    div {
        className = ClassName("row")
        labelSpan(name, required)
        if (dataDriven && editable) {
            if (present) removeControl(name, omit) else addControl(name) { emit(emptyMap<String, Any?>()) }
        }
    }
    prop.description?.let { desc(it) }
    if (dataDriven && !present) {
        // Nothing there: awaiting an Add, or simply absent. Expanding it anyway would show a structure the
        // data does not have -- empty lat/lon for a contact with no location -- which reads as present-but-blank.
        if (!editable) {
            p {
                className = ClassName("type-hint")
                +"(none)"
            }
        }
        return
    }

    div {
        className = ClassName("nested")
        val childSeen = if (typeName != null) seen + typeName else seen
        renderObject(vt, value.toJsonMapOrEmpty(), childSeen, editable) { newSub -> emit(newSub) }
    }
}

/**
 * A list-of-objects field: each element is its own indented sub-form under an `[i]` header (the index
 * convention the response renderer already uses), with the add control after the last one, where an append
 * belongs. Removing the final element drops the whole field unless it is required, so an optional list does
 * not linger as an empty array in the payload.
 */
private fun ChildrenBuilder.renderObjectList(
    name: String,
    prop: SchProperty,
    required: Boolean,
    value: Any?,
    elementType: SchType,
    seen: Set<String>,
    editable: Boolean,
    emit: (Any?) -> Unit,
    omit: () -> Unit,
) {
    val elements = value.toJsonListOrEmpty()
    div {
        className = ClassName("row")
        labelSpan(name, required)
    }
    prop.description?.let { desc(it) }

    val typeName = elementType.name
    val childSeen = if (typeName != null) seen + typeName else seen
    elements.forEachIndexed { i, element ->
        div {
            className = ClassName("nested")
            div {
                className = ClassName("row")
                span {
                    className = ClassName("type-hint")
                    +"[$i]"
                }
                if (editable) {
                    removeControl("$name $i") {
                        val rest = elements.filterIndexed { j, _ -> j != i }
                        if (rest.isEmpty() && !required) omit() else emit(rest)
                    }
                }
            }
            renderObject(elementType, element.toJsonMapOrEmpty(), childSeen, editable) { newElement ->
                emit(elements.mapIndexed { j, old -> if (j == i) newElement else old })
            }
        }
    }
    if (editable) {
        div {
            className = ClassName("row")
            addControl(name) { emit(elements + listOf<Any?>(mapOf<String, Any?>())) }
        }
    } else if (elements.isEmpty()) {
        p {
            className = ClassName("type-hint")
            +"(none)"
        }
    }
}

/**
 * The add affordance. It carries only the verb: it sits inside the block of the field it adds to, so position
 * supplies the noun — which also keeps the engine generic, since deriving a singular noun from a field name
 * ("contacts" -> "contact") is a guess that eventually produces nonsense. [what] names the field for assistive
 * technology, where that surrounding context is not available.
 */
private fun ChildrenBuilder.addControl(what: String, onAdd: () -> Unit) {
    Button {
        type = "link"
        size = "small"
        onClick = onAdd
        asDynamic()["aria-label"] = "Add $what"
        +"+ Add"
    }
}

private fun ChildrenBuilder.removeControl(what: String, onRemove: () -> Unit) {
    Button {
        type = "link"
        size = "small"
        danger = true
        onClick = onRemove
        asDynamic()["aria-label"] = "Remove $what"
        +"✕"
    }
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
            this.value = value.toJsonListOfStrings().toTypedArray()
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
        // File content (OpenAPI's `type: string, format: binary`): a file picker. What it emits is the
        // browser's own File object, not text -- which is exactly why the kernel validator leaves a binary
        // field's value alone rather than coercing it, and why SchemaCatalogApi sends this endpoint as
        // multipart/form-data rather than JSON. A plain <input type="file"> rather than antd's Upload: that
        // component wants to own the upload itself, which is the runtime's job here.
        vt.jsonType == SCT.string && isBinaryFormat(vt.format) -> input {
            // `type` is web.html.InputType, an external value over the HTML attribute string; "file" is that
            // attribute's value, cast rather than spelled through the wrapper's own constant so this does not
            // ride on which of them the current kotlin-wrappers exposes.
            type = "file".unsafeCast<InputType>()
            onChange = { e ->
                val files = e.target.asDynamic().files
                emit(if (files != null && (files.length as Int) > 0) files[0] else null)
            }
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
    vt.jsonType == SCT.string && isBinaryFormat(vt.format) -> "file"
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
    SCT.string if isBinaryFormat(vt.format) -> "file"
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
