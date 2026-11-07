package com.dynamicruntime.webapp

import com.dynamicruntime.common.gedra.CCT
import com.dynamicruntime.common.gedra.CIT
import com.dynamicruntime.common.gedra.CLD
import com.dynamicruntime.common.gedra.CPY
import com.dynamicruntime.common.gedra.CUS
import com.dynamicruntime.common.gedra.ClientTraitUsage
import com.dynamicruntime.common.gedra.UsageKind
import com.dynamicruntime.common.gedra.WorkflowLayout
import com.dynamicruntime.common.gedra.sandboxParentOf
import com.dynamicruntime.common.gedra.toRuleMap
import com.dynamicruntime.common.gedra.usageFromRuleMap
import com.dynamicruntime.common.util.checkTemplateSyntax
import com.dynamicruntime.common.util.loneFieldPath
import com.dynamicruntime.common.util.toJsonListOfMaps
import com.dynamicruntime.common.util.toJsonMapOrEmpty
import com.dynamicruntime.common.util.toOptStr
import com.dynamicruntime.common.util.toOptEnum

/**
 * A client's listing columns, on its page (issue #1491; `ClientUsagesSection`): the catalog read, the editor's draft --
 * a trait, a heading, and a field, with a hand-written value and presentation under More options -- its checks, and
 * the save and removal. A column is the kernel's `ClientTraitUsage`, written as its stored entry
 * ([toRuleMap]), so what the page sends is what the backend stores.
 *
 * Everything here is pure, and covered under `jsNodeTest`.
 */

/** A field of a trait a column may show: its key, what a person calls it, how a column of it alone reads, and whether it offers choices. */
class UsageFieldView(val field: String, val label: String, val readsAs: UsageKind, val hasChoices: Boolean)

/** A form trait a column may read, and its fields a column may show. */
class UsageTraitView(val traitId: String, val description: String?, val fields: List<UsageFieldView>) {
    fun field(key: String): UsageFieldView? = fields.firstOrNull { it.field == key }
}

/**
 * One of the client's columns: the column, how its value reads, the stored configuration holding it -- or why it
 * cannot be changed here -- and whether it is stored but not run.
 */
class UsageColumnView(
    val usage: ClientTraitUsage,
    val readsAs: UsageKind,
    val configName: String?,
    val notEditable: String?,
    val notRunning: Boolean = false,
) {
    /** What the page keys it by: its trait and heading, the backend's key (issue #1490). */
    val key: String get() = "${usage.traitId}|${usage.label}"
}

/** The catalog: the client's columns, whether they are the deployment's defaults, and the traits a column may read. */
class UsageCatalogView(
    val clientId: String,
    val columns: List<UsageColumnView>,
    val defaults: Boolean,
    /** The stored configuration a new column goes into: the one holding the client's definition. */
    val newColumnsIn: String?,
    val traits: List<UsageTraitView>,
    /** How the client's forms listing draws each row's workflows, as it runs (issue #1599). */
    val workflowLayout: WorkflowLayout = WorkflowLayout.split,
    /** The stored configuration holding the client's listing settings; null when none does, and the code's (or the default) holds. */
    val layoutConfigName: String? = null,
) {
    fun trait(traitId: String): UsageTraitView? = traits.firstOrNull { it.traitId == traitId }

    fun column(key: String): UsageColumnView? = columns.firstOrNull { it.key == key }
}

private fun kindOf(raw: Any?): UsageKind = UsageKind.entries.firstOrNull { it.name == raw.toOptStr() } ?: UsageKind.string

fun parseUsageCatalog(raw: Map<String, Any?>): UsageCatalogView = UsageCatalogView(
    clientId = raw[CLD.client].toOptStr().orEmpty(),
    columns = raw[CUS.columns].toJsonListOfMaps().map {
        UsageColumnView(
            usageFromRuleMap(it), kindOf(it[CUS.readsAs]), it[CUS.configName].toOptStr(), it[CUS.notEditable].toOptStr(),
            it[CUS.notRunning] == true,
        )
    },
    defaults = raw[CUS.defaults] == true,
    newColumnsIn = raw[CUS.newColumnsIn].toOptStr(),
    workflowLayout = raw[CUS.workflowLayout].toOptEnum<WorkflowLayout>() ?: WorkflowLayout.split,
    layoutConfigName = raw[CUS.layoutConfigName].toOptStr(),
    traits = raw[CUS.traits].toJsonListOfMaps().map { t ->
        UsageTraitView(
            t[CCT.traitId].toOptStr().orEmpty(),
            t[CUS.description].toOptStr(),
            t[CUS.fields].toJsonListOfMaps().map {
                UsageFieldView(
                    it[CUS.field].toOptStr().orEmpty(), it[CUS.label].toOptStr().orEmpty(), kindOf(it[CUS.readsAs]),
                    it[CUS.hasChoices] == true,
                )
            },
        )
    },
)

/** How a column reads, in a word or two. */
fun usageKindText(kind: UsageKind): String = when (kind) {
    UsageKind.string -> "Text"
    UsageKind.number -> "Number"
    UsageKind.date -> "Date"
    UsageKind.boolean -> "Yes or no"
}

