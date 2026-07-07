@file:JsModule("antd")
@file:JsNonModule

package com.dynamicruntime.webapp

import react.ComponentType
import react.PropsWithChildren

/**
 * Hand-written Kotlin bindings for a few Ant Design components.
 *
 * `@file:JsModule("antd")` maps every top-level `external val` in this file to a
 * named export of the `antd` npm package (so `Button` here is antd's `Button`).
 * There are no official kotlin-wrappers for antd and the old TS→Kotlin generator
 * (Dukat) is discontinued, so we declare only the props we actually use. antd
 * ignores unknown/absent props, so these thin interfaces are safe to grow later.
 *
 * Each component is a [ComponentType], i.e. a React `ElementType`, so it plugs
 * straight into the kotlin-react builder DSL: `Button { type = "primary"; +"Go" }`.
 */

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
    var disabled: Boolean?
    /** antd passes a change event; read `event.target.value`. */
    var onChange: ((event: dynamic) -> Unit)?
    /** Fires when Enter is pressed inside the input. */
    var onPressEnter: ((event: dynamic) -> Unit)?
}

external val Input: ComponentType<InputProps>

external interface CheckboxProps : PropsWithChildren {
    var checked: Boolean?
    /** antd passes a change event; read `event.target.checked`. */
    var onChange: ((event: dynamic) -> Unit)?
}

external val Checkbox: ComponentType<CheckboxProps>

external interface DatePickerProps : PropsWithChildren {
    /** antd calls this with (date, dateString); we only need the string form. */
    var onChange: ((date: dynamic, dateString: String) -> Unit)?
}

external val DatePicker: ComponentType<DatePickerProps>

/** Layout helper that spaces its children; used to lay out the demo row. */
external interface SpaceProps : PropsWithChildren

external val Space: ComponentType<SpaceProps>
