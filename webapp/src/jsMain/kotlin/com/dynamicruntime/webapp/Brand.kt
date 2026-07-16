package com.dynamicruntime.webapp

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

/** The brand mark (`brand-mark.svg` in the webapp's resources): the app bar's logo and the home page's hero. */
val brandMarkUrl: String get() = appRootPrefix() + "/brand-mark.svg"
