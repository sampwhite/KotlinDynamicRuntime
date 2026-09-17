package com.dynamicruntime.common.gedra

import com.dynamicruntime.common.cfact.CFACTS
import com.dynamicruntime.common.context.KdrCxt
import com.dynamicruntime.common.context.ReadScope
import com.dynamicruntime.common.endpoint.EI
import com.dynamicruntime.common.endpoint.EP
import com.dynamicruntime.common.endpoint.HttpMethod
import com.dynamicruntime.common.endpoint.ListPage
import com.dynamicruntime.common.endpoint.SchModule
import com.dynamicruntime.common.endpoint.defaultListLimit
import com.dynamicruntime.common.endpoint.schemaModule
import com.dynamicruntime.common.exception.EXC
import com.dynamicruntime.common.exception.KdrException
import com.dynamicruntime.common.gedra.workflow.SVY
import com.dynamicruntime.common.gedra.workflow.SVYS
import com.dynamicruntime.common.gedra.workflow.WSF
import com.dynamicruntime.common.gedra.workflow.WVF
import com.dynamicruntime.common.gedra.workflow.surveyStatusOf
import com.dynamicruntime.common.gedra.workflow.PFO
import com.dynamicruntime.common.gedra.workflow.WfDeclared
import com.dynamicruntime.common.gedra.workflow.WfEventType
import com.dynamicruntime.common.gedra.workflow.WorkflowService
import com.dynamicruntime.common.gedra.workflow.noWorkflowView
import com.dynamicruntime.common.gedra.workflow.resolveWorkflowView
import com.dynamicruntime.common.gedra.workflow.saveWorkflow
import com.dynamicruntime.common.schema.SCT
import com.dynamicruntime.common.startup.SchemaService
import com.dynamicruntime.common.user.AdminRules
import com.dynamicruntime.common.user.AuthUserRow
import com.dynamicruntime.common.user.ReadScopeRules
import com.dynamicruntime.common.user.UserService
import com.dynamicruntime.common.user.refreshActingRoles
import com.dynamicruntime.common.util.fmt
import com.dynamicruntime.common.util.getOptBool
import com.dynamicruntime.common.util.toJsonListOfMaps
import com.dynamicruntime.common.util.toJsonMapOrEmpty
import com.dynamicruntime.common.util.toOptLong
import com.dynamicruntime.common.util.toOptStr

// `GEP` (the endpoint paths and response type-names) now lives in `base/kernel` (GedraConstants.kt) so the
// front end can name them too (issue #393); this file's references resolve unchanged, same package.

/**
 * The endpoints over stored gedra data -- create a form document, read one, list them (issue #310), delete
 * one (#326), and patch several at once (#337).
 *
 * They sit in the **`gedra`** section, which is login-gated (`RequestService.userSections`). That is the whole
 * of the level check, and it is deliberately not more: how far a caller reaches is a *scope* question rather
 * than a privilege one, and `ReadScopeRules.forCaller` answers it -- an ordinary user reaches their own
 * documents, an administrator their client's (narrowed to their organization if they have one), and an
 * administrator holding `allClients` reaches everything. One surface serves all of them, which is why there is
 * no second listing endpoint behind an admin section.
 *
 * This is the endpoint `ReadScopeRules` has been waiting for. Its own note says the own-user width had no
 * surface reaching it and would arrive with "the first ordinary endpoint over an owned table". This is that
 * endpoint, and the width is now exercised by a caller rather than only by a test.
 *
 * ### These are the **shared** copies, and they publish global schema
 *
 * Every endpoint here also exists per client, at a path naming one -- `/gedra/acme/formDoc/create` beside
 * `/gedra/formDoc/create` (issue #387). The difference is not cosmetic and matters most to whoever builds a
 * form:
 *
 *  - **Here**, the published input type is **global**, because one path serves every client and
 *    `RequestService` caches resolved types by path. A client's narrowing is enforced only where the entry is
 *    *stored*, so a form built from this schema offers choices a client has removed and finds out on save.
 *  - **On a client's own path**, the published type is that client's, so what is advertised is what is
 *    enforced, and a control cannot offer what the client removed.
 *
 * **So a UI must reach the *client's* path, not the bare shared one.** `GET /schema/endpoints` already answers
 * with the caller's own client's paths -- an `acme` user is shown `/gedra/acme/...` and *not* the shared one --
 * so a form built from a catalog entry and posted to the `path` it carries is client-scoped by construction.
 * `GEP` now lives in `base/kernel` (issue #393), so a frontend *can* name these paths; the safe way to use one
 * is `clientPath(GEP.formDoc, client)`, which builds that client's surface. Posting the bare `GEP.formDoc` is
 * the mistake this warns about -- it silently selects the global schema, and the symptom looks like a backend
 * fault rather than a wrong path.
 *
 * An `allClients` holder can ask for one client's surface with the catalog's `client` filter.
 */
/**
 * One stored row's wire map with the client's computed display values attached (issue #537). The list path
 * inlines the same thing over a page; this is the single-read counterpart, so a form opened directly presents
 * the same columns a listing does.
 */
/**
 * [row] with each entry's g-derived data values computed on read (issue #712), so the wire document, the
 * display columns, the search filter and the sort all read the same values -- a derived field (an expense
 * report's total) must not show in a column that its own filter and sort cannot see. Keyed on the row's own
 * client, so an `allClients` caller reading across clients gets each form's derivers, not their own client's.
 * The row is a throwaway extraction (a fresh object per read, never the cached map), so replacing its entries
 * enriches the response without touching what is stored; deriving is idempotent, so calling it in a filter and
 * again for display is harmless.
 */
private fun withDerivedEntries(cxt: KdrCxt, row: GedraDataRow): GedraDataRow {
    row.entries = deriveEntryData(cxt, row.kind, row.entries, row.client)
    return row
}

private fun withDisplayValues(cxt: KdrCxt, row: GedraDataRow): Map<String, Any?> {
    val derived = withDerivedEntries(cxt, row)
    return derived.toJsonMap() +
        (GDF.displayValues to computeDisplayValues(cxt, derived, SchemaService.get(cxt).traitUsagesFor(cxt.client)))
}

/**
 * How many of the caller's most-recent documents a field-value suggestion list scans (issue #581). A suggestion
 * list is a convenience, not a report: scanning the most recent this many surfaces the values a person is
 * likely reaching for, and bounds the work so the endpoint stays cheap enough to answer a keystroke.
 */
private const val fieldValueScanCap = 2000

