package com.dynamicruntime.webapp

import react.ChildrenBuilder
import react.ComponentType
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

/**
 * antd's `Input.Password`: a masked input carrying its own show/hide eye toggle, so someone typing a password
 * they cannot see can check it before submitting -- which matters most on the *new* password fields, where a
 * typo is not caught by a failed login but silently becomes the password.
 *
 * It is reached through [Input] rather than declared in [AntdComponents] because it is a static property of
 * antd's `Input`, not a named export of the package -- and every top-level declaration in that file maps to
 * one. The props are `Input`'s (antd's `visibilityToggle` defaults to on), so [InputProps] describes it.
 */
@Suppress("UNCHECKED_CAST")
private val PasswordInput: ComponentType<InputProps> =
    Input.asDynamic().Password as ComponentType<InputProps>

/**
 * HTML `autocomplete` tokens, so every field states what it is instead of letting a password manager guess.
 *
 * The guess is worth pre-empting: a browser looking at "some text input, then a password input" reasonably
 * concludes it is a sign-in form and fills the pair -- putting the account's *email* in the verification-code
 * box and the *existing* password in a field asking for a new one. [oneTimeCode] and [newPassword] are the
 * standard tokens that say otherwise, and [newPassword] additionally invites a manager to offer a generated
 * password, which is the behavior you actually want on a change-password screen.
 */
@Suppress("ConstPropertyName")
object AC {
    const val oneTimeCode = "one-time-code"
    const val newPassword = "new-password"
    const val currentPassword = "current-password"
    const val username = "username"
}

/**
 * A labeled antd text/password input row. A password row masks its value and offers a show/hide toggle.
 *
 * [autoComplete] should be set on anything a password manager might take an interest in -- see [AC].
 */
fun ChildrenBuilder.textField(
    label: String,
    value: String,
    isPassword: Boolean = false,
    disabled: Boolean = false,
    autoComplete: String? = null,
    onChange: (String) -> Unit,
) {
    div {
        className = ClassName("row")
        span {
            className = ClassName("field-label")
            +label
        }
        // Input.Password sets its own input type as the toggle flips, so `type` is left alone here.
        val field = if (isPassword) PasswordInput else Input
        field {
            this.value = value
            this.disabled = disabled
            this.autoComplete = autoComplete
            this.onChange = { event -> onChange(event.target.value as String) }
        }
    }
}
