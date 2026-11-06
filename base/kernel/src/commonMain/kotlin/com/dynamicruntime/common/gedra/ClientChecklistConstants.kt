package com.dynamicruntime.common.gedra

/**
 * A new client's checklist (issue #1639): the few steps that make a self-serve client useful, each checked off from the
 * client's state, which Home's "Set up your client" card lists. The read is `UADEP.clientChecklist`; its steps' ids are
 * here so the webapp names each step by the id the backend answers with, and adds its own ([assistant]) beside them.
 */
@Suppress("ConstPropertyName")
object CHKL {
    /** The read's type names. */
    const val checklistTypeName = "ClientChecklist"
    const val stepTypeName = "ClientChecklistStep"

    // --- field names (each matches its value) ---
    /** The steps, in the order the card lists them. */
    const val steps = "steps"

    /** A step's id: one of the step ids below. */
    const val id = "id"

    /** Whether the step is done. */
    const val done = "done"

    // --- step ids ---
    /** The client's New form, its creation workflow, made its own: changed since the starter it was created with. */
    const val newForm = "newForm"

    /** A normal workflow of the client's own, beside the creation and core details workflows it starts with. */
    const val workflow = "workflow"

    /** Someone besides its first administrator: more than one active user. */
    const val invite = "invite"

    /** A first form filled in. */
    const val firstForm = "firstForm"

    /**
     * The workflow assistant switched on. Not in the read: added by the webapp, from the assistant's status it already
     * reads, only where the menu offers the assistant -- core never asks the assistant module.
     */
    const val assistant = "assistant"
}
