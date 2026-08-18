package com.dynamicruntime.common.user

import com.dynamicruntime.common.context.ACFG
import com.dynamicruntime.common.context.KdrInstanceConfig

/** Constants for the environment-auth ("env auth") header contract. */
@Suppress("ConstPropertyName")
object ENVA {
    /**
     * The header an edge server sets on a request it has already authenticated, carrying the **full** email
     * address of the person who got through the perimeter.
     *
     * The whole address rather than the local part: prior art dropped the `@domain` suffix because it was
     * always the same, which leaves the receiving side re-attaching a domain from a rule free to drift from
     * whatever the edge actually matched. Namespaced and plainly ours, deliberately not `X-Forwarded-User` or
     * its relatives, which other infrastructure also sets.
     */
    const val header = "X-Kdr-Env-Email"

    /** Env var that defaults [ACFG.trustEnvAuthHeader] when the config option is unset. */
    const val trustEnvAuthHeaderEnvVar = "KDR_TRUST_ENV_AUTH_HEADER"

    /**
     * Longest address accepted, matching the 254-character maximum of an SMTP path. Generous on purpose: the
     * bound exists to keep an attacker-supplied string out of a log line unbounded, not to second-guess what
     * a real address may look like, and a legitimate address silently refused would be far worse.
     */
    const val maxAddressLength = 254

    /**
     * Characters permitted beyond letters and digits. [ADMR.atChar] separates the parts; the rest occur in
     * real addresses. Reuses `ADMR`'s definition of `@` rather than declaring a second one -- the two rules
     * read the same addresses, and one character with two spellings is one too many.
     */
    const val extraAddressChars = "@.+_-"
}

/**
 * The one answer to "did this request arrive through an authenticated edge, and as whom" (issue #348).
 *
 * **Env auth is a property of the channel, not of the user.** Two independent facts about a request: how it
 * arrived, and who is acting. All four combinations are legitimate -- the common one being an env-authed
 * channel carrying an ordinarily logged-in user -- so this never becomes a role on `UserProfile`. Roles answer
 * who you are; this answers how you got here. It rides on `KdrCxt.envAuthEmail`, beside the other request-scoped
 * channel facts.
 *
 * Everything reads the answer through here rather than touching [ENVA.header] directly. That is what lets the
 * mechanism become a **signed assertion** later -- the edge encrypting the address with a key shared through the
 * `node` topic's auth-config table -- without a single consumer changing.
 */
object EnvAuthRules {
    /**
     * Whether this node believes [ENVA.header] at all.
     *
     * A node **cannot verify** that the header came from an edge: any client able to reach its port can set
     * one, and in local development there is no edge at all. The real guarantee is a network property -- a
     * private subnet, a security group admitting only the edge -- which the application has no way to check.
     * So it is *asserted by configuration* rather than pretended-to by a runtime test.
     *
     * Notably **not** `NodeService.checkIsInternalAddress`, which looks apt and is ruled out by its own
     * comment: trusted/internal addresses relax limits and gate expensive APIs, and are explicitly not an auth
     * bypass. An IP filter does not prove a request came from the edge, and a check that pretends to verify is
     * worse than one that admits it cannot.
     *
     * Defaults to [KdrInstanceConfig.isTestInstance], reusing the fence that already governs the other
     * development-only surfaces rather than inventing a second notion of "a development build" (the reasoning
     * recorded for `showErrorDetail`/`allowDebugPages`). That fence is audited: a node claiming to be a test
     * instance outside `local`/`unit` refuses to start, so the default cannot reach a real environment. The
     * config option and env var override it in **either** direction, so a test can exercise the untrusted path.
     */
    fun isTrusted(config: KdrInstanceConfig): Boolean =
        (config.get(ACFG.trustEnvAuthHeader) as? Boolean)
            ?: config.getEnvBool(ENVA.trustEnvAuthHeaderEnvVar)
            ?: config.isTestInstance

    /**
     * The env-authed address for a request, or null when this node does not trust the header, none was sent,
     * or the value is not one this node will repeat.
     *
     * [rawHeader] is the header value exactly as received; the caller reads it off the request so this stays
     * transport-neutral (and directly unit-testable).
     */
    fun resolveEnvEmail(config: KdrInstanceConfig, rawHeader: String?): String? {
        if (!isTrusted(config)) {
            return null
        }
        return sanitizeAddress(rawHeader)
    }

    /**
     * The address as this node will carry and log it, or null when it is not one worth repeating.
     *
     * **This is a log-safety check before it is anything else.** The value is attacker-supplied on any node
     * that trusts the header, and it is destined for log lines -- so a carriage return in it forges log
     * entries, and an unbounded one floods them. The same treatment `RequestHandler`'s `appId`/`traceId` get,
     * and for the same reason, widened only by the characters an address actually needs.
     *
     * A rejected value degrades the request to "not env-authed" rather than failing it: this is an assertion
     * about the channel, and a malformed one is no assertion, not an error the caller can act on.
     *
     * Lower-cased, since the address is compared and logged rather than displayed back.
     *
     * The shape check is deliberately minimal -- an `@` with something either side. It does **not** test the
     * address against the admin email domain: that is the edge's decision, and a second copy of the rule here
     * would be free to drift from it. This node trusts the edge's answer or ignores the header; it never
     * second-guesses it.
     */
    fun sanitizeAddress(raw: String?): String? {
        val trimmed = raw?.trim()?.lowercase() ?: return null
        if (trimmed.isEmpty() || trimmed.length > ENVA.maxAddressLength) {
            return null
        }
        if (!trimmed.all { it.isLetterOrDigit() || it in ENVA.extraAddressChars }) {
            return null
        }
        val at = trimmed.indexOf(ADMR.atChar)
        // One `@`, with a non-empty local part and a non-empty domain.
        if (at <= 0 || at != trimmed.lastIndexOf(ADMR.atChar) || at == trimmed.length - 1) {
            return null
        }
        return trimmed
    }
}
