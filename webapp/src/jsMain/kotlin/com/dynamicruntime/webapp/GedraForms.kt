package com.dynamicruntime.webapp

import com.dynamicruntime.common.endpoint.EI
import com.dynamicruntime.common.endpoint.clientPath
import com.dynamicruntime.common.endpoint.EP
import com.dynamicruntime.common.endpoint.HttpMethod
import com.dynamicruntime.common.gedra.DUF
import com.dynamicruntime.common.gedra.GDF
import com.dynamicruntime.common.gedra.GE
import com.dynamicruntime.common.gedra.GED
import com.dynamicruntime.common.gedra.GEP
import com.dynamicruntime.common.gedra.GSORT
import com.dynamicruntime.common.gedra.UF
import com.dynamicruntime.common.gedra.GPF
import com.dynamicruntime.common.gedra.GedraDataType
import com.dynamicruntime.common.gedra.GedraEditAction
import com.dynamicruntime.common.gedra.GedraId
import com.dynamicruntime.common.gedra.workflow.SVY
import com.dynamicruntime.common.home.HMENU
import react.ChildrenBuilder
import react.dom.html.ReactHTML.div
import web.cssom.ClassName
import com.dynamicruntime.common.schema.PSTAT
import com.dynamicruntime.common.schema.SCH
import com.dynamicruntime.common.schema.SchFailCode
import com.dynamicruntime.common.schema.SchFailure
import com.dynamicruntime.common.schema.SchType
import com.dynamicruntime.common.schema.childPath
import com.dynamicruntime.common.schema.indexPath
import com.dynamicruntime.common.schema.validate
import com.dynamicruntime.common.util.toJsonListOfMaps
import com.dynamicruntime.common.util.toJsonMapOrEmpty
import com.dynamicruntime.common.util.toOptStr

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

/**
 * The client a stored form belongs to, read from its gedra id (issue #714): an `allClients` admin editing
 * another client's form must render it in *that* client's rules, not their own. Null when the id is absent or
 * unparseable, in which case a page falls back to the caller's own client-scoped copy -- the ordinary case,
 * where the two are the same. Parsed with the kernel's own [GedraId], so the frontend reads the client from an
 * id exactly as the backend does. Pure, and covered under `jsNodeTest`.
 */
fun formClientOf(gedraId: String?): String? =
    gedraId?.ifBlank { null }?.let { runCatching { GedraId.parse(it).client }.getOrNull() }

/**
 * The one way a form page fetches one of its endpoints (issue #714): the **bare** [barePath] resolved on the
 * surface of [formClient] -- the form's own client, from [formClientOf] -- or, when that is null, the caller's
 * own. The backend does the resolving *and* the fallback: a client that varies nothing has no `/gedra/<client>/…`
 * copy, and asking for one by exact path found nothing (the #714 review's regression), whereas resolving on
 * that client's surface answers with the shared endpoint, in that client's `$defs`. Shared by the raw editor
 * and the survey page so the rule lives once.
 */
suspend fun fetchFormEndpoint(method: String, barePath: String, formClient: String?): Catalog =
    SchemaCatalogApi.fetchEndpoint(method, barePath, resolveClient = true, client = formClient)

/**
 * Which client a **resolved** endpoint path is the copy for (issue #714): [formClient] when [resolvedPath] is
 * its client copy of [barePath], else null -- the shared endpoint answered, so the caller's own client is bound
 * and a sibling path (the workflow view, its save) must stay bare too. Pure, and covered under `jsNodeTest`.
 */
fun clientOfResolvedPath(resolvedPath: String?, barePath: String, formClient: String?): String? =
    formClient?.takeIf { resolvedPath == clientPath(barePath, it) }

/**
 * The applied forms search after a cross-client caller chooses [chosen] (issue #714), or clears it (null): the
 * `client` selector set or dropped, and the `user` scope dropped either way -- a user belongs to one client, so
 * one picked under the old client (or the cross-client view) would not resolve under the new one and would
 * 400 the listing. Every other filter is kept; `loadForClient` then whitelists it to what the target client's
 * listing declares. Pure, and covered under `jsNodeTest`.
 */
