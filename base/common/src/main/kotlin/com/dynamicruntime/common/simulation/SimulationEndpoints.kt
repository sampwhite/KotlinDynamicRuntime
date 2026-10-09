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

    simulationEndpoint(
        ImpactDemo.simulationName,
        "Provisions the publish impact demo (issue #935): a client with a sandbox, three forms stored under its two " +
            "traits, and an unpublished draft of its traits that would strand them -- one trait dropped, a field " +
            "made required that no stored form has. Publishing the draft from the client page is refused with the " +
            "impact report; Check impact shows it first. Give a suffix for a fresh copy (impactdemo2). A rerun " +
            "publishes the harmless traits again and leaves the draft anew; its forms accumulate.",
        inputFields = {
            field(SIM.suffix, "Appended to the client id for a fresh copy; lowercase letters and digits. Absent, the demo client itself.")
        },
    ) { c, request -> provisionImpactDemo(c, request[SIM.suffix].toOptStr()) }
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
    // Approves a logistics plan (issue #1071): the label is the client's, and only its carrier sees the step as theirs.
    // An administrator, since a reviewer reads forms other people own; the designer is one too, without the label.
    val reviewer = Simulations.provisionUser(cxt, "reviewer@$client.example", client, ROLE.admin, name = "Remy Reviewer")
    Simulations.setLabels(cxt, reviewer, listOf(DesignDemo.reviewerLabel))
    return SimulationReport(
        clients = listOf(client),
        users = listOf(
            Simulations.reported(designer, ROLE.admin, "the designer -- an administrator, who can turn on Design View"),
            Simulations.reported(requester, ROLE.user, "a requester -- the ordinary view of the same form"),
            Simulations.reported(reviewer, ROLE.admin, "a reviewer -- an administrator carrying the 'reviewer' label, who approves a logistics plan"),
        ),
        startPage = "page=${HMENU.pageNewForm}",
        summary = "Client '$client' is ready. Sign in as the designer and turn on Design in the app bar.",
    )
}