fun gedraSchema(cxt: KdrCxt): SchModule = schemaModule(cxt, GEP.gedraNamespace) {
    val formDoc = GedraDataType.formDoc
    val docType = GU.gedraName(formDoc)
    GedraDataRow.defineType(this, formDoc)

    // Creation takes the same type it returns. Everything but `entries` is `g-derived`, so the input
    // projection leaves a caller supplying exactly the part that is theirs -- and a client that echoes a whole
    // document back (which is how every form works) has its derived fields dropped rather than refused.
    itemEndpoint(
        GEP.formDocCreate,
        "Creates a form document carrying the supplied entries, and answers with it as stored.",
        HttpMethod.POST,
        outputRef = docType,
        // The sent shape, not the stored one: they differ by `allowAdditionalTraits` and the on-behalf `user`,
        // which are instructions about this write and have no place in what a document *is* (issues #379, #727).
        inputRef = GU.inputName(formDoc),
        // The form surface a client's own application calls, so it is part of the published API (issue #489);
        // the per-client copies inherit this. Marks are on the five here at once, so the set reads as one
        // decision. `needsClientConfig` is the sync opt-in (issue #618): a form document is validated against
        // the client's configured schema, so the node runs current config before serving one -- the whole form
        // surface carries it, for the same reason the publicApi marks do.
        publicApi = true,
        needsClientConfig = true,
    ) { c, request ->
        val entries = request[GDF.entries].toJsonListOfMaps()
        // On-behalf create (issue #727): when `user` names someone else, an admin creates the form for them,
        // owned in their scope, the caller left as the actor; absent or self, this is `c` unchanged.
        val ownerCxt = createForUserCxt(c, request)
        GedraDataService.get(ownerCxt)
            .createGedra(ownerCxt, formDoc, entries, request.getOptBool(GDF.allowAdditionalTraits) == true)
            .toJsonMap()
    }

    itemEndpoint(
        GEP.formDoc,
        "Fetches one form document by its gedra id.",
        HttpMethod.GET,
        outputRef = docType,
        inputFields = {
            field(GDF.gedraId, "Id of the form document to fetch.", required = true)
        },
        publicApi = true,
        needsClientConfig = true,
    ) { c, request ->
        val fullId = request[GDF.gedraId].toOptStr()
            ?: throw KdrException.mkInput("A ${GDF.gedraId} is required.")
        val row = GedraDataService.get(c).queryGedra(c, fullId, formDoc, ReadScopeRules.forCaller(c))
        // Absent, disabled, the wrong kind and out of scope all arrive here as null, and all leave as 404 --
        // see `GedraDataService.queryGedra` for why the last of those must not be distinguishable.
            ?: throw KdrException("No form document '$fullId'.", code = EXC.notFound)
        withDisplayValues(c, row)
    }

    // What a delete answers with. Not the document: a caller who has just deleted something does not want it
    // handed back looking exactly like a live one, since the type carries no `enabled` to tell them apart.
    type(GEP.deletedGedra) {
        type = SCT.kObject
        description = "Confirmation that a gedra was deleted."
        property(GDF.gedraId, "Id of the gedra that was deleted.", required = true)
    }

    // Shares a URL with `GET /gedra/formDoc` and differs only by verb, which is what the method is for
    // (issue #335). `KdrEndpoint.collationKey` is already `path:method`, so two endpoints on one path is
    // routine. The input travels as query params rather than a body, like the GET's -- see [HttpMethod.DELETE]
    // for why nothing here sends a DELETE body.
    generalEndpoint(
        GEP.formDoc,
        "Deletes a form document, so that it is no longer readable or listed.",
        HttpMethod.DELETE,
        outputRef = GEP.deletedGedra,
        inputFields = {
            field(GDF.gedraId, "Id of the form document to delete.", required = true)
        },
        publicApi = true,
        needsClientConfig = true,
    ) { c, request ->
        val fullId = request[GDF.gedraId].toOptStr()
            ?: throw KdrException.mkInput("A ${GDF.gedraId} is required.")
        // Absent, already deleted, the wrong kind and out of scope all answer false and all leave as 404 --
        // the same four-into-one the read makes, so trying to delete something reveals no more than trying to
        // read it. A second delete is therefore a 404 rather than a quiet success, which says plainly that
        // there was nothing there rather than implying this call is what removed it.
        if (!GedraDataService.get(c).deleteGedra(c, fullId, formDoc, ReadScopeRules.forCaller(c))) {
            throw KdrException("No form document '$fullId'.", code = EXC.notFound)
        }
        mapOf(GDF.gedraId to fullId)
    }

    // The listing's stable input, as a named type so a per-client copy can carry that client's search fields
    // (issue #538). Only the fields authored here are stable; the search fields for each scope are generated
    // from its usage rules and merged onto this type at boot (see `augmentFormDocsQuery`).
    type(GEP.formDocsQuery) {
        type = SCT.kObject
        description = "The forms-listing query: paging, an optional user filter, and a client's search fields."
        property(EP.offset, "How many documents to skip before this page; 0 for the first page.") {
            type = SCT.integer
            // Empty means the default rather than a 400, and a page never starts before the beginning.
            // (A query param arrives as text; an integer coerces from one by default.)
            emptyIsAbsent = true
            minimum = 0
            default = 0
        }
        // Confine the search to one user -- a userId or an email (issue #545). Shown only to a caller who
        // ranks at admin (`g-visibleWhen`), since an ordinary user reaches only their own rows and the
        // param would name nobody else; the handler enforces the same, resolving the ref within the
        // caller's read scope, so an ordinary caller who sends it can still only ever name themselves.
        property(EI.user, "Confine the search to one user -- a userId or an email. Defaults to you.") {
            emptyIsAbsent = true
            visibleWhen = CFACTS.hasAdminLevel
        }
        // Confine the listing to one client (issue #668) -- the cross-client counterpart of the `user` filter.
        // Shown to any admin (`g-visibleWhen`), but honored only for an `allClients` caller, whose scope spans
        // clients; for anyone else the handler ignores it, since their scope cannot widen to a client they are
        // not in. The frontend draws the control only for an `allClients` caller (the `canSeeAllClients` flag).
        property(EI.client, "Confine the listing to one client. Honored only for a caller who sees across clients.") {
            emptyIsAbsent = true
            visibleWhen = CFACTS.hasAdminLevel
        }
        // The free-text term (issue #562): one box that searches every text field at once, so a caller need
        // not know which column holds the value they remember. ANDed with any per-field filters also sent.
        property(EI.q, "Free text matched against every text search field (any field, case-insensitive substring).") {
            emptyIsAbsent = true
        }
        // Whether to attach the per-row owner block (issue #591): off unless asked, so the owner lookup and the
        // extra payload happen only where a caller draws the User column. Admin-only like the `user` filter --
        // an ordinary caller's rows are all their own, so there is nobody else to name -- and gated by scope on
        // top of this in the handler, so setting it can never widen what a caller may see.
        property(EI.includeUsers, "Attach the owner (user) of each document. Defaults to false; a caller who may not see other users' documents gets nothing regardless.") {
            type = SCT.boolean
            emptyIsAbsent = true
            visibleWhen = CFACTS.hasAdminLevel
        }
        // Attach each form's state entries (issue #600): off unless asked, so the states read happens only where
        // a caller wants them. Not admin-gated -- a caller sees the state of the forms they can already see,
        // read with the same scope that admitted the row. A boolean on a GET, so it coerces from query text.
        property(GDF.withStates, "Attach each document's state entries (issue #600). Defaults to false.") {
            type = SCT.boolean
            emptyIsAbsent = true
            allowCoerce = true
        }
        // Filter by the form's global survey status (issue #695): a closed choice over the three statuses the
        // column shows. Not a trait search field -- it reads the form's state, not a display value -- so it is
        // applied over the states cache before paging, and `numAvailable` counts what matched. A form with no
        // computed state matches none of them. Not admin-gated: a caller filters the forms they can already see.
        property(SVY.surveyStatus, "Only forms whose survey status is this: ${SVYS.valid} / ${SVYS.needsInfo} / ${SVYS.invalid}. Absent means any.") {
            emptyIsAbsent = true
            option(SVYS.valid, "Valid")
            option(SVYS.needsInfo, "Needs Info")
            option(SVYS.invalid, "Invalid")
        }
        // The sort (issue #666): a column to order by -- a display trait id, or a fixed column -- and a
        // direction. Absent means the default (most recently written first). Not admin-gated: any caller may
        // order the rows they can already see (`${GSORT.owner}` and `${GSORT.client}` are admin/allClients-only,
        // as their columns are, and are ignored otherwise). Open text, since the column names a client's own trait;
        // an unknown column falls back to the default order rather than faulting a stale bookmark.
        property(
            GSORT.sort,
            "Order by this column: a display trait id, or `${GSORT.updated}` / `${GSORT.created}` / " +
                "`${GSORT.contains}` / `${GSORT.owner}` (admin-only) / `${GSORT.client}` (allClients-only). " +
                "Defaults to most recently written.",
        ) {
            emptyIsAbsent = true
        }
        property(GSORT.sortDir, "Sort direction. Defaults to ascending when a column is named.") {
            emptyIsAbsent = true
            option(GSORT.asc, "Ascending")
            option(GSORT.desc, "Descending")
            openOptions()
        }
    }

    listEndpoint(
        GEP.formDocs,
        "Lists the form documents the caller may see, most recently written first, a page at a time.",
        outputRef = docType,
        // Paging (issue #408): the answer carries whether more remain and the total the scope admits, so a UI
        // can page past the default limit rather than silently seeing only the first page.
        hasMore = true,
        hasNumAvailable = true,
        inputRef = GEP.formDocsQuery,
        publicApi = true,
        needsClientConfig = true,
    ) { c, request ->
        val limit = (request[EP.limit] as? Number)?.toInt() ?: defaultListLimit
        val offset = (request[EP.offset] as? Number)?.toInt() ?: 0
        val callerScope = ReadScopeRules.forCaller(c)
        // A client filter narrows an `allClients` caller's (unrestricted) scope to one client (issue #668); it is
        // ignored for anyone else, whose scope cannot widen to a client they are not in. Naming a client that is
        // not present just yields no rows, the same as a search matching nothing.
        val clientFilter = (request[EI.client] as? String)?.trim()?.ifEmpty { null }?.takeIf { AdminRules.canSeeAllClients(c) }
        val baseScope = if (clientFilter != null) ReadScope.ofClient(clientFilter) else callerScope
        // A named user narrows further to that user -- but only within what the caller may already see (see
        // [resolveTargetUser], resolved within the client-narrowed scope); no name is the client/caller scope.
        val target = resolveTargetUser(c, request, baseScope)
        val scope = if (target == null) baseScope else ReadScope.ofUser(target.userId)
        // The client's usage rules, read once: they drive both the display columns (issue #537) and the search
        // parameters (issue #538). A search parameter the caller filled becomes an in-memory predicate applied
        // before paging, so the page and its `numAvailable` are both over the matched set (see `listGedras`).
        val usages = SchemaService.get(c).traitUsagesFor(c.client)
        val filter = searchFilter(c, request, usages)
        val sort = gedraSortFor(c, request, usages, scope)
        // The survey-status filter (issue #695): a predicate over a row's state entries, through the same rule
        // the status column reads them by, applied by `listGedras` over the states cache before paging. The
        // schema has already settled the value: a blank is absent (`emptyIsAbsent`), anything but the three
        // options was refused, so what arrives is one of them or nothing.
        val statusWanted = request[SVY.surveyStatus] as? String
        val stateFilter: ((List<Map<String, Any?>>) -> Boolean)? = statusWanted?.let { wanted -> { states -> surveyStatusOf(states) == wanted } }
        val svc = GedraDataService.get(c)
        val page = svc.listGedras(c, formDoc, scope, limit, offset, filter, sort, stateFilter)
        // Attach each form's state (issue #600) only when asked. One batch read over the page's ids -- cache-
        // first off the resident states cache, the misses (a form with no state, or a cache-absent node) sharing
        // one session -- read with the same `scope` that admitted the rows, so the state a caller sees is
        // exactly the state of the forms they can already see.
        val withStates = request.getOptBool(GDF.withStates) == true
        val statesByGedra = if (withStates) svc.readStates(c, page.rows.map { it.gedraId }, scope) else emptyMap()
        // Who owns each row, for a caller who asked for it and may see other users' documents (issues #562,
        // #591): the name and email the User column shows. Attached only when `includeUsers` is set AND the
        // caller may see past their own rows -- an ordinary caller's rows are all their own, so there is nobody
        // else to name, and a caller that will not draw the column pays for no lookup. Resolved in one scoped
        // bulk read over the page's distinct owners, confined to the caller's scope exactly as `resolveTargetUser`
        // confines the `user` parameter above: a row's stamped org can outlive its owner's move to another, and
        // the owner is then not this caller's to see even though the row is.
        val includeUsers = request.getOptBool(EI.includeUsers) == true
        val owners = if (includeUsers && AdminRules.canManageUsers(c)) ownersOf(c, page.rows, callerScope) else emptyMap()
        ListPage(
            page.rows.map { row ->
                // Compute each entry's g-derived data values on read (issue #712) before the wire map and the
                // display columns, exactly as the single read does -- the raw read-only form view is built from a
                // listing row, so a derived value (an expense report's total) must ride here too, not only on the
                // single GET.
                val derived = withDerivedEntries(c, row)
                derived.toJsonMap() + (GDF.displayValues to computeDisplayValues(c, derived, usages)) + ownerFields(owners[row.userId]) +
                    if (withStates) mapOf(GDF.states to statesByGedra[row.gedraId.fullId].orEmpty()) else emptyMap()
            },
            page.numAvailable,
            hasMore = offset + page.rows.size < page.numAvailable,
        )
    }

    // --- field-value suggestions (issue #581) --------------------------------------------------------------

    // One suggested value for a text filter box: a `{value}` wrapper, so the listing envelope can report how
    // many distinct values matched (numAvailable) beside the capped page the box shows.
    type(GEP.fieldValueType) {
        type = SCT.kObject
        description = "One distinct value a text trait takes across the caller's documents (issue #581)."
        property(UF.value, "The distinct display value.", required = true)
    }

    listEndpoint(
        GEP.formDocValues,
        "Distinct values a text trait takes across the caller's own form documents, for a filter box's " +
            "type-ahead. Case-insensitive, capped, and confined to what the caller may see.",
        outputRef = GEP.fieldValueType,
        hasNumAvailable = true,
        hasMore = true,
        inputFields = {
            field(GE.traitId, "The text trait whose values to suggest -- a search field the client declared.", required = true) {
                emptyIsAbsent = true
            }
            field(EI.q, "Only values containing this fragment (case-insensitive). Absent lists the first values.") {
                emptyIsAbsent = true
            }
        },
        publicApi = true,
        needsClientConfig = true,
    ) { c, request ->
        // Floored at 0 like the user search: the auto-appended `limit` is deliberately unbounded, so a
        // negative one would otherwise reach `List.take` and throw a 500 rather than the harmless empty page
        // a nonsensical `?limit=-1` should get.
        val limit = ((request[EP.limit] as? Number)?.toInt() ?: defaultListLimit).coerceAtLeast(0)
        val traitId = (request[GE.traitId] as? String)?.trim()?.ifEmpty { null }
            ?: throw KdrException.mkInput("A ${GE.traitId} is required.")
        val term = (request[EI.q] as? String)?.trim()?.ifEmpty { null }
        // The trait must be one the client declared a *text* usage for -- the same set the search boxes are
        // built from. A number or date, or an unknown trait, has no value list to suggest and is a 400 rather
        // than an empty page, so a caller learns the parameter was wrong rather than that nothing matched.
        val usage = SchemaService.get(c).traitUsagesFor(c.client).firstOrNull { it.traitId == traitId && it.kind == UsageKind.string }
            ?: throw KdrException.mkInput("'$traitId' is not a text search field of this client.")
        // Scan the caller's scope up to a cap, compute this one trait's display value per row, and keep the
        // distinct non-blank ones. The cap bounds the work: a suggestion list is a convenience, not a report,
        // so scanning the most-recent [fieldValueScanCap] documents is enough to surface the common values.
        val scope = ReadScopeRules.forCaller(c)
        val rows = GedraDataService.get(c).listGedras(c, formDoc, scope, fieldValueScanCap).rows
        val distinct = LinkedHashMap<String, String>()
        for (row in rows) {
            // Over the derived entries (issue #712 review), so a value suggestion for a derived-field column
            // offers the values the column shows rather than none.
            val value = computeDisplayValues(c, withDerivedEntries(c, row), listOf(usage)).first()[UF.value].toOptStr()?.trim().orEmpty()
            if (value.isEmpty()) continue
            if (term != null && !value.contains(term, ignoreCase = true)) continue
            distinct.putIfAbsent(value.lowercase(), value)
        }
        val sorted = distinct.values.sortedBy { it.lowercase() }
        ListPage(
            sorted.take(limit).map { mapOf(UF.value to it) },
            numAvailable = sorted.size,
            hasMore = sorted.size > limit,
        )
    }

    // --- the import (issue #545) --------------------------------------------------------------------------

    // What an import did: the documents it created and, per (category, trait), what it threw away.
    type(GEP.importResultType) {
        type = SCT.kObject
        description = "The outcome of an import: the documents created and the entries thrown away."
        property(GIF.imported, "The documents created, each with its assigned id and excluded traits.", required = true) {
            type = SCT.array
            items {
                type = SCT.kObject
                property(GDF.gedraId, "The gedra id assigned to the created document.", required = true)
                property(GIF.excludedTraits, "Trait ids excluded from this document.", required = true) {
                    type = SCT.array
                    items { type = SCT.string }
                }
            }
        }
        property(GIF.discarded, "What was thrown away, one row per category and trait, with a count.", required = true) {
            type = SCT.array
            items {
                type = SCT.kObject
                property(GIF.category, "The discard category ('${GIF.unknownTrait}' or '${GIF.invalidEntry}').", required = true)
                property(GE.traitId, "The trait the discarded entries named (blank when they named none).", required = true)
                property(GIF.count, "How many entries were thrown away for this category and trait.", required = true) {
                    type = SCT.integer
                }
            }
        }
    }

    generalEndpoint(
        GEP.formDocImport,
        "Imports form documents (as a search returns them) for a user, forgiving faults per the flags (issue #545).",
        HttpMethod.POST,
        outputRef = GEP.importResultType,
        inputFields = {
            // The target user -- a userId or an email. Shown only to a caller who can act for others; an
            // ordinary caller imports for themselves, and the handler confines a supplied ref to their scope.
            field(EI.user, "The user to import for -- a userId or an email. Defaults to you.") {
                emptyIsAbsent = true
                visibleWhen = CFACTS.hasAdminLevel
            }
            // The copied data, schema-less on purpose: a single form document, or a `{items: [...]}` wrapper.
            field(GIF.data, "The copied data to import: one form document, or an object with an 'items' array.", required = true) {
                type = SCT.kObject
            }
            field(GIF.forgiveUnknownTraits, "Throw away an entry whose trait the target client does not support.") {
                type = SCT.boolean
                emptyIsAbsent = true
                default = true
            }
            field(GIF.forgiveInvalidEntries, "Throw away an entry that fails validation instead of rejecting the import.") {
                type = SCT.boolean
                emptyIsAbsent = true
                default = false
            }
            // Preserving entry ids risks cross-user id collision, so it is offered and allowed only from an
            // env-authed channel -- gated in the schema and enforced by the handler regardless.
            field(GIF.preserveEntryIds, "Keep the incoming entry ids instead of minting fresh ones (env auth only).") {
                type = SCT.boolean
                emptyIsAbsent = true
                default = false
                visibleWhen = CFACTS.hasEnvAuth
            }
        },
        publicApi = true,
        needsClientConfig = true,
    ) { c, request ->
        val callerScope = ReadScopeRules.forCaller(c)
        // The target owner: the named user (resolved within the caller's scope, so an ordinary caller reaches
        // only themselves -- see [resolveTargetUser]), or the caller when no user is named. The target's client
        // is the one whose traits the import validates against.
        val targetRow = resolveTargetUser(c, request, callerScope)
        val target = if (targetRow == null) {
            Triple(c.userProfile.client, c.userProfile.userId, c.userProfile.org)
        } else {
            Triple(targetRow.client, targetRow.userId, targetRow.org)
        }
        val preserve = request.getOptBool(GIF.preserveEntryIds) == true
        // The schema hides the toggle from a non-env-authed caller; the gate is not a defense, so enforce it.
        if (preserve && !c.isEnvAuthEffective) {
            throw KdrException.mkInput("Preserving entry ids requires env auth.")
        }
        // Bind a sub context to the target as the owner (client/userId/org), keeping the caller as the actor
        // stamped into createdBy.
        val sub = c.mkSubContext("formDocImport", target.first)
        sub.userId = target.second
        sub.org = target.third
        // Normalize the loose data: a `{items: [...]}` wrapper, else the map itself as one document.
        val data = request[GIF.data].toJsonMapOrEmpty()
        val docs = (data[EP.items] as? List<*>)?.toJsonListOfMaps() ?: listOf(data)
        val opts = GedraImportOptions(
            forgiveUnknownTraits = request.getOptBool(GIF.forgiveUnknownTraits) != false,
            forgiveInvalidEntries = request.getOptBool(GIF.forgiveInvalidEntries) == true,
            preserveEntryIds = preserve,
        )
        GedraDataService.get(c).importGedras(sub, formDoc, docs, opts).toJsonMap()
    }
    // --- the patch (issue #337) ---------------------------------------------------------------------------

    // One target: a gedra, and the edits asked of it. Its `edits` are the manufactured edit union for this
    // kind, so a trait that cannot be carried by a form document is refused at the path where it was written
    // rather than by service code that has to remember.
    type(GEP.patchTarget) {
        type = SCT.kObject
        description = "One gedra a patch touches, and everything it asks of that gedra."
        property(GDF.gedraId, "Id of the gedra to change.", required = true)
        property(GPF.edits, "What to do with this gedra's entries.", required = true) {
            type = SCT.array
            items { ref("${GCFG.globalNamespace}.${GU.editUnionName(formDoc)}") }
        }
    }

    // Targets are grouped by kind, and the kind is therefore stated twice -- once here and once inside every
    // id. That redundancy is the price of typing: a schema cannot read a prefix out of an id string to choose
    // a branch, so for `edits` to be typed per kind at all the kind has to be a token the schema can see. The
    // service refuses a row whose id disagrees with its group.
    //
    // Only `formDoc` today, because it is the only kind with an edit union -- which is the only kind anything
    // can store. A kind appears here when it appears in the union assembly, which is the right amount of
    // friction for a decision about what may exist.
    type(GEP.patchTargets) {
        type = SCT.kObject
        description = "The gedras a patch touches, grouped by kind."
        property(formDoc.name, "Form documents to change.") {
            type = SCT.array
            items { ref(GEP.patchTarget) }
        }
    }

    type(GEP.patchedGedra) {
        type = SCT.kObject
        description = "What a patch did to one gedra."
        property(GDF.gedraId, "Id of the gedra that was patched.", required = true)
        property(GPF.outcomes, "What became of each edit, named by the trait it addressed.", required = true) {
            type = SCT.array
            items {
                type = SCT.kObject
                property(GE.traitId, "The trait the edit addressed.", required = true)
                property(GPF.applied, "Whether the edit changed anything.", required = true) {
                    type = SCT.boolean
                }
            }
        }
    }

    // A list endpoint because the answer is one result per target, and a POST because a patch is neither a
    // read nor an HTTP PATCH -- it targets an arbitrary set of rows rather than the resource at the URI, and
    // the PATCH method advertises body formats (RFC 6902's op arrays, RFC 7386's merge-patch where `null`
    // deletes) that this design specifically does not use. See `gedra-patch.md`.
    listEndpoint(
        GEP.patch,
        "Changes entries on one or more gedras, and answers with what became of each edit.",
        outputRef = GEP.patchedGedra,
        method = HttpMethod.POST,
        // Nothing to truncate: the answer is one result per target supplied.
        noLimit = true,
        inputFields = {
            field(GPF.targets, "The gedras to change, grouped by kind.", required = true) {
                ref(GEP.patchTargets)
            }
            field(GDF.allowAdditionalTraits, GedraDataRow.additionalTraitsHint) { type = SCT.boolean }
        },
        publicApi = true,
        needsClientConfig = true,
    ) { c, request ->
        val gedraService = GedraService.get(c)
        val byKind = LinkedHashMap<GedraDataType, List<GedraPatchTarget>>()
        for ((kindName, raw) in request[GPF.targets].toJsonMapOrEmpty()) {
            // A property the schema does not declare cannot arrive, so an unknown name here would mean the
            // type and this loop had drifted -- worth a fault rather than a silent skip.
            val kind = GedraDataType.entries.firstOrNull { it.name == kindName }
                ?: throw KdrException.mkInput("'$kindName' is not a kind of gedra a patch can target.")
            byKind[kind] = raw.toJsonListOfMaps().map { GedraPatchTarget.extract(gedraService, it) }
        }
        if (byKind.values.all { it.isEmpty() }) {
            throw KdrException.mkInput("A patch has to name at least one gedra to change.")
        }
        GedraDataService.get(c)
            .patchGedras(
                c, byKind, ReadScopeRules.forCaller(c),
                request.getOptBool(GDF.allowAdditionalTraits) == true,
            )
            .map { it.toJsonMap() }
    }

    // --- the resolved workflow view (issue #534) ----------------------------------------------------------

    // Open by design: a resolved view is a render blob like a UiBlock, not a fixed contract, so the type
    // vouches only for `found` and lets the rest through. `validateResponseSchema` (on in tests) then checks
    // the one field that is a promise rather than rejecting the render shape.
    type(GEP.workflowViewType) {
        type = SCT.kObject
        description = "A resolved workflow, shaped for a page to render; open like a UiBlock."
        property(WVF.found, "Whether a workflow was resolved for this caller.", required = true) { type = SCT.boolean }
        additionalProperties = true
    }

    // Answers with the caller's client's workflow resolved for rendering. With no `workflowId`, the client's
    // **creation** workflow -- which is how a create page asks "does this client have one, and what does it
    // collect?" in a single call. `cxt.client` is the caller's own on the shared path and the path's on a
    // per-client copy, so the gedra-section copy machinery gives each client `/gedra/<client>/workflow/view`
    // without this naming one.
    generalEndpoint(
        GEP.workflowView,
        "Resolves a workflow for rendering: its tasks, each trait's schema ref, and resolved labels. With no " +
            "workflowId, the caller's client's creation workflow.",
        HttpMethod.GET,
        outputRef = GEP.workflowViewType,
        inputFields = {
            field(GDF.workflowId, "The workflow to resolve; omit for the caller's creation workflow, or with a gedraId its survey.")
            field(GDF.gedraId, "An existing form to resolve against, seeding each field with its data; with no workflowId this resolves the client's survey.")
        },
        publicApi = true,
        needsClientConfig = true,
    ) { c, request ->
        val registry = WorkflowService.get(c).forClient(c.client)
        val requested = request[GDF.workflowId].toOptStr()
        val gedraId = request[GDF.gedraId].toOptStr()
        // A workflowId names one directly; otherwise the singleton kind is deduced from whether a form was
        // named -- a form means "edit its survey", none means "create". Both singletons resolve by kind, so a
        // caller never needs to know the id up front (issue #658).
        val declared = when {
            requested != null -> registry.workflow(requested)
                ?: throw KdrException("No workflow '$requested' for this caller.", code = EXC.notFound)
            gedraId != null -> registry.survey
            else -> registry.creation
        }
        if (declared == null) {
            noWorkflowView()
        } else {
            // A named form seeds each task from its current entries -- which also makes completeness real.
            val row = if (gedraId == null) null else surveyFormRow(c, gedraId)
            val entriesByTask = row?.let { entriesByTaskOf(declared, it) } ?: emptyMap()
            // The owner a prefillData function defaults from: the form's user for a survey, the caller for a
            // creation view. Read only when the workflow actually declares a prefill (issue #679).
            val ownerAttributes = prefillOwnerAttributes(c, declared, row?.userId ?: c.userId)
            resolveWorkflowView(c, declared, entriesByTask, ownerAttributes)
        }
    }

    // --- the workflow save (issue #535) -------------------------------------------------------------------

    type(GEP.workflowSaveType) {
        type = SCT.kObject
        description = "The outcome of a workflow save: either a refusal naming what is missing, or the created gedra."
        property(WSF.saved, "Whether the save happened; false means a required trait was missing.", required = true) {
            type = SCT.boolean
        }
        property(WSF.unmetTraits, "When not saved: the required trait ids no entry satisfied.") {
            type = SCT.array
            items { type = SCT.string }
        }
        property(WSF.item, "When saved: the created form document, or the updated one for a survey edit.") { ref(docType) }
        property(
            WSF.view,
            "When saved by a survey edit: the refreshed workflow view -- each task's status and the earliest task " +
                "needing action -- so the save is the refresh (issue #700).",
        ) { ref(GEP.workflowViewType) }
    }

    // Saves the entries a workflow task collected, with the workflow's gate (issue #535). A refused save is a
    // **result** -- `saved` false, the unmet required traits named -- not an error, so a page can point at the
    // fields to finish; a mistake (unknown task/save, a trait the task does not collect) is a loud 400.
    generalEndpoint(
        GEP.workflowSave,
        "Saves a workflow task's entries. A `create` save creates the form (or, if incomplete, answers with the " +
            "unmet required traits -- not an error); a survey `edit` save updates the form named by gedraId and " +
            "recomputes its survey state.",
        HttpMethod.POST,
        outputRef = GEP.workflowSaveType,
        inputFields = {
            field(GDF.workflowId, "The workflow being saved.", required = true)
            field(GDF.taskId, "The task whose entries these are.", required = true)
            field(GDF.saveId, "The save option chosen within the task.", required = true)
            field(GDF.gedraId, "The form an edit save updates; omit for a create save, which makes a new form.")
            field(GDF.entries, "The entries the task collected, each an instance of a trait the task declares.", required = true) {
                type = SCT.array
                items { ref("${GCFG.globalNamespace}.${GU.unionName(formDoc)}") }
            }
            // The on-behalf create (issue #727): applies to a `create` save only -- an admin makes the new form
            // for the named user; ignored on an edit save, which acts on the form named by gedraId.
            field(EI.user, GedraDataRow.createForUserHint)
        },
        publicApi = true,
        needsClientConfig = true,
    ) { c, request ->
        val workflowId = request[GDF.workflowId].toOptStr()
            ?: throw KdrException.mkInput("A ${GDF.workflowId} is required.")
        val declared = WorkflowService.get(c).forClient(c.client).workflow(workflowId)
            ?: throw KdrException("No workflow '$workflowId' for this caller.", code = EXC.notFound)
        val taskId = request[GDF.taskId].toOptStr() ?: throw KdrException.mkInput("A ${GDF.taskId} is required.")
        val saveId = request[GDF.saveId].toOptStr() ?: throw KdrException.mkInput("A ${GDF.saveId} is required.")
        val gedraId = request[GDF.gedraId].toOptStr()
        // A `create` save (no gedraId) may be for another user (issue #727); an `edit` acts on the named form as
        // the caller, so `user` does not apply there and the caller's own context is used.
        val saveCxt = if (gedraId == null) createForUserCxt(c, request) else c
        val result = saveWorkflow(saveCxt, declared, taskId, saveId, request[GDF.entries].toJsonListOfMaps(), gedraId)
        // A survey edit answers with the refreshed view too (issue #700): re-resolved against the updated form,
        // so the task rail's per-task statuses and its earliest-actionable task follow the save without a second
        // call -- the save is the refresh. A create save has no form to resolve a survey against, so it answers as
        // before. The same helpers the view endpoint uses, so the two cannot drift.
        if (gedraId != null && result[WSF.saved] == true) {
            // From the row the save already read back and returned as `item` -- its entries and owner are all the
            // resolver needs -- rather than reading it a second time; the client confinement the view endpoint's
            // own read re-checks is already guaranteed here by the patch path.
            val item = result[WSF.item].toJsonMapOrEmpty()
            val entriesByTask = entriesByTaskOf(declared, item[GDF.entries].toJsonListOfMaps())
            val owner = prefillOwnerAttributes(c, declared, item[GDF.userId].toOptLong() ?: c.userId)
            result + (WSF.view to resolveWorkflowView(c, declared, entriesByTask, owner))
        } else {
            result
        }
    }
}

