package com.dynamicruntime.edge

import com.dynamicruntime.common.context.ENV
import com.dynamicruntime.common.context.KdrInstanceConfig
import com.dynamicruntime.common.node.NodeService
import com.dynamicruntime.common.util.jsonMap
import com.dynamicruntime.common.util.toJsonStr
import com.dynamicruntime.common.util.toOptLong
import com.dynamicruntime.common.util.toOptStr

/** Constants for the Env Auth session: the cookie, and how long it lasts. */
@Suppress("ConstPropertyName")
object ENVAUTH {
    /**
     * The edge's session cookie.
     *
     * **A distinct name from `kdrAuth` is load-bearing, not cosmetic.** Once the edge proxies, a KDR backend
     * reached through it sets its own `kdrAuth` on the same host, and that must arrive at the backend
     * untouched -- so the edge has to strip exactly its own cookie and pass the rest through. A shared name
     * would make the two indistinguishable on the wire. Cedar reached the same conclusion by putting "Env"
     * into the name.
     */
    const val cookie = "kdrEnvAuth"

    /** Instance-config key overriding [sessionMillis]; set directly by tests. */
    const val sessionMillisKey = "envAuthSessionMillis"

    /** Env var that defaults [sessionMillisKey] when the config option is unset. */
    const val sessionMillisEnvVar = "KDR_ENV_AUTH_SESSION_MILLIS"

    /** Twelve hours, the production default. */
    const val prodSessionMillis = 12L * 3600 * 1000

    /** Two days, the default everywhere else. */
    const val defaultSessionMillis = 2L * 24 * 3600 * 1000

    /**
     * How long an Env Auth session lasts: the config option, then the env var, then **12 hours in `prod` and
     * two days elsewhere** -- the same three-level shape `RequestHandler.obfuscateSensitiveErrors` uses.
     *
     * **This timeout is the only revocation the edge has**, which is why it is so much shorter than the
     * application's thirty-day session. That one is safe at thirty days because `refreshActingRoles` overrules
     * it from the user's row within seconds, so disabling an account bites immediately. An edge has no user
     * store and so no refresh: whoever holds a valid cookie keeps their access until it expires, and nothing
     * can take it back. Twelve hours in production is roughly "log in each working day", which is cheap given
     * an already-signed-in Google account.
     *
     * Absolute rather than sliding, deliberately: a cap that is extended on use is not a cap. If it chafes,
     * an idle-timeout on top is the follow-up, and this number need not change.
     */
    fun sessionMillis(config: KdrInstanceConfig): Long =
        (config.get(sessionMillisKey) as? Long)
            ?: config.getEnvVar(sessionMillisEnvVar)?.toLongOrNull()
            ?: if (config.env == ENV.prod) prodSessionMillis else defaultSessionMillis
}

/**
 * The contents of the Env Auth session cookie: **who** got through the perimeter, and when that stops being
 * true. Serialized compactly and encrypted with the instance key, so a browser can neither read nor forge it.
 *
 * The key being the *instance's* rather than a node's is what lets an edge be load-balanced: a session opened
 * against one edge node is honored by its siblings.
 *
 * Not a `UserAuthCookie`, and it cannot be: that type requires a `userId` and refuses to decode without one,
 * while an env-authed caller has no user row anywhere. The address *is* the identity here -- it is what
 * reaches the log line, and what the forwarded header will carry inward.
 *
 * No roles are stored. The application's cookie carries them because a user's roles are theirs; here every
 * caller who cleared the domain gate gets the same level, so recording it per-session would only create a way
 * for an old cookie to disagree with the current rule.
 */
class EnvAuthCookie(val email: String, val expireEpochMs: Long) {
    /** Encrypts this cookie to its wire string using the instance key (shared by every node). */
    fun encode(node: NodeService): String =
        node.encryptString(mapOf(K_EMAIL to email, K_EXPIRE to expireEpochMs).toJsonStr(compact = true))

    companion object {
        private const val K_EMAIL = "e"
        private const val K_EXPIRE = "x"

        /** Decrypts and parses a wire cookie, or null if it is absent, malformed, or undecryptable. */
        fun decode(node: NodeService, cookie: String): EnvAuthCookie? = try {
            val m = node.decryptString(cookie).jsonMap() ?: return null
            val email = m[K_EMAIL].toOptStr() ?: return null
            val expire = m[K_EXPIRE].toOptLong() ?: return null
            EnvAuthCookie(email, expire)
        } catch (_: Exception) {
            // Undecryptable means a cookie this node did not issue -- a forgery, or one from before a key
            // rotation. Either way it is not a session, and treating it as absent is the whole response.
            null
        }
    }
}
