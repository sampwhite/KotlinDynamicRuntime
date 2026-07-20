package com.dynamicruntime.common.test

import com.dynamicruntime.common.context.KdrCxt
import com.dynamicruntime.common.context.UserProfile
import com.dynamicruntime.common.endpoint.HttpMethod
import com.dynamicruntime.common.endpoint.SchModule
import com.dynamicruntime.common.endpoint.schemaModule
import com.dynamicruntime.common.exception.KdrException
import com.dynamicruntime.common.schema.SCT
import com.dynamicruntime.common.user.UserService

/**
 * Test-only endpoints (issue #125): conveniences that make automated and manual testing easier. Every endpoint
 * here is marked `forTestingOnly`, so [com.dynamicruntime.common.startup.SchemaService] drops the whole set
 * from the store unless the deployment allows test endpoints -- and a deployment that allows them outside a
 * `local`/`unit` environment fails startup. So nothing here can ever reach a real environment.
 *
 * [TEP.becomeUser] creates (or finds) a user by email and logs you straight in as them -- no verification code
 * or password -- which is what lets a test or a simulation get an authenticated session in one call. The
 * `user` package's `TestUser` wraps exactly that: it calls this endpoint through a `TestHttpClient` (whose
 * cookie jar captures the session) and hands back an authenticated client.
 */
fun testSchema(cxt: KdrCxt): SchModule = schemaModule(cxt, "test") {
    // The endpoint returns the acting user's info, so the shared UserInfo type is pulled into this module.
    UserProfile.defineInfoType(this)

    generalEndpoint(
        TEP.becomeUser,
        "Test-only: create (or find) a user by email and immediately log in as them.",
        HttpMethod.POST, outputRef = UserProfile.infoTypeName, forTestingOnly = true,
        inputFields = {
            field(TEP.email, "The email address (primary contact) of the user to become.", required = true)
            field(TEP.grantAdmin,
                "On a freshly created user, also grant the `admin` role (ignored when the user already exists).") {
                type = SCT.boolean
            }
            field(TEP.failIfUserAlreadyExists,
                "Fail instead of logging in when a user with this email already exists.") {
                type = SCT.boolean
            }
        },
    ) { c, request ->
        val service = UserService.get(c) ?: throw KdrException("UserService is not available.")
        service.checkInit(c) // idempotent; ensures the handler is built
        service.authFormHandler.becomeUserByEmail(
            c,
            email = request[TEP.email] as String,
            grantAdmin = request[TEP.grantAdmin] == true,
            failIfUserAlreadyExists = request[TEP.failIfUserAlreadyExists] == true,
        )
    }
}