/**
 * The context a create runs under when it may be **for another user** (issue #727): the caller's own [c] when
 * the request names no `user`, or names the caller themselves; otherwise a sub-context bound to the named user
 * as owner (client / user / org), with [c]'s actor left in place so `createdBy` stays the caller -- the same
 * owner/actor split (#325) `adminFormDocForUser` makes.
 *
 * Creating for someone else is an **admin** power, and confined to the caller's own scope: the user is resolved
 * through [ReadScopeRules.forCaller], so a client administrator reaches only their own client (narrowed to their
 * organization if they have one) and a cross-client or out-of-org id simply is not found. An ordinary user's
 * scope resolves only themselves, so they cannot name another. A disabled or deleted target is refused, as it
 * is on the `allClients` on-behalf endpoint -- such an account cannot log in to see or finish the form.
 *
 * Shared by the plain create and the workflow create so the two enforce it identically; the section gate
 * (login) is unchanged, since this is a scope-and-privilege check within an endpoint an ordinary user may call
 * for their own forms.
 */
private fun createForUserCxt(c: KdrCxt, request: Map<String, Any?>): KdrCxt {
    val ref = request[EI.user].toOptStr()?.trim()?.ifEmpty { null } ?: return c
    // Fresh roles before an escalation check, as the home config's admin flags are read (a grant or revoke
    // bites immediately rather than at cookie expiry).
    refreshActingRoles(c)
    val scope = ReadScopeRules.forCaller(c)
    val target = UserService.get(c).resolveUserRef(c, ref, scope)
        ?: throw KdrException("No user matching '$ref'.", code = EXC.notFound)
    // Naming yourself is the ordinary self-create -- no escalation, no rebind.
    if (target.userId == c.userProfile.userId) return c
    // Creating for another user is an administrator's power (client-scoped is enough; the scope above already
    // confined *which* user). Checked after resolving so naming yourself never trips it.
    if (!AdminRules.canManageUsers(c)) {
        throw KdrException("Creating a form for another user requires an administrator.", code = EXC.notAuthorized)
    }
    // Same client only (issue #727 review): these are the ordinary, client-scoped create surfaces -- the form is
    // built in and owned by `c.client`, so a target in another client would create it cross-client, stamped with
    // this client's workflow lineage and gated on its traits. A client admin's scope already excludes another
    // client's user (resolved above -> 404); this also stops an `allClients` admin, whose scope is unrestricted,
    // from reaching cross-client here. Cross-client creation has its own surface (`/admin/formDocForUser`).
    if (target.client != c.client) {
        throw KdrException.mkInput(
            "The user '$ref' is in client '${target.client}', not '${c.client}'. Use the cross-client admin " +
                "surface to create a form for a user in another client.",
        )
    }
    if (target.isDeleted || !target.enabled) {
        throw KdrException.mkInput(
            "The user '$ref' is ${if (target.isDeleted) "deleted" else "disabled"}; a form cannot be created for them.",
        )
    }
    return c.mkSubContext("createForUser", target.client).also {
        it.userId = target.userId
        it.org = target.org
    }
}

