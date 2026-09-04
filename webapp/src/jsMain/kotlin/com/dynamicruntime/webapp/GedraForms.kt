package com.dynamicruntime.webapp

import com.dynamicruntime.common.endpoint.HttpMethod
import com.dynamicruntime.common.gedra.DUF
import com.dynamicruntime.common.gedra.GDF
import com.dynamicruntime.common.gedra.GE
import com.dynamicruntime.common.gedra.GED
import com.dynamicruntime.common.gedra.GEP
import com.dynamicruntime.common.gedra.UF
import com.dynamicruntime.common.gedra.GPF
import com.dynamicruntime.common.gedra.GedraDataType
import com.dynamicruntime.common.gedra.GedraEditAction
import com.dynamicruntime.common.schema.SchType
import com.dynamicruntime.common.util.toJsonListOfMaps
import com.dynamicruntime.common.util.toJsonMapOrEmpty

/*
 * Shared helpers for the gedra form-document pages (issue #408): discovering the client-scoped endpoints in the
 * caller's catalog, reaching the entry union that labels traits, and summarizing a stored form. Both the create
 * page and the list/view page read a `formDoc` the same way, so this is their one home for it.
 */

/**
 * [path] with its leading **section** segment removed: `/gedra/formDoc/create` -> `/formDoc/create`. This is
 * the part a client-scoped path shares with the shared one, since a client is inserted *after* the section
 * (`/gedra/acme/formDoc/create`; see the kernel's `clientPath`).
 *
 * The section is dropped by **position, not by name** -- cutting a literal `"/gedra"` would silently break the
 * day the section is renamed (`substringAfter` returns the whole string when its delimiter is absent). Pure,
 * and covered under `jsNodeTest` against a renamed section.
 */
fun pathAfterSection(path: String): String = "/" + path.removePrefix("/").substringAfter('/')

private val formCreateSuffix: String = pathAfterSection(GEP.formDocCreate)
private val formsListSuffix: String = pathAfterSection(GEP.formDocs)
private val formGetSuffix: String = pathAfterSection(GEP.formDoc)
private val patchSuffix: String = pathAfterSection(GEP.patch)

/**
 * The endpoint that creates a form document, from the caller's own catalog.
 *
 * Matched by its trait suffix, not the bare `GEP.formDocCreate`: `/schema/endpoints` answers with the caller's
 * **client-scoped** path (`/gedra/<client>/formDoc/create`), which is the whole point -- a form built from and
 * posted to that path is narrowed to the client by construction (issues #387, #393). Null when the caller's
 * surface carries no such endpoint. Pure, and covered under `jsNodeTest`.
 */
fun findFormCreateEndpoint(endpoints: List<EndpointInfo>): EndpointInfo? =
    endpoints.firstOrNull { it.method == HttpMethod.POST.name && it.path.endsWith(formCreateSuffix) }

/** The endpoint that lists the caller's form documents, matched the same client-scoped way as the create one. */
fun findFormsListEndpoint(endpoints: List<EndpointInfo>): EndpointInfo? =
    endpoints.firstOrNull { it.method == HttpMethod.GET.name && it.path.endsWith(formsListSuffix) }

/**
 * The endpoint that fetches **one** form document by id (`GET /gedra/<client>/formDoc`). Distinct from the list
 * by its suffix (`/formDoc`, no trailing `s`) and from the same path's DELETE by method. Lets the view resolve
 * a form the loaded list page does not hold -- a bookmark, or a link to a form now past the first page.
 */
fun findFormGetEndpoint(endpoints: List<EndpointInfo>): EndpointInfo? =
    endpoints.firstOrNull { it.method == HttpMethod.GET.name && it.path.endsWith(formGetSuffix) }

/**
 * The endpoint that deletes one form document (`DELETE /gedra/<client>/formDoc`, issue #408). Shares its path
 * with the single-form GET and is told apart by the DELETE method -- the same `path:method` split the backend
 * uses (issue #335). Null when the caller's surface carries no delete.
 */
fun findFormDeleteEndpoint(endpoints: List<EndpointInfo>): EndpointInfo? =
    endpoints.firstOrNull { it.method == HttpMethod.DELETE.name && it.path.endsWith(formGetSuffix) }

/**
 * The endpoint that **patches** gedras (`POST /gedra/<client>/patch`, issue #337) -- what an edit sends. Found
 * the same client-scoped way as the others; its `/patch` suffix is unique on the surface. Null when the
 * caller's surface carries no patch. Pure, and covered under `jsNodeTest`.
 */
