package com.dynamicruntime.sample.gedra

import com.dynamicruntime.common.context.KdrCxt
import com.dynamicruntime.common.endpoint.HttpMethod
import com.dynamicruntime.common.endpoint.SchModule
import com.dynamicruntime.common.endpoint.schemaModule
import com.dynamicruntime.common.schema.SCT
import com.dynamicruntime.common.schema.SchTypeBuilder
import com.dynamicruntime.common.startup.ServiceInitializer
import kotlin.time.Instant
import com.dynamicruntime.common.util.toJsonListOrEmpty
import com.dynamicruntime.common.util.toJsonMapOrEmpty
import com.dynamicruntime.common.util.toOptStr

/**
 * A working sketch of the **Gedra entry** described in `gedra-entry.md`, and nothing more: it stores nothing,
 * persists nothing, and is not the entity store. It exists to exercise the schema constructs that model an
 * entry — a discriminated union today (issue #252), a conditional and a projection as those land — because
 * nothing else in the codebase does, which is the same bar the file endpoints clear and the reason issue #243
 * removed the demo and todo endpoints that cleared no bar at all.
 *
 * Named for what it is rather than for what it prototypes, so nobody later mistakes it for the real `Gedra` --
 * and the **paths** say the same thing, under a `gedraSketch` section rather than `gedra`. Two reasons, and
 * the second is the one that bites: `gedra` is a name with a future, so a fixture holding it collides with the
 * day a real endpoint wants to save an entry. The verb is held to the same standard: this fills entries out
 * and returns them, so it is `fillOut` and not `save`, which would be a plain untruth in the first place a
 * reader looks. A convention for fixtures generally is issue #270.
 * The trait vocabulary is deliberately the design document's, since the point is to find out whether that
 * document's shapes survive contact with the form and the validator.
 */
class GedraSketchService : ServiceInitializer {
    override val serviceName: String = GedraSketchService.serviceName

