package com.dynamicruntime.webapp

import com.dynamicruntime.common.endpoint.EP

/**
 * Where the app's own static assets live, resolved at runtime rather than hardcoded.
 *
 * The two shells serve the app from different places: the dev server serves it (and the webapp's resources)
 * from the origin root, while in production `appui` serves it under the **app context root** — `/wa` by
 * default, but a deployment can configure it. So `/brand-mark.svg` is right in dev and wrong in production,
 * and `/wa/brand-mark.svg` is the reverse. Neither can be a literal.
 *
 * A relative URL (`brand-mark.svg`) is not the answer either: it resolves against the page's path, so it only
 * works when the app root carries a trailing slash. That is why [AppUiPage] already writes the bundle
 * `<script>` as an absolute path built from the live context root rather than the bare relative name the dev
 * shell uses.
 *
 * So we ask the page. `appui` injects the runtime's frontend bootstrap as `window.kdrCfg` —
 * `{contextRoots: {app: "wa", api: "kda", ...}}` — precisely so the frontend can build URLs from the live
 * roots. This is its first consumer. The dev shell injects nothing, which is exactly right: no bootstrap means
 * no context root means the origin root, which is where the dev server serves from.
 */

/** The app context root as a URL prefix — `"/wa"` under `appui`, `""` on the dev server (assets at the origin
 *  root). Assets are addressed as `appRootPrefix() + "/name"`, which is correct under both. */
fun appRootPrefix(): String {
    val root = js("(window.kdrCfg && window.kdrCfg.contextRoots && window.kdrCfg.contextRoots.app) || ''") as String
    return if (root.isEmpty()) "" else "/$root"
}

/**
 * A flat value the page injected into `window.kdrCfg`, or "" when there is none (a dev shell injects nothing).
 * Module-visible so the frontend has one place the bootstrap-lookup + empty-fallback semantics live, rather
 * than a hand-rolled `js("... || ''")` per reader.
 */
internal fun bootstrapValue(key: String): String = js("(window.kdrCfg && window.kdrCfg[key]) || ''") as String

/** The file name the dev server serves the mark under (no bootstrap there); the one literal the tiers share. */
private const val brandMarkFile = "brand-mark.svg"

/**
 * The brand mark (`brand-mark.svg` in the webapp's resources): the app bar's logo and the home page's hero.
 *
 * Content-addressed under `appui` (issue #529): the bootstrap carries the mark's whole versioned name
 * ([EP.brandMarkName], e.g. `brand-mark.svg:1a2b3c`) — built by the backend's `versionedName`, so the frontend
 * never spells the `:hash` grammar — and the mark is cached immutably instead of refetched every load. The dev
 * server injects no bootstrap, so the name is absent and the mark is served bare from the origin root.
 */
val brandMarkUrl: String get() = brandMarkUrlFrom(appRootPrefix(), bootstrapValue(EP.brandMarkName))

/** Pure ([appConfigFrom]-style) so both branches are covered by `jsNodeTest` (issue #529): the bootstrap's
 *  content-addressed name when appui supplied one, else the bare [brandMarkFile] the dev server serves. */
fun brandMarkUrlFrom(rootPrefix: String, versionedName: String): String =
    "$rootPrefix/" + versionedName.ifEmpty { brandMarkFile }
