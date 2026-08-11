package com.dynamicruntime.common.user

import com.dynamicruntime.common.node.NodeService
import com.dynamicruntime.common.util.jsonMap
import com.dynamicruntime.common.util.toJsonListOfStrings
import com.dynamicruntime.common.util.toJsonStr
import com.dynamicruntime.common.util.toOptLong
import com.dynamicruntime.common.util.toOptStr

/** Cookie names and auth timeouts. */
@Suppress("ConstPropertyName")
object AUTHC {
    /** The session (auth) cookie: an encrypted [UserAuthCookie]. */
    const val authCookie = "kdrAuth"

    /** The device cookie: a stable per-browser GUID used to recognize a device (dn's login-source cookie). */
    const val deviceCookie = "kdrDevice"

    /** How long a session auth cookie is valid, in milliseconds (30 days). */
    const val sessionMillis = 30L * 24 * 3600 * 1000

    /**
     * How long a device stays "familiar" (trusted for password login) after a verification-code login, in
     * milliseconds (30 days). Every code login refreshes it; password login rides it but never extends it.
     */
    const val deviceTrustMillis = 30L * 24 * 3600 * 1000

    /** How long a form auth token is valid before a fresh one is needed, in milliseconds (15 minutes). */
    const val formTokenMillis = 15L * 60 * 1000
}

/**
 * The contents of the session auth cookie: who the user is and when the session expires. Serialized as a
 * compact JSON map and encrypted with the node's key (via [NodeService]), so the client cannot read or forge
 * it. A per-request check of [expireEpochMs] bounds the session; the actual roles/client are trusted from the
 * (encrypted) cookie for the fast path -- no database hit on every request.
 *
 * The primary organization is deliberately **not** here (issue #225). Nothing needs it: every section that
 * scopes is a gated one, and `refreshActingRoles` rebuilds the profile from the row before the gate, so a
 * cookie value would be overwritten before any read saw it. It becomes a real question when content carries
 * an org and writes on an *anonymous* section stamp it -- that path is never refreshed, which is exactly why
 * `client` is here. At that point it has to arrive together with an answer about staleness: a thirty-day
 * cookie would otherwise keep stamping the org a user had when they logged in, long after an administrator
 * moved them. Ported from dn's `UserAuthCookie`,
 * pared down to what verify-code login needs.
 */
class UserAuthCookie(
    val userId: Long,
    val client: String,
    val roles: List<String>,
    val expireEpochMs: Long,
) {
    /** Encrypts this cookie to its wire string using the node key. */
    fun encode(node: NodeService): String =
        node.encryptString(
            mapOf(K_USER to userId, K_CLIENT to client, K_ROLES to roles, K_EXPIRE to expireEpochMs)
                .toJsonStr(compact = true),
        )

    companion object {
        private const val K_USER = "u"
        private const val K_CLIENT = "c"
        private const val K_ROLES = "r"
        private const val K_EXPIRE = "e"

        /** Decrypts and parses a wire cookie string, or null if it is absent, malformed, or undecryptable. */
        fun decode(node: NodeService, cookie: String): UserAuthCookie? = try {
            val m = node.decryptString(cookie).jsonMap() ?: return null
            val userId = m[K_USER].toOptLong() ?: return null
            val client = m[K_CLIENT].toOptStr() ?: return null
            val roles = m[K_ROLES].toJsonListOfStrings()
            val expire = m[K_EXPIRE].toOptLong() ?: return null
            UserAuthCookie(userId, client, roles, expire)
        } catch (_: Exception) {
            null
        }
    }
}
