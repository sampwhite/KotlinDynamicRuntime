package com.dynamicruntime.common.gedra

import com.dynamicruntime.common.counter.CTR

/**
 * The core config traits (issues #316, #625): the trait ids the storage slots are keyed by, and their data fields.
 * The traits themselves are declared in `base:common` (`CoreConfigTraits.kt`); the names are here so the frontend
 * reads a client's definition -- its traits' metadata, say -- by the names the backend writes (issue #906).
 */
@Suppress("ConstPropertyName")
object CCT {
    const val configName = "coreConfigTraits"

    // --- the storage slots (trait ids) ---

    /** The client definition itself; single-instance, since a bundle defines at most one client. */
    const val clientDef = "kdr:clientDef"

    /** One data-trait **declaration** this configuration contributes, keyed by trait id (issue #625). */
    const val traitDef = "kdr:traitDef"

    /** One trait-usage rule -- how a client presents a trait in a listing -- keyed by trait id (issue #625). */
    const val usageDef = "kdr:usageDef"

    /** One workflow definition, keyed by its workflow id. */
    const val workflowDef = "kdr:workflowDef"

    /** One schema type definition, keyed by the type's name. */
    const val schemaDef = "kdr:schemaDef"

    /** One Markdown-fragment overlay, keyed by the fragment file id (issue #625). */
    const val fragmentDef = "kdr:fragmentDef"

    /** One UiBlock overlay, keyed by the block id (issue #625). */
    const val uiBlockDef = "kdr:uiBlockDef"

    /** One cfact **name declaration** (declaration only, never a production), keyed by name (issue #625). */
    const val cfactDef = "kdr:cfactDef"

    /** One named report, keyed by its report id (issue #979). */
    const val reportDef = "kdr:reportDef"

    /** One customer, keyed by its customer id (issue #1107); only `hub`'s stored configuration holds any. */
    const val customerDef = "kdr:customerDef"

    /** One file ref -- a named file of the client's, by where its bytes are -- keyed by its ref id (issues #1153, #1154). */
    const val fileRefDef = "kdr:fileRefDef"

    /** The client's listing-wide settings (issue #1599); single-instance, as [clientDef] is. See `ListingDef`. */
    const val listingDef = "kdr:listingDef"

    /** One counter -- what numbers a field's ids -- keyed by its counter id (issue #1849). See `CounterDef`. */
    const val counterDef = "kdr:counterDef"

    /** One form type -- the traits a kind of form collects -- keyed by its id (issue #1902). See `FormTypeDef`. */
    const val formTypeDef = "kdr:formTypeDef"

    /**
     * Every slot, in the order the core config traits declare them, with what code handling slots one by one needs of
     * each (issue #1854): the code layer reads which stand over by key, a stored override reads their key fields, and a
     * sandbox overlay's report names an entry by its kind. One line per slot, so a new slot is told here once.
     *
     * Theme: #SLTKW.
     */
    val slotRules: List<ConfigSlotRule> = listOf(
        ConfigSlotRule(clientDef, "definition", null, SlotStandsOver.never),
        ConfigSlotRule(customerDef, "customer", CUST.customerId, SlotStandsOver.never),
        ConfigSlotRule(traitDef, "trait", traitId, SlotStandsOver.byKey),
        ConfigSlotRule(usageDef, "column", traitId, SlotStandsOver.asOneSet),
        ConfigSlotRule(workflowDef, "workflow", workflowId, SlotStandsOver.byKey),
        ConfigSlotRule(reportDef, "report", reportId, SlotStandsOver.byKey),
        ConfigSlotRule(schemaDef, "type", typeName, SlotStandsOver.byKey),
        ConfigSlotRule(fragmentDef, "copy", fileId, SlotStandsOver.never),
        ConfigSlotRule(uiBlockDef, "block", blockId, SlotStandsOver.never),
        ConfigSlotRule(cfactDef, "cfact", name, SlotStandsOver.byKey),
        ConfigSlotRule(fileRefDef, "file ref", refId, SlotStandsOver.byKey),
        ConfigSlotRule(listingDef, "listing settings", null, SlotStandsOver.never),
        ConfigSlotRule(counterDef, "counter", CTR.counterId, SlotStandsOver.byKey),
        ConfigSlotRule(formTypeDef, "form type", FTYPE.formTypeId, SlotStandsOver.byKey),
    )

