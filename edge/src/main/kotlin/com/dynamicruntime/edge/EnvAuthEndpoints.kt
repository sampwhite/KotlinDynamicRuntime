package com.dynamicruntime.edge

import com.dynamicruntime.common.context.KdrCxt
import com.dynamicruntime.common.context.UserProfile
import com.dynamicruntime.common.endpoint.HttpMethod
import com.dynamicruntime.common.endpoint.SchModule
import com.dynamicruntime.common.endpoint.schemaModule
import com.dynamicruntime.common.exception.EXC
import com.dynamicruntime.common.exception.KdrException
import com.dynamicruntime.common.node.NodeService
import com.dynamicruntime.common.schema.SCT
import com.dynamicruntime.common.user.AddressRules
import com.dynamicruntime.common.user.GoogleAuthConfig
import com.dynamicruntime.common.util.getOptStr
import kotlin.time.Instant

/** Wire vocabulary for the Env Auth login. */
@Suppress("ConstPropertyName")
object EAEP {
    /**
     * Where a browser posts the Google credential.
     *
     * Under the **`auth`** section, which `RequestService.anonSections` already serves anonymously -- so this
     * needs no change to `base/common` and no new section. Deliberately *not* the application's
     * `/auth/login/google`: until role profiling lands, an edge still loads the application's auth endpoints,
     * and two logins answering the same path would be indistinguishable to a caller.
     */
    const val login = "/auth/env/login"

    /** Request field: the Google ID token (a JWT) the browser's sign-in hands back. */
    const val googleCredential = "googleCredential"

    /** Response field: the address now signed in. */
    const val email = "email"

    /** The response schema type name. */
    const val sessionType = "EnvAuthSession"
}

/**
 * The Env Auth login (issue #386): the one thing an anonymous caller may do on an edge.
 *
 * Google **Sign-In ID-token verification**, not a server-side authorization-code flow -- the browser obtains
 * the JWT and posts it here. That is why there is no client secret, no registered redirect URI, no `state`,
 * and no callback endpoint, and why a plain server-rendered page is enough to log somebody in.
 */
fun envAuthSchema(cxt: KdrCxt): SchModule = schemaModule(cxt, "auth") {
    type(EAEP.sessionType) {
        type = SCT.kObject
        property(EAEP.email, "The address now signed in for this session.", required = true)
    }

    generalEndpoint(
        EAEP.login,
        "Sign in to this environment with a Google account whose address is in the permitted domain.",
        HttpMethod.POST, outputRef = EAEP.sessionType,
        inputFields = {
            field(EAEP.googleCredential, "The Google ID token from the browser's sign-in.", required = true)
        },
    ) { c, req ->
        val credential = req.getOptStr(EAEP.googleCredential)
            ?: throw KdrException.mkInput("A Google credential is required.")
        val verifier = GoogleAuthConfig.mkVerifier(c.instanceConfig)
            ?: throw KdrException("Google sign-in is not configured on this node.")
        val token = verifier.verify(c, credential)

        // An unverified address is a claim Google has not stood behind, so it cannot open the gate.
        val email = token.email?.takeIf { token.emailVerified }
            ?: throw KdrException(refusal, code = EXC.notAuthorized, sensitive = true)

        // `isControlledDomain` rather than `isAutoAdminAddress`, and the difference is the question being
        // asked. This one is "is this address one of ours", which is exactly what a perimeter wants; the other
        // adds the `+`-tag exclusion, which exists so nobody self-grants admin through a tagged address and
        // has nothing to say about entering an environment. Reused rather than rewritten either way: it
        // matches the domain part only, so `notacme.com` cannot pass for `acme.com`.
        if (!AddressRules.isControlledDomain(c, email)) {
            throw KdrException(refusal, code = EXC.notAuthorized, sensitive = true)
        }

        val node = NodeService.get(c) ?: throw KdrException("The node service is not available.")
        val expireMs = c.now().toEpochMilliseconds() + ENVAUTH.sessionMillis(c.instanceConfig)
        // Written through the request's WebRequest -- the transport-neutral seam -- so it behaves identically
        // under a browser and the in-process test client. Safe here because a handler runs before the response
        // is sent; a cookie set afterwards would be dropped.
        c.request?.webRequest?.addResponseCookie(
            ENVAUTH.cookie, EnvAuthCookie(email, expireMs).encode(node), Instant.fromEpochMilliseconds(expireMs),
        )
        // Bound for this request too, so the response is already the signed-in view rather than one round trip
        // behind it.
        c.envAuthEmail = email
        c.bindToUserProfile(UserProfile.envAuthed(email))
        LogEdge.info(c) { "Env auth granted to $email." }
        mapOf(EAEP.email to email)
    }
}

/**
 * The one thing every refusal says.
 *
 * Deliberately identical for "no verified address" and "wrong domain", and marked sensitive so a deployment
 * that obfuscates replaces even this: a caller learning *which* check they tripped learns which domain opens
 * the gate, which is the one fact a probe wants. `GoogleIdTokenVerifier` already takes the same line with its
 * own failures.
 */
private const val refusal = "That account may not enter this environment."
