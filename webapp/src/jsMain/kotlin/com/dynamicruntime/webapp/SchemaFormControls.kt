package com.dynamicruntime.webapp

import com.dynamicruntime.common.schema.SCT
import com.dynamicruntime.common.schema.SFMT
import com.dynamicruntime.common.schema.SchOption
import com.dynamicruntime.common.schema.SchType
import com.dynamicruntime.common.schema.hideGatedFields
import com.dynamicruntime.common.schema.isBinaryFormat
import com.dynamicruntime.common.schema.isDateFormat
import com.dynamicruntime.common.util.toJsonStr
import com.dynamicruntime.common.util.toOptBool
import react.ChildrenBuilder
import react.FC
import react.Props
import react.dom.html.ReactHTML.input
import react.dom.html.ReactHTML.p
import react.dom.html.ReactHTML.textarea
import react.useState
import web.cssom.ClassName
import web.html.InputType
import com.dynamicruntime.common.util.toJsonListOfStrings

/**
 * The input control an editable field draws, decided purely from its schema. Extracted from [widget] (issue
 * #781) so the mapping — the most consequential presentation rule in the form — is a pure function a
 * `jsNodeTest` pins, rather than logic only a browser exercises; [widget] switches its antd construction on the
 * result, so the test and the render cannot drift. A non-editable field is [ReadOnly] whatever its type.
 */
enum class ControlKind {
    /** Not editable: the read-only view renders the value, not a control. */
    ReadOnly,

    /** An array of closed choices — antd `Select` in `multiple` mode. */
    MultiSelect,

    /** An array of open choices — antd `Select` in `tags` mode (pick several, or add your own; issue #418). */
    MultiSelectOpen,

    /** A single open choice — the `OpenChoiceField` (`AutoComplete`): the list suggests, anything else passes. */
    OpenChoice,

    /** A single closed choice — antd `Select`. */
    Choice,

    /** A two-state boolean — a checkbox (issue #261); see [booleanIsTwoState] / [checkboxDraw]. */
    Checkbox,

    /** A boolean with a reachable third (absent) state — a `Select` over `true`/`false` with `allowClear`. */
    TristateBoolean,

    /** A day-only date (`format: "date"`) — a `DatePicker`. */
    Date,

    /** A date-time (`format: "date-time"`) — a `DatePicker` with `showTime`. */
    DateTime,

    /** File content (`format: "binary"`) — a native file input. */
    File,

    /** A free-form object with no declared properties — the `JsonObjectField` map editor (issue #251). */
    JsonMap,

    /** string / integer / number / unknown — a text `Input`, coerced by the kernel validator on submit. */
    Text,
}

/**
 * Which [ControlKind] a field draws, from its schema [vt], whether it is [required], and whether it is
 * [editable] (issue #781). Pure over the kernel [SchType] — the branch order mirrors the historical `widget`
 * `when`: options win over the base type, an open list is its own kind, a two-state boolean is a checkbox and a
 * three-state one a select, then the string formats, a property-less object is a JSON map, everything else text.
 */
fun controlKind(vt: SchType, required: Boolean, editable: Boolean): ControlKind = when {
    !editable -> ControlKind.ReadOnly
    // Multi-select: an array of choices. An open element type accepts a value that is not offered, so it takes
    // `tags` mode rather than a separate component (a multi-select is already a list, so one more is a mode).
    vt.jsonType == SCT.array && vt.itemType?.options != null ->
        if (vt.itemType?.openOptions == true) ControlKind.MultiSelectOpen else ControlKind.MultiSelect
    vt.options != null && vt.openOptions -> ControlKind.OpenChoice
    vt.options != null -> ControlKind.Choice
    vt.jsonType == SCT.boolean && booleanIsTwoState(vt, required) -> ControlKind.Checkbox
    vt.jsonType == SCT.boolean -> ControlKind.TristateBoolean
    vt.jsonType == SCT.string && isDateFormat(vt.format) ->
        if (vt.format == SFMT.date) ControlKind.Date else ControlKind.DateTime
    vt.jsonType == SCT.string && isBinaryFormat(vt.format) -> ControlKind.File
    vt.jsonType == SCT.kObject -> ControlKind.JsonMap
    // string / integer / number / unknown. Lists do not arrive here in edit mode -- a list of choices is the
    // multi-select above, and any other list is a growing column of these widgets (renderScalarList).
    else -> ControlKind.Text
}

