package com.dynamicruntime.common.test

import com.dynamicruntime.common.content.UIC
import com.dynamicruntime.common.content.MarkdownFragmentService
import com.dynamicruntime.common.content.FRAG
import com.dynamicruntime.common.context.KdrCxt
import com.dynamicruntime.common.context.UserProfile
import com.dynamicruntime.common.endpoint.HttpMethod
import com.dynamicruntime.common.endpoint.SchModule
import com.dynamicruntime.common.endpoint.schemaModule
import com.dynamicruntime.common.exception.EXC
import com.dynamicruntime.common.exception.KdrException
import com.dynamicruntime.common.mail.MailService
import com.dynamicruntime.common.schema.SCT
import com.dynamicruntime.common.app.APP
import com.dynamicruntime.common.user.ENVA
import com.dynamicruntime.common.user.EnvAuthRules
import com.dynamicruntime.common.user.UserService
import com.dynamicruntime.common.util.getOptStr
import com.dynamicruntime.common.util.toJsonListOfStrings
import com.dynamicruntime.common.util.toOptEnum
import com.dynamicruntime.common.util.toOptLong
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Instant
import com.dynamicruntime.common.http.request.ROLE
import com.dynamicruntime.common.http.request.RoleLadder

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
            field(TEP.level,
                "Access level for a freshly created user (ignored when the user already exists). One of the " +
                    "privilege ladder's rungs; each includes the ones below it. Defaults to `${ROLE.user}`.") {
                for (rung in RoleLadder.ordered) option(rung)
            }
            field(
                TEP.capabilities,
                "Extra roles for a freshly created user, beyond the level -- capabilities such as " +
                    "`${ROLE.allClients}`, which are orthogonal to the ladder and cannot be expressed by a level.",
            ) {
                type = SCT.array
                items { type = SCT.string }
            }
            field(TEP.failIfUserAlreadyExists,
                "Fail instead of logging in when a user with this email already exists.") {
                type = SCT.boolean
            }
            field(
                TEP.client,
                "Client to create the user in (ignored when the user already exists). Defaults to whatever " +
                    "the email address names, which for an ordinary address is the public client. A client " +
                    "this node does not carry is refused rather than quietly replaced.",
            )
        },
    ) { c, request ->
        val service = UserService.get(c)
        service.checkInit(c) // idempotent; ensures the handler is built
        service.authFormHandler.becomeUserByEmail(
            c,
            email = request[TEP.email] as String,
            level = request.getOptStr(TEP.level) ?: ROLE.user,
            capabilities = request[TEP.capabilities].toJsonListOfStrings(),
            failIfUserAlreadyExists = request[TEP.failIfUserAlreadyExists] == true,
            client = request.getOptStr(TEP.client),
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
        val mail = MailService.get(c)
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
            field(TCLK.op, "The clock operation to perform.", required = true) { options(ClockOp.entries) }
            field(TCLK.deltaMs, "For '${ClockOp.advance}': milliseconds to advance (negative rewinds).") { type = SCT.integer }
            field(TCLK.atMs, "For '${ClockOp.set}': the target time as epoch milliseconds.") { type = SCT.integer }
        },
    ) { c, req ->
        val clock = c.instanceConfig.clock
        // The op is choice-constrained above, so validation already rejected anything but a ClockOp name.
        val op = req[TCLK.op].toOptEnum<ClockOp>() ?: throw KdrException.mkInput("A valid '${TCLK.op}' is required.")
        when (op) {
            ClockOp.advance -> clock.advanceBy(
                (req[TCLK.deltaMs].toOptLong() ?: throw KdrException.mkInput("'${TCLK.deltaMs}' is required for '${ClockOp.advance}'.")).milliseconds,
            )
            ClockOp.set -> clock.setAbsolute(
                Instant.fromEpochMilliseconds(req[TCLK.atMs].toOptLong() ?: throw KdrException.mkInput("'${TCLK.atMs}' is required for '${ClockOp.set}'.")),
            )
            ClockOp.freeze -> clock.freeze()
            ClockOp.unfreeze -> clock.unfreeze()
            ClockOp.reset -> clock.reset()
        }
        mapOf(TCLK.instanceNowMs to clock.instanceNow().toEpochMilliseconds())
    }

    // Assert env auth for a browser session no edge vouched for (issue #360), so the env-authed UI can be
    // seen and driven before an edge server exists. A browser cannot attach a request header, so without this
    // the env-authed view is simply unreachable in a real browser.
    //
    // `forTestingOnly` because this GRANTS, unlike the app-level suppress endpoint, which only subtracts. Note
    // the fence here is only half of it: `EnvAuthRules` refuses the cookie outside a test instance as well,
    // because marking the endpoint stops the cookie being issued and does nothing to stop one being typed
    // into a browser.
    generalEndpoint(
        TENV.path,
        "Test-only: act env-authed as an address (assert), or stop pretending (clear).",
        HttpMethod.POST, outputRef = APP.envAuthStateType, forTestingOnly = true,
        inputFields = {
            field(TENV.op, "Whether to assert env auth for this session or clear the assertion.",
                required = true) { options(EnvAuthFixtureOp.entries) }
            field(TENV.email, "For '${EnvAuthFixtureOp.assert}': the address to act env-authed as.")
        },
    ) { c, req ->
        val op = req[TENV.op].toOptEnum<EnvAuthFixtureOp>()
            ?: throw KdrException.mkInput("A valid '${TENV.op}' is required.")
        val web = c.request?.webRequest
        var asserted: String? = null
        when (op) {
            EnvAuthFixtureOp.assert -> {
                val raw = req.getOptStr(TENV.email)
                    ?: throw KdrException.mkInput("'${TENV.email}' is required for '${EnvAuthFixtureOp.assert}'.")
                // Sanitized here as well as on the way back in, so a value this node would refuse to repeat is
                // rejected with a message now rather than silently ignored on the next request.
                asserted = EnvAuthRules.sanitizeAddress(raw)
                    ?: throw KdrException.mkInput("'${TENV.email}' is not an address this node will carry.")
                web?.addResponseCookie(ENVA.assertCookie, asserted, null)
            }
            // Clearing returns the session to whatever the channel really is -- NOT the same as suppressing,
            // which overrides a real env auth. They coincide only where no edge is in front.
            EnvAuthFixtureOp.clear ->
                web?.addResponseCookie(ENVA.assertCookie, "", Instant.fromEpochMilliseconds(0))
        }
        // The state for the NEXT request, as this one resolved its env auth before the cookie changed.
        val available = asserted != null || c.envAuthEmail != null
        mapOf(
            APP.isEnvAuthed to (available && !c.envAuthSuppressed),
            APP.envAuthSuppressible to (available && EnvAuthRules.suppressionOffered(c.instanceConfig)),
            // This fixture does not touch the debug cookie, so debug carries the session's current state
            // (issue #517).
            APP.envAuthDebug to (available && !c.envAuthSuppressed && c.envAuthDebug),
        )
    }

    // Two endpoints on ONE path, differing only by verb -- the shape issue #335 introduced for real at
    // `/gedra/formDoc`. Kept here as a fixture because the hazard is in the dispatcher's compiled-type memo
    // rather than in gedra: see [TVB] for what goes wrong when that memo is keyed by path alone.
    type(TVB.getType) {
        type = SCT.kObject
        description = "What the GET half of the verb fixture answers with."
        property(TVB.verb, "The verb that ran.", required = true)
        property(TVB.getOnly, "Echo of the GET-only input field.", required = true)
    }

    type(TVB.deleteType) {
        type = SCT.kObject
        description = "What the DELETE half of the verb fixture answers with."
        property(TVB.verb, "The verb that ran.", required = true)
        property(TVB.deleteOnly, "Echo of the DELETE-only input field.", required = true)
    }

    generalEndpoint(
        TVB.path,
        "Test-only: the GET half of a two-verbs-one-path fixture.",
        HttpMethod.GET, outputRef = TVB.getType, forTestingOnly = true,
        inputFields = {
            field(TVB.getOnly, "A field only the GET accepts.", required = true)
        },
    ) { _, req ->
        mapOf(TVB.verb to HttpMethod.GET.name, TVB.getOnly to req[TVB.getOnly])
    }

    generalEndpoint(
        TVB.path,
        "Test-only: the DELETE half of a two-verbs-one-path fixture.",
        HttpMethod.DELETE, outputRef = TVB.deleteType, forTestingOnly = true,
        inputFields = {
            field(TVB.deleteOnly, "A field only the DELETE accepts.", required = true)
        },
    ) { _, req ->
        mapOf(TVB.verb to HttpMethod.DELETE.name, TVB.deleteOnly to req[TVB.deleteOnly])
    }

    // A content element that pulls a fragment on the frontend (issue #505, Phase 3). The `text` is a template
    // whose `@t("portal.welcome")` resolves against the `sample` file's copy -- which the element names via
    // `fileId`/`buildId`, since a content string is not itself a fragment file and so has no ambient context.
    // The debug "fragment" tool fetches this, fetches that file's copy, and renders the resolved result.
    type("FragmentDemoElement") {
        type = SCT.kObject
        property(UIC.fileId, "The fragment file this element's text resolves its @t pulls against.", required = true)
        property(UIC.buildId, "That file's build id, so the frontend can fetch its copy.", required = true)
        property(
            TEP.demoText,
            "A template string, its backend %{@t(...)} pull already resolved, still carrying a frontend " +
                "${'$'}{@t(...)} pull and a plain substitution.",
            required = true,
        )
    }
    generalEndpoint(
        TEP.fragmentDemo,
        "Demo: a content element whose text pulls a fragment on the backend and one on the frontend (#505).",
        HttpMethod.GET, outputRef = "FragmentDemoElement", forTestingOnly = true,
    ) { c, _ ->
        val service = MarkdownFragmentService.get(c)
        // Both passes in one string: the backend resolves `%{...}` now -- a three-part `fileId.namespace.key`
        // across the registry -- and leaves `${...}` for the frontend to resolve against this file's copy. So
        // the shipped text still carries the frontend pull, its backend pull already substituted.
        val text = service.backendPass(
            c,
            $$"""Backend pulled *%{@t("$${FRAG.sample}.email.subject")}*; frontend pulls **${@t("portal.welcome")}** — plain value: ${demoVar}.""",
        )
        mapOf(
            UIC.fileId to FRAG.sample,
            UIC.buildId to (MarkdownFragmentService.fragmentBuildId(c, FRAG.sample) ?: ""),
            TEP.demoText to text,
        )
    }
}
