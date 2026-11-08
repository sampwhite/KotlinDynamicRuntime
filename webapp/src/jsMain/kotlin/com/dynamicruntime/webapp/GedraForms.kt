package com.dynamicruntime.webapp

import com.dynamicruntime.common.schema.SchLayout
import com.dynamicruntime.common.gedra.branchDisplayName
import com.dynamicruntime.common.gedra.workflow.engagedWorkflowIds
import com.dynamicruntime.common.gedra.workflow.WAGG
import com.dynamicruntime.common.gedra.workflow.WCOL
import com.dynamicruntime.common.gedra.workflow.WfPhase
import com.dynamicruntime.common.gedra.workflow.WfColumnCategory
import com.dynamicruntime.common.gedra.workflow.FormWorkflow
import com.dynamicruntime.common.gedra.workflow.WFS
import com.dynamicruntime.common.gedra.workflow.WfWaitsOn
import com.dynamicruntime.common.home.HFRAG
import com.dynamicruntime.common.util.evalTemplate
import kotlin.time.Clock
import kotlin.time.Instant
import com.dynamicruntime.common.gedra.workflow.WfWeight
import com.dynamicruntime.common.gedra.workflow.formWorkflowWeights
import com.dynamicruntime.common.endpoint.EI
import com.dynamicruntime.common.endpoint.clientPath
import com.dynamicruntime.common.endpoint.EP
import com.dynamicruntime.common.endpoint.HttpMethod
import com.dynamicruntime.common.gedra.DUF
import com.dynamicruntime.common.gedra.GDF
import com.dynamicruntime.common.gedra.WorkflowLayout
import com.dynamicruntime.common.gedra.GE
import com.dynamicruntime.common.gedra.GED
import com.dynamicruntime.common.gedra.GEP
import com.dynamicruntime.common.gedra.GSORT
import com.dynamicruntime.common.gedra.UF
import com.dynamicruntime.common.gedra.GPF
import com.dynamicruntime.common.gedra.GedraDataType
import com.dynamicruntime.common.gedra.GedraEditAction
import com.dynamicruntime.common.gedra.GedraId
import com.dynamicruntime.common.gedra.ListingColumn
import com.dynamicruntime.common.gedra.UsageKind
import com.dynamicruntime.common.gedra.presentColumnValue
import com.dynamicruntime.common.util.ScriptEnv
import com.dynamicruntime.common.util.parseDateOrNull
import com.dynamicruntime.common.gedra.workflow.FSV
import com.dynamicruntime.common.gedra.workflow.CTAK
import com.dynamicruntime.common.gedra.workflow.CTKQ
import com.dynamicruntime.common.gedra.workflow.CTKF
import com.dynamicruntime.common.gedra.workflow.SWF
import com.dynamicruntime.common.gedra.workflow.WFD
import com.dynamicruntime.common.gedra.workflow.WSC
import com.dynamicruntime.common.gedra.workflow.formStatusOf
import com.dynamicruntime.common.home.HMENU
import com.dynamicruntime.common.util.Parsed
import react.ChildrenBuilder
import react.dom.html.ReactHTML.div
import react.dom.html.ReactHTML.h1
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
import com.dynamicruntime.common.util.toOptLong
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
    gedraId?.ifBlank { null }?.let { GedraId.parseOrNull(it)?.client }

/**
 * The one way a form page fetches one of its endpoints (issue #714): the **bare** [barePath] resolved on the
 * surface of [formClient] -- the form's own client, from [formClientOf] -- or, when that is null, the caller's
 * own. The backend does the resolving *and* the fallback: a client that varies nothing has no `/gedra/<client>/…`
 * copy, and asking for one by exact path found nothing (the #714 review's regression), whereas resolving on
 * that client's surface answers with the shared endpoint, in that client's `$defs`. Shared by the raw editor
 * and the core details page so the rule lives once.
 *
 * Theme: #CLCPY.
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
 * Whether the form's trait picker should allow **free-form** entry of a trait this node's global union does not
 * list (issue #667): only on the **cross-client admin** surface -- the caller can see across clients
 * ([canSeeAllClients]) **and** the endpoint resolved to the shared/global copy ([resolvedClient] null), whose
 * union carries only the global traits. A per-client copy ([resolvedClient] non-null) already offers that
 * client's full trait set, so it stays a closed choice -- the issue's "not available to the client-specific
 * endpoints" -- and an ordinary caller (never `allClients`) never gets it. Pure, and covered under `jsNodeTest`.
 */
