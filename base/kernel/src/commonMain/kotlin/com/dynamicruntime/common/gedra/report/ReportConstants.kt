package com.dynamicruntime.common.gedra.report

import com.dynamicruntime.common.gedra.GE

/**
 * Where a report path reads from (issue #977): a [form]'s own trait entries, the [workflow] progress recorded on it,
 * the account of the [user] who owns it, or a fact about the form itself ([meta]).
 */
@Suppress("EnumEntryName")
enum class ReportSource {
    /** A value inside one of the form's trait entries, or a field of the entry's own envelope. */
    form,

    /** A computed fact about one workflow on the form: its category, an approval. Never raw state. */
    workflow,

    /** An attribute of the form owner's account. Users carry no trait data, so this is a fixed set. */
    user,

    /** A fact about the form itself: its id, its dates, its status. */
    meta,
}

/**
 * What a report value is, which decides how it is compared, combined and shown (issue #977).
 *
 * Deliberately not `UsageKind`, which it resembles. A usage kind decides which **search parameters** a trait usage
 * mints for the forms listing (an exact match, a `Min`/`Max` pair), and its comparison works on display *strings*
 * parsed per kind. A report holds typed values and has a kind search has no parameters for -- `boolean`, which most
 * of the workflow vocabulary is. Folding the two together would put `boolean` into every usage's choices and every
 * exhaustive `when` over them, to no one's benefit; a usage's three kinds are a subset of these by name.
 */
@Suppress("EnumEntryName")
enum class ReportKind {
    string,
    number,
    date,
    boolean,
}

/**
 * One name a path may end in outside a trait's own data (issue #977): its [kind], fixed by the vocabulary rather
 * than read from a schema, what it holds ([description], written for whoever builds a report, issue #1563), and
 * whether it holds several values ([multiValued]) -- a user's labels, a form's cfacts.
 */
class ReportAttr(val name: String, val kind: ReportKind, val description: String, val multiValued: Boolean = false)

/** The report path's punctuation and bounds (issue #977). */
@Suppress("ConstPropertyName")
object RPT {
    /** Separates a path's segments. */
    const val sep = '.'

    /** Opens and closes a selector: which entry of a keyed trait, or which task's approval. */
    const val selectorOpen = '['
    const val selectorClose = ']'

    /** The selector for every entry of a keyed trait: `yearly[*]`. */
    const val all = '*'

    /** Separates a selector's key values, and a named value's field from its value: `[year=2024,quarter=Q1]`. */
    const val valueSep = ','
    const val nameSep = '='

    /** Marks a field of the entry's envelope rather than of its data: `form.yearly[2024].@updatedAt`. */
    const val envMark = '@'

    /** Quotes a key value that could not be written bare. */
    const val quote = '"'

    /**
     * The most field segments a form path may hold. Far past any real schema's depth; it bounds the walk a path
     * drives through a schema and through data, so neither needs a guard of its own.
     */
    const val maxFields = 32
}

/** The `workflow` source's attributes (issue #977): what a report may say about one workflow on a form. */
@Suppress("ConstPropertyName")
object RWF {
    /** Where the form stands in the workflow: eligible, engaged, finished, ineligible. */
    const val category = "category"
    const val engaged = "engaged"
    const val eligible = "eligible"
    const val finished = "finished"
    const val tasksDone = "tasksDone"

    /** The task the form's people are asked to do next, while the workflow is engaged. */
    const val ctaTask = "ctaTask"

    /** The search keyword its call to action carries, while the workflow is under way (issue #1600). */
    const val ctaKeyword = "ctaKeyword"

    /** Who its call to action waits on, in the client's words (issue #1600): the CTA filter's label. */
    const val ctaFilterLabel = "ctaFilterLabel"
    const val lastEngagedAt = "lastEngagedAt"

    /** When anyone last acted on the form in the workflow (issue #1787): the newest stamp on its engagement. */
    const val lastActivityAt = "lastActivityAt"