/**
 * How a column reading as [kind] is searched and shown, in a sentence, beside the editor's field; [hasChoices] for a field
 * with choices.
 */
fun usageReadsAsText(kind: UsageKind, hasChoices: Boolean): String = when (kind) {
    UsageKind.string ->
        if (hasChoices) "Text: its search offers the field's choices, and its cells show their labels."
        else "Text: searched for what is typed."
    UsageKind.number -> "A number: searched by a range, and sorted as a number."
    UsageKind.date -> "A date: searched by a range of days, and shown relative to today."
    UsageKind.boolean -> "Yes or no: searched by a Yes or No choice."
}

/** The field a lone `${field}` [display] shows, when it is one of [trait]'s; null for a hand-written value. */
fun usageField(display: String, trait: UsageTraitView?): UsageFieldView? =
    display.loneFieldPath()?.singleOrNull()?.let { trait?.field(it) }

/** What a column shows, for the table: its field's name, else its hand-written value. */
fun usageShowsText(usage: ClientTraitUsage, trait: UsageTraitView?): String = usageField(usage.display, trait)?.label ?: usage.display

/** What the editor holds: the column as it is being written, before it is sent. */
data class UsageDraft(
    val traitId: String,
    val label: String,
    /** The value, a string script: `${field}` when a field is picked. */
    val display: String,
    /** How the value reads, when the column says; null takes it from the field, and is text for a hand-written value. */
    val kind: UsageKind?,
    val substring: Boolean,
    /** How the cell reads, a string script with the value as `value`; blank for none. */
    val presentation: String,
) {
    fun toUsage(): ClientTraitUsage =
        ClientTraitUsage(traitId, label.trim(), display.trim(), kind, substring, presentation.trim().ifBlank { null })

    /**
     * This draft reading [field]: its value `${field}`, its kind the field's, and its heading the field's when it has none
     * yet.
     */
    fun withField(field: UsageFieldView): UsageDraft =
        copy(display = $$"${$${field.field}}", kind = null, label = label.ifBlank { field.label })

    /** This draft reading [traitId], with no field picked yet. */
    fun withTrait(traitId: String): UsageDraft = if (traitId == this.traitId) this else copy(traitId = traitId, display = "", kind = null)
}

/** A new column's draft: the first trait a column may read, nothing chosen yet. */
fun newUsageDraft(catalog: UsageCatalogView): UsageDraft =
    UsageDraft(catalog.traits.firstOrNull()?.traitId.orEmpty(), "", "", null, false, "")

/** The draft of [column] as it stands. */
fun usageDraftOf(column: UsageColumnView): UsageDraft = with(column.usage) {
    UsageDraft(traitId, label, display, kind, substring, presentation.orEmpty())
}

/**
 * How [draft] would read: the kernel's rule (`resolveUsageColumn`) over the catalog's fields -- a lone `${field}`
 * reads as its field, unless the column declares a kind the field contradicts, and a hand-written value as its declared
 * kind, else as text.
 */
fun usageDraftReadsAs(draft: UsageDraft, trait: UsageTraitView?): UsageKind {
    val field = usageField(draft.display, trait)
    return if (field != null && (draft.kind == null || draft.kind == field.readsAs)) field.readsAs else draft.kind ?: UsageKind.string
}

/**
 * What [draft] sets under More options, each said shortly, for its summary line -- which opens it while any is set
 * (`MoreOptions`): a value written by hand, how such a value reads, and a presentation.
 */
fun usageOptionsInForce(draft: UsageDraft, trait: UsageTraitView?): List<String> = buildList {
    if (draft.display.isNotBlank() && usageField(draft.display, trait) == null) add("value written by hand")
    draft.kind?.let { if (usageField(draft.display, trait) == null) add("reads as ${usageKindText(it).lowercase()}") }
    if (draft.presentation.isNotBlank()) add("presentation")
}

/**
 * What is wrong with [draft], in words, for the editor to list before Save: a trait, a heading, and a value are needed;
 * a heading its trait already has is taken (the backend's key, issue #1490); and a value or presentation that does
 * not parse is said with where. [editing] is the column being changed, whose own heading is not taken.
 */
fun usageDraftProblems(draft: UsageDraft, catalog: UsageCatalogView, editing: UsageColumnView?): List<String> = buildList {
    if (catalog.trait(draft.traitId) == null && editing == null) add("Choose the trait the column reads.")
    val label = draft.label.trim()
    if (label.isEmpty()) add("Give the column a heading.")
    val taken = catalog.columns.any { it !== editing && it.usage.traitId == draft.traitId && it.usage.label == label }
    if (label.isNotEmpty() && taken) add("Trait ${draft.traitId} already has a column headed “$label”.")
    if (draft.display.isBlank()) add("Pick the field it shows, or write its value under More options.")
    draft.display.checkTemplateSyntax().firstOrNull()?.let { add("The value does not read: ${it.message} (column ${it.col}).") }
    draft.presentation.checkTemplateSyntax().firstOrNull()?.let { add("The presentation does not read: ${it.message} (column ${it.col}).") }
}

