package com.dynamicruntime.webapp

import com.dynamicruntime.common.schema.SCT
import com.dynamicruntime.common.schema.SFMT
import com.dynamicruntime.common.schema.SchFailure
import com.dynamicruntime.common.schema.SchOption
import com.dynamicruntime.common.schema.SchProperty
import com.dynamicruntime.common.schema.SchType
import com.dynamicruntime.common.schema.byPath
import com.dynamicruntime.common.schema.childKeyOf
import com.dynamicruntime.common.schema.childPath
import com.dynamicruntime.common.schema.indexPath
import com.dynamicruntime.common.schema.isBinaryFormat
import com.dynamicruntime.common.schema.isDateFormat
import com.dynamicruntime.common.util.toJsonStr
import kotlinx.browser.document
import web.dom.ElementId
import org.w3c.dom.HTMLElement
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
    /**
     * Validation failures to show against the fields that caused them. Their paths are the ones the kernel
     * validator reported, and the form rebuilds the same paths as it walks — see [FieldErrors].
     */
    var failures: List<SchFailure>
    /** Called with the full new values map for this object whenever a field changes. */
    var onChange: (Map<String, Any?>) -> Unit
    /**
     * Called with the path of the field the user just edited, so its now-stale failures can be dropped. Fired
     * from the innermost thing touched, never from the parent updates that bubble up behind it — editing one
     * field must not clear a sibling's error.
     */
    var onFieldEdit: (String) -> Unit
}

/**
 * The per-field validation state a render pass carries: which messages to show under a field, and how to
 * report that a field was edited.
 *
 * It exists, so the two travel together through the traversal rather than as two more parameters on every
 * render function, and so the grouping is done once for the pass instead of rescanning the failure list per
 * field.
 */
class FieldErrors(private val all: List<SchFailure>, val noteEdit: (String) -> Unit) {
    private val byPath = all.byPath()

    /** The failures reported at exactly [path]; empty when the field is fine (or when nothing was validated). */
    fun messagesAt(path: String): List<SchFailure> = byPath[path] ?: emptyList()

    /**
     * Failures below [path] whose next key down is not one of [declared] — reported against a property this
     * object does not have, so no field of its own will ever be drawn for it. Grouped by their own path, so
     * each gets one addressable place to appear.
     */
    fun undeclaredBelow(path: String, declared: Set<String>): Map<String, List<SchFailure>> =
        all.filter { f -> childKeyOf(f.path, path)?.let { it !in declared } == true }.byPath()
}

/**
 * The DOM id of a field's row, and of the block holding its messages. Derived from the path so anything
 * holding a failure can find its field: the listing at the foot of the page, and Validate focusing the first
 * problem.
 *
 * Looked up with `getElementById`, never a CSS selector — a path contains `.` and `[`, which are selector
 * syntax and would need escaping, while `getElementById` takes the string literally. Paths carry no spaces,
 * so they are also valid as the `aria-describedby` IDREF that ties a control to its message.
 */
fun fieldRowId(path: String): String = "fld-$path"

fun fieldErrorsId(path: String): String = "err-$path"

/**
 * Brings the field at [path] into view and puts focus on it, or does nothing when no field is rendered there.
 *
 * Focus lands on the first control inside the row when there is one, and on the row itself otherwise — a
 * list header or an object header has no control of its own, and giving every row `tabIndex = -1` makes it
 * focusable programmatically without adding a tab stop for someone moving through the form by keyboard.
 */
fun focusField(path: String) {
    // The plain DOM `document` here, whose getElementById takes the id as a String; the `id` prop on the
    // React side is the wrappers' ElementId. Same string either way.
    val row = document.getElementById(fieldRowId(path)) ?: return
    row.asDynamic().scrollIntoView(js("({ block: 'center', behavior: 'smooth' })"))
    val control = row.querySelector("input, select, textarea, button") as? HTMLElement
    (control ?: row as? HTMLElement)?.focus()
}

