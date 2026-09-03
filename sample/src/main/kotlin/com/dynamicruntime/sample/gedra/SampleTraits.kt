package com.dynamicruntime.sample.gedra

import com.dynamicruntime.common.cfact.CFACTS
import com.dynamicruntime.common.context.KdrCxt
import com.dynamicruntime.common.gedra.GedraConfig
import com.dynamicruntime.common.gedra.GedraDataType
import com.dynamicruntime.common.gedra.gedraConfig
import com.dynamicruntime.common.schema.SCT

/** The sample traits' names, kept beside the config that declares them. */
@Suppress("ConstPropertyName")
object ST {
    /** The bundle: `gc.cd.global.sampleTraits`. */
    const val sampleTraits = "sampleTraits"

    /** The namespace the sample's own definitions live in. */
    const val namespace = "sampleconfig"

    // --- the expense trait, which exercises a derived value ---
    const val expenseReport = "expenseReport"
    const val expenseReportEntry = "ExpenseReportEntry"
    const val year = "year"
    const val perItemAmount = "perItemAmount"
    const val itemCount = "itemCount"
    const val totalAmount = "totalAmount"
    const val reviewerNote = "reviewerNote"

    // --- the questionnaire trait, which exercises a merge ---
    const val questionnaire = "questionnaire"
    const val questionnaireEntry = "QuestionnaireEntry"
    const val topic = "topic"
    const val notes = "notes"
    const val hasIssue = "hasIssue"
    const val explanation = "explanation"


    // --- the approval trait, which exercises a conditional inside `data` ---
    /**
     * A named **interior** type, referenced by [siteVisit]'s data rather than declared inline (issue #379).
     *
     * Pulled out on purpose. An overlay applies at two levels and no deeper, so there is no way to address
     * part of a type -- which means a structure a client may want to narrow has to *be* a type, reachable by
     * `$ref`, before anyone can narrow it. This is that case under test, and the reason the codebase names
     * interior types at all.
     */
    const val siteAddress = "SiteAddress"
    const val country = "country"
    const val postcode = "postcode"
    const val siteVisit = "siteVisit"
    const val siteVisitEntry = "SiteVisitEntry"
    const val visitedOn = "visitedOn"
    const val address = "address"
    const val purpose = "purpose"

    /**
     * Suggested purposes for a site visit -- an **open** list (issue #418), against [countries] beside it.
     *
     * Both in one sample on purpose, because the difference is the whole of what the keyword means. A country
     * list can claim to be complete, so it bounds the value and a client may narrow it. A list of purposes
     * cannot: whatever is written here, the next visit is for something else, and a field that refused it
     * would be wrong rather than strict.
     */
    val purposes: List<String> = listOf("inspection", "maintenance", "delivery", "survey")

    /** The countries the global schema admits; a client may offer fewer, and one does. */
    val countries: List<String> = listOf("gb", "ie", "fr", "de")

    const val managerApproval = "managerApproval"
    const val approvalEntry = "ApprovalEntry"
    const val approved = "approved"
    const val decidedBy = "decidedBy"
    const val rejectionReason = "rejectionReason"

    // --- the yearly trait, which exercises a primary key (issue #487): several entries told apart by `year` ---
    const val yearly = "yearly"
    const val yearlyEntry = "YearlyEntry"
    const val note = "note"
}

/**
 * Traits that exist to be exercised, not to be shipped (issues #292, #301).
 *
 * They live in the `sample` component, which loads only in developer environments, so nothing here reaches a
 * real deployment — and they are contributed the same way a real trait is, through
 * `ComponentDefinition.gedraConfigs`, which is what makes them a genuine test of the path rather than a
 * fixture pretending to be one.
 *
 * Two of them, deliberately, because **one trait does not test a union.** With a single branch there is
 * nothing for a discriminator to select between, no way for a failure to be attributed to the wrong branch,
 * and no payload carrying several shapes at once. The manufactured `FormDocEntry` union picks these up
 * alongside `globalconfig`'s `name`, so the fixture drives a union with three branches and a default.
 *
 * Between them, they carry every schema construct an entry can: a discriminated branch, a conditional inside
 * `data`, a derived value, and bounds. The conditional is the one worth having on purpose — pushing a trait's
 * fields under `data` moved conditionals one level deeper, and the form had a bug of exactly that shape in
 * #253.
 *
 * Their namespace is their own. `globalconfig` belongs to the runtime's real definitions, and a sample
 * writing into it would be the namespace-ownership rule being broken by the first thing to test it.
 */
