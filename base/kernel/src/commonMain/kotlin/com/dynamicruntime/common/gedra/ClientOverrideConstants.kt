package com.dynamicruntime.common.gedra

/**
 * The wire vocabulary of a client's **overrides** (issue #916): the copy (Markdown fragment keys) and the interface
 * (UiBlock items and fields) that its own configuration changes, each with the value it replaces and the config
 * that set it. In the kernel so the Clients page reads the same names the endpoint writes. Each name matches its
 * value. The types are declared in the clients overview module (`CLD.overviewNamespace`), beside the endpoint.
 */
@Suppress("ConstPropertyName")
object COV {
    const val typeName = "ClientOverrides"
    const val copyTypeName = "CopyOverride"
    const val blockTypeName = "BlockOverride"
    const val blockFieldTypeName = "BlockFieldOverride"

    const val client = "client"

    /** The fragment keys the client's layers set. */
    const val copy = "copy"

    /** The UiBlock items and objects the client's layers change. */
    const val blocks = "blocks"

    const val fileId = "fileId"
    const val namespaceField = "namespace"
    const val key = "key"
    const val audience = "audience"

    /** What everybody else gets: the base with the components' overlays, before any of this client's. */
    const val baseValue = "baseValue"

    /** What this client gets. */
    const val value = "value"

    /** The client's source-config value a stored config overrides; present only when one does. */
    const val sourceValue = "sourceValue"

    /** The client config that set the value, and whether it is a source or a stored one. */
    const val configName = "configName"
    const val origin = "origin"

    /**
     * The template the client extends whose configuration set the value, when the client's own did not (issue
     * #945). Its config is source code, so [origin] says `source`, and [configName] names the template's config.
     */
    const val template = "template"

    /** A key no base declares -- usually a renamed base key, which leaves the override silently unused. */
    const val orphan = "orphan"

    /**
     * Where the application shows the file's copy, as its declaration says (issue #933) -- "the home page and app
     * bar". Absent when the file is not shown by this application: a fixture, or one only a deployment's own
     * frontend reads by URL.
     */
    const val shownOn = "shownOn"

    const val blockId = "blockId"

    /** The dotted path to the keyed array holding [itemId], or to the object whose fields changed ("" for the root). */
    const val path = "path"

    /**
     * The item's primary-key value within its keyed array. Absent for a change to an object outside one, and for an
     * item the client adds with no key (then [added] is true and [path] names the array).
     */
    const val itemId = "itemId"

    /** Whether the item is the client's own, not in the block everybody else gets. */
    const val added = "added"

    /** Whether this client's layers withdraw the item (its condition is `#never` here and not for everybody else). */
    const val hidden = "hidden"

    /**
     * The item's `label` in the block everybody else gets, when it has one -- so a row that hides or shows an item
     * can name it, whether or not the client set a label of its own. Absent for an added item or an object.
     */
    const val baseLabel = "baseLabel"

    const val fields = "fields"
    const val field = "field"
}