fun freeformTraitEntry(canSeeAllClients: Boolean, resolvedClient: String?): Boolean =
    canSeeAllClients && resolvedClient == null

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
 * The search a mounting forms page applies from its address (issues #592, #714): [formsSearchFromRoute], minus the
 * `client` selector for a caller who may **not** see across clients -- a shared chosen-client link opened by an
 * ordinary user would otherwise carry a filter they have no control to clear (and, on an empty account, read
 * "no forms match" instead of the empty state). Pure, and covered under `jsNodeTest`.
 */
fun formsInitialSearch(hp: Map<String, String>, seeAllClients: Boolean): Map<String, String> =
    formsSearchFromRoute(hp).let { if (seeAllClients) it else it - EI.client }

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
class FormsListPage(
    val rows: List<Map<String, Any?>>,
    val numAvailable: Int,
    /**
     * The workflow column's summary (issue #791): what it may show, over every form the caller may see. Null when
     * the fetch did not ask for it -- a page turn, search or sort, none of which change it -- so the page keeps the
     * one it has.
     */
    val workflowSummary: List<WorkflowSummaryEntry>? = null,
    /**
     * The user the listing is confined to (issue #1095), when the request named one: said by every page, so it is
     * of these rows and no others. Null when the listing is everyone's the caller may see.
     */
    val scopeUser: ScopeUser? = null,
    /** The listing's columns (issue #1323), with the presentation each cell reads through; empty when it has none. */
    val columns: List<ListingColumn> = emptyList(),
    /** Each search keyword's words in the caller's client's wording (issue #1596); empty when the summary says none. */
    val ctaKeywords: List<CtaKeywordWords> = emptyList(),
    /** The same words as each other client with a row on the page says them, by client id (issue #1596). */
    val ctaKeywordsByClient: Map<String, List<CtaKeywordWords>> = emptyMap(),
    /** How the listing's client draws each row's workflows (issue #1599), its listing setting; `split` unless it says. */
    val workflowLayout: WorkflowLayout = WorkflowLayout.split,
    /**
     * How many of the listing's forms are the caller's turn (issue #1599), for the Your turn column's header -- with
     * [workflowSummary], and null when the fetch did not ask for it, so the page keeps the count it has.
     */
    val yourTurnCount: Int? = null,
)

/**
 * A search keyword's words for the caller (issue #1596), as the forms listing's summary says them: resolved on the
 * backend for the caller's client, so a client's rewording is what a page shows.
 */
class CtaKeywordWords(
    val keyword: String,
    val verb: String,
    val filterLabel: String,
    val columnHeader: String,
    val reportLabel: String,
    val statusText: String,
) {
    companion object {
        /** The summary's [GDF.ctaKeywords] list; one without a keyword is skipped. Pure, under `jsNodeTest`. */
        fun listFrom(raw: Any?): List<CtaKeywordWords> = raw.toJsonListOfMaps().mapNotNull { m ->
            CtaKeywordWords(
                m[CTKF.keyword].toOptStr() ?: return@mapNotNull null,
                m[CTKF.verb].toOptStr().orEmpty(),
                m[CTKF.filterLabel].toOptStr().orEmpty(),
                m[CTKF.columnHeader].toOptStr().orEmpty(),
                m[CTKF.reportLabel].toOptStr().orEmpty(),
                m[CTKF.statusText].toOptStr().orEmpty(),
            )
        }

        /** The summary's [GDF.ctaKeywordsByClient]: each client's list, as [listFrom] reads one. Pure, under `jsNodeTest`. */
        fun byClientFrom(raw: Any?): Map<String, List<CtaKeywordWords>> =
            raw.toJsonMapOrEmpty().mapValues { (_, words) -> listFrom(words) }
    }
}

/**
 * The words [status] reads as (issue #1596): its own label, except a status a search keyword sets -- Needs Review is the
 * `review` keyword's -- which reads as that keyword's status text in [keywords], when they say one. Pure, under
 * `jsNodeTest`.
 */
fun formStatusLabel(status: FormStatus, keywords: List<CtaKeywordWords>): String {
    val cfact = status.singletonCfact ?: return status.label
    return keywords.firstOrNull { CTAK.of(it.keyword)?.statusCfact == cfact }?.statusText?.takeIf { it.isNotBlank() }
        ?: status.label
}

/**
 * The forms address's navigation keys -- the page, the open form, the listing a child was opened from, the arrival
 * highlight, and the core details workflow child page's edit-mode flag (issue #694) and open task (issue #700). Every
 * other key on a forms address is a search parameter (a trait filter, the scope-bar `user`, the free-text `q`), because
 * the forms search shares the endpoint's own arg names, the same arrangement the Users page uses.
 */
private val formsNavKeys = setOf(HP.page, HP.gedra, HP.from, HP.highlight, HP.created, HP.edit, HP.task, HP.workflow)

/**
 * The applied forms search read back out of an address (issue #592): every param that is not a navigation key. So a
 * shared or bookmarked forms URL reproduces the filter, and returning from an edit -- which carries the search
 * on its own URL -- lands on the same filtered list. Pure, and covered under `jsNodeTest`.
 */
fun formsSearchFromRoute(hp: Map<String, String>): Map<String, String> =
    hp.filterKeys { it !in formsNavKeys }.filterValues { it.isNotBlank() }

/**
 * The note to show when the form a create or edit just saved is **not on the returned listing** (issues #669,
 * #758), or null when it is on screen (so the row-flash is feedback enough) or nothing was saved. [savedId] is
 * the row's id, [rowIds] the ids on the loaded page, [created] whether the save made the form, and
 * [customSort] whether the listing is in anything but its default newest-first order.
 *
 * The note says **why**, because the remedy differs. A row saved under the default order lands on page one, so
 * with a filter active the filter is the reason; under a custom sort the sort may be instead, and the note
 * says either could be rather than sending the user to clear a filter that is not at fault. With **no** filter
 * the two kinds of save part: an edited row's absence is left unremarked (it was on the list the user came
 * from), but a **created** one is still announced, because the flash was going to be the only sign the create
 * happened -- and a create with no confirmation at all reads as a create that failed. For the same reason a
 * create whose response carried no id is announced plainly.
 *
 * A workflow's listing (issue #792) is not filtered by what makes it that listing ([formsDrillKeys]), which Clear
 * keeps: there the note says which forms the list shows, since a save -- engaging a form from the eligible ones, say
 * -- moves a form out of a state's list. Pure, covered under `jsNodeTest`.
 */
fun savedNotShownNote(
    savedId: String?,
    appliedSearch: Map<String, Any?>,
    rowIds: List<String?>,
    created: Boolean = false,
    customSort: Boolean = false,
): String? {
    if (savedId == null) return if (created) "The form was created." else null
    if (savedId in rowIds) return null
    val what = if (created) "The form was created, but" else "The form you just saved"
    val filtered = (appliedSearch - formsDrillKeys).isNotEmpty()
    val drillState = workflowDrillOf(appliedSearch)?.second
    return when {
        drillState != null && filtered ->
            "$what isn't shown here — this list shows only the forms ${workflowDrillForms(drillState)}, and the " +
                "current filter may hide it too."
        drillState != null -> "$what isn't shown here — this list shows only the forms ${workflowDrillForms(drillState)}."
        filtered && customSort ->
            "$what isn't shown here — it doesn't match the current filter, or the current sort puts it on another page."
        filtered -> "$what isn't shown here — it doesn't match the current filter. Clear the filter to see it."
        !created -> null
        customSort ->
            "$what isn't shown here — the current sort puts it on another page. Sort by Updated, newest first, to see it."
        else -> "$what it isn't on this page of the list."
    }
}

/** The value a present-or-absent forms address flag carries ([HP.created]): written and read in this file only. */
private const val formsRouteFlagOn = "1"

/**
 * Whether a forms address [hp] asks for anything but the listing's default order (newest-updated first), which
 * is what decides whether a just-saved row can be off page one for a reason other than a filter. Pure.
 */
fun formsCustomSort(hp: Map<String, String>): Boolean {
    val column = hp[GSORT.sort]?.ifBlank { null } ?: return false
    return !(column == GSORT.updated && hp[GSORT.sortDir].equals(GSORT.desc, ignoreCase = true))
}

/**
 * The note a forms listing shows on **arrival** (#758 review): what the arriving address [hp] says was saved --
 * the highlighted id, whether it was a create, the sort it arrived under -- read against the page that loaded.
 * The reading half of [formsListingReturn], kept beside it so the flag's spelling lives in one file and one
 * test can run a return straight into an arrival. Pure, and covered under `jsNodeTest`.
 */
fun formsArrivalNote(hp: Map<String, String>, appliedSearch: Map<String, Any?>, rowIds: List<String?>): String? =
    savedNotShownNote(
        hp[HP.highlight]?.ifBlank { null },
        appliedSearch,
        rowIds,
        created = hp[HP.created] == formsRouteFlagOn,
        customSort = formsCustomSort(hp),
    )

/**
 * The root fields of the client's create input that a create page does **not** draw (issues #727, #762):
 * `allowAdditionalTraits` is a power flag, and `user` is the page's own answer -- the ordinary page's picker,
 * or the user a create-for-user page was opened for -- appended to the body by [formForUserBody], never typed
 * into a box. One list for both create pages: #762 was the two lists drifting, one page drawing the `user`
 * field and asking again for the user it was opened for. Covered under `jsNodeTest`.
 */
val formCreateOmittedFields: List<String> = listOf(GDF.allowAdditionalTraits, EI.user)

/**
 * The body of an on-behalf create (issues #727, #762): the form's [payload] with the picked user [userRef]
 * beside it. The right-hand pair wins a map union, so a `user` the payload might carry never overrides the
 * user the page chose -- `mapOf(user) + payload` had it the other way round. A blank [userRef] is sent as is;
 * the endpoint refuses it, and a page only gets here with a picked user. Pure, and covered under `jsNodeTest`.
 */
fun formForUserBody(userRef: String, payload: Map<String, Any?>): Map<String, Any?> = payload + (EI.user to userRef)

/**
 * The applied forms [search] as route params (issue #592): its non-blank entries, to merge beside the page and
 * the open form. The inverse of [formsSearchFromRoute]. Pure, and covered under `jsNodeTest`.
 */
fun formsSearchRouteParams(search: Map<String, String>): List<Pair<String, String>> =
    search.entries.mapNotNull { (k, v) -> v.trim().ifEmpty { null }?.let { k to it } }

/**
 * The one way a form page returns to the listing after its work is saved (issues #726, #758): the forms page,
 * the listing context in [hp] carried back -- its search, sort and chosen client, as the back link carries
 * them -- and the form just worked on flagged as [highlightId] so the list flashes it. [created] marks a form
 * that was just **made** ([HP.created]), so one the listing cannot show is still announced; it rides even when
 * [highlightId] is null (a create whose response carried no id), which the arrival then announces plainly.
 *
 * [hp] is normally the page's own address, whose navigation keys are left behind here; a caller that returns to a
 * *different* listing than it was launched from (create-for-user, into the user's client) passes that context
 * instead. Shared by the core details workflow and raw editors' Done and by every create surface, so the ways home cannot
 * drift: #758 was exactly that, a return the picker had (#663) and the workflow create did not. The bare back
 * link is the same target without the flash. The listing is the one the page was opened from ([formsListingOf]),
 * so a form worked on from a workflow's listing goes home to that listing. Pure, and covered under `jsNodeTest`.
 */
fun formsListingReturn(hp: Map<String, String>, highlightId: String?, created: Boolean = false): List<Pair<String, String>> =
    buildList {
        add(HP.page to formsListingOf(hp))
        addAll(formsSearchRouteParams(formsSearchFromRoute(hp)))
        highlightId?.let { add(HP.highlight to it) }
        if (created) add(HP.created to formsRouteFlagOn)
    }

/**
 * Where a **create** goes once it has succeeded (issue #758), or null when the user has already left the page
 * it was launched from -- a slow response must not pull someone out of wherever they went next, nor carry that
 * page's address keys into the listing as a "search" (#758 review). [launched] is the address when the create was
 * clicked, [now] the address when it finished, [newId] the new form's id, and [context] the listing context to
 * return with: the launching page's own by default. Pure, and covered under `jsNodeTest`.
 */
fun formsCreateReturn(
    launched: Map<String, String>,
    now: Map<String, String>,
    newId: String?,
    context: Map<String, String> = launched,
): List<Pair<String, String>>? =
    if (now[HP.page] != launched[HP.page]) null else formsListingReturn(context, newId, created = true)

/**
 * The raw **read-only** view of one form (issue #726): the listing page with the form open in place -- the same view a
 * row click opens where the client has no core details workflow -- reached from the core details workflow's "View raw"
 * beside "Raw edit", so every trait can be looked at without entering the editor. Carries the listing's search and sort
 * out of the core details workflow's own address [hp], as its other ways home do, and none of the core details
 * workflow's own keys. The listing is the one the core details workflow was opened from ([formsListingOf]), so a form
 * reached through a workflow's listing is viewed there. Pure, and covered under `jsNodeTest`.
 */
fun formsRawViewRoute(hp: Map<String, String>, gedraId: String): List<Pair<String, String>> =
    listOf(HP.page to formsListingOf(hp), HP.gedra to gedraId) + formsSearchRouteParams(formsSearchFromRoute(hp))

/**
 * The core details workflow's read-only "View Info" of one form (issue #726): reached from the raw editor's "View info" beside
 * Done, for a form whose client has a core details workflow. Carries the listing's search and sort out of the editor's address
 * [hp], and `from=forms` so the core details page's own back link still leads home; none of the editor's keys. Pure,
 * and covered under `jsNodeTest`.
 */
fun formsCoreDetailsViewRoute(hp: Map<String, String>, gedraId: String): List<Pair<String, String>> =
    listOf(HP.page to pageCoreDetailsEdit, HP.from to formsListingOf(hp), HP.gedra to gedraId) +
        formsSearchRouteParams(formsSearchFromRoute(hp))

/** The listings a form belongs to: My forms, and a workflow's listing of forms (issue #792). */
private val formsListings = setOf(HMENU.pageForms, pageWorkflowForms)

/**
 * The forms listing a form page belongs to, from its address [hp]: the one `from` names when it is a forms listing
 * ([formsListings]) -- a workflow's listing as well as My forms -- else My forms, the natural parent. Only a forms
 * listing counts, unlike a back link's `from`: a way *home* for a form is a listing that can show it flashed, and
 * another page the back rules know (Users, say) cannot. What every way home reads, so a form opened from a
 * workflow's listing returns there. Pure, and covered under `jsNodeTest`.
 */
fun formsListingOf(hp: Map<String, String>): String = hp[HP.from]?.takeIf { it in formsListings } ?: HMENU.pageForms

/**
 * The `← My forms` link atop a forms child page (issues #554, #671): the shared row + [backToListing], carrying
 * the listing's search and sort back so a cancel/back returns to the same filtered, sorted list the child was
 * opened from -- the sort rides through [formsSearchFromRoute] as non-nav route params (issues #592, #666, #669).
 * Both the create and edit pages render this, so their way back cannot drift.
 */
fun ChildrenBuilder.formsBackToListing() {
    div {
        className = ClassName("row")
        formsBackLink()
    }
}

/**
 * The bare back link of [formsBackToListing], for a header that lays it out itself (the workflow form's one
 * header line, issue #719): the same target and the same forwarded search, without the `.row` around it. The
 * target is the listing the page's `from` names, else [listing] -- My forms, unless a listing page draws the link
 * on itself (its in-place form view) and says which listing it is.
 */
fun ChildrenBuilder.formsBackLink(listing: String = HMENU.pageForms) {
    backToListing(listing, formsSearchRouteParams(formsSearchFromRoute(routeParams())))
}

/**
 * The one header line both form editors share (issue #726, after the core details workflow's #719 line): the back link, the
 * [title] beside it, and the [actions] right-aligned -- Edit / Done, Raw edit, the saved note, whichever apply.
 * One composable rather than two lookalike blocks, so the core details editor and the raw editor cannot drift apart
 * in where "back" lives or how the actions sit. [title] is a builder so a core details workflow can render its Markdown label
 * where the raw editor renders plain text.
 */
fun ChildrenBuilder.formsEditorHeader(
    title: ChildrenBuilder.() -> Unit,
    listing: String = HMENU.pageForms,
    actions: ChildrenBuilder.() -> Unit,
) {
    div {
        className = ClassName("wf-header")
        formsBackLink(listing)
        h1 { title() }
        div {
            className = ClassName("wf-actions")
            actions()
        }
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
 * Reading an address back through this **whitelist** rather than "everything that is not a navigation key" is what
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
 * What a failed load of the forms listing says first. "The forms", not "your forms" (issue #1091): the listing is an
 * administrator's client's as often as it is the caller's own, and this is true of both.
 */
const val formsLoadFailureLead = "Couldn't load the forms."

/**
 * The user a forms listing is confined to, as the listing's own `summary` says them ([GDF.scopeUser], issue #1095):
 * who the server resolved the request's `user` to. An id names one user; an address names a person, whom the server
 * resolves to their default user -- so this is the only thing that says which of a person's users is on screen.
 */
class ScopeUser(
    val userId: Long,
    /** The account's display name, when it has one that is not its email. */
    val name: String?,
    val email: String,
    val client: String,
    val persona: String,
    val personaSuffix: String,
)

/**
 * The listing envelope's `summary` as the user it is confined to, or null when it names none -- the request named
 * no user -- or the block has no id to say which. Pure, covered under `jsNodeTest`.
 */
fun parseScopeUser(summary: Any?): ScopeUser? {
    val block = summary.toJsonMapOrEmpty()[GDF.scopeUser].toJsonMapOrEmpty()
    return ScopeUser(
        userId = block[DUF.userId].toOptLong() ?: return null,
        name = block[DUF.name].toOptStr()?.ifBlank { null },
        email = block[DUF.email].toOptStr().orEmpty(),
        client = block[DUF.client].toOptStr().orEmpty(),
        persona = block[DUF.persona].toOptStr().orEmpty(),
        personaSuffix = block[DUF.personaSuffix].toOptStr().orEmpty(),
    )
}

/**
 * What the scope bar says of the user the listing is confined to (issue #1095), under its controls: which user, by
 * id; who, by name and address; and what kind of user of that person's -- "User 2: Mem One — mem1@acme.test [Member
 * B]". The id leads because it is the one thing an address typed into the box did not say. The bracket is the
 * persona and its suffix, and before it the client for a caller who sees [acrossClients], since only they could be
 * looking at another client's user. Always given, though most people have one user: it is what the listing was told,
 * and saying it costs a word where finding out whether there are others would cost a lookup. Pure, covered under
 * `jsNodeTest`.
 */
fun scopeUserLabel(user: ScopeUser, acrossClients: Boolean): String {
    // The name is sent only when it is not the email, so its presence is the whole test.
    val who = if (user.name == null) user.email else "${user.name} — ${user.email}"
    // A part the block did not say is left out rather than drawn empty -- and the bracket with it, if that was all.
    val kind = listOfNotNull(
        user.client.takeIf { acrossClients && it.isNotEmpty() },
        personaCell(user.persona, user.personaSuffix).takeIf { user.persona.isNotEmpty() },
    )
    return "User ${user.userId}: $who" + if (kind.isEmpty()) "" else " [${kind.joinToString(" · ")}]"
}

/**
 * The user a listing said it is confined to, **with the scope it was asked about** (issue #1095): [askedFor] is the
 * `user` the request carried, null for a request that named none. Kept together because the page records a newly
 * applied scope before the listing answers, and a listing that refuses it never answers at all -- so the last
 * answer on hand may be about a scope that is no longer the one applied.
 */
class ScopeAnswer(val askedFor: String?, val user: ScopeUser?)

/**
 * The user to name under the scope bar: [answer]'s, when it is about the scope now [applied] -- else nobody. So a
 * scope the listing refused, or has not yet answered, is never named with the previous scope's user. Pure, covered
 * under `jsNodeTest`.
 */
fun scopeUserFor(applied: String?, answer: ScopeAnswer?): ScopeUser? =
    answer?.user?.takeIf { applied != null && applied.trim() == answer.askedFor?.trim() }

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
fun formDocPatchBody(target: Map<String, Any?>, allowAdditionalTraits: Boolean = false, overrideReason: String? = null): Map<String, Any?> =
    buildMap {
        put(GPF.targets, mapOf(GedraDataType.formDoc.name to listOf(target)))
        // The escape hatch for a free-form trait the client's union does not know (issue #667): sent only when
        // one was entered, so an ordinary edit stays bound to the client's declared traits.
        if (allowAdditionalTraits) put(GDF.allowAdditionalTraits, true)
        // An explicit override of the trait locks the edits touch (issue #857), with the writer's reason.
        overrideReason?.let { put(GPF.overrideReason, it) }
    }

/**
 * The note a raw-editor section carries when its trait is locked for the caller (issue #857) -- "Locked by Audit
 * review", naming every workflow locking it -- or null for a section of an unlocked trait, or one naming no trait
 * yet. Pure, and covered under `jsNodeTest`.
 */
fun lockNoteFor(locks: List<TraitLock>, traitId: String?): String? {
    if (traitId == null) return null
    val on = locks.filter { it.traitId == traitId }
    return if (on.isEmpty()) null else "Locked by ${on.joinToString(" and ") { it.label }}"
}

/** What [splitLockedEdits] makes of a patch target: the [target] to send, and the locked traits the user changed. */
class LockedEditSplit(val target: Map<String, Any?>, val changedLocked: List<String>)

/**
 * The raw editor's patch target with its **locked** sections sorted out (issue #857): [target] (the one-target
 * `{gedraId, edits}` shape) without the edits of a trait in [locked] that are just as [seeded] -- the editor sends
 * every section it loaded, and an untouched one must not trip a lock -- and the locked traits whose edits the user
 * did change, which only an override may send. Pure, and covered under `jsNodeTest`.
 */
fun splitLockedEdits(target: Map<String, Any?>, seeded: Map<String, Any?>, locked: Set<String>): LockedEditSplit {
    if (locked.isEmpty()) return LockedEditSplit(target, emptyList())
    val seededEdits = seeded[GPF.edits].toJsonListOfMaps()
    val changed = LinkedHashSet<String>()
    val kept = target[GPF.edits].toJsonListOfMaps().filter { edit ->
        val traitId = edit[GE.traitId].toOptStr() ?: return@filter true
        if (traitId !in locked) return@filter true
        if (edit in seededEdits) false else { changed.add(traitId); true }
    }
    return LockedEditSplit(target + (GPF.edits to kept), changed.toList())
}

/**
 * Whether an edited patch target ([values], the one-target `{gedraId, edits}` shape) names a trait the edit
 * union does not list (issue #667) -- a free-form trait entered on the cross-client admin surface. When it does,
 * the patch must carry `allowAdditionalTraits` or the backend refuses the unknown trait. [targetType] is the
 * one-target patch shape ([formDocPatchTargetType]); an edit whose trait the union already knows needs nothing.
 * Pure, and covered under `jsNodeTest`.
 */
fun patchNamesUnknownTrait(targetType: SchType?, values: Map<String, Any?>): Boolean {
    val editsUnion = targetType?.properties?.get(GPF.edits)?.valueType?.itemType?.variants ?: return false
    return values[GPF.edits].toJsonListOfMaps().any { edit ->
        val traitId = edit[GE.traitId].toOptStr()
        traitId != null && !editsUnion.isKnown(traitId)
    }
}

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
 * One computed display value from a client's trait-usage rule (issue #537): a column ([columnId], its name, issue
 * #1490) and its cell's value, as the backend evaluated it -- what the column sorts and searches by -- and the [data]
 * of the row's entry of the trait it reads ([traitId]), which the column's presentation reads (issue #1323). [data] is
 * empty when the row has no such entry.
 */
class DisplayValue(
    val columnId: String,
    val traitId: String,
    val label: String,
    val value: String,
    val kind: UsageKind = UsageKind.string,
    val data: Map<String, Any?> = emptyMap(),
)

/**
 * What [display]'s cell shows (issue #1323): its value read through [column]'s presentation, in [env]'s zone and
 * against its clock -- the value as it is when there is no column for it.
 */
fun presentedValue(display: DisplayValue, column: ListingColumn?, env: ScriptEnv): String =
    if (column == null) {
        display.value
    } else {
        presentColumnValue(display.value, column.kind, column.presentation, display.data, env, column.choices)
    }

/**
 * A form's heading (issue #537): the first of its display values that is not blank, read through its column's
 * presentation (issue #1323), so the heading and the table cell say the same. Null when every one is blank -- a
 * client that declares no usage rule has none, and the document is shown unnamed.
 */
fun formHeading(summary: FormSummary, columns: List<ListingColumn>, env: ScriptEnv): String? {
    val first = summary.displayValues.firstOrNull { it.value.isNotBlank() } ?: return null
    return presentedValue(first, columns.firstOrNull { it.columnId == first.columnId }, env)
}

/**
 * The tooltip of a listing cell that shows [shown] for [display] (issue #1323): the exact moment for a date the
 * presentation reworded, the value for anything else it reworded, and none for a cell showing its value as it is.
 */
fun presentedCellTip(display: DisplayValue, shown: String): String? = when {
    display.value.isBlank() || shown == display.value -> null
    display.kind == UsageKind.date && display.value.parseDateOrNull() != null -> formatTimestamp(display.value)
    else -> display.value
}

/**
 * A form's status for the forms-list column (issues #694, #789) and its filter (issue #695): the kernel's [FSV]
 * value on the [wire], the label the chip and the filter's choice show, and the [PSTAT] colour class the chip
 * renders in. The derivation itself is the kernel's `formStatusOf`, shared with the backend filter, so the column
 * and the filter cannot disagree -- **invalid trumps incomplete**, and a workflow's Needs Review or Finished takes
 * the place of Valid only.
 */
enum class FormStatus(val wire: String, val label: String, val pstat: String) {
    valid(FSV.valid, "Valid", PSTAT.ok),
    needsInfo(FSV.needsInfo, "Needs Info", PSTAT.warning),
    invalid(FSV.invalid, "Invalid", PSTAT.error),
    needsReview(FSV.needsReview, "Needs Review", PSTAT.info),
    finished(FSV.finished, "Finished", PSTAT.ok),
    ;

    /**
     * The framework singleton cfact behind this chip (issue #789) -- what its click asks the backend about -- or
     * null for a core details status, whose chip links into the core details workflow instead.
     */
    val singletonCfact: String?
        get() = when (this) {
            needsReview -> WSC.needsReview
            finished -> WSC.finished
            else -> null
        }

    companion object {
        /** The status a [FSV] wire value names, or null for anything else. */
        fun fromWire(value: String?): FormStatus? = entries.firstOrNull { it.wire == value }
    }
}

/**
 * The [FormStatus] from a row's state entries (issues #694, #789), or null when the form has no core details state —
 * a client with no core details workflow, or a row not yet computed — in which case the column shows nothing for it. The
 * kernel's `formStatusOf`, mapped onto the enum; covered under `jsNodeTest`.
 */
fun formStatusFrom(states: List<Map<String, Any?>>): FormStatus? = FormStatus.fromWire(formStatusOf(states))

/**
 * One workflow behind a Needs Review / Finished chip (issue #789), as `formDocSingletonWorkflows` answers: its
 * name, and -- when it has a current task -- what that task asks of the caller, and whether they may review it.
 */
class SingletonWorkflow(
    val workflowId: String,
    val label: String,
    val actionText: String?,
    val isReviewer: Boolean,
)

/** The workflows in a `formDocSingletonWorkflows` answer's `results`, in the order the backend listed them. */
fun parseSingletonWorkflows(results: Map<String, Any?>): List<SingletonWorkflow> =
    results[SWF.workflows].toJsonListOfMaps().map {
        SingletonWorkflow(
            workflowId = it[WFD.workflowId].toOptStr() ?: "",
            label = it[WFD.label].toOptStr() ?: it[WFD.workflowId].toOptStr() ?: "",
            actionText = it[SWF.actionText].toOptStr(),
            isReviewer = it[SWF.isReviewer] == true,
        )
    }

/**
 * Whether the shell says [config]'s caller may create a form for another user (issues #727, #1433): an administrator,
 * or an editor -- whoever's reads reach the client's forms. The backend's `AdminRules.createsFormsForOthers` is the
 * rule; this only decides whether the create pages offer the user picker. Pure, covered under `jsNodeTest`.
 */
fun createsFormsForOthers(config: HomeConfig): Boolean = config.canManageUsers || config.seesClientForms

/**
 * Whether the caller may delete a form owned by [ownerId] whose state is [states] (issues #1432, #1452): an
 * administrator deletes any; a caller with a [limitedDelete] -- anyone but an administrator, an editor included --
 * deletes its own ([selfUserId]), and another user's only while the form is in no workflow, by the kernel's own
 * reading of the engagement ([engagedWorkflowIds]). [states] are the ones the listing was shown, which leave out a
 * retired workflow's, as the backend's rule does -- or null when the row carried none (the single-form read), and then
 * nothing says the form is free, so another user's is not offered. Pure, covered under `jsNodeTest`.
 */
fun mayDeleteForm(ownerId: Long?, selfUserId: Long?, limitedDelete: Boolean, states: List<Map<String, Any?>>?): Boolean =
    !limitedDelete || (ownerId != null && ownerId == selfUserId) || (states != null && engagedWorkflowIds(states).isEmpty())

/**
 * Whose the form [row] is, for a delete question, when it is not [selfUserId]'s (issue #1452) -- [otherOwnerLabel] read
 * straight off the row's `userId` and `owner` block, without summarizing the rest of it.
 */
fun otherOwnerOf(row: Map<String, Any?>, selfUserId: Long?): String? {
    val owner = row[GDF.owner].toJsonMapOrEmpty()
    return otherOwnerLabel(row[GDF.userId].toOptLong(), selfUserId, owner[DUF.name] as? String, owner[DUF.email] as? String)
}

class FormSummary(
    val gedraId: String,
    /**
     * The client's declared display columns for this row: label and value, in the client's order (issue #537). The
     * document's heading is the first that is not blank ([formHeading]).
     */
    val displayValues: List<DisplayValue>,
    /** Each entry's trait, by the same friendly label the form's trait picker showed. */
    val traitLabels: List<String>,
    /**
     * When it was created, as the wire has it -- each surface formats it its own way (issue #1141): the table
     * relative to now, a form's own page in full. Null when the row carried no timestamp.
     */
    val createdAt: String?,
    /** When it was last written, as the wire has it, like [createdAt]; null when the row carried none (issue #562). */
    val updatedAt: String? = null,
    /**
     * The owner's display name, from the row's `owner` block (issue #580): present only for a caller who sees
     * other users' documents, and only when the account has a name that is not its email. Null otherwise.
     */
    val ownerName: String? = null,
    /** The owner's email, from the row's `owner` block (issue #580); null for an ordinary caller's own rows. */
    val ownerEmail: String? = null,
    /** The owner's persona, from the `owner` block (issue #1097); null when there is no block. */
    val ownerPersona: String? = null,
    /** The owner's personaSuffix, from the `owner` block (issue #1097); empty when the user has none. */
    val ownerPersonaSuffix: String = "",
    /** The owning user's id, from the row's `userId`: whose form it is, which decides who may delete it (issue #1432). */
    val ownerId: Long? = null,
    /** The owning client, from the row's `client` (issue #668): what the Client column shows for a caller who
     *  administers across clients. Every row carries it; empty only when the row somehow arrived without one. */
    val client: String = "",
    /** The form's global core details status (issue #694), or null when it has no core details state — see [formStatusFrom]. */
    val formStatus: FormStatus? = null,
    /** The form's state entries as the row carried them (`withStates`) -- what its workflow cell is drawn from (issue #791). */
    val states: List<Map<String, Any?>> = emptyList(),
    /** The steps waiting on the caller ([GDF.waitingSteps], issue #1597): which of its workflows are the caller's turn. */
    val waitingSteps: List<Map<String, Any?>> = emptyList(),
    /** Why each other step waits ([GDF.waitingReasons], issue #1598): what the cell's muted links say. */
    val waitingReasons: List<Map<String, Any?>> = emptyList(),
)

/**
 * Summarizes a form-document row (as the create or list endpoint returns it).
 *
 * A `formDoc` has **no dedicated name field** -- it is a generic bag of trait entries -- so what it presents
 * as is whatever the client's **trait-usage rules** declared (issue #537), computed on the backend and
 * attached to each row as `displayValues`. The heading is the first non-blank of those; a client that declared
 * no usage rule has none, and the document is shown unnamed -- a legitimate state, not filled with a guess.
 *
 * Each trait is named by the rule every page names one with (issue #1520): as the row's own client names it, when a
 * listed row carries the names worked out for its client (`traitNames`, issue #1543), else by `branchDisplayName` from
 * [entriesUnion] (see [entriesUnionOf]) and the delivered [layouts], whose headings come first -- as the form's trait
 * choice names it. Pure, and covered under `jsNodeTest`.
 */
fun summarizeForm(item: Map<String, Any?>, entriesUnion: SchType?, layouts: Map<String, SchLayout> = emptyMap()): FormSummary {
    val entries = item[GDF.entries].toJsonListOfMaps()
    val owner = item[GDF.owner].toJsonMapOrEmpty()
    // The row's own names first: a listing across clients holds rows the page's one-client catalog cannot name.
    val rowNames = item[GDF.traitNames].toJsonMapOrEmpty()
    val traitLabels = entries.mapNotNull { entry ->
        (entry[GE.traitId] as? String)?.let { traitId ->
            rowNames[traitId] as? String ?: branchDisplayName(traitId, entriesUnion?.variants, layouts)
        }
    }
    // The columns a client's usage rules declared, computed on the backend and attached per row (issue
    // #537). The heading is the first non-blank one -- the form's presentation "name", now client-declared
    // rather than the `name` trait hardcoded here. Each carries the data of the row's entry of its trait, which a
    // presentation reads (issue #1323): the first such entry, as the backend's value is read from.
    val displayValues = item[GDF.displayValues].toJsonListOfMaps().map {
        val traitId = it[UF.traitId] as? String ?: ""
        DisplayValue(
            columnId = it[UF.columnId] as? String ?: "",
            traitId = traitId,
            label = it[UF.label] as? String ?: "",
            value = it[UF.value] as? String ?: "",
            kind = UsageKind.entries.firstOrNull { k -> k.name == it[UF.kind] } ?: UsageKind.string,
            data = entries.firstOrNull { e -> e[GE.traitId] == traitId }?.get(GE.data).toJsonMapOrEmpty(),
        )
    }
    return FormSummary(
        gedraId = item[GDF.gedraId] as? String ?: "(unknown)",
        displayValues = displayValues,
        traitLabels = traitLabels,
        createdAt = item[GDF.createdAt] as? String,
        updatedAt = item[GDF.updatedAt] as? String,
        // The owner's name and email come from the row's `owner` block (issue #580), attached only for a caller
        // who may see other users' documents. Kept as two flat summary fields, since the User column and the
        // read-only view read them one at a time; a block absent (an ordinary caller's own row) leaves both null.
        ownerName = owner[DUF.name] as? String,
        ownerEmail = owner[DUF.email] as? String,
        // Which of the person's users owns it (issue #1097): what the cell's persona tag says.
        ownerPersona = owner[DUF.persona] as? String,
        ownerPersonaSuffix = owner[DUF.personaSuffix] as? String ?: "",
        ownerId = item[GDF.userId].toOptLong(),
        // The owning client (issue #668), attached to every listed row; the Client column shows it for a caller
        // who administers across clients.
        client = item[GDF.client] as? String ?: "",
        // The global core details status (issue #694): present only when the row carried state (`withStates`), null
        // for a client with no core details workflow, in which case the list draws no status for the row.
        formStatus = formStatusFrom(item[GDF.states].toJsonListOfMaps()),
        states = item[GDF.states].toJsonListOfMaps(),
        waitingSteps = item[GDF.waitingSteps].toJsonListOfMaps(),
        waitingReasons = item[GDF.waitingReasons].toJsonListOfMaps(),
    )
}

/**
 * One workflow the forms listing's workflow column may show (issue #791), as the listing's summary names it: its
 * [client] and [workflowId] (together the key a row's entry is joined on), its resolved [label], its [phase], the
 * resolved [explanations] of its eligibility tests by id, and its [lastTask] -- and, frozen outside its relevancy window,
 * when that window opens ([opensAt]) or closed ([closedAt]), as the wire has them (issue #1598).
 */
class WorkflowSummaryEntry(
    val client: String,
    val workflowId: String,
    val label: String,
    val phase: WfPhase,
    val explanations: Map<String, String>,
    val lastTask: String,
    val opensAt: String? = null,
    val closedAt: String? = null,
    /** Whether the workflow highlights its Start (issue #1690): one that waits on someone else is then drawn waiting. */
    val highlightStart: Boolean = false,
)

/** The listing envelope's `summary` (issue #791) as its entries; empty when there is none. Pure, and covered under `jsNodeTest`. */
fun parseWorkflowSummary(summary: Any?): List<WorkflowSummaryEntry> =
    summary.toJsonMapOrEmpty()[WCOL.workflows].toJsonListOfMaps().mapNotNull { w ->
        val phase = WfPhase.entries.firstOrNull { it.name == w[WCOL.phase] } ?: return@mapNotNull null
        WorkflowSummaryEntry(
            client = w[WCOL.client].toOptStr().orEmpty(),
            workflowId = w[WFD.workflowId].toOptStr() ?: return@mapNotNull null,
            label = w[WFD.label].toOptStr().orEmpty(),
            phase = phase,
            explanations = w[WCOL.explanations].toJsonMapOrEmpty().mapValues { it.value.toOptStr().orEmpty() },
            lastTask = w[WCOL.lastTask].toOptStr().orEmpty(),
            opensAt = w[WCOL.opensAt].toOptStr(),
            closedAt = w[WCOL.closedAt].toOptStr(),
            highlightStart = w[WCOL.highlightStart] == true,
        )
    }

/**
 * One workflow in a row's cell: the form's own [workflow] joined to the summary's [entry] for it, with what it asks of
 * the viewer (issue #1598): its [weight] from the classification, the [verb] on a link that asks something of them, and
 * the [reason] a muted link gives.
 */
class WorkflowCellItem(
    val entry: WorkflowSummaryEntry,
    val workflow: FormWorkflow,
    val weight: WfWeight,
    /** "Review", "Start": on a link of [WfWeight.yourTurn] or [WfWeight.available]; null on any other. */
    val verb: String? = null,
    /** Why a [WfWeight.waiting] link waits ("Waiting on a regional reviewer", "Closed 2026-10-30"); null on any other. */
    val reason: String? = null,
    /** For a Start that is the viewer's turn (issue #1662), the workflow's first task, which its link opens. */
    val startTask: String? = null,
) {
    /**
     * The task a link opens (issue #791): the current task of an engaged workflow, the last task of a finished
     * one (having no current task), and none for an eligible one, which opens on the workflow to engage.
     */
    val linkTask: String? get() = when (workflow.category) {
        WfColumnCategory.engaged -> workflow.ctaTask
        WfColumnCategory.finished -> entry.lastTask.ifBlank { null }
        // A Start that is the viewer's turn (issue #1662) opens on the first task, once its link has engaged the form.
        WfColumnCategory.eligible -> startTask
        else -> null
    }

    /**
     * Whether the link engages the form on the way (issue #1662): a Start that is the viewer's turn -- eligible, with the
     * workflow's first task theirs -- so one click puts the form in and opens that task ready to edit.
     */
    val linkEngages: Boolean get() = workflow.category == WfColumnCategory.eligible && weight == WfWeight.yourTurn

    /** The reasons an ineligible workflow is not offered, in its tests' order; empty otherwise. */
    val reasons: List<String> get() = workflow.failureIds.map { entry.explanations[it] ?: it }

    /** What kind of link the cell draws for it (issue #1598): its [weight], a settled one split by its category. */
    val kind: WorkflowCellKind get() = when (weight) {
        WfWeight.yourTurn -> WorkflowCellKind.yourTurn
        WfWeight.available -> WorkflowCellKind.available
        WfWeight.waiting -> WorkflowCellKind.waiting
        WfWeight.settled ->
            if (workflow.category == WfColumnCategory.finished) WorkflowCellKind.finished else WorkflowCellKind.notAvailable
    }
}

/**
 * The kinds of link the workflow cell draws (issue #1598), **in the cell's order**: the viewer's turn, then what they
 * could start, then what waits on someone else, then what is finished, and last what the form is not eligible for. It
 * is the page's order, not the classification's ([WfWeight], which keeps waiting ahead of available), and it names the
 * two kinds a settled link may be, since "settled" is no word to show anyone. Each has its heading in a popover of links
 * ([groupKey]) and its count in a folded cell ([countKey], a template over `n`), with the words shipped for both.
 */
@Suppress("EnumEntryName")
enum class WorkflowCellKind(val groupKey: String, val group: String, val countKey: String, val count: String) {
    yourTurn(HFRAG.wcYourTurnGroup, "Your turn", HFRAG.wcYourTurnCount, $$"${n} your turn"),
    available(HFRAG.wcAvailableGroup, "Available", HFRAG.wcAvailableCount, $$"${n} available"),
    waiting(HFRAG.wcWaitingGroup, "Waiting", HFRAG.wcWaitingCount, $$"${n} waiting"),
    finished(HFRAG.wcFinishedGroup, "Finished", HFRAG.wcFinishedCount, $$"${n} finished"),
    notAvailable(HFRAG.wcNotAvailableGroup, "Not available", HFRAG.wcNotAvailableCount, $$"${n} not available"),
}

/**
 * The words the workflow cell says (issue #1598), from the home file's [HFRAG.workflowCellNs] -- as shipped until the
 * copy is read. [opens] and [closed] are templates over `at`, the moment as the table says one ("in 3h", "2026-10-30");
 * [more] and each of [counts] are over `n`, and [whyNotAvailable] over `label` -- the page's pass over the copy, which
 * the server's leaves alone. [groups] and [counts] are by [WorkflowCellKind], every kind present.
 *
 * Theme: #TPLPS.
 */
data class WorkflowCellWords(
    val start: String = "Start",
    val continueVerb: String = "Continue",
    val waiting: String = "Waiting",
    val waitingOnOwner: String = "Waiting on the owner",
    val waitingOnOthers: String = "Waiting on someone else",
    val waitingForApproval: String = "Waiting for approval",
    val opens: String = $$"Opens ${at}",
    val closed: String = $$"Closed ${at}",
    val closedUndated: String = "Closed",
    val groups: Map<WorkflowCellKind, String> = WorkflowCellKind.entries.associateWith { it.group },
    val counts: Map<WorkflowCellKind, String> = WorkflowCellKind.entries.associateWith { it.count },
    val more: String = $$"+${n} more",
    val moreTitle: String = "More workflows",
    val allTitle: String = "All workflows",
    val notAvailableTip: String = "Not available: see why",
    val whyNotAvailable: String = $$"Why ${label} is not available",
    val noReasons: String = "This form does not meet the workflow's conditions.",
    val keywordMine: String = "Yours",
    val keywordAny: String = "Anyone's",
    /** A workflow's page, for a form that may start the workflow and has not (issue #1691): the line under its title. */
    val notStarted: String = "This form has not started this workflow. Start it to work on it.",
) {
    /** The heading over [kind]'s links in a popover, which is also the name of that kind. */
    fun group(kind: WorkflowCellKind): String = groups[kind] ?: kind.group

    /** How many of [kind] there are, [n], as a folded cell counts them. */
    fun count(kind: WorkflowCellKind, n: Int): String = (counts[kind] ?: kind.count).evalTemplate(mapOf("n" to n.toString()))

    /** The button for [n] folded links. */
    fun moreText(n: Int): String = more.evalTemplate(mapOf("n" to n.toString()))

    /** The title of the dialog saying why workflow [label] is not available. */
    fun whyNotAvailableText(label: String): String = whyNotAvailable.evalTemplate(mapOf("label" to label))

    companion object {
        /** The words [copy] gives, each one it does not falling back to the shipped one. Pure, under `jsNodeTest`. */
        fun of(copy: Copy): WorkflowCellWords {
            val shipped = WorkflowCellWords()
            fun t(key: String, dflt: String) = copy.t(HFRAG.workflowCellNs, key, dflt)
            return WorkflowCellWords(
                start = t(HFRAG.wcStart, shipped.start),
                continueVerb = t(HFRAG.wcContinue, shipped.continueVerb),
                waiting = t(HFRAG.wcWaiting, shipped.waiting),
                waitingOnOwner = t(HFRAG.wcWaitingOnOwner, shipped.waitingOnOwner),
                waitingOnOthers = t(HFRAG.wcWaitingOnOthers, shipped.waitingOnOthers),
                waitingForApproval = t(HFRAG.wcWaitingForApproval, shipped.waitingForApproval),
                opens = t(HFRAG.wcOpens, shipped.opens),
                closed = t(HFRAG.wcClosed, shipped.closed),
                closedUndated = t(HFRAG.wcClosedUndated, shipped.closedUndated),
                groups = WorkflowCellKind.entries.associateWith { t(it.groupKey, it.group) },
                counts = WorkflowCellKind.entries.associateWith { t(it.countKey, it.count) },
                more = t(HFRAG.wcMore, shipped.more),
                moreTitle = t(HFRAG.wcMoreTitle, shipped.moreTitle),
                allTitle = t(HFRAG.wcAllTitle, shipped.allTitle),
                notAvailableTip = t(HFRAG.wcNotAvailableTip, shipped.notAvailableTip),
                whyNotAvailable = t(HFRAG.wcWhyNotAvailable, shipped.whyNotAvailable),
                noReasons = t(HFRAG.wcNoReasons, shipped.noReasons),
                keywordMine = t(HFRAG.wcKeywordMine, shipped.keywordMine),
                keywordAny = t(HFRAG.wcKeywordAny, shipped.keywordAny),
                notStarted = t(HFRAG.wcNotStarted, shipped.notStarted),
            )
        }
    }
}

/**
 * The workflow cell of [form] (issues #791, #1598): its workflows weighed by the kernel's classification
 * ([formWorkflowWeights]) -- the caller's turn exactly where the row's [FormSummary.waitingSteps] name a step, the one
 * thing state cannot say -- each joined to [summary] for the form's client, so the page decides nothing about what is
 * actionable. In a fixed order: by [WorkflowCellKind] -- the viewer's turn, could start, waiting, finished, not
 * available -- then the workflows' declared order (the summary's), never by the order the state entries arrive in or by
 * recency, so a row's links hold their places as people act on the form.
 *
 * A link the viewer acts on takes its verb from the CTA's keyword ([keywords], in the form's client's words), else from
 * what its step asks in words, else [words]' "Continue"; one they could start says "Start"; a waiting one says why, from
 * [FormSummary.waitingReasons] or, frozen, from its window, read against [now]. Pure, and covered under `jsNodeTest`.
 */
fun workflowCellOf(
    form: FormSummary,
    summary: List<WorkflowSummaryEntry>,
    keywords: List<CtaKeywordWords> = emptyList(),
    words: WorkflowCellWords = WorkflowCellWords(),
    now: Instant = Clock.System.now(),
): List<WorkflowCellItem> {
    val declared = summary.filter { it.client == form.client }.withIndex().associateBy { it.value.workflowId }
    val steps = form.waitingSteps.associateBy { it[WFD.workflowId].toOptStr() }
    val reasons = form.waitingReasons.associateBy { it[WFD.workflowId].toOptStr() }
    val highlights = { id: String -> declared[id]?.value?.highlightStart == true }
    return formWorkflowWeights(form.states, { declared[it]?.value?.phase }, highlights) { workflow, _ -> workflow.workflowId in steps }
        .mapNotNull { weighed ->
            val id = weighed.workflow.workflowId
            val entry = declared[id]?.value ?: return@mapNotNull null
            val starts = weighed.workflow.category == WfColumnCategory.eligible
            val verb = when (weighed.weight) {
                // A Start that is the viewer's turn (issue #1662) says "Start", as any eligible one does.
                WfWeight.yourTurn -> if (starts) words.start else keywords.firstOrNull { it.keyword == weighed.keyword }?.verb
                    ?.ifBlank { null } ?: steps[id]?.get(SWF.actionText).toOptStr()?.ifBlank { null } ?: words.continueVerb
                WfWeight.available -> words.start
                else -> null
            }
            val reason = if (weighed.weight == WfWeight.waiting) waitingReasonOf(entry, reasons[id], words, now) else null
            val startTask = if (starts && weighed.weight == WfWeight.yourTurn) steps[id]?.get(WFS.ctaTask).toOptStr() else null
            WorkflowCellItem(entry, weighed.workflow, weighed.weight, verb, reason, startTask)
        }
        .sortedWith(compareBy({ it.kind.ordinal }, { declared[it.workflow.workflowId]?.index ?: Int.MAX_VALUE }))
}

/**
 * Why a waiting link waits (issue #1598): a workflow frozen outside its relevancy window ([entry]'s phase does not
 * calculate) says when it opens or closed; any other says whom its step waits on ([reason], a row's
 * [GDF.waitingReasons] entry) -- the CTA filter's own label when the filter is what keeps the viewer out -- and one the
 * row gives none for (a call to action not computed yet, or a step with nothing to save or approve) just waits. The
 * window's words are the page's pass over the copy (`${at}`); the filter's label arrives resolved by the server's.
 *
 * Theme: #TPLPS.
 */
fun waitingReasonOf(entry: WorkflowSummaryEntry, reason: Map<String, Any?>?, words: WorkflowCellWords, now: Instant): String {
    fun at(template: String, iso: String) = template.evalTemplate(mapOf("at" to formatRelativeTime(iso, now)))
    if (!entry.phase.calculates) {
        return entry.opensAt?.let { at(words.opens, it) } ?: entry.closedAt?.let { at(words.closed, it) } ?: words.closedUndated
    }
    return when (WfWaitsOn.entries.firstOrNull { it.name == reason?.get(WCOL.waitsOn) }) {
        WfWaitsOn.filter -> reason?.get(WFS.ctaFilterLabel).toOptStr()?.ifBlank { null } ?: words.waitingOnOthers
        WfWaitsOn.approval -> words.waitingForApproval
        WfWaitsOn.owner -> words.waitingOnOwner
        WfWaitsOn.others -> words.waitingOnOthers
        null -> words.waiting
    }
}

/**
 * Where a workflow-column link goes (issue #791): the core details page opened on normal workflow [workflowId] against
 * form [gedraId], on [task] when given, and in edit mode when [edit] -- the current task of an engaged workflow is
 * work to do; a finished workflow's last task is there to be looked at. [from] is the listing the link is on, so
 * the page's way back leads to it. Pure, and covered under `jsNodeTest`.
 */
fun workflowPageRoute(
    gedraId: String,
    workflowId: String,
    task: String?,
    edit: Boolean,
    from: String = HMENU.pageForms,
    /** Engage the form on arrival (issue #1662): a highlighted Start's link. */
    engage: Boolean = false,
): List<Pair<String, String>> =
    buildList {
        add(HP.page to pageCoreDetailsEdit)
        add(HP.from to from)
        add(HP.gedra to gedraId)
        add(HP.workflow to workflowId)
        task?.let { add(HP.task to it) }
        if (edit && task != null) add(HP.edit to "1")
        if (engage) add(HP.engage to "1")
    }

/**
 * Whether a cell item's link opens its task ready to edit (issues #791, #1598): only the current task of a workflow that
 * is the viewer's turn. A waiting one opens to be looked at, since its step is someone else's.
 */
val WorkflowCellItem.linkEdits: Boolean get() = weight == WfWeight.yourTurn

/**
 * The search keys that say **which workflow's forms** a listing shows (issue #792): the workflow and the state. On
 * the workflow's listing they are its identity rather than a filter -- kept by Clear, part of what a history entry
 * is -- though they ride the query like any other search key, so paging, sorting and a client switch carry them.
 */
val formsDrillKeys: Set<String> = setOf(WAGG.workflowId, WAGG.workflowState, WAGG.keyword, WAGG.keywordWhose)

/**
 * The search keys the listing's **Clear** keeps: the scope controls outside the filter panel -- whose forms
 * ([EI.user], issue #562) and which client's ([EI.client], #668), each cleared through its own control -- and the
 * workflow's listing's own identity ([formsDrillKeys]), which no filter control put there.
 */
val formsScopeKeys: Set<String> = setOf(EI.user, EI.client) + formsDrillKeys

/**
 * The workflow drill-down a forms search carries (issue #792): the workflow id and, when given, the state -- or null
 * when the listing is not drilled into a workflow. Read off the applied search, where the address put it.
 */
fun workflowDrillOf(applied: Map<String, Any?>): Pair<String, WfColumnCategory?>? {
    val id = applied[WAGG.workflowId]?.toString()?.ifBlank { null } ?: return null
    val state = applied[WAGG.workflowState]?.toString()?.let { name -> WfColumnCategory.entries.firstOrNull { it.name == name } }
    return id to state
}

/**
 * The keyword drill-down a forms search carries (issue #1600): the keyword and whose ([CTKQ.mine] or [CTKQ.any], the
 * latter when unsaid), or null when the listing is not drilled onto a keyword count. Pure, and covered under `jsNodeTest`.
 */
fun workflowKeywordDrillOf(applied: Map<String, Any?>): Pair<String, String>? {
    if (workflowDrillOf(applied) == null) return null
    val keyword = applied[WAGG.keyword]?.toString()?.ifBlank { null } ?: return null
    val whose = applied[WAGG.keywordWhose]?.toString()?.takeIf { it == CTKQ.mine } ?: CTKQ.any
    return keyword to whose
}

/**
 * A keyword count's words (issue #1600), "Needs review: Yours" -- the keyword's filter label and whose, as the forms
 * list's keyword filter chip says them ([keywordFilterChips]) -- or the keyword itself when [keywords] does not word it.
 * Pure, and covered under `jsNodeTest`.
 */
fun keywordCountWords(keyword: String, whose: String, keywords: List<CtaKeywordWords>, words: WorkflowCellWords): String {
    val label = keywords.firstOrNull { it.keyword == keyword }?.filterLabel?.ifBlank { null } ?: keyword
    return "$label: ${if (whose == CTKQ.mine) words.keywordMine else words.keywordAny}"
}

/**
 * What the workflow's listing is headed (issue #792): the workflow's label from the listing's [summary] when it names
 * the workflow -- under the listing's client, across clients -- else its id. Null when the search names no workflow.
 * Pure, and covered under `jsNodeTest`.
 */
fun workflowDrillLabel(applied: Map<String, Any?>, summary: List<WorkflowSummaryEntry>): String? {
    val (id, _) = workflowDrillOf(applied) ?: return null
    val client = applied[EI.client]?.toString()
    return summary.firstOrNull { it.workflowId == id && (client == null || it.client == client) }?.label ?: id
}

/**
 * The line under the workflow's listing's heading (issue #792): which forms these are, `The forms engaged with
 * this workflow.` -- or every form it knows when the search names no state. Pure, and covered under `jsNodeTest`.
 */
fun workflowDrillNote(state: WfColumnCategory?, keywordWords: String? = null): String = when {
    // A keyword count (issue #1600): the engaged forms its words narrow, "... under way — Needs review: Yours."
    keywordWords != null -> "The forms ${workflowDrillForms(state ?: WfColumnCategory.engaged)} — $keywordWords."
    else -> state?.let { "The forms ${workflowDrillForms(it)}." } ?: "Every form this workflow applies to."
}

/** Which forms a workflow's listing in [state] shows, after "the forms": its heading's line and the saved note's. */
fun workflowDrillForms(state: WfColumnCategory): String = when (state) {
    WfColumnCategory.eligible -> "eligible for this workflow, not yet in it"
    WfColumnCategory.engaged -> "engaged with this workflow, with work under way"
    WfColumnCategory.finished -> "finished with this workflow"
    WfColumnCategory.ineligible -> "not eligible for this workflow"
}

/**
 * One workflow on the workflow pages (issue #792), as the aggregate reports it: which it is, where it stands, and how
 * many of the caller's forms are eligible for it, engaged with it and finished with it.
 */
class WorkflowAggregateEntry(
    val client: String,
    val workflowId: String,
    val label: String,
    val phase: WfPhase?,
    val eligible: Int,
    val engaged: Int,
    val finished: Int,
    /** Each search keyword the workflow's calls to action may carry, with its counts (issue #1600); empty for none. */
    val keywords: List<WorkflowKeywordCount> = emptyList(),
)

/**
 * One search keyword on the workflow pages (issue #1600): how many forms' calls to action in the workflow carry it
 * ([any]), and how many of those the caller may act on now ([mine]).
 */
class WorkflowKeywordCount(val keyword: String, val any: Int, val mine: Int)

/**
 * The search keywords the workflow pages head a column for (issue #1600): every keyword some entry of [entries] counts,
 * in the order the entries first name them. Pure, and covered under `jsNodeTest`.
 */
fun aggregateKeywords(entries: List<WorkflowAggregateEntry>): List<String> =
    entries.flatMap { e -> e.keywords.map { it.keyword } }.distinct()

/** The aggregate endpoint's items as entries (issue #792). Pure, and covered under `jsNodeTest`. */
fun parseWorkflowAggregate(items: List<Map<String, Any?>>): List<WorkflowAggregateEntry> = items.mapNotNull { w ->
    WorkflowAggregateEntry(
        client = w[WCOL.client].toOptStr().orEmpty(),
        workflowId = w[WFD.workflowId].toOptStr() ?: return@mapNotNull null,
        label = w[WFD.label].toOptStr().orEmpty(),
        phase = WfPhase.entries.firstOrNull { it.name == w[WCOL.phase] },
        eligible = (w[WAGG.eligible] as? Number)?.toInt() ?: 0,
        engaged = (w[WAGG.engaged] as? Number)?.toInt() ?: 0,
        finished = (w[WAGG.finished] as? Number)?.toInt() ?: 0,
        keywords = w[WAGG.keywords].toJsonListOfMaps().mapNotNull { k ->
            WorkflowKeywordCount(
                keyword = k[CTKF.keyword].toOptStr() ?: return@mapNotNull null,
                any = (k[CTKQ.any] as? Number)?.toInt() ?: 0,
                mine = (k[CTKQ.mine] as? Number)?.toInt() ?: 0,
            )
        },
    )
}

/**
 * Where a count on the workflow pages leads (issue #792): the **workflow's listing** ([pageWorkflowForms]) of the forms
 * in [state] for [workflowId] -- its own page under Workflows rather than My forms filtered, so what it lists is
 * its heading and not a chip. The workflow and state ride the address as search keys, so the listing sends them on
 * like any other; narrowed to [client] when the caller sees across clients, since the listing's client names
 * whose workflow it is. Pure, and covered under `jsNodeTest`.
 */
fun workflowDrillRoute(workflowId: String, state: WfColumnCategory, client: String?): List<Pair<String, String>> =
    listOf(HP.page to pageWorkflowForms, WAGG.workflowId to workflowId, WAGG.workflowState to state.name) +
        listOfNotNull(client?.let { EI.client to it })

/**
 * Where a keyword count on the workflow pages leads (issue #1600): the workflow's listing of its engaged forms, narrowed
 * to those whose call to action carries [keyword], asked [whose] -- [workflowDrillRoute] with the keyword's pair, so the
 * listing's Engaged state is the one lit. Pure, and covered under `jsNodeTest`.
 */
fun workflowKeywordDrillRoute(workflowId: String, keyword: String, whose: String, client: String?): List<Pair<String, String>> =
    workflowDrillRoute(workflowId, WfColumnCategory.engaged, client) + listOf(WAGG.keyword to keyword, WAGG.keywordWhose to whose)

/**
 * A drill-down state as the workflow pages head its count -- Eligible, Engaged, Finished (issue #792) -- in lower
 * case for a chip, so the count clicked and the listing it opens name the state with one word.
 */
fun workflowStateHeading(state: WfColumnCategory): String = when (state) {
    WfColumnCategory.eligible -> "eligible"
    WfColumnCategory.engaged -> "engaged"
    WfColumnCategory.finished -> "finished"
    WfColumnCategory.ineligible -> "not eligible"
}

/** [workflowStateHeading] as a title -- the Workflows page's column headings and the listing's state switch. */
fun workflowStateTitle(state: WfColumnCategory): String = workflowStateHeading(state).replaceFirstChar { it.uppercase() }

/** A phase as the workflow pages say it beside a workflow's name (issue #792); empty for one open to new forms. */
fun workflowPhaseText(phase: WfPhase?): String = when (phase) {
    WfPhase.lifetimeOnly -> "closed"
    WfPhase.relevant -> "not taking new forms"
    else -> ""
}

/**
 * A cell folded to its counts (issues #791, #1598), what a narrow screen shows: each kind of link the [items] hold, in
 * the cell's order, in [words] -- `1 your turn · 2 available`. Empty for none. Pure, and covered under `jsNodeTest`.
 */
fun workflowCellCounts(items: List<WorkflowCellItem>, words: WorkflowCellWords = WorkflowCellWords()): String =
    items.groupBy { it.kind }.entries
        .sortedBy { it.key.ordinal }
        .joinToString(" \u00b7 ") { words.count(it.key, it.value.size) }

/**
 * When a Workflows cell folds (issues #1598, #1599): up to [foldPast] links it draws them all; past that it draws
 * [drawn] and folds the rest, so a fold hides at least two.
 */
class WorkflowCellFoldAt(val foldPast: Int, val drawn: Int) {
    companion object {
        /** The `combined` layout's cell (issue #1598), which holds the viewer's turn too. */
        val combined = WorkflowCellFoldAt(foldPast = 4, drawn = 3)

        /** The `split` layout's (issue #1599), whose Your turn column holds the strong links, leaving the cell the rest. */
        val split = WorkflowCellFoldAt(foldPast = 3, drawn = 2)
    }
}

/**
 * A cell's links, in cell order, split into those drawn and those folded behind a "+N" (issues #1598, #1599), as
 * [at] says. The viewer's turn never folds: those links come first, are all drawn whatever their number, and count
 * toward the links drawn -- in a combined cell, two of them and four others draw one more -- so a fold hides at least
 * two unless the viewer's turn alone fills the cell. Pure, under `jsNodeTest`.
 */
fun workflowCellFold(
    items: List<WorkflowCellItem>,
    at: WorkflowCellFoldAt = WorkflowCellFoldAt.combined,
): Pair<List<WorkflowCellItem>, List<WorkflowCellItem>> {
    if (items.size <= at.foldPast) return items to emptyList()
    val drawn = maxOf(at.drawn, items.count { it.weight == WfWeight.yourTurn })
    return items.take(drawn) to items.drop(drawn)
}

/**
 * The Your turn column's header (issue #1599): the one search keyword in use's own header ("To review"), else -- none
 * in use, or more than one -- the cell's "Your turn" ([WorkflowCellWords.group]); with [count], how many forms are the
 * viewer's turn, "To review (4)". [keywordsInUse] are the keywords the listing's client's workflows use, in its words.
 * Pure, under `jsNodeTest`.
 */
fun yourTurnHeader(keywordsInUse: List<CtaKeywordWords>, words: WorkflowCellWords, count: Int?): String {
    val header = keywordsInUse.singleOrNull()?.columnHeader?.ifBlank { null } ?: words.group(WorkflowCellKind.yourTurn)
    return if (count == null) header else "$header ($count)"
}

/**
 * The search keywords a listing offers a filter for (issue #1599): those [keywords] whose parameter the listing's
 * endpoint declares in [searchKeys] -- the keywords its client's workflows use, on a client's own listing; none on the
 * shared one. In [keywords]' order. Pure, under `jsNodeTest`.
 */
fun keywordsInUse(keywords: List<CtaKeywordWords>, searchKeys: Set<String>): List<CtaKeywordWords> =
    keywords.filter { it.keyword in searchKeys }

/**
 * The chips of the keyword filters applied (issue #1599), one per keyword with a question in [applied]: its filter
 * label and the choice, "Needs review: Yours". Pure, under `jsNodeTest`.
 */
fun keywordFilterChips(applied: Map<String, Any?>, keywords: List<CtaKeywordWords>, words: WorkflowCellWords): List<String> =
    keywords.mapNotNull { kw ->
        applied[kw.keyword]?.toString()?.takeIf { it == CTKQ.mine || it == CTKQ.any }
            ?.let { whose -> keywordCountWords(kw.keyword, whose, keywords, words) }
    }

// `formatTimestamp`, which shows a wire timestamp in the client's zone, is in `DisplayZone.kt` (issue #1115).