fun findFormPatchEndpoint(endpoints: List<EndpointInfo>): EndpointInfo? =
    endpoints.firstOrNull { it.method == HttpMethod.POST.name && it.path.endsWith(patchSuffix) }

/**
 * The **one-target** shape inside the patch input -- `PatchTarget` (`{ gedraId, edits: [<edit union>] }`) for
 * the form-document kind -- reached from the patch endpoint's input type (issue #417).
 *
 * The edit page renders *this* rather than the whole patch envelope: a person editing one form should fill in
 * that form's edits, not assemble the `targets`-grouped-by-kind wrapper, which [formDocPatchBody] adds back on
 * submit. Navigated structurally (`targets` -> the form-document group -> its element) so it follows a rename
 * of the wrapper's own field names. Null when the type is absent or not shaped this way.
 */
fun formDocPatchTargetType(patchInput: SchType?): SchType? {
    val targets = patchInput?.properties?.get(GPF.targets)?.valueType ?: return null
    val group = targets.properties[GedraDataType.formDoc.name]?.valueType ?: return null
    return group.itemType
}

/**
 * The edits that reproduce a stored form's current entries as a starting point for editing (issue #417): each
 * entry becomes an [GedraEditAction.addOrReplace] carrying its trait and its data. So the edit form opens
 * showing what the form holds now; the user changes a field, adds an entry, or switches one to
 * [GedraEditAction.deleteOrNoOp] to remove it. Pure, and covered under `jsNodeTest`.
 *
 * **The stored `entryId` is deliberately not seeded.** A gedra holds at most one entry per trait
 * (`checkOneEntryPerTrait`), so an absent `entryId` already means "the entry this trait names, or a new one"
 * (see [GE.entryId] / `GedraEdit`) — seeding it is redundant, and it actively broke switching a section's trait:
 * the id names an entry *of the old trait*, and carrying it onto the new one made the patch fail ("names entry
 * '…', but the gedra holds no entry of that trait"). Leaving it absent lets both a plain edit and a trait switch
 * resolve to the right entry.
 */
fun seededEdits(form: Map<String, Any?>): List<Map<String, Any?>> =
    form[GDF.entries].toJsonListOfMaps().mapNotNull { entry ->
        val traitId = entry[GE.traitId] as? String ?: return@mapNotNull null
        buildMap {
            put(GED.action, GedraEditAction.addOrReplace.name)
            put(GE.traitId, traitId)
            (entry[GE.data] as? Map<*, *>)?.let { put(GE.data, it) }
        }
    }

/**
 * Wraps one edited [target] (`{ gedraId, edits }`) back into the patch endpoint's `targets`-grouped-by-kind
 * body (issue #417) -- the inverse of [formDocPatchTargetType]. The edit page edits a single form, so this is
 * always one target under the form-document kind. Pure, and covered under `jsNodeTest`.
 */
fun formDocPatchBody(target: Map<String, Any?>): Map<String, Any?> =
    mapOf(GPF.targets to mapOf(GedraDataType.formDoc.name to listOf(target)))

/**
 * The trait labels a patch response reports as **applied** (`outcomes` with `applied = true`), across the
 * patched gedras, by the same friendly label the form's trait picker showed (issue #417). Empty when a patch
 * changed nothing -- which the edit page reports as "no changes" rather than a false success. Pure, and covered
 * under `jsNodeTest`.
 */
fun appliedTraitLabels(patched: List<Map<String, Any?>>, entriesUnion: SchType?): List<String> =
    patched.flatMap { it[GPF.outcomes].toJsonListOfMaps() }
        .filter { it[GPF.applied] == true }
        .mapNotNull { it[GE.traitId] as? String }
        .map { traitId -> entriesUnion?.variants?.byValue?.get(traitId)?.title ?: humanizeFieldName(traitId) }

/**
 * The trait entry union inside a form-document type -- the `entries` array's element -- or null when [type] is
 * absent or not shaped that way. Both a `FormDocInput` (create) and a `FormDoc` (stored) carry it, so the two
 * pages reach it identically to label each entry's trait.
 */
fun entriesUnionOf(type: SchType?): SchType? = type?.properties?.get(GDF.entries)?.valueType?.itemType

