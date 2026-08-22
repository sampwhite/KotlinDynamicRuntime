package com.dynamicruntime.webapp

import com.dynamicruntime.common.endpoint.HttpMethod
import com.dynamicruntime.common.gedra.GDF
import com.dynamicruntime.common.gedra.GE
import com.dynamicruntime.common.gedra.GEP
import com.dynamicruntime.common.schema.SchType
import com.dynamicruntime.common.util.toJsonListOfMaps

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
 * The trait entry union inside a form-document type -- the `entries` array's element -- or null when [type] is
 * absent or not shaped that way. Both a `FormDocInput` (create) and a `FormDoc` (stored) carry it, so the two
 * pages reach it identically to label each entry's trait.
 */
fun entriesUnionOf(type: SchType?): SchType? = type?.properties?.get(GDF.entries)?.valueType?.itemType

/**
 * The global `name` trait -- "what somebody chose to call this document" -- and the one field it carries. Both
 * are `GT.name` on the backend (`base/common`, not reachable here); each matches its own value like any schema
 * key, so the literals are safe. The two share the word because the trait is *about* the name.
 */
private const val nameTraitId = "name"
private const val nameTraitField = "name"

/**
 * A friendly account of a stored form document (issue #408): the human [title] if it has one, the traits it
 * carries by their picker labels, and when it was created. Shown on the create-success screen and in the list.
 */
class FormSummary(
    val gedraId: String,
    /** The document's own name, or null when it has none -- see [summarizeForm]. */
    val title: String?,
    /** Each entry's trait, by the same friendly label the form's trait picker showed. */
    val traitLabels: List<String>,
    /** When it was created, already formatted for reading; null when the row carried no timestamp. */
    val createdAt: String?,
)

/**
 * Summarizes a form-document row (as the create or list endpoint returns it).
 *
 * A `formDoc` has **no dedicated name field** -- it is a generic bag of trait entries -- so a document's human
 * title, when it has one, comes from the global `name` trait, which exists precisely to hold "what somebody
 * chose to call this document" (`CoreTraits.GT.name`). It is read from the entry carrying that trait id; a form
 * with no such entry -- the client does not support the trait, or nobody filled it in -- is untitled, a
 * legitimate state shown as one rather than filled with a guess.
 *
 * [entriesUnion] (see [entriesUnionOf]) labels each trait the way its picker did (title, or a humanized id).
 * Pure, and covered under `jsNodeTest`.
 */
fun summarizeForm(item: Map<String, Any?>, entriesUnion: SchType?): FormSummary {
    val entries = item[GDF.entries].toJsonListOfMaps()
    val traitLabels = entries.mapNotNull { entry ->
        (entry[GE.traitId] as? String)?.let { traitId ->
            entriesUnion?.variants?.byValue?.get(traitId)?.title ?: humanizeFieldName(traitId)
        }
    }
    // The title is the `name` trait's, matched by trait id -- not any entry that happens to carry a `name`
    // field (a site's, a vendor's), which is a field-level name and would otherwise depend on entry order.
    val title = entries.firstOrNull { it[GE.traitId] == nameTraitId }
        ?.let { (it[GE.data] as? Map<*, *>)?.get(nameTraitField) as? String }
        ?.takeIf { it.isNotBlank() }
    return FormSummary(
        gedraId = item[GDF.gedraId] as? String ?: "(unknown)",
        title = title,
        traitLabels = traitLabels,
        createdAt = (item[GDF.createdAt] as? String)?.let { formatTimestamp(it) },
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
