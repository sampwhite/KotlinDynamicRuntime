package com.dynamicruntime.common.cfact

import com.dynamicruntime.common.context.KdrCxt
import com.dynamicruntime.common.endpoint.SchModule
import com.dynamicruntime.common.endpoint.schemaModule
import com.dynamicruntime.common.exception.KdrException
import com.dynamicruntime.common.http.request.ROLE
import com.dynamicruntime.common.startup.SchemaService
import com.dynamicruntime.common.user.AdminRules
import com.dynamicruntime.common.user.AdminScope

/**
 * The discovery surface for cfacts (issue #455): what an expression may name here, and what makes each one
 * true.
 *
 * **Reference material, in the same sense the schema catalog is.** A client authoring workflows and UI
 * configuration has to know which cfacts exist before it can write a condition, exactly as it has to know
 * which types exist before it can extend one. That is why this sits in `clientAdmin` -- the client-scoped
 * administration section (issue #466) -- rather than in `operator`, which is for somebody running the
 * deployment. Both admit an administrator through the ladder today; the section says *whose* surface it is,
 * and that outlives the ladder.
 *
 * Its own module rather than a listing folded into `AdminEndpoints`: a section is a statement about
 * authority, not about topic, and that module is about administering users -- and it is contributed
 * application-only, where this belongs on every node, since an edge has a registry of its own to report.
 */
fun cfactSchema(cxt: KdrCxt): SchModule = schemaModule(cxt, "cfact") {
    CFactDef.defineInfoType(this)
    listEndpoint(
        CFD.cfactsPath,
        "Lists the cfacts an expression may name here: each one's name, the group it presents under, and " +
            "what makes it true. Answers with the caller's own client's set, which may hold names nobody " +
            "else has; a path naming a client answers with that client's.",
        outputRef = CFD.infoTypeName,
        // The known set is fixed at boot and small, and this is what somebody consults to find out whether
        // the name they are about to write exists. Paging the answer would put half of it where they cannot
        // see it while they are looking for exactly the entry that is missing.
        noLimit = true,
        // A copy per client that adds any, at `/clientAdmin/<client>/cfacts` (issue #455). That is what lets an
        // administrator who may reach every client ask about one, and what puts the client's copy in the
        // catalog its own people are shown.
        clientShaped = true,
    ) { c, _ ->
        checkPathClient(c)
        // `c.client` is the caller's own on the shared surface and the path's on a client copy -- one read
        // serving both, the same property that lets a client endpoint share the shared handler.
        SchemaService.get(c).cfactsFor(c.client).defs.values
            // Grouped, then alphabetical inside the group: the order a page presents this in, decided here so
            // that every reader of the endpoint gets the same one rather than each sorting it again.
            .sortedWith(compareBy({ it.group }, { it.name }))
            .map { it.toInfo() }
    }
}

/**
 * Refuses a client-pathed read by an administrator confined to a different client (issue #455).
 *
 * **Required by the section rather than optional.** Under `operator` this endpoint was reachable only by
 * people running the deployment, for whom every client is already theirs. `clientAdmin` admits a *customer's*
 * own administrator, and one customer reading another's declarations is exactly the leak that decided cfact
 * names are not held unique across clients: a name refused at boot would let a client discover its
 * neighbors by trying words, and so would a listing of their vocabulary.
 *
 * Only fires on a client copy -- on the shared surface `clientFromPath` is null and the caller gets their own
 * registry, which is theirs by definition. [AdminScope.allClients] passes, which is the whole meaning of the
 * capability and what lets us look at a client's list on their behalf.
 */
private fun checkPathClient(cxt: KdrCxt) {
    val named = cxt.clientFromPath ?: return
    if (AdminRules.adminScope(cxt) == AdminScope.allClients || named == cxt.userProfile.client) {
        return
    }
    throw KdrException.mkInput(
        "Looking at another client's cfacts takes the '${ROLE.allClients}' capability. Yours is " +
            "'${cxt.userProfile.client}'.",
    )
}
