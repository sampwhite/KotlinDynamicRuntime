package com.dynamicruntime.common.gedra

import com.dynamicruntime.common.schema.SchLayout
import com.dynamicruntime.common.schema.SchType
import com.dynamicruntime.common.schema.SchVariants
import com.dynamicruntime.common.util.humanizeFieldName

/**
 * A trait's name, wherever one is shown to a person (issue #1520): the forms listing's Contains column, a form's trait
 * choice and entry headers, a lock's refusal, a section's fallback heading, Design View. The first of:
 *
 *  1. the name line of its data type's layout heading ([headingName] of `g-layout` `label`) -- the copy written for
 *     people, and the one that gets changed: by the trait designer, by Design View, and by a client's alteration of a
 *     global trait's layout;
 *  2. its data type's `title`, the schema's own name, for a type with no heading;
 *  3. its id, humanized.
 *
 * [heading] is the heading as delivered, its backend `%{@t(...)}` pulls already resolved. The entry type's own title
 * is not read: a client can set it only on a global trait, since a client's own trait regenerates its entry type.
 */
fun traitNameOf(traitId: String, heading: String?, title: String?): String =
    heading?.let(::headingName)?.takeIf { it.isNotBlank() }
        ?: title?.trim()?.takeIf { it.isNotBlank() }
        ?: humanizeFieldName(traitId)

/**
 * [traitNameOf] for the trait whose data type is [dataType], its heading looked up in [layouts] by the data type's
 * qualified name -- the key every delivered layout map uses. A null [dataType] (a trait the caller's schema does not
 * carry) names the trait by its id.
 */
fun traitDisplayName(traitId: String, dataType: SchType?, layouts: Map<String, SchLayout>): String =
    traitNameOf(traitId, dataType?.name?.let { layouts[it] }?.label, dataType?.title)

/**
 * [traitDisplayName] for the trait a branch of an entries union stands for: [variants] is the union, [traitId] the
 * branch's discriminator value, and the trait's data type is the branch's `data` property.
 */
fun traitDisplayName(traitId: String, variants: SchVariants?, layouts: Map<String, SchLayout>): String =
    traitDisplayName(traitId, variants?.byValue?.get(traitId)?.properties?.get(GE.data)?.valueType, layouts)

/**
 * The name a layout heading gives: [headingLine], unless that line still holds a template block -- a frontend `${…}`,
 * which only the form can fill in, or a backend `%{…}` delivered as written because it did not resolve. Such a line is
 * no name in plain text, so it gives none (blank), and the trait is named by its title instead.
 */
fun headingName(heading: String): String =
    headingLine(heading).takeUnless { $$"${" in it || "%{" in it }.orEmpty()

/**
 * A layout heading as one line of plain words: its first non-blank line, with its Markdown heading marks and its inline
 * Markdown taken off -- `Site visit` from `## **Site** visit` and the copy under it. A heading's first line is its
 * type's name, by convention; the rest is copy for the form, which only the section heading shows.
 */
fun headingLine(heading: String): String =
    plainInline(heading.lineSequence().map { it.trim() }.firstOrNull { it.isNotEmpty() }?.trimStart('#')?.trim().orEmpty())

// An image or a link reads as its text; code, strong, emphasis, and strike lose their marks. A `*` or `_` counts as a
// mark only where it opens and closes a run at word edges, so `snake_case` keeps its underscore.
private val inlineLink = Regex("""!?\[([^\]]*)\]\([^)]*\)""")
private val inlineMarks = Regex("""`+|\*\*|__|~~""")
private val inlineEmphasis = Regex("""(?<![\w*])[*_](\S(?:.*?\S)?)[*_](?![\w*])""")

private fun plainInline(line: String): String =
    line.replace(inlineLink, "$1").replace(inlineMarks, "").replace(inlineEmphasis, "$1").trim()