    /** Not an attribute but a step: `approval[<taskId>]`, then one of [approvalAttrs]. */
    const val approval = "approval"
    const val approved = "approved"
    const val approvedAt = "approvedAt"
    const val approvedBy = "approvedBy"

    /** An approval reversed by an administrator and not given again (issue #1787): whether, when, by whom, and why. */
    const val reversed = "reversed"
    const val reversedAt = "reversedAt"
    const val reversedBy = "reversedBy"
    const val reversedReason = "reversedReason"

    val attrs: Map<String, ReportAttr> = listOf(
        ReportAttr(
            category, ReportKind.string,
            "Where the form stands with the workflow: `engaged` while it is under way, `eligible` when it could start, " +
                "`finished` once it is done, and `ineligible` when an eligibility test fails.",
        ),
        ReportAttr(engaged, ReportKind.boolean, "Whether the form has entered the workflow."),
        ReportAttr(eligible, ReportKind.boolean, "Whether the form passes every one of the workflow's eligibility tests."),
        ReportAttr(finished, ReportKind.boolean, "Whether the workflow has finished for the form."),
        ReportAttr(tasksDone, ReportKind.boolean, "Whether every task of the workflow is complete and valid for the form, so none is left to do."),
        ReportAttr(
            ctaTask, ReportKind.string, "The id of the task the form's people are asked to do next, while the workflow is under way.",
        ),
        ReportAttr(
            ctaKeyword, ReportKind.string,
            "The search keyword of the task the form's people are asked to do next, such as `review`, while the workflow " +
                "is under way. A report's table shows the keyword's report label.",
        ),
        ReportAttr(
            ctaFilterLabel, ReportKind.string,
            "Whom the task the form's people are asked to do next waits on, in the client's words, such as \"Waiting on " +
                "a Northwest reviewer\", while the workflow is under way and its call to action says.",
        ),
        ReportAttr(lastEngagedAt, ReportKind.date, "When the form last entered the workflow."),
        ReportAttr(
            lastActivityAt, ReportKind.date,
            "When anyone last acted on the form in the workflow: entered or left it, gave or reversed an approval, or " +
                "overrode a lock. Saving the form's data is not counted; the form's `updatedAt` says when that happened.",
        ),
    ).associateBy { it.name }

    val approvalAttrs: Map<String, ReportAttr> = listOf(
        ReportAttr(approved, ReportKind.boolean, "Whether a reviewer has approved the task."),
        ReportAttr(approvedAt, ReportKind.date, "When the task was approved."),
        ReportAttr(approvedBy, ReportKind.number, "The user id of the reviewer who approved it."),
        ReportAttr(
            reversed, ReportKind.boolean,
            "Whether an administrator reversed the task's approval, or a step of it, and it has not been given again since " +
                "the form entered the workflow.",
        ),
        ReportAttr(reversedAt, ReportKind.date, "When the approval was reversed, while it stands reversed."),
        ReportAttr(reversedBy, ReportKind.number, "The user id of the administrator who reversed it."),
        ReportAttr(reversedReason, ReportKind.string, "Why the administrator reversed it, in their words."),
    ).associateBy { it.name }
}

/** The `user` source's attributes (issue #977): the form owner's account. */
@Suppress("ConstPropertyName")
object RUSR {
    const val userId = "userId"
    const val name = "name"

    /** The name shown to others, which falls back when the user gave none. */
    const val publicName = "publicName"
    const val email = "email"
    const val persona = "persona"
    const val org = "org"
    const val labels = "labels"
    const val isEntity = "isEntity"
    const val enabled = "enabled"

