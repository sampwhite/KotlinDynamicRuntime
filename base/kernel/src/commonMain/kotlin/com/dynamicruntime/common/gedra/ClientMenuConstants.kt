package com.dynamicruntime.common.gedra

import com.dynamicruntime.common.http.request.SECT

/**
 * The wire vocabulary of editing a client's **menu** (issue #919): the home menu's items as a client sees them, and
 * the rename, hide, show and reset of one. Item addresses reuse `COV`'s names. Each name matches its value.
 */
@Suppress("ConstPropertyName")
object MNU {
    const val namespace = "kdr.clientMenu"

    /** The home menu's items for a client: shipped and effective label and condition, and what the client changed. */
    const val itemsPath = "/${SECT.clientAdmin}/client/menu/items"

    /** Renames, hides or shows one item for a client, and makes it live -- or a draft, for a client with a sandbox. */
    const val setPath = "/${SECT.clientAdmin}/client/menu/set"

    /** Removes a client's stored changes to one item, and makes that live -- or a draft, for a sandboxed client. */
    const val resetPath = "/${SECT.clientAdmin}/client/menu/reset"

    const val itemTypeName = "MenuItemView"
    const val resultTypeName = "MenuEditResult"
    const val audienceTypeName = "MenuAudience"

    /** The item's label for this client; the shipped one is `COV.baseLabel`. */
    const val label = "label"

    /** The condition deciding who is offered the item: shipped, and for this client. `#never` withdraws it. */
    const val baseCondition = "baseCondition"
    const val condition = "condition"

    /**
     * What the audience of [baseCondition] and of [condition] is called (issue #1094): the name a person reads, the
     * condition being what is stored. Absent for a withdrawn item, which has no audience, and for a condition the
     * menu's audiences do not name -- one written by hand in a source config, which reads as custom.
     */
    const val baseAudience = "baseAudience"
    const val audience = "audience"

    /** On an item: the audiences it may be shown to, each a [condition] and its [name] -- exactly what a show accepts. */
    const val audiences = "audiences"
    const val name = "name"

    /** The item this one sits under, when it is not top-level. */
    const val parentId = "parentId"

    /**
     * What a set asks for the item's visibility: [hide] withdraws it (`#never`); [show] puts it on offer under the
     * condition [condition] names -- one of the item's [audiences], since a client may choose an audience the menu
     * names but not write one. Absent leaves the visibility as it is.
     */
    const val visibility = "visibility"
    const val hide = "hide"
    const val show = "show"
}
