package com.dynamicruntime.common.gedra

/**
 * How a client says a trait should **present** in surfaces other than its own form (issue #537) -- the first
 * thing a client's definition changes about a page it does not own: the forms *list*.
 *
 * A usage names a trait, a **display expression** that pulls a value out of that trait's stored data (a string
 * script -- substitution only for now, e.g. `${'$'}{name}`), a **label** for the column it drives, and optionally a
 * **value kind**. How the column is searched (issue #538) is its [ColumnSearchShape]: a `number` or `date` value as a
 * range and a `string` as text ([gedraSearchParams]). A display that is a lone `${'$'}{field}` takes its shape from the
 * field's schema (issue #1508), so it needs no kind; a computed one is text unless it declares one.
 *
 * Pure model in `base/kernel`, beside the config that carries it; the value is *computed* on the backend (it
 * needs a row's data), so a client never authors the presented string, only the rule that makes it.
 *
 * A trait may have several columns (issue #1490): what tells them apart is the column's own name, [columnKey] --
 * its [columnId], else its trait id -- which names its search parameters, its sort, and its value on a row. The
 * stored entry is keyed by trait and label, so two columns of one trait differ in their headings.
 */
class ClientTraitUsage(
    /** The trait whose data this presents. */
    val traitId: String,
    /** The column header this drives in a listing. */
    val label: String,
    /**
     * The string-script expression evaluated against the trait's own data to produce the presented value --
     * `${'$'}{name}` pulls the `name` field. Substitution only in this issue; computed and comparison forms
     * arrive with the workflow function work.
     */
    val display: String,
    /**
     * How the value reads -- text, a number, or a date -- when the usage says (issue #1508). Null takes it from the
     * field a lone `${'$'}{field}` display reads, and is text for a computed display ([resolveUsageColumn]). One that
     * contradicts its field is a configuration problem, and the declared kind is kept.
     */
    val kind: UsageKind? = null,
    /**
     * For a `string` kind, whether the search (issue #538) also offers a **substring** parameter beside the
     * exact one -- two ways to find the value. Ignored for `number` and `date`, which are always searched as a
     * range. Off by default: an exact match is the cheaper promise, and a client asks for the contains variant
     * only where partial-name lookup is worth it.
     */
    val substring: Boolean = false,
    /**
     * How the cell **reads** (issue #1323): a string script over the trait's data, with the column's value as
     * `value`, evaluated on the frontend in the client's zone and against the table's clock -- so it can compose
     * several fields, add a unit, write a currency, or say a date relative to now, while the column still sorts and
     * searches by its value. Null shows the value, except that a `date` column reads relative to now
     * ([columnPresentation]).
     */
    val presentation: String? = null,
    /**
     * The column's own name (issue #1490), when it is not the trait id: needed only on a second column of the same
     * trait, whose search parameters and sort would otherwise share the first's. An identifier, since it names
     * query parameters.
     */
    val columnId: String? = null,
) {
    /**
     * The column's machine name (issue #1490): [columnId], else the trait id. What its search parameters are named
     * from, what its sort and its value on a row are keyed by -- so a column without a [columnId] keeps the names it
     * always had.
     */
    val columnKey: String get() = columnId ?: traitId
}

/**
 * What a [ClientTraitUsage.columnId] must look like (issue #1490): letters, digits, and underscores, starting with a
 * letter -- since it names query parameters and a sort key.
 */
const val columnIdPattern = "^[A-Za-z][A-Za-z0-9_]*$"

/**
 * The kinds a [ClientTraitUsage] value can be: what a listing shows and a search (issue #538) filters on. A `boolean`
 * (issue #1508) is searched by one yes-or-no parameter and reads as Yes or No.
 */
@Suppress("EnumEntryName")
enum class UsageKind { string, number, date, boolean }

/**
 * The field names of one **computed** display value on a stored-row's wire map (issue #537) -- what the list
 * and read endpoints attach per row under [GDF.displayValues], and the forms table reads to build its columns.
 */
@Suppress("ConstPropertyName")
object UF {
    /** The trait the value came from. */
    const val traitId = "traitId"

    /** The column's machine name ([ClientTraitUsage.columnKey], issue #1490): what a row's value and a column are keyed by. */
    const val columnId = "columnId"

    /** The column header, from the usage's label. */
    const val label = "label"

    /**
     * The column's value, already evaluated from the usage's `display`; empty when the row carries no such trait. What
     * the column sorts and searches by (issue #1323), and what its presentation reads as `value`.
     */
    const val value = "value"

    /** How the value reads (`UsageKind`), for a search to filter on. */
    const val kind = "kind"

    /** Whether a `string` usage also offers a contains search ([ClientTraitUsage.substring]); on the usage's own
     *  wire projection, not a display value. Here so the one usage-field object owns every usage key. */
    const val substring = "substring"

    /** A listing column's presentation ([ClientTraitUsage.presentation]), on the listing's [GDF.columns] (issue #1323). */
    const val presentation = "presentation"

    /**
     * A listing column's choices, on the listing's [GDF.columns] (issue #1500): each a `{value, label}`, deduced from
     * the field a lone `${field}` display reads. What its filter suggests and its cells name; absent when there are none.
     */
    const val choices = "choices"
}