val SchemaForm = FC<SchemaFormProps> { props ->
    val errors = FieldErrors(props.failures, props.onFieldEdit)
    div {
        className = ClassName("schema-form")
        // The root path is empty, which is what the validator starts from too, so `childPath` composes the
        // identical strings on both sides (see SchValidator's note on why these are shared).
        renderObject(props.type, props.values, emptySet(), props.editable, "", errors, props.onChange)
    }
}

/** Renders an object type's fields, threading the cycle-guard [seen] set (visited `$ref` type names). */
private fun ChildrenBuilder.renderObject(
    type: SchType,
    values: Map<String, Any?>,
    seen: Set<String>,
    editable: Boolean,
    path: String,
    errors: FieldErrors,
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
            name, prop, name in type.required, values[name], seen, editable, childPath(path, name), errors,
            // A removal has to drop the key, not null it: a null against an object/array type fails the plain
            // type check (they do not coerce), so "removed" would read as "present but wrong".
            emit = { newValue -> onChange(values + (name to newValue)) },
            omit = { onChange(values - name) },
        )
    }
    // Then anything reported against a key this object does not declare. Nothing above draws these -- there is
    // no property to render -- so without this they exist only in the listing at the foot of the page, which
    // is precisely the "named somewhere you cannot go" problem this issue is about.
    errors.undeclaredBelow(path, type.properties.keys).forEach { (at, messages) ->
        undeclaredField(childKeyOf(at, path) ?: at, at, messages)
    }
}

/**
 * A failure against a key the schema does not declare. It borrows the shape of a field row so it reads in
 * place, and carries the same id, so the listing can jump to it like any other — but it shows the **key**
 * rather than a label, since there is no declared property behind it to have a description or a type.
 */
private fun ChildrenBuilder.undeclaredField(key: String, path: String, messages: List<SchFailure>) {
    div {
        id = ElementId(fieldRowId(path))
        tabIndex = -1
        className = ClassName("row field-invalid")
        span {
            className = ClassName("field-label")
            +key
        }
        span {
            className = ClassName("field-type")
            +"(not in the schema)"
        }
    }
    fieldErrors(path, messages)
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
    path: String,
    errors: FieldErrors,
    emit: (Any?) -> Unit,
    omit: () -> Unit,
) {
    val vt = prop.valueType
    val elementType = objectElementType(vt)
    if (elementType != null) {
        renderObjectList(name, prop, required, value, elementType, seen, editable, path, errors, emit, omit)
        return
    }
    // A list of scalars, edited: one widget per element. Not the multi-select case (an item type with options
    // is a fixed set of choices, which the Select already emits as a real list), and not the read-only view,
    // where a comma-joined line reads better than a column of single values.
    if (editable && vt.jsonType == SCT.array && vt.itemType?.options == null) {
        renderScalarList(name, prop, required, value, vt.itemType, path, errors, emit, omit)
        return
    }
    if (isStructuredObject(vt)) {
        renderNestedObject(name, prop, required, value, vt, seen, editable, path, errors, emit, omit)
        return
    }

    val messages = errors.messagesAt(path)
    fieldFrame(name, prop, required, path, messages) {
        widget(vt, value, editable, messages.ifEmpty { null }?.let { fieldErrorsId(path) }) { newValue ->
            errors.noteEdit(path)
            emit(newValue)
        }
    }
}

/**
 * How every field presents *itself*, whatever it then goes on to contain: a row carrying the label and
 * whatever [rowContent] puts beside it, the field's description, and any failures reported against it.
 *
 * The four render paths differ in what they draw *after* this — a widget inline, an expanded sub-form, a
 * column of elements — but they must not differ in this, or a field's error appears in a different place
 * depending on the shape of its type. Having one frame is also what keeps the next addition to it (an
 * ancestor marker, an `aria-describedby` tying control to message) a single edit rather than four.
 */
