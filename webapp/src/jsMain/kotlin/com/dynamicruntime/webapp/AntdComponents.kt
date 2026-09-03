@file:JsModule("antd")
@file:JsNonModule

package com.dynamicruntime.webapp

import react.ComponentType
import react.Props
import react.PropsWithChildren

/**
 * Handwritten Kotlin bindings for a few Ant Design components.
 *
 * `@file:JsModule("antd")` maps every top-level `external val` in this file to a
 * named export of the `antd` npm package (so `Button` here is antd's `Button`).
 * There are no official kotlin-wrappers for antd and the old TS→Kotlin generator
 * (Dukat) is discontinued, so we declare only the props we actually use. The antd library
 * ignores unknown/absent props, so these thin interfaces are safe to grow later.
 *
 * Each component is a [ComponentType], i.e., a React `ElementType`, so it plugs
 * straight into the kotlin-react builder DSL: `Button { type = "primary"; +"Go" }`.
 */

/**
 * antd's `theme` export, whose `darkAlgorithm` is the dark token set handed to [ConfigProvider]. antd v5 has
 * no stylesheet to swap: it derives every component's colors from these tokens at runtime. Opaque functions
 * we only pass straight back to antd, so `dynamic` (as with [SelectProps.style]).
 */
external val theme: dynamic

external interface ConfigProviderProps : PropsWithChildren {
    /** A theme config object, e.g. `js("({})")` with `algorithm` set to one of [theme]'s algorithms. */
    var theme: dynamic
}

/**
 * antd's app-wide configuration context. Wrapping the tree in one carrying [theme]'s `darkAlgorithm` is what
 * makes antd's controls dark. Without it antd renders its **light** default inside our permanently dark
 * shell, which is legible only by luck: disabled text lands at `rgba(0,0,0,.25)` on a dark card -- 1.44:1,
 * well under WCAG's 4.5:1 -- and inputs come out as white slabs (issue #96).
 */
external val ConfigProvider: ComponentType<ConfigProviderProps>

external interface ButtonProps : PropsWithChildren {
    /** "primary" | "default" | "dashed" | "text" | "link". */
    var type: String?
    /** antd passes a MouseEvent; a zero-arg Kotlin lambda is fine at runtime. */
    var onClick: (() -> Unit)?
    var loading: Boolean?
    var disabled: Boolean?
    /** Renders the button in a red/danger style — used for Delete. */
    var danger: Boolean?
    /** "large" | "middle" | "small". */
    var size: String?
}

external val Button: ComponentType<ButtonProps>

external interface InputProps : PropsWithChildren {
    var value: String?
    var placeholder: String?
    /** HTML input type, e.g. "password" (antd masks it); omit for a normal text input. */
    var type: String?
    var disabled: Boolean?
    /** Shows a clear (×) button once the input has a value. */
    var allowClear: Boolean?
    /** antd passes a change event; read `event.target.value`. */
    var onChange: ((event: dynamic) -> Unit)?
    /** Fires when Enter is pressed inside the input. */
    var onPressEnter: ((event: dynamic) -> Unit)?
    /**
     * The HTML `autocomplete` token (`"one-time-code"`, `"new-password"`, `"current-password"`, `"username"`,
     * `"off"`, …). antd forwards unknown props to the underlying `<input>`, so this reaches the browser. It is
     * what stops a password manager from guessing wrong -- see [AC] and the note in [textField].
     */
    var autoComplete: String?

    /**
     * React style object. antd's `Input` is `width: 100%`, so inside a flex row it takes whatever the
     * container has left -- which makes its width an accident of the panel rather than a decision, and is why
     * a filter box grew to 816px the moment the panel around it did (issue #462).
     *
     * Set here rather than in `app.css`, and that is not a preference: antd 6 emits its own CSS-in-JS rules
     * with higher specificity and later injection, so a stylesheet selector for an antd control's width is
     * quietly ignored. Tried, and it was. [SelectProps.style] carries its widths for the same reason.
     */
    var style: dynamic
}

external val Input: ComponentType<InputProps>

external interface CheckboxProps : PropsWithChildren {
    var checked: Boolean?
    /**
     * Draws the box as neither on nor off — antd's "mixed" state, a dash instead of a tick. Visual only: it
     * does not change what [checked] reports, and a click still arrives as `checked = true`.
     */
    var indeterminate: Boolean?
    var disabled: Boolean?
    /** antd passes a change event; read `event.target.checked`. */
    var onChange: ((event: dynamic) -> Unit)?
}

external val Checkbox: ComponentType<CheckboxProps>

