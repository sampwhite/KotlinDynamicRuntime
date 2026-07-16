package com.dynamicruntime.webapp

import react.ChildrenBuilder
import react.dom.html.ReactHTML.div
import react.dom.html.ReactHTML.span
import web.cssom.ClassName

/**
 * Small form-row helpers shared by the hand-written widget-groups ([AuthFlow], [Profile]).
 *
 * These are *not* part of the generic display engine: [SchemaForm] renders a whole kernel `SchType` and
 * validates it with the shared `coerceAndValidate`, which is the right tool for admin/CRUD surfaces. The auth
 * and profile groups are the other mode -- hand-written React whose copy and features come from the backend --
 * so they lay out a handful of known fields themselves and just want them to look alike while doing it.
 */

/** A labeled antd text/password input row. */
fun ChildrenBuilder.textField(
    label: String,
    value: String,
    isPassword: Boolean = false,
    disabled: Boolean = false,
    onChange: (String) -> Unit,
) {
    div {
        className = ClassName("row")
        span {
            className = ClassName("field-label")
            +label
        }
        Input {
            this.value = value
            this.disabled = disabled
            if (isPassword) type = "password"
            this.onChange = { event -> onChange(event.target.value as String) }
        }
    }
}
