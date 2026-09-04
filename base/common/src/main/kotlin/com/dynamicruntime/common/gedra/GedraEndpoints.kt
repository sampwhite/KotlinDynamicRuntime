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
import com.dynamicruntime.common.gedra.workflow.WSF
import com.dynamicruntime.common.gedra.workflow.WVF
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
import com.dynamicruntime.common.util.fmt
import com.dynamicruntime.common.util.getOptBool
import com.dynamicruntime.common.util.toJsonListOfMaps
import com.dynamicruntime.common.util.toJsonMapOrEmpty
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
private fun withDisplayValues(cxt: KdrCxt, row: GedraDataRow): Map<String, Any?> =
    row.toJsonMap() + (GDF.displayValues to computeDisplayValues(cxt, row, SchemaService.get(cxt).traitUsagesFor(cxt.client)))

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
        // The sent shape, not the stored one: they differ by `allowAdditionalTraits`, which is an instruction
        // about this write and has no place in what a document *is* (issue #379).
        inputRef = GU.inputName(formDoc),
        // The form surface a client's own application calls, so it is part of the published API (issue #489);
        // the per-client copies inherit this. Marks are on the five here at once, so the set reads as one
        // decision.
        publicApi = true,
    ) { c, request ->
        val entries = request[GDF.entries].toJsonListOfMaps()
        GedraDataService.get(c)
            .createGedra(c, formDoc, entries, request.getOptBool(GDF.allowAdditionalTraits) == true)
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
        // The free-text term (issue #562): one box that searches every text field at once, so a caller need
        // not know which column holds the value they remember. ANDed with any per-field filters also sent.
        property(EI.q, "Free text matched against every text search field (any field, case-insensitive substring).") {
            emptyIsAbsent = true
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
    ) { c, request ->
        val limit = (request[EP.limit] as? Number)?.toInt() ?: defaultListLimit
        val offset = (request[EP.offset] as? Number)?.toInt() ?: 0
        val callerScope = ReadScopeRules.forCaller(c)
        // A named user narrows the scope to that user -- but only within what the caller may already see (see
        // [resolveTargetUser]); no name is the caller's own scope.
        val target = resolveTargetUser(c, request, callerScope)
        val scope = if (target == null) callerScope else ReadScope.ofUser(target.userId)
        // The client's usage rules, read once: they drive both the display columns (issue #537) and the search
        // parameters (issue #538). A search parameter the caller filled becomes an in-memory predicate applied
        // before paging, so the page and its `numAvailable` are both over the matched set (see `listGedras`).
        val usages = SchemaService.get(c).traitUsagesFor(c.client)
        val filter = searchFilter(c, request, usages)
        val page = GedraDataService.get(c).listGedras(c, formDoc, scope, limit, offset, filter)
        // Who owns each row, for a caller who can see other users' documents (issue #562): the name and email
        // the User column shows. Only such a caller gets it -- an ordinary caller's rows are all their own, so
        // the column is not drawn and nothing is looked up. Resolved in one scoped bulk read over the page's
        // distinct owners, confined to the caller's scope exactly as `resolveTargetUser` confines the `user`
        // parameter above: a row's stamped org can outlive its owner's move to another, and the owner is then
        // not this caller's to see even though the row is.
        val owners = if (AdminRules.canManageUsers(c)) ownersOf(c, page.rows, callerScope) else emptyMap()
        ListPage(
            page.rows.map { row ->
                row.toJsonMap() + (GDF.displayValues to computeDisplayValues(c, row, usages)) + ownerFields(owners[row.userId])
            },
            page.numAvailable,
            hasMore = offset + page.rows.size < page.numAvailable,
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
            field(GDF.workflowId, "The workflow to resolve; omit for the client's creation workflow.")
        },
        publicApi = true,
    ) { c, request ->
        val registry = WorkflowService.get(c).forClient(c.client)
        val requested = request[GDF.workflowId].toOptStr()
        val declared = if (requested != null) {
            registry.workflow(requested)
                ?: throw KdrException("No workflow '$requested' for this caller.", code = EXC.notFound)
        } else {
            registry.creation
        }
        if (declared == null) noWorkflowView() else resolveWorkflowView(c, declared)
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
        property(WSF.item, "When saved: the created form document, as create returns it.") { ref(docType) }
    }

    // Saves the entries a workflow task collected, with the workflow's gate (issue #535). A refused save is a
    // **result** -- `saved` false, the unmet required traits named -- not an error, so a page can point at the
    // fields to finish; a mistake (unknown task/save, a trait the task does not collect) is a loud 400.
    generalEndpoint(
        GEP.workflowSave,
        "Saves a workflow task's entries. On a satisfied `create` save, creates the form and answers with it; " +
            "on an incomplete one, answers with the unmet required traits (not an error).",
        HttpMethod.POST,
        outputRef = GEP.workflowSaveType,
        inputFields = {
            field(GDF.workflowId, "The workflow being saved.", required = true)
            field(GDF.taskId, "The task whose entries these are.", required = true)
            field(GDF.saveId, "The save option chosen within the task.", required = true)
            field(GDF.entries, "The entries the task collected, each an instance of a trait the task declares.", required = true) {
                type = SCT.array
                items { ref("${GCFG.globalNamespace}.${GU.unionName(formDoc)}") }
            }
        },
        publicApi = true,
    ) { c, request ->
        val workflowId = request[GDF.workflowId].toOptStr()
            ?: throw KdrException.mkInput("A ${GDF.workflowId} is required.")
        val declared = WorkflowService.get(c).forClient(c.client).workflow(workflowId)
            ?: throw KdrException("No workflow '$workflowId' for this caller.", code = EXC.notFound)
        val taskId = request[GDF.taskId].toOptStr() ?: throw KdrException.mkInput("A ${GDF.taskId} is required.")
        val saveId = request[GDF.saveId].toOptStr() ?: throw KdrException.mkInput("A ${GDF.saveId} is required.")
        saveWorkflow(c, declared, taskId, saveId, request[GDF.entries].toJsonListOfMaps())
    }
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
        val byTrait = computeDisplayValues(c, row, usages).associate { display ->
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
    val name = owner.name?.trim()?.ifEmpty { null } ?: owner.publicName()
    val block = if (name == email) mapOf(DUF.email to email) else mapOf(DUF.name to name, DUF.email to email)
    return mapOf(GDF.owner to block)
}