private fun ChildrenBuilder.fieldFrame(
    name: String,
    prop: SchProperty,
    required: Boolean,
    path: String,
    messages: List<SchFailure>,
    rowContent: ChildrenBuilder.() -> Unit = {},
) {
    div {
        id = ElementId(fieldRowId(path))
        // Focusable on purpose but not in the tab order: something jumping here from the failure listing has
        // to be able to land on a row that carries no control of its own.
        tabIndex = -1
        className = ClassName(rowClass(messages))
        labelSpan(name, required)
        rowContent()
    }
    prop.description?.let { desc(it) }
    fieldErrors(path, messages)
}

/**
 * Marks a control as invalid and points it at the block holding its messages, so the state is announced and
 * the reason read out rather than only being visible as a colour. A no-op when [describedBy] is null, which
 * is how a field with nothing wrong says so.
 *
 * Set through `asDynamic` because these are plain HTML attributes that antd's components forward to the
 * control they wrap, and their Kotlin external interfaces do not declare them — the same route the add and
 * remove buttons already take for `aria-label`.
 *
 * That forwarding was checked rather than assumed, against a running instance: `Input`, `Select`,
 * `DatePicker` and `Checkbox` each land both attributes on their inner `<input>`, and the plain file input
 * takes them directly. A widget added later should be checked the same way — a silently dropped
 * `aria-describedby` looks identical to a working one.
 */
private fun markInvalid(props: dynamic, describedBy: String?) {
    if (describedBy == null) return
    props["aria-invalid"] = true
    props["aria-describedby"] = describedBy
}

/** The row's class, marked invalid when the field carries a failure, so the label and control can be styled. */
private fun rowClass(messages: List<SchFailure>): String = if (messages.isEmpty()) "row" else "row field-invalid"

/**
 * The failures for one field, printed beneath it. The path is deliberately *not* repeated here — the message
 * is already sitting on the field it is about, and the path's job (saying which field) is done by position.
 * The listing at the bottom of the page keeps the path, since that surface has no field to sit next to.
 */
private fun ChildrenBuilder.fieldErrors(path: String, messages: List<SchFailure>) {
    if (messages.isEmpty()) return
    // One block per field rather than loose paragraphs, so the control can point `aria-describedby` at all of
    // its messages with a single id.
    div {
        id = ElementId(fieldErrorsId(path))
        messages.forEach { f ->
            p {
                className = ClassName("field-error")
                +"${f.message}${choicesSuffix(f)}"
            }
        }
    }
}

/**
 * The "Valid options: …" sentence listing an invalid-option failure's choices; empty for every other failure.
 * Its own sentence rather than a trailing parenthetical, because the message it follows now ends in a period
 * and a lowercase "(valid: …)" stranded after one reads as an afterthought.
 *
 * Each choice shows its **value**, which is what actually goes in the payload — this surface documents the
 * wire, the same reason the endpoint form labels fields with their key rather than a `title`. The label is
 * appended only when it says something the value does not, so a labeled choice list is not reduced to bare
 * codes, and an unlabeled one is not padded with a repeat of itself.
 */
fun choicesSuffix(f: SchFailure): String {
    val opts = f.options ?: return ""
    return " Valid options: ${opts.joinToString(", ") { if (it.label == it.value) it.value else "${it.value} — ${it.label}" }}."
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
    path: String,
    errors: FieldErrors,
    emit: (Any?) -> Unit,
    omit: () -> Unit,
) {
    val typeName = vt.name
    val recursive = typeName != null && typeName in seen
    val present = value is Map<*, *>
    val dataDriven = recursive || !required
    val messages = errors.messagesAt(path)

    fieldFrame(name, prop, required, path, messages) {
        if (dataDriven && editable) {
            // Adding or removing the whole branch invalidates anything reported inside it, which is why the
            // edit is noted against this field rather than against whatever it contained.
            if (present) {
                removeControl(name) { errors.noteEdit(path); omit() }
            } else {
                addControl(name) { errors.noteEdit(path); emit(emptyMap<String, Any?>()) }
            }
        }
    }
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
        renderObject(vt, value.toJsonMapOrEmpty(), childSeen, editable, path, errors) { newSub -> emit(newSub) }
    }
}

