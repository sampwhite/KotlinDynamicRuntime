package com.dynamicruntime.common.gedra

import com.dynamicruntime.common.context.CL
import com.dynamicruntime.common.context.KdrCxt
import com.dynamicruntime.common.endpoint.HttpMethod
import com.dynamicruntime.common.endpoint.SchModule
import com.dynamicruntime.common.endpoint.schemaModule
import com.dynamicruntime.common.exception.EXC
import com.dynamicruntime.common.exception.KdrException
import com.dynamicruntime.common.schema.SCT
import com.dynamicruntime.common.user.AuthIdentityRow
import com.dynamicruntime.common.user.AuthUserRow
import com.dynamicruntime.common.user.PERSONA
import com.dynamicruntime.common.user.UserService
import com.dynamicruntime.common.user.authHandler
import com.dynamicruntime.common.util.toOptStr

/**
 * Joining a demo client (issue #1248): a visitor signed in to `public` takes one of the roles a demo client declares
 * ([ClientDef.joinRoles]) and gets a user of their **own identity** in that one shared client -- `acme` or `designdemo`
 * as an administrator, `confdemo` as a speaker, organizer or committee member -- and the session moves to it. Their
 * other users stay theirs, in the switcher.
 *
 * - **Offered only where the deployment says so** ([isOffered], `JOIN.offeredKey`, which the `reference` flavor sets),
 *   to a caller acting in `public`. Anywhere else the endpoints answer as absent (404): a node that merely carries
 *   `acme` -- every developer's does -- must never make a stranger its administrator.
 * - **Only what the role says.** The user takes the role's persona and so that persona's default roles -- never
 *   `allClients` or `operator`, which no persona carries -- and the role's labels. The one provisioning path
 *   ([UserService.claimUser]) checks the persona, the suffix and the client's plan, and finds a user the person
 *   already has there rather than making a second.
 * - **Acknowledged.** A demo client is shared: what a visitor writes, and as an administrator their view of the
 *   other visitors, are other visitors'. A join must carry the acknowledgment the Join page asks for, or it is refused
 *   -- unless the visitor has joined the client before ([Joinable.joined]), and so acknowledged it then: the disclosure
 *   is the client's, the same whichever role is chosen (issue #1410).
 */
object ClientJoin {
    /** Whether this deployment offers joining a demo client (issue #1248). */
    fun isOffered(cxt: KdrCxt): Boolean = cxt.instanceConfig.get(JOIN.offeredKey) == true

    /**
     * One role of a [Joinable] client, as the caller stands in it: [userId] when they hold it, and [removed] when they
     * held it and one of the client's administrators has since disabled that user. (A user deleted for good gives up
     * its key, so the person may join as the role afresh, as they may hold a user anywhere such a delete freed.)
     */
    class JoinableRole(val role: ClientJoinRole, val userId: Long?, val removed: Boolean)

    /** A client the caller may join, with each of its roles and how the caller stands in it. */
    class Joinable(val def: ClientDef, val roles: List<JoinableRole>) {
        /**
         * Whether the caller has joined the client: they hold one of its roles, or held one since disabled. (A user
         * deleted for good is not counted, as it is not for its role: the person joins as though for the first time.)
         */
        val joined: Boolean get() = roles.any { it.userId != null || it.removed }

        fun toJsonMap(): Map<String, Any?> = buildMap {
            put(JOIN.client, def.clientId)
            put(JOIN.clientName, def.name.ifEmpty { def.clientId })
            def.description?.let { put(JOIN.description, it) }
            if (joined) put(JOIN.joined, true)
            put(JOIN.roles, roles.map { held ->
                buildMap {
                    put(JOIN.name, held.role.name)
                    put(JOIN.label, held.role.label)
                    put(JOIN.persona, held.role.persona)
                    put(JOIN.summary, held.role.summary)
                    held.userId?.let { put(JOIN.userId, it) }
                    if (held.removed) put(JOIN.removed, true)
                }
            })
        }
    }

