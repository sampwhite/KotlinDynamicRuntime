package com.dynamicruntime.common.gedra

import com.dynamicruntime.common.context.KdrCxt
import com.dynamicruntime.common.endpoint.HttpMethod
import com.dynamicruntime.common.endpoint.SchModule
import com.dynamicruntime.common.endpoint.schemaModule
import com.dynamicruntime.common.exception.EXC
import com.dynamicruntime.common.exception.KdrException
import com.dynamicruntime.common.gedra.workflow.WorkflowService
import com.dynamicruntime.common.schema.SCT
import com.dynamicruntime.common.user.AdminRules
import com.dynamicruntime.common.user.UADEP
import com.dynamicruntime.common.user.UserService
import com.dynamicruntime.common.util.toOptStr

/**
 * The clients an administrator oversees (issue #904): the listing behind the Clients page, and the scoped retrieve
 * of one client's definition.
 *
 * In the **`clientAdmin`** section, once, rather than built for `admin` as well: that section admits every
 * administrator, and an `allClients` holder is simply unconfined on it -- so one handler serves the two kinds of
 * administrator by narrowing on the caller's scope, the way the user administration does (see
 * `scopedUserAdminSchema`). The full-scope client catalog (`/admin/clients`, its summary and retrieve) stays as it
 * is: it feeds choice lists and the cross-client forms surface, and this is not a second copy of it but the
 * administrators' own view -- where a client stands, where its definition came from, and what it holds.
 *
 * **App-only**, unlike the catalog module every node loads: the counts read the user and gedra caches, which an
 * edge does not carry.
 */
fun clientOverviewSchema(cxt: KdrCxt): SchModule = schemaModule(cxt, CLD.overviewNamespace) {
    type(CLD.overviewTypeName) {
        type = SCT.kObject
        description = "One client as its administrators see it: where it stands on this node, where its definition " +
            "came from, and how much it holds."
        property(CLD.clientId, "The client's unique key.", required = true)
        // `emptyIsAbsent = false`: an empty name is a handled state (the id stands in), not a missing field.
        property(CLD.name, "The client's presented name; its id when nothing names it.", required = true) { emptyIsAbsent = false }
        property(CLD.status, "Where the client stands on this node: present, or why not (issue #828).", required = true) {
            options(ClientStatus.entries)
        }
        property(
            CLD.origin,
            "Where the client's definition comes from: a component's source code, or stored configuration. A " +
                "client known only from stored configuration reads as stored.",
            required = true,
        ) { options(GedraConfigOrigin.entries) }
        property(
            CLD.storedConfigs,
            "How many stored configurations this node loaded for the client: its own definition when stored, or " +
                "overlays on a definition from source.",
            required = true,
        ) { type = SCT.integer }
        property(CLD.forms, "How many live form documents the client holds.", required = true) { type = SCT.integer }
        property(
            CLD.users,
            "How many active (enabled) users the client has. Disabled and deleted users are not counted; the count " +
                "is client-wide whoever asks.",
            required = true,
        ) { type = SCT.integer }
        property(CLD.unclaimedUsers, "Of the users, how many have not yet claimed their account.", required = true) {
            type = SCT.integer
        }
        property(CLD.workflowCount, "How many workflows the client sees; 0 for a client this node does not carry.", required = true) {
            type = SCT.integer
        }
        property(CLD.hasSurvey, "Whether the client declares a survey workflow; false for one this node does not carry.", required = true) {
            type = SCT.boolean
        }
        property(CLD.issues, "Problems found in the client's configuration and forgiven (issue #840).", required = true) {
            type = SCT.array
            items { ref(CLD.configIssueTypeQualified) }
        }
    }

    listEndpoint(
        UADEP.clientsOverview,
        "The clients this administrator oversees, each with its status, the origin of its definition, and its " +
            "counts of stored configurations, forms and active users. An allClients administrator sees every " +
            "client this node knows of -- present, not enabled here, dropped by a check, or known only from stored " +
            "configuration -- and a client-scoped one their own. Counts are computed for an absent client too: " +
            "what a dropped client holds is exactly what its administrator needs to see.",
        outputRef = CLD.overviewTypeName,
        // The clients are a declared set, small enough that paging would pretend otherwise (as `/admin/clients` says).
        noLimit = true,
        needsClientConfig = true,
    ) { c, _ ->
        // A `public` self-administrator administers only themselves (issue #805); there is no client for them to oversee.
        AdminRules.requireClientAdministrator(c)
        val present = namableClients(c).map { overviewRow(c, it.clientId, it.name, ClientStatus.present) }
        if (!AdminRules.canSeeAllClients(c)) return@listEndpoint present
        present + absentClients(c).map { overviewRow(c, it.clientId, it.name, it.status) }
    }

    itemEndpoint(
        UADEP.clientDefinition,
        "One client's definition, as `/admin/client/definition` answers it: the caller's own client, or the one " +
            "named by an administrator who may see every client.",
        HttpMethod.GET,
        outputRef = CLD.definitionTypeQualified,
        inputFields = { field(CLD.client, "The client to retrieve; the caller's own when absent.") },
        needsClientConfig = true,
    ) { c, request ->
        AdminRules.requireClientAdministrator(c)
        val own = c.userProfile.client
        val clientId = request[CLD.client].toOptStr() ?: own
        if (clientId != own && !AdminRules.canSeeAllClients(c)) {
            throw KdrException("You may see only your own client, '$own'.", code = EXC.notAuthorized)
        }
        clientDefinitionItem(c, clientId)
    }
}

/**
 * One overview row. A client no kept config declares -- which a present client never is -- is known from stored
 * configuration alone, so that is its origin. Workflows and the survey are read only for a present client: an
 * absent one has no registry to ask.
 */
private fun overviewRow(cxt: KdrCxt, clientId: String, name: String, status: ClientStatus): Map<String, Any?> {
    val isPresent = status == ClientStatus.present
    val users = UserService.get(cxt).countActiveUsers(cxt, clientId)
    return mapOf(
        CLD.clientId to clientId,
        CLD.name to name,
        CLD.status to status.name,
        CLD.origin to (ClientService.get(cxt).originOf(clientId) ?: GedraConfigOrigin.stored).name,
        CLD.storedConfigs to GedraConfigLoadService.get(cxt).loadedFor(clientId).size,
        CLD.forms to GedraDataService.get(cxt).countLiveGedras(cxt, GedraDataType.formDoc, clientId),
        CLD.users to users.total,
        CLD.unclaimedUsers to users.unclaimed,
        CLD.workflowCount to if (isPresent) workflowIdsFor(cxt, clientId).size else 0,
        CLD.hasSurvey to (isPresent && WorkflowService.get(cxt).forClient(clientId).survey != null),
        CLD.issues to ClientConfigIssues.get(cxt).issuesFor(clientId).map { it.toWireMap() },
    )
}
