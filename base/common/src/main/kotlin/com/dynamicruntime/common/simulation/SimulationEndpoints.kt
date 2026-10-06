package com.dynamicruntime.common.simulation

import com.dynamicruntime.common.context.KdrCxt
import com.dynamicruntime.common.endpoint.SchModule
import com.dynamicruntime.common.endpoint.schemaModule
import com.dynamicruntime.common.home.HMENU
import com.dynamicruntime.common.http.request.ROLE
import com.dynamicruntime.common.test.SIM
import com.dynamicruntime.common.util.toOptStr

/**
 * The simulations that depend on nothing beyond `common` (issue #997), so every test instance offers them. A
 * component's own simulations -- of its clients or its data -- are declared in its own schema module instead.
 */
fun simulationSchema(cxt: KdrCxt): SchModule = schemaModule(cxt, "kdr.simulation") {
    simulationEndpoint(
        DesignDemo.simulationName,
        "Provisions the Design View demo client -- a client defined entirely in data, with one form showing each case " +
            "the inspector tells apart -- and an administrator and a requester to sign in as. Give a suffix for a fresh " +
            "copy (designdemo2, designdemoeva), so a review starts from the demo as shipped rather than from the last " +
            "session's edits. A rerun rewrites that client's configuration, Design View edits included; its users and " +
            "forms stay.",
        inputFields = {
            field(SIM.suffix, "Appended to the client id for a fresh copy; lowercase letters and digits. Absent, the demo client itself.")
        },
    ) { c, request -> provisionDesignDemo(c, request[SIM.suffix].toOptStr()) }
}

/**
 * Provisions the Design View demo client (issue #997): its configuration, written and made live on every node, and an
 * administrator and a requester. [suffix] makes a fresh copy, `designdemo<suffix>` (see [SIM.suffix]).
 */
fun provisionDesignDemo(cxt: KdrCxt, suffix: String? = null): SimulationReport {
    val client = DesignDemo.client + Simulations.checkedSuffix(suffix)
    Simulations.provisionConfig(cxt, designDemoConfig(cxt, client))
    val designer = Simulations.provisionUser(cxt, "designer@$client.example", client, ROLE.admin, name = "Dana Designer")
    val requester = Simulations.provisionUser(cxt, "requester@$client.example", client, ROLE.user, name = "Riley Requester")
    return SimulationReport(
        clients = listOf(client),
        users = listOf(
            Simulations.reported(designer, ROLE.admin, "the designer -- an administrator, who can turn on Design View"),
            Simulations.reported(requester, ROLE.user, "a requester -- the ordinary view of the same form"),
        ),
        startPage = "page=${HMENU.pageNewForm}",
        summary = "Client '$client' is ready. Sign in as the designer and turn on Design in the app bar.",
    )
}
