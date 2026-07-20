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
 * makes antd's controls dark. Without it antd renders its **light** default inside our permanently-dark
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
}

external val Input: ComponentType<InputProps>

external interface CheckboxProps : PropsWithChildren {
    var checked: Boolean?
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

external interface DatePickerProps : PropsWithChildren {
    /** antd calls this with (date, dateString); we only need the string form. */
    var onChange: ((date: dynamic, dateString: String) -> Unit)?
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
}

external val Table: ComponentType<TableProps>
