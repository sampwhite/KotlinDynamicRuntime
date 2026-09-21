package com.dynamicruntime.common.user

import com.dynamicruntime.common.context.CL
import com.dynamicruntime.common.context.ENVGRP
import com.dynamicruntime.common.context.EnvVarDef
import com.dynamicruntime.common.context.KdrCxt
import com.dynamicruntime.common.home.HMENU
import com.dynamicruntime.common.http.request.ContextRoot
import com.dynamicruntime.common.node.NodeService
import com.dynamicruntime.common.util.isVariableName
import com.dynamicruntime.common.util.jsonMap
import com.dynamicruntime.common.util.toJsonStr
import com.dynamicruntime.common.util.toOptLong
import com.dynamicruntime.common.util.toOptStr

/** Constants for invitations (issue #751). */
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
        // Headers are the caller's to set. Only an administrator's request reaches here today, so the fallback
        // is tolerable, but a real deployment should not be relying on it -- say so where an operator reads.
        if (!cxt.instanceConfig.isTestInstance) {
            LogAuth.warn(cxt) {
                "${publicUrl.name} is unset; the mailed link is built from this request's Host ('$host'). " +
                    "Set it on a deployment behind a proxy, or wherever the request's host is not the public one."
            }
        }
        return "$scheme://$host"
    }

    /** The invitation page's URL for [token] under [publicUrl], or the page's path alone when the deployment's URL is unknown. */
    fun invitationUrl(publicUrl: String?, token: String): String =
        "${publicUrl ?: ""}/${ContextRoot.wa}#page=${HMENU.pageInvite}&${HMENU.inviteTokenParam}=$token"
}

/**
 * The key a claim names (issue #751): the address plus the client, persona, and personId, with the defaults
 * applied for a blank client (`public`) and persona (`member`). Built once from the typed values and carried
 * as an object -- never round-tripped through a joined string, which a `|` in an address's local part or in a
 * free-typed client would misalign.
 *
 * [isValid] says whether the three typed parts are ids at all: a client and persona within [maxPartLength]
 * and the id charset, and a personId within its own rules. A value that is not an id cannot name a user, and
 * -- the reason this is checked before anything else -- must never be **echoed into a mail**: the send is
 * anonymous and mails any address, so an unvalidated part would let a stranger put a sentence of their own
 * into a message the deployment sends from its own domain.
 */
class ClaimKey(address: String, client: String?, persona: String?, personId: String) {
    val address: String = address.trim()
    val client: String = client?.trim()?.ifEmpty { null } ?: CL.public
    val persona: String = persona?.trim()?.ifEmpty { null } ?: PERSONA.member
    val personId: String = personId.trim()

    /** Whether every typed part is an id; false means no user can match and nothing may be echoed. */
    val isValid: Boolean
        get() = isIdPart(client) && isIdPart(persona) && PERSONID.isValid(personId)

    /** What the code is computed over: every part, so a code for one user cannot serve another of the address. */
    val hashText: String get() = "$address|$client|$persona|$personId"

    /** The key's client and persona as a mail spells them: `client "hub" as Admin B`. Only for a valid key. */
    fun describe(): String = "client \"$client\" as ${PERSONA.typed(PERSONA.label(persona), personId)}"

    /** Whether [user] is the user this key names (a deleted one never is). */
    fun matches(user: AuthUserRow): Boolean =
        user.client == client && user.persona == persona && user.personId == personId && !user.isDeleted

    @Suppress("ConstPropertyName")
    companion object {
        /** The most a typed client or persona may be: ids are short, and an echoed value must stay one. */
        const val maxPartLength = 64

        private fun isIdPart(value: String): Boolean = value.length <= maxPartLength && value.isVariableName()
    }
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