/**
 * The survey's form, read in the caller's own scope so a form out of reach answers 404 (issue #658), and
 * confined to the client whose survey was resolved (`cxt.client`): `registry.survey` is that client's, and an
 * `allClients` caller could otherwise name a form in another client that scope alone would admit -- seeding one
 * client's survey with another's data. The edit save is already guarded this way (checkPathClient). The resolver
 * itself reads no gedra, so the one read a survey view needs lives here in the handler layer.
 */
private fun surveyFormRow(cxt: KdrCxt, fullId: String): GedraDataRow {
    val row = GedraDataService.get(cxt).queryGedra(cxt, fullId, GedraDataType.formDoc, ReadScopeRules.forCaller(cxt))
        ?: throw KdrException("No form '$fullId' for this caller.", code = EXC.notFound)
    if (row.client != cxt.client) {
        throw KdrException.mkInput(
            "Form '$fullId' belongs to client '${row.client}', and this survey view is for '${cxt.client}'. " +
                "Use that client's own survey view.",
        )
    }
    return row
}

/** The form's current entries split per task (issue #658): each task's are the entries whose trait it collects. */
private fun entriesByTaskOf(declared: WfDeclared, row: GedraDataRow): Map<String, List<Map<String, Any?>>> =
    entriesByTaskOf(declared, row.entries)

