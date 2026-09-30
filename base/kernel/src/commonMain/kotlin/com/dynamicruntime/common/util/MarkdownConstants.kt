package com.dynamicruntime.common.util

/**
 * The Markdown extensions' vocabulary (issue #795): the roles shipped copy marks a span with, the attribute names
 * an image may carry, and the class prefix the default rendering gives a role. Each name matches its value.
 */
@Suppress("ConstPropertyName")
object MDR {
    /**
     * The prefix of the class a role renders as by default: `[x]{.code}` becomes `<span class="md-code">`. A prefix
     * rather than the bare role, so a role name can never collide with an application class (`.code` is the
     * app's inset-well style, which an inline value must not pick up).
     */
    const val classPrefix = "md-"

    /** A substituted value a reader copies out -- an address, a client, a persona: set apart from the prose. */
    const val value = "value"

    /** A code a reader reads off and types elsewhere: large, monospaced, spaced out. */
    const val code = "code"

    /** The attributes an image may carry, numeric only; anything else in an attribute block is dropped. */
    const val width = "width"
    const val height = "height"
}
