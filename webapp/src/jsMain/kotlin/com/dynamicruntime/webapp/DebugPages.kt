package com.dynamicruntime.webapp

import com.dynamicruntime.common.endpoint.EP
import com.dynamicruntime.common.content.UIC
import com.dynamicruntime.common.test.TEP
import com.dynamicruntime.common.util.evalTemplate
import com.dynamicruntime.common.util.toJsonMapOrEmpty
import com.dynamicruntime.common.util.toJsonStr
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.launch
import react.FC
import react.Props
import react.dom.html.ReactHTML.a
import react.dom.html.ReactHTML.div
import react.dom.html.ReactHTML.h1
import react.dom.html.ReactHTML.h2
import react.dom.html.ReactHTML.li
import react.dom.html.ReactHTML.p
import react.dom.html.ReactHTML.pre
import react.dom.html.ReactHTML.ul
import react.useEffect
import react.useState
import web.cssom.ClassName

// The frontend's debug pages (issue #227): a small area that exists only where the deployment permits it,
// holding things useful for diagnosing the app itself rather than for using it. The first tenant makes the app
// fail on demand; the second shows the state the app is running on. Both are reached by URL, which is what
// lets a browser test drive them with nothing but a link.

/** Hash parameter naming which debug tool to show, e.g. `#page=debug&tool=fault`. */
const val debugToolParam = "tool"

/** The tool that throws during render, so the page-level error boundary can be seen to catch. */
const val debugToolFault = "fault"

/** The tool that dumps the app's resolved state. */
const val debugToolState = "state"

/** The tool that renders a server-provided content element whose text pulls a fragment on the frontend (#505). */
const val debugToolFragment = "fragment"

/**
 * Hash parameter that makes the **shell** fail (`#fault=shell`), rather than the page.
 *
 * It is a separate lever from [debugToolFault] because it has to be reachable from *any* page: proving the
 * backstop catches means breaking the app bar, which renders outside the page boundary. Honored in `AppBar`.
 */
const val shellFaultParam = "fault"

/** The value [shellFaultParam] must carry to break the shell. */
const val shellFaultValue = "shell"

/**
 * Whether the shell should throw right now -- the app bar asks this on every render.
 *
 * It keeps answering true while the parameter is present, deliberately. An earlier attempt *consumed* the
 * request during render, so that a reload would not re-fault -- and that quietly destroyed the demonstration:
 * React retries a failed render, the retry no longer faulted, and it recovered instead of showing the
 * backstop. (It surfaced as an uncaught React #520 rather than anything legible.) A fault that stops being
 * true halfway through rendering is not a fault worth testing against.
 *
 * Escaping is therefore an explicit act rather than a side effect -- see [reloadWithoutFault], which the shell
 * fallback's reload uses.
 */
fun shouldFailShell(): Boolean =
    appConfig().allowDebugPages && hashParams()[shellFaultParam] == shellFaultValue

/**
 * Reloads with any fault parameter stripped, which is what the shell fallback's reload does.
 *
 * The shell fallback is the one fallback a debug fault can put on screen, and a plain reload would re-read the
 * URL that caused the failure -- offering an action that cannot work, forever. Stripping first makes the
 * offer honest.
 */
fun reloadWithoutFault() {
    js(
        """
        var hash = window.location.hash.replace(/^#/, '');
        var kept = hash.split('&').filter(function (p) { return p.indexOf('fault=') !== 0; }).join('&');
        // replaceState rewrites the address WITHOUT navigating, so the reload below re-reads the cleaned URL.
        // location.replace() would not do: a change of hash alone is not a navigation, so the reload that
        // followed it re-read the original address and faulted straight back -- observed, not theorised.
        window.history.replaceState(null, '', window.location.pathname + (kept ? '#' + kept : ''));
        window.location.reload();
        """,
    )
}

/**
 * The debug area's entry point, dispatching on the [debugToolParam].
 *
 * Reached only when [AppConfig.allowDebugPages] is set -- `App` resolves this route to Home otherwise, so on a
 * real deployment there is no debug page to find and nothing that says one exists.
 */
val DebugPage = FC<Props> {
    when (hashParams()[debugToolParam]) {
        debugToolFault -> DebugFault {}
        debugToolState -> DebugState {}
        // The fragment demo calls a `forTestingOnly` fixture endpoint that a real deployment does not register,
        // so it works only on a test instance -- offer it nowhere else, even to an env-debug operator who can
        // reach this page (issue #517). A hand-typed URL falls back to the index rather than a guaranteed error.
        debugToolFragment -> if (appConfig().isTestInstance) DebugFragment {} else DebugIndex {}
        else -> DebugIndex {}
    }
}

/** Lists what the debug area offers, so it is discoverable without reading this file. */
val DebugIndex = FC<Props> {
    div {
        className = ClassName("card wide")
        h1 { +"Debug" }
        p {
            className = ClassName("subtitle")
            +("Tools for diagnosing the application itself. This page exists only where the deployment " +
                "permits it — on a real deployment the route resolves to the home page instead.")
        }
        ul {
            li {
                a {
                    href = "#page=debug&$debugToolParam=$debugToolState"
                    +"App state"
                }
                +" — the resolved configuration and refresh generation this tab is running on."
            }
            li {
                a {
                    href = "#page=debug&$debugToolParam=$debugToolFault"
                    +"Fault: fail this page"
                }
                +" — throws while rendering, so the page error boundary is seen to catch."
            }
            // Offered only on a genuine test instance: it demos a forTestingOnly fixture endpoint absent from a
            // real deployment, so an env-debug operator who reaches this page is not shown a tool that can only
            // fail (issue #517).
            if (appConfig().isTestInstance) {
                li {
                    a {
                        href = "#page=debug&$debugToolParam=$debugToolFragment"
                        +"Fragment pull"
                    }
                    +(" — renders a server-provided content element whose text pulls a fragment, resolved on the " +
                        "frontend (issue #505).")
                }
            }
            li {
                a {
                    href = "#page=debug&$shellFaultParam=$shellFaultValue"
                    +"Fault: fail the shell"
                }
                +" — throws in the app bar, so the backstop boundary is seen to catch."
            }
        }
    }
}

