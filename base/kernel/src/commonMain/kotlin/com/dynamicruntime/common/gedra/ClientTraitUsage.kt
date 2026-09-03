package com.dynamicruntime.common.gedra

/**
 * How a client says a trait should **present** in surfaces other than its own form (issue #537) -- the first
 * thing a client's definition changes about a page it does not own: the forms *list*.
 *
 * A usage names a trait, a **display expression** that pulls a value out of that trait's stored data (a string
 * script -- substitution only for now, e.g. `${'$'}{name}`), a **label** for the column it drives, and a
 * **value kind**. The kind is declared now and read later: the search endpoint (issue #538) treats a `number`
 * or `date` value as a range and a `string` as text, and it is cheaper to declare the kind with the display
 * than to infer it when search arrives.
 *
 * Pure model in `base/kernel`, beside the config that carries it; the value is *computed* on the backend (it
 * needs a row's data), so a client never authors the presented string, only the rule that makes it.
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
    /** How the value reads -- text, a number, or a date; what a later search treats it as. */
    val kind: UsageKind = UsageKind.string,
)

/** The kinds a [ClientTraitUsage] value can be: what a listing shows and a search (issue #538) filters on. */
@Suppress("EnumEntryName")
enum class UsageKind { string, number, date }

/**
 * The field names of one **computed** display value on a stored-row's wire map (issue #537) -- what the list
 * and read endpoints attach per row under [GDF.displayValues], and the forms table reads to build its columns.
 */
@Suppress("ConstPropertyName")
object UF {
    /** The trait the value came from -- the stable column key. */
    const val traitId = "traitId"

    /** The column header, from the usage's label. */
    const val label = "label"

    /** The presented value, already evaluated; empty when the row carries no such trait. */
    const val value = "value"

    /** How the value reads (`UsageKind`), for a search to filter on. */
    const val kind = "kind"
}
