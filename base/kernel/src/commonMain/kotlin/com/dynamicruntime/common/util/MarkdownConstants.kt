package com.dynamicruntime.common.util

/**
 * The Markdown extensions' vocabulary (issue #795): the roles shipped copy marks a span with, the attribute names
 * an image may carry, and the class prefix the default rendering gives a role. Each name matches its value, except a
 * hyphenated role's, which is camel-cased ([floatLeft]). Every role is declared, with what it does, in [markdownRoles].
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

    /** An image floated left of the text that follows (issue #1382). */
    const val floatLeft = "float-left"

    /** An image floated right of the text that follows (issue #1382). */
    const val floatRight = "float-right"

    /** A floated image the size of a character, kept beside its line at any width (issue #1534). */
    const val icon = "icon"

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

/** What a role is written on (issue #1562): a bracketed span `[text]{.role}`, or an image `![alt](src){.role}`. */
@Suppress("EnumEntryName")
enum class MdRolePlace { span, image }

/**
 * One role copy may write (issue #1562): its [name], what it is written on ([place]), what it does on a page
 * ([description]), and how a mail shows it ([inMail]), where no stylesheet reaches. Data, so the copy reference lists
 * the roles from it and a check reports copy naming a role that is not here, or one written where it does not apply.
 */
class MarkdownRole(val name: String, val place: MdRolePlace, val description: String, val inMail: String)

/**
 * Every role the renderer's surfaces realize (issue #1562), in the order the copy reference lists them. Each renders as
 * the class [MDR.classPrefix] + its name, which `app.css` styles, and a mail realizes the span roles itself
 * (`MailCopy.decorateValue`). A role added here needs its rule there, which a test holds it to.
 */
val markdownRoles: List<MarkdownRole> = listOf(
    MarkdownRole(
        MDR.value, MdRolePlace.span,
        "Sets a substituted value apart from the prose, such as an address or a client, so a reader can see what to copy.",
        "In bold.",
    ),
    MarkdownRole(
        MDR.code, MdRolePlace.span,
        "A code a reader reads off and types elsewhere: monospaced, bold, and spaced out.",
        "Large, monospaced, and bold.",
    ),
    MarkdownRole(
        MDR.clear, MdRolePlace.span,
        "Written as an empty span, `[]{.clear}`. Ends a floated image here, so what follows goes below the image rather " +
            "than beside it.",
        "Nothing, since a mail floats no image.",
    ),
    MarkdownRole(
        MDR.floatLeft, MdRolePlace.image,
        "Floats the image left of the text that follows. On a screen narrower than 480 pixels, it sits above the text instead.",
        "Nothing: a mail shows an image as its alt text.",
    ),
    MarkdownRole(
        MDR.floatRight, MdRolePlace.image,
        "Floats the image right of the text that follows. On a screen narrower than 480 pixels, it sits above the text instead.",
        "Nothing: a mail shows an image as its alt text.",
    ),
    MarkdownRole(
        MDR.icon, MdRolePlace.image,
        "With a float, keeps a small image beside its line at any width, a character's space from it: " +
            "`{.float-left .icon width=20}`.",
        "Nothing: a mail shows an image as its alt text.",
    ),
)
