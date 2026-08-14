package com.dynamicruntime.common.gedra

import com.dynamicruntime.common.exception.KdrException
import com.dynamicruntime.common.schema.SCH
import com.dynamicruntime.common.schema.SCT
import com.dynamicruntime.common.schema.SchTypeBuilder
import com.dynamicruntime.common.schema.SchTypesBuilder
import kotlin.time.Instant

/**
 * The field names an entry carries outside its trait's own data (issue #297).
 *
 * These are the **envelope**, and the reason [data] exists at all: the envelope is expected to grow —
 * `gedra-entry.md` still has `origin`, `lockedBy`, `createdBy` and `updatedBy` to add — and in a flat entry
 * every one of those additions would be a silent breaking change to any trait that already used that field
 * name. With the trait's fields one level down, the envelope can grow forever and no trait author notices.
 *
 * The response envelope made the same call for the same reason: `results` / `item` / `items` keep a handler's
 * payload clear of `requestUri`, `duration` and everything since.
 */
@Suppress("ConstPropertyName")
object GE {
    /** Which trait this entry instantiates — the discriminator, and the one envelope field a caller sends. */
    const val traitId = "traitId"

    /** The trait's own data. Always a map; see [traitEntry]. */
    const val data = "data"

    /** Stable id of this entry, assigned when stored. */
    const val entryId = "entryId"

    /** How the current value came to be. */
    const val source = "source"

    /** When the entry was first written. */
    const val createdAt = "createdAt"

    /** When the entry was last changed. */
    const val updatedAt = "updatedAt"

    /**
     * Which kinds of gedra may carry this entry — a type-level keyword on a generated entry type, holding
     * kind *names* (`formDoc`), not the two-letter abbreviations, which exist only to keep a [GedraId] short.
     *
     * **Absent means the type is not a trait entry at all**, rather than meaning every kind. Spelling a
     * wildcard as omission would make the universal case the accidental one, and a trait that applies to
     * everything is exactly what prior art found was not justified. A wildcard, if it is ever wanted, gets
     * written out loudly.
     *
     * A `g-` keyword, so [SCH.gPrefix]'s export rule governs it: stripped by default, and dropping it can
     * make an export neither stricter nor looser, since it bears on where an entry may be *used* rather than
     * on whether one is valid. Keeping it on export is a decision for whoever builds the export.
     *
     * Deliberately not surfaced on `SchType`. The schema layer does not need to understand a gedra concept
     * to carry one: the parser ignores keywords it does not know, and the raw defs ride along in the schema
     * store, so assembly reads it from there.
     */
    const val appliesTo = "g-appliesTo"
}

/**
 * Values for an entry's [GE.source] -- who is accountable for what it currently holds (issue #310).
 *
 * One value so far, because a source is minted by whatever channel produced the value, and only one channel
 * exists. `excel` and its kind arrive with the imports that write them; this is not a closed set waiting to be
 * filled in, which is also why it is constants rather than an enum -- a client's integration will have its own
 * and cannot edit ours.
 *
 * The field is **mutable**, unlike an entry's origin: an approval flips it in bulk across every trait the
 * approval covers, which is what makes "which values has nobody vouched for" a plain equality filter. See
 * `gedra-entry.md`.
 */
@Suppress("ConstPropertyName")
object GSRC {
    /** A person is accountable: they entered it, edited it, or approved it. What a direct API call sets. */
    const val user = "user"
}

/**
 * Declares the entry type for one trait: a branch of the entry union, with the trait's own schema under
 * [GE.data] and the stored envelope around it (issue #297).
 *
 * ```json
 * {"traitId": "name", "data": {"name": "My Expense Form"}}
 * ```
 *
 * [dataSchema] configures the `data` property, so both authoring styles go through one function — declare the
 * shape inline, or `ref("some.Type")` an existing one. What it may **not** do is make `data` anything but an
 * object: an entry's data is a map, and a trait declaring it a string or an array is refused here, where the
 * author is, rather than at the first payload that fails to validate.
 *
 * The known gap: a `ref` whose target turns out not to be an object cannot be caught at build time, because
 * the target is resolved later. That check belongs where types are compiled.
 *
 * `data` is **required**. An entry with a trait and no data says nothing, and Cedar allowed it and regretted
 * it. A trait whose fields are all optional still satisfies this with an empty map, which is the honest way
 * to say "nothing supplied yet".
 *
 * [appliesTo] is the set of gedra kinds that may carry this entry, emitted onto the type as [GE.appliesTo].
 * A *set* rather than one kind, because a trait like `name` genuinely means the same thing on a form document
 * and on workflow data — and the alternative, a `name` beside a `wfDataName`, is two names for one concept.
 * Where two traits really are different concepts they get different ids, which is the right answer anyway.
 */
