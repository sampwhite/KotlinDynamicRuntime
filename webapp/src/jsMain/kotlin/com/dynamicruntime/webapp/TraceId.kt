package com.dynamicruntime.webapp

import com.dynamicruntime.common.util.formatCompactId
import kotlin.random.Random
import kotlin.time.Clock

/**
 * Mints a trace id for each backend call (issue #105): the same compact UTC timestamp the backend's
 * `mkUniqueId` uses (`formatCompactId`, millisecond-granular), plus a two-digit counter.
 *
 * The frontend needs far less uniqueness than the backend. The millisecond timestamp does most of the work,
 * the device cookie separates devices, and the whole point is troubleshooting, not security — so the suffix
 * only has to tell apart calls that share a millisecond on one device. A counter does that deterministically
 * and compactly, where randomness would need more characters for the same guarantee.
 *
 * The counter **starts at a random 1..99** rather than 1: two tabs load with independent module state, so a
 * fixed start would have them both begin at 1 and mint the same id if they fire in the same millisecond; a
 * random start makes that coincidence unlikely without spending randomness per id. It wraps back to 1 after
 * 99 (two digits is ample — a browser cannot issue 100 requests within one millisecond).
 *
 * The id leads with **`f`** (frontend). The backend's own ids start with the timestamp's leading digit, so the
 * letter both distinguishes the two and, more usefully, tells a person who meets the id *outside* a log line —
 * pasted into a chat, say — that it came from the browser. (`b` is left free for a backend counterpart.)
 */
private const val traceIdPrefix = "f"

private var counter = Random.nextInt(1, 100)

/** The next trace id, e.g. `f20260717...07`: prefix + compact timestamp + counter. Set as `X-Kdr-Trace-Id`. */
fun nextTraceId(): String {
    val n = counter
    counter = if (counter >= 99) 1 else counter + 1
    return traceIdPrefix + Clock.System.now().formatCompactId() + n.toString().padStart(2, '0')
}
