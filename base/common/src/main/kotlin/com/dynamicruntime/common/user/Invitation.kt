package com.dynamicruntime.common.user

import com.dynamicruntime.common.context.ENVGRP
import com.dynamicruntime.common.context.EnvVarDef
import com.dynamicruntime.common.context.KdrCxt
import com.dynamicruntime.common.home.HMENU
import com.dynamicruntime.common.http.request.ContextRoot
import com.dynamicruntime.common.node.NodeService
import com.dynamicruntime.common.util.jsonMap
import com.dynamicruntime.common.util.toJsonStr
import com.dynamicruntime.common.util.toOptLong
import com.dynamicruntime.common.util.toOptStr

/** Constants for invitations (issue #751). */
@Suppress("ConstPropertyName")
object INVITE {
    /**
     * The URL a browser reaches this deployment at, for the links a mail carries. A mailed link has to name
     * the deployment from outside, which no node knows on its own: behind a proxy the request's `Host` is the
     * proxy's, and a batch job has no request at all. Unset, [publicUrlFor] falls back to the request's own
     * scheme and host, which is right for a single node reached directly (local development, a test).
     */
    val publicUrl = EnvVarDef(
        "KDR_PUBLIC_URL", group = ENVGRP.application, defaultDoc = "unset (derived from the request)",
        description = "The URL a browser reaches this deployment at, e.g. 'https://app.example.com', used to " +
            "build the links that mailed invitations carry (issue #751). Unset (the default), a link is built " +
            "from the request's own scheme and host -- right for a node reached directly, wrong behind a proxy " +
            "that rewrites them, which is when this must be set. No trailing slash.",
    )


    /**
     * The deployment's public URL for a link in a mail: the configured [publicUrl], else the request's
     * `X-Forwarded-Proto` / `Host` (a proxy's convention for the original), else plain `http` and `Host`.
     * Null when there is neither a configuration nor a request to read, in which case a mail can only carry
     * the token and say where to enter it.
     */
    fun publicUrlFor(cxt: KdrCxt): String? {
        cxt.getEnvVar(publicUrl)?.trim()?.trimEnd('/')?.ifEmpty { null }?.let { return it }
        val request = cxt.request?.webRequest ?: return null
        val host = request.getRequestHeader("Host")?.trim()?.ifEmpty { null } ?: return null
        val scheme = request.getRequestHeader("X-Forwarded-Proto")?.trim()?.ifEmpty { null } ?: "http"
        return "$scheme://$host"
    }

    /** The invitation page's URL for [token] under [publicUrl], or the page's path alone when the deployment's URL is unknown. */
    fun invitationUrl(publicUrl: String?, token: String): String =
        "${publicUrl ?: ""}/${ContextRoot.wa}#page=${HMENU.pageInvite}&${HMENU.inviteTokenParam}=$token"
}

/**
 * What a mailed invitation carries (issue #751): which identity and which of its users it invites the reader
 * to claim, and when it stops being acceptable. Serialized compact and encrypted with the instance key, like
 * the session cookie and the form token, so the link cannot be forged or read -- the token *is* the proof
 * that the reader had the mail, which is why nothing else (no code) is asked of them.
 *
 * The user id is what is claimed; the identity id is checked against the user's on acceptance, so a user moved
 * to another identity in the meantime (nothing does that today) cannot be claimed by a stale link.
 */
class InvitationToken(val identityId: String, val userId: Long, val expireEpochMs: Long) {
    /** Encrypts this token to its wire string using the instance key. */
    fun encode(node: NodeService): String =
        node.encryptString(mapOf(K_IDENTITY to identityId, K_USER to userId, K_EXPIRE to expireEpochMs).toJsonStr(compact = true))

    companion object {
        private const val K_IDENTITY = "i"
        private const val K_USER = "u"
        private const val K_EXPIRE = "e"

        /** Decrypts and parses a wire token, or null if it is absent, malformed, or undecryptable. */
        fun decode(node: NodeService, token: String): InvitationToken? = try {
            val m = node.decryptString(token).jsonMap() ?: return null
            InvitationToken(
                m[K_IDENTITY].toOptStr() ?: return null,
                m[K_USER].toOptLong() ?: return null,
                m[K_EXPIRE].toOptLong() ?: return null,
            )
        } catch (_: Exception) {
            null
        }
    }
}
