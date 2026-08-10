package com.dynamicruntime.script

import com.dynamicruntime.common.exception.KdrException
import com.dynamicruntime.common.http.request.ROLE
import com.dynamicruntime.common.util.toJsonStr
import kotlin.system.exitProcess

/**
 * Drives a **running** instance from the command line, as a chosen caller (issue #215):
 *
 * ```sh
 * kdr-probe [--url http://localhost:7071] <scenario> [args...]
 * kdr-probe call [--as user|operator|admin] GET /schema/endpoints [k=v ...]
 * ```
 *
 * It is a **host for scenarios**, not a single-purpose tool, and that shape is forced by the problem rather
 * than chosen. Anything spanning more than one call needs session state across those calls; if scenarios were
 * composed by invoking a one-shot repeatedly, that state would live in cookie files threaded through shell --
 * relocating the very mistake this replaces, with argument quoting added on top. So the rule is: **one call
 * may be flags; more than one call is a scenario here, never shell.**
 *
 * Scenarios **report**; they do not assert. Anything worth asserting belongs in kotest, where it runs on every
 * `check` -- see [ProbeSession]'s note on the naming that keeps promotion mechanical. This covers the pass
 * before that, and the things an in-process test cannot see: real cookies, the real dispatcher, live roles.
 *
 * Adding one is meant to be routine -- a function and a line in [scenarios]. A check about to be done for the
 * second time is already a scenario; the hand-rolled version is the one that silently lies.
 *
 * It talks HTTP to a server that is already up (start one with `KDR_PORT=7071 KDR_IN_MEMORY_ONLY=true
 * kdr-backend`), rather than booting the runtime in-process the way [GrantRole] does. Exercising the real
 * dispatcher, real cookies and the live-role refresh is the entire point; an in-process client would skip the
 * layer those defects live in. It depends on the test-only `becomeUser` endpoint, so it only ever works
 * against a test instance -- no separate gating needed.
 */
object Probe {
    /** Exit code for a usage error or a scenario that could not run. */
    const val failureExit = 1

    /**
     * The last line of every run, one marker or the other. A reader who has piped the output through `grep`
     * or `tail` no longer has the exit code, so the report has to say for itself whether it finished.
     */
    const val completedMarker = "kdr-probe: completed"
    const val failedMarker = "kdr-probe: FAILED"

    /** Flags accepted before the scenario name. */
    @Suppress("ConstPropertyName")
    object PRF {
        const val url = "--url"
        const val callAs = "--as"
    }

    /** The one-shot verb, for a single call that needs no session across requests. */
    const val callVerb = "call"

    /**
     * Scenario name to implementation. An explicit registry rather than reflection: the set is small, listing
     * it is the documentation, and a name that does not exist should fail immediately with the list of ones
     * that do -- not with a class-not-found several frames down.
     */
    val scenarios: Map<String, (ProbeContext) -> Unit> = linkedMapOf(
        catalogDiffName to ::catalogDiff,
        accessMatrixName to ::accessMatrix,
        grantThenCallName to ::grantThenCall,
    )
}

/** What a scenario is handed: where the instance is, and whatever arguments followed its name. */
class ProbeContext(val baseUrl: String, val args: List<String>) {
    /** A fresh session for [label], against this run's instance. */
    fun session(label: String): ProbeSession = ProbeSession(label, baseUrl)

    /**
     * A session already logged in at [level], using an address derived from the level so repeated runs against
     * one long-lived instance keep getting the same user. (`becomeUser` applies a level only when it *creates*
     * the user, so a level-specific address is what keeps a rerun from silently reusing a lesser account.)
     * The anonymous "level" is a session that never logs in.
     */
    fun sessionAt(level: String?): ProbeSession {
        val session = session(level ?: anonymousLabel)
        if (level != null) {
            session.becomeUser("probe-$level@example.com", level)
        }
        return session
    }
}

/** The label used for a caller that never logs in. */
const val anonymousLabel = "anonymous"

/** The ladder rungs a scenario walks, weakest first, with anonymous ahead of them all. */
val probeLevels: List<String?> = listOf(null, ROLE.user, ROLE.operator, ROLE.admin)