/** Whether [draft] differs from the [column] it was opened on; a new column always does. */
fun usageDraftChanged(draft: UsageDraft, column: UsageColumnView?): Boolean =
    column == null || draft.toUsage().toRuleMap() != column.usage.copyWithoutColumnId().toRuleMap()

private fun ClientTraitUsage.copyWithoutColumnId() = ClientTraitUsage(traitId, label, display, kind, substring, presentation)

/**
 * The save's request: the column as it should be stored, the heading of the one it changes ([previousLabel], null for
 * a new column), and how the page expects it to take effect. A text column's contains search is sent only where it
 * applies.
 */
fun usageSaveRequest(
    clientId: String,
    draft: UsageDraft,
    trait: UsageTraitView?,
    previousLabel: String?,
    expected: String,
): Map<String, Any?> = buildMap {
    put(CLD.client, clientId)
    val usage = draft.toUsage()
    val text = usageDraftReadsAs(draft, trait) == UsageKind.string
    val sent = ClientTraitUsage(usage.traitId, usage.label, usage.display, usage.kind, usage.substring && text, usage.presentation)
    put(CUS.column, sent.toRuleMap())
    previousLabel?.let { put(CUS.previousLabel, it) }
    put(CPY.mode, expected)
}

/** What a save did: the configuration the column is in, the column as saved, and how it took effect. */
class UsageSaveResult(val configName: String, val usage: ClientTraitUsage, val mode: String)

fun parseUsageSaveResult(raw: Map<String, Any?>): UsageSaveResult = UsageSaveResult(
    raw[CIT.configName].toOptStr().orEmpty(),
    usageFromRuleMap(raw[CUS.column].toJsonMapOrEmpty()),
    raw[CPY.mode].toOptStr().orEmpty(),
)

/** Whether the page offers to change or remove [column]: one the client stores, or one of the defaults it would make its own. */
fun usageEditable(column: UsageColumnView): Boolean = column.notEditable == null

/**
 * Why a column cannot be removed though it can be changed, or null: the one default left while the client has none of
 * its own, since a listing with no column of its own shows the defaults again.
 */
fun usageRemoveBlocked(catalog: UsageCatalogView): String? = if (catalog.defaults && catalog.columns.size == 1) {
    "It is the deployment's default, shown while this client has no column of its own. Add one, and it can then be removed."
} else {
    null
}

/** The question a removal asks first, naming where it acts (issue #1049's rule): the live client, or the draft of it. */
fun usageRemoveQuestion(column: UsageColumnView, clientId: String, draft: Boolean): String =
    if (draft) "Remove the column “${column.usage.label}” from the draft of ${sandboxParentOf(clientId) ?: clientId}?"
    else "Remove the column “${column.usage.label}” of ${deleteTargetText(clientId)}?"

/**
 * What removing [column] does, under [usageRemoveQuestion]: when it is the client's last column of its own, the
 * deployment's defaults come back; when the client shows the defaults, the others become its own.
 */
fun usageRemoveConsequence(column: UsageColumnView, catalog: UsageCatalogView, draft: Boolean): String {
    val own = catalog.columns.count { it.configName != null }
    val what = when {
        catalog.defaults -> "The other default columns become this client's own."
        own == 1 && column.configName != null ->
            "It is this client's last column of its own, so the deployment's default columns come back."
        else -> "Its forms keep their data; only the listing changes."
    }
    return if (draft) "$what The client's people see it once the draft is published." else what
}

/** The removal's request: the column, by its trait and heading, and how the page expects it to take effect. */
fun usageRemoveRequest(clientId: String, column: UsageColumnView, expected: String): Map<String, Any?> = mapOf(
    CLD.client to clientId,
    CCT.traitId to column.usage.traitId,
    CCT.label to column.usage.label,
    CPY.mode to expected,
)

/** The request that sets [clientId]'s workflow [layout] (issue #1599), saying how its caller told its user it takes effect. */
fun usageLayoutRequest(clientId: String, layout: WorkflowLayout, expected: String): Map<String, Any?> = mapOf(
    CLD.client to clientId,
    CUS.workflowLayout to layout.name,
    CPY.mode to expected,
)

/** How a workflow layout reads in the Clients page's choice of it (issue #1599). */
fun workflowLayoutLabel(layout: WorkflowLayout): String = when (layout) {
    WorkflowLayout.split -> "Your turn column, then the rest"
    WorkflowLayout.combined -> "One Workflows column"
}

/** What the section says above the table: where new columns go, and -- for a client showing them -- that the defaults become its own. */
fun usageSectionNote(catalog: UsageCatalogView): String = buildString {
    append("The columns of this client's forms listing, each a trait's value, searched and sorted by how it reads.")
    if (catalog.defaults) {
        append(" These are the deployment's default columns, shown while the client has none of its own; ")
        append("changing one, or adding one, makes them its own.")
    }
    catalog.newColumnsIn?.let { append(" A new column is stored in configuration $it, with the client's definition.") }
}
