package com.dynamicruntime.common.cfact

import com.dynamicruntime.common.context.KdrCxt
import com.dynamicruntime.common.endpoint.HttpMethod
import com.dynamicruntime.common.endpoint.SchModule
import com.dynamicruntime.common.endpoint.schemaModule
import com.dynamicruntime.common.exception.KdrException
import com.dynamicruntime.common.http.request.ROLE
import com.dynamicruntime.common.schema.SCT

/**
 * The cfact reference surface (issues #455, #488): which cfacts this client knows -- each one's group and what
 * makes it true -- and, for the caller asking, **whether each is present right now**. Assembled server-side as
 * one Markdown document and rendered by the frontend the way the environment-variable reference and the README
 * are ([renderCFactReference]).
 *
 * **Reference material, in the same sense the schema catalog is**, and now with the live half a static list
 * could never carry. It sits in `clientOperator` -- the client-scoped operator section (issue #488) -- rather
 * than `operator`, which is for somebody running the *deployment* and takes `allClients` a client's own
 * operator lacks. Moved down from `clientAdmin` (where the declarations-only listing lived) because reading
 * what a cfact means and what it currently evaluates to is what keeping a client's deployment running needs,
 * not only authoring its config; the section admits an operator or an admin, still confined by scope.
 *
 * Its own module rather than a listing folded into `AdminEndpoints`: a section is a statement about authority,
 * not about topic, and that module is about administering users -- and it is contributed application-only,
 * where this belongs on every node, since an edge has a registry of its own to report.
 */
fun cfactSchema(cxt: KdrCxt): SchModule = schemaModule(cxt, "cfact") {
    type(CFD.referenceType) {
        type = SCT.kObject
        property(CFD.markdown, "The assembled cfact reference, as Markdown.", required = true)
    }
    itemEndpoint(
        CFD.cfactsPath,
        "The cfacts this client knows -- each one's group and what makes it true -- assembled as Markdown, " +
            "with whether each is present for the caller now. Answers with the caller's own client's set; a " +
            "path naming a client answers with that client's.",
        HttpMethod.GET,
        outputRef = CFD.referenceType,
        // A copy per client that adds any, at `/clientOperator/<client>/cfacts` (issues #455, #488). That is
        // what lets an administrator who may reach every client ask about one, and what puts the client's copy
        // in the catalog its own people are shown.
        clientShaped = true,
    ) { c, _ ->
        checkPathClient(c)
        // `c.client` is the caller's own on the shared surface and the path's on a client copy -- one read
        // serving both, the same property that lets a client endpoint share the shared handler. The present
        // markers always reflect who is *asking*, whichever client's declarations are being shown.
        mapOf(CFD.markdown to renderCFactReference(c))
    }
}

/**
 * Refuses a client-pathed read by an operator or administrator confined to a different client (issues #455,
 * #488).
 *
 * **Required by the section rather than optional.** Under `operator` this endpoint would be reachable only by
 * people running the deployment, for whom every client is already theirs. `clientOperator` admits a
 * *customer's* own operator, and one customer reading another's declarations is exactly the leak that decided
 * cfact names are not held unique across clients: a name refused at boot would let a client discover its
 * neighbors by trying words, and so would a listing of their vocabulary.
 *
 * Only fires on a client copy -- on the shared surface `clientFromPath` is null and the caller gets their own
 * registry, which is theirs by definition. Holding [ROLE.allClients] passes, which is the whole meaning of the
 * capability and what lets us look at a client's list on their behalf.
 *
 * The capability is tested **directly**, not through `AdminRules.adminScope`, which is an admin-shaped question:
 * it answers `none` for anyone without `admin`, so routing this through it would refuse an *operator* who holds
 * `allClients` -- a deployment operator -- with a message saying they lack a capability they hold. That proxy
 * was only coincidentally right while this lived under `clientAdmin` (where every caller was an admin); moving
 * to `clientOperator` (issue #488) made the difference bite. The section gate has already established the
 * caller is at least an operator, so "holds `allClients`" is the whole of the cross-client condition here.
 */
private fun checkPathClient(cxt: KdrCxt) {
    val named = cxt.clientFromPath ?: return
    if (ROLE.allClients in cxt.userProfile.roles || named == cxt.userProfile.client) {
        return
    }
    throw KdrException.mkInput(
        "Looking at another client's cfacts takes the '${ROLE.allClients}' capability. Yours is " +
            "'${cxt.userProfile.client}'.",
    )
}
