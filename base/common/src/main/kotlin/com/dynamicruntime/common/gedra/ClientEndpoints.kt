package com.dynamicruntime.common.gedra

import com.dynamicruntime.common.context.KdrCxt
import com.dynamicruntime.common.endpoint.HttpMethod
import com.dynamicruntime.common.endpoint.SchModule
import com.dynamicruntime.common.endpoint.schemaModule
import com.dynamicruntime.common.exception.EXC
import com.dynamicruntime.common.exception.KdrException
import com.dynamicruntime.common.gedra.workflow.WorkflowService
import com.dynamicruntime.common.http.request.ROLE
import com.dynamicruntime.common.schema.SCT
import com.dynamicruntime.common.schema.SchOption
import com.dynamicruntime.common.startup.SchemaService
import com.dynamicruntime.common.user.ADEP
import com.dynamicruntime.common.util.toOptStr

/**
 * The administrative view of which clients this deployment carries (issue #343).
 *
 * One endpoint, in the **`admin`** section -- so it takes [ROLE.admin] *and* the [ROLE.allClients] capability,
 * the path prefix being the access control. Both, because the question it answers is a cross-client one:
 * a client-scoped administrator does not get a narrowed version of this listing, they get nothing, since the
 * only client they could be shown is the one they already know they are in.
 *
 * It reports what was **declared and survived**, which is the useful thing rather than the tidy one: a client
 * whose definition was refused is simply not here, and on a production node that refusal is in the startup log
 * beside the reason (see [checkClientDefs]). The listing is not the place to explain an absence, because
 * anything that could explain one would also have to be readable by somebody who should not see the client at
 * all.
 *
 * The module is named `clientCatalog`, not `clientAdmin` (issue #466). This is a *catalog of clients*, served
 * full-scope under the `admin` section; `clientAdmin` now names the opposite thing -- the client-*scoped*
 * administration section -- and one name reading both ways was the ambiguity #466 cleared.
 */
