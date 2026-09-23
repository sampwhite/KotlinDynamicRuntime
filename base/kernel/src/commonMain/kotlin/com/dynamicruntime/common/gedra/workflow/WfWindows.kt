package com.dynamicruntime.common.gedra.workflow

import com.dynamicruntime.common.exception.KdrException
import com.dynamicruntime.common.util.toJsonMapOrEmpty
import kotlin.time.Instant

/**
 * One time window of a normal workflow (issue #790): half-open, `[start, end)`. A null bound constrains nothing,
 * so a window with neither is always open.
 */
class WfWindow(val start: Instant? = null, val end: Instant? = null) {
    /** Whether [now] falls in the window: at or after [start], and before [end]. */
    fun contains(now: Instant): Boolean = (start == null || now >= start) && (end == null || now < end)

    /** Whether the window names neither bound. */
    val isEmpty: Boolean get() = start == null && end == null

    /** The window as its JSON form, bounds that are set only. */
    fun toJsonMap(): Map<String, Any?> = buildMap {
        start?.let { put(WFD.start, it) }
        end?.let { put(WFD.end, it) }
    }

    override fun toString(): String = "[${start ?: "-"}, ${end ?: "-"})"

    companion object {
        val always = WfWindow()

        /** A window from its JSON form, already coerced by the definition schema; absent reads as [always]. */
        fun fromJson(raw: Any?): WfWindow {
            val m = raw.toJsonMapOrEmpty()
            return WfWindow(m[WFD.start] as? Instant, m[WFD.end] as? Instant)
        }
    }
}

/**
 * Where a workflow stands in its time windows at a moment (issue #790), from the outside in. Each phase implies
 * the ones before it, because the windows nest: engagement inside relevancy inside lifetime.
 */
@Suppress("EnumEntryName")
enum class WfPhase {
    /** Outside the lifetime: as if the workflow were not configured. Its stored data is kept, but nothing shows it. */
    outsideLifetime,

    /**
     * In the lifetime but outside relevancy: nothing is calculated for it. A form engaged with it keeps the state
     * last calculated -- **frozen**, and read-only -- and a form not engaged with it has no state for it.
     */
    lifetimeOnly,

    /** In relevancy but outside engagement: calculated, eligibility included, but no form may newly engage. */
    relevant,

    /** In the engagement window: calculated, and a form may engage with it. */
    engageable;

    /** Whether the workflow exists at all for searches, pages and endpoints. */
    val exists: Boolean get() = this != outsideLifetime

    /** Whether the workflow's state is calculated -- and so whether its tasks may be saved and approved. */
    val calculates: Boolean get() = this == relevant || this == engageable

    /**
     * Whether the workflow is shown for a form: it exists, and either a form may still engage with it or this
     * form already is. The design's "outside the engagement window and not engaged, it is ignored for search and
     * display".
     */
    fun isShown(engaged: Boolean): Boolean = exists && (this == engageable || engaged)
}

/**
 * A normal workflow's three nested, optional time windows (issue #790): [lifetime], [relevancy] inside it, and
 * [engagement] inside that. Held as declared; [effectiveRelevancy] and [effectiveEngagement] resolve the
 * fallbacks the design gives a narrower window's missing bound -- a **start** falls back to the enclosing
 * window's start, an **end** forward to its end -- so a relevancy with no end lasts as long as the lifetime.
 * A bound nothing supplies constrains nothing.
 *
 * Every bound is an absolute instant, and the moment compared against is the caller's (`cxt.now()`), so a
 * window opens and closes with no batch job -- what is stored about a form catches up on its next recompute.
 */
class WfWindows(
    val lifetime: WfWindow = WfWindow.always,
    val relevancy: WfWindow = WfWindow.always,
    val engagement: WfWindow = WfWindow.always,
) {
    /** Relevancy with its missing bounds taken from the lifetime. */
    val effectiveRelevancy: WfWindow =
        WfWindow(relevancy.start ?: lifetime.start, relevancy.end ?: lifetime.end)

    /** Engagement with its missing bounds taken from the effective relevancy. */
    val effectiveEngagement: WfWindow =
        WfWindow(engagement.start ?: effectiveRelevancy.start, engagement.end ?: effectiveRelevancy.end)

    /** Whether no window declares any bound, which is every workflow's default. */
    val isEmpty: Boolean get() = lifetime.isEmpty && relevancy.isEmpty && engagement.isEmpty

    /** Where the workflow stands at [now]. */
    fun phaseAt(now: Instant): WfPhase = when {
        !lifetime.contains(now) -> WfPhase.outsideLifetime
        !effectiveRelevancy.contains(now) -> WfPhase.lifetimeOnly
        !effectiveEngagement.contains(now) -> WfPhase.relevant
        else -> WfPhase.engageable
    }

    /**
     * Refuses windows that do not nest or are empty, naming the workflow -- rather than clamping, which would
     * quietly move a date someone wrote down. Each resolved window must start before it ends, and a narrower
     * window's own bounds must lie inside the enclosing one's.
     */
    fun check(workflowId: String) {
        fun refuse(reason: String): Nothing =
            throw KdrException.mkConv("The time windows of workflow '$workflowId' are not valid: $reason")
        for ((name, w) in listOf(WFD.lifetime to lifetime, WFD.relevancy to effectiveRelevancy, WFD.engagement to effectiveEngagement)) {
            if (w.start != null && w.end != null && w.start >= w.end) {
                refuse("its $name window $w does not start before it ends.")
            }
        }
        fun within(name: String, own: WfWindow, outerName: String, outer: WfWindow) {
            if (own.start != null && outer.start != null && own.start < outer.start) {
                refuse("its $name window starts at ${own.start}, before its $outerName window does (${outer.start}).")
            }
            if (own.end != null && outer.end != null && own.end > outer.end) {
                refuse("its $name window ends at ${own.end}, after its $outerName window does (${outer.end}).")
            }
        }
        within(WFD.relevancy, relevancy, WFD.lifetime, lifetime)
        within(WFD.engagement, engagement, WFD.relevancy, effectiveRelevancy)
    }

    companion object {
        val none = WfWindows()
    }
}
