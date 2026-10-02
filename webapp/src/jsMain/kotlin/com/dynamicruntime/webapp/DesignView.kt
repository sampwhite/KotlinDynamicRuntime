package com.dynamicruntime.webapp

import com.dynamicruntime.common.gedra.CCT
import com.dynamicruntime.common.gedra.DSV
import com.dynamicruntime.common.gedra.DesignOrigin
import com.dynamicruntime.common.schema.SCH
import com.dynamicruntime.common.schema.SL
import com.dynamicruntime.common.schema.SchLayoutField
import com.dynamicruntime.common.schema.SchProperty
import com.dynamicruntime.common.schema.SchType
import com.dynamicruntime.common.util.toJsonListOfMaps
import com.dynamicruntime.common.util.toJsonMapOrEmpty
import com.dynamicruntime.common.util.toOptStr
import react.createContext
import react.useEffect
import react.useRef

/*
 * Design View, the webapp's half (issue #972): an administrator's mode that marks each part of a workflow form with
 * the definition it comes from, and opens any of them in a side inspector. Read-only.
 *
 * The page never works out where a definition came from. It asks for Design View with a request header, and the
 * backend -- which alone knows how configuration was merged -- answers with an address and an origin for every
 * type the page draws (the workflow view's `design` block). What the page does decide is its *own* rendering: which
 * fields it hid and why, since those decisions are made here (see `FieldHidden`).
 */

// --- the switch -------------------------------------------------------------------------------------------------

/**
 * Whether this browser session has Design View on. Kept in `sessionStorage` like the debug tags (see [Http]): a
 * mode for a working session, surviving a reload but not the browser. Read on every request, which then carries
 * the view header; the backend honors it only for a client administrator, so it is safe to send from anyone.
 */
private const val designViewStorageKey = "kdrDesignView"

fun designViewRequested(): Boolean = sessionStorageGet(designViewStorageKey) == DSV.design

fun setDesignViewRequested(on: Boolean) = sessionStorageSet(designViewStorageKey, if (on) DSV.design else "")

/**
 * Re-reads a workflow page's view when Design View is switched while the page is open (issue #972). The view only
 * carries Design View's block when it was fetched with the switch on, so a page loaded before the switch would
 * otherwise show nothing until it was left and reopened. [reread] fetches and swaps the view **in place** -- never
 * through the page's loading state -- so a form keeps whatever has been typed into it; it is skipped on the first
 * render, where the page's own load already asked with the switch as it stands. A re-read that gets no usable answer
 * should leave the page as it was (see [designRereadValue]): the switch is a lens, not something to fail a page over.
 */
fun useDesignViewReread(reread: suspend () -> Unit) {
    val designOn = designViewRequested()
    val applied = useRef(designOn)
    useEffect(designOn) {
        if (applied.current == designOn) return@useEffect
        applied.current = designOn
        reread()
    }
}

/** A Design View re-read's view, or null -- the failure said on the console -- when the call got no usable answer. */
fun <T> ApiResult<T>.designRereadValue(): T? {
    failureOrNull()?.let { console.warn("$errorLogPrefix could not re-read the view for Design View: ${it.message}") }
    return valueOrNull()
}

// --- what the backend sends -------------------------------------------------------------------------------------

/**
 * A definition's address and where it was declared, as the backend's Design View block gives it: the config [slot]
 * and [key] that name the definition, the [path] within its entry where the addressed part sits, the [origin] (a
 * [DesignOrigin] name), the [config] that declares it, and whether it is [editable] in place.
 */
class DesignAddress(
    val slot: String,
    val key: String,
    val path: String?,
    val origin: String,
    val config: String?,
    val editable: Boolean,
) {
    /** This address with [more] appended to its path -- a field inside the definition's body. */
    fun below(more: List<String>): DesignAddress =
        if (more.isEmpty()) this else DesignAddress(slot, key, (listOfNotNull(path) + more).joinToString("."), origin, config, editable)
}

