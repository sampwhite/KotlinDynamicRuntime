package com.dynamicruntime.sample.gedra

import com.dynamicruntime.common.cfact.CFACTS
import com.dynamicruntime.common.context.KdrCxt
import com.dynamicruntime.common.gedra.GE
import com.dynamicruntime.common.exception.KdrException
import com.dynamicruntime.common.gedra.GedraConfig
import com.dynamicruntime.common.gedra.GedraDataDeriver
import com.dynamicruntime.common.gedra.GedraDataType
import com.dynamicruntime.common.gedra.GedraPrepForSaveFn
import com.dynamicruntime.common.gedra.GedraStateContext
import com.dynamicruntime.common.gedra.GedraStateDeriver
import com.dynamicruntime.common.gedra.StateTraitClass
import com.dynamicruntime.common.gedra.gedraConfig
import com.dynamicruntime.common.schema.SCT
import com.dynamicruntime.common.schema.layout
import com.dynamicruntime.common.util.toJsonMapOrEmpty
import com.dynamicruntime.common.util.toOptLong
import com.dynamicruntime.common.util.toOptStr
import kotlinx.datetime.UtcOffset
import kotlinx.datetime.asTimeZone
import kotlinx.datetime.toLocalDateTime

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

    // --- a state trait (issue #597): derived, keyed by year -- the phase-B declaration; the derivation is phase D ---
    const val traitPresenceByYear = "traitPresenceByYear"
    const val traitPresenceByYearEntry = "TraitPresenceByYearEntry"
    const val presentTraits = "presentTraits"

    // --- a cross-kind state trait (issue #597): an id from a third-party sync, applies to more than one kind ---
    const val externalId = "externalId"
    const val externalIdEntry = "ExternalIdEntry"
    const val externalSource = "externalSource"
    const val externalRef = "externalRef"

    // --- phase D (issue #599): the demo state derivation's opt-in feature, and a demo cfact for the bridge ---
    /** Test/demo feature name a client lists in `testFeatures` to run the `traitPresenceByYear` derivation. */
    const val captureTraitPresenceByYear = "captureTraitPresenceByYear"

    /** A demo cfact a form's stored state can assert, exercising the state→cfact bridge (issue #599). */
    const val sampleFormReady = "sampleFormReady"
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
            // A `g-errors` message with ${'$'}{…} substitution (issue #589): the validator resolves it against
            // the failure's params -- the declared bound and the offending value -- so the `400` envelope and a
            // form's client-side validation get one resolved copy. Distinct from the layout `hint` below, which
            // the *friendly* form renders and which a wire-documenting surface ignores; this wording is the
            // schema's own, shown wherever the field is validated (the endpoint form included).
            errors {
                belowMinimum($$"We keep reports back to ${min}; ${value} is earlier than that.")
                aboveMaximum($$"Reports run no later than ${max}; ${value} is beyond that.")
            }
        }
        property(ST.perItemAmount, "Amount claimed for one item.") { type = SCT.number }
        property(ST.itemCount, "How many items are claimed.") { type = SCT.integer }
        // Admin-entered (issue #569): a `g-visibleWhen` gate the frontend evaluates, so an *editable* form draws the
        // field for an administrator and hides it from an ordinary caller -- on the creation-workflow form (acme
        // collects this trait) as on the endpoint form. Optional, because the gate is refused on a required
        // property. Presentation only: a read-only view shows the value to anyone who can see the form, the API
        // returns it to them, and the backend does not refuse the value from a non-administrator -- the gate hides
        // the box, nothing more. The description says what a reader can rely on.
        property(
            ST.reviewerNote,
            "A reviewer's note on the report. Only an administrator is offered a field to write it; anyone who " +
                "can see the form can read it.",
        ) {
            visibleWhen = CFACTS.hasAdminLevel
        }
        // Derived: the caller does not supply it, the form draws no control for it, and one echoed back is
        // dropped. Something has to produce it, and for a real trait that something is code bound to the
        // trait; here it is the fixture's own fill-out.
        property(ST.totalAmount, "Total claimed; computed from the two above, not supplied.") {
            type = SCT.number
            derived = true
        }
        // A layout `hint` (issue #587) over the field's bounds context: `${'$'}{min}` / `${'$'}{max}` resolve to this
        // field's own minimum/maximum on the frontend, replacing the derived "range: 2000 to 2100". The boot
        // check confirms the placeholders name bounds the field actually declares.
        layout {
            field(ST.year, label = "Reporting year", hint = $$"Any year from ${min} to ${max}.")
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
        // The type's `g-layout` (issue #585): friendly copy for the form, kept out of the served schema and
        // delivered beside it. Names every field, `notes` included, so acme -- whose overlay drops `notes` --
        // inherits this layout **pruned** to the three properties it kept. Delivered but not yet rendered.
        layout(fragmentFileId = SF.formHelp, label = "%{@t(\"questionnaire.heading\")}") {
            // The block `label` is the type's **heading** override (issue #605), a backend fragment pull that
            // resolves to Markdown at delivery ("## Questionnaire" + a line under it); the workflow form renders
            // it as Markdown in place of the schema title, leaving the served schema's title untouched.
            // `topic`'s description is a backend fragment pull too: `%{@t("questionnaire.topicHelp")}` resolves
            // against SF.formHelp server-side at delivery, so only the finished copy reaches the page.
            // The error override (issue #588): the form's own wording for an invalid topic, resolved on the
            // frontend over the failure's params -- the offending `${'$'}{value}` and the `${'$'}{options}` list --
            // shadowing the framework's "not a valid option" message. Base `topic` offers no options, so this
            // only fires for a client that adds them (acme does); the copy is inert but valid on the base.
            field(ST.topic, label = "Topic", description = "%{@t(\"questionnaire.topicHelp\")}") {
                invalidOption($$"""We don't cover the topic "${value}" here. Choose one of: ${options}.""")
            }
            field(ST.notes, label = "Anything else?")
            field(ST.hasIssue, label = "Did you hit a problem?")
            field(ST.explanation, label = "What went wrong", hint = "Only asked when a problem is flagged.")
        }
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

    // A **state** trait (issue #597), and the one that exercises the state union the way `yearly` exercises the
    // data one: a *derived* projection, keyed by `year`, recording which of a form's traits have data that year.
    // Keyed by `year` on purpose -- it is the proof that a state trait keys by whatever dimension it names, not
    // always `workflowId`. Only the schema lives here; the derivation that computes it is phase D (#599).
    stateTrait(
        ST.traitPresenceByYearEntry,
        ST.traitPresenceByYear,
        setOf(GedraDataType.formDoc),
        StateTraitClass.derived,
        "Per year, which of the form's traits have data -- a derived projection of the form's own data.",
        primaryKey = listOf(ST.year),
    ) {
        property(ST.year, "The year this record is for; the entry's primary key.", required = true) {
            type = SCT.integer
            minimum = 2000
            maximum = 2100
        }
        property(ST.presentTraits, "The trait ids that have data for this year.") {
            type = SCT.array
            items { type = SCT.string }
        }
    }

    // A **cross-kind** state trait (issue #597): the id a third-party store hands back when a gedra is synced to
    // it, keyed by the source so one gedra can be synced to several. It applies to more than one gedra kind --
    // whatever was synced -- which is the case that makes state a single `StateEntry` union rather than one per
    // kind: the id's shape has nothing to do with whether it is a formDoc or a wfData underneath. `asserted`:
    // an external fact a batch must never recompute away.
    stateTrait(
        ST.externalIdEntry,
        ST.externalId,
        setOf(GedraDataType.formDoc, GedraDataType.wfData),
        StateTraitClass.asserted,
        "The id a third-party store returned for this gedra, keyed by the source.",
        primaryKey = listOf(ST.externalSource),
    ) {
        property(ST.externalSource, "Which third-party store; the entry's primary key.", required = true) {
            maxLength = 64
        }
        property(ST.externalRef, "The id that store uses for this gedra.", required = true) { maxLength = 256 }
    }

    // A demo cfact (issue #599) declared globally, so a form's `cfacts` state may assert it and the state→cfact
    // bridge (`GedraDataService.assembleFormCfacts`) can union it into what an eligibility expression sees.
    // Nothing computes it as a request source; a form's stored state is what makes it present, which is the
    // whole point of the bridge. The real producer is the survey (a later phase).
    cfact(ST.sampleFormReady, "sampleState", "The sample form has recorded that it is ready -- a demo form-state cfact.")
}

