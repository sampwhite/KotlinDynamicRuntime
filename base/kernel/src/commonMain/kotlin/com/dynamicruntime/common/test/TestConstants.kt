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
     * The client to create the user in (issue #352). Absent means whatever the email address says, which for
     * an ordinary address is `public` -- so every call written before this existed means what it did.
     */
    const val client = "client"

    /**
     * Recent emails a test instance captured instead of sending (issue #158), so a test or the local frontend
     * can read a verification code back. A `forTestingOnly` GET; the fields/type names are in [TSE].
     */
    const val simulatedEmails = "/fixture/simulatedEmails"

    /**
     * A content element demonstrating a **frontend** `@t` fragment pull (issue #505): a `forTestingOnly` GET
     * returning `{fileId, buildId, text}`, where `text` is a template that pulls a fragment. The debug page
     * that renders it fetches that file's copy and resolves the pull on the frontend -- the Phase 3 vertical
     * slice. `fileId`/`buildId` reuse the `UIC` wire keys; the text field is [demoText].
     */
    const val fragmentDemo = "/fixture/fragmentDemo"

    /** The template-string field of the [fragmentDemo] content element. */
    const val demoText = "text"

    /** A data variable the [fragmentDemo] text substitutes, to show `@t` and `${...}` in one string. */
    const val demoVar = "demoVar"
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
@Suppress("EnumEntryName")
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

/**
 * The `forTestingOnly` env-auth fixture (issue #360): asserts env auth for a browser session that no edge
 * vouched for, so the env-authed UI can be seen and driven before an edge server exists.
 *
 * A browser cannot attach a request header, so without this there is no way to reach the env-authed view in a
 * real browser at all. The fence is the one [TEP.becomeUser] already sits behind, which fabricates a fully
 * authenticated admin session -- asserting env auth is strictly less powerful than that.
 */
@Suppress("ConstPropertyName")
object TENV {
    const val path = "/fixture/envAuth"

    /** Request: which operation to perform -- an [EnvAuthFixtureOp] name. */
    const val op = "op"

    /** Request: for [EnvAuthFixtureOp.assert], the address to act env-authed as. */
    const val email = "email"
}

/**
 * The operations [TENV.op] accepts (issue #360).
 *
 * [clear] drops the pretense and returns the session to whatever the channel really is. It is **not**
 * `EnvAuthOp.suppress`, which overrides a real env auth; the two coincide only where no edge is in front,
 * which is the case that makes conflating them tempting and wrong.
 */
@Suppress("EnumEntryName")
enum class EnvAuthFixtureOp { assert, clear }

/**
 * Two `forTestingOnly` endpoints sharing **one path** and differing only by verb (issue #335), each with its
 * own input field and its own output type.
 *
 * They exist to hold a defect down. Compiled endpoint input/output types are memoized, and that memo was keyed
 * by path alone -- so the first endpoint on a path to be called compiled its schemas, and every other endpoint
 * on that same path was then validated against *them*. Nothing noticed while no two endpoints shared a URL.
 *
 * The fields are deliberately **disjoint**: input types are closed to undeclared properties, so if the memo
 * ever collides again, each verb starts rejecting the other's field and the test says so. The output types
 * differ for the same reason, on the response side.
 */
@Suppress("ConstPropertyName")
object TVB {
    const val path = "/fixture/verb"

    /** Request: the only field `GET /fixture/verb` accepts. */
    const val getOnly = "getOnly"

    /** Request: the only field `DELETE /fixture/verb` accepts. */
    const val deleteOnly = "deleteOnly"

    /** Response: which verb actually ran, so a test can tell the two apart. */
    const val verb = "verb"

    const val getType = "VerbFixtureGet"
    const val deleteType = "VerbFixtureDelete"
}
