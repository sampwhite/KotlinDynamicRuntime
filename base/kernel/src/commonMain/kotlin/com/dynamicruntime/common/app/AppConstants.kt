package com.dynamicruntime.common.app

/**
 * Shared wire vocabulary for the app-level UI config (issue #118): the endpoint path, and the feature keys the
 * frontend reads off the response. In the KMP kernel so the frontend uses exactly the strings the backend
 * writes. The app config is deployment-global -- the same for every caller -- and fetched once for the whole
 * frontend rather than per widget-group (part of the refresh foundation, issue #113).
 */
@Suppress("ConstPropertyName")
object APP {
    /** The app-level UI-config endpoint: deployment-wide config visible to the entire frontend. */
    const val uiConfig = "/app/ui/config"

    /**
     * Feature flag: whether this deployment obfuscates sensitive error messages (issue #108). The frontend
     * reads it to decide whether to show the content of a raw (non-fragment) error or suppress it (issue #111).
     */
    const val obfuscateSensitiveErrors = "obfuscateSensitiveErrors"

    /**
     * Feature flag: whether the frontend may show the detail of a caught render failure -- its message and
     * React's component stack -- rather than a generic panel (issue #223).
     *
     * Set from the backend's `isTestInstance`, deliberately reusing the fence that already governs the other
     * detailed-diagnostic surface (`explainAccess`, issue #215) instead of inventing a second notion of "a
     * development build". One rule, already audited, and one that a real deployment cannot turn on by
     * accident: an instance claiming to be a test instance outside `local`/`unit` refuses to start.
     */
    const val showErrorDetail = "showErrorDetail"

    /**
     * Feature flag: whether the frontend's **debug pages** exist at all (issue #227) -- the fault route that
     * makes the app throw on demand, and whatever diagnostic views join it.
     *
     * Separate from [showErrorDetail] on purpose, though both derive from the backend's `isTestInstance`
     * today. They authorize different things -- seeing internals versus *manufacturing a failure* -- and a
     * flag named for disclosure must not silently confer injection. Kept apart so they can diverge later
     * without one quietly widening the other.
     */
    const val allowDebugPages = "allowDebugPages"

    /**
     * Feature flag: whether this request is **currently acting** env-authed (issues #348, #360) -- the
     * *effective* value, not the raw truth of the channel. False when no edge vouched for the request, and
     * also when the session has deliberately suppressed its env auth ([envAuthPath]).
     *
     * **Effective rather than raw is the deliberate half.** Everything that varies with env auth reads this
     * one, so a consumer that has never heard of suppression still honors it -- and a consumer that forgets
     * [envAuthSuppressible] shows *less* than it might, never more. Failing in the closed direction is the same
     * choice made for the boot-role default and the header trust flag.
     *
     * A **boolean, not the address.** The frontend needs to know *that* the channel is env-authed; who the
     * caller is env-authed as stays server-side on the context, where it is also what reaches the logs. Should
     * a later issue want to show it, that is a deliberate widening rather than something inherited by default.
     *
     * Unlike most of its neighbors here this is **per-request**, not deployment-global: the same node answers
     * differently depending on how a request reached it. The app config is still the right home -- it is what
     * the whole frontend reads once at the app root -- but a caller must not cache the answer across a change
     * of route into the deployment.
     */
    const val isEnvAuthed = "isEnvAuthed"

    /**
     * Feature flag: whether this caller may **suppress** their own env auth (issues #360, #446) -- which is
     * what the control does, and so what deciding to show it depends on.
     *
     * Exists because one boolean cannot express this. While suppressed, [isEnvAuthed] is false but the user
     * must still be shown the control that restores it -- otherwise the affordance disappears along with the
     * thing it controls, and there is no way back. So **visibility of the indicator reads this**, and nothing
     * else should: anything deciding what a user may see or do reads [isEnvAuthed].
     *
     * Named for suppressibility rather than availability since #446. It was only ever read to decide whether
     * to offer the toggle, and on an **edge** the two come apart: env auth is available there -- it is what
     * let the caller in -- and suppressing it is not offered, because there is nothing to preview and
     * `EnvAuthRules.suppressionOffered` therefore refuses the cookie. A name saying "available" would have had
     * to be false on an edge while being true, which is how a field starts lying.
     */
    const val envAuthSuppressible = "envAuthSuppressible"

    /**
     * The endpoint a session uses to suppress its own env auth, or restore it ([EnvAuthOp]). Anonymous and
     * **not** test-only: this is live behavior in a deployed environment, because seeing the application as an
     * ordinary user sees it is a real thing to want, not merely a testing affordance.
     *
     * Safe to expose because it only ever **subtracts** -- the worst a caller can do to themselves is see less
     * than they are entitled to. The opposite direction (asserting env auth that no edge granted) is a
     * `forTestingOnly` fixture precisely because it does not have that property.
     */
    const val envAuthPath = "/app/envAuth"

    /** Request field of [envAuthPath]: which operation to perform, an [EnvAuthOp] name. */
    const val envAuthOp = "op"

    /** The response schema type name for [envAuthPath]: the two flags as they stand after the operation. */
    const val envAuthStateType = "EnvAuthState"

    /**
     * Setting (under the envelope's `settings`, not a flag): how often, in milliseconds, the frontend "bumps" its refresh
     * generation while a tab is visible, so a long-open tab notices a timed-out session or a newer deploy
     * (issue #146). Deployment-tunable through the custom-config object; the frontend re-arms its timer when the
     * value changes.
     */
    const val idleBumpIntervalMs = "idleBumpIntervalMs"

    /**
     * The default idle-bump interval (issue #146): one minute. Authoritative on the backend, which always
     * serves a value; on the frontend it is only the pre-first-fetch / fetch-failure fallback. Shared here so
     * the two cannot drift.
     */
    const val defaultIdleBumpIntervalMs = 60_000
}

/**
 * The operations [APP.envAuthOp] accepts (issue #360). Names are the wire values, and drive both the schema's
 * choice list and the endpoint's `when`.
 *
 * [suppress] is different from clearing a test fixture's assertion: suppressing **overrides** a real env auth,
 * while clearing merely stops pretending and returns the session to whatever the channel actually is. With no
 * edge in front, the two look identical, which is exactly why they do not share a name.
 */
@Suppress("EnumEntryName")
enum class EnvAuthOp { suppress, restore }