    @Suppress("ConstPropertyName")
    companion object {
        const val serviceName = "GedraSketchService"

        /**
         * The entry types and the one endpoint that echoes an entry back, in the `gedra` namespace.
         *
         * The union is reached through a **property** rather than as the input itself: endpoint input is a
         * flat set of top-level fields, and a union has no top-level fields of its own to flatten — its
         * properties live on whichever branch was selected.
         */
        fun schema(cxt: KdrCxt): SchModule = schemaModule(cxt, "gedra") {
            variantBranch(
                "ExpenseReportEntry", GS.traitId, GS.expenseReport,
                "An expense report for one reporting year.",
            ) {
                property(GS.year, "Reporting year this report covers.", required = true) {
                    type = SCT.integer
                    minimum = 2000
                    maximum = 2100
                }
                property(GS.perItemAmount, "Amount claimed for one item.") { type = SCT.number }
                property(GS.itemCount, "How many items are claimed.") { type = SCT.integer }
                // Derived (issue #254): the caller does not supply this, so it is not accepted from them and
                // the form offers no box for it. The handler computes it and the response carries it.
                //
                // The trait deliberately has ONE total. An earlier version kept an ordinary `totalAmount`
                // beside a derived `totalGallons`, and the two were mistaken for each other on sight -- a
                // computed value appearing in a response reads as the value you typed being overwritten when
                // a similarly named field is sitting next to it.
                property(GS.totalAmount, "Total claimed; computed from the two above, not supplied.") {
                    type = SCT.number
                    derived = true
                }
                property(GS.notes, "Free-text explanation.")
                storedFields()
            }
            variantBranch(
                "ApprovalEntry", GS.traitId, GS.managerApproval,
                "A manager's decision on what was submitted.",
            ) {
                property(GS.approved, "Whether it was approved.", required = true) { type = SCT.boolean }
                property(GS.decidedBy, "Who decided.")
                property(GS.rejectionReason, "Why it was rejected.")
                // The conditional case (issue #253), inside a union branch, so the two mechanisms are exercised
                // in one payload: a reason is required when the decision is a rejection, and inadmissible when
                // it is not. `presentWhen` emits the `if`/`then`/`else` triple, including the `required` inside
                // the `if` that stops an absent `approved` from demanding a reason.
                presentWhen(GS.rejectionReason, on = GS.approved, value = false)
                storedFields()
            }
            // Not `variantBranch`: a catch-all must not declare a `const`, or it rejects the very value it is
            // there to accept. See `variantDefault`.
            variantDefault("OpaqueEntry", GS.traitId, "An entry whose trait this deployment does not know.")

            variantType(
                "GedraEntry",
                "One schema-defined unit stored on a Gedra, selected by its traitId.",
                on = GS.traitId,
                branches = listOf("ExpenseReportEntry", "ApprovalEntry"),
                // Trait definitions are authored at runtime, so a reader meeting one it has never heard of is
                // an ordinary event: the entry stays readable instead of taking the payload down.
                defaultBranch = "OpaqueEntry",
            )

            type("EntryEcho") {
                type = SCT.kObject
                property(GS.traitId, "The trait the entry claimed.", required = true)
                property(GS.branch, "Which branch validated it, or 'default' when none matched.", required = true)
                property(GS.fieldCount, "How many fields the entry carried.", required = true) {
                    type = SCT.integer
                }
                // The entry itself, back out through the union. Two things fall out of echoing it rather than
                // only describing it: the output schema carries a union, so the catalog's structural view has
                // one to render; and with response-schema validation on, what we send is checked against the
                // same branches that accepted it -- a round trip rather than a one-way check.
                property(GS.entry, "The entry as it was validated.", required = true) { ref("GedraEntry") }
            }

            generalEndpoint(
                "/gedraSketch/entry/echo",
                "Validates one Gedra entry against its trait's branch and reports which branch accepted it. " +
                    "Stores nothing.",
                HttpMethod.POST,
                outputRef = "EntryEcho",
                inputFields = {
                    field(GS.entry, "The entry to validate.", required = true) { ref("GedraEntry") }
                },
            ) { cxt, request ->
                // Getting this far means the union already validated the entry -- the endpoint runs after
                // input validation, so a wrong-shaped entry never reaches here. What is left is to say which
                // branch accepted it, which is the part a caller cannot see from a bare 200.
                val entry = stored(request[GS.entry].toJsonMapOrEmpty(), 0, cxt.instanceNow())
                val trait = entry[GS.traitId].toOptStr() ?: ""
                linkedMapOf<String, Any?>(
                    GS.traitId to trait,
                    GS.branch to if (trait == GS.expenseReport || trait == GS.managerApproval) trait else GS.default,
                    GS.fieldCount to entry.size,
                    GS.entry to entry,
                )
            }

            // The round trip the sketch exists for (issue #255): a set of entries in, the same entries back
            // filled out. Nothing is stored -- the point is to show what storing would have to do and to put
            // all three schema constructs under load at once. Each element is validated against the branch its
            // own traitId names, so one array carries several shapes, and a failure names the element it came
            // from.
            listEndpoint(
                "/gedraSketch/entries/fillOut",
                "Validates a set of Gedra entries and answers with them filled out. Stores nothing -- the " +
                    "name says what it does rather than what a real endpoint here would be called.",
                outputRef = "GedraEntry",
                method = HttpMethod.POST,
                // A `limit` would be nonsense here: the answer is one entry per entry supplied, so there is
                // nothing to truncate.
                noLimit = true,
                inputFields = {
                    field(GS.entries, "The entries to validate and fill out.", required = true) {
                        type = SCT.array
                        items { ref("GedraEntry") }
                    }
                },
            ) { cxt, request ->
                val now = cxt.instanceNow()
                request[GS.entries].toJsonListOrEmpty().mapIndexed { index, raw ->
                    stored(raw.toJsonMapOrEmpty(), index, now)
                }
            }
        }
    }
}

