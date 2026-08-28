package com.dynamicruntime.edge

import com.dynamicruntime.common.context.KdrCxt
import com.dynamicruntime.common.context.UserProfile
import com.dynamicruntime.common.endpoint.HttpMethod
import com.dynamicruntime.common.endpoint.SchModule
import com.dynamicruntime.common.endpoint.schemaModule
import com.dynamicruntime.common.exception.EXC
import com.dynamicruntime.common.exception.KdrException
import com.dynamicruntime.common.http.request.CKI
import com.dynamicruntime.common.node.NodeService
import com.dynamicruntime.common.schema.SCT
import com.dynamicruntime.common.user.AddressRules
import com.dynamicruntime.common.user.GoogleAuthConfig
import com.dynamicruntime.common.util.getOptStr
import kotlin.time.Instant

/** Paths the edge serves as content rather than as endpoints. */
@Suppress("ConstPropertyName")
object EDGEP {
    /** The sign-in page, under the edge's content root -- the whole anonymous surface of an edge. */
    const val loginPage = "/login"

    /**
     * Query parameter on [loginPage] noting the caller has just signed out (issue #486): presence-only, so its
     * value is unread. The sign-in page shows a brief "you have been signed out" note when it is present -- a
     * grace note that tells a caller their logout took rather than leaving them on a bare sign-in they might
     * read as a session that simply expired.
     */
    const val loggedOutParam = "loggedOut"
}

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

    /**
     * Where a browser clears the env-auth session (issue #486) -- the symmetric counterpart to [login], under
     * the same **`auth`** section and so anonymous for the same reason: it only ever subtracts, and the worst
     * a caller can do to themselves is have to sign in again. A GET, matching the application's own logout
     * (`AEP.logout`), which is likewise a cookie-clearing GET rather than a POST.
     */
    const val logout = "/auth/env/logout"

    /** Request field: the Google ID token (a JWT) the browser's sign-in hands back. */
    const val googleCredential = "googleCredential"

    /** Response field: the address now signed in. */
    const val email = "email"

    /** The response schema type name. */
    const val sessionType = "EnvAuthSession"

    /** The [logout] response schema type name: an empty acknowledgement, since the frontend acts on the call's arguments, not the body. */
    const val ackType = "EnvAuthLogoutAck"
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

    type(EAEP.ackType) { type = SCT.kObject }

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

        // A verified address on the domain is still not evidence Google is authoritative for it. A Google
        // consumer account can be registered against one of our addresses -- it presents email_verified=true and
        // no `hd`, and would otherwise pass the check above. Require the signed `hd` to name the configured
        // domain, so a stale alias, forwarding rule or catch-all cannot open the gate (issue #429).
        if (!AddressRules.isGoogleAuthoritative(c, token.hostedDomain)) {
            throw KdrException(refusal, code = EXC.notAuthorized, sensitive = true)
        }

        val node = NodeService.get(c)
        val expireMs = c.now().toEpochMilliseconds() + ENVAUTH.sessionMillis(c.instanceConfig)
        // Written through the request's WebRequest -- the transport-neutral seam -- so it behaves identically
        // under a browser and the in-process test client. Safe here because a handler runs before the response
        // is sent; a cookie set afterwards would be dropped.
        // SameSite=Strict for the perimeter cookie (issue #431): nothing should be arriving at an edge
        // cross-site, so its session cookie need not ride a cross-site request. The cookie is set and read
        // same-origin, so the login flow is unaffected; the app's `kdrAuth` stays Lax for its Google redirect.
        c.request?.webRequest?.addResponseCookie(
            ENVAUTH.cookie, EnvAuthCookie(email, expireMs).encode(node), Instant.fromEpochMilliseconds(expireMs),
            CKI.strict,
        )
        // Bound for this request too, so the response is already the signed-in view rather than one round trip
        // behind it.
        c.envAuthEmail = email
        c.bindToUserProfile(UserProfile.envAuthed(email))
        LogEdge.info(c) { "Env auth granted to $email." }
        mapOf(EAEP.email to email)
    }

    generalEndpoint(
        EAEP.logout,
        "Clear this environment's sign-in, dropping the perimeter cookie -- the counterpart to signing in.",
        HttpMethod.GET, outputRef = EAEP.ackType,
    ) { c, _ ->
        // Clear by re-issuing the cookie empty and already expired, the pattern the app's logout and the
        // variant-behavior endpoint both use. Written through the request's WebRequest for the same reason the
        // login sets it there, and with the same SameSite=Strict so the attributes match the cookie being
        // cleared. A caller with no cookie clears nothing and still succeeds -- logout is idempotent.
        c.request?.webRequest?.addResponseCookie(
            ENVAUTH.cookie, "", Instant.fromEpochMilliseconds(0), CKI.strict,
        )
        // The bound identity is left as it was for the rest of this request -- the frontend navigates away to
        // the sign-in page next, so nothing renders from it, and the cleared cookie is what ends the session
        // on the following request. Logged from the context so the line names who was signed out.
        c.envAuthEmail?.let { LogEdge.info(c) { "Env auth cleared for $it." } }
        emptyMap<String, Any?>()
    }
}

/**
 * The one thing every refusal says.
 *
 * Deliberately identical for "no verified address", "wrong domain", and "Google is not authoritative for the
 * address" (issue #429), and marked sensitive so a deployment that obfuscates replaces even this: a caller
 * learning *which* check they tripped learns which domain opens the gate, which is the one fact a probe wants.
 * `GoogleIdTokenVerifier` already takes the same line with its own failures.
 */
private const val refusal = "That account may not enter this environment."
