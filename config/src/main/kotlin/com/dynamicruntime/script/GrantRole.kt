package com.dynamicruntime.script

import com.dynamicruntime.common.context.ACFG
import com.dynamicruntime.common.context.ENV
import com.dynamicruntime.common.context.KdrCxt
import com.dynamicruntime.common.context.KdrInstanceConfig
import com.dynamicruntime.common.http.request.ROLE
import com.dynamicruntime.common.logging.LogSetup
import com.dynamicruntime.common.user.AuthUserRow
import com.dynamicruntime.common.user.UserService
import com.dynamicruntime.kdn.Startup
import kotlin.system.exitProcess

/**
 * Grants or revokes a role on an existing user, from the command line:
 *
 * ```sh
 * kdr-run com.dynamicruntime.script.GrantRoleKt <loginId> [role] [--revoke] [--list]
 * ```
 *
 * `loginId` is a username or an email address (the same identifier a login accepts) and `role` defaults to
 * `admin`. This is the operator's escape hatch for the chicken-and-egg problem the admin endpoints have -- they
 * require an administrator to reach -- and the counterpart to the auto-admin email domain
 * (`KDR_ADMIN_EMAIL_DOMAIN`, see `AdminRules`): the domain rule suits a deployment whose operators share an
 * email domain, while this suits granting one specific account, or taking the role back.
 *
 * It is a **Kotlin** script, per the code guide: `bin/kdr-run` handles the irreducible shell part (finding the
 * fat jar, keeping it fresh) and every decision lives here. It boots the runtime the ordinary way
 * ([Startup.mkBootCxt]) but starts no HTTP server, so it works against a deployment's real database with the
 * server stopped -- and, because it goes through [UserService] rather than raw SQL, it cannot write a row shape
 * the application would not recognize.
 *
 * The environment decides which database it edits: it must match the deployment's (`KDR_ENV`, `KDR_DB_*`). It
 * refuses to run against an in-memory database, where the grant would be discarded on exit.
 */
object GrantRole {
    /** Flags the script accepts. */
    @Suppress("ConstPropertyName")
    object GRF {
        const val revoke = "--revoke"
        const val list = "--list"
    }

    /** Exit codes: 0 success, 1 usage/among-us failure. */
    const val failureExit = 1

    fun run(args: List<String>): Int {
        val flags = args.filter { it.startsWith("-") }.toSet()
        val positional = args.filter { !it.startsWith("-") }
        val unknownFlags = flags - setOf(GRF.revoke, GRF.list)
        if (unknownFlags.isNotEmpty()) {
            return usage("Unknown flag(s): ${unknownFlags.joinToString(" ")}")
        }
        val loginId = positional.getOrNull(0) ?: return usage("A loginId (username or email) is required.")
        val role = positional.getOrNull(1) ?: ROLE.admin
        val revoke = GRF.revoke in flags

        val cxt = bootCxt()
        if (cxt.instanceConfig.get(ACFG.inMemoryOnly) == true) {
            return fail(
                "Refusing to run against an in-memory database -- the change would be discarded on exit.\n" +
                    "Set KDR_IN_MEMORY_ONLY=false (and the KDR_DB_* variables) for the deployment you mean to edit.",
            )
        }
        val service = UserService.get(cxt) ?: return fail("The user service is unavailable; cannot continue.")

        if (GRF.list in flags) {
            return listUsers(cxt, service, loginId)
        }

        val row = service.queryByLoginId(cxt, loginId)
            ?: return fail("No user matches '$loginId' (tried username, then primary email).")
        val had = row.roles.contains(role)
        when {
            revoke && !had -> return report(row, "already lacks the '$role' role; nothing to do")
            !revoke && had -> return report(row, "already has the '$role' role; nothing to do")
            revoke -> row.roles = row.roles.filter { it != role }
            else -> row.roles = row.roles + role
        }
        service.updateUser(cxt, row)
        val verb = if (revoke) "Revoked" else "Granted"
        println("$verb '$role' ${if (revoke) "from" else "to"} ${describe(row)}.")
        println("Roles are now: ${row.roles.joinToString(", ")}.")
        if (!revoke) {
            println("It takes effect on their next request; they do not need to log in again.")
        }
        return 0
    }

    /** Boots the runtime without a server, under an instance named for this script. */
    private fun bootCxt(): KdrCxt {
        val preBoot = KdrInstanceConfig.preBootLoadConfig()
        val cxt = KdrCxt.mkSimpleCxt("preBoot", preBoot)
        LogSetup.initFromEnv(getEnv = cxt::getEnvVar)
        val env = cxt.getEnvVar(envVarName) ?: ENV.local
        return Startup.mkBootCxt("grantRole", env, preBoot.entries().toMap())
    }

    /** `--list`: shows the users matching [search], so an operator can find the right loginId. */
    private fun listUsers(cxt: KdrCxt, service: UserService, search: String): Int {
        val rows = service.listUsers(cxt, search, listLimit)
        if (rows.isEmpty()) {
            println("No users match '$search'.")
            return 0
        }
        for (row in rows) {
            println(describe(row) + " roles=[${row.roles.joinToString(", ")}] enabled=${row.enabled}")
        }
        return 0
    }

    private fun describe(row: AuthUserRow): String = "user ${row.userId} <${row.primaryId}> (${row.username})"

    private fun report(row: AuthUserRow, note: String): Int {
        println("${describe(row)} $note.")
        return 0
    }

    private fun fail(message: String): Int {
        System.err.println(message)
        return failureExit
    }

    private fun usage(problem: String): Int = fail(
        "$problem\n\n" +
            "Usage: kdr-run com.dynamicruntime.script.GrantRoleKt <loginId> [role] [${GRF.revoke}] [${GRF.list}]\n" +
            "  loginId    a username or email address; with ${GRF.list}, a substring to search for\n" +
            "  role       the role to grant or revoke (default '${ROLE.admin}')\n" +
            "  ${GRF.revoke}   take the role away instead of granting it\n" +
            "  ${GRF.list}     list matching users and their roles; change nothing",
    )

    /** How many matches `--list` prints. */
    private const val listLimit = 50

    /** The environment-name variable, read to boot the same instance the deployment runs. */
    private const val envVarName = "KDR_ENV"
}

fun main(args: Array<String>) {
    exitProcess(GrantRole.run(args.toList()))
}
