package com.dynamicruntime.appui

/**
 * The webapp's HTML shell, rendered as a Kotlin string (the house style: what would elsewhere be a static
 * `index.html` asset is a Kotlin declaration). It mirrors `webapp/src/jsMain/resources/index.html` -- the
 * same `#root` mount point and the same CSS the React components style against (`card`, `row`, `count`,
 * `todo-title`, ...) -- with two differences for same-origin serving from the runtime:
 *
 *  - the bundle `<script>` src is built as an absolute path from the live app context root
 *    ([appRoot], e.g. `/wa/webapp.js`) rather than a bare relative name, and
 *  - the frontend bootstrap config is injected as `window.kdrCfg` (as the portal does), so the app can build
 *    backend URLs from the live context roots if it needs to.
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
<style>
  :root { color-scheme: light dark; }
  * { box-sizing: border-box; }
  body {
    margin: 0;
    min-height: 100vh;
    display: grid;
    place-items: center;
    font-family: -apple-system, BlinkMacSystemFont, "Segoe UI", Roboto, sans-serif;
    background: #0f172a;
    color: #e2e8f0;
  }
  .card {
    width: min(560px, 92vw);
    padding: 32px 36px;
    border-radius: 16px;
    background: #1e293b;
    box-shadow: 0 20px 60px rgba(0, 0, 0, .45);
  }
  h1 { margin: 0 0 4px; font-size: 1.6rem; }
  h2 { margin: 28px 0 12px; font-size: 1.05rem; color: #94a3b8; }
  .subtitle { margin: 0; color: #94a3b8; }
  .row { display: flex; align-items: center; gap: 12px; flex-wrap: wrap; }
  button {
    cursor: pointer;
    border: none;
    border-radius: 10px;
    padding: 10px 18px;
    font-size: 1rem;
    font-weight: 600;
    background: #6366f1;
    color: white;
  }
  button:hover { background: #818cf8; }
  button.ghost { background: transparent; color: #94a3b8; border: 1px solid #334155; }
  .count { min-width: 48px; text-align: center; font-size: 1.5rem; font-weight: 700; }
  input {
    flex: 1;
    min-width: 180px;
    padding: 10px 14px;
    border-radius: 10px;
    border: 1px solid #334155;
    background: #0f172a;
    color: #e2e8f0;
    font-size: 1rem;
  }
  .greeting { font-size: 1.2rem; margin-top: 12px; }
  .todo-title { flex: 1; min-width: 120px; }
  .todo-title.done { text-decoration: line-through; color: #94a3b8; }
  .todo-error { color: #f87171; margin-top: 12px; }
</style>
</head>
<body>
<div id="root"></div>
<script>window.kdrCfg = $${cfgJson};</script>
<script src="/$${appRoot}/webapp.js"></script>
</body>
</html>
"""
}