/** The same split over entries already in hand as wire maps -- a save's returned item (issue #700). */
private fun entriesByTaskOf(declared: WfDeclared, entries: List<Map<String, Any?>>): Map<String, List<Map<String, Any?>>> =
    declared.def.tasks.associate { task ->
        val traitIds = task.traits.map { it.traitId }.toSet()
        task.id to entries.filter { it[GE.traitId].toOptStr() in traitIds }
    }

/**
 * The form owner's attributes a `prefillData` function may default a field from (issue #679) -- empty when the
 * workflow declares no prefill (so an ordinary view pays no user read) or the owner cannot be read here. The
 * owner is the form's user for a survey, the caller for a creation view; the attribute keys are [PFO]'s, the
 * same ones `prefillFromOwner` names.
 */
private fun prefillOwnerAttributes(cxt: KdrCxt, declared: WfDeclared, ownerUserId: Long): Map<String, Any?> {
    val wantsPrefill = declared.def.tasks.any { t -> t.resolvedFunctions.any { it.event == WfEventType.prefillData } }
    if (!wantsPrefill || ownerUserId <= 0L) {
        return emptyMap()
    }
    val owner = UserService.get(cxt)
        .queryUsersByIds(cxt, listOf(ownerUserId), ReadScopeRules.forCaller(cxt))[ownerUserId]
        ?: return emptyMap()
    return mapOf(PFO.publicName to owner.publicName(), PFO.name to owner.name, PFO.email to owner.primaryId)
}