fun main(args: Array<String>) {
    var baseUrl = defaultProbeUrl
    val rest = args.toMutableList()
    while (rest.isNotEmpty() && rest[0].startsWith("--")) {
        when (rest[0]) {
            Probe.PRF.url -> {
                if (rest.size < 2) usage("${Probe.PRF.url} needs a value, e.g. ${Probe.PRF.url} $defaultProbeUrl")
                baseUrl = rest[1].trimEnd('/')
                repeat(2) { rest.removeAt(0) }
            }
            else -> usage("Unknown option '${rest[0]}'.")
        }
    }
    if (rest.isEmpty()) {
        usage("No scenario given.")
    }

    val name = rest.removeAt(0)
    val cxt = ProbeContext(baseUrl, rest)
    try {
        if (name == Probe.callVerb) {
            oneShotCall(cxt)
        } else {
            val scenario = Probe.scenarios[name] ?: usage("Unknown scenario '$name'.")
            scenario(cxt)
        }
    } catch (e: Throwable) {
        // A probe that cannot do its job says so and stops. Reporting a partial result is what makes a broken
        // harness look like a finding about the code -- the mistake this whole tool exists to retire.
        //
        // Catching Throwable rather than KdrException on purpose, and telling the two apart. A KdrException is
        // the probe working correctly and reporting that it cannot proceed (almost always the instance);
        // anything else is a defect in the probe, and saying so stops a reader from taking it as news about
        // the code under test. Left uncaught, the second kind printed a bare JVM stack trace with no marker at
        // all, which is exactly the shape a reader skims past.
        println()
        if (e is KdrException) {
            println("${Probe.failedMarker} $name -- ${e.fullMessage()}")
        } else {
            println("${Probe.failedMarker} $name -- probe defect: ${e::class.simpleName}: ${e.message}")
            e.printStackTrace()
        }
        exitProcess(Probe.failureExit)
    }
    // The sentinel, and the reason it is unconditional. Scenarios print as they go, so a run that dies partway
    // leaves output that reads as a short but finished report -- and reading only the table (or grepping for
    // it) loses the exit code entirely. Absence of this last line means the report is incomplete, however
    // complete it looks.
    println()
    println("${Probe.completedMarker} $name")
}

/**
 * `call [--as level] METHOD /path [k=v ...]` -- one request, printed. A GET's `k=v` pairs become query
 * parameters; any other method sends them as a JSON body.
 */
private fun oneShotCall(cxt: ProbeContext) {
    val args = cxt.args.toMutableList()
    var level: String? = null
    if (args.isNotEmpty() && args[0] == Probe.PRF.callAs) {
        if (args.size < 2) usage("${Probe.PRF.callAs} needs a level: ${ROLE.user}, ${ROLE.operator} or ${ROLE.admin}.")
        level = args[1]
        repeat(2) { args.removeAt(0) }
    }
    if (args.size < 2) {
        usage("call needs a method and a path, e.g. call GET /schema/endpoints")
    }
    val method = args.removeAt(0).uppercase()
    val path = args.removeAt(0)
    val params = args.mapNotNull { pair ->
        val at = pair.indexOf('=')
        if (at <= 0) null else pair.substring(0, at) to pair.substring(at + 1)
    }.toMap()

    val session = cxt.sessionAt(level)
    val response = if (method == "GET") {
        session.sendGetRequest(path, params)
    } else {
        session.sendPostRequest(path, params)
    }
    println("$method $path as ${session.label} -> HTTP ${response.statusCode}")
    println(response.body.toJsonStr())
}

/** Prints [message], the usage, and exits non-zero. Never returns. */
private fun usage(message: String): Nothing {
    println(message)
    println()
    println("Usage: kdr-probe [${Probe.PRF.url} <baseUrl>] <scenario> [args...]")
    println("       kdr-probe ${Probe.callVerb} [${Probe.PRF.callAs} <level>] <METHOD> <path> [k=v ...]")
    println()
    println("Scenarios:")
    for (name in Probe.scenarios.keys) {
        println("  $name")
    }
    println()
    println("Levels: ${ROLE.user}, ${ROLE.operator}, ${ROLE.admin} (omit for anonymous).")
    println("Default instance: $defaultProbeUrl -- start one with")
    println("  KDR_PORT=7071 KDR_IN_MEMORY_ONLY=true kdr-backend")
    exitProcess(Probe.failureExit)
}