fun formsSearchForClient(applied: Map<String, String>, chosen: String?): Map<String, String> =
    (if (chosen.isNullOrBlank()) applied - EI.client else applied + (EI.client to chosen)) - EI.user

/**
 * The search a mounting forms page applies from its hash (issues #592, #714): [formsSearchFromHash], minus the
 * `client` selector for a caller who may **not** see across clients -- a shared chosen-client link opened by an
 * ordinary user would otherwise carry a filter they have no control to clear (and, on an empty account, read
 * "no forms match" instead of the empty state). Pure, and covered under `jsNodeTest`.
 */
fun formsInitialSearch(hp: Map<String, String>, seeAllClients: Boolean): Map<String, String> =
    formsSearchFromHash(hp).let { if (seeAllClients) it else it - EI.client }

/** The client selector's empty choice on the forms listing (issue #668): every client's rows, the caller's own columns. */
const val formsAllClientsLabel = "All clients"

/**
 * The note under the forms listing's client selector once a client is chosen (issue #714 review), naming the
 * chosen client by its plain [name] (the selector already shows the id): the selector alone reads as a row
 * filter, but the columns and the filters are now that client's -- and a new form would still be made in the
 * caller's own client, which is why "New form" is not offered here. Pure, and covered under `jsNodeTest`.
 */
fun chosenClientNote(name: String): String =
    "Showing $name's forms, with $name's columns and filters. New forms still go to your own client — " +
        "choose $formsAllClientsLabel to create one."

/** One fetched page of the forms listing: its rows and the total available, published together (#714 review). */
class FormsListPage(val rows: List<Map<String, Any?>>, val numAvailable: Int)

/**
 * The forms hash's navigation keys -- the page, the open form, the listing a child was opened from, the
 * arrival highlight, and the survey child page's edit-mode flag (issue #694) and open task (issue #700). Every
 * other key on a forms hash is a search parameter (a trait filter, the scope-bar `user`, the free-text `q`),
 * because the forms search shares the endpoint's own arg names, the same arrangement the Users page uses.
 */
private val formsNavKeys = setOf(HP.page, HP.gedra, HP.from, HP.highlight, HP.edit, HP.task)

/**
 * The applied forms search read back out of a hash (issue #592): every param that is not a navigation key. So a
 * shared or bookmarked forms URL reproduces the filter, and returning from an edit -- which carries the search
 * on its own URL -- lands on the same filtered list. Pure, and covered under `jsNodeTest`.
 */
fun formsSearchFromHash(hp: Map<String, String>): Map<String, String> =
    hp.filterKeys { it !in formsNavKeys }.filterValues { it.isNotBlank() }

/**
 * The note to show when the form a create or edit just saved is **not on the returned listing** (issue #669),
 * or null when it is on screen (so the row-flash is feedback enough) or nothing was saved. A saved row lands on
 * page one under the default newest-first sort, so an active filter (`appliedSearch` non-empty) is the realistic
 * reason it is absent; with no filter, absence is left unremarked. Neutral about create vs edit, since either
 * reaches the same gap. [savedId] is the row's id, [rowIds] the ids on the loaded page. Pure, covered under
 * `jsNodeTest`.
 */
fun savedNotShownNote(savedId: String?, appliedSearch: Map<String, Any?>, rowIds: List<String?>): String? =
    if (savedId != null && appliedSearch.isNotEmpty() && savedId !in rowIds) {
        "The form you just saved isn't shown here — it doesn't match the current filter. Clear the filter to see it."
    } else {
        null
    }

/**
 * The applied forms [search] as hash params (issue #592): its non-blank entries, to merge beside the page and
 * the open form. The inverse of [formsSearchFromHash]. Pure, and covered under `jsNodeTest`.
 */
fun formsSearchHashParams(search: Map<String, String>): List<Pair<String, String>> =
    search.entries.mapNotNull { (k, v) -> v.trim().ifEmpty { null }?.let { k to it } }

/**
 * The `← My forms` link atop a forms child page (issues #554, #671): the shared row + [backToListing], carrying
 * the listing's search and sort back so a cancel/back returns to the same filtered, sorted list the child was
 * opened from -- the sort rides through [formsSearchFromHash] as non-nav hash params (issues #592, #666, #669).
 * Both the create and edit pages render this, so their way back cannot drift.
 */