    val attrs: Map<String, ReportAttr> = listOf(
        ReportAttr(userId, ReportKind.number, "The owner's user id."),
        ReportAttr(name, ReportKind.string, "The owner's name: a person's full name, or a business's. Empty when they gave none."),
        ReportAttr(
            publicName, ReportKind.string,
            "The name shown to others: the owner's username, or their email address until they choose one.",
        ),
        ReportAttr(email, ReportKind.string, "The owner's email address."),
        ReportAttr(persona, ReportKind.string, "The kind of user the owner is in the client: `member`, `editor`, or `admin`."),
        ReportAttr(org, ReportKind.string, "The owner's organization within the client. Empty when they have none."),
        ReportAttr(labels, ReportKind.string, "The labels the owner's user carries, such as `committee`.", multiValued = true),
        ReportAttr(isEntity, ReportKind.boolean, "Whether the account belongs to a business rather than a person."),
        ReportAttr(enabled, ReportKind.boolean, "Whether the owner's account is enabled."),
    ).associateBy { it.name }
}

/** The `meta` source's attributes (issue #977): facts about the form itself. */
@Suppress("ConstPropertyName")
object RMETA {
    const val gedraId = "gedraId"
    const val client = "client"
    const val org = "org"
    const val ownerId = "ownerId"
    const val createdAt = "createdAt"
    const val updatedAt = "updatedAt"

    /** The form's overall status, from its core details workflow and its workflows. */
    const val formStatus = "formStatus"
    const val coreDetailsStatus = "coreDetailsStatus"
    const val cfacts = "cfacts"

    /** The search keywords the form's workflows' calls to action carry (issue #1600), whichever workflow. */
    const val ctaKeywords = "ctaKeywords"

    val attrs: Map<String, ReportAttr> = listOf(
        ReportAttr(gedraId, ReportKind.string, "The form's id."),
        ReportAttr(client, ReportKind.string, "The client the form belongs to."),
        ReportAttr(org, ReportKind.string, "The organization the form belongs to within the client. Empty when it has none."),
        ReportAttr(ownerId, ReportKind.number, "The user id of the form's owner."),
        ReportAttr(createdAt, ReportKind.date, "When the form was created."),
        ReportAttr(updatedAt, ReportKind.date, "When the form was last changed."),
        ReportAttr(
            formStatus, ReportKind.string,
            "The status the forms list shows: the core details status, except that a valid form reads `needsReview` " +
                "while a workflow waits on a review, and `finished` once one has finished.",
        ),
        ReportAttr(
            coreDetailsStatus, ReportKind.string,
            "Whether the form's core details are done: `valid`, `needsInfo` when a required trait is missing, or " +
                "`invalid` when entered data fails its checks. Empty for a client with no core details workflow.",
        ),
        ReportAttr(cfacts, ReportKind.string, "The facts the form has, such as `kdr:finished`.", multiValued = true),
        ReportAttr(
            ctaKeywords, ReportKind.string,
            "The search keywords, such as `review`, that the calls to action of the workflows under way on the form carry, " +
                "each once. A report's table shows each keyword's report label.",
            multiValued = true,
        ),
    ).associateBy { it.name }
}

/** The fields of an entry's envelope a form path may read after [RPT.envMark] (issue #977): who wrote it, and when. */
@Suppress("ConstPropertyName")
object RENV {
    // The entry's own field names (`GE`), so a renamed envelope field cannot leave a path naming the old one.
    const val createdAt = GE.createdAt
    const val updatedAt = GE.updatedAt
    const val createdBy = GE.createdBy
    const val updatedBy = GE.updatedBy
    const val source = GE.source

    val attrs: Map<String, ReportAttr> = listOf(
        ReportAttr(createdAt, ReportKind.date, "When the entry was first written."),
        ReportAttr(updatedAt, ReportKind.date, "When the entry was last changed."),
        ReportAttr(createdBy, ReportKind.number, "The user id of whoever first wrote the entry, who may not be the form's owner."),
        ReportAttr(updatedBy, ReportKind.number, "The user id of whoever last changed the entry."),
        ReportAttr(source, ReportKind.string, "How the entry's value came to be: `user` when a person entered, edited, or approved it."),
    ).associateBy { it.name }
}
