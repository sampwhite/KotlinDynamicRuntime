package com.dynamicruntime.webapp

import com.dynamicruntime.common.gedra.CLD
import com.dynamicruntime.common.gedra.CWF
import com.dynamicruntime.common.gedra.IMP
import com.dynamicruntime.common.gedra.workflow.WFD
import com.dynamicruntime.common.gedra.workflow.WfPhase
import com.dynamicruntime.common.gedra.workflow.WfWindow
import com.dynamicruntime.common.gedra.workflow.WfWindows
import com.dynamicruntime.common.util.formatDate
import com.dynamicruntime.common.util.toJsonMapOrEmpty
import kotlin.time.Instant
import com.dynamicruntime.common.gedra.workflow.WfParamsSetting
import com.dynamicruntime.common.gedra.workflow.WfParams
import com.dynamicruntime.common.gedra.workflow.WFP
import com.dynamicruntime.common.gedra.isSandboxClient

/**
 * A normal workflow's time windows on the client page (issue #1121; `WorkflowWindowsEditor`): the three windows in plain
 * words, what a missing bound falls back to, where the workflow stands at a moment, and the timeline's layout. The
 * windows, their fallbacks and `phaseAt` are the kernel's (`WfWindows`), so the page says what the backend will run.
 *
 * Everything here is pure, and covered under `jsNodeTest`.
 */

/** One of the three nested windows, in the editor's words. */
@Suppress("EnumEntryName")
enum class WindowBand(val label: String, val explained: String) {
    lifetime("Available", "Outside it, the workflow is as good as not configured: nothing shows it, though its data is kept."),
    relevancy("Tracked", "Inside it, the workflow is calculated. Outside it, a form engaged with it keeps its last state, read-only."),
    engagement("New forms may join", "Inside it, a form may newly take part in the workflow.");

    /** This band's window as declared in [windows], a missing bound unset. */
    fun declared(windows: WfWindows): WfWindow = when (this) {
        lifetime -> windows.lifetime
        relevancy -> windows.relevancy
        engagement -> windows.engagement
    }

    /** This band's window as it runs: a missing bound taken from the band enclosing it. */
    fun effective(windows: WfWindows): WfWindow = when (this) {
        lifetime -> windows.lifetime
        relevancy -> windows.effectiveRelevancy
        engagement -> windows.effectiveEngagement
    }

    /** The band enclosing this one, whose bounds a missing one of this one's falls back to; null for the lifetime. */
    val enclosing: WindowBand? get() = when (this) {
        lifetime -> null
        relevancy -> lifetime
        engagement -> relevancy
    }
}

/** These windows with [band]'s start (or, with [isStart] false, end) set to [at], or unset when [at] is null. */
fun WfWindows.withBound(band: WindowBand, isStart: Boolean, at: Instant?): WfWindows {
    val old = band.declared(this)
    val window = if (isStart) WfWindow(at, old.end) else WfWindow(old.start, at)
    return when (band) {
        WindowBand.lifetime -> WfWindows(window, relevancy, engagement)
        WindowBand.relevancy -> WfWindows(lifetime, window, engagement)
        WindowBand.engagement -> WfWindows(lifetime, relevancy, window)
    }
}

/**
 * What a missing bound of [band] means, for its empty field (issue #1121): the lifetime's is no bound at all; a
 * narrower window's is its enclosing window's, which is said with the moment that comes to when there is one.
 */
fun boundFallbackText(windows: WfWindows, band: WindowBand, isStart: Boolean, zone: String): String {
    val outer = band.enclosing ?: return if (isStart) "No start" else "No end"
    val inherited = outer.effective(windows).let { if (isStart) it.start else it.end }
    val same = "Same as ${outer.label}"
    return inherited?.let { "$same (${formatTimestamp(it.formatDate(), zone)})" } ?: same
}

/** A moment's distance from [now], in the tables' words: "in 3h", "5m ago", or the day for one further off. */
private fun whenText(at: Instant, now: Instant, zone: String): String {
    val relative = formatRelativeTime(at.formatDate(), now, zone)
    return if (relative.startsWith("in ") || relative.endsWith(" ago") || relative == "just now") relative else "on $relative"
}

/**
 * Where a workflow with [windows] stands at [at], in words, with what changes next (issue #1121): "Open to new forms;
 * closes to new forms in 5d". [now] is what the next change is measured from -- the moment itself for the current
 * phase, the real now for an "as of" preview.
 */
fun windowPhaseText(windows: WfWindows, at: Instant, zone: String, now: Instant = at): String {
    fun next(what: String, moment: Instant?) = moment?.takeIf { it > at }?.let { "; $what ${whenText(it, now, zone)}" }.orEmpty()
    val lifetime = windows.lifetime
    val tracked = windows.effectiveRelevancy
    val joining = windows.effectiveEngagement
    return when (windows.phaseAt(at)) {
        WfPhase.engageable -> "Open to new forms" + next("closes to new forms", joining.end)
        WfPhase.relevant -> {
            val opens = joining.start?.takeIf { it > at }
            if (opens != null) "Tracked, not yet open to new forms" + next("opens to new forms", opens)
            else "Tracked, closed to new forms" + next("tracking ends", tracked.end)
        }
        WfPhase.lifetimeOnly -> {
            val starts = tracked.start?.takeIf { it > at }
            if (starts != null) "Available, not yet tracked" + next("tracking starts", starts)
            else "No longer tracked: forms engaged with it keep their last state" + next("stops being available", lifetime.end)
        }
        WfPhase.outsideLifetime -> lifetime.start?.takeIf { it > at }?.let { "Not available yet" + next("becomes available", it) }
            ?: "No longer available"
    }
}