/**
 * Throws while rendering. That is its whole job.
 *
 * A dedicated component rather than a conditional inside a real page: real pages stay clean, and a test that
 * drives this is deterministic instead of coupled to whatever a real page happens to do. Issue #223's
 * boundaries shipped with no automated coverage precisely because making the app fail required editing source code
 * and rebuilding, which a test cannot do.
 */
val DebugFault = FC<Props> {
    error("Deliberate fault from the debug page (issue #227).")
}

/**
 * The state this tab is actually running on -- the resolved app config and the current refresh generation.
 *
 * Every value here is otherwise reachable only by reading code or stepping through a console, and each is the
 * sort of thing wanted at the moment something is behaving unexpectedly rather than in advance.
 */
val DebugState = FC<Props> {
    val config = appConfig()
    val generation = useRefreshGeneration()
    div {
        className = ClassName("card wide")
        h1 { +"App state" }
        h2 { +"Resolved app config" }
        pre {
            className = ClassName("code")
            +mapOf(
                "obfuscateSensitiveErrors" to config.obfuscateSensitiveErrors,
                "idleBumpIntervalMs" to config.idleBumpIntervalMs,
                "showErrorDetail" to config.showErrorDetail,
                "allowDebugPages" to config.allowDebugPages,
                "isTestInstance" to config.isTestInstance,
                "isEnvAuthed" to config.isEnvAuthed,
                "envAuthDebug" to config.envAuthDebug,
            ).toJsonStr()
        }
        h2 { +"Refresh generation" }
        p {
            className = ClassName("subtitle")
            +("$generation — bumped by navigation, by the idle timer, and by state changes; every mounted " +
                "config consumer re-reads when it moves.")
        }
    }
}

private val debugFragmentScope = MainScope()

/**
 * Renders a **server-provided content element whose text pulls a fragment on the frontend** — the Phase 3
 * vertical slice of issue #505.
 *
 * The flow is the whole point, so the page shows each step: it fetches the element (`{fileId, buildId, text}`)
 * from the `fragmentDemo` fixture, then fetches *that file's* copy, builds a [Copy.fragmentResolver] over it,
 * and evaluates `text` with that resolver. A `${@t("namespace.key")}` in the text resolves against the named
 * file's copy — which the element names, because a content string is not itself a fragment file and so has no
 * ambient file context. Both the raw template and the resolved Markdown are shown, so the substitution is
 * visible rather than implied.
 */
val DebugFragment = FC<Props> {
    var fileId by useState("")
    var rawText by useState<String?>(null)
    var resolved by useState<String?>(null)
    var error by useState<String?>(null)
    val generation = useRefreshGeneration()

    useEffect(generation) {
        debugFragmentScope.launch {
            try {
                suspend fun element(): Map<String, Any?> =
                    Http.getApi(TEP.fragmentDemo)[EP.results].toJsonMapOrEmpty()
                fun refOf(e: Map<String, Any?>) =
                    FragmentRef(e[UIC.fileId] as? String ?: "", e[UIC.buildId] as? String ?: "")

                val el = element()
                val fid = el[UIC.fileId] as? String ?: ""
                val text = el[TEP.demoText] as? String ?: ""
                // Recover a stale build id the way every other copy fetch does (issue #469): a rolling deploy
                // leaves this ref one deploy old, which 404s -- re-fetch the element for a fresh ref and retry
                // once, rather than reporting the misleading "is the runtime running" below.
                val copy = fetchCopyWithRetry(refOf(el)) { runCatching { refOf(element()) }.getOrNull() }
                // The fragment pull resolves against the file's copy; `demoVar` shows a plain substitution too.
                fileId = fid
                rawText = text
                resolved = text.evalTemplate(mapOf(TEP.demoVar to "42"), resolver = copy.fragmentResolver())
                error = null
            } catch (e: Throwable) {
                error = "Could not render the fragment demo — is the runtime running with test endpoints? (${e.message})"
            }
        }
    }

    div {
        className = ClassName("card wide")
        h1 { +"Fragment pull" }
        p {
            className = ClassName("subtitle")
            +("A content element from the server carries a fileId and a template string. Its backend %{@t(...)} " +
                "pull is already resolved server-side; the frontend fetches that file's copy and resolves the " +
                "remaining \${@t(...)} pull here.")
        }
        if (error != null) {
            p { className = ClassName("error"); +error!! }
        } else if (rawText == null) {
            p { className = ClassName("subtitle"); +"Loading…" }
        } else {
            h2 { +"Element" }
            pre { className = ClassName("code"); +"fileId: $fileId" }
            h2 { +"Template (as delivered — backend pull already resolved)" }
            pre { className = ClassName("code"); +rawText!! }
            h2 { +"Resolved and rendered" }
            Markdown { source = resolved ?: "" }
        }
    }
}
