package com.dynamicruntime.common.gedra

import com.dynamicruntime.common.util.toOptEnum

/**
 * How a client's forms listing draws each row's workflows (issue #1599): the drawing only, never what counts as the
 * caller's turn, which the classification decides whatever the layout. An enum rather than a yes/no, so a layout of
 * one column per workflow can be added later without a rename.
 */
@Suppress("EnumEntryName")
enum class WorkflowLayout {
    /** One Workflows cell holding every link, strongest first (issue #1598). */
    combined,

    /**
     * A Your turn column holding only the caller's turn, sortable so their work comes to the top, beside the Workflows
     * cell holding the rest. The default.
     */
    split,
}

/**
 * A client's **listing-wide** settings (issue #1599), the one entry of the [CCT.listingDef] slot: what applies to its
 * forms listing as a whole, where a usage rule ([CCT.usageDef]) is one column of it. [workflowLayout] is the first.
 *
 * A client's setting resolves **stored over code**: an entry in its stored configuration stands over one its code (or
 * its template) declares, with no `overrides` mark, since the slot holds one entry and there is nothing else for it
 * to be. Absent everywhere, every field is its default.
 */
class ListingDef(
    /** How each row's workflows are drawn; [WorkflowLayout.split] unless the client says otherwise. */
    val workflowLayout: WorkflowLayout = WorkflowLayout.split,
) {
    /** The slot entry this writes, every field present. */
    fun toEntry(): Map<String, Any?> = linkedMapOf(CCT.workflowLayout to workflowLayout.name)

    companion object {
        /** Every field at its default: what a client that declares none has. */
        val defaults: ListingDef = ListingDef()

        /**
         * The settings a slot [entry] holds, read leniently: a field absent, or holding a value this release does not
         * know, is its default. A write is held to the slot's shape first, so only what is already stored reads leniently.
         *
         * Theme: #CFGFG.
         */
        fun fromEntry(entry: Map<String, Any?>): ListingDef =
            ListingDef(workflowLayout = entry[CCT.workflowLayout].toOptEnum<WorkflowLayout>() ?: defaults.workflowLayout)
    }
}
