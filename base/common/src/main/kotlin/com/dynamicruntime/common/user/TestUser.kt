package com.dynamicruntime.common.user

import com.dynamicruntime.common.context.KdrCxt
import com.dynamicruntime.common.context.UPF
import com.dynamicruntime.common.endpoint.EP
import com.dynamicruntime.common.http.request.TestHttpClient
import com.dynamicruntime.common.test.TEP
import com.dynamicruntime.common.util.toJsonMapOrEmpty
import com.dynamicruntime.common.util.toOptLong

/**
 * An authenticated in-process test client (issue #125): a [TestHttpClient] already logged in as a specific
 * user, plus the [cxt] it was built from and the [userInfo] of who it is acting as. Created via [create], which
 * calls the `forTestingOnly` `/test/becomeUser` endpoint -- so the client's cookie jar carries the session on
 * every subsequent call, and requests through it are made *as that user*.
 *
 * It lives in core (not test source), like [TestHttpClient] itself, because putting a few test helpers in core
 * pays off once we run more involved multi-user "simulations" against the in-process pipeline. Convenience
 * methods are added here as those needs surface; the request helpers below unwrap the standard `results`
 * envelope so a caller reads response data directly.
 */
class TestUser(val client: TestHttpClient, val cxt: KdrCxt, val userInfo: Map<String, Any?>) {

    /** The numeric id of the user this client is acting as. */
    val userId: Long get() = userInfo[UPF.userId].toOptLong() ?: -1L

    /** GETs [path] as this user; returns the response's `results` map. */
    fun getData(path: String, args: Map<String, Any?>? = null): Map<String, Any?> =
        client.sendJsonGetRequest(path, args)[EP.results].toJsonMapOrEmpty()

    /** POSTs [data] to [path] as this user; returns the response's `results` map. */
    fun postData(path: String, data: Map<String, Any?>): Map<String, Any?> =
        client.sendJsonPostRequest(path, data)[EP.results].toJsonMapOrEmpty()

    companion object {
        /**
         * Creates (or finds) the user with primary contact [email] and returns a [TestUser] authenticated as
         * them, built on [cxt]'s instance. [admin] grants the `admin` role to a *freshly created* user (ignored
         * when the user already exists). Requires the deployment to allow test endpoints (unit tests do).
         */
        fun create(cxt: KdrCxt, email: String, admin: Boolean = false): TestUser {
            val client = TestHttpClient(cxt.instanceConfig)
            val userInfo = client.sendJsonPostRequest(
                TEP.becomeUser,
                mapOf(TEP.email to email, TEP.grantAdmin to admin),
            )[EP.results].toJsonMapOrEmpty()
            return TestUser(client, cxt, userInfo)
        }
    }
}
