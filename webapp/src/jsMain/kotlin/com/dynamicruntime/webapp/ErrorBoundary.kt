package com.dynamicruntime.webapp

import react.ComponentType
import react.FC
import react.Props
import react.PropsWithChildren
import react.dom.html.ReactHTML.button
import react.dom.html.ReactHTML.div
import react.dom.html.ReactHTML.h2
import react.dom.html.ReactHTML.p
import react.dom.html.ReactHTML.pre
import web.cssom.ClassName

/**
 * What a fallback is told about the failure it is standing in for.
 *
 * [detail] is the diagnostic half -- React's component stack, which names the component that threw. A fallback
 * shows it (and [message]) only where the deployment allows it, via [AppConfig.showErrorDetail]; elsewhere it
 * shows its own generic copy and nothing from the failure itself.
 */
external interface ErrorFallbackProps : Props {
    var message: String
    var detail: String?
}

/** What the boundary needs: the children it guards, the fallback to swap in, and where to report. */
external interface ErrorBoundaryProps : PropsWithChildren {
    var fallback: ComponentType<ErrorFallbackProps>
    var onError: (message: String, errorStack: String, componentStack: String) -> Unit
}

/**
 * Builds the boundary class in raw JS, because React will only let a **class** component catch: it decides by
 * looking for `getDerivedStateFromError` / `componentDidCatch`, and a function component can carry neither.
 * The kotlin-wrappers are function-component oriented and expose no base class, so this is the one place the
 * app steps outside them -- following the `js()` precedent `GoogleSignIn.kt` already sets, and taking React's
 * pieces as parameters rather than assuming a global.
 *
 * Both hooks are implemented, and they do different jobs: `getDerivedStateFromError` is what swaps in the
 * fallback (it runs during the failed render), while `componentDidCatch` is where the component stack arrives
 * and is therefore where reporting happens.
 */
@Suppress("unused")
private fun mkErrorBoundary(base: dynamic, create: dynamic): dynamic = js(
    """
    function describe(err) {
        if (!err) return 'unknown error';
        // A Kotlin exception reaches JS with `message` undefined and a minified `name`, so falling straight to
        // String(err) yields something like "ji". Preferring the message, then the name, keeps the useful text
        // for a native error (a TypeError says what was undefined) without pretending the Kotlin case is
        // legible -- for that, the component stack below is the part worth reading.
        return err.message || err.name || String(err);
    }
    function KdrErrorBoundary(props) {
        base.call(this, props);
        this.state = { message: null, detail: null };
    }
    KdrErrorBoundary.prototype = Object.create(base.prototype);
    KdrErrorBoundary.prototype.constructor = KdrErrorBoundary;
    KdrErrorBoundary.getDerivedStateFromError = function (err) {
        return { message: describe(err) };
    };
    KdrErrorBoundary.prototype.componentDidCatch = function (err, info) {
        var componentStack = (info && info.componentStack) || '';
        // Both stacks are reported: the JS one says where it threw, the component one says which component was
        // rendering. Neither subsumes the other, and an earlier draft lost the JS stack by overwriting it.
        this.props.onError(describe(err), (err && err.stack) || '', componentStack);
        this.setState({ message: describe(err), detail: componentStack });
    };
    KdrErrorBoundary.prototype.render = function () {
        if (this.state.message) {
            return create(this.props.fallback, { message: this.state.message, detail: this.state.detail });
        }
        return this.props.children;
    };
    return KdrErrorBoundary;
    """,
)

/**
 * Catches a render failure in its subtree and shows [ErrorBoundaryProps.fallback] instead of letting React
 * unmount everything.
 *
 * Without one, a throw during render takes the **whole tree** down and leaves an empty page -- which is the
 * least informative failure the app can produce, for a user and for an automated test alike. Issue #210 was
 * exactly that: a blank screen whose only evidence was a minified console line.
 *
 * It catches render and lifecycle errors only. Anything thrown from an event handler, an effect callback or a
 * rejected promise never reaches a boundary -- [installGlobalErrorHandlers] covers those.
 */
val ErrorBoundary: ComponentType<ErrorBoundaryProps> =
    mkErrorBoundary(Component, ::createElement).unsafeCast<ComponentType<ErrorBoundaryProps>>()

