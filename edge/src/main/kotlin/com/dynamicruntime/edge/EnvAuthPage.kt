package com.dynamicruntime.edge

/**
 * The pages an edge serves as content: the sign-in page, and what a signed-in caller sees.
 *
 * Server-rendered from Kotlin, the way `PortalPage` is, and deliberately **self-contained** -- an inline style
 * and (for sign-in) Google's own script, no stylesheet, no bundle, no image. That is what keeps the anonymous
 * surface of an edge to one page: nothing to exempt from the gate, and nothing to break the day somebody adds
 * an asset. It is possible only because Google Sign-In hands the browser an ID token to post, so signing in
 * needs a button and a `fetch` rather than a redirect dance.
 */
object EnvAuthPage {
    /**
     * [clientId] is public by design -- it identifies the application to Google, and the browser must present
     * it. [returnTo] has already been through [EnvAuthReturn.sanitize], so it is a same-site path.
     */
    fun render(clientId: String, returnTo: String, loginPath: String): String {
        // Escaped once, up front, so no second form of a value is floating around to embed by mistake.
        val rt = jsString(returnTo)
        val lp = jsString(loginPath)
        val cid = jsString(clientId)
        return $$"""
<!doctype html>
<html lang="en">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<title>Sign in</title>
<style>
  :root { color-scheme: light dark; }
  body { margin: 0; min-height: 100vh; display: grid; place-items: center;
         font: 15px/1.5 system-ui, -apple-system, "Segoe UI", sans-serif;
         background: Canvas; color: CanvasText; }
  main { width: min(26rem, 90vw); text-align: center; }
  h1 { font-size: 1.25rem; font-weight: 600; margin: 0 0 .25rem; }
  p.sub { margin: 0 0 1.5rem; opacity: .7; }
  #btn { display: flex; justify-content: center; }
  #err { margin-top: 1.25rem; min-height: 1.25rem; color: #b3261e; font-size: .9rem; }
  @media (prefers-color-scheme: dark) { #err { color: #f2b8b5; } }
  .where { font-size: .8rem; opacity: .55; margin-top: 2rem; word-break: break-all; }
</style>
</head>
<body>
<main>
  <h1>Sign in to continue</h1>
  <p class="sub">This environment is restricted.</p>
  <div id="btn"></div>
  <div id="err" role="alert"></div>
  <div class="where" id="where"></div>
</main>
<script src="https://accounts.google.com/gsi/client" async defer></script>
<script>
  var returnTo = $$rt;
  var loginPath = $$lp;
  if (returnTo !== '/') {
    document.getElementById('where').textContent = 'You will be returned to ' + returnTo;
  }

  function onCredential(response) {
    document.getElementById('err').textContent = '';
    fetch(loginPath, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      credentials: 'same-origin',
      body: JSON.stringify({ googleCredential: response.credential })
    }).then(function (r) {
      return r.json().then(function (body) { return { ok: r.ok, body: body }; });
    }).then(function (res) {
      if (res.ok) {
        // Back to whatever was asked for before the challenge. Server-sanitized, so this can only ever be a
        // same-site path -- a redirect target taken from a query string is otherwise an open redirect, and a
        // perimeter is the worst place in a system to have one.
        window.location.replace(returnTo);
      } else {
        document.getElementById('err').textContent =
          (res.body && res.body.errorMessage) || 'Sign-in was refused.';
      }
    }).catch(function () {
      document.getElementById('err').textContent = 'Sign-in could not be completed.';
    });
  }

  window.onload = function () {
    if (!window.google || !window.google.accounts) {
      document.getElementById('err').textContent = 'Google sign-in could not be loaded.';
      return;
    }
    window.google.accounts.id.initialize({ client_id: $$cid, callback: onCredential });
    window.google.accounts.id.renderButton(document.getElementById('btn'), { theme: 'outline', size: 'large' });
  };
</script>
</body>
</html>
"""
    }

    /**
     * What the edge shows somebody who is already signed in.
     *
     * It exists because without it the bare content root has nothing to offer a signed-in caller, and sending
     * them to the sign-in page instead makes a **loop**: root to login, login back to root. That is what an
     * edge with no home page of its own does by default, and it was found the first time somebody signed in
     * from the bare root rather than from a deep link. When the edge grows a real front end, this becomes its
     * landing.
     */
    fun renderSignedIn(email: String, catalogPath: String): String {
        val who = htmlText(email)
        val cat = htmlText(catalogPath)
        return $$"""
<!doctype html>
<html lang="en">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<title>Signed in</title>
<style>
  :root { color-scheme: light dark; }
  body { margin: 0; min-height: 100vh; display: grid; place-items: center;
         font: 15px/1.5 system-ui, -apple-system, "Segoe UI", sans-serif;
         background: Canvas; color: CanvasText; }
  main { width: min(30rem, 90vw); text-align: center; }
  h1 { font-size: 1.25rem; font-weight: 600; margin: 0 0 .5rem; }
  p { margin: 0 0 1rem; opacity: .75; }
  a { color: LinkText; }
</style>
</head>
<body>
<main>
  <h1>Signed in</h1>
  <p>You are signed in to this environment as <code>$$who</code>.</p>
  <p><a href="$$cat">Endpoint catalog</a></p>
</main>
</body>
</html>
"""
    }

    /** Escapes text for HTML content -- the characters that would otherwise open a tag or an entity. */
    private fun htmlText(value: String): String = value
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\"", "&quot;")

    /**
     * A string safe to embed inside a `<script>` block.
     *
     * Quotes and backslashes are escaped, and so is `<` -- because `</script>` ends the block wherever it
     * appears, including inside a string literal, which is the HTML escaping rule that catches people writing
     * values into a page. Every value passed here is already constrained (a sanitized path, a configured
     * client id), so this is the second line of defence rather than the first.
     */
    private fun jsString(value: String): String {
        val escaped = value
            .replace("\\", "\\\\")
            .replace("\"", "\\\"")
            .replace("<", "\\u003c")
            .replace("\n", "")
            .replace("\r", "")
        return "\"" + escaped + "\""
    }
}
