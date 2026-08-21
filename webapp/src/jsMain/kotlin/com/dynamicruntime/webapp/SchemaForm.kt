package com.dynamicruntime.webapp

import com.dynamicruntime.common.schema.SCT
import com.dynamicruntime.common.schema.SFMT
import com.dynamicruntime.common.schema.SchFailure
import com.dynamicruntime.common.schema.SchOption
import com.dynamicruntime.common.schema.SchProperty
import com.dynamicruntime.common.schema.SchType
import com.dynamicruntime.common.schema.SchVariants
import com.dynamicruntime.common.schema.byPath
import com.dynamicruntime.common.schema.childKeyOf
import com.dynamicruntime.common.schema.childPath
import com.dynamicruntime.common.schema.indexPath
import com.dynamicruntime.common.schema.isBinaryFormat
import com.dynamicruntime.common.schema.isDateFormat
import com.dynamicruntime.common.util.fmtD
import com.dynamicruntime.common.util.toJsonStr
import com.dynamicruntime.common.util.toOptBool
import com.dynamicruntime.common.util.toOptStr
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
import react.dom.html.ReactHTML.textarea
import react.useState
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
/**
 * How the form renders, beyond the schema itself (issue #408): whether it is a **friendly** data-entry form or
 * the wire-documenting catalog view, and any root fields to omit. The defaults reproduce the catalog's
 * behavior exactly, so a caller that passes nothing is unchanged.
 */
class FormOpts(
    /**
     * Friendly mode: label a field with its schema `title`, or a humanized key when it declares none, and
     * **hide** derived fields entirely rather than showing them read-only. The catalog wants the opposite --
     * it documents the wire, so it keeps the key and shows the derived field it is documenting.
     */
    val friendly: Boolean = false,
    /**
     * Root-level field names to leave out of the form entirely -- an advanced flag an end-user form should not
     * offer. Applies only at the top level, so a same-named field deeper in the tree is untouched.
     */
    val omit: Set<String> = emptySet(),
)

/**
 * A short, readable label from a wire key: `expenseReport` -> `Expense report`, `perItemAmount` -> `Per item
 * amount`. A space goes in before each capital and the result reads as one sentence-cased phrase.
 *
 * The fallback when a field declares no `title`. Deliberately simple -- a nicety over the key, not a
 * translation -- so a run of capitals (an acronym) spaces out oddly; a field that cares declares a title.
 * Pure, and covered under `jsNodeTest`.
 */
fun humanizeFieldName(name: String): String {
    if (name.isEmpty()) return name
    val sb = StringBuilder()
    name.forEachIndexed { i, c ->
        if (i > 0 && c.isUpperCase()) sb.append(' ')
        sb.append(c.lowercaseChar())
    }
    return sb.toString().replaceFirstChar { it.uppercaseChar() }
}

/**
 * The label a field shows: its schema title (or a humanized key) in friendly mode, else the raw wire key. The
 * title is the **property's**, never its value type's -- a `$ref` field's value type is shared, so a title from
 * there would label every field referencing that type alike (see [SchProperty.title]).
 */
private fun fieldLabel(name: String, prop: SchProperty, opts: FormOpts): String =
    if (opts.friendly) prop.title ?: humanizeFieldName(name) else name