/**
 * The demo state derivation (issue #599): reads a formDoc's data entries and records, per year, which trait ids
 * carry data that year -- filling the `traitPresenceByYear` state trait. The design's end-to-end vehicle: a
 * *derived*, non-workflow state keyed by a dimension (`year`) that is not `workflowId`, computed on create and
 * import and read back through the states cache.
 *
 * Gated by [ST.captureTraitPresenceByYear]: it runs only on a test instance for a client that lists that
 * feature (acme does), so it neither reaches production nor surprises a client that did not ask for it.
 */
/**
 * The expense report's derived total from its two supplied numbers, or empty when either is absent (issue
 * #712) -- the smallest honest pre-processor, exactly as the trait's `totalAmount` note describes. Shared by
 * the on-read [ExpenseTotalDeriver] and the fixture's `fillOut`, so the two producers of the same value cannot
 * drift: one computation, called from both.
 */
fun expenseTotalFields(data: Map<String, Any?>): Map<String, Any?> {
    val perItem = (data[ST.perItemAmount] as? Number)?.toDouble() ?: return emptyMap()
    val count = (data[ST.itemCount] as? Number)?.toDouble() ?: return emptyMap()
    return mapOf(ST.totalAmount to perItem * count)
}

/**
 * The expense report's total, computed **on read** (issue #712): the production producer of the `totalAmount`
 * the trait declares `g-derived`. A caller never supplies it and the form draws no control for it, so it is
 * filled whenever the form is read -- in the single-document view, the survey view, and any display column over
 * it -- from the per-item amount and item count beside it, and never stored.
 *
 * Ungated (no [featureName]): unlike the demo [TraitPresenceByYearDeriver], this is a *real* derivation -- a
 * value that genuinely follows from a form's own data -- so it runs for every client that carries the
 * `expenseReport` trait rather than only a test-instance opt-in. It is the data twin of a [GedraStateDeriver]:
 * the same registration seam, run on read rather than on write.
 */