/**
 * The fields every stored entry carries and no caller supplies: an id, how the value came to be, and when it
 * was written (issue #255).
 *
 * All three are `g-derived`, which is what makes them the *stored* shape rather than the *sent* shape -- absent
 * from the input schema, undrawn by the form, dropped if a client echoes them back, and required on the way
 * out. Declared once here rather than repeated per branch, so a new trait cannot end up with a different
 * envelope from its siblings.
 *
 * `entryId` is the stable surrogate `gedra-entry.md` argues for: identity that does not move when the data it
 * describes is edited. This sketch numbers them, having nothing to mint from and nowhere to keep them.
 *
 * `createdAt` / `updatedAt` deliberately spell it the way the SQL layer's audit columns already do (`PF`),
 * because an entry is meant to read like a row -- and `modifiedAt` up here against `updatedAt` one layer down
 * would be two names for one idea, leaving whoever met the second to guess which the first meant.
 */
private fun SchTypeBuilder.storedFields() {
    property(GS.entryId, "Stable id of this entry; assigned when stored.", required = true) { derived = true }
    property(GS.source, "How the current value came to be.", required = true) { derived = true }
    property(GS.createdAt, "When the entry was first written.", required = true) {
        dateTime()
        derived = true
    }
    property(GS.updatedAt, "When the entry was last changed.", required = true) {
        dateTime()
        derived = true
    }
}

/**
 * One entry as storing it would leave it: the trait's own derived values, plus the envelope no caller supplies
 * (issue #255).
 *
 * Both endpoints go through here, and they have to: `GedraEntry` declares the envelope `required`, so an
 * endpoint that answered with an entry lacking it would fail its own response-schema check. That check is what
 * turned an inconsistency between the two into a test failure rather than a difference nobody noticed.
 *
 * [now] stays an `Instant` all the way into the map rather than being formatted here. Serialization is where
 * a date becomes text -- `JsonUtil` writes it through `fmt`, which is the one place that decides the wire
 * format (ISO-8601 UTC, milliseconds) -- and the validator recognizes an `Instant` as already being the shape
 * a `date-time` field declares. Formatting at the call site would put that decision in every handler that
 * ever stamps a row, which is how one of them ends up with microseconds.
 */
private fun stored(entry: Map<String, Any?>, index: Int, now: Instant): Map<String, Any?> =
    filledOut(entry) + linkedMapOf<String, Any?>(
        GS.entryId to "e-${index + 1}",
        // Deduced from context rather than taken from the caller: a direct endpoint call is a person acting,
        // so it reads `user`. An integration would say what it was instead.
        GS.source to GS.userSource,
        // Both stamps, and the same value here only because the sketch stores nothing: a record that has never
        // been changed was last changed when it was written. Storage is what makes them diverge, and an entry
        // carrying only one of them cannot answer "has this been touched since it arrived" -- which is the
        // question the pair exists for.
        GS.createdAt to now,
        GS.updatedAt to now,
    )

/**
 * Fills in what the caller does not supply -- the sketch's stand-in for a trait's pre-processor (issue #254).
 *
 * `totalAmount` is declared `g-derived`, so it is absent from the input schema, undrawn by the form, and
 * dropped if a client echoes one back. Something has to produce it, and in a real trait that something is
 * code bound to the trait; here it is this function, which is the smallest honest version of the same thing.
 */
private fun filledOut(entry: Map<String, Any?>): Map<String, Any?> {
    val perItem = (entry[GS.perItemAmount] as? Number)?.toDouble() ?: return entry
    val count = (entry[GS.itemCount] as? Number)?.toDouble() ?: return entry
    return entry + (GS.totalAmount to perItem * count)
}

/** Field names and trait ids for the Gedra sketch, kept beside the schema that declares them. */
@Suppress("ConstPropertyName")
object GS {
    // Envelope.
    const val traitId = "traitId"
    const val entry = "entry"
    const val entries = "entries"
    const val entryId = "entryId"
    const val source = "source"
    const val createdAt = "createdAt"
    const val updatedAt = "updatedAt"
    const val userSource = "user"

    // Branch fields.
    const val year = "year"
    const val totalAmount = "totalAmount"
    const val notes = "notes"
    const val perItemAmount = "perItemAmount"
    const val itemCount = "itemCount"
    const val approved = "approved"
    const val decidedBy = "decidedBy"
    const val rejectionReason = "rejectionReason"

    // Trait ids.
    const val expenseReport = "expenseReport"
    const val managerApproval = "managerApproval"

    // Echo result.
    const val branch = "branch"
    const val fieldCount = "fieldCount"
    const val default = "default"
}