/**
 * A list-of-objects field: each element is its own indented sub-form under an "i" header (the index
 * convention the response renderer already uses), with the "add" control after the last one, where an "append"
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
    path: String,
    errors: FieldErrors,
    emit: (Any?) -> Unit,
    omit: () -> Unit,
) {
    val elements = value.toJsonListOrEmpty()
    val messages = errors.messagesAt(path)
    fieldFrame(name, prop, required, path, messages)

    val typeName = elementType.name
    val childSeen = if (typeName != null) seen + typeName else seen
    elements.forEachIndexed { i, element ->
        val elementPath = indexPath(path, i)
        // A failure against the element *itself* rather than one of its fields -- an element that is not an
        // object at all, say. Its fields each have a row; until now the element did not, so this was the one
        // failure inside a list with nowhere of its own to appear.
        val elementMessages = errors.messagesAt(elementPath)
        div {
            className = ClassName("nested")
            div {
                id = ElementId(fieldRowId(elementPath))
                tabIndex = -1
                className = ClassName(rowClass(elementMessages))
                span {
                    className = ClassName("type-hint")
                    +"[$i]"
                }
                if (editable) {
                    removeControl("$name $i") {
                        // Noted against the list, not the element: removing re-indexes everything after it, so
                        // a failure held against element 2 would end up pointing at what used to be element 3.
                        errors.noteEdit(path)
                        val rest = elements.filterIndexed { j, _ -> j != i }
                        if (rest.isEmpty() && !required) omit() else emit(rest)
                    }
                }
            }
            fieldErrors(elementPath, elementMessages)
            renderObject(elementType, element.toJsonMapOrEmpty(), childSeen, editable, elementPath, errors) { newElement ->
                emit(elements.mapIndexed { j, old -> if (j == i) newElement else old })
            }
        }
    }
    if (editable) {
        div {
            className = ClassName("row")
            addControl(name) {
                errors.noteEdit(path)
                emit(elements + listOf<Any?>(mapOf<String, Any?>()))
            }
        }
    } else if (elements.isEmpty()) {
        p {
            className = ClassName("type-hint")
            +"(none)"
        }
    }
}

/**
 * A list of scalars — a growing column of single-value widgets, each with its own remove and an "add" at the
 * end. It emits a real list, which is what an array field's type actually requires.
 *
 * The single comma-separated text box this replaces emitted a *String*, and an array type does not coerce from
 * one (`allowCoerce` defaults on only for numeric and date formats), so every entry failed validation as a
 * plain type mismatch — the field could be described but never filled in.
 */
private fun ChildrenBuilder.renderScalarList(
    name: String,
    prop: SchProperty,
    required: Boolean,
    value: Any?,
    elementType: SchType?,
    path: String,
    errors: FieldErrors,
    emit: (Any?) -> Unit,
    omit: () -> Unit,
) {
    val elements = value.toJsonListOrEmpty()
    val messages = errors.messagesAt(path)
    fieldFrame(name, prop, required, path, messages)

    elements.forEachIndexed { i, element ->
        val elementPath = indexPath(path, i)
        val elementMessages = errors.messagesAt(elementPath)
        fun replace(newValue: Any?) {
            errors.noteEdit(elementPath)
            emit(elements.mapIndexed { j, old -> if (j == i) newValue else old })
        }
        div {
            id = ElementId(fieldRowId(elementPath))
            tabIndex = -1
            className = ClassName("${rowClass(elementMessages)} nested")
            // An untyped array declares nothing about its elements, so there is no widget to dispatch on:
            // a plain text box, whose value the validator leaves alone for want of an item type.
            if (elementType != null) widget(elementType, element, true) { replace(it) } else Input {
                this.value = displayValue(element)
                placeholder = "value"
                onChange = { e -> replace(e.target.value as String) }
            }
            removeControl("$name $i") {
                // Against the list, since removing re-indexes what follows (see renderObjectList).
                errors.noteEdit(path)
                val rest = elements.filterIndexed { j, _ -> j != i }
                if (rest.isEmpty() && !required) omit() else emit(rest)
            }
        }
        fieldErrors(elementPath, elementMessages)
    }
    div {
        className = ClassName("row")
        addControl(name) {
            errors.noteEdit(path)
            emit(elements + listOf<Any?>(""))
        }
    }
}