    /**
     * Every slot, in the order the core config traits declare them (issue #1854). A test holds those traits to this list,
     * and the places that must know every slot -- the bundle editor's labels, the code layer's keys, the sandbox's copy,
     * the client overview, the assistant's prompt -- are held to it in turn, so a slot added here fails each place until
     * it is told.
     *
     * Theme: #SLTKW.
     */
    val slots: List<String> = slotRules.map { it.slot }

    /** The rule of [slot], or null for an id that is no slot. */
    fun slotRule(slot: String): ConfigSlotRule? = slotRules.firstOrNull { it.slot == slot }

    // --- what a stored entry says of the code it stands over (issue #1634) ---

    /**
     * On a stored entry of a keyed slot: that it stands over the code layer's entry with the same key -- the template's
     * copies, the client's own code, a sandbox's code -- the one value being [overridesCodeValue]. Without it, a stored
     * entry whose key matches a code entry is refused at write, and loads as it always has, with an issue saying to
     * mark it. A plain field rather than a `$` note, which a request drops and a stored entry may not hold.
     */
    const val overrides = "overrides"

    /** The value of [overrides] for an entry that stands over the code's, whole. */
    const val overridesCodeValue = "code"

    /**
     * The value of [overrides] for an entry that holds only what it changes of the code's (issue #1657): an overlay,
     * merged over the code's entry as it is now on every read. A workflow or a schema type takes one
     * (`codeOverlaySlots`), and a client's definition takes one that sets only its sandbox (`clientDefOverlayKeys`, issue
     * #1677).
     */
    const val overridesCodeOverlayValue = "codeOverlay"

    // --- field names inside the slots' data (each matches its value) ---

    const val traitId = "traitId"
    const val typeName = "typeName"
    const val appliesTo = "appliesTo"
    const val dataSchema = "dataSchema"
    const val description = "description"

    const val label = "label"
    const val display = "display"
    const val kind = "kind"
    const val substring = "substring"
    const val presentation = "presentation"
    const val columnId = "columnId"

    /** On a usage rule (issue #1855): how much room its column wants, a `ColumnWidth`. */
    const val width = "width"

    const val workflowId = "workflowId"
    const val definition = "definition"

    const val reportId = "reportId"

    const val schema = "schema"

    const val fileId = "fileId"
    const val blockId = "blockId"
    const val content = "content"

    const val name = "name"
    const val group = "group"
    const val toFrontend = "toFrontend"

    const val refId = "refId"
    const val source = "source"
    const val hash = "hash"
    const val root = "root"
    const val path = "path"

    const val workflowLayout = "workflowLayout"
}

/** How an entry of a configuration slot stands over another's of the same slot (issue #1854). */
@Suppress("EnumEntryName")
enum class SlotStandsOver {
    /** Entry by entry, by its key: a client's trait over its template's of the same id, or a stored one over the code's. */
    byKey,

    /** All together: a configuration declaring any columns of the forms list declares all of them. */
    asOneSet,

    /**
     * Never by a key: the slot's one entry stands over the one beneath by itself (the definition, the listing settings),
     * its entries are layered key by key (copy, the menu and Home's cards), or only the platform declares them (customers).
     */
    never,
}

/**
 * What code handling configuration slots one by one needs of [slot] (issue #1854): [kind], what a person calls one of
 * its entries; [keyField], the field that names one, the first of its shape's `g-primaryKey` (null for a slot of one
 * entry, which a test holds to the shape); and how one [standsOver] another.
 */
class ConfigSlotRule(val slot: String, val kind: String, val keyField: String?, val standsOver: SlotStandsOver)
