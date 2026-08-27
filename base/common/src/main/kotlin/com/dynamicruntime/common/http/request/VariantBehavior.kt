package com.dynamicruntime.common.http.request

import com.dynamicruntime.common.context.ACFG
import com.dynamicruntime.common.context.KdrCxt
import com.dynamicruntime.common.context.KdrInstanceConfig
import com.dynamicruntime.common.exception.KdrException
import com.dynamicruntime.common.util.isVariableName

/**
 * One targeting rule within a [VariantScenario]. It fires when the request path contains [pathContains], and
 * then delays and/or fails the request.
 */
class VariantRule(
    /**
     * The rule fires when the request's full path **contains** this substring -- deliberately not a glob
     * mini-language. `/md/` targets fragment fetches; `/home/ui/config` targets the shell config; the
     * motivating case is *config fast, fragment slow*, which one delay rule on `/md/` produces.
     */
    val pathContains: String,
    /** Milliseconds to delay before the handler runs, capped at [VBH.maxDelayMs]; null for no delay. */
    val delayMs: Int? = null,
    /** An HTTP status to fail the request with instead of running the handler (e.g. 404 for a stale fragment). */
    val failStatus: Int? = null,
    /** The message for a [failStatus] failure; a default is used when null. */
    val failMessage: String? = null,
)

/** A named misbehavior scenario: the [VBH.cookieName] cookie selects it by [name]. */
class VariantScenario(val name: String, val rules: List<VariantRule>)

/** Constants for the request-variant facility (issue #471). */
@Suppress("ConstPropertyName")
object VBH {
    /**
     * The cookie that selects the active scenario by name. Set by `/fixture/variant/set`, so it is `HttpOnly`
     * (JS never needs to read it) and, deliberately, **not** a session cookie -- it survives the reload the
     * behavior under test happens during.
     */
    const val cookieName = "kdrVariant"

    /** Hard ceiling on an injected delay: a delay holds a Jetty worker, so a typo cannot park one for minutes. */
    const val maxDelayMs = 30_000

    /** The scenario-name field the set-cookie endpoint reads. */
    const val scenario = "scenario"
}

/**
 * A facility for making the backend **misbehave on purpose** -- slow or failed responses -- so a browser can be
 * driven through the frontend's loading and failure states (issue #471, first caller #469).
 *
 * **The deployment consents; the tester selects.** The set of behaviors a node will produce is fixed by its
 * configuration ([ACFG.testVariantScenarios], declared in code by an `AppConfigApplier` -- not an env var,
 * since it carries structured data) and readable from it. A [VBH.cookieName] cookie then picks *which* one is
 * active, carrying the scenario **name only** -- never the rules, so the cookie stays a short validated token
 * rather than a mini-language a browser holder composes.
 *
 * **Off is the default and the safety property.** With no scenarios configured, [apply] does nothing at all and
 * the cookie is inert -- which is the test that matters, and why the escape-hatch endpoints exist only on a node
 * that opted in. A delay *consumes* (it holds a worker), unlike the ungated `_debug` disclosures, so an ungated
 * one would be a denial-of-service lever; a real environment refuses to boot with it set (see
 * `RequestService.onCreate`, which also runs [validationError]).
 */
object VariantBehavior {
    /** The scenarios this node offers, or empty when the facility is off. Live objects from the config map. */
    fun scenarios(config: KdrInstanceConfig): List<VariantScenario> =
        (config.get(ACFG.testVariantScenarios) as? List<*>)?.filterIsInstance<VariantScenario>() ?: emptyList()

    /** Whether the facility is enabled at all (any scenario configured). */
    fun isEnabled(config: KdrInstanceConfig): Boolean = scenarios(config).isNotEmpty()

    /** Whether [name] is one of the configured scenarios -- what the set-cookie endpoint validates against. */
    fun isValidName(config: KdrInstanceConfig, name: String): Boolean = scenarios(config).any { it.name == name }

    /**
     * A human-readable reason the configured scenarios are unusable, or null when they are fine -- checked at
     * boot so a bad declaration fails loudly rather than misbehaving silently at request time (issue #471).
     * Names must be plain identifiers (they ride in a `Set-Cookie` verbatim), and a rule must actually do
     * something and stay within the delay ceiling.
     */
    fun validationError(config: KdrInstanceConfig): String? {
        for (s in scenarios(config)) {
            if (!s.name.isVariableName()) {
                return "scenario name '${s.name}' must be a plain identifier (letters/digits/underscore), so it " +
                    "is a safe cookie token."
            }
            if (s.rules.isEmpty()) {
                return "scenario '${s.name}' has no rules."
            }
            for (rule in s.rules) {
                if (rule.pathContains.isBlank()) {
                    return "scenario '${s.name}' has a rule with a blank pathContains, which would match every request."
                }
                val delay = rule.delayMs
                if (delay != null && delay > VBH.maxDelayMs) {
                    return "scenario '${s.name}' has delayMs=$delay over the ${VBH.maxDelayMs}ms ceiling."
                }
                if (rule.delayMs == null && rule.failStatus == null) {
                    return "scenario '${s.name}' has a rule that neither delays nor fails -- a silent no-op."
                }
            }
        }
        return null
    }

    /**
     * The dispatch hook (issue #471): applies the active scenario's first matching rule to this request, after
     * auth and before the handler. A no-op -- reading nothing from the cookie -- unless scenarios are
     * configured, which is the whole safety property.
     */
    fun apply(cxt: KdrCxt, handler: RequestHandler) {
        // The escape hatch is never subject to the behavior it installs. Otherwise a broad rule (fail/slow on
        // "/") would make `/fixture/variant/clear` itself fail, and the cookie is HttpOnly with a day's life --
        // there would be no way back except deleting it in devtools.
        if (handler.appPath.startsWith(VEP.pathRoot)) {
            return
        }
        val scenarios = scenarios(cxt.instanceConfig)
        if (scenarios.isEmpty()) {
            return
        }
        val name = handler.getRequestCookies()[VBH.cookieName] ?: return
        val scenario = scenarios.firstOrNull { it.name == name } ?: return
        val rule = scenario.rules.firstOrNull { it.pathContains in handler.target } ?: return

        rule.delayMs?.let { ms ->
            val capped = ms.coerceIn(0, VBH.maxDelayMs)
            if (capped > 0) {
                LogRequest.debug(cxt, "variant '$name': delaying ${capped}ms for ${handler.target}")
                Thread.sleep(capped.toLong())
            }
        }
        rule.failStatus?.let { status ->
            LogRequest.debug(cxt, "variant '$name': failing ${handler.target} with $status")
            throw KdrException(rule.failMessage ?: "Injected failure from test variant '$name'.", code = status)
        }
    }
}
