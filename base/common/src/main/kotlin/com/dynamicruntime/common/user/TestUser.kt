package com.dynamicruntime.common.user

import com.dynamicruntime.common.context.KdrCxt
import com.dynamicruntime.common.context.UPF
import com.dynamicruntime.common.endpoint.EP
import com.dynamicruntime.common.http.request.TestHttpClient
import com.dynamicruntime.common.node.NodeService
import com.dynamicruntime.common.test.TEP
import com.dynamicruntime.common.util.toJsonListOfMaps
import com.dynamicruntime.common.util.toJsonMapOrEmpty
import com.dynamicruntime.common.util.toOptLong
import com.dynamicruntime.common.util.toOptStr
import com.dynamicruntime.common.http.request.ROLE

/**
 * An authenticated in-process test client (issue #125): a [TestHttpClient] already logged in as a specific
 * user, plus the [cxt] it was built from and the [userInfo] of who it is acting as. The client's cookie jar
 * carries the session on every subsequent call, so requests through it are made *as that user*.
 *
 * Two ways in, for two different questions:
 *  - [create] uses the `forTestingOnly` `/test/becomeUser` endpoint to provision rows directly -- the fast path
 *    when a test just needs *an authenticated user*.
 *  - [register] walks the real self-service verification-code flow -- the right instrument when the thing under
 *    test is what an *ordinary registration* grants (auto-admin, initial roles, contact validation).
 *
 * It lives in core (not test source), like [TestHttpClient] itself, because putting a few test helpers in core
 * pays off once we run more involved multi-user "simulations" against the in-process pipeline. The request
 * helpers unwrap the standard envelope so a caller reads response data directly.
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

    /** GETs [path] as this user; returns the response's `items` list (empty when absent). */
    fun getItems(path: String, args: Map<String, Any?>? = null): List<Map<String, Any?>> =
        client.sendJsonGetRequest(path, args)[EP.items].toJsonListOfMaps()

    /** This user's *current* roles, read live from `/auth/self/info` (not the possibly-stale [userInfo]). */
    fun selfRoles(): List<String> = rolesOf(getData(AEP.selfInfo))

    /** This user's *current* primary organization, read live, or null when they have none (issue #225). */
    fun selfOrg(): String? = getData(AEP.selfInfo)[UPF.org].toOptStr()

    /**
     * Sends to [path] as this user and asserts the call **failed** with [status] (the error envelope's status
     * field, issue #103), returning the envelope for any further checks. A GET when [data] is null, otherwise a
     * POST. Throws [AssertionError] -- reported as a test failure -- on a mismatch or an unexpected success;
     * [TestUser] is core, so it cannot reach for a test-framework matcher.
     */
    fun expectError(status: Int, path: String, data: Map<String, Any?>? = null): Map<String, Any?> {
        val env = if (data == null) client.sendJsonGetRequest(path) else client.sendJsonPostRequest(path, data)
        val actual = (env[EP.status] as? Number)?.toInt()
        if (actual != status) {
            throw AssertionError("Expected '$path' to fail with status $status but got ${actual ?: "a success response"}.")
        }
        return env
    }

    @Suppress("ConstPropertyName")
    companion object {
        private const val emailContactType = "email"

        /**
         * Creates (or finds) the user with primary contact [email] and returns a [TestUser] authenticated as
         * them, built on [cxt]'s instance. [capabilities] adds non-rung roles (see [createFullAdmin]), and
         * [level] places a *freshly created* user on the privilege ladder --
         * `ROLE.user` (the default), `ROLE.operator` or `ROLE.admin`, each including the levels below it --
         * and is ignored when the user already exists, since you become whoever is already there. Requires the
         * deployment to allow test endpoints (unit tests do).
         */
        fun create(
            cxt: KdrCxt,
            email: String,
            level: String = ROLE.user,
            capabilities: List<String> = emptyList(),
        ): TestUser {
            val client = TestHttpClient(cxt.instanceConfig)
            val userInfo = client.sendJsonPostRequest(
                TEP.becomeUser,
                mapOf(TEP.email to email, TEP.level to level, TEP.capabilities to capabilities),
            )[EP.results].toJsonMapOrEmpty()
            return TestUser(client, cxt, userInfo)
        }

        /**
         * A **full-scope** administrator: [ROLE.admin] plus [ROLE.allClients] (issue #225). The `admin`
         * section requires the capability, not merely the level, so this is what a test wanting to exercise it
         * needs -- and it exists as a named helper because no endpoint can grant `allClients` (anti-escalation
         * refuses reach the granter lacks, and nobody holds it to begin with), which makes provisioning the
         * only way in.
         *
         * Use [create] with `level = ROLE.admin` and no capabilities for the *scoped* administrator instead --
         * the two must stay separable, since the difference between them is the thing under test.
         */
        fun createFullAdmin(cxt: KdrCxt, email: String): TestUser =
            create(cxt, email, level = ROLE.admin, capabilities = listOf(ROLE.allClients))

        /**
         * Registers a brand-new user through the real self-service verification-code flow (createToken →
         * sendVerify → createInitial → setLoginData) and returns a [TestUser] logged in as them. Unlike
         * [create], which provisions rows directly, this exercises the ordinary registration path -- so it is
         * the right instrument when the thing under test is what a *registration* grants. [name] is the username.
         */
        fun register(cxt: KdrCxt, email: String, name: String): TestUser {
            val client = TestHttpClient(cxt.instanceConfig)
            val token = client.sendJsonGetRequest(AEP.createToken)[EP.results].toJsonMapOrEmpty()[AFLD.formAuthToken]
                as? String ?: throw IllegalStateException("createToken returned no form token.")
            client.sendJsonPostRequest(
                AEP.newContactSendVerify,
                mapOf(AFLD.contactAddress to email, AFLD.contactType to emailContactType, AFLD.formAuthToken to token),
            )
            // Computed the way the server does -- via the instance's own NodeService key -- because a
            // white-box test runs in-process and legitimately holds the node it is driving. An external
            // caller, which is the threat, has only HTTP and cannot reach the key.
            val node = NodeService.get(cxt) ?: error("NodeService is required to compute a verify code.")
            val code = node.computeVerifyCode(token, email)
            val userId = client.sendJsonPutRequest(
                AEP.createInitial,
                mapOf(
                    AFLD.contactAddress to email, AFLD.contactType to emailContactType,
                    AFLD.formAuthToken to token, AFLD.verifyCode to code,
                ),
            )[EP.results].toJsonMapOrEmpty()[AFLD.userId].toOptLong()
                ?: throw IllegalStateException("createInitial returned no user id.")
            val userInfo = client.sendJsonPutRequest(
                AEP.setLoginData,
                mapOf(AFLD.userId to userId, AFLD.username to name, AFLD.formAuthToken to token, AFLD.verifyCode to code),
            )[EP.results].toJsonMapOrEmpty()
            return TestUser(client, cxt, userInfo)
        }

        /** The role names in a user/userInfo map (from self-info, an admin list, or a role-change response). */
        fun rolesOf(userMap: Map<String, Any?>): List<String> =
            (userMap[UPF.roles] as? List<*>)?.map { it.toString() } ?: emptyList()
    }
}
