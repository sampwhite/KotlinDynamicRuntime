package com.dynamicruntime.common.gedra

import com.dynamicruntime.common.context.KdrCxt
import com.dynamicruntime.common.endpoint.SchModule
import com.dynamicruntime.common.endpoint.schemaModule
import com.dynamicruntime.common.http.request.ROLE
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
        ClientService.get(c).presentClients.map { it.toInfo() }
    }
}
