package com.dynamicruntime.webapp

import react.FC
import react.Props
import react.dom.html.ReactHTML.div
import react.dom.html.ReactHTML.p
import react.useEffect
import react.useState
import web.cssom.ClassName
import web.dom.ElementId

/**
 * Google's Identity Services sign-in button (issue #157), rendered by Google's own script into a container we
 * provide. The script hands back a signed **ID token**, which is POSTed to the backend's Google login endpoint;
 * the browser never sees anything it could forge, and the backend verifies the signature and audience.
 *
 * **The script is injected here, on demand, rather than declared in the page shells.** The obvious alternative
 * -- a `<script>` tag in `index.html` and its twin in `AppUiPage` -- was rejected for three reasons:
 *  - those two shells must be kept identical by hand, and each already carries a comment about exactly that
 *    drift; this feature would add a second thing to keep in step for no gain;
 *  - a shell tag loads Google's script for **every** deployment on **every** page, including deployments with
 *    Google sign-in switched off, which never need it;
 *  - it would contact Google for every visitor before they have expressed any interest in signing in with
 *    Google. Loading it only when the sign-in UI is actually shown keeps that contact to the users who are
 *    looking at the button.
 *
 * So nothing loads until an auth flow renders this component, which happens only when the deployment turned
 * the feature on ([AuthFeatures.googleLogin], i.e. it configured a client id).
 */

/** The id of the injected script tag, so a second mount reuses the in-flight or completed load. */
private const val gisScriptId = "kdr-gsi-script"

/** Google's Identity Services client library. */
private const val gisScriptSrc = "https://accounts.google.com/gsi/client"

/** Whether Google's library has finished loading and exposes the id API. */
private fun isGisReady(): Boolean =
    js("typeof google !== 'undefined' && !!google.accounts && !!google.accounts.id") as Boolean

/**
 * Ensures Google's script is loaded, then calls [onReady] (or [onFail] if it cannot be fetched -- an offline
 * browser, or a network that blocks Google). Safe to call from several mounts: an already-loaded library calls
 * back immediately, and an in-flight tag is subscribed to rather than duplicated.
 */
private fun loadGis(onReady: () -> Unit, onFail: () -> Unit) {
    js(
        """
        if (typeof google !== 'undefined' && google.accounts && google.accounts.id) {
            onReady();
        } else {
            var existing = document.getElementById('kdr-gsi-script');
            if (existing) {
                existing.addEventListener('load', onReady);
                existing.addEventListener('error', onFail);
            } else {
                var s = document.createElement('script');
                s.id = 'kdr-gsi-script';
                s.src = 'https://accounts.google.com/gsi/client';
                s.async = true;
                s.defer = true;
                s.onload = onReady;
                s.onerror = onFail;
                document.head.appendChild(s);
            }
        }
        """,
    )
}

/**
 * Hands Google the [clientId] and asks it to draw its button into the element with [elementId]. Google invokes
 * [onCredential] with the ID token once the user completes the sign-in.
 */
private fun renderGisButton(elementId: String, clientId: String, onCredential: (String) -> Unit) {
    js(
        """
        var el = document.getElementById(elementId);
        if (el) {
            el.innerHTML = '';
            google.accounts.id.initialize({
                client_id: clientId,
                callback: function (response) { onCredential(response.credential); }
            });
            google.accounts.id.renderButton(el, { theme: 'outline', size: 'large', text: 'signin_with' });
        }
        """,
    )
}

/** The page's own origin (scheme + host + port), which is exactly the string Google wants registered. */
private fun pageOrigin(): String = js("window.location.origin") as String

/**
 * What to tell a developer about [origin] beside the Google button.
 *
 * Phrased as a standing requirement, **not** as a detected error, and that is the whole design. The component
 * cannot tell a working origin from a rejected one (see [GoogleSignInButton]), so anything shaped like "this
 * origin is not registered" would be a red warning on a perfectly healthy dev instance -- a message that cries
 * wolf gets ignored on the day it is right. Stating the requirement is true either way, and it hands over the
 * exact string to paste into the Cloud Console, which is the part that is actually missing when this bites.
 *
 * Pure, and covered under `jsNodeTest`.
 */
fun googleOriginHint(origin: String): String =
    "Google sign-in requires this page's origin -- $origin -- to be registered as an Authorized JavaScript " +
        "origin for the client id. A button that does nothing, or an \"Access blocked\" message from Google, " +
        "is that registration missing. Shown only on a test instance."

external interface GoogleSignInProps : Props {
    /** The deployment's Google OAuth client id, from the auth UI config. */
    var clientId: String

    /** Called with the ID token Google returns; the flow exchanges it for a session. */
    var onCredential: (String) -> Unit
}

/**
 * Renders the Google sign-in button, or nothing at all when Google's script cannot be reached. A deployment
 * that offers Google sign-in still has to work for a user who cannot load Google's library, so *that* failure
 * is silent -- the code and password paths beside it are unaffected, and an error message about a button the
 * user may not have wanted would only be noise.
 *
 * **A misconfigured origin is a different failure, and this component cannot see it** (issue #250). Google
 * refuses a client id on an origin that is not registered against it, but nothing about that reaches our code:
 * the script loads, [isGisReady] is true, `renderButton` draws a button, and the refusal happens later inside
 * Google's own UI ("Access blocked...") or not visibly at all. Measured, not assumed -- an `error_callback`
 * passed to `initialize` never fires for it, so there is no error to hang a message on.
 *
 * So instead of reporting a failure it cannot detect, the component states the fact a developer needs
 * (`originHint`) and leaves the diagnosis to them. It is shown only where `showErrorDetail` is on, which is
 * the backend's `isTestInstance` -- the same fence the error boundaries use. A real deployment shows nothing,
 * which is what the paragraph above is about.
 */
val GoogleSignInButton = FC<GoogleSignInProps> { props ->
    val containerId = "kdr-google-button"
    var failed by useState(false)

    useEffect(props.clientId) {
        if (props.clientId.isEmpty()) return@useEffect
        loadGis(
            onReady = {
                // Guard the render: the callback can arrive after this component has gone (a fast navigation),
                // and Google throws if handed an element that is no longer in the document.
                if (isGisReady()) {
                    renderGisButton(containerId, props.clientId, props.onCredential)
                }
            },
            onFail = { failed = true },
        )
    }

    if (!failed) {
        div {
            className = ClassName("row")
            id = ElementId(containerId)
        }
        // Beside the button rather than in a console line, because the person who needs it is looking at the
        // button that will not work, not at a log. Withheld on a real deployment (issue #250).
        if (appConfig().showErrorDetail) {
            p {
                className = ClassName("type-hint")
                +googleOriginHint(pageOrigin())
            }
        }
    }
}