/** A workflow view's Design View block: the workflow's own address, and each carried type's by qualified name. */
class WfDesign(val workflow: DesignAddress?, val types: Map<String, DesignAddress>)

fun parseDesignAddress(raw: Any?): DesignAddress? {
    val m = raw.toJsonMapOrEmpty()
    val slot = m[DSV.slot].toOptStr() ?: return null
    val key = m[DSV.key].toOptStr() ?: return null
    return DesignAddress(
        slot, key, m[DSV.path].toOptStr(), m[DSV.origin].toOptStr() ?: DesignOrigin.global.name,
        m[DSV.config].toOptStr(), m[DSV.editable] == true,
    )
}

/** The view's Design View block, or null when the response carries none -- an ordinary view. */
fun parseWfDesign(raw: Any?): WfDesign? {
    val m = raw as? Map<*, *> ?: return null
    val block = m.toJsonMapOrEmpty()
    return WfDesign(
        parseDesignAddress(block[DSV.workflow]),
        block[DSV.types].toJsonMapOrEmpty().mapNotNull { (k, v) -> parseDesignAddress(v)?.let { k to it } }.toMap(),
    )
}

// --- what the inspector is pointed at ---------------------------------------------------------------------------

/**
 * Why a form left a field out (issue #972) -- each is a decision [SchemaForm] makes itself, so it is the webapp, not
 * the backend, that names it. Design View draws such a field as a ghost carrying its [reason].
 */
@Suppress("EnumEntryName")
enum class FieldHidden(val reason: String) {
    /** The page asked the form to leave this root field off. */
    omitted("This page leaves the field off its form."),

    /** Computed by the system, so a data-entry form does not offer it. */
    derived("The system computes this value, so nobody enters it."),

    /** Its `g-visibleWhen` fails for this viewer -- someone else would see it. */
    notForViewer("Hidden from you by its visibility rule; a viewer it allows would see it."),

    /** Forbidden by the type's conditional rule under the current answers, and empty. */
    forbidden("Not asked for under the current answers."),

    /** An authoritative layout lists the fields the form shows, and this is not one of them. */
    notInLayout("The layout leaves this field out."),
}

/** What the inspector shows: the workflow, one trait of a task, or one field of a trait's form. */
sealed class DesignTarget {
    /** The workflow itself. */
    object Workflow : DesignTarget()

    class Trait(val trait: WfTraitView) : DesignTarget()

    /**
     * A field: its [path] within the trait's data (the form's own path, `a.b`), its property, whether its object
     * requires it, the layout entry its copy comes from, and -- for a ghost -- why the form left it out. [root] is
     * the trait the form draws.
     */
    class Field(
        val root: WfTraitView,
        val path: String,
        val name: String,
        val prop: SchProperty,
        val required: Boolean,
        val layout: SchLayoutField?,
        val hidden: FieldHidden?,
    ) : DesignTarget()

    /** A key the selection can be compared by -- one per distinct thing on the page. */
    val id: String
        get() = when (this) {
            Workflow -> "wf"
            is Trait -> "trait:${trait.traitId}"
            is Field -> "field:${root.traitId}:$path"
        }
}

/**
 * Where a field is declared (issue #972): the named type that owns it ([typeName]), and the field's place within
 * that type's body ([schemaPath]). What [fieldOwner] answers.
 */
class FieldOwner(val typeName: String, val schemaPath: List<String>)

/**
 * The named type the field at [dataPath] of [rootType] (named [rootName]) belongs to, and the field's place within
 * that type's body (issue #972). The form addresses a field by its data path (`contact.email`); a definition is
 * addressed by the type that declares the field, so walking down, a property whose value is a **named** type (`$ref`)
 * starts a new owner. An array steps into its items. Pure, so a `jsNodeTest` pins it.
 */
