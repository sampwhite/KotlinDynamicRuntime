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
     * Feature flag: whether this request arrived through an authenticating **edge** server (issue #348), so
     * the frontend can offer a fuller, internal experience to someone who reached the application that way.
     *
     * A **boolean, not the address.** The frontend needs to know *that* the channel is env-authed; who the
     * caller is env-authed as stays server-side on the context, where it is also what reaches the logs. Should
     * a later issue want to show it, that is a deliberate widening rather than something inherited by default.
     *
     * Unlike its neighbours here this is **per-request**, not deployment-global: the same node answers
     * differently depending on how a request reached it. The app config is still the right home -- it is what
     * the whole frontend reads once at the app root -- but a caller must not cache the answer across a change
     * of route into the deployment.
     */
    const val isEnvAuthed = "isEnvAuthed"

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
