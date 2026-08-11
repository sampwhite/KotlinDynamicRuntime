package com.dynamicruntime.sample.gedra

import com.dynamicruntime.common.context.KdrCxt
import com.dynamicruntime.common.endpoint.HttpMethod
import com.dynamicruntime.common.endpoint.SchModule
import com.dynamicruntime.common.endpoint.schemaModule
import com.dynamicruntime.common.schema.SCT
import com.dynamicruntime.common.startup.ServiceInitializer
import com.dynamicruntime.common.util.toJsonMapOrEmpty
import com.dynamicruntime.common.util.toOptStr

/**
 * A working sketch of the **Gedra entry** described in `gedra-entry.md`, and nothing more: it stores nothing,
 * persists nothing, and is not the entity store. It exists to exercise the schema constructs that model an
 * entry — a discriminated union today (issue #252), a conditional and a projection as those land — because
 * nothing else in the codebase does, which is the same bar the file endpoints clear and the reason issue #243
 * removed the demo and todo endpoints that cleared no bar at all.
 *
 * Named for what it is rather than for what it prototypes, so nobody later mistakes it for the real `Gedra`.
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
                property(GS.totalAmount, "Total claimed, in the client's currency.") { type = SCT.number }
                property(GS.notes, "Free-text explanation.")
            }
            variantBranch(
                "ApprovalEntry", GS.traitId, GS.managerApproval,
                "A manager's decision on what was submitted.",
            ) {
                property(GS.approved, "Whether it was approved.", required = true) { type = SCT.boolean }
                property(GS.decidedBy, "Who decided.")
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
                "/gedra/entry/echo",
                "Validates one Gedra entry against its trait's branch and reports which branch accepted it. " +
                    "Stores nothing.",
                HttpMethod.POST,
                outputRef = "EntryEcho",
                inputFields = {
                    field(GS.entry, "The entry to validate.", required = true) { ref("GedraEntry") }
                },
            ) { _, request ->
                // Getting this far means the union already validated the entry -- the endpoint runs after
                // input validation, so a wrong-shaped entry never reaches here. What is left is to say which
                // branch accepted it, which is the part a caller cannot see from a bare 200.
                val entry = request[GS.entry].toJsonMapOrEmpty()
                val trait = entry[GS.traitId].toOptStr() ?: ""
                linkedMapOf<String, Any?>(
                    GS.traitId to trait,
                    GS.branch to if (trait == GS.expenseReport || trait == GS.managerApproval) trait else GS.default,
                    GS.fieldCount to entry.size,
                    GS.entry to entry,
                )
            }
        }
    }
}

/** Field names and trait ids for the Gedra sketch, kept beside the schema that declares them. */
@Suppress("ConstPropertyName")
object GS {
    // Envelope.
    const val traitId = "traitId"
    const val entry = "entry"

    // Branch fields.
    const val year = "year"
    const val totalAmount = "totalAmount"
    const val notes = "notes"
    const val approved = "approved"
    const val decidedBy = "decidedBy"

    // Trait ids.
    const val expenseReport = "expenseReport"
    const val managerApproval = "managerApproval"

    // Echo result.
    const val branch = "branch"
    const val fieldCount = "fieldCount"
    const val default = "default"
}
