package com.dynamicruntime.common.simulation

import com.dynamicruntime.common.context.KdrCxt
import com.dynamicruntime.common.endpoint.HttpMethod
import com.dynamicruntime.common.endpoint.InputFieldsBuilder
import com.dynamicruntime.common.endpoint.SchModuleBuilder
import com.dynamicruntime.common.exception.KdrException
import com.dynamicruntime.common.gedra.ClientSyncService
import com.dynamicruntime.common.gedra.GedraConfig
import com.dynamicruntime.common.gedra.GedraConfigReload
import com.dynamicruntime.common.gedra.GedraConfigService
import com.dynamicruntime.common.gedra.GedraDataService
import com.dynamicruntime.common.gedra.GedraDataType
import com.dynamicruntime.common.http.request.RoleLadder
import com.dynamicruntime.common.schema.SCT
import com.dynamicruntime.common.test.SIM
import com.dynamicruntime.common.user.AuthUserRow
import com.dynamicruntime.common.user.UserService

/*
 * Simulations (issue #997): canned scenarios a test instance provisions with one call. A simulation is a
 * `forTestingOnly` endpoint declared with [simulationEndpoint] in the schema module of the component that owns what it
 * provisions -- that declaration is its registration -- and it answers with a [SimulationReport]. The provisioning
 * itself is a plain function the endpoint's handler calls, so a test calls the same function in-process.
 *
 * Simulations may build on each other (one calling another's function). Where two could conflict -- both writing one
 * client's configuration, say -- the endpoint's description says so; nothing here guards against it.
 */

/** A user a simulation provisioned, as its report lists them: what signing in as them takes, and what they are for. */
class SimulationUser(
    val email: String,
    val client: String,
    val level: String,
    val persona: String?,
    /** What this user is for, in a few words: "the designer", "an owner". */
    val purpose: String,
) {
    fun toJsonMap(): Map<String, Any?> = buildMap {
        put(SIM.email, email)
        put(SIM.client, client)
        put(SIM.level, level)
        persona?.let { put(SIM.persona, it) }
        put(SIM.purpose, purpose)
    }
}

/**
 * What a simulation did (issue #997): the [clients] it provisioned or added to, the [users] to sign in as, the
 * [startPage] to open (a page hash, e.g. `page=newForm`), and a one-line [summary]. The same shape for every
 * simulation, so the Simulations page can turn any report into "sign in as … and open …".
 */
class SimulationReport(
    val clients: List<String>,
    val users: List<SimulationUser>,
    val startPage: String?,
    val summary: String,
) {
    fun toJsonMap(): Map<String, Any?> = buildMap {
        put(SIM.clients, clients)
        put(SIM.users, users.map { it.toJsonMap() })
        startPage?.let { put(SIM.startPage, it) }
        put(SIM.summary, summary)
    }
}

/** Provisioning steps simulations share, each the way the corresponding endpoint does it. */
object Simulations {
    /**
     * Writes [config] as its client's stored configuration and makes it live on **every** node: a trial-checked
     * write, a reload of the client here, and an announcement so the other nodes reload too -- what every
     * config-writing endpoint does. (A test's `writeConfig` plus `reloadClient` reaches this node only.) A problem the
     * reload reports is thrown, so a simulation never reports success over a client that did not load.
     */
    fun provisionConfig(cxt: KdrCxt, config: GedraConfig) {
        val client = config.gedraId.client
        GedraConfigService.get(cxt).writeConfig(cxt.mkSubContext("simulation", client), config, trial = true)
        val result = GedraConfigReload.reloadClient(cxt, client)
        ClientSyncService.get(cxt).announceReload(cxt, result)
        result.issues.firstOrNull()?.let {
            throw KdrException("Client '$client' was written but did not load cleanly: ${it.message} ${it.degradedTo}")
        }
    }