    /** What the caller may join: every present client declaring join roles, in the order the deployment holds them. */
    fun joinable(cxt: KdrCxt): List<Joinable> {
        val identity = joiner(cxt)
        val mine = UserService.get(cxt).usersOfIdentity(cxt, identity.identityId)
        return ClientService.get(cxt).presentClients.filter { it.joinRoles.isNotEmpty() }.map { standing(it, mine) }
    }

    /** How the person whose users are [mine] stands in [def]'s roles. */
    private fun standing(def: ClientDef, mine: List<AuthUserRow>): Joinable =
        Joinable(def, def.joinRoles.map { role ->
            val held = mine.firstOrNull { it.holds(def.clientId, role) }
            JoinableRole(role, held?.takeIf { it.isActive }?.userId, held != null && !held.enabled)
        })

    /**
     * Joins [client] as its role [roleName] for the person signed in as [cxt]'s user, who has [acknowledged] the
     * disclosure, now or when they first joined the client: their user there, found or made, carrying the role's
     * labels. The endpoint then moves the session to it.
     */
    fun join(cxt: KdrCxt, client: String, roleName: String, acknowledged: Boolean): AuthUserRow {
        val identity = joiner(cxt)
        val def = ClientService.get(cxt).present(client)?.takeIf { it.joinRoles.isNotEmpty() }
            ?: throw KdrException.mkInput("'$client' is not a client you may join.")
        val role = def.joinRoles.firstOrNull { it.name == roleName }
            ?: throw KdrException.mkInput(
                "'$roleName' is not a role '$client' may be joined as; its roles are ${def.joinRoles.joinToString(", ") { it.name }}.",
            )
        val users = UserService.get(cxt)
        val mine = users.usersOfIdentity(cxt, identity.identityId)
        if (!acknowledged && !standing(def, mine).joined) {
            throw KdrException.mkInput(
                "Joining '$client' needs the acknowledgment that it is a shared demo space, where other visitors see what you do.",
            )
        }
        // The load already holds a role to a persona the registry has (`joinRoleFaults`); asked again, as the one place
        // a role becomes roles.
        val persona = PERSONA.def(role.persona) ?: throw KdrException("Join role '${role.name}' names no persona.")
        // A user one of the client's administrators disabled stays so: in a shared demo, joining again must not undo
        // another visitor's decision, which the disclosure tells the visitor an administrator may make. (A user deleted
        // for good gave up its key, so it is not found here, and the person joins as the role afresh.)
        if (mine.any { it.holds(client, role) && !it.enabled }) {
            throw KdrException.mkInput(
                "Your ${role.label} user in ${def.name.ifEmpty { client }} was disabled by one of its administrators, so " +
                    "it is not joined again.",
            )
        }
        val row = try {
            users.claimUser(cxt, identity, client, persona.name, role.personaSuffix, persona.defaultRoles)
        } catch (e: Exception) {
            // Two joins of the role at once both find no user and both make one: the second loses on the user's key,
            // and finds the first's.
            users.usersOfIdentity(cxt, identity.identityId).firstOrNull { it.holds(client, role) && it.isActive } ?: throw e
        }
        val missing = role.userLabels.filterNot { it in row.labels }
        if (missing.isNotEmpty()) {
            row.labels += missing
            users.updateUser(cxt.mkSubContext("clientJoin", client), row)
        }
        return row
    }

    /** The caller's identity, when joining is offered to them; refused as absent when it is not. */
    private fun joiner(cxt: KdrCxt): AuthIdentityRow {
        if (!isOffered(cxt) || cxt.userProfile.client != CL.public) {
            throw KdrException("Joining a demo client is not offered here.", code = EXC.notFound)
        }
        return cxt.userProfile.identityId?.let { UserService.get(cxt).queryIdentityFresh(cxt, it) }
            ?: throw KdrException("This session has no identity to join with; log in again.", code = EXC.authNeeded)
    }

    private fun AuthUserRow.holds(client: String, role: ClientJoinRole): Boolean =
        this.client == client && persona == role.persona && personaSuffix == role.personaSuffix