/**
 * A friendly account of a stored form document (issue #408): the human [title] if it has one, the traits it
 * carries by their picker labels, and when it was created. Shown on the create-success screen and in the list.
 */
/** One computed display value from a client's trait-usage rule (issue #537): a column and its cell. */
class DisplayValue(val traitId: String, val label: String, val value: String)

class FormSummary(
    val gedraId: String,
    /**
     * The document's heading, or null when it has none: the first display value the client's usage rules
     * produced (issue #537). A client that declares no usage rule -- like the trait-picker fallback's global
     * client -- has none, and the document is shown unnamed, as before.
     */
    val title: String?,
    /** The client's declared display columns for this row: label and value, in the client's order (issue #537). */
    val displayValues: List<DisplayValue>,
    /** Each entry's trait, by the same friendly label the form's trait picker showed. */
    val traitLabels: List<String>,
    /** When it was created, already formatted for reading; null when the row carried no timestamp. */
    val createdAt: String?,
    /** When it was last written, formatted like [createdAt]; null when the row carried no timestamp (issue #562). */
    val updatedAt: String? = null,
    /**
     * The owner's display name, from the row's `owner` block (issue #580): present only for a caller who sees
     * other users' documents, and only when the account has a name that is not its email. Null otherwise.
     */
    val ownerName: String? = null,
    /** The owner's email, from the row's `owner` block (issue #580); null for an ordinary caller's own rows. */
    val ownerEmail: String? = null,
)

/**
 * Summarizes a form-document row (as the create or list endpoint returns it).
 *
 * A `formDoc` has **no dedicated name field** -- it is a generic bag of trait entries -- so what it presents
 * as is whatever the client's **trait-usage rules** declared (issue #537), computed on the backend and
 * attached to each row as `displayValues`. The heading is the first non-blank of those; a client that declared
 * no usage rule has none, and the document is shown unnamed -- a legitimate state, not filled with a guess.
 *
 * [entriesUnion] (see [entriesUnionOf]) labels each trait the way its picker did (title, or a humanized id).
 * Pure, and covered under `jsNodeTest`.
 */
fun summarizeForm(item: Map<String, Any?>, entriesUnion: SchType?): FormSummary {
    val entries = item[GDF.entries].toJsonListOfMaps()
    val owner = item[GDF.owner].toJsonMapOrEmpty()
    val traitLabels = entries.mapNotNull { entry ->
        (entry[GE.traitId] as? String)?.let { traitId ->
            entriesUnion?.variants?.byValue?.get(traitId)?.title ?: humanizeFieldName(traitId)
        }
    }
    // The columns a client's usage rules declared, computed on the backend and attached per row (issue
    // #537). The heading is the first non-blank one -- the form's presentation "name", now client-declared
    // rather than the `name` trait hardcoded here.
    val displayValues = item[GDF.displayValues].toJsonListOfMaps().map {
        DisplayValue(
            traitId = it[UF.traitId] as? String ?: "",
            label = it[UF.label] as? String ?: "",
            value = it[UF.value] as? String ?: "",
        )
    }
    val title = displayValues.firstOrNull { it.value.isNotBlank() }?.value
    return FormSummary(
        gedraId = item[GDF.gedraId] as? String ?: "(unknown)",
        title = title,
        displayValues = displayValues,
        traitLabels = traitLabels,
        createdAt = (item[GDF.createdAt] as? String)?.let { formatTimestamp(it) },
        updatedAt = (item[GDF.updatedAt] as? String)?.let { formatTimestamp(it) },
        // The owner's name and email come from the row's `owner` block (issue #580), attached only for a caller
        // who may see other users' documents. Kept as two flat summary fields, since the User column and the
        // read-only view read them one at a time; a block absent (an ordinary caller's own row) leaves both null.
        ownerName = owner[DUF.name] as? String,
        ownerEmail = owner[DUF.email] as? String,
    )
}

/**
 * A wire timestamp shown to a person: `2026-08-21T19:49:51.568Z` -> `2026-08-21 19:49 UTC`. Minute precision,
 * and the wire is UTC so it is labeled as such rather than pretending to be local. A value not shaped like an
 * ISO timestamp is returned unchanged rather than sliced into nonsense. Pure, and covered under `jsNodeTest`.
 */
fun formatTimestamp(iso: String): String {
    if (iso.length < 16 || iso[10] != 'T') return iso
    return iso.substring(0, 10) + " " + iso.substring(11, 16) + " UTC"
}
