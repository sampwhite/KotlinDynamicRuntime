package com.dynamicruntime.common.test

/**
 * Wire constants for **simulations** (issue #997): canned scenarios -- clients, users, data -- a test instance can
 * provision with one call, the same way the tests do, so a person doing UAT can set one up and look at it.
 *
 * A simulation is a `forTestingOnly` endpoint under [pathRoot], tagged [tag] and answering with a [reportType]: what it
 * provisioned, who to sign in as, and where to start. Shared in the kernel so the endpoints, `kdr-probe` and the
 * webapp's Simulations page read one vocabulary.
 */
@Suppress("ConstPropertyName")
object SIM {
    /** The catalog tag every simulation endpoint carries -- how the listing finds them. */
    const val tag = "simulation"

    /** Where simulation endpoints live: `<pathRoot><name>`, e.g. `/fixture/simulate/design-demo`. */
    const val pathRoot = "/fixture/simulate/"

    /**
     * The simulations this node offers (a `forTestingOnly` GET), in the endpoint catalog's shape: the listing a
     * Simulations page draws its forms from. Unlike the catalog it is not narrowed to the published API for a caller
     * without env auth -- on a test instance, simulations are for anyone testing it.
     */
    const val list = "/fixture/simulations"

    /** The output type every simulation answers with (defined in each module that declares a simulation). */
    const val reportType = "SimulationReport"

    /** A user a simulation provisioned, as its report lists them. */
    const val userType = "SimulationUser"

    // --- the report ---

    /** The clients the simulation provisioned or added to. */
    const val clients = "clients"

    /** The users to sign in as, each with what it is for. */
    const val users = "users"

    /** Where to start looking: a page hash such as `page=newForm`, opened after signing in. */
    const val startPage = "startPage"

    /** One line of what was done, e.g. "Created 32 forms in 'acme'." */
    const val summary = "summary"

    // --- a user in the report: what `/fixture/becomeUser` needs to sign in as them, and why ---

    const val email = "email"
    const val client = "client"
    const val level = "level"
    const val persona = "persona"
    const val purpose = "purpose"

    // --- inputs a simulation may take ---

    /**
     * For a simulation of a client defined in data: a suffix making a fresh client of the same shape
     * (`designdemo` + `2` = `designdemo2`), so each review gets its own client rather than one everyone edits.
     * Lowercase letters and digits only, since it becomes part of a client id.
     */
    const val suffix = "suffix"
}