fun ChildrenBuilder.formsBackToListing() {
    val forward = formsSearchHashParams(formsSearchFromHash(hashParams()))
    div {
        className = ClassName("row")
        backToListing(HMENU.pageForms, forward)
    }
}

/** The declared query keys that are not applied-search values: paging, the owner-block flag, the sort
 *  column and direction (#666), and the always-on `withStates` (#694) -- none rides the applied search. */
private val formsNonSearchKeys = setOf(EP.offset, EP.limit, EI.includeUsers, GSORT.sort, GSORT.sortDir, GDF.withStates)

/**
 * The applied-search keys a listing's [inputSchema] actually declares (issue #592 review): its own property
 * names, minus paging and the owner flag. `user` and `q` are declared and kept, as is every trait filter this
 * client's variant carries.
 *
 * Reading a hash back through this **whitelist** rather than "everything that is not a navigation key" is what
 * keeps a stale or hand-added param from reaching the endpoint, where an undeclared property is a 400 -- so a
 * bookmarked URL from before a client's usage rules changed lands on the list rather than an error page, and a
 * stray `offset`/`limit` in the URL cannot pin the paging. Pure, and covered under `jsNodeTest`.
 */
fun formsSearchKeys(inputSchema: Map<String, Any?>): Set<String> =
    inputSchema[SCH.properties].toJsonMapOrEmpty().keys - formsNonSearchKeys

private val formCreateSuffix: String = pathAfterSection(GEP.formDocCreate)
private val formsListSuffix: String = pathAfterSection(GEP.formDocs)
private val formGetSuffix: String = pathAfterSection(GEP.formDoc)
private val formValuesSuffix: String = pathAfterSection(GEP.formDocValues)
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
 * The endpoint that suggests a text trait's distinct values (`GET /gedra/<client>/formDoc/values`, issue #581),
 * matched the same client-scoped way. Backs the filter boxes' type-ahead; null when the caller's surface has no
 * such endpoint (an older node), and then the boxes stay plain text. Pure, and covered under `jsNodeTest`.
 */
fun findFormValuesEndpoint(endpoints: List<EndpointInfo>): EndpointInfo? =
    endpoints.firstOrNull { it.method == HttpMethod.GET.name && it.path.endsWith(formValuesSuffix) }

/**
 * The label a user-picker suggestion shows (issue #581): a display name and the email, or the email alone when
 * neither adds to it. The display name is the real name if there is one, else the **public name** -- the
 * username -- so a user found by their username (the search hits it) is shown by it rather than by an email
 * that carried no part of what was typed. A username starting with `@` is the placeholder `@<email>`, whose
 * public name is the email itself (the backend's `publicName()` rule); a real username never contains `@`. The
 * value a pick sends is always the [email], which the listing's `user` parameter resolves. Pure, covered under
 * `jsNodeTest`.
 */
