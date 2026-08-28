package com.dynamicruntime.common.gedra

import com.dynamicruntime.common.exception.KdrException
import com.dynamicruntime.common.schema.SCH
import com.dynamicruntime.common.schema.SCT
import com.dynamicruntime.common.schema.SchTypeBuilder
import com.dynamicruntime.common.schema.SchTypesBuilder
import com.dynamicruntime.common.schema.isScalarType
import com.dynamicruntime.common.schema.typeRefPath
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
     * Numeric userId of whoever first wrote this entry (issue #325).
     *
     * The **actor**, not the owner — who did the editing, as distinct from whose gedra it is. The two differ
     * whenever an administrator edits somebody else's document, which is the case the field exists for; the
     * owner is already recorded once on the row and would say nothing new here.
     */
    const val createdBy = "createdBy"

    /** Numeric userId of whoever last changed this entry — the actor again; see [createdBy]. */
    const val updatedBy = "updatedBy"

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
 * The type holding a trait's own data, named from the entry type that carries it: `NameEntry` becomes
 * `NameData` (issue #379).
 *
 * Derived by one rule rather than given a naming scheme of its own, so the pair reads as a pair -- and so
 * that nothing has to be told the name twice. A type not ending in `Entry` simply gains the suffix.
 */
fun traitDataTypeName(entryTypeName: String): String = entryTypeName.removeSuffix("Entry") + "Data"

/**
 * Declares the entry type for one trait: a branch of the entry union, with the trait's own schema under
 * [GE.data] and the stored envelope around it (issue #297).
 *
 * ```json
 * {"traitId": "name", "data": {"name": "My Expense Form"}}
 * ```
 *
 * *dataSchema* configures the `data` property, so both authoring styles go through one function — declare the
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
 * *appliesTo* is the set of gedra kinds that may carry this entry, emitted onto the type as [GE.appliesTo].
 * A *set* rather than one kind, because a trait like `name` genuinely means the same thing on a form document
 * and on workflow data — and the alternative, a `name` beside a `wfDataName`, is two names for one concept.
 * Where two traits really are different concepts they get different ids, which is the right answer anyway.
 */
fun SchTypesBuilder.traitEntry(
    name: String,
    traitId: String,
    appliesTo: Set<GedraDataType>,
    description: String? = null,
    primaryKey: List<String> = emptyList(),
    dataSchema: SchTypeBuilder.() -> Unit,
): Map<String, Any?> {
    if (appliesTo.isEmpty()) {
        throw KdrException.mkConv(
            "Trait '$traitId' says it applies to no kind of gedra, so nothing could ever carry it. Name the " +
                "kinds it attaches to.",
        )
    }
    // The trait's data is a **named type**, and its entry refers to it (issue #379). Three things follow,
    // and the first is what forced it:
    //
    //  - **A client can narrow it.** An overlay reaches a type's own keys and its property set and stops, so
    //    a client altering a trait would otherwise have to restate the generated envelope -- `traitId`,
    //    `data`, and whatever `storedEntryFields` currently adds -- none of which the trait's author wrote,
    //    and any later addition to which every client would silently drop. Naming the data makes it a type
    //    somebody can target, which is the same reason any interior structure gets a name here.
    //  - **One alteration reaches both unions.** The edit union builds its `data` from what this returns, so
    //    a narrowed data type narrows what may be stored *and* what may be asked for, from one declaration.
    //  - It exports more simply, which is prior art rather than a discovery: Cedar named these for the same
    //    reason, partly to keep a Swagger export from inlining the same shape at every use.
    val declared = SchTypeBuilder(cxt, namespace).apply {
        dataSchema()
        holdDataToAnObject(traitId)
    }.data
    // The primary key goes on the data type, alongside its `required` (issue #487), and every field it names is
    // checked here where the author is. A keyed trait declares its data **inline**: the key and the fields it
    // names have to live on one type, which a bare `$ref` -- resolved only when types compile -- cannot give.
    if (primaryKey.isNotEmpty()) {
        if (SCH.dRef in declared) {
            throw KdrException.mkConv(
                "Trait '$traitId' declares a primary key, but its data is a \$ref. A keyed trait has to declare " +
                    "its data inline, so the key and the fields it names are one type; put the key on the " +
                    "referenced type instead.",
            )
        }
        val props = declared[SCH.properties] as? Map<*, *> ?: emptyMap<Any?, Any?>()
        val required = (declared[SCH.required] as? List<*>)?.map { it.toString() }?.toSet().orEmpty()
        for (field in primaryKey) {
            val prop = props[field] as? Map<*, *>
                ?: throw KdrException.mkConv(
                    "Trait '$traitId' names '$field' in its primary key, but its data has no such field. A key " +
                        "has to be one of the trait's own fields.",
                )
            if (field !in required) {
                throw KdrException.mkConv(
                    "Trait '$traitId' names '$field' in its primary key, but that field is not required. A key " +
                        "that can be omitted cannot tell two entries apart, so it has to be required.",
                )
            }
            if (!isScalarType(prop[SCH.type] as? String)) {
                throw KdrException.mkConv(
                    "Trait '$traitId' names '$field' in its primary key, but that field is not a scalar. A key " +
                        "is compared by value, so it has to be a string, number, integer or boolean.",
                )
            }
            if (prop[SCH.derived] == true) {
                throw KdrException.mkConv(
                    "Trait '$traitId' names the derived field '$field' in its primary key. Identity that " +
                        "depends on a computation re-partitions stored data whenever the computation changes.",
                )
            }
        }
        declared[SCH.primaryKey] = primaryKey
    }
    // A trait whose data is **already** a named type keeps that name. Manufacturing a second one for the same
    // shape would at best duplicate it and at worst collide: `NameEntry` derives `NameData`, which is exactly
    // what an author naming their own data type would have called it -- and the generated wrapper would have
    // replaced theirs with a reference to itself.
    val builtData: Map<String, Any?> = if (SCH.dRef in declared) {
        declared
    } else {
        val dataName = traitDataTypeName(name)
        type(dataName) { data.putAll(declared) }
        linkedMapOf(SCH.dRef to typeRefPath(dataName, namespace))
    }
    // A branch, not a bare type: the discriminator has to stay top-level for the union to select on it, which
    // is exactly what `variantBranch` declares -- including the `const` that keeps the branch valid to a
    // reader that ignores the `discriminator` keyword entirely.
    variantBranch(name, GE.traitId, traitId, description) {
        // Kind names, not abbreviations: this is a document meant to be read, and the enum name is the
        // settled vocabulary. Sorted so rebuilding the same trait produces the same bytes.
        data[GE.appliesTo] = appliesTo.map { it.name }.sorted()
        property(GE.data, "This entry's own data, as its trait defines it.", required = true) {
            data.putAll(builtData)
        }
        storedEntryFields()
    }
    return builtData
}

/**
 * The fields every stored entry carries and no caller supplies: an id, how the value came to be, and when and
 * by whom it was written.
 *
 * All of them are `g-derived`, which is what makes this the *stored* shape rather than the *sent* shape —
 * absent from the input schema, undrawn by the form, dropped if a client echoes them back, and required on
 * the way out. Declared once here rather than per trait, so a new trait cannot end up with a different
 * envelope from its siblings.
 *
 * The four audit fields deliberately spell it the way the SQL layer's audit columns already do (`PF`), because
 * an entry is meant to read like a row — and `modifiedAt` up here against `updatedAt` one layer down would be
 * two names for one idea. The resemblance is now the whole set rather than half of it (issue #325): a date
 * without an actor answers *when* something changed and leaves *who* to be inferred from a log, which is
 * exactly the question an audit asks first.
 *
 * **Per entry, not per gedra**, and that is the point of putting them here. The row's own audit columns record
 * the last write to the *document*; these record who touched each trait's worth of data, so an approval that
 * changed one entry does not read as having rewritten the rest.
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
    property(GE.createdBy, "Numeric userId that first wrote this entry.", required = true) {
        type = SCT.integer
        derived = true
    }
    property(GE.updatedBy, "Numeric userId that last changed this entry.", required = true) {
        type = SCT.integer
        derived = true
    }
}

/** The separator between an entry key's parts -- a space, which cannot occur in a trait id or a scalar value. */
private const val entryKeySep = " "

/**
 * The map key an entry is addressed by (issue #487): its [traitId] alone when the trait is single-instance, or
 * `traitId` plus each primary-key field's value in the key's declared order when the trait is keyed. Pass an
 * empty [pkValues] for an unkeyed trait.
 */
fun entryKey(traitId: String, pkValues: List<Any?>): String =
    if (pkValues.isEmpty()) traitId
    else traitId + pkValues.joinToString("") { entryKeySep + canonicalKey(it) }

/**
 * A primary-key value as a stable string for keying and comparison (issue #487). A number is normalized to its
 * integral or decimal form, so the same year read back as `Int` or `Long` keys the same entry, and `"2024"` and
 * `2024` are one key once coerced; anything else is its own text.
 */
fun canonicalKey(value: Any?): String = when (value) {
    is Number -> {
        val d = value.toDouble()
        if (d == d.toLong().toDouble()) d.toLong().toString() else d.toString()
    }
    else -> value.toString()
}

/**
 * The ordered primary-key values [entry] carries for a trait keyed on [pkFields] -- one per field, in order.
 * Empty [pkFields] gives an empty list (a single-instance trait). A keyed entry that omits any key field throws:
 * it could not be told apart from another of the same trait.
 */
fun entryKeyValues(entry: Map<String, Any?>, traitId: String, pkFields: List<String>): List<Any?> {
    if (pkFields.isEmpty()) return emptyList()
    val data = entry[GE.data] as? Map<*, *> ?: emptyMap<Any?, Any?>()
    return pkFields.map { field ->
        data[field] ?: throw KdrException.mkInput(
            "An entry of trait '$traitId' supplies no value for its primary key '$field', so it could not be " +
                "told apart from another entry of the same trait.",
        )
    }
}

/**
 * Refuses a set of entries that carries two with the same address (issue #337, #487).
 *
 * A gedra holds at most one entry per trait -- unless the trait declares a `g-primaryKey`, and then one per
 * distinct value of that key. That is the rule the whole addressing story rests on: it lets a patch name an
 * entry by its trait (and key), and makes "an edit with no entryId" mean update the one that is there, or
 * create the first. Two entries sharing an address would make both questions ambiguous.
 *
 * [pkFieldsOf] answers, per trait id, the ordered fields of its primary key (empty for a single-instance
 * trait). A keyed entry that omits a key field is refused too. Enforced here -- one guard for every write path
 * -- rather than at each caller, since a path that forgot it would corrupt quietly.
 */
fun checkEntryKeys(entries: List<Map<String, Any?>>, pkFieldsOf: (String) -> List<String>) {
    val seen = mutableSetOf<String>()
    for (entry in entries) {
        val traitId = entry[GE.traitId] as? String ?: continue
        val pkFields = pkFieldsOf(traitId)
        val pkValues = entryKeyValues(entry, traitId, pkFields)
        if (!seen.add(entryKey(traitId, pkValues))) {
            throw KdrException.mkInput(
                if (pkFields.isEmpty()) {
                    "Trait '$traitId' appears twice among these entries. A gedra holds at most one entry per " +
                        "trait, so there would be no way to say afterward which of the two was meant."
                } else {
                    "Two entries of trait '$traitId' share the primary key (${pkFields.joinToString(", ")}) = " +
                        "(${pkValues.joinToString(", ") { canonicalKey(it) }}). Each entry of a keyed trait " +
                        "has to have a distinct key."
                },
            )
        }
    }
}

/**
 * This entry with the stored envelope stamped onto it — what storing it would leave behind.
 *
 * [updatedAt] and [updatedBy] default to their `created` counterparts because a record that has never been
 * changed was last changed when it was written, by whoever wrote it. Storage is what makes each pair diverge,
 * and an entry carrying only one half cannot answer "has this been touched since it arrived, and by whom",
 * which is the question the pairs exist for.
 *
 * [createdBy] is the **actor** — the acting user, `KdrCxt.userProfile.userId` — rather than the gedra's owner.
 * The SQL layer draws the same distinction for the same columns (`SqlTopicUtil.prepForStdExecute` takes
 * `createdBy` from the actor and the ownership columns from the bound owner), and it is the one that carries
 * information: the owner is recorded once on the row, so repeating it per entry would say nothing, while the
 * actor is what differs when an administrator edits somebody else's document.
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
    createdBy: Long,
    updatedAt: Instant = createdAt,
    updatedBy: Long = createdBy,
): Map<String, Any?> = this + linkedMapOf<String, Any?>(
    GE.entryId to entryId,
    GE.source to source,
    GE.createdAt to createdAt,
    GE.updatedAt to updatedAt,
    GE.createdBy to createdBy,
    GE.updatedBy to updatedBy,
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