external interface SchemaFormProps : Props {
    /** The object type whose properties to render. */
    var type: SchType
    /** Current values by field name (a nested Kotlin Map/List tree). */
    var values: Map<String, Any?>
    /** When true, widgets are editable and call [onChange]; when false, they render disabled. */
    var editable: Boolean
    /**
     * Friendly data-entry presentation (issue #408): fields labeled by title/humanized key, derived system
     * fields hidden. Optional and read as false when absent -- the catalog does not set it and is unchanged.
     */
    var friendly: Boolean?
    /** Root-level field names to omit from the form (see [FormOpts.omit]); absent means omit nothing. */
    var omit: List<String>?
    /**
     * Validation failures to show against the fields that caused them. Their paths are the ones the kernel
     * validator reported, and the form rebuilds the same paths as it walks — see [FieldErrors].
     *
     * Optional, and genuinely so: a form rendering a *response* has nothing to report against. Declaring it
     * non-null would not make it safe either -- an external interface is erased on the JS side, so an omitted
     * prop arrives as `undefined` no matter what the Kotlin type says, and the first call on it throws.
     */
    var failures: List<SchFailure>?
    /** Called with the full new values map for this object whenever a field changes. */
    var onChange: (Map<String, Any?>) -> Unit
    /**
     * Called with the path of the field the user just edited, so its now-stale failures can be dropped. Fired
     * from the innermost thing touched, never from the parent updates that bubble up behind it — editing one
     * field must not clear a sibling's error. Optional for the same reason as [failures]: a read-only form
     * has no edits to report.
     */
    var onFieldEdit: ((String) -> Unit)?
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
    val errors = FieldErrors(props.failures ?: emptyList(), props.onFieldEdit ?: {})
    // An external-interface Boolean arrives as `undefined` when a caller omits it, so read it as `== true`
    // rather than trusting the declared type; the catalog omits both and gets the plain wire view.
    val opts = FormOpts(friendly = props.friendly == true, omit = props.omit?.toSet() ?: emptySet())
    div {
        className = ClassName("schema-form")
        // The root path is empty, which is what the validator starts from too, so `childPath` composes the
        // identical strings on both sides (see SchValidator's note on why these are shared).
        renderObject(props.type, props.values, emptySet(), props.editable, "", errors, props.onChange, opts)
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
    opts: FormOpts,
) {
    type.variants?.let { variants ->
        renderVariant(type, variants, values, seen, editable, path, errors, onChange, opts)
        return
    }
    if (type.properties.isEmpty()) {
        p {
            className = ClassName("type-hint")
            +"(no parameters)"
        }
        return
    }
    renderProperties(type, values, seen, editable, path, errors, onChange, opts)
    // Then anything reported against a key this object does not declare. Nothing above draws these -- there is
    // no property to render -- so without this they exist only in the listing at the foot of the page, which
    // is precisely the "named somewhere you cannot go" problem this issue is about.
    errors.undeclaredBelow(path, type.properties.keys).forEach { (at, messages) ->
        undeclaredField(childKeyOf(at, path) ?: at, at, messages)
    }
}

/**
 * Renders a type's declared properties, applying its conditional-presence rule if it has one (issue #253).
 *
 * Shared by an ordinary object and by a union's selected branch, which is not tidiness but correctness: a
 * branch is an ordinary type that happens to have been chosen, so a rule declared on it has to apply exactly
 * as it would anywhere else. When [renderVariant] had a property loop of its own, a conditional inside a
 * branch was silently ignored — the schema said it, the validator enforced it, and the form knew nothing
 * about it, which is the worst of the three places to disagree.
 *
 * [skip] is how the union keeps its discriminator out: that one is drawn by the union itself, as a choice
 * rather than as the fixed value the branch declares.
 */
private fun ChildrenBuilder.renderProperties(
    type: SchType,
    values: Map<String, Any?>,
    seen: Set<String>,
    editable: Boolean,
    path: String,
    errors: FieldErrors,
    onChange: (Map<String, Any?>) -> Unit,
    opts: FormOpts,
    skip: String? = null,
) {
    // A conditional-presence rule decides, from what the watched field currently holds, which properties are
    // required and which may not appear at all.
    val condition = type.condition
    val holds = condition?.holds(values) ?: false
    val forbidden = condition?.forbiddenWhen(holds) ?: emptySet()
    val alsoRequired = condition?.requiredWhen(holds) ?: emptySet()
    // Changing the watched field re-decides the rule, so anything it now forbids has to go with it. The same
    // reasoning as switching a union's branch: leaving the value behind puts a field on the wire that
    // validation refuses, and the form is no longer showing, which is unfixable from the screen.
    fun settle(next: Map<String, Any?>, edited: String): Map<String, Any?> =
        if (condition == null || edited != condition.property) next
        else next - condition.forbiddenWhen(condition.holds(next))

    type.properties.forEach { (name, prop) ->
        if (name == skip) {
            return@forEach
        }
        // A field the form was told to omit, only at the root: an advanced flag an end-user form should not
        // offer (issue #408). A same-named field deeper in the tree is a different field and stays.
        if (path.isEmpty() && name in opts.omit) {
            return@forEach
        }
        // In friendly mode a derived value is not shown at all -- it is produced by something other than the
        // person at the form (an id, the source, the audit stamps), so it has no place on a data-entry screen.
        // The catalog does the opposite and shows it read-only, because that surface documents the wire.
        if (opts.friendly && prop.valueType.derived) {
            return@forEach
        }
        // A forbidden field is hidden -- unless it still holds something, in which case hiding it would hide
        // its failure too, leaving a complaint about a field with nowhere to go and no way to clear it. Shown,
        // it carries its own "not allowed" message and can be emptied.
        if (name in forbidden && isBlankValue(values[name])) {
            return@forEach
        }
        // A derived value is produced by something other than whoever is filling this in (issue #254), so it
        // is shown and never offered: no box to type in, in either mode.
        //
        // Shown rather than hidden, because this surface documents the wire -- the same reason it labels
        // fields with their key rather than a `title`. Hiding a field the contract contains is the
        // data-entry instinct applied to a tool for reading a contract, and it cost real confusion: with the
        // computed field invisible, a value appearing in the response reads as the one you typed being
        // overwritten. A real form-entry GUI would hide it, and would be right to; these two surfaces read
        // the same keyword and reach opposite conclusions, which is the point of the annotation.
        renderField(
            // Never marked required, derived: the asterisk means "you must supply this", and a field with no
            // control is not something anybody can supply. It is required of the *stored* shape, which the
            // response demonstrates by carrying it.
            name, prop,
            (name in type.required || name in alsoRequired) && !prop.valueType.derived,
            values[name], seen,
            editable && !prop.valueType.derived,
            childPath(path, name), errors,
            // A removal has to drop the key, not null it: a null against an object/array type fails the plain
            // type check (they do not coerce), so "removed" would read as "present but wrong".
            emit = { newValue -> onChange(settle(values + (name to newValue), name)) },
            omit = { onChange(settle(values - name, name)) },
            opts = opts,
        )
    }
}

/**
 * Whether a field currently holds nothing worth showing — absent, or text someone has cleared.
 *
 * Matches the kernel's own `emptyIsAbsent` reading of a scalar closely enough for the one decision it makes
 * here (whether a now-forbidden field still needs to be on screen). It deliberately does not try to be that
 * rule: the authority on whether a value counts is the validator, and this only chooses what to draw.
 */
private fun isBlankValue(value: Any?): Boolean = value == null || (value is String && value.isBlank())

/**
 * Renders a discriminated union: a choice of branch, then that branch's own fields (issue #252).
 *
 * The discriminator is drawn **here** rather than by the branch, even though every branch declares it. A
 * branch declares it as a `const` — one fixed value, which is a statement about the shape rather than a
 * choice — so left to the branch it would render as a text box that cannot change the shape it belongs to.
 * Drawn here it is a select over the declared values, which is the one control that makes a union editable.
 *
 * **Switching branches drops the old branch's fields.** Branches are closed objects, so carrying them over
 * would turn every switch into a payload full of undeclared-property failures against fields the form is no
 * longer even showing — invisible and unfixable. Values whose names the new branch also declares are kept,
 * since re-typing a shared field to change one next to it is the kind of thing that makes a form tiring.
 */
private fun ChildrenBuilder.renderVariant(
    type: SchType,
    variants: SchVariants,
    values: Map<String, Any?>,
    seen: Set<String>,
    editable: Boolean,
    path: String,
    errors: FieldErrors,
    onChange: (Map<String, Any?>) -> Unit,
    opts: FormOpts,
) {
    val name = variants.discriminator
    val chosen = values[name].toOptStr()
    val branch = variants.select(chosen)
    val discriminatorPath = childPath(path, name)
    val messages = errors.messagesAt(discriminatorPath)
    // Captured under its own name: inside the Select builder `onChange` is the widget's own prop, and `values`
    // and `options` are likewise taken.
    val emitAll = onChange
    // In friendly mode each choice reads by its branch's title (or a humanized value); the value sent stays the
    // wire discriminator, so only the label changes. The catalog shows the value itself, documenting the wire.
    val choices = optionsToJs(
        variants.values.map { value ->
            val label = if (opts.friendly) variants.byValue[value]?.title ?: humanizeFieldName(value) else value
            SchOption(value, label)
        },
    )
    val describedBy = messages.ifEmpty { null }?.let { fieldErrorsId(discriminatorPath) }

    div {
        id = ElementId(fieldRowId(discriminatorPath))
        tabIndex = -1
        className = ClassName(rowClass(messages))
        labelSpan(if (opts.friendly) humanizeFieldName(name) else name, required = true)
        if (editable) {
            Select {
                this.options = choices
                this.value = chosen
                placeholder = "(choose)"
                style = js("({ minWidth: 200 })")
                this.onChange = { v ->
                    errors.noteEdit(discriminatorPath)
                    val picked = v as? String
                    val next = variants.select(picked)
                    // Keep only what the branch being switched to actually declares (see the note above).
                    val kept = if (next == null) emptyMap() else values.filterKeys { it in next.properties }
                    emitAll(kept + (name to picked))
                }
                markInvalid(asDynamic(), describedBy)
            }
        } else {
            // Not `readOnlyValue(type, …)`: `type` here is the union, so it would label the discriminator
            // "(object)" -- the shape it selects rather than the value it holds.
            chosen?.let {
                span {
                    className = ClassName("field-value")
                    +if (opts.friendly) variants.byValue[it]?.title ?: humanizeFieldName(it) else it
                }
            }
            span {
                className = ClassName("field-type")
                +"(choice)"
            }
        }
    }
    fieldErrors(discriminatorPath, messages)

    if (branch == null) {
        p {
            className = ClassName("type-hint")
            +"Choose a $name to see the rest of the fields."
        }
        return
    }
    // The branch's own fields, minus the discriminator it declares -- already drawn above, and as a choice
    // rather than as the fixed value the branch states. Through the shared loop, so a rule declared on the
    // branch (a conditional, say) applies exactly as it would on any other type.
    renderProperties(branch, values, seen, editable, path, errors, onChange, opts, skip = name)
    errors.undeclaredBelow(path, branch.properties.keys).forEach { (at, messages2) ->
        undeclaredField(childKeyOf(at, path) ?: at, at, messages2)
    }
}

/**
 * A failure against a key the schema does not declare. It borrows the shape of a field row so it reads in
 * place and carries the same id, so the listing can jump to it like any other — but it shows the **key**
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
    opts: FormOpts,
) {
    val vt = prop.valueType
    val elementType = objectElementType(vt)
    if (elementType != null) {
        renderObjectList(name, prop, required, value, elementType, seen, editable, path, errors, emit, omit, opts)
        return
    }
    // A list of scalars, edited: one widget per element. Not the multi-select case (an item type with options
    // is a fixed set of choices, which the Select already emits as a real list), and not the read-only view,
    // where a comma-joined line reads better than a column of single values.
    if (editable && vt.jsonType == SCT.array && vt.itemType?.options == null) {
        renderScalarList(name, prop, required, value, vt.itemType, path, errors, emit, omit, opts)
        return
    }
    if (isStructuredObject(vt)) {
        renderNestedObject(name, prop, required, value, vt, seen, editable, path, errors, emit, omit, opts)
        return
    }

    val messages = errors.messagesAt(path)
    fieldFrame(name, prop, required, path, messages, opts) {
        widget(vt, value, required, editable, messages.ifEmpty { null }?.let { fieldErrorsId(path) }) { newValue ->
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
    opts: FormOpts,
    rowContent: ChildrenBuilder.() -> Unit = {},
) {
    div {
        id = ElementId(fieldRowId(path))
        // Focusable on purpose but not in the tab order: something jumping here from the failure listing has
        // to be able to land on a row that carries no control of its own.
        tabIndex = -1
        className = ClassName(rowClass(messages))
        labelSpan(fieldLabel(name, prop, opts), required)
        rowContent()
    }
    prop.description?.let { desc(it) }
    // Stated before the field is filled in, not discovered by being rejected. The outline shows the same
    // thing for an output type; this is the input side, which is the one someone is about to type into.
    boundHint(prop.valueType)
    fieldErrors(path, messages)
}

/**
 * Marks a control as invalid and points it at the block holding its messages, so the state is announced, and
 * the reason read out rather than only being visible as a color. A no-op when [describedBy] is null, which
 * is how a field with nothing wrong says so.
 *
 * Set through `asDynamic` because these are plain HTML attributes that antd's components forward to the
 * control they wrap, and their Kotlin external interfaces do not declare them — the same route the "add" and
 * "remove" buttons already take for `aria-label`.
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
                // The schema's own wording when it has any, and *only* that: someone filling in a form does
                // not need to be told the JSON type of what they got wrong. The listing at the foot of the
                // page keeps both, because that surface is documenting the wire (issue #202).
                +"${f.userMessage ?: f.message}${choicesSuffix(f)}"
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

/**
 * An object type with a shape to lay out, as opposed to a free-form map.
 *
 * A discriminated union counts, and has to: its fields live on the branch rather than on the union, so it
 * declares no `properties` of its own and would otherwise look exactly like a free-form map — and be handed to
 * the raw-JSON editor, which is the one thing a union must not be edited as. Both places that ask this
 * question (a nested field and an array's element type) need the same answer, which is why it is asked here.
 */
private fun isStructuredObject(vt: SchType): Boolean =
    vt.variants != null || (vt.jsonType == SCT.kObject && vt.properties.isNotEmpty())

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
    opts: FormOpts,
) {
    val typeName = vt.name
    val recursive = typeName != null && typeName in seen
    val present = value is Map<*, *>
    val dataDriven = recursive || !required
    val messages = errors.messagesAt(path)

    fieldFrame(name, prop, required, path, messages, opts) {
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
        renderObject(vt, value.toJsonMapOrEmpty(), childSeen, editable, path, errors, { newSub -> emit(newSub) }, opts)
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
    opts: FormOpts,
) {
    val elements = value.toJsonListOrEmpty()
    val messages = errors.messagesAt(path)
    fieldFrame(name, prop, required, path, messages, opts)

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
            renderObject(elementType, element.toJsonMapOrEmpty(), childSeen, editable, elementPath, errors, { newElement ->
                emit(elements.mapIndexed { j, old -> if (j == i) newElement else old })
            }, opts)
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
    opts: FormOpts,
) {
    val elements = value.toJsonListOrEmpty()
    val messages = errors.messagesAt(path)
    fieldFrame(name, prop, required, path, messages, opts)

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
            //
            // An element is a slot that exists, so `required = true`: there is no absent state to express
            // inside a list (removing an element is the remove control's job), which is what a boolean
            // element asks about (issue #261).
            if (elementType != null) {
                widget(elementType, element, required = true, editable = true) { replace(it) }
            } else {
                Input {
                    this.value = displayValue(element)
                    placeholder = "value"
                    onChange = { e -> replace(e.target.value as String) }
                }
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
 *
 * [required] is the parent object's statement about this field, not part of [vt] — only the boolean branch
 * consults it, to decide whether absence is a state the control has to be able to express.
 */
private fun ChildrenBuilder.widget(
    vt: SchType, value: Any?, required: Boolean, editable: Boolean, describedBy: String? = null,
    emit: (Any?) -> Unit,
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
        // Boolean, where absent says nothing the field cannot already say: a checkbox, the compact control
        // (issue #261). See [booleanIsTwoState] for when that is true, and [checkboxDraw] for what it shows.
        vt.jsonType == SCT.boolean && booleanIsTwoState(vt, required) -> Checkbox {
            val draw = checkboxDraw(vt, value)
            checked = draw == CheckDraw.on
            indeterminate = draw == CheckDraw.unanswered
            onChange = { e -> emit(e.target.checked as Boolean) }
            markInvalid(asDynamic(), describedBy)
        }
        // Boolean with a reachable third state: the choice widget above, over `true` / `false`, whose
        // `allowClear` is the way back to absent. Labels are the wire values rather than Yes / No, for the same
        // reason the form labels a field with its key: this surface documents the payload.
        vt.jsonType == SCT.boolean -> Select {
            options = optionsToJs(booleanOptions)
            this.value = value?.toString()
            placeholder = "(choose)"
            allowClear = true
            style = js("({ minWidth: 200 })")
            // A real Boolean, never the option's string: `allowCoerce` defaults **off** for a boolean, so
            // "true" against a boolean type is a plain wrongType failure. Cleared emits null, which
            // `emptyIsAbsent` reads as absent -- the coerced payload drops the key rather than sending null.
            onChange = { v -> emit((v as? String)?.toOptBool()) }
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
        // A free-form map: an object declaring no properties of its own, so there are no fields to lay out, and
        // the structured path in `renderField` never claimed it. Before this it fell through to the text box
        // below, which showed a Kotlin map's `toString` and turned the map into a string the moment anyone
        // typed in it (issue #251).
        vt.jsonType == SCT.kObject -> JsonObjectField {
            this.value = value
            this.describedBy = describedBy
            this.onEmit = emit
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

/** The two choices a three-state boolean offers. The wire values, verbatim — see the widget's note on labels. */
private val booleanOptions = listOf(SchOption("true", "true"), SchOption("false", "false"))

/**
 * Whether a boolean field has only **two** reachable states, so a checkbox can express it (issue #261).
 *
 * A boolean value has three: `true`, `false`, and absent — and `emptyIsAbsent` defaults **true** for scalars,
 * booleans included, so an absent boolean is "not supplied" rather than `false`. On an update endpoint that is
 * the difference between *leave this alone* and *set it off*, and a two-state control cannot say which: an
 * untouched box is indistinguishable from a deliberate `false`, emitting `false` at all takes toggling on and
 * back off, and nothing gets back to absent.
 *
 * The third state collapses in exactly two cases, which is what this asks:
 *
 * - a **`default`** — the validator injects it for a missing required property, and a declared default is what
 *   absent means in any case, so absent is not an outcome of its own; or
 * - **`required`** — absent is invalid, so only `true` and `false` are reachable *outcomes*. Note the word:
 *   the form can still be holding absent, and for a while it always is. Which control to give the field and
 *   what that control should draw are separate questions, and answering only the first is what left the bug
 *   in #322 — see [checkboxDraw] for the second.
 *
 * Pure, and deliberately so: [SchType] plus the `required` its parent object declares (which lives in the
 * parent's `required` set, not on the property), decided in one place and covered without a browser.
 */
fun booleanIsTwoState(vt: SchType, required: Boolean): Boolean = required || vt.default != null

/**
 * What a boolean checkbox draws: on, off, or **no answer yet** (issue #322).
 *
 * A third entry on a control [booleanIsTwoState] just called two-state is not a contradiction — it is the
 * distinction that rule elides. Absent is invalid for a *required* field, which is what makes `true` and
 * `false` the only two **outcomes**; but it is an entirely ordinary thing for the form to be *holding*, and it
 * is the state a freshly chosen union branch or a newly admitted conditional field starts in. Drawing that as
 * an unticked box asserted an answer the form did not have, and the payload then said nothing: the checkbox
 * looked filled in, a conditional watching it stayed shut, and Run came back 400 on a field the screen showed
 * as decided. Toggling on and back off — the same gesture #261 removed for optional booleans — was the only
 * way to turn absent into a real `false`.
 *
 * So the three cases, in the order they are asked:
 *
 * - a value the form **holds** draws itself, and anything not `true` draws off. A non-boolean pasted through
 *   the request-JSON panel lands here rather than in [CheckDraw.unanswered]: it is present, it is wrong, and
 *   the validator's `wrongType` against it is the honest complaint, not a claim that the field is empty;
 * - **absent with a `default`** draws the default, which is what absent means where one is declared — a
 *   `default: true` field drawn unticked would report the opposite of what it sends;
 * - absent with nothing to stand in for it is [CheckDraw.unanswered]. By [booleanIsTwoState] this reaches the
 *   checkbox only for a required field, which is exactly the field where the missing answer matters.
 *
 * The mixed state is deliberately not *emitted* — nothing seeds a value the user did not enter. The form shows
 * what it holds; the schema's `required` says an answer is owed; validation says so again by name.
 */
fun checkboxDraw(vt: SchType, value: Any?): CheckDraw = when {
    value != null -> if (value == true) CheckDraw.on else CheckDraw.off
    vt.default != null -> if (vt.default == true) CheckDraw.on else CheckDraw.off
    else -> CheckDraw.unanswered
}

/** The three states a boolean checkbox can be drawn in — see [checkboxDraw]. */
@Suppress("EnumEntryName")
enum class CheckDraw { on, off, unanswered }

/** What a free-form map field needs: the value it holds, where to point a screen reader, and how to emit. */
external interface JsonObjectFieldProps : Props {
    var value: Any?
    var describedBy: String?
    var onEmit: (Any?) -> Unit
}

/**
 * Edits a free-form map — an object type with no declared properties — as raw JSON (issue #251).
 *
 * It follows the request-JSON panel's idiom deliberately, down to the `json-edit` class, because the two are
 * the same act at different scales: **text is not parsed per keystroke**. Splicing JSON by hand is rarely one
 * keystroke's worth of change, so a half-finished edit is a normal state rather than an error, and
 * reformatting under the caret while someone types is worse than waiting. **Blur** is where the text is asked
 * to be JSON — and clicking Validate or Run blurs first, so nothing reaches the kernel without passing here.
 *
 * Note what this component does *not* hold: the text. While someone types, the form's own value **is** the
 * text, because an unparseable edit emits the raw string (see [parseJsonField]). That leaves one piece of
 * state — the message from the last parse — and sidesteps the usual controlled-editor problem of re-syncing
 * local text against a value that arrived from somewhere else, which here happens twice: "Apply to form" from
 * the request panel, and the values restored from the URL hash after the first render.
 */
val JsonObjectField = FC<JsonObjectFieldProps> { props ->
    var parseError by useState<String?>(null)
    textarea {
        className = ClassName("code json-edit json-field")
        value = jsonFieldText(props.value)
        spellCheck = false
        placeholder = "{ }"
        onChange = { e ->
            // Typing says nothing about whether the text is JSON yet; it only stops claiming the last answer.
            parseError = null
            props.onEmit(e.target.value)
        }
        onBlur = {
            val parsed = parseJsonField(jsonFieldText(props.value))
            parseError = parsed.error
            props.onEmit(parsed.value)
        }
        markInvalid(asDynamic(), props.describedBy)
    }
    // Rendered with the same class the kernel's own failures use, and for the same field: someone correcting
    // a map should not have to learn that two different things can complain about it.
    parseError?.let { message ->
        p {
            className = ClassName("field-error")
            +message
        }
    }
}

/**
 * The text a free-form map field shows for [value].
 *
 * A `String` passes through untouched — that is text someone is part way through typing, and reformatting it
 * under the caret is exactly what makes an editor unusable. Anything else is a value the form genuinely
 * holds, so it is shown as pretty JSON.
 */
fun jsonFieldText(value: Any?): String = when (value) {
    null -> ""
    is String -> value
    else -> value.toJsonStr()
}

/** What a free-form map field's text parsed to: the [value] to hold, and the [error] to show when it will not. */
class JsonFieldParse(val value: Any?, val error: String?)

/**
 * Accepts the text of a free-form map field.
 *
 * On success the parsed map becomes the field's value. On failure **the text itself does**, deliberately: the
 * form then holds exactly what is on screen, so the request-JSON panel cannot show something the field
 * contradicts, and the kernel reports its own `wrongType` against the same path when Validate runs. The
 * message here is the immediate half of that — it names the offending line and column, which "this must be of
 * type 'object'" cannot.
 *
 * Blank means **absent** rather than an empty map: clearing a field is how someone removes an optional value,
 * and `{}` stays available to anyone who means it.
 */
fun parseJsonField(text: String): JsonFieldParse {
    if (text.isBlank()) {
        return JsonFieldParse(null, null)
    }
    val parse = parseRawPayload(text, "value")
    return JsonFieldParse(parse.values ?: text, parse.error)
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

/**
 * A field's declared bounds, stated the way the outline already states a choice field's options — the range
 * is part of what the shape *is*, and reading it here beats discovering it by being rejected (issue #203).
 * Silent when the field declares neither end.
 */
private fun ChildrenBuilder.boundHint(vt: SchType) {
    val min = vt.minBound
    val max = vt.maxBound
    if (min == null && max == null) return
    val what = when (vt.jsonType) {
        SCT.string -> "characters"
        SCT.array -> "items"
        SCT.kObject -> "properties"
        else -> null
    }
    val range = when {
        min != null && max != null -> "${min.fmtD()} to ${max.fmtD()}"
        min != null -> "${min.fmtD()} or more"
        else -> "${max!!.fmtD()} or less"
    }
    p {
        className = ClassName("type-hint")
        +if (what == null) "range: $range" else "$what: $range"
    }
}

private fun ChildrenBuilder.optionList(options: List<SchOption>) {
    p {
        className = ClassName("type-hint")
        +"one of: ${options.joinToString(", ") { it.value }}"
    }
}
