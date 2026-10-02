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
 * than read from a schema, and whether it holds several values ([multiValued]) -- a user's labels, a form's cfacts.
 */
class ReportAttr(val name: String, val kind: ReportKind, val multiValued: Boolean = false)

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
    const val lastEngagedAt = "lastEngagedAt"

    /** Not an attribute but a step: `approval[<taskId>]`, then one of [approvalAttrs]. */
    const val approval = "approval"
    const val approved = "approved"
    const val approvedAt = "approvedAt"
    const val approvedBy = "approvedBy"

    val attrs: Map<String, ReportAttr> = listOf(
        ReportAttr(category, ReportKind.string),
        ReportAttr(engaged, ReportKind.boolean),
        ReportAttr(eligible, ReportKind.boolean),
        ReportAttr(finished, ReportKind.boolean),
        ReportAttr(tasksDone, ReportKind.boolean),
        ReportAttr(ctaTask, ReportKind.string),
        ReportAttr(lastEngagedAt, ReportKind.date),
    ).associateBy { it.name }

    val approvalAttrs: Map<String, ReportAttr> = listOf(
        ReportAttr(approved, ReportKind.boolean),
        ReportAttr(approvedAt, ReportKind.date),
        ReportAttr(approvedBy, ReportKind.number),
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
        ReportAttr(userId, ReportKind.number),
        ReportAttr(name, ReportKind.string),
        ReportAttr(publicName, ReportKind.string),
        ReportAttr(email, ReportKind.string),
        ReportAttr(persona, ReportKind.string),
        ReportAttr(org, ReportKind.string),
        ReportAttr(labels, ReportKind.string, multiValued = true),
        ReportAttr(isEntity, ReportKind.boolean),
        ReportAttr(enabled, ReportKind.boolean),
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

    /** The form's overall status, from its survey and its workflows. */
    const val formStatus = "formStatus"
    const val surveyStatus = "surveyStatus"
    const val cfacts = "cfacts"

    val attrs: Map<String, ReportAttr> = listOf(
        ReportAttr(gedraId, ReportKind.string),
        ReportAttr(client, ReportKind.string),
        ReportAttr(org, ReportKind.string),
        ReportAttr(ownerId, ReportKind.number),
        ReportAttr(createdAt, ReportKind.date),
        ReportAttr(updatedAt, ReportKind.date),
        ReportAttr(formStatus, ReportKind.string),
        ReportAttr(surveyStatus, ReportKind.string),
        ReportAttr(cfacts, ReportKind.string, multiValued = true),
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
        ReportAttr(createdAt, ReportKind.date),
        ReportAttr(updatedAt, ReportKind.date),
        ReportAttr(createdBy, ReportKind.number),
        ReportAttr(updatedBy, ReportKind.number),
        ReportAttr(source, ReportKind.string),
    ).associateBy { it.name }
}
