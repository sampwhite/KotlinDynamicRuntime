package com.dynamicruntime.webapp

import com.dynamicruntime.common.endpoint.EP
import com.dynamicruntime.common.endpoint.EndpointKind
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Pure-logic coverage for the envelope line the catalog shows above a response payload (issue #321) -- the
 * fields `renderResponse` used to drop because it rendered only the payload.
 *
 * The line is built by a pure function precisely so it can be tested here: what it says, and what it declines
 * to say, are decisions, and the alternative is checking them by eye in a browser every time.
 */
class EnvelopeSummaryTest {

    private fun list(vararg pairs: Pair<String, Any?>) = mapOf(*pairs)

    // --- the count, which is what the issue reported missing ---------------------

    @Test
    fun aListReportsHowManyItemsCameBack() {
        assertEquals("3 items", envelopeSummary(EndpointKind.list.name, list(EP.numItems to 3L)))
    }

    /** One item is not "1 items". Small, but this line is read on every call. */
    @Test
    fun oneItemReadsAsSingular() {
        assertEquals("1 item", envelopeSummary(EndpointKind.list.name, list(EP.numItems to 1L)))
        assertEquals("0 items", envelopeSummary(EndpointKind.list.name, list(EP.numItems to 0L)))
    }

    /**
     * The count is a protocol field, but the items are the truth. If a response ever arrives without the
     * count, saying nothing would be worse than counting what is plainly there.
     */
    @Test
    fun aMissingCountFallsBackToCountingTheItems() {
        assertEquals("2 items", envelopeSummary(EndpointKind.list.name, list(EP.items to listOf(1, 2))))
    }

    // --- the paging fields, which are declared but not yet populated -------------

    /**
     * `hasMore` and `numAvailable` are opt-in on `listEndpoint` and are not populated by execution yet, so the
     * summary must read them strictly when present -- never inventing "more available" from their absence.
     */
    @Test
    fun pagingFieldsAppearOnlyWhenPresent() {
        assertEquals("5 items", envelopeSummary(EndpointKind.list.name, list(EP.numItems to 5L)))
        assertEquals(
            "5 items · more available",
            envelopeSummary(EndpointKind.list.name, list(EP.numItems to 5L, EP.hasMore to true)),
        )
        assertEquals(
            "5 items · 42 in total",
            envelopeSummary(EndpointKind.list.name, list(EP.numItems to 5L, EP.numAvailable to 42L)),
        )
        // Present and false is not "more available" either.
        assertEquals(
            "5 items",
            envelopeSummary(EndpointKind.list.name, list(EP.numItems to 5L, EP.hasMore to false)),
        )
    }

    // --- duration, on every kind -------------------------------------------------

    @Test
    fun durationIsShownForEveryKind() {
        assertEquals("12ms", envelopeSummary(EndpointKind.general.name, list(EP.duration to 12.0)))
        assertEquals("12ms", envelopeSummary(EndpointKind.item.name, list(EP.duration to 12.0)))
        assertEquals(
            "1 item · 12ms",
            envelopeSummary(EndpointKind.list.name, list(EP.numItems to 1L, EP.duration to 12.0)),
        )
    }

    /**
     * The reason duration is rounded rather than truncated: `durationMs()` is a Double, so a fast call would
     * otherwise render as "0ms" and read as a broken timer instead of a fast endpoint.
     */
    @Test
    fun aSubMillisecondCallDoesNotCollapseToZero() {
        assertEquals("0.42ms", envelopeSummary(EndpointKind.general.name, list(EP.duration to 0.4237)))
        assertEquals("4.3ms", envelopeSummary(EndpointKind.general.name, list(EP.duration to 4.344833)))
        assertEquals("43ms", envelopeSummary(EndpointKind.general.name, list(EP.duration to 43.4)))
    }

    @Test
    fun roundMsKeepsPrecisionWhereItMatters() {
        assertEquals("0.01", roundMs(0.008))
        assertEquals("0.5", roundMs(0.5))
        assertEquals("1.5", roundMs(1.54))
        // Below 10ms a decimal is still worth keeping; at or above it, whole milliseconds are enough.
        assertEquals("9.8", roundMs(9.8))
        assertEquals("10", roundMs(10.4))
        assertEquals("1234", roundMs(1234.4))
    }

    // --- what it declines to say --------------------------------------------------

    /**
     * These three are deliberately absent: `contentHash` is for a machine to compare between fetches,
     * `requestUri` echoes the request just submitted on the same screen, and `webAppHash` is identical on
     * every response. The raw panel below still carries all of them.
     */
    @Test
    fun theMachineFacingAndRedundantFieldsAreLeftOut() {
        val summary = envelopeSummary(
            EndpointKind.general.name,
            list(
                EP.duration to 5.0,
                EP.contentHash to "a1b2c3",
                EP.requestUri to "/kda/gedra/formDocs",
                EP.webAppHash to "deadbeef",
            ),
        )
        assertEquals("5ms", summary)
    }

    /** Nothing to say means no line at all, rather than an empty one taking up space. */
    @Test
    fun anEnvelopeWithNothingToReportRendersNoLine() {
        assertNull(envelopeSummary(EndpointKind.general.name, emptyMap()))
        assertNull(envelopeSummary(EndpointKind.item.name, list(EP.contentHash to "a1b2c3")))
    }
}