    /**
     * The user at [email] in [client], found or created at [level] -- without signing in as them, which is the page's
     * to do. A found user is taken as it is (its level and name unchanged), as `/fixture/becomeUser` takes one.
     */
    fun provisionUser(
        cxt: KdrCxt,
        email: String,
        client: String,
        level: String,
        name: String? = null,
    ): AuthUserRow {
        val service = UserService.get(cxt)
        service.checkInit(cxt)
        return service.authFormHandler.findOrProvisionUser(
            cxt, email, level, capabilities = emptyList(), failIfUserAlreadyExists = false, client = client, name = name,
        ).row
    }

    /** The [SimulationUser] a report lists for [row], provisioned at [level], with what it is [purpose] for. */
    fun reported(row: AuthUserRow, level: String, purpose: String): SimulationUser =
        SimulationUser(row.primaryId, row.client, level, row.persona, purpose)

    /**
     * A form document carrying [entries], created as [owner] in their client -- owned by them, as their own create
     * would make it.
     */
    fun createForm(cxt: KdrCxt, owner: AuthUserRow, entries: List<Map<String, Any?>>) {
        val ownerCxt = cxt.mkSubContext("simulation", owner.client).also {
            it.userId = owner.userId
            it.org = owner.org
        }
        GedraDataService.get(ownerCxt).createGedra(ownerCxt, GedraDataType.formDoc, entries)
    }

    /**
     * [suffix] checked as a client-id suffix (see [SIM.suffix]): blank is none, and anything but lowercase letters
     * and digits is refused, since it becomes part of a client id and from there of every gedra id the client owns.
     */
    fun checkedSuffix(suffix: String?): String {
        val s = suffix?.trim().orEmpty()
        if (s.isNotEmpty() && !s.all { it in 'a'..'z' || it in '0'..'9' }) {
            throw KdrException.mkInput("A client suffix is lowercase letters and digits only; '$s' is not.")
        }
        return s
    }
}

/**
 * Declares a **simulation** (issue #997): a `forTestingOnly` POST at `/fixture/simulate/<name>`, tagged
 * [SIM.tag] so the listing finds it, answering with the [SimulationReport] [handler] returns. Being test-only, it
 * exists only on a test instance. The module gets the shared report types the first time it declares one.
 *
 * [description] is what the Simulations page shows above the form: say what the simulation provisions, whether a
 * rerun rewrites or adds, and any simulation it could conflict with.
 */
fun SchModuleBuilder.simulationEndpoint(
    name: String,
    description: String,
    inputFields: (InputFieldsBuilder.() -> Unit)? = null,
    handler: (cxt: KdrCxt, request: Map<String, Any?>) -> SimulationReport,
) {
    if ("$namespace.${SIM.reportType}" !in defs) defineSimulationReportTypes(this)
    generalEndpoint(
        SIM.pathRoot + name,
        description,
        HttpMethod.POST,
        outputRef = SIM.reportType,
        inputFields = inputFields,
        forTestingOnly = true,
        tags = setOf(SIM.tag),
    ) { c, request -> handler(c, request).toJsonMap() }
}

/** The [SimulationReport] and [SimulationUser] types, defined on [builder] in its namespace. */
private fun defineSimulationReportTypes(builder: SchModuleBuilder) {
    builder.type(SIM.userType) {
        type = SCT.kObject
        description = "A user a simulation provisioned: what signing in as them takes, and what they are for."
        property(SIM.email, "The user's address.", required = true)
        property(SIM.client, "The user's client.", required = true)
        property(SIM.level, "The user's access level.", required = true) { for (rung in RoleLadder.ordered) option(rung) }
        property(SIM.persona, "The user's persona.")
        property(SIM.purpose, "What this user is for, in a few words.", required = true)
    }
    builder.type(SIM.reportType) {
        type = SCT.kObject
        description = "What a simulation provisioned, who to sign in as, and where to start."
        property(SIM.clients, "The clients provisioned or added to.", required = true) {
            type = SCT.array
            items { type = SCT.string }
        }
        property(SIM.users, "The users to sign in as.", required = true) {
            type = SCT.array
            items { ref(SIM.userType) }
        }
        property(SIM.startPage, "The page to open after signing in, as a page hash (e.g. page=newForm).")
        property(SIM.summary, "One line of what was done.", required = true)
    }
}