external interface SelectProps : PropsWithChildren {
    /** Selected value: a `String` for single-select, or an array of strings when [mode] is "multiple". */
    var value: Any?
    /** "multiple" | "tags" for multi-select; leave null/undefined for a single choice. */
    var mode: String?
    /** The choices, as antd `{ label, value }` objects (build with [optionsToJs]). */
    var options: Array<dynamic>?
    var disabled: Boolean?
    var placeholder: String?
    var allowClear: Boolean?
    /** React style object (e.g. `js("({ minWidth: 180 })")`); antd Selects otherwise collapse narrow. */
    var style: dynamic
    /** antd passes the new value (a string, or an array for multi-select). */
    var onChange: ((value: dynamic) -> Unit)?
}

external val Select: ComponentType<SelectProps>

/**
 * antd's combobox: a text input with a suggestion popup, for a choice list that does not bound the value
 * (issue #418).
 *
 * Internally it *is* [Select] -- antd renders `<Select mode={SECRET_COMBOBOX_MODE_DO_NOT_USE} suffixIcon=
 * {null}>` -- so it costs no new dependency, inherits the same theme tokens, and behaves the same way about
 * keyboard and popup placement. What differs is that the control is a real `<input>`, which is also why it is
 * the one choice widget here that browser automation can drive.
 */
external interface AutoCompleteProps : PropsWithChildren {
    /** The text in the box, which for a free-entry field **is** the value. */
    var value: String?
    /** The suggestions, as antd `{ label, value }` objects (build with [optionsToJs]). */
    var options: Array<dynamic>?
    var disabled: Boolean?
    var placeholder: String?
    var allowClear: Boolean?
    /** React style object; see [SelectProps.style] for why one is needed at all. */
    var style: dynamic
    /** antd passes the text -- typed or the picked option's **value**, never its label. */
    var onChange: ((value: dynamic) -> Unit)?
    /**
     * Whether the popup narrows to what has been typed. `false` shows every option always, which is what a
     * short suggestion list wants (see `OpenChoiceField`).
     *
     * **Only the boolean form works here.** In antd 6 a custom filter *function* for `AutoComplete` lives
     * under `showSearch` (`showSearch = { filterOption: … }`), not at the top level; one passed here is
     * ignored without complaint. Left as `dynamic` rather than `Boolean?` so the function form stays
     * reachable when a long list eventually needs it -- but read that note before reaching for it.
     */
    var filterOption: dynamic
}

external val AutoComplete: ComponentType<AutoCompleteProps>

external interface DatePickerProps : PropsWithChildren {
    /**
     * The selected date, as a `Dayjs` (or null for empty) — antd's own date type, not a string. Without this
     * the picker is uncontrolled, and a value the form already holds never reaches the field.
     */
    var value: Dayjs?

    /** Whether to pick a time as well as a day; set for a `date-time` field so the time is not lost. */
    var showTime: Boolean?

    /** antd calls this with (date, dateString): the Dayjs object and its formatted text. */
    var onChange: ((date: Dayjs?, dateString: String) -> Unit)?

    /** React style object; see [InputProps.style] for why a width belongs here and not in the stylesheet. */
    var style: dynamic
}

external val DatePicker: ComponentType<DatePickerProps>

/** Layout helper that spaces its children; used to lay out the demo row. */
external interface SpaceProps : PropsWithChildren

external val Space: ComponentType<SpaceProps>

external interface TableProps : Props {
    /** Column configs, each an antd `{ title, dataIndex, key, width? }` object. */
    var columns: Array<dynamic>
    /** Row data, each a plain object keyed by the columns' dataIndex plus a `key`. */
    var dataSource: Array<dynamic>
    /** `false` to hide paging or a paging config object. */
    var pagination: dynamic
    /** Per-row props; antd calls it with (record, index) and uses the returned `{ onClick, style }`. */
    var onRow: ((record: dynamic, index: Int) -> dynamic)?
    /** Field on each row used as its React key. */
    var rowKey: String?
    /** "large" | "middle" | "small". */
    var size: String?
    /**
     * `"fixed"` makes the declared column widths **authoritative**; left unset, the browser's auto table
     * layout treats them as hints and hands width to whichever column has the longest unbreakable content.
     *
     * That difference is visible rather than theoretical: with auto layout one 41-character email address
     * took 298px of a 220px column while the three date columns -- whose content is a fixed 153px and cannot
     * be shortened -- were squeezed to 138 and wrapped onto a second line. The column that *could* have given
     * way took from the ones that could not.
     */
    var tableLayout: String?
    /**
     * Fires on a table change -- here, a column-header sort. antd calls it with (pagination, filters, sorter);
     * the sorter carries `{ field, order }`, `order` being "ascend" | "descend" | undefined (undefined when a
     * header is toggled back to unsorted). Used to drive a **server-side** re-fetch (issue #411).
     */
    var onChange: ((pagination: dynamic, filters: dynamic, sorter: dynamic) -> Unit)?
}

external val Table: ComponentType<TableProps>