/**
 * Resolves the `user` parameter (issue #545) that the search and import handlers share: the caller-supplied
 * userId-or-email confined to [callerScope], or null when none was named. A ref that resolves to nobody *within
 * that scope* is a 400 whose wording does not say whether the user is absent or merely out of reach -- the one
 * place the confinement rule and its message live, so the two surfaces cannot drift. An ordinary caller's scope
 * is their own user, so they can only ever name themselves; an administrator reaches their client (or all).
 */
private fun resolveTargetUser(c: KdrCxt, request: Map<String, Any?>, callerScope: ReadScope): AuthUserRow? {
    val userRef = (request[EI.user] as? String)?.trim()?.ifEmpty { null } ?: return null
    return UserService.get(c).resolveUserRef(c, userRef, callerScope)
        ?: throw KdrException.mkInput("No user matching '$userRef' is within your access.")
}

/**
 * The forms-list search predicate (issue #538), or null when the caller filled no search field. Built from the
 * client's [usages]: each contributes parameters ([gedraSearchParams]), and a filled one becomes a condition on
 * the row's **display value** for that trait -- the same value the listing shows in its column, so a person
 * searches what they see. A row satisfies the filter only when it satisfies every filled parameter.
 *
 * A number/date parameter arrives coerced (a `Number`); it is compared as text against the row's display value,
 * so both sides read through the same parse. Applied by [GedraDataService.listGedras] before paging, over the
 * cache's client+kind index (the SQL fallback filters its query's rows), with the stated in-memory ceiling.
 */
