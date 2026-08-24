package com.dynamicruntime.common.gedra

import com.dynamicruntime.common.context.KdrCxt
import com.dynamicruntime.common.endpoint.SchModule
import com.dynamicruntime.common.endpoint.schemaModule
import com.dynamicruntime.common.http.request.ROLE
import com.dynamicruntime.common.schema.SchOption
import com.dynamicruntime.common.user.ADEP

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
 */
fun clientAdminSchema(cxt: KdrCxt): SchModule = schemaModule(cxt, "clientAdmin") {
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