/**
 * The "add" affordance. It carries only the verb: it sits inside the block of the field it adds to, so position
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
private fun ChildrenBuilder.widget(
    vt: SchType, value: Any?, editable: Boolean, describedBy: String? = null, emit: (Any?) -> Unit,
) {
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
            markInvalid(asDynamic(), describedBy)
        }
        // Single choice.
        singleOptions != null -> Select {
            options = optionsToJs(singleOptions)
            this.value = value?.toString()
            placeholder = "(choose)"
            allowClear = true
            style = js("({ minWidth: 200 })")
            onChange = { v -> emit(v as? String) }
            markInvalid(asDynamic(), describedBy)
        }
        vt.jsonType == SCT.boolean -> Checkbox {
            checked = value == true
            onChange = { e -> emit(e.target.checked as Boolean) }
            markInvalid(asDynamic(), describedBy)
        }
        // Date field. Bound like every other widget, which it previously was not: with no `value`, antd's
        // picker is uncontrolled, so a date the form already held -- from a restored link, or a payload loaded
        // through the request-JSON panel -- never appeared in the field. (It also left the browser free to
        // repopulate the input on reload from its own form memory, showing a date the form did not have.)
        //
        // The conversions are antd's terms, not ours: it speaks Dayjs where the schema says string.
        vt.jsonType == SCT.string && isDateFormat(vt.format) -> {
            val dayOnly = vt.format == SFMT.date
            DatePicker {
                this.value = value?.toString()?.takeIf { it.isNotBlank() }?.let { dayjs(it) }?.takeIf { it.isValid() }
                // A `date-time` field needs the time picked too. Without this the widget can only return a
                // day, so binding a full timestamp and then touching the field would quietly drop its time.
                showTime = !dayOnly
                onChange = { date, dateString ->
                    // A day emits the day text; a moment emits ISO-8601 in UTC, which is exactly the shape the
                    // kernel parses and writes back. Cleared emits null, which reads as absent (issue #187).
                    emit(if (date == null) null else if (dayOnly) dateString else date.toISOString())
                }
                markInvalid(asDynamic(), describedBy)
            }
        }
        // File content (OpenAPI's `type: string, format: binary`): a file picker. What it emits is the
        // browser's own File object, not text -- which is exactly why the kernel validator leaves a binary
        // field's value alone rather than coercing it, and why SchemaCatalogApi sends this endpoint as
        // multipart/form-data rather than JSON. A plain <input type="file"> rather than antd's Upload: that
        // component wants to own the upload itself, which is the runtime's job here.
        vt.jsonType == SCT.string && isBinaryFormat(vt.format) -> input {
            // `type` is web.html.InputType, an external value over the HTML attribute string; "file" is that
            // attribute's value, cast rather than spelled through the wrapper's own constant, so this does not
            // ride on which of them the current kotlin-wrappers exposes.
            type = "file".unsafeCast<InputType>()
            onChange = { e ->
                val files = e.target.asDynamic().files
                emit(if (files != null && (files.length as Int) > 0) files[0] else null)
            }
            markInvalid(asDynamic(), describedBy)
        }
        // string / integer / number / unknown: a text box. The kernel validator coerces the entered string to
        // the declared type on validation. Lists do not arrive here in edit mode -- a list of choices is the
        // multi-select above, and any other list is a growing column of these widgets (renderScalarList).
        else -> Input {
            this.value = displayValue(value)
            placeholder = typeHint(vt)
            onChange = { e -> emit(e.target.value as String) }
            markInvalid(asDynamic(), describedBy)
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
