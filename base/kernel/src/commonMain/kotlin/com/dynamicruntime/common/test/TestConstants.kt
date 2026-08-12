package com.dynamicruntime.common.test

/**
 * Wire constants for the **fixture** endpoints (issue #125): shared in the KMP kernel so the endpoint that
 * defines them and the helpers/clients that call them (e.g. `TestUser` in base:common) use the same strings.
 * These endpoints exist only on a test instance (see `KdrInstanceConfig.isTestInstance`).
 *
 * They sit under the `fixture` section root rather than `test` (issue #270). The old name described the
 * *gate* -- absent outside a test instance -- while the root is meant to describe the *purpose*: something
 * that exists so a capability can be exercised, by an automated test or by a developer by hand. Nothing here
 * is ever shown to a client, which is what separates it from `demo`.
 */
@Suppress("ConstPropertyName")
object TEP {
    /** Create-or-find a user by email and immediately become them (a `forTestingOnly` POST). */
    const val becomeUser = "/fixture/becomeUser"

    const val email = "email"
    const val level = "level"
    const val capabilities = "capabilities"
    const val failIfUserAlreadyExists = "failIfUserAlreadyExists"

    /**
     * Recent emails a test instance captured instead of sending (issue #158), so a test or the local frontend
     * can read a verification code back. A `forTestingOnly` GET; the fields/type names are in [TSE].
     */
    const val simulatedEmails = "/fixture/simulatedEmails"
}

/**
 * The `forTestingOnly` clock-control endpoint (issue #160): travels the instance clock so a test (or a manual
 * browser session) can force an expiry or rate-limit window without a real wait.
 */
@Suppress("ConstPropertyName")
object TCLK {
    const val path = "/fixture/clock"

    /** Request: which operation to perform -- one of the op values below. */
    const val op = "op"

    /** Request: for [ClockOp.advance], the milliseconds to advance (negative rewinds). */
    const val deltaMs = "deltaMs"

    /** Request: for [ClockOp.set], the target time as epoch milliseconds. */
    const val atMs = "atMs"

    /** Response: the instance clock's value after the operation, as epoch milliseconds. */
    const val instanceNowMs = "instanceNowMs"

    /** The response schema type name. */
    const val stateType = "ClockState"
}

/** The operations [TCLK.op] accepts (issue #160). The names are the wire values; the schema choice list and
 *  the endpoint's `when` are both driven off this enum. */
enum class ClockOp { advance, set, freeze, unfreeze, reset }

/** Fields and type names of the [TEP.simulatedEmails] endpoint. */
@Suppress("ConstPropertyName")
object TSE {
    const val emails = "emails"
    const val to = "to"
    const val subject = "subject"
    const val text = "text"

    const val emailType = "SimulatedEmail"
    const val emailsType = "SimulatedEmails"
}
