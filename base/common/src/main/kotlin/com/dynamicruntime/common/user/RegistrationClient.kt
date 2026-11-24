package com.dynamicruntime.common.user

import com.dynamicruntime.common.context.CL
import com.dynamicruntime.common.context.KdrCxt
import com.dynamicruntime.common.context.KdrInstanceConfig
import com.dynamicruntime.common.exception.KdrException
import com.dynamicruntime.common.gedra.ClientService
import com.dynamicruntime.common.http.request.ROLE
import com.dynamicruntime.common.http.request.RoleLadder
import com.dynamicruntime.common.logging.LogStartup

/** Constants for the registration client (issue #1867). */
@Suppress("ConstPropertyName")
object REGC {
    /**
     * Instance-config key naming **the one client every new user lands in**: a visitor registering by code, a first
     * Google sign-in, the env gate's sign-in, and an administrator's new user placed in no named client. Unset, a new
     * user lands where `AddressRules.defaultClient` says (`hub` for an administrator, `public` for everyone else).
     * Set, it replaces that rule for everyone, administrators included, so a deployment for one organization keeps
     * all its people in one client. A flavor sets it; the boot refuses a client that is not present, and `public` or
     * `hub`, which are what it replaces.
     */
    const val client = "registrationClient"

    /**
     * Instance-config key naming the role a person who registers into [client] gets beside `user`: a role of the
     * deployment's own, such as `lead`, that its endpoints and workflows recognize. Unset, `user` alone. Never a
     * level above `user`, nor `editor` or `allClients`: an administrator gets their roles from the auto-admin rule
     * (`AdminRules.isAutoAdminAddress`), never from this key, so the boot refuses those.
     */
    const val role = "registrationRole"
}

/**
 * The registration client (issue #1867): a deployment's choice of the one client every new user lands in, and the
 * role a person registering there gets.
 *
 * It exists for a deployment that runs one organization beside a public website, where the visitors who register
 * through the site and the organization's own people (signing in through the env gate) are all users of one client.
 * The `public` client's way of working (each user effectively their own client, joining a demo or making a client of
 * their own) is then not wanted, and the endpoints offered only in `public` are absent for a caller in another client.
 */
object RegistrationClient {
    /** The roles a registration role may not be: every level above `user`, and the capabilities that widen one. */
    private val privileged: Set<String> =
        RoleLadder.ordered.filter { it != ROLE.user }.toSet() + ROLE.editor + ROLE.allClients

    /** What a role name may look like: one word, as every role this deployment grants is. */
    private val roleName = Regex("[A-Za-z][A-Za-z0-9_]*")

    /** The registration client [config] names, or null when it names none. */
    fun clientOf(config: KdrInstanceConfig): String? = (config.get(REGC.client) as? String)?.trim()?.ifEmpty { null }

    /** The role a person registering into the registration client gets beside `user`: [ROLE.user] when none is set. */
    fun roleOf(config: KdrInstanceConfig): String = (config.get(REGC.role) as? String)?.trim()?.ifEmpty { null } ?: ROLE.user

    /**
     * The roles a person who registers, or signs in with Google for the first time, starts with in [client], given
     * the [roles] the address earns (`AdminRules.initialRoles`): those, and the registration role when [client] is
     * the registration client. Not for an administrator: the auto-admin rule's roles stand as they are, since a
     * registration role names an ordinary registrant's kind.
     */
    fun withRegistrationRole(cxt: KdrCxt, client: String, roles: List<String>): List<String> {
        val config = cxt.instanceConfig
        if (client != clientOf(config)) return roles
        if (RoleLadder.highestHeld(roles) != ROLE.user || roles.any { it in privileged }) return roles
        return (roles + roleOf(config)).distinct()
    }

    /**
     * Whether a demo's provisioning at boot is skipped (issue #1867), logging the skip under the demo's name for a log
     * line, [demo]: it is, on a deployment naming a registration client. Such a deployment's visitors all land in that
     * client, and joining a demo is offered only in `public`, so nobody there could reach the demo. Its users would sit
     * at fixed addresses in a client of their own, and its stored configuration would make a client nobody uses.
     */
    fun skipsDemoAtBoot(cxt: KdrCxt, demo: String): Boolean {
        val client = clientOf(cxt.instanceConfig) ?: return false
        LogStartup.info(
            cxt, "Not provisioning the $demo at boot: every new user here lands in '$client' (${REGC.client}), so none could join it.",
        )
        return true
    }

    /**
     * Refuses a registration client that is not present on this node ([clients]), or is `public` or `hub`, and a
     * registration role that is not a role's name or that is privileged. Called once the clients are known.
     */
    fun checkBoot(config: KdrInstanceConfig, clients: ClientService) {
        fun refuse(why: String): Nothing = throw KdrException("Registration client: $why")
        val rawClient = config.get(REGC.client)
        val rawRole = config.get(REGC.role)
        if (rawClient != null && rawClient !is String) refuse("'${REGC.client}' is not text: '$rawClient'.")
        if (rawRole != null && rawRole !is String) refuse("'${REGC.role}' is not text: '$rawRole'.")
        val client = clientOf(config)
        if (client == null) {
            if (rawRole != null) refuse("'${REGC.role}' is set, but '${REGC.client}' is not, so it would mean nothing.")
            return
        }
        if (client == CL.public || client == CL.hub) {
            refuse(
                "'${REGC.client}' is '$client', which is one of the clients it replaces. Name the deployment's own " +
                    "client, or leave it unset for the usual rule ('${CL.hub}' for an administrator, '${CL.public}' " +
                    "for everyone else).",
            )
        }
        if (!clients.isPresent(client)) {
            val why = if (clients.known(client) != null) "it is declared but not enabled here" else "no config declares it"
            refuse(
                "'${REGC.client}' is '$client', but $why. The clients present here are " +
                    "${clients.presentClients.joinToString(", ") { it.clientId }}.",
            )
        }
        val role = roleOf(config)
        if (!roleName.matches(role)) refuse("'${REGC.role}' is '$role', which is not a role's name.")
        if (role in privileged) {
            refuse(
                "'${REGC.role}' is '$role', which a registration may not grant: an administrator gets their roles " +
                    "from the auto-admin rule, never from this key.",
            )
        }
    }
}