    /** A user the person may act as: enabled, registered, and not deleted. */
    private val AuthUserRow.isActive: Boolean get() = enabled && isRegistered && !isDeleted
}

/**
 * The join endpoints (issue #1248), in the login-gated `user` section beside the person's other self endpoints. An
 * application node's: joining makes a user.
 */
fun clientJoinSchema(cxt: KdrCxt): SchModule = schemaModule(cxt, JOIN.namespace) {
    type(JOIN.joinableRoleTypeName) {
        type = SCT.kObject
        description = "A role a visitor may join a demo client as."
        property(JOIN.name, "The role's id, to send when joining.", required = true)
        property(JOIN.label, "The role as the Join page names it.", required = true)
        property(JOIN.persona, "The persona joining as the role gives (member, editor or admin).", required = true)
        property(JOIN.summary, "A sentence on what the role does.", required = true)
        property(JOIN.userId, "The caller's user holding the role, when they have joined as it.") { type = SCT.integer }
        property(
            JOIN.removed,
            "True when the caller joined as the role and an administrator of the client has since disabled that user, " +
                "which is not joined again; absent otherwise.",
        ) { type = SCT.boolean }
    }
    type(JOIN.joinableTypeName) {
        type = SCT.kObject
        description = "A demo client the caller may join (issue #1248), with its roles."
        property(JOIN.client, "The client's id.", required = true)
        property(JOIN.clientName, "The client's name.", required = true)
        property(JOIN.description, "What the client is.")
        property(
            JOIN.joined,
            "True when the caller has joined the client, as any of its roles, and so acknowledged its disclosure " +
                "then; a join then needs no acknowledgment again (issue #1410). Absent otherwise.",
        ) { type = SCT.boolean }
        property(JOIN.roles, "The roles it may be joined as.", required = true) {
            type = SCT.array
            items { ref(JOIN.joinableRoleTypeName) }
        }
    }
    type(JOIN.resultTypeName) {
        type = SCT.kObject
        description = "What joining a demo client did (issue #1248)."
        property(JOIN.client, "The client joined.", required = true)
        property(JOIN.role, "The role joined as.", required = true)
        property(JOIN.userId, "The caller's user in the client, which the session now acts as.", required = true) {
            type = SCT.integer
        }
    }

    listEndpoint(
        JOIN.joinablePath,
        "The demo clients the caller may join (issue #1248), each with the roles it may be joined as and the caller's " +
            "user in each role they hold. Offered only to a caller in '${CL.public}' on a deployment that offers joining; " +
            "absent elsewhere.",
        outputRef = JOIN.joinableTypeName,
        // A handful of demo clients: paging would pretend otherwise.
        noLimit = true,
    ) { c, _ -> ClientJoin.joinable(c).map { it.toJsonMap() } }

    generalEndpoint(
        JOIN.joinPath,
        "Joins a demo client as one of its roles (issue #1248): a user of the caller's own identity there, found or " +
            "made, with the role's persona and labels; the session moves to it. Refused without the acknowledgment that " +
            "the client is a shared demo space, unless the caller has joined it before. Offered only to a caller in " +
            "'${CL.public}' on a deployment that offers joining; absent elsewhere.",
        HttpMethod.POST,
        outputRef = JOIN.resultTypeName,
        inputFields = {
            field(JOIN.client, "The client to join.", required = true)
            field(JOIN.role, "The role to join it as.", required = true)
            field(
                JOIN.acknowledged,
                "That the caller has read that the client is a shared demo space; not needed once they have joined it.",
            ) { type = SCT.boolean }
        },
        sessionOnly = true,
    ) { c, request ->
        val role = request[JOIN.role].toOptStr().orEmpty()
        val row = ClientJoin.join(c, request[JOIN.client].toOptStr().orEmpty(), role, request[JOIN.acknowledged] == true)
        authHandler(c).switchUser(c, row.userId)
        mapOf(JOIN.client to row.client, JOIN.role to role, JOIN.userId to row.userId)
    }
}
