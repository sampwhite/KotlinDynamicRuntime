package com.dynamicruntime.common.gedra

import com.dynamicruntime.common.context.KdrCxt
import com.dynamicruntime.common.endpoint.HttpMethod
import com.dynamicruntime.common.endpoint.SchModule
import com.dynamicruntime.common.endpoint.schemaModule
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

    // --- one client's full definition + trait schema, and a cross-client summary listing (issue #672) -------
    //
    // Both are full-scope (the `admin` section takes `allClients`), because they answer a cross-client question:
    // an `allClients` administrator retrieves another client's rules so a formDocs surface can list, filter,
    // edit and create in *that* client's terms. The frontend that consumes these arrives in a later slice
    // (depends on #668); this is the backend they read from.
    //
    // `testFeatures` never leaks here: the retrieve projects `ClientDef.toInfo()` off the **present** definition,
    // which `ClientService` has already neutralized on a non-test node (issue #696), so the field is simply
    // absent off a test instance whatever the stored row held. No per-endpoint strip is needed.

    // One of a client's traits: id, generated type name, applicable kinds, primary key, and its data schema --
    // the `dataSchema` being the trait schema a form is built from.
    type(CLD.traitInfoTypeName) {
        type = SCT.kObject
        description = "One of a client's traits: its id, generated type, applicable gedra kinds, primary key and data schema."
        property(CLD.traitId, "The trait's globally unique id.", required = true)
        property(CLD.typeName, "The fully qualified name of the entry type this trait generated.", required = true)
        property(CLD.appliesTo, "The gedra kinds an entry of this trait may be carried on.", required = true) {
            type = SCT.array
            items { type = SCT.string }
        }
        property(CLD.primaryKey, "The data fields that tell several entries apart; empty when single-instance.", required = true) {
            type = SCT.array
            items { type = SCT.string }
        }
        // Free-form: the trait's own JSON Schema, whatever shape the trait declared.
        property(CLD.dataSchema, "The JSON Schema of the trait's own data.", required = true) { type = SCT.kObject }
    }

    // One of a client's trait-usage rules -- a listing column and its search behavior (issues #537, #538).
    type(CLD.usageInfoTypeName) {
        type = SCT.kObject
        description = "One of a client's trait-usage rules: a listing column and how its value searches."
        property(CLD.traitId, "The trait whose value the column shows.", required = true)
        property(CLD.label, "The column header.", required = true)
        property(CLD.kind, "How the value is read and compared.", required = true) { options(UsageKind.entries) }
        property(CLD.substring, "Whether a string column also offers a contains search.", required = true) { type = SCT.boolean }
    }

    type(CLD.definitionTypeName) {
        type = SCT.kObject
        description = "One client's full definition: its attributes, traits (with data schema), usage rules and workflow ids."
        property(CLD.client, "The client's attributes.", required = true) { ref(CLD.infoTypeName) }
        property(CLD.traits, "The traits this client supports, each with its data schema.", required = true) {
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
        property(CLD.name, "The client's presented name.", required = true)
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
    }

    itemEndpoint(
        ADEP.clientDefinition,
        "One client's full definition: its attributes, traits (with data schema), usage rules and workflow ids.",
        HttpMethod.GET,
        outputRef = CLD.definitionTypeName,
        inputFields = { field(CLD.client, "The client to retrieve.", required = true) },
    ) { c, request ->
        val clientId = request[CLD.client].toOptStr()?.ifBlank { null }
            ?: throw KdrException.mkInput("A '${CLD.client}' is required.")
        // Only a present client (enabled in this environment) -- an absent one has no data or endpoints to act
        // in, so it reads as not found rather than an error. `present` returns the neutralized definition.
        ClientService.get(c).present(clientId)?.let { clientDefinitionOf(c, it) }
    }

    listEndpoint(
        ADEP.clientSummaries,
        "Every present client with a brief summary: its workflow ids, trait ids and usage-column labels.",
        outputRef = CLD.summaryTypeName,
        // The clients are a declared set loaded at boot, small enough that paging would pretend otherwise -- as
        // the `/admin/clients` listing above says for the same reason.
        noLimit = true,
    ) { c, _ ->
        namableClients(c).map { clientSummaryOf(c, it) }
    }
}

/** One client's full definition for the retrieve endpoint (issue #672): its attributes, its traits with their
 *  data schema, its usage rules, and the ids of the workflows it sees. Reads the *present* (neutralized)
 *  definition and the compiled per-client schema, so `testFeatures` is already absent off a test instance. */
private fun clientDefinitionOf(cxt: KdrCxt, def: ClientDef): Map<String, Any?> {
    val schema = SchemaService.get(cxt)
    val traits = schema.gedraTraitsFor(def.clientId).map { t ->
        mapOf(
            CLD.traitId to t.traitId,
            CLD.typeName to t.typeName,
            CLD.appliesTo to t.appliesTo.map { it.name },
            CLD.primaryKey to t.primaryKey,
            CLD.dataSchema to t.dataSchema,
        )
    }
    val usages = schema.traitUsagesFor(def.clientId).map { u ->
        mapOf(CLD.traitId to u.traitId, CLD.label to u.label, CLD.kind to u.kind.name, CLD.substring to u.substring)
    }
    return mapOf(
        CLD.client to def.toInfo(),
        CLD.traits to traits,
        CLD.usages to usages,
        CLD.workflows to WorkflowService.get(cxt).forClient(def.clientId).workflows.keys.toList(),
    )
}

/** One row of the cross-client summary listing (issue #672): a client and short lists of what it defines. */
private fun clientSummaryOf(cxt: KdrCxt, def: ClientDef): Map<String, Any?> {
    val schema = SchemaService.get(cxt)
    return mapOf(
        CLD.clientId to def.clientId,
        CLD.name to def.name,
        CLD.workflowIds to WorkflowService.get(cxt).forClient(def.clientId).workflows.keys.toList(),
        CLD.traitIds to schema.gedraTraitsFor(def.clientId).map { it.traitId },
        CLD.usageLabels to schema.traitUsagesFor(def.clientId).map { it.label },
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
