package com.dynamicruntime.common.gedra

import com.dynamicruntime.common.schema.SCH
import com.dynamicruntime.common.schema.SCT
import com.dynamicruntime.common.schema.SchTypeBuilder
import com.dynamicruntime.common.util.deepClone

/** The fields an *edit* carries that a stored entry does not (issue #337). */
@Suppress("ConstPropertyName")
object GED {
    /**
     * What is being asked of the entry — the verb, and the reason a patch is not an update.
     *
     * It sits beside `traitId` rather than inside the entry, because an action is not a property of an entry.
     * An entry is a value; "delete this entry" is a different thing said *about* one. See `gedra-patch.md`.
     */
    const val action = "action"
}

/**
 * What a patch may ask of one entry (issue #337).
 *
 * Each name says what happens when the entry is **not** already there, which is the half a caller most often
 * gets wrong and the half a bare `delete` / `merge` / `replace` would leave unsaid. None of the three is an
 * error against an absent entry; they simply differ in what they then do.
 *
 * An enum rather than string constants because the code exhausts it in a `when`, and because
 * `SchTypeBuilder.options` builds the schema's choice list straight from the entries — so the validator, the
 * form's dropdown and the `when` cannot come to disagree about what the verbs are.
 */
@Suppress("EnumEntryName")
enum class GedraEditAction {
    /** Remove the entry if it is there; do nothing if it is not. Never an error. */
    deleteOrNoOp,

    /** Merge the supplied keys into the stored entry or create it when there is none. */
    addOrMerge,

    /** Replace the stored entry wholesale or create it when there is none. */
    addOrReplace,
}

/**
 * What one edit does to the entry it addresses (issue #909, phase D): the shared core of a data patch
 * (`GedraDataService.applyEdit`) and a config patch (`applyConfigSlotEdits`), which apply the same three actions
 * by the same addressing rule. Each side keeps its own concerns around it -- the data side its `entryId` staleness
 * check, its `g-visibleWhen` gate, unchanged-is-no-op and stamps; the config side its slots.
 */
sealed interface KeyedEdit {
    /** A delete of an entry that is not there: nothing to do, and not an error. */
    data object NoOp : KeyedEdit

    /** Remove the entry. */
    data object Remove : KeyedEdit

    /** Store [data] as the entry's data, creating it when there was none. */
    class Put(val data: Map<String, Any?>) : KeyedEdit
}

/**
 * This action applied to the entry an edit addresses: [existing] is that entry's **data** (null when there is
 * none), [supplied] the edit's. A merge folds the supplied keys over what is stored -- keys, not a deep merge,
 * since a page owns the answers it shows and says nothing about the rest; a replace takes the supplied data
 * whole; a delete removes what is there.
 */
fun GedraEditAction.applyTo(existing: Map<String, Any?>?, supplied: Map<String, Any?>): KeyedEdit = when (this) {
    GedraEditAction.deleteOrNoOp -> if (existing == null) KeyedEdit.NoOp else KeyedEdit.Remove
    GedraEditAction.addOrReplace -> KeyedEdit.Put(supplied)
    GedraEditAction.addOrMerge -> KeyedEdit.Put(existing.orEmpty() + supplied)
}

/**
 * The fields of the key [pkFields] that an edit's [data] does not supply. The entry an edit names is its
 * primary-key values, carried in its own data -- so an edit missing any of them names no entry, and must be
 * refused rather than silently no-op a delete or add a keyless entry. Empty for an unkeyed trait or slot.
 */
fun missingKeyFields(pkFields: List<String>, data: Map<String, Any?>?): List<String> =
    pkFields.filter { data?.get(it) == null }

/**
 * Whether [entryData] is the entry an edit carrying [data] addresses: every field of [pkFields] equal, compared
 * as [canonicalKey] does, so a year keyed as `2024` is the one an edit naming `2024.0` means. An empty key -- a
 * single-instance trait or slot -- addresses the one entry there is.
 */
fun addressesEntry(pkFields: List<String>, entryData: Map<String, Any?>, data: Map<String, Any?>): Boolean =
    pkFields.all { canonicalKey(entryData[it]) == canonicalKey(data[it]) }

/**
 * The fields every edit carries whatever its trait: the verb, and which entry is meant.
 *
 * Declared once so a manufactured branch cannot end up with a different envelope from its siblings — the same
 * reason `storedEntryFields` exists for the stored shape.
 *
 * Neither `entryId` nor `data` is required, and both absences are meaningful rather than lax:
 *
 * - **`entryId` absent** means "the entry this trait (and key) names, or a new one". A gedra holds at most one
 *   entry per trait -- or per primary-key value, for a trait with a `g-primaryKey` (issue #487) -- so the entry
 *   an edit names is `(traitId, data[primaryKey])`, which the edit's own data carries. `entryId`, when sent,
 *   stays a staleness check: it has to match the entry that address resolves to.
 * - **`data` absent** is what a [GedraEditAction.deleteOrNoOp] sends. One branch cannot say "required unless
 *   the action is a delete", so the schema permits it, and the service refuses data-less adds — which is also
 *   where a merge's completeness is settled, for the same reason.
 */
fun SchTypeBuilder.editEnvelopeFields() {
    property(GED.action, "What to do with this entry.", required = true) {
        options(GedraEditAction.entries)
    }
    // `GE.entryId` rather than a name of its own: an edit names the same field a stored entry carries, and
    // spelling it twice is how the two would come to differ.
    property(GE.entryId, "Which entry is meant; absent means the one this trait names, or a new one.")
}

/**
 * The `data` an edit carries — typed by the trait when the trait is known, and an open object when it is not.
 *
 * A known trait's [schema] is copied rather than referenced, and deep-cloned on the way in, so a branch cannot
 * be mutated through the trait it was built from. Where that schema is itself a `$ref`, the ref travels, and
 * both the entry type and this one resolve to the same target — one definition, two users, nothing to drift.
 *
 * It is a **fragment** ([SCH.optionalContents], issue #487): the validator checks its fields but not its
 * `required`. An edit sends only what it means to change -- a delete carries its key alone, a merge the fields
 * its page owns -- so demanding a complete object on the way in is what stopped a keyed trait with any other
 * required field from being deleted at all. Completeness is settled instead where the edit is folded into the
 * stored entry (`GedraDataService.checkStoredEntries`), which validates the assembled whole with `required`
 * on. This marks only the edit union's copy; the entry union (a create) still requires a complete object,
 * because the shared target type keeps its `required` and only this property waives it.
 */
fun SchTypeBuilder.editDataProperty(schema: Map<String, Any?>?) {
    property(GE.data, "The data this edit supplies; absent for a delete.") {
        if (schema != null) data.putAll(schema.deepClone()) else type = SCT.kObject
        data[SCH.optionalContents] = true
    }
}
