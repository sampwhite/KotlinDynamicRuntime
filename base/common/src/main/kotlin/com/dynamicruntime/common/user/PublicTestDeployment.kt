package com.dynamicruntime.common.user

import com.dynamicruntime.common.context.ACFG
import com.dynamicruntime.common.context.ENV
import com.dynamicruntime.common.context.ENVGRP
import com.dynamicruntime.common.context.EnvVarDef
import com.dynamicruntime.common.context.KdrCxt
import com.dynamicruntime.common.context.KdrInstanceConfig
import com.dynamicruntime.common.exception.EXC
import com.dynamicruntime.common.exception.KdrException
import com.dynamicruntime.common.exception.KdrMsg
import com.dynamicruntime.common.node.NodeUtil

/**
 * What a public test deployment does for a caller **without** env auth when they try to sign in or register
 * (issue #1150): [PTD.publicSignInEnvVar].
 */
@Suppress("EnumEntryName")
enum class PublicSignIn {
    /** The sign-in flows refuse them, saying the deployment is for testing and development only. The default. */
    blocked,

    /** They sign in as on a real deployment: their mail is transmitted for real, and never captured. */
    realMail,
}

/** The settings of a public test deployment (issue #1150). */
object PTD {
    /** Env var that opts a test instance into being reachable by the public: see [PublicTestDeployment]. */
    val publicTestDeploymentEnvVar = EnvVarDef(
        "KDR_PUBLIC_TEST_DEPLOYMENT", group = ENVGRP.application, defaultDoc = "false",
        description = "Makes this test instance a **public test deployment** (issue #1150): one the general public " +
            "can reach, clearly marked as for testing and development only. It is what permits a test instance in " +
            "`dev` -- otherwise one refuses to start outside `local`/`unit`. The test surface is already only for " +
            "an env-authed caller (#1149); under this, env auth is hardened: `KDR_TRUST_ENV_AUTH_HEADER` must be " +
            "set explicitly (true only behind an edge that strips the header from outside traffic), and env auth " +
            "is assumed only on a node listening on loopback alone (`KDR_BIND_ADDRESS`). A caller without env auth gets what `KDR_PUBLIC_SIGN_IN` says, and every page and " +
            "mail says the deployment is for testing.",
    )

    /** Env var choosing what a caller without env auth may do: a [PublicSignIn] name. */
    val publicSignInEnvVar = EnvVarDef(
        "KDR_PUBLIC_SIGN_IN", group = ENVGRP.application, defaultDoc = PublicSignIn.blocked.name,
        description = "On a public test deployment, what a caller without env auth may do about signing in " +
            "(issue #1150): `${PublicSignIn.blocked}` (the default) refuses them every sign-in and registration " +
            "flow; `${PublicSignIn.realMail}` lets them sign in as on a real deployment, their mail transmitted " +
            "for real through the provider and never captured, so nobody can read it back. Real mail is an opt-in, " +
            "so a deployment never starts mailing the public because an API key is present -- and a public form " +
            "then mails any address typed into it, which it relies on the code-sending throttles " +
            "(`AuthRateLimiter`, per source IP and per address) to bound.",
    )

    /** Env var that lets an administrator's login carry env auth on an ephemeral instance. */
    val envAuthFromAdminLoginEnvVar = EnvVarDef(
        "KDR_ENV_AUTH_FROM_ADMIN_LOGIN", group = ENVGRP.application, defaultDoc = "false",
        description = "On a public test deployment with **no edge** -- an ephemeral instance, whose database is " +
            "refreshed or discarded -- a signed-in user is env-authed when their address is on the admin domain " +
            "(`KDR_ADMIN_EMAIL_DOMAIN`) and they hold `allClients`, read live (issue #1150). Such an address may " +
            "also sign in, and is mailed its codes for real, whatever `KDR_PUBLIC_SIGN_IN` says. Refused unless " +
            "`KDR_PUBLIC_TEST_DEPLOYMENT` is on, `KDR_TRUST_ENV_AUTH_HEADER` is off (there is no edge to strip " +
            "it), and the admin domain is set and is not a consumer mail domain, every address of which would be " +
            "an administrator's.",
    )
}

/**
 * A **public test deployment** (issue #1150): a test instance the general public can reach, clearly marked as for
 * testing and development only. Env-authed people get the test surface (#1149); everyone else uses the application
 * as on a real deployment, or -- by default -- is refused sign-in.
 *
 * Three settings, in [PTD], and the boot refuses every combination that would make the test surface reachable by
 * someone an edge did not vouch for ([checkBoot]). The other pieces read [isOn]: the mail a caller causes
 * (`MailService.deliveryFor`), the sign-in flows ([requireSignInOpen]), the app config's band and the mails' line
 * saying the deployment is for testing, and env auth from an administrator's login (`EnvAuthRules.fromAdminLogin`).
 */
object PublicTestDeployment {
    /** Whether this node is a public test deployment: [PTD.publicTestDeploymentEnvVar]. */
    fun isOn(config: KdrInstanceConfig): Boolean = config.getEnvBool(PTD.publicTestDeploymentEnvVar) == true

    /** What a caller without env auth may do about signing in: [PTD.publicSignInEnvVar], `blocked` when unset. */
    fun publicSignIn(config: KdrInstanceConfig): PublicSignIn =
        config.getEnvVar(PTD.publicSignInEnvVar)?.trim()?.ifEmpty { null }
            ?.let { v -> PublicSignIn.entries.firstOrNull { it.name == v } }
            ?: PublicSignIn.blocked