/**
 * A field's value cell. In read-only mode ([editable] false — the response view and the read-only input view)
 * it is plain text: the value, annotated with the field's type in words, with no form control. In edit mode it
 * is the control appropriate to the field's kind, reporting changes through [emit].
 *
 * [required] is the parent object's statement about this field, not part of [vt] — only the boolean branch
 * consults it, to decide whether absence is a state the control has to be able to express.
 *
 * [commit] says the user has finished with the field (issue #718). Which moment that is depends on the control:
 * a text box settles on **blur** (the value changes per keystroke, and none of those is an answer), while a
 * choice, a checkbox, a date or a file settle on the **selection** -- picking is finishing -- so those call it
 * right after they emit. A caller that does not care leaves it as the no-op.
 */
internal fun ChildrenBuilder.widget(
    vt: SchType, value: Any?, required: Boolean, editable: Boolean, describedBy: String? = null,
    presentation: String? = vt.presentation, opts: FormOpts = FormOpts(),
    /** The choices a form's layout offers in place of the schema's (issue #1022, [formChoiceList]). */
    choices: List<SchOption>? = null,
    commit: () -> Unit = {},
    emit: (Any?) -> Unit,
) {
    val kind = controlKind(vt, required, editable)
    when (kind) {
        ControlKind.ReadOnly -> readOnlyValue(vt, value, presentation, opts)
        ControlKind.MultiSelect, ControlKind.MultiSelectOpen -> Select {
            mode = if (kind == ControlKind.MultiSelectOpen) "tags" else "multiple"
            options = optionsToJs(vt.itemType?.options.orEmpty())
            this.value = value.toJsonListOfStrings().toTypedArray()
            placeholder = "(choose)"
            style = js("({ minWidth: 200 })")
            onChange = { v -> emit(jsToList(v)); commit() }
            markInvalid(asDynamic(), describedBy)
        }
        ControlKind.OpenChoice -> OpenChoiceField {
            // Qualified, because the enclosing function's own `value` / `describedBy` parameters shadow the
            // props of the same name inside this block.
            this.options = vt.options.orEmpty()
            this.value = value?.toString()
            this.describedBy = describedBy
            this.onEmit = { v -> emit(v) }
            this.onCommit = commit
        }
        ControlKind.Choice -> Select {
            options = optionsToJs(choices ?: vt.options.orEmpty())
            this.value = value?.toString()
            placeholder = "(choose)"
            allowClear = true
            style = js("({ minWidth: 200 })")
            onChange = { v -> emit(v as? String); commit() }
            markInvalid(asDynamic(), describedBy)
        }
        ControlKind.Checkbox -> Checkbox {
            val draw = checkboxDraw(vt, value)
            checked = draw == CheckDraw.on
            indeterminate = draw == CheckDraw.unanswered
            onChange = { e -> emit(e.target.checked as Boolean); commit() }
            markInvalid(asDynamic(), describedBy)
        }
        // The three-state boolean's labels are the wire values rather than Yes / No, for the same reason the
        // form labels a field with its key: this surface documents the payload.
        ControlKind.TristateBoolean -> Select {
            options = optionsToJs(booleanOptions)
            this.value = value?.toString()
            placeholder = "(choose)"
            allowClear = true
            style = js("({ minWidth: 200 })")
            // A real Boolean, never the option's string: `allowCoerce` defaults **off** for a boolean, so
            // "true" against a boolean type is a plain wrongType failure. Cleared emits null, which
            // `emptyIsAbsent` reads as absent -- the coerced payload drops the key rather than sending null.
            onChange = { v -> emit((v as? String)?.toOptBool()); commit() }
            markInvalid(asDynamic(), describedBy)
        }
        // Date field. Bound like every other widget, which it previously was not: with no `value`, antd's
        // picker is uncontrolled, so a date the form already held -- from a restored link, or a payload loaded
        // through the request-JSON panel -- never appeared in the field. The conversions are antd's terms, not
        // ours: it speaks Dayjs where the schema says string.
        ControlKind.Date, ControlKind.DateTime -> {
            val dayOnly = kind == ControlKind.Date
            DatePicker {
                this.value = value?.toString()?.takeIf { it.isNotBlank() }?.let { dayjs(it) }?.takeIf { it.isValid() }
                // A `date-time` field needs the time picked too. Without this the widget can only return a
                // day, so binding a full timestamp and then touching the field would quietly drop its time.
                showTime = !dayOnly
                onChange = { date, dateString ->
                    // A day emits the day text; a moment emits ISO-8601 in UTC, which is exactly the shape the
                    // kernel parses and writes back. Cleared emits null, which reads as absent (issue #187).
                    emit(if (date == null) null else if (dayOnly) dateString else date.toISOString())
                    commit()
                }
                markInvalid(asDynamic(), describedBy)
            }
        }
        // File content (OpenAPI's `type: string, format: binary`): a file picker. What it emits is the
        // browser's own File object, not text -- which is exactly why the kernel validator leaves a binary
        // field's value alone rather than coercing it, and why SchemaCatalogApi sends this endpoint as
        // multipart/form-data rather than JSON. A plain <input type="file"> rather than antd's Upload: that
        // component wants to own the upload itself, which is the runtime's job here.
        ControlKind.File -> input {
            // `type` is web.html.InputType, an external value over the HTML attribute string; "file" is that
            // attribute's value, cast rather than spelled through the wrapper's own constant, so this does not
            // ride on which of them the current kotlin-wrappers exposes.
            type = "file".unsafeCast<InputType>()
            onChange = { e ->
                val files = e.target.asDynamic().files
                emit(if (files != null && (files.length as Int) > 0) files[0] else null)
                commit()
            }
            markInvalid(asDynamic(), describedBy)
        }
        ControlKind.JsonMap -> JsonObjectField {
            // One control over a whole object -- a map among them (issue #1055) -- has no box per field to leave
            // undrawn, so the fields a gate hides from this caller are left out of what it is handed. A write
            // keeps a gated field that is absent as it was stored, so the round trip is the hidden box's.
            this.value = hideGatedFields(vt, value, opts.gateAllows)
            this.describedBy = describedBy
            this.onEmit = emit
            this.onCommit = commit
        }
        ControlKind.Text -> Input {
            this.value = displayValue(value)
            placeholder = typeHint(vt)
            onChange = { e -> emit(e.target.value as String) }
            onBlur = { commit() }
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

/** What an open choice field needs: its suggestions, the value held, where to point a screen reader, how to emit. */
external interface OpenChoiceFieldProps : Props {
    var options: List<SchOption>
    var value: String?
    var describedBy: String?
    var onEmit: (String?) -> Unit
    /** The field settled (issue #718): a suggestion picked, or the box left after typing. See `widget`. */
    var onCommit: (() -> Unit)?
}

/**
 * A choice field whose list does not bound the value (issue #418): pick a suggestion, or type something else.
 *
 * The control is antd's `AutoComplete`, which is `Select` in combobox mode -- so the difference from the
 * closed dropdown beside it is real but small, and costs no new dependency.
 *
 * **`filterOption = false` is the load-bearing line, and it is deliberate rather than a default.** A combobox
 * normally filters its popup by what is in the box -- which for a *short* list is the wrong behavior twice
 * over. Once a value is chosen, what is in the box *is* that value, so reopening would offer only the option
 * already showing and the rest of a five-item list would be unreachable without clearing the field first. And
 * while free text is being typed, the suggestions vanish one keystroke in, exactly when a reminder of what is
 * on offer is most useful. Turning filtering off means the whole list is visible whenever the popup is,
 * which is what somebody who does not yet know what is there needs.
 *
 * It is not the permanent answer for a **long** list, where narrowing is the only way to find anything. When
 * one appears, note that in antd 6 `filterOption` is no longer a top-level `AutoComplete` prop -- it moved
 * under `showSearch`, and a function passed at the top level is silently ignored rather than rejected. That
 * cost an afternoon: several filtering rules were written, and the reason none of them appeared to work was
 * that none of them was ever called.
 *
 * Filtering matches the **label**, so a friendly label stays searchable while a terser value is stored. What
 * lands in the box on selection is antd's decision and is the option's *value* -- deliberately accepted
 * rather than worked around: a free-entry value has to be something a person could plausibly have typed, so a
 * list whose labels stray far from its values is the thing to reconsider, not the widget.
 */
val OpenChoiceField = FC<OpenChoiceFieldProps> { props ->
    AutoComplete {
        options = optionsToJs(props.options)
        value = props.value
        placeholder = "(choose or type)"
        allowClear = true
        style = js("({ minWidth: 200 })")
        // Show every suggestion whatever is typed; see the class note.
        filterOption = false
        onChange = { v ->
            // Cleared emits null rather than "", so `emptyIsAbsent` reads it as absent and the coerced
            // payload drops the key -- the same contract the closed dropdown's `allowClear` has.
            props.onEmit((v as? String)?.ifEmpty { null })
        }
        // A typed value is the field's value while it is typed, so the box settles on blur like a text box;
        // a pick closes the popup and blurs too, so one hook covers both ways of answering.
        onBlur = { props.onCommit?.invoke() }
        markInvalid(asDynamic(), props.describedBy)
    }
}

/** What a free-form map field needs: the value it holds, where to point a screen reader, and how to emit. */
external interface JsonObjectFieldProps : Props {
    var value: Any?
    var describedBy: String?
    var onEmit: (Any?) -> Unit
    /** The field settled (issue #718): the blur that parses the text is also where the value is final. */
    var onCommit: (() -> Unit)?
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
            props.onCommit?.invoke()
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
