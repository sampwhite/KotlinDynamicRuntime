package com.dynamicruntime.common.util

/**
 * The Markdown extensions' vocabulary (issue #795): the roles shipped copy marks a span with, the attribute names
 * an image may carry, and the class prefix the default rendering gives a role. Each name matches its value.
 */
@Suppress("ConstPropertyName")
object MDR {
    /**
     * The prefix of the class a role renders as by default, so this span:
     *
     * ```
     * [x]{.code}
     * ```
     *
     * becomes `<span class="md-code">`. A prefix rather than the bare role, so a role name can never collide with an
     * application class (`.code` is the app's inset-well style, which an inline value must not pick up).
     */
    const val classPrefix = "md-"

    /** A substituted value a reader copies out -- an address, a client, a persona: set apart from the prose. */
    const val value = "value"

    /** A code a reader reads off and types elsewhere: large, monospaced, spaced out. */
    const val code = "code"

    /**
     * Ends a floated image here (issue #1382): an empty span, `[]{.clear}`, after which what follows -- the rest of the
     * paragraph, and the blocks after it -- goes below the image rather than beside it. For copy whose text beside an
     * image is short on a wide screen, so a list after it would otherwise be squeezed beside the image too.
     */
    const val clear = "clear"

    /** The attributes an image may carry, numeric only; anything else in an attribute block is dropped. */
    const val width = "width"
    const val height = "height"

    /**
     * The scheme an image's source names a client's **file ref** by (issue #1186): `![Our logo](ref:logo)`. Not a
     * path, so copy says *which image* and never which bytes -- a replaced image is the same copy. What it is drawn
     * from is the surface's to say ([MarkdownHooks.refAddress]); a surface that cannot say draws its alt text.
     */
    const val refScheme = "ref"

    /**
     * The scheme an image's source names an image a **component ships** by (issue #1339): `![A diagram](img:sample:diagram)`,
     * the name rooted under the component's owner. The same image for every client -- unlike a [refScheme] ref, which is
     * a client's own -- and a separate namespace from refs: a client changes what copy shows by changing the copy. What
     * it is drawn from is the surface's to say ([MarkdownHooks.imageAddress]); a surface that cannot say draws its alt
     * text.
     */
    const val imageScheme = "img"
}