fun userPickLabel(name: String?, username: String, email: String): String {
    val realName = name?.trim()?.ifEmpty { null }
    val publicName = username.takeIf { it.isNotEmpty() && !it.startsWith("@") }
    val display = realName ?: publicName
    return if (display == null || display == email) email else "$display — $email"
}

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
 * Completeness failures the edit form should show inline before submit (issue #662), one patch target's worth.
 *
 * An edit's `data` is a `g-optionalContents` fragment, so the form's ordinary `checkInput` does not demand a
 * complete object -- which is right for a merge, but an **addOrReplace** takes the supplied data *whole*
 * (`GedraDataService.applyEdit`), so it must be complete. The server settles that (`checkStoredEntries`) and,
 * for the switch case where the old trait's data was dropped, rejects it with a form-level "carries no data".
 * This surfaces the same rule inline instead: each `addOrReplace` edit's data is validated against its trait's
 * data **type** -- not the optionalContents property, so `required` is enforced -- and the missing-required failures re-pathed
 * to the edit's place in the target (`${GPF.edits}[i].${GE.data}.<field>`), the same path space `checkInput` and
 * the form walk, so they mark the field.
 *
 * A **merge** is partial by design and a **delete** needs only its key, so both are left to the server as
 * before. [targetType] is the one-target patch shape ([formDocPatchTargetType]); [values] is that target as the
 * form holds it. Pure, and covered under `jsNodeTest`.
 */
fun editDataCompletenessFailures(targetType: SchType?, values: Map<String, Any?>): List<SchFailure> {
    val editsUnion = targetType?.properties?.get(GPF.edits)?.valueType?.itemType ?: return emptyList()
    val out = mutableListOf<SchFailure>()
    values[GPF.edits].toJsonListOfMaps().forEachIndexed { i, edit ->
        if (edit[GED.action].toOptStr() != GedraEditAction.addOrReplace.name) return@forEachIndexed
        val traitId = edit[GE.traitId].toOptStr() ?: return@forEachIndexed
        val dataType = editsUnion.variants?.select(traitId)?.properties?.get(GE.data)?.valueType
            ?: return@forEachIndexed
        val prefix = childPath(indexPath(GPF.edits, i), GE.data)
        val supplied = edit[GE.data]
        if (supplied == null) {
            // Absent data: an addOrReplace needs some, but an absent nested object renders collapsed behind an
            // "Add" control with no child fields drawn, so a per-field failure would mark nothing. One failure on
            // the data field itself, which that "Add" row does show.
            out += SchFailure(prefix, SchFailCode.missingRequired, "Add this entry's data, or switch the action to delete.")
        } else {
            // Present (possibly seeded empty): its fields are on screen, so mark each missing required one. Only
            // the completeness failures checkInput skipped -- `optionalContents` waives `required` alone, so a
            // wrong type or a bad option is already reported there and adding it here would double it.
            validate(dataType, supplied.toJsonMapOrEmpty())
                .filter { it.code == SchFailCode.missingRequired }
                .forEach { f -> out += f.copy(path = if (f.path.isEmpty()) prefix else childPath(prefix, f.path)) }
        }
    }
    return out
}

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

/**
 * A form's global survey status for the forms-list column (issue #694), derived from its `surveyCompletion`
 * state. Each carries the label the chip shows and the [PSTAT] colour class it renders in. **Invalid trumps
 * incomplete**: data that fails schema is [invalid] even if a required trait is also missing.
 */
enum class SurveyStatus(val label: String, val pstat: String) {
    valid("Valid", PSTAT.ok),
    needsInfo("Needs Info", PSTAT.warning),
    invalid("Invalid", PSTAT.error),
}

/**
 * The [SurveyStatus] from a row's state entries (issue #694), or null when the form has no survey state — a
 * client with no survey, or a row not yet computed — in which case the column shows nothing for it. Reads the
 * `surveyCompletion` entry's `complete`/`valid` booleans; `!valid` → Invalid, else `!complete` → Needs Info,
 * else Valid. Pure, covered under `jsNodeTest`.
 */
fun surveyStatusFrom(states: List<Map<String, Any?>>): SurveyStatus? {
    val entry = states.firstOrNull { it[GE.traitId] == SVY.surveyCompletion } ?: return null
    val data = entry[GE.data].toJsonMapOrEmpty()
    val valid = data[SVY.valid] as? Boolean ?: return null
    val complete = data[SVY.complete] as? Boolean ?: return null
    return when {
        !valid -> SurveyStatus.invalid
        !complete -> SurveyStatus.needsInfo
        else -> SurveyStatus.valid
    }
}

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
    /** The owning client, from the row's `client` (issue #668): what the Client column shows for a caller who
     *  administers across clients. Every row carries it; empty only when the row somehow arrived without one. */
    val client: String = "",
    /** The form's global survey status (issue #694), or null when it has no survey state — see [surveyStatusFrom]. */
    val surveyStatus: SurveyStatus? = null,
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
        // The owning client (issue #668), attached to every listed row; the Client column shows it for a caller
        // who administers across clients.
        client = item[GDF.client] as? String ?: "",
        // The global survey status (issue #694): present only when the row carried state (`withStates`), null
        // for a client with no survey, in which case the list draws no status for the row.
        surveyStatus = surveyStatusFrom(item[GDF.states].toJsonListOfMaps()),
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