object ExpenseTotalDeriver : GedraDataDeriver {
    override val appliesTo: Set<GedraDataType> = setOf(GedraDataType.formDoc)
    override val traitId: String = ST.expenseReport

    override fun derive(cxt: KdrCxt, data: Map<String, Any?>): Map<String, Any?> = expenseTotalFields(data)
}

/**
 * A trait save-time **validation** (issue #728): an expense report's reporting year may not be in the future,
 * refused before the write. The demonstrating half of the trait-function mechanism -- a check that JSON Schema
 * cannot express, because it turns on the runtime clock (`cxt.instanceNow()`) rather than a static bound, unlike
 * the trait's own `minimum`/`maximum` of 2000..2100.
 *
 * Deliberately a **single-field** rule, so it is safe on a merge fragment: it fires only when `year` is in the
 * data the caller sent, so a merge editing some other field never trips it on a year it did not touch (see
 * [GedraPrepForSaveFn] on why a save-time function must be fragment-safe). It returns the data unchanged -- a
 * pure validation -- and throws `KdrException.mkInput` to reject, which surfaces to the caller as a 400.
 *
 * Ungated, like [ExpenseTotalDeriver]: a real rule for every client that carries the trait.
 */
object ExpenseReportPrepForSaveFn : GedraPrepForSaveFn {
    override val appliesTo: Set<GedraDataType> = setOf(GedraDataType.formDoc)
    override val traitId: String = ST.expenseReport

    override fun prepForSave(cxt: KdrCxt, data: Map<String, Any?>): Map<String, Any?> {
        val year = data[ST.year].toOptLong() ?: return data
        // The latest year that is "now" anywhere on Earth: the new year first arrives at UTC+14 (Kiribati), so a
        // reporting year is only in the future when it is future for everyone. Reading the server's own zone
        // would wrongly refuse a filer east of it for the first hours of January -- the kind of thing a static
        // schema bound cannot get wrong because it never asks the clock, and this rule must.
        val latestCurrentYear = cxt.instanceNow().toLocalDateTime(UtcOffset(hours = 14).asTimeZone()).year
        if (year > latestCurrentYear) {
            throw KdrException.mkInput(
                "A reporting year cannot be in the future; $year is later than the current year $latestCurrentYear.",
            )
        }
        return data
    }
}

object TraitPresenceByYearDeriver : GedraStateDeriver {
    override val appliesTo: Set<GedraDataType> = setOf(GedraDataType.formDoc)
    override val featureName: String = ST.captureTraitPresenceByYear

    override fun derive(cxt: KdrCxt, state: GedraStateContext): List<Map<String, Any?>> {
        // Group trait ids by the `year` each entry's data carries -- expenseReport and yearly both have one; a
        // trait with no year simply contributes to no year. A sorted map + set keep the output deterministic.
        val byYear = sortedMapOf<Long, MutableSet<String>>()
        for (entry in state.row.entries) {
            val traitId = entry[GE.traitId].toOptStr() ?: continue
            val year = entry[GE.data].toJsonMapOrEmpty()[ST.year].toOptLong() ?: continue
            byYear.getOrPut(year) { sortedSetOf() }.add(traitId)
        }
        return byYear.map { (year, traits) ->
            mapOf(GE.traitId to ST.traitPresenceByYear, GE.data to mapOf(ST.year to year, ST.presentTraits to traits.toList()))
        }
    }
}