/** One band of the timeline: where it starts and ends across the track, in percent, and whether it runs off an edge. */
class TimelineBand(val band: WindowBand, val left: Double, val right: Double, val openStart: Boolean, val openEnd: Boolean)

/** The timeline's layout: a band per window, and where the markers stand across the track, in percent. */
class TimelineLayout(val bands: List<TimelineBand>, val nowAt: Double, val asOfAt: Double?)

/**
 * Lays the three windows out on one track (issue #1121), over the span from the earliest moment shown to the latest --
 * every bound set, [now], and [asOf] -- with a margin either side, so a band with no start or end runs off the track's
 * edge. With no bound set, every band runs the whole track.
 */
fun timelineLayout(windows: WfWindows, now: Instant, asOf: Instant? = null): TimelineLayout {
    val moments = WindowBand.entries.flatMap { listOfNotNull(it.effective(windows).start, it.effective(windows).end) } + listOfNotNull(now, asOf)
    val first = moments.min().toEpochMilliseconds().toDouble()
    val last = moments.max().toEpochMilliseconds().toDouble()
    // A margin of a tenth either side, and a day when everything is one moment, so a marker never sits on the edge.
    val margin = maxOf((last - first) / 10, 86_400_000.0)
    val from = first - margin
    val span = last + margin - from
    fun at(moment: Instant): Double = (moment.toEpochMilliseconds() - from) / span * 100
    val bands = WindowBand.entries.map { band ->
        val w = band.effective(windows)
        TimelineBand(band, w.start?.let(::at) ?: 0.0, w.end?.let(::at) ?: 100.0, w.start == null, w.end == null)
    }
    return TimelineLayout(bands, at(now), asOf?.let(::at))
}

/**
 * The windows save's request (issue #1356): the client or sandbox, the workflow, its parameters with [windows] as the
 * moments they are -- none set, none sent, which is no limits -- and an acknowledgement.
 */
fun windowsSaveRequest(clientId: String, workflowId: String, windows: WfWindows, acknowledge: Boolean): Map<String, Any?> =
    buildMap {
        put(CLD.client, clientId)
        put(WFD.workflowId, workflowId)
        put(
            WFP.params,
            WfParams(windows).toInfo().mapValues { (_, ws) ->
                (ws as Map<*, *>).mapValues { (_, w) -> (w as Map<*, *>).mapValues { (_, at) -> (at as Instant).formatDate() } }
            },
        )
        if (acknowledge) put(IMP.acknowledgeImpact, true)
    }

/** What a windows save did: the workflow's parameters here now, with the setting it replaced among the earlier ones. */
fun parseWindowsSaveResult(raw: Map<String, Any?>): WorkflowParamsView = parseWorkflowParams(raw[CWF.params].toJsonMapOrEmpty())

/**
 * How a windows save takes effect (issue #1356), said beside Save: at once, in the client or sandbox [clientId] names,
 * with no draft and no publish -- and from a sandbox, never reaching its client.
 */
fun windowsEffectText(clientId: String): String =
    if (isSandboxClient(clientId)) {
        "Saving takes effect at once in this sandbox, and never reaches its client: a publish carries no time windows."
    } else {
        "Saving takes effect at once for this client's people, with no draft and no publish."
    }

/** Who set [setting] and when, as the editor says it ("Set Oct 20, 2026, 9:30 AM CT by Pat Lee"); null for values nobody set. */
fun settingByline(setting: WfParamsSetting, zone: String): String? {
    val at = setting.setAt ?: return null
    val by = setting.setByName ?: setting.setBy?.let { "user $it" }
    return "Set ${formatTimestamp(at.formatDate(), zone)}" + (by?.let { " by $it" } ?: "")
}

/**
 * [windows] in a line, as the history lists an earlier setting: each window that has a bound, from when to when in
 * [zone] -- "Available: from Oct 1, 2026, 9:00 AM CT; New forms may join: until Oct 20, 2026, 5:00 PM CT" -- or "No
 * limits" for none.
 */
fun windowsSummaryText(windows: WfWindows, zone: String): String {
    val parts = WindowBand.entries.mapNotNull { band ->
        val w = band.declared(windows)
        if (w.isEmpty) return@mapNotNull null
        val from = w.start?.let { "from ${formatTimestamp(it.formatDate(), zone)}" }
        val until = w.end?.let { "until ${formatTimestamp(it.formatDate(), zone)}" }
        "${band.label}: ${listOfNotNull(from, until).joinToString(" ")}"
    }
    return parts.joinToString("; ").ifEmpty { "No limits" }
}
