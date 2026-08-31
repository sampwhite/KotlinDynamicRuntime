package com.dynamicruntime.common.http.request

import com.dynamicruntime.common.context.KdrCxt
import com.dynamicruntime.common.endpoint.ETAG
import com.dynamicruntime.common.endpoint.HttpMethod
import com.dynamicruntime.common.endpoint.SchModule
import com.dynamicruntime.common.endpoint.schemaModule
import com.dynamicruntime.common.exception.KdrException
import com.dynamicruntime.common.schema.SCT
import com.dynamicruntime.common.util.getOptStr
import kotlin.time.Duration.Companion.days
import kotlin.time.Instant

/** Endpoint paths, fields and the state type for the request-variant facility (issue #471). */
@Suppress("ConstPropertyName")
object VEP {
    /** The path prefix both escape-hatch endpoints share -- also what `VariantBehavior.apply` exempts. */
    const val pathRoot = "/fixture/variant/"
    const val set = "${pathRoot}set"
    const val clear = "${pathRoot}clear"

    /** The state the endpoints report back: what is active now, and what the node offers. */
    const val stateType = "VariantState"
    /** The scenario selected after the call -- empty string when none / after a clear. */
    const val active = "active"
    /** The names of every scenario this node has configured, so a tester can see the choices. */
    const val available = "available"
}

/**
 * The escape hatch that selects a request-variant scenario (issue #471): a small endpoint sets the
 * [VBH.cookieName] cookie so the frontend's own unmodified requests carry it.
 *
 * **An endpoint rather than JavaScript**, deliberately: the cookie is then `HttpOnly` (the frontend never needs
 * to read it), one place validates the requested name against the configured set, and clearing it is a real
 * operation. In the anonymous `fixture` section -- it grants nothing, it only tags the session with a name the
 * dispatcher already knows about.
 *
 * **Registered only when the facility is enabled** (`CommonComponent` gates on `VariantBehavior.isEnabled`), so
 * a node that configured no scenarios does not carry these endpoints at all -- keeping the `fixture` section's
 * rule that every endpoint under it is gated. Not `forTestingOnly`, though: that gate is on `isTestInstance`,
 * which an ordinary local run against a real database is *not*, and that run is exactly where somebody watches a
 * spinner in a real browser. Enablement is the deployment's own config choice, and a real environment refuses to
 * boot with scenarios set.
 */
fun variantSchema(cxt: KdrCxt): SchModule = schemaModule(cxt, "fixture") {
    type(VEP.stateType) {
        type = SCT.kObject
        // Optional: absent means no scenario is active (after a clear, or when none was ever selected) -- an
        // empty string would fail required-string validation, and "no active scenario" is genuinely an absence.
        property(VEP.active, "The scenario active for this session after the call; absent when none.")
        property(VEP.available, "Every scenario name this node offers.", required = true) {
            type = SCT.array
            items { type = SCT.string }
        }
    }

    generalEndpoint(
        VEP.set,
        "Test-only (issue #471): select a request-misbehavior scenario by name, setting a day-long cookie the " +
            "frontend's own requests then carry. Refused unless the deployment configured that scenario.",
        HttpMethod.POST,
        outputRef = VEP.stateType,
        inputFields = {
            field(VBH.scenario, "The configured scenario to activate.", required = true)
        },
        // A diagnostic/test facility, not an app-facing surface (issue #489).
        tags = setOf(ETAG.internal),
    ) { c, req ->
        val name = req.getOptStr(VBH.scenario)
            ?: throw KdrException.mkInput("'${VBH.scenario}' is required.")
        if (!VariantBehavior.isValidName(c.instanceConfig, name)) {
            val configured = VariantBehavior.scenarios(c.instanceConfig).map { it.name }
            throw KdrException.mkInput(
                if (configured.isEmpty()) {
                    "No request-variant scenarios are configured on this node, so none can be selected."
                } else {
                    "Unknown request-variant scenario '$name'. Configured: ${configured.joinToString(", ")}."
                },
            )
        }
        // Not a session cookie -- a future expiry, so it survives the reload the behavior under test happens
        // during. `HttpOnly`/`Secure`/`SameSite` are added by `addResponseCookie`.
        c.request?.webRequest?.addResponseCookie(VBH.cookieName, name, c.instanceNow() + 1.days)
        variantState(c, name)
    }

    generalEndpoint(
        VEP.clear,
        "Test-only (issue #471): clear the request-variant cookie, returning this session to ordinary behavior.",
        HttpMethod.POST,
        outputRef = VEP.stateType,
        tags = setOf(ETAG.internal),
    ) { c, _ ->
        c.request?.webRequest?.addResponseCookie(VBH.cookieName, "", Instant.fromEpochMilliseconds(0))
        variantState(c, "")
    }
}

/** The state both endpoints answer with: the scenario now active (omitted when none), and the names on offer. */
private fun variantState(cxt: KdrCxt, active: String): Map<String, Any?> = buildMap {
    if (active.isNotEmpty()) put(VEP.active, active)
    put(VEP.available, VariantBehavior.scenarios(cxt.instanceConfig).map { it.name })
}