fun fieldOwner(rootName: String, rootType: SchType, dataPath: String): FieldOwner {
    var owner = rootName
    var at = mutableListOf<String>()
    var type: SchType? = rootType
    val segments = dataPath.split('.').filter { it.isNotEmpty() }.map { it.substringBefore('[') }
    segments.forEachIndexed { i, segment ->
        val prop = type?.properties?.get(segment)
        at.add(SCH.properties)
        at.add(segment)
        if (i == segments.lastIndex || prop == null) return@forEachIndexed
        var next = prop.valueType
        if (prop.refName != null && next.name != null) {
            owner = next.name!!
            at = mutableListOf()
        }
        next.itemType?.let { item ->
            at.add(SCH.items)
            if (item.name != null) {
                owner = item.name!!
                at = mutableListOf()
            }
            next = item
        }
        type = next
    }
    return FieldOwner(owner, at)
}

/** A definition's origin, said for the inspector: who owns it and whether it can be edited in place. */
fun originText(address: DesignAddress): String = when (address.origin) {
    DesignOrigin.stored.name -> "This client's stored configuration" + (address.config?.let { " ($it)" } ?: "")
    DesignOrigin.source.name -> "Defined in code for this client" + (address.config?.let { " ($it)" } ?: "")
    else -> "Global — shared by every client" + (address.config?.let { " ($it)" } ?: "")
}

/** A slot's name for a reader: `kdr:traitDef` reads "trait". */
fun slotWord(slot: String): String = when (slot) {
    CCT.traitDef -> "trait"
    CCT.schemaDef -> "type"
    CCT.workflowDef -> "workflow"
    else -> slot.substringAfter(':')
}

/**
 * An address for people -- "trait eventRequest › properties › venue" -- never parsed back. The path's structural
 * words (`dataSchema`, `properties`, `items`) are dropped, since a reader wants the names, not the JSON Schema.
 */
fun addressLine(address: DesignAddress): String {
    val parts = (address.path ?: "").split('.').filter { it.isNotEmpty() && it !in structuralWords }
    return (listOf("${slotWord(address.slot)} ${address.key}") + parts).joinToString(" › ")
}

private val structuralWords = setOf(CCT.dataSchema, CCT.schema, SCH.properties, SCH.items)

/**
 * The part of an authored [entry] at [path] (dotted), or null when the entry has nothing there. A layout entry is
 * found by its `field` within `g-layout`'s list, which is how the inspector shows a field's layout copy beside its
 * schema.
 */
fun subtreeAt(entry: Map<String, Any?>, path: String?): Any? {
    var at: Any? = entry
    for (segment in (path ?: "").split('.').filter { it.isNotEmpty() }) {
        at = (at as? Map<*, *>)?.get(segment) ?: return null
    }
    return at
}

/** The layout entry for [field] in a type body's `g-layout`, or null. */
fun layoutEntryIn(body: Any?, field: String): Map<String, Any?>? =
    body.toJsonMapOrEmpty()[SCH.layout].toJsonMapOrEmpty()[SL.schemaFields].toJsonListOfMaps()
        .firstOrNull { it[SL.field] == field }

// --- the session the form and the inspector share ---------------------------------------------------------------

/**
 * A Design View session on one workflow page: the backend's [design] block, what is selected, and the two display
 * switches. Provided by the workflow form to every [SchemaForm] under it through [DesignViewContext]; null there --
 * the default -- means no Design View, and the forms draw exactly as they always have.
 */
class DesignSession(
    val design: WfDesign,
    val selected: DesignTarget?,
    val select: (DesignTarget) -> Unit,
    val showAllIds: Boolean,
    val showHidden: Boolean,
    /** The trait the enclosing form draws -- set per trait by the workflow form, so a field can name its root. */
    val trait: WfTraitView? = null,
) {
    fun forTrait(t: WfTraitView): DesignSession = DesignSession(design, selected, select, showAllIds, showHidden, t)

    fun isSelected(target: DesignTarget): Boolean = selected?.id == target.id
}

val DesignViewContext = createContext<DesignSession?>(null)