fun clientCatalogSchema(cxt: KdrCxt): SchModule = schemaModule(cxt, CLD.catalogNamespace) {
    ClientDef.defineInfoType(this)

    listEndpoint(
        ADEP.clients,
        "Lists the clients enabled in this environment, as they were declared.",
        outputRef = CLD.infoTypeName,
        // No `limit` field: a deployment's clients are a declared set loaded at boot, small enough that
        // paging it would be pretending it came from somewhere it did not.
        noLimit = true,
    ) { c, _ ->
        namableClients(c).map { it.toInfo() }
    }

    // The choice list behind every attribute marked `clientAttribute()` (issue #413). Declared in the same
    // block as the listing it agrees with, so the two cannot answer differently about who may name what.
    optionsProvider(CLD.clientOptions) { c, _ ->
        namableClients(c).map { SchOption(it.clientId, clientLabel(it.clientId, it.name)) }
    }

    // --- one client's definition + a cross-client summary listing (issue #672) ------------------------------
    //
    // Both are full-scope (the `admin` section takes `allClients`), because they answer a cross-client question:
    // an `allClients` administrator retrieves another client's rules so a formDocs surface can list, filter,
    // edit and create in *that* client's terms. The frontend that consumes these arrives in a later slice
    // (depends on #668); this is the backend they read from.
    //
    // What they do NOT carry is the *resolved* trait schema. A trait's `dataSchema` is a bare `$ref` into the
    // client's `$defs` (an altered trait resolves only in that client's variant store), and the endpoint catalog
    // already resolves a named client's schema and ships the `$defs` bag -- `GET /schema/endpoints?client=X`
    // (`SchemaService.catalogSurface`). So the retrieve reports the *definition* the catalog does not (attributes,
    // supported traits, usage columns, workflow ids) and the form/edit surfaces read the schema from the catalog
    // with `client=`, rather than this endpoint duplicating it (issue #672 review, option b).
    //
    // `testFeatures` never leaks here: the retrieve projects `ClientDef.toInfo()` off the **present** definition,
    // which `ClientService` has already neutralized on a non-test node (issue #696), so the field is simply
    // absent off a test instance whatever the stored row held. No per-endpoint strip is needed.

    // One of a client's traits, as metadata: its id, the generated entry type, the gedra kinds it applies to, and
    // its primary key. Not the resolved field schema -- see the note above. Field keys are `CCT`'s (co-located
    // with `GedraTrait`), so a rename is one edit.
    // One of a client's traits, as metadata. The map and this schema are the shared trait projection
    // (`GedraTrait.toMetadataMap` / `traitMetadataFields`, issue #702) -- the same shape the config serializer
    // writes, minus the config-only extras. Not the resolved field schema; see the note above.
    type(CLD.traitInfoTypeName) {
        type = SCT.kObject
        description = "One of a client's traits: its id, generated entry type, applicable gedra kinds and primary key."
        traitMetadataFields()
    }

    // One of a client's trait-usage rules -- a listing column and its search behavior (issues #537, #538). The
    // shared usage projection (`ClientTraitUsage.toRuleMap` / `usageRuleFields`, issue #702) without `display`,
    // the internal expression an admin view has no use for.
    type(CLD.usageInfoTypeName) {
        type = SCT.kObject
        description = "One of a client's trait-usage rules: a listing column and how its value searches."
        usageRuleFields(includeDisplay = false)
    }

    // One configuration issue a check forgave (issue #840). Here, in the module every node loads, so the config
    // endpoints (app-only) reference it by its qualified name.
    type(CLD.configIssueTypeName) {
        type = SCT.kObject
        description = "A problem found in a client's configuration and forgiven: what was wrong, what was dropped, " +
            "and where the offending definition came from."
        property(GCI.message, "What is wrong.", required = true)
        property(GCI.degradedTo, "What was dropped, and what kept, so the node could carry on.", required = true)
        property(GCI.client, "The client holding the offending definition (`global` for a component's own).")
        property(GCI.storedConfigId, "The stored configuration holding it, when it came from the database.")
        property(GCI.elementKind, "What kind of definition is at fault: config, client, workflow, function, type or usage.")
        property(GCI.elementId, "Which one: its id within its kind.")
        property(GCI.origin, "Where the offending definition came from: source code, or stored configuration.", required = true) {
            options(GedraConfigOrigin.entries)
        }
    }

    type(CLD.definitionTypeName) {
        type = SCT.kObject
        description = "One client's definition: its attributes, supported traits, usage rules and workflow ids."
        property(CLD.client, "The client's attributes.", required = true) { ref(CLD.infoTypeName) }
        property(
            CLD.present,
            "Whether this node carries the client. False for one whose definition a check dropped -- returned " +
                "anyway, with its issues, so the reason is visible; its traits, usages and workflows are then empty.",
            required = true,
        ) { type = SCT.boolean }
        property(CLD.issues, "Problems found in the client's configuration and forgiven (issue #840).", required = true) {
            type = SCT.array
            items { ref(CLD.configIssueTypeName) }
        }
        property(CLD.traits, "The traits this client supports (metadata; read the schema from the endpoint catalog).", required = true) {
            type = SCT.array
            items { ref(CLD.traitInfoTypeName) }
        }
        property(CLD.usages, "The client's listing columns / search rules.", required = true) {
            type = SCT.array
            items { ref(CLD.usageInfoTypeName) }
        }
        property(CLD.workflows, "The ids of the workflows this client sees.", required = true) {
            type = SCT.array
            items { type = SCT.string }
        }
    }

    type(CLD.summaryTypeName) {
        type = SCT.kObject
        description = "A cross-client overview row: a client and a brief summary of what it defines."
        property(CLD.clientId, "The client's unique key.", required = true)
        // `emptyIsAbsent = false`: an empty name is a handled state (clientLabel falls back to the id), so it
        // must not read as a missing required field (issue #672 review).
        property(CLD.name, "The client's presented name.", required = true) { emptyIsAbsent = false }
        property(CLD.workflowIds, "The ids of the workflows this client sees.", required = true) {
            type = SCT.array
            items { type = SCT.string }
        }
        property(CLD.traitIds, "The trait ids this client supports.", required = true) {
            type = SCT.array
            items { type = SCT.string }
        }
        property(CLD.usageLabels, "The client's listing-column labels.", required = true) {
            type = SCT.array
            items { type = SCT.string }
        }
        property(CLD.hasSurvey, "Whether the client declares a survey workflow (issue #695): a forms surface working in this client then offers its survey-status filter.", required = true) {
            type = SCT.boolean
        }
        property(CLD.issues, "Problems found in the client's configuration and forgiven (issue #840).", required = true) {
            type = SCT.array
            items { ref(CLD.configIssueTypeName) }
        }
    }

    itemEndpoint(
        ADEP.clientDefinition,
        "One client's definition: its attributes, supported traits, usage rules and workflow ids.",
        HttpMethod.GET,
        outputRef = CLD.definitionTypeName,
        inputFields = { field(CLD.client, "The client to retrieve.", required = true) },
        // Reads a client's stored definition, so opt into the config sync (issue #618): a peer's write to this
        // client's config is picked up before the read rather than served stale.
        needsClientConfig = true,
    ) { c, request ->
        val clientId = request[CLD.client].toOptStr().orEmpty()
        val def = ClientService.get(c).present(clientId)
        if (def != null) {
            clientDefinitionOf(c, def)
        } else {
            droppedClientDefinitionOf(c, clientId)
        }
    }

    listEndpoint(
        ADEP.clientSummaries,
        "Every present client with a brief summary: its workflow ids, trait ids and usage-column labels.",
        outputRef = CLD.summaryTypeName,
        // The clients are a declared set loaded at boot, small enough that paging would pretend otherwise -- as
        // the `/admin/clients` listing above says for the same reason.
        noLimit = true,
        needsClientConfig = true,
    ) { c, _ ->
        namableClients(c).map { clientSummaryOf(c, it) }
    }
}