/**
 * The page-level fallback: what stands in for a section of the app that failed to render.
 *
 * It is deliberately a *panel*, not a whole-page takeover, because the boundary wraps the page content inside
 * the shell rather than the root. A crash should cost the page, not the navigation -- someone (or a test)
 * needs to be able to click away from a broken screen instead of being stranded on it.
 */
@Suppress("DuplicatedCode")
val ErrorFallback = FC<ErrorFallbackProps> { props ->
    div {
        className = ClassName("card wide error-panel")
        h2 { +"This section could not be displayed" }
        p {
            className = ClassName("subtitle")
            +("Something went wrong while drawing this page. The rest of the app is still working — use the " +
                "navigation above to go elsewhere, or reload to try again.")
        }
        // Shown only where the deployment allows it (`showErrorDetail`, from the backend's isTestInstance).
        // On a real deployment there is nothing here, and the detail lives in the console rather than on a
        // user's screen. Read at render time rather than captured, so the first config fetch is reflected.
        if (appConfig().showErrorDetail) {
            p {
                className = ClassName("subtitle")
                +props.message
            }
            props.detail?.let { detail ->
                pre {
                    className = ClassName("code")
                    +detail
                }
            }
        }
    }
}

/**
 * The last-resort fallback: what stands in when the **shell itself** failed, so there is no navigation left to
 * offer and no page to preserve.
 *
 * Deliberately barer than [ErrorFallback] -- no card, no assumption that anything above it drew -- because the
 * thing that broke may be the app bar. A reload is the only honest action here: the page-level fallback can
 * say "go elsewhere" because elsewhere still works, and this one cannot.
 */
@Suppress("DuplicatedCode")
val ShellErrorFallback = FC<ErrorFallbackProps> { props ->
    div {
        className = ClassName("card wide error-panel")
        h2 { +"The application could not start" }
        p {
            className = ClassName("subtitle")
            +"Something went wrong before the page could be drawn. Reloading may clear it."
        }
        button {
            className = ClassName("update-banner-reload")
            onClick = { reloadWebApp() }
            +"Reload"
        }
        if (appConfig().showErrorDetail) {
            p {
                className = ClassName("subtitle")
                +props.message
            }
            props.detail?.let { detail ->
                pre {
                    className = ClassName("code")
                    +detail
                }
            }
        }
    }
}

/** The prefix every frontend error report carries, so it is greppable in a console log and assertable in a test. */
const val errorLogPrefix = "[kdr]"

/**
 * Reports a caught render failure to the console, which is where a diagnosis actually starts.
 *
 * The boundary reports *as well as* rendering, never instead of it. A boundary that displayed prettily and
 * swallowed the console error would go quiet exactly when something broke -- and a browser test asserting a
 * clean console would then pass on a broken page, which is worse than the blank screen this replaces.
 */
fun reportRenderFailure(message: String, errorStack: String, componentStack: String) {
    console.error("$errorLogPrefix render failure: $message")
    if (errorStack.isNotBlank()) {
        console.error("$errorLogPrefix error stack:\n$errorStack")
    }
    if (componentStack.isNotBlank()) {
        console.error("$errorLogPrefix component stack:$componentStack")
    }
}

/**
 * Catches what a boundary structurally cannot: React boundaries see render and lifecycle errors only, so a
 * throw from an event handler, an effect callback, or a rejected promise never reaches one. Issue #210 was a
 * render error and would have been caught; a failure inside an `onClick` would still pass silently.
 *
 * These only *report* -- with the same prefix, so one search finds every frontend failure, however, it arose.
 * They deliberately do not draw anything: a global error has no place in the tree to render into, and
 * inventing a whole-page takeover for one would cost more than it gives.
 */
fun installGlobalErrorHandlers() {
    js(
        """
        window.addEventListener('error', function (e) {
            var msg = (e && e.message) || 'unknown error';
            var at = e && e.filename ? ' (' + e.filename + ':' + e.lineno + ':' + e.colno + ')' : '';
            console.error('[kdr] uncaught error: ' + msg + at);
        });
        window.addEventListener('unhandledrejection', function (e) {
            var r = e && e.reason;
            console.error('[kdr] unhandled rejection: ' + ((r && (r.message || r)) || 'unknown reason'));
        });
        """,
    )
}