private fun searchFilter(
    c: KdrCxt,
    request: Map<String, Any?>,
    usages: List<ClientTraitUsage>,
): ((GedraDataRow) -> Boolean)? {
    val active = gedraSearchParams(usages).mapNotNull { param ->
        request[param.name]?.let { it.fmt().ifBlank { null } }?.let { param to it }
    }
    // The free-text term (issue #562), searched across every text field; blank is no term.
    val term = request[EI.q]?.fmt()?.ifBlank { null }
    if (active.isEmpty() && term == null) {
        return null
    }
    val textTraits = textSearchTraitIds(usages)
    return { row ->
        // Over the derived entries (issue #712 review), so a search on a derived-field column matches the value
        // the column shows rather than the blank the stored data would yield.
        val byTrait = computeDisplayValues(c, withDerivedEntries(c, row), usages).associate { display ->
            (display[UF.traitId].toOptStr() ?: "") to (display[UF.value].toOptStr() ?: "")
        }
        matchesSearch(byTrait, active) && (term == null || matchesAnyText(byTrait, textTraits, term))
    }
}

/**
 * The owning users of [rows] that [scope] admits, keyed by user id, for the User column a scope-wide caller sees
 * (issue #562). One bulk read ([UserService.queryUsersByIds]): the cache answers the owners it holds, the rest
 * share a single session, and every row is checked against [scope]. An owner outside the scope, or one the
 * store no longer has, is simply absent -- their rows show no owner rather than faulting the page or naming
 * somebody the caller may not see.
 */
private fun ownersOf(c: KdrCxt, rows: List<GedraDataRow>, scope: ReadScope): Map<Long, AuthUserRow> =
    UserService.get(c).queryUsersByIds(c, rows.map { it.userId }, scope)

/** The name the User column shows for [owner] (issue #666): the account's own name, else its public name. */
private fun ownerSortName(owner: AuthUserRow): String = owner.name?.trim()?.ifEmpty { null } ?: owner.publicName()

/**
 * The sort a listing request asks for (issue #666), or null for the default order. `updated` / `created` order
 * by the row's protocol dates; a display column arrives namespaced (`display_<traitId>`, [GSORT.displayTraitId])
 * and orders by that trait's value under its declared [UsageKind] -- the namespacing is what keeps a trait
 * named like a fixed column from being read as one. An unknown column returns null (the default order) rather
 * than faulting a stale bookmark. The direction is `desc` when [GSORT.sortDir] says so, ascending otherwise; the
 * trait's value is read the way the column shows it ([computeDisplayValues] for just that usage).
 */
private fun gedraSortFor(
    cxt: KdrCxt,
    request: Map<String, Any?>,
    usages: List<ClientTraitUsage>,
    scope: ReadScope,
): GedraDataService.GedraSort? {
    val column = request[GSORT.sort].toOptStr()?.ifBlank { null } ?: return null
    val descending = request[GSORT.sortDir].toOptStr()?.equals(GSORT.desc, ignoreCase = true) == true
    return when (column) {
        GSORT.updated -> GedraDataService.GedraSort(UsageKind.date, descending) { it.updatedAt?.toString() ?: "" }
        GSORT.created -> GedraDataService.GedraSort(UsageKind.date, descending) { it.createdAt?.toString() ?: "" }
        // The "Contains" summary orders by the row's traits as text (issue #666). Its display shows friendly
        // labels, but those are computed on the frontend (humanizeFieldName is not in the kernel); the trait ids
        // sort in the same relative order for the ordinary case where a label is just the humanized id.
        GSORT.contains -> GedraDataService.GedraSort(UsageKind.string, descending) { row ->
            row.entries.mapNotNull { it[GE.traitId].toOptStr() }.joinToString(", ")
        }
        // The owner (the User column, issue #666): admin-only, as the column is -- an ordinary caller's rows are
        // all their own. Ordered by the name the column shows (the name, else the email). Resolved in the `prepare`
        // hook by one batch read over the whole matched set ([ownersOf]) -- the cache answers what it holds and the
        // misses share a single session, where a per-row lookup would open one session each on a cache-absent
        // node. A user beyond the caller's scope is absent from the map, so `keyOf` reads blank, which sorts last.
        GSORT.owner -> {
            if (!AdminRules.canManageUsers(cxt)) {
                null
            } else {
                val names = HashMap<Long, String>()
                GedraDataService.GedraSort(
                    UsageKind.string,
                    descending,
                    prepare = { rows -> ownersOf(cxt, rows, scope).forEach { (id, owner) -> names[id] = ownerSortName(owner) } },
                ) { row -> names[row.userId] ?: "" }
            }
        }
        // The client (the Client column, issue #668): `allClients`-only, as the column is -- a caller who does not
        // see across clients has only their own client's rows, so there is nothing to order by (null = default
        // order). The client is a protocol column on the row, so it needs no per-row resolution.
        GSORT.client ->
            if (!AdminRules.canSeeAllClients(cxt)) null
            else GedraDataService.GedraSort(UsageKind.string, descending) { it.client }
        else -> {
            val traitId = GSORT.displayTraitId(column) ?: return null
            val usage = usages.firstOrNull { it.traitId == traitId } ?: return null
            GedraDataService.GedraSort(usage.kind, descending) { row ->
                // Over the derived entries (issue #712 review), so a sort by a derived-field column orders on the
                // value the column shows, not the blank the stored data holds.
                computeDisplayValues(cxt, withDerivedEntries(cxt, row), listOf(usage)).firstOrNull()?.get(UF.value).toOptStr() ?: ""
            }
        }
    }
}

/**
 * The owner block attached to a listed row (issue #580, flat keys in #562): a `{name?, email}` map under
 * [GDF.owner]. The email always, and a display name only when the account has one that is not the email --
 * `name`, else a chosen username (the `UserProfile.displayName` rule). A provisioned account with neither would
 * otherwise repeat its address as its name, and the column's rule is "the email, or the name with the email
 * beneath": sending the name only when it adds something lets the frontend render exactly that without comparing
 * the two strings. Empty for no [owner], so the map addition is a no-op rather than an empty block.
 */
private fun ownerFields(owner: AuthUserRow?): Map<String, Any?> {
    if (owner == null) return emptyMap()
    val email = owner.primaryId
    val name = ownerSortName(owner)
    val block = if (name == email) mapOf(DUF.email to email) else mapOf(DUF.name to name, DUF.email to email)
    return mapOf(GDF.owner to block)
}

/**
 * The global admin state surface (issue #600): read one gedra's state, and replace it wholesale. On `/admin/…`
 * rather than `/gedra/…` on purpose -- state is global, so the section gate wanted is the deployment-wide one
 * (`admin` + `allClients`), not the client-scoped `gedra` gate. A separate module, registered once (not
 * per-client-copied, which the `gedra` section is), and never `publicApi`: this is an internal admin tool, not
 * part of the client-facing API.
 *
 * The edit is a **full replace** (issue #600): `writeState` is a whole-set upsert, so a caller reads the state,
 * edits it, and posts the complete set back. A finer per-entry state patch (the state counterpart of
 * `patchGedras`) is left for when a workflow or console needs it.
 */