fun sampleTraits(cxt: KdrCxt): GedraConfig = gedraConfig(cxt, ST.sampleTraits, ST.namespace) {
    trait(
        ST.expenseReportEntry,
        ST.expenseReport,
        setOf(GedraDataType.formDoc),
        "An expense report for one reporting year.",
    ) {
        property(ST.year, "Reporting year this report covers.", required = true) {
            type = SCT.integer
            minimum = 2000
            maximum = 2100
        }
        property(ST.perItemAmount, "Amount claimed for one item.") { type = SCT.number }
        property(ST.itemCount, "How many items are claimed.") { type = SCT.integer }
        // Admin-only (issue #569): a `g-visibleWhen` gate the frontend evaluates, so the field is drawn for an
        // administrator and hidden from an ordinary caller -- on the creation-workflow form (acme collects this
        // trait) as on the endpoint form. Optional, because the gate is refused on a required property; the
        // served schema keeps the field for everyone, and only the page hides it.
        property(ST.reviewerNote, "An internal reviewer note, shown to administrators only.") {
            visibleWhen = CFACTS.hasAdminLevel
        }
        // Derived: the caller does not supply it, the form draws no control for it, and one echoed back is
        // dropped. Something has to produce it, and for a real trait that something is code bound to the
        // trait; here it is the fixture's own fill-out.
        property(ST.totalAmount, "Total claimed; computed from the two above, not supplied.") {
            type = SCT.number
            derived = true
        }
    }

    // A merge sends a fragment -- a page updates a few keys of a large entry. That fragment is now accepted on
    // the way in (an edit's data is validated as a fragment, `g-optionalContents`, issue #487), so a required
    // field no longer makes `addOrMerge` unreachable; completeness is settled against the merged result. This
    // trait is still **everything optional** on purpose, because that is what real merge targets look like:
    // their requiredness is a soft rule the workflow owns, not a per-page schema check (see the soft-validation
    // section of `gedra-patch.md`). The questionnaire is the case it is named for: one entry holding a long
    // body of answers, of which a page updates a few.
    trait(
        ST.questionnaireEntry,
        ST.questionnaire,
        setOf(GedraDataType.formDoc),
        "A body of answers, updated a page at a time.",
    ) {
        property(ST.topic, "What this questionnaire is about.")
        property(ST.notes, "Anything the answerer wanted to add.")
        property(ST.hasIssue, "Whether the answerer flagged a problem.") { type = SCT.boolean }
        property(ST.explanation, "What the problem is.")
        // A conditional over two *optional* fields, which is what makes it possible to send a fragment that is
        // valid alone and invalid once merged: `{hasIssue: false}` says nothing wrong by itself, and says
        // something wrong when it lands on a stored explanation.
        presentWhen(ST.explanation, on = ST.hasIssue, value = true)
    }

    trait(
        ST.approvalEntry,
        ST.managerApproval,
        setOf(GedraDataType.formDoc),
        "A manager's decision on what was submitted.",
    ) {
        property(ST.approved, "Whether it was approved.", required = true) { type = SCT.boolean }
        property(ST.decidedBy, "Who decided.")
        property(ST.rejectionReason, "Why it was rejected.")
        // A reason is required when the decision is a rejection and inadmissible when it is not -- now one
        // level down, inside `data`, which is the arrangement worth having under test.
        presentWhen(ST.rejectionReason, on = ST.approved, value = false)
    }

    // A named type, so that a client can narrow it (issue #379). Everything else here declares its properties
    // inline, which leaves nothing for an overlay to target: an overlay reaches a type's own keys and its
    // property set, and stops. A structure worth narrowing separately has to be a type of its own.
    type(ST.siteAddress) {
        type = SCT.kObject
        description = "Where a visit happened."
        property(ST.country, "Country the site is in.", required = true) {
            for (c in ST.countries) option(c)
        }
        property(ST.postcode, "Postal code, as written locally.")
    }

    trait(
        ST.siteVisitEntry,
        ST.siteVisit,
        setOf(GedraDataType.formDoc),
        "A visit to a site, whose address is a type in its own right.",
    ) {
        property(ST.visitedOn, "When the visit happened.") { dayOnlyDate() }
        property(ST.purpose, "Why the visit happened; the suggestions are not the whole list.") {
            for (p in ST.purposes) option(p)
            openOptions()
        }
        // The `$ref` that makes interior alteration possible: a client narrowing `SiteAddress` narrows this
        // without the trait being edited, or knowing.
        property(ST.address, "Where the visit happened.", required = true) { ref(ST.siteAddress) }
    }

    // A trait with a **primary key** (issue #487), and the one that exercises several entries of one trait: a
    // formDoc may carry one `yearly` entry per year, told apart by `year`. The key is numeric, which is the
    // case the issue names -- a primary key is not always a string. Every other trait here is single-instance.
    trait(
        ST.yearlyEntry,
        ST.yearly,
        setOf(GedraDataType.formDoc),
        "One record per year, keyed by the year.",
        primaryKey = listOf(ST.year),
    ) {
        property(ST.year, "The year this record is for; the entry's primary key.", required = true) {
            type = SCT.integer
            minimum = 2000
            maximum = 2100
        }
        property(ST.note, "Anything recorded for the year.")
    }
}