fun SchTypesBuilder.traitEntry(
    name: String,
    traitId: String,
    appliesTo: Set<GedraDataType>,
    description: String? = null,
    dataSchema: SchTypeBuilder.() -> Unit,
) {
    if (appliesTo.isEmpty()) {
        throw KdrException.mkConv(
            "Trait '$traitId' says it applies to no kind of gedra, so nothing could ever carry it. Name the " +
                "kinds it attaches to.",
        )
    }
    // A branch, not a bare type: the discriminator has to stay top-level for the union to select on it, which
    // is exactly what `variantBranch` declares -- including the `const` that keeps the branch valid to a
    // reader that ignores the `discriminator` keyword entirely.
    variantBranch(name, GE.traitId, traitId, description) {
        // Kind names, not abbreviations: this is a document meant to be read, and the enum name is the
        // settled vocabulary. Sorted so rebuilding the same trait produces the same bytes.
        data[GE.appliesTo] = appliesTo.map { it.name }.sorted()
        property(GE.data, "This entry's own data, as its trait defines it.", required = true) {
            dataSchema()
            holdDataToAnObject(traitId)
        }
        storedEntryFields()
    }
}

/**
 * The fields every stored entry carries and no caller supplies: an id, how the value came to be, and when it
 * was written.
 *
 * All of them are `g-derived`, which is what makes this the *stored* shape rather than the *sent* shape —
 * absent from the input schema, undrawn by the form, dropped if a client echoes them back, and required on
 * the way out. Declared once here rather than per trait, so a new trait cannot end up with a different
 * envelope from its siblings.
 *
 * `createdAt` / `updatedAt` deliberately spell it the way the SQL layer's audit columns already do (`PF`),
 * because an entry is meant to read like a row — and `modifiedAt` up here against `updatedAt` one layer down
 * would be two names for one idea.
 */
fun SchTypeBuilder.storedEntryFields() {
    property(GE.entryId, "Stable id of this entry; assigned when stored.", required = true) { derived = true }
    property(GE.source, "How the current value came to be.", required = true) { derived = true }
    property(GE.createdAt, "When the entry was first written.", required = true) {
        dateTime()
        derived = true
    }
    property(GE.updatedAt, "When the entry was last changed.", required = true) {
        dateTime()
        derived = true
    }
}

/**
 * This entry with the stored envelope stamped onto it — what storing it would leave behind.
 *
 * [updatedAt] defaults to [createdAt] because a record that has never been changed was last changed when it
 * was written. Storage is what makes the two diverge, and an entry carrying only one of them cannot answer
 * "has this been touched since it arrived", which is the question the pair exists for.
 *
 * The instants stay [Instant] all the way into the map rather than being formatted here. Serialization is
 * where a date becomes text — `JsonUtil` writes it through `fmt`, the one place that decides the wire format
 * — and the validator already recognizes an `Instant` as the shape a `date-time` field declares. Formatting
 * at the call site would put that decision in every handler that ever stamps an entry, which is how one of
 * them ends up with microseconds.
 */
fun Map<String, Any?>.asStoredEntry(
    entryId: String,
    source: String,
    createdAt: Instant,
    updatedAt: Instant = createdAt,
): Map<String, Any?> = this + linkedMapOf<String, Any?>(
    GE.entryId to entryId,
    GE.source to source,
    GE.createdAt to createdAt,
    GE.updatedAt to updatedAt,
)

/**
 * Holds a trait's `data` to an object: absent means object, an explicit object is fine, and anything else is
 * refused. A `$ref` passes through untouched, since what it points at is not known until types are compiled.
 */
private fun SchTypeBuilder.holdDataToAnObject(traitId: String) {
    if (SCH.dRef in data) {
        return
    }
    when (val declared = data[SCH.type]) {
        null -> type = SCT.kObject
        SCT.kObject -> {}
        else -> throw KdrException.mkConv(
            "Trait '$traitId' declares its '${GE.data}' as '$declared'. An entry's data is always a map, so " +
                "that it can carry named fields beside an envelope that grows -- a trait wanting a single " +
                "value gives it a name and puts it in the map.",
        )
    }
}
