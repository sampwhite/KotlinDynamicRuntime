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