    /** Whether an administrator's login carries env auth here: [PTD.envAuthFromAdminLoginEnvVar], on a public test deployment. */
    fun envAuthFromAdminLogin(config: KdrInstanceConfig): Boolean =
        isOn(config) && config.getEnvBool(PTD.envAuthFromAdminLoginEnvVar) == true

    /**
     * Whether the caller of [cxt] may sign in -- or register, or claim, or accept an invitation -- as [address]
     * (issue #1150). Anywhere but a public test deployment, yes. On one: an env-authed caller, anyone when
     * [PublicSignIn.realMail] is chosen, and -- under [envAuthFromAdminLogin] -- an address on the admin domain,
     * since signing in is how such a person comes to be env-authed and, before it, everyone is without.
     */
    fun signInOpenTo(cxt: KdrCxt, address: String): Boolean {
        val config = cxt.instanceConfig
        if (!isOn(config) || cxt.isEnvAuthEffective) return true
        if (publicSignIn(config) == PublicSignIn.realMail) return true
        return envAuthFromAdminLogin(config) && AddressRules.isAdminDomain(cxt, address)
    }

    /** Refuses the sign-in flow for [address] unless [signInOpenTo] allows it, saying this is a test deployment. */
    fun requireSignInOpen(cxt: KdrCxt, address: String) {
        if (!signInOpenTo(cxt, address)) {
            throw KdrException.mkMsg(KdrMsg(AFRAG.auth, AERR.ns, AERR.testDeploymentSignIn), code = EXC.notAuthorized)
        }
    }

    /**
     * Refuses to start a node whose settings would let someone no edge vouched for reach the test surface (issue
     * #1150). Called where the test-instance fence always was (`SchemaService.checkInit`), and it is that fence:
     *
     * - a test instance outside `local`/`unit` refuses to start, unless it is a public test deployment in `dev`;
     * - a public test deployment must be a test instance, must say explicitly whether it trusts the env-auth
     *   header, must not assume env auth, and must name a [PublicSignIn] it knows;
     * - env auth from an administrator's login needs a public test deployment, no header trust, and an admin domain
     *   that is set and is not a consumer mail domain.
     */
    fun checkBoot(config: KdrInstanceConfig) {
        fun refuse(why: String): Nothing = throw KdrException("Refusing to start: $why")
        val on = isOn(config)
        val env = config.env
        if (config.isTestInstance && env != ENV.local && env != ENV.unit && !(on && env == ENV.dev)) {
            refuse(
                "this is a test instance in the '$env' environment. Test-only affordances must NEVER be exposed outside " +
                    "'local' or 'unit' -- unset ${KdrInstanceConfig.testInstanceEnvVar} and turn off inMemoryOnly, or run " +
                    "in a local/unit environment, or, in 'dev', make it a public test deployment " +
                    "(${PTD.publicTestDeploymentEnvVar}).",
            )
        }
        val namedSignIn = config.getEnvVar(PTD.publicSignInEnvVar)?.trim()?.ifEmpty { null }
        if (on) {
            if (!config.isTestInstance) refuse("${PTD.publicTestDeploymentEnvVar} is on, but this is not a test instance.")
            val trust = (config.get(ACFG.trustEnvAuthHeader) as? Boolean) ?: config.getEnvBool(ENVA.trustEnvAuthHeaderEnvVar)
            if (trust == null) {
                refuse(
                    "a public test deployment must say whether it trusts the env-auth header: set " +
                        "${ENVA.trustEnvAuthHeaderEnvVar} -- true only behind an edge that strips the header from outside " +
                        "traffic, or anyone could send it and be env-authed.",
                )
            }
            // Assuming env auth grants it to every request with no forwarded-for address -- every request that did not
            // come through the proxy (issue #1245). Safe only when nothing but this machine can send one: the server
            // listening on loopback alone, so the guarantee is the node's own rather than a firewall's.
            if (EnvAuthRules.assumesEnvAuth(config) && !NodeUtil.bindsLoopbackOnly(config)) {
                refuse(
                    "a public test deployment may assume env auth (${ENVA.assumeEnvAuthEnvVar}) only when it listens on " +
                        "loopback alone (${NodeUtil.bindAddress}=127.0.0.1); otherwise every request that reaches it " +
                        "directly, from anywhere, would be env-authed.",
                )
            }
            if (namedSignIn != null && PublicSignIn.entries.none { it.name == namedSignIn }) {
                refuse("${PTD.publicSignInEnvVar} is '$namedSignIn'; it takes one of ${PublicSignIn.entries.joinToString()}.")
            }
        }
        if (config.getEnvBool(PTD.envAuthFromAdminLoginEnvVar) == true) {
            if (!on) refuse("${PTD.envAuthFromAdminLoginEnvVar} is on, but this is not a public test deployment (${PTD.publicTestDeploymentEnvVar}).")
            if (EnvAuthRules.isTrusted(config)) {
                refuse("${PTD.envAuthFromAdminLoginEnvVar} is for a node with no edge, so ${ENVA.trustEnvAuthHeaderEnvVar} must be off.")
            }
            val domain = AdminRules.adminEmailDomain(config)
                ?: refuse("${PTD.envAuthFromAdminLoginEnvVar} needs an admin domain (${ADMR.adminEmailDomainEnvVar}).")
            if (domain in ADR.gmailDomains) {
                refuse(
                    "${PTD.envAuthFromAdminLoginEnvVar} with the admin domain '$domain', a consumer mail domain: every " +
                        "address there is auto-granted administration, and so would be env-authed.",
                )
            }
        }
    }
}