/**
 * Resolves the gedra an admin state request names (issue #600): its `gedraId` param to the stored row, faulting
 * a malformed id (400), a non-data (config) id (400), and a gedra that does not exist (404) -- so a state read
 * or write acts on a real gedra, an empty state read means "no state" rather than "no gedra", and a write finds
 * the owner its state row must scope to. The section gate confines the caller to `allClients`, so the scope is
 * unrestricted and a 404 means genuinely absent rather than out of reach.
 */
private fun adminStateGedra(c: KdrCxt, request: Map<String, Any?>): GedraDataRow {
    val fullId = request[GDF.gedraId].toOptStr() ?: throw KdrException.mkInput("A ${GDF.gedraId} is required.")
    val kind = GedraService.get(c).readId(fullId).dataType
        ?: throw KdrException.mkInput("'$fullId' is not a data gedra, so it carries no state.")
    return GedraDataService.get(c).queryGedra(c, fullId, kind, ReadScopeRules.forCaller(c))
        ?: throw KdrException("No gedra '$fullId'.", code = EXC.notFound)
}

fun gedraStateAdminSchema(cxt: KdrCxt): SchModule = schemaModule(cxt, "adminGedra") {
    val stateRef = "${GCFG.globalNamespace}.${GU.stateUnionName}"
    type(GEP.gedraStateDoc) {
        type = SCT.kObject
        description = "One gedra's state: its id and its state entries."
        property(GDF.gedraId, "The gedra whose state this is.", required = true) { derived = true }
        property(GDF.states, "The gedra's state entries.", required = true) {
            type = SCT.array
            items { ref(stateRef) }
            derived = true
        }
    }

    itemEndpoint(
        GEP.adminGedraState,
        "Reads one gedra's state entries (issue #600).",
        HttpMethod.GET,
        outputRef = GEP.gedraStateDoc,
        inputFields = { field(GDF.gedraId, "Id of the gedra whose state to read.", required = true) },
    ) { c, request ->
        // Resolve (and 404) the gedra first, so an empty result means "no state" and a missing gedra is a real
        // 404 -- an item response could not carry the not-found otherwise. Then read its state (admin scope is
        // unrestricted, so it reads whoever owns the gedra).
        val row = adminStateGedra(c, request)
        mapOf(GDF.gedraId to row.gedraId.fullId, GDF.states to GedraDataService.get(c).readState(c, row.gedraId, ReadScopeRules.forCaller(c)))
    }

    itemEndpoint(
        GEP.adminGedraState,
        "Replaces one gedra's state entries with the supplied set (issue #600); a whole-set upsert.",
        HttpMethod.POST,
        outputRef = GEP.gedraStateDoc,
        inputFields = {
            field(GDF.gedraId, "Id of the gedra whose state to replace.", required = true)
            field(GDF.states, "The complete set of state entries to store; it replaces what is there.", required = true) {
                type = SCT.array
                items { type = SCT.kObject }
            }
        },
    ) { c, request ->
        val row = adminStateGedra(c, request)
        val entries = request[GDF.states].toJsonListOfMaps()
        // A state row scopes to the gedra's owner, not the admin, so writeState runs on a context bound to that
        // owner -- exactly as create and import bind one; the caller stays the actor stamped into the audit.
        val ownerCxt = c.mkSubContext("adminState", row.client)
        ownerCxt.userId = row.userId
        ownerCxt.org = row.org
        mapOf(GDF.gedraId to row.gedraId.fullId, GDF.states to GedraDataService.get(c).writeState(ownerCxt, row.gedraId, entries))
    }

    // Create a form document on behalf of another user (issue #672 Slice 3). On `/admin/…`, so the section gate
    // is the deployment-wide one (`admin` + `allClients`) -- an ordinary or client-scoped admin has no business
    // creating another user's documents. The document is owned by the named user in *their* client, and the
    // caller is the actor: the same owner/actor split (issue #325) `createGedra` already makes, bound here the way
    // the admin-state write above binds it. `needsClientConfig` because the entries validate against the target
    // user's client's schema, which must be current on this node (issue #618); the sync is node-global, so the
    // target client's config is brought current even though it is not the caller's own.
    val onBehalfKind = GedraDataType.formDoc
    // The gedra-namespace form-document type, qualified because this module's namespace is `adminGedra` (the
    // state doc above qualifies its cross-namespace ref the same way).
    val onBehalfDocType = "${GEP.gedraNamespace}.${GU.gedraName(onBehalfKind)}"
    itemEndpoint(
        GEP.adminFormDocForUser,
        "Creates a form document owned by another user, in that user's client (issue #672). For an `allClients` " +
            "admin; the shared `${GEP.formDocCreate}` is unchanged.",
        HttpMethod.POST,
        outputRef = onBehalfDocType,
        inputFields = {
            field(EI.user, "The user to create the form for -- their numeric id or email. Their client is taken " +
                "from them, and the form is validated against and owned in that client.", required = true)
            // Loose entries, as the admin-state write declares its states (open objects passed through): the
            // real per-trait check is `createGedra`'s, run against the target user's client below, since this
            // one endpoint serves every client and its published union could not be one client's.
            field(GDF.entries, "The entries the form carries, each an instance of a trait the user's client " +
                "supports.", required = true) {
                type = SCT.array
                items { type = SCT.kObject }
            }
            field(GDF.allowAdditionalTraits, GedraDataRow.additionalTraitsHint) { type = SCT.boolean }
        },
        needsClientConfig = true,
    ) { c, request ->
        val ref = request[EI.user].toOptStr()?.trim()?.ifEmpty { null }
            ?: throw KdrException.mkInput("A '${EI.user}' (numeric id or email) is required.")
        // Unrestricted, since the `admin` section already confines the caller to `allClients`: the user may be in
        // any client, and their client is what the form is created in. Absent is a 404, as a retrieve of a
        // missing resource is.
        val target = UserService.get(c).resolveUserRef(c, ref, ReadScope.unrestricted)
            ?: throw KdrException("No user matching '$ref'.", code = EXC.notFound)
        // A disabled account or a deleted tombstone is not a valid owner: `resolveUserRef` returns such rows
        // (ids stay resolvable), but the account cannot log in to see or finish the form, and every
        // administrative user edit already refuses one. Refuse here too rather than mint an unreachable form.
        if (target.isDeleted || !target.enabled) {
            throw KdrException.mkInput("The user '$ref' is ${if (target.isDeleted) "deleted" else "disabled"}; a form cannot be created for them.")
        }
        // An allClients admin creates for other users, not for themselves (issue #672) -- the everyday create
        // surface is for one's own forms.
        if (target.userId == c.userProfile.userId) {
            throw KdrException.mkInput("Use the ordinary form-creation surface to create your own forms; this one is for other users.")
        }
        val entries = request[GDF.entries].toJsonListOfMaps()
        // Bound to the target as owner (client, user, org), the caller left as the actor -- so the id is minted in
        // the user's client, ownership is theirs, and `createdBy` is the admin. The same binding the admin-state
        // write makes above.
        val ownerCxt = c.mkSubContext("createOnBehalf", target.client)
        ownerCxt.userId = target.userId
        ownerCxt.org = target.org
        GedraDataService.get(ownerCxt)
            .createGedra(ownerCxt, onBehalfKind, entries, request.getOptBool(GDF.allowAdditionalTraits) == true)
            .toJsonMap()
    }
}
