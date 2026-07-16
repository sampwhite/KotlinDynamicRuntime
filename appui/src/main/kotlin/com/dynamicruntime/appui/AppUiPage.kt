package com.dynamicruntime.appui

/**
 * The webapp's **production** HTML shell, rendered as a Kotlin string (the house style: what would elsewhere
 * be a static `index.html` asset is a Kotlin declaration). It is the counterpart of
 * `webapp/src/jsMain/resources/index.html`, which the webpack dev server serves; both mount the app at the
 * same `#root` and load the same assets, with two differences for same-origin serving from the runtime:
 *
 *  - the bundle `<script>` src and the stylesheet/icon `<link>` hrefs are built as absolute paths from the
 *    live app context root ([appRoot], e.g. `/wa/webapp.js`) rather than the bare relative names the dev
 *    shell can use, and
 *  - the frontend bootstrap config is injected as `window.kdrCfg` (as the portal does), so the app can build
 *    backend URLs from the live context roots -- `Brand.kt` in the webapp does exactly that.
 *
 * The icon links mirror the dev shell's, and are the branding set's documented integration: an SVG icon for
 * browsers that take one, a PNG fallback for those that do not, and the iOS home-screen icon. The rasters are
 * served as bytes by [AppUiService] -- see the webapp's `jsMain/resources/README.md`.
 *
 * **This shell holds no CSS.** Both shells `<link>` the webapp's single `app.css`, which `appui` embeds and
 * serves like the bundle. That is deliberate and load-bearing: each shell used to carry its own inline copy,
 * and every feature added after the app bar styled only the dev shell -- so a built jar rendered the app
 * largely unstyled while the dev server looked right. Two copies of the CSS is the bug; do not reintroduce a
 * `<style>` block here.
 *
 * Written as a multi-dollar (`$$`) string so a bare `$` is literal; the injected values are the bootstrap
 * config ([cfgJson]) and the app context root ([appRoot]).
 */
object AppUiPage {
    fun render(cfgJson: String, appRoot: String): String = $$"""
<!doctype html>
<html lang="en">
<head>
<meta charset="UTF-8">
<meta name="viewport" content="width=device-width, initial-scale=1.0">
<title>Kotlin React Webapp</title>
<link rel="icon" type="image/svg+xml" href="/$${appRoot}/favicon.svg">
<link rel="icon" type="image/png" sizes="32x32" href="/$${appRoot}/favicon-32.png">
<link rel="apple-touch-icon" href="/$${appRoot}/apple-touch-icon.png">
<link rel="stylesheet" href="/$${appRoot}/app.css">
</head>
<body>
<div id="root"></div>
<script>window.kdrCfg = $${cfgJson};</script>
<script src="/$${appRoot}/webapp.js"></script>
</body>
</html>
"""
}