/** The ids of the workflows [clientId] sees (issue #672); its own plus the inherited global ones. */
private fun workflowIdsFor(cxt: KdrCxt, clientId: String): List<String> =
    WorkflowService.get(cxt).forClient(clientId).workflows.keys.toList()

/** One client's definition for the retrieve endpoint (issue #672): its attributes, the traits it **supports**
 *  (as metadata -- the resolved schema is read from the endpoint catalog with `client=`), its usage rules, and
 *  the ids of the workflows it sees. Reads the *present* (neutralized) definition, so `testFeatures` is already
 *  absent off a test instance. */
private fun clientDefinitionOf(cxt: KdrCxt, def: ClientDef): Map<String, Any?> {
    val schema = SchemaService.get(cxt)
    // The shared projections (issue #702): a trait's metadata as the serializer writes it, and a usage rule
    // without its internal `display` expression.
    val traits = schema.supportedGedraTraitsFor(def.clientId, def).map { it.toMetadataMap() }
    val usages = schema.traitUsagesFor(def.clientId).map { it.toRuleMap(includeDisplay = false) }
    return mapOf(
        CLD.client to def.toInfo(),
        CLD.present to true,
        CLD.issues to ClientConfigIssues.get(cxt).issuesFor(def.clientId).map { it.toWireMap() },
        CLD.traits to traits,
        CLD.usages to usages,
        CLD.workflows to workflowIdsFor(cxt, def.clientId),
    )
}

/**
 * The definition of a client this node does **not** carry because a check dropped it (issue #840): its declared
 * attributes, nothing it supports, and the issues that say why. Anything else absent -- a client nobody declared,
 * or one declared but not enabled here -- is a 404, the house contract for a retrieve of a missing resource (see
 * GedraEndpoints' formDoc get) rather than a null item, which would fail output validation. A client whose whole
 * configuration was refused has no declared definition to show; its 404 carries the issues in its message.
 */
private fun droppedClientDefinitionOf(cxt: KdrCxt, clientId: String): Map<String, Any?> {
    val issues = ClientConfigIssues.get(cxt).issuesFor(clientId)
    // Known but not enabled here is an ordinary absence, not a dropped definition.
    if (issues.isEmpty() || ClientService.get(cxt).known(clientId) != null) {
        throw KdrException("No present client '$clientId'.", code = EXC.notFound)
    }
    val declared = ClientService.get(cxt).declared(clientId)
        ?: throw KdrException(
            "No present client '$clientId': its configuration was not loaded. " +
                issues.joinToString(" ") { "${it.message} ${it.degradedTo}" },
            code = EXC.notFound,
        )
    return mapOf(
        CLD.client to declared.toInfo(),
        CLD.present to false,
        CLD.issues to issues.map { it.toWireMap() },
        CLD.traits to emptyList<Any?>(),
        CLD.usages to emptyList<Any?>(),
        CLD.workflows to emptyList<Any?>(),
    )
}

/** One row of the cross-client summary listing (issue #672): a client and short lists of what it defines. */
private fun clientSummaryOf(cxt: KdrCxt, def: ClientDef): Map<String, Any?> {
    val schema = SchemaService.get(cxt)
    return mapOf(
        CLD.clientId to def.clientId,
        CLD.name to def.name,
        CLD.workflowIds to workflowIdsFor(cxt, def.clientId),
        CLD.traitIds to schema.supportedGedraTraitsFor(def.clientId, def).map { it.traitId },
        CLD.usageLabels to schema.traitUsagesFor(def.clientId).map { it.label },
        CLD.hasSurvey to (WorkflowService.get(cxt).forClient(def.clientId).survey != null),
        CLD.issues to ClientConfigIssues.get(cxt).issuesFor(def.clientId).map { it.toWireMap() },
    )
}

/**
 * The clients this caller may name: every one this node carries for an [ROLE.allClients] holder, and their own
 * alone for everybody else (issue #413).
 *
 * **One rule, read by two surfaces.** The listing above is fenced to `allClients` by its section, so it could
 * as well have said `presentClients` and been right today -- but a sourced choice list is served to *any*
 * caller with a client attribute in front of them, including a client-scoped administrator, and that one
 * cannot. Written twice, the two would agree until somebody changed the fence on one of them. #390 was
 * exactly that shape, and its lesson was that a comment saying two lists must match is not a mechanism.
 *
 * It restates no policy of its own: `catalogClient` and `assignableClient` already refuse a foreign client
 * without the capability, and they keep doing the refusing. This decides what to *offer*, which is a weaker
 * question -- an attribute may be offered a list and still have its value checked by the handler that
 * receives it, and a client attribute always is.
 */
fun namableClients(cxt: KdrCxt): List<ClientDef> {
    val service = ClientService.get(cxt)
    if (cxt.userProfile.roles.contains(ROLE.allClients)) {
        return service.presentClients
    }
    // Their own, when this node carries it. A caller whose client is absent is offered nothing rather than a
    // list they cannot use -- the same answer `hasEndpoints` gives for the same reason.
    return listOfNotNull(service.present(cxt.userProfile.client))
}
