package com.dynamicruntime.common.test

import com.dynamicruntime.common.context.KdrCxt
import com.dynamicruntime.common.context.UserProfile
import com.dynamicruntime.common.endpoint.HttpMethod
import com.dynamicruntime.common.endpoint.SchModule
import com.dynamicruntime.common.endpoint.schemaModule
import com.dynamicruntime.common.exception.EXC
import com.dynamicruntime.common.exception.KdrException
import com.dynamicruntime.common.mail.MailService
import com.dynamicruntime.common.schema.SCT
import com.dynamicruntime.common.user.UserService
import com.dynamicruntime.common.util.getOptStr
import com.dynamicruntime.common.util.toOptLong
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Instant

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

    // The recent emails a test instance captured instead of sending (issue #158), so a test or the local
    // frontend can read a verification code back without real mail. `forTestingOnly` like the rest of this
    // module; the defensive `!useSimulatedEmail` check stays, though a test instance simulates by default.
    type(TSE.emailType) {
        type = SCT.kObject
        property(TSE.to, "The recipient address.", required = true)
        property(TSE.subject, "The subject line.", required = true)
        property(TSE.text, "The full message text (it contains the verification code).", required = true)
    }
    type(TSE.emailsType) {
        type = SCT.kObject
        property(TSE.emails, "The recent simulated emails, most recent first.", required = true) {
            type = SCT.array
            items { ref(TSE.emailType) }
        }
    }
    generalEndpoint(
        TEP.simulatedEmails,
        "Test-only: recent emails captured instead of sent (available when email is simulated).",
        HttpMethod.GET, outputRef = TSE.emailsType, forTestingOnly = true,
        inputFields = { field(TSE.to, "Only include emails addressed to this recipient.") },
    ) { c, req ->
        val mail = MailService.get(c) ?: throw KdrException("MailService is not available.")
        if (!mail.useSimulatedEmail) {
            throw KdrException("Recent emails are only available when email is simulated.", code = EXC.notFound)
        }
        val to = req.getOptStr(TSE.to)
        val emails = mail.recentSentEmails()
            .filter { to == null || it.to == to }
            .map { mapOf(TSE.to to it.to, TSE.subject to it.subject, TSE.text to it.text) }
        mapOf(TSE.emails to emails)
    }

    // Travel the instance clock (issue #160), so a test or a manual session can force an expiry or a rate-limit
    // window without a real wait. Mutates the per-instance clock every context reads through `now()` /
    // `instanceNow()`; returns the resulting instance time. `forTestingOnly`, so it is absent outside a test
    // instance -- there is no way to move a real deployment's clock.
    type(TCLK.stateType) {
        type = SCT.kObject
        property(TCLK.instanceNowMs, "The instance clock's value after the operation, epoch milliseconds.",
            required = true) { type = SCT.integer }
    }
    generalEndpoint(
        TCLK.path,
        "Test-only: travel the instance clock (advance / set / freeze / unfreeze / reset).",
        HttpMethod.POST, outputRef = TCLK.stateType, forTestingOnly = true,
        inputFields = {
            field(TCLK.op, "One of: advance, set, freeze, unfreeze, reset.", required = true)
            field(TCLK.deltaMs, "For 'advance': milliseconds to advance (negative rewinds).") { type = SCT.integer }
            field(TCLK.atMs, "For 'set': the target time as epoch milliseconds.") { type = SCT.integer }
        },
    ) { c, req ->
        val clock = c.instanceConfig.clock
        when (val op = req[TCLK.op] as? String) {
            TCLK.advance -> clock.advanceBy(
                (req[TCLK.deltaMs].toOptLong() ?: throw KdrException.mkInput("'${TCLK.deltaMs}' is required for '${TCLK.advance}'.")).milliseconds,
            )
            TCLK.set -> clock.setAbsolute(
                Instant.fromEpochMilliseconds(req[TCLK.atMs].toOptLong() ?: throw KdrException.mkInput("'${TCLK.atMs}' is required for '${TCLK.set}'.")),
            )
            TCLK.freeze -> clock.freeze()
            TCLK.unfreeze -> clock.unfreeze()
            TCLK.reset -> clock.reset()
            else -> throw KdrException.mkInput("Unknown clock op '$op'.")
        }
        mapOf(TCLK.instanceNowMs to clock.instanceNow().toEpochMilliseconds())
    }
}
