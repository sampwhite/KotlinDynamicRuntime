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
     * Session cookie by which a caller **suppresses** their own env auth (issue #360) -- the production
     * downgrade, so someone who came in through an edge can see the application as an ordinary user does.
     *
     * Honored in every environment, because suppressing only ever **subtracts**: the worst it can do is show
     * its holder less than they are entitled to. That is why it needs no fence, and why it is a different
     * cookie from [assertCookie], which needs one.
     */
    const val suppressCookie = "kdrEnvOff"

    /**
     * Session cookie by which the `forTestingOnly` fixture **asserts** env auth no edge granted, so the
     * env-authed UI can be driven in a browser before an edge exists.
     *
     * **Honored only on a test instance, and that check belongs to the reader, not the endpoint.** Fencing
     * the fixture endpoint stops the cookie being *issued*; it does nothing to stop one being *typed into a
     * browser*. A reader that honored this anywhere would hand env auth to anyone who could set a cookie, and
     * the `forTestingOnly` marking would be worth precisely nothing.
     *
     * Kept as its own cookie rather than a value of [suppressCookie] for exactly that reason: a reader that
     * must inspect a value before deciding whether a fence applies is one somebody later simplifies into a
     * bypass.
     */
    const val assertCookie = "kdrEnvFixture"

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
     * Everything a request's env auth amounts to: who an edge vouched for, and whether the session is choosing
     * to act on it (issue #360).
     *
     * [rawHeader] is the header value exactly as received and [cookies] the request's cookies; the caller
     * reads both off the request so this stays transport-neutral, and directly unit-testable.
     */
    fun resolve(
        config: KdrInstanceConfig,
        rawHeader: String?,
        cookies: Map<String, String>,
    ): EnvAuthState {
        // Suppression is read first and unconditionally: it subtracts, so no environment needs protecting
        // from it, and it applies even where the assertion below is refused.
        //
        // Tested by VALUE, not presence. Clearing a cookie is done by re-sending it empty with an expiry in
        // the past, so a cleared cookie arrives as `kdrEnvOff=` rather than not arriving at all -- and a
        // `containsKey` check reads that as still suppressed, leaving no way to restore. (Found by the test
        // that restores; a browser drops it eventually, which would have made this intermittent rather than
        // absent.) The same reasoning covers the assert cookie below, where an empty value fails to sanitize.
        val suppressed = !cookies[ENVA.suppressCookie].isNullOrEmpty()
        if (!isTrusted(config)) {
            return EnvAuthState(null, suppressed)
        }
        // The fixture's assertion stands in for the header, so it passes the same trust gate -- and then one
        // more that the header does not need. See ENVA.assertCookie for why this check lives here.
        val asserted = if (config.isTestInstance) sanitizeAddress(cookies[ENVA.assertCookie]) else null
        return EnvAuthState(sanitizeAddress(rawHeader) ?: asserted, suppressed)
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

/**
 * A request's env auth as two independent facts (issue #360), because one boolean cannot carry both.
 *
 * [email] is the **truth** -- who an edge vouched for -- and is what reaches the log line, whatever the session
 * has chosen to display. Suppression is a display choice and never a cloak: someone browsing in the downgraded
 * view is still that person, and an audited perimeter that let them act unattributed would be pointless.
 *
 * [isEffective] is what everything else should read. [isAvailable] exists for exactly one consumer -- the
 * control that restores a suppressed session, which has to remain visible while suppressed or there is no way
 * back.
 */
class EnvAuthState(val email: String?, val suppressed: Boolean) {
    /** Whether an edge vouched for this request at all, regardless of what the session is acting as. */
    val isAvailable: Boolean get() = email != null

    /** Whether the request is *acting* env-authed: vouched for, and not suppressed by its own session. */
    val isEffective: Boolean get() = isAvailable && !suppressed

    companion object {
        /** No edge, no suppression -- what a request on a node not behind an edge always resolves to. */
        val none = EnvAuthState(null, suppressed = false)
    }
}
