package com.dynamicruntime.webapp

import com.dynamicruntime.common.endpoint.EI
import com.dynamicruntime.common.endpoint.EP
import com.dynamicruntime.common.endpoint.EndpointKind
import com.dynamicruntime.common.filestore.FMETA
import com.dynamicruntime.common.operator.FSB
import com.dynamicruntime.common.operator.TCI
import com.dynamicruntime.common.operator.TCS
import com.dynamicruntime.common.schema.PRES
import com.dynamicruntime.common.schema.PSTAT
import com.dynamicruntime.common.schema.SCH
import com.dynamicruntime.common.schema.SCT
import com.dynamicruntime.common.schema.SL
import com.dynamicruntime.common.schema.SchType
import com.dynamicruntime.common.schema.parseSchemaTypes
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

// The operator pages and the endpoint catalog (issue #1740 gathered these into one file): the verdict an operator list
// leads with, an object drawn as panels, the cache state, the file-store browser, then the catalog's reader and
// filters, a response's envelope line, and the request JSON panel's parse, raw schema document, and file check. Each
// class is the file it used to be, kept whole with its own helpers.

/**
 * Pure-logic coverage (issue #161) for [operatorVerdict], the verdict-first summary an operator list page leads
 * with (issue #540). It reads the status column off the schema and counts the rows, so any list endpoint with a
 * `presentation: status` column gets a summary for free -- and a list without one gets none.
 */
class OperatorVerdictTest {

    /** An element type with a `presentation: status` column, built through the real parser (as the page does). */
    private fun withStatus(): SchType = parseSchemaTypes(
        mapOf(
            "Row" to mapOf(
                SCH.type to SCT.kObject,
                SCH.properties to mapOf(
                    "name" to mapOf(SCH.type to SCT.string),
                    "state" to mapOf(SCH.type to SCT.string, SCH.presentation to PRES.status),
                ),
            ),
        ),
    ).getValue("Row")

    private fun row(state: String) = mapOf("name" to "x", "state" to state)

    @Test
    fun leadsWithAllClearWhenNothingNeedsAttention() {
        // ok and info are both "fine"; only warning/error count as attention.
        val items = listOf(row(PSTAT.ok), row(PSTAT.info))
        assertEquals("All 2 OK.", operatorVerdict(withStatus(), items))
    }

    @Test
    fun countsWarningsAndErrorsAsAttention() {
        val items = listOf(row(PSTAT.ok), row(PSTAT.warning), row(PSTAT.error))
        assertEquals("2 of 3 need attention.", operatorVerdict(withStatus(), items))
    }

    @Test
    fun reportsNothingForAnEmptyList() {
        assertEquals("Nothing to report.", operatorVerdict(withStatus(), emptyList()))
    }

    @Test
    fun noVerdictWhenThereIsNoStatusColumn() {
        // A list whose element type declares no status column gets no summary line at all.
        val noStatus = parseSchemaTypes(
            mapOf(
                "Row" to mapOf(
                    SCH.type to SCT.kObject,
                    SCH.properties to mapOf("name" to mapOf(SCH.type to SCT.string)),
                ),
            ),
        ).getValue("Row")
        assertNull(operatorVerdict(noStatus, listOf(mapOf("name" to "x"))))
        assertNull(operatorVerdict(null, listOf(mapOf("name" to "x"))))
    }
}

/**
 * Pure-logic coverage for [objectPanels] (issue #1620): the panels an operator page draws a free-form object as, such
 * as System info. One per top-level section in the object's own order, and a leading Summary for any top-level
 * scalars, so every value belongs to a panel.
 */
class ObjectPanelsTest {

    @Test
    fun eachSectionIsAPanelInTheObjectsOwnOrder() {
        val obj = mapOf(
            "node" to mapOf("nodeId" to "n1"),
            "build" to mapOf("commit" to "abc"),
            "gcCollectors" to listOf(mapOf("name" to "G1")),
        )
        assertEquals(
            listOf(
                ObjectPanel("node", "op-key-node", "Node"),
                ObjectPanel("build", "op-key-build", "Build"),
                ObjectPanel("gcCollectors", "op-key-gcCollectors", "Gc collectors"),
            ),
            objectPanels(obj),
        )
    }

    @Test
    fun topLevelScalarsGoInALeadingSummary() {
        val obj = mapOf("node" to mapOf("nodeId" to "n1"), "pid" to 42)
        assertEquals(
            listOf(ObjectPanel(null, "op-summary", "Summary"), ObjectPanel("node", "op-key-node", "Node")),
            objectPanels(obj),
        )
    }

    @Test
    fun aSectionNamedSummaryKeepsItsOwnPanel() {
        val obj = mapOf("status" to "ok", "summary" to mapOf("count" to 3))
        val ids = objectPanels(obj).map { it.id }
        assertEquals(listOf("op-summary", "op-key-summary"), ids)
    }

    @Test
    fun anEmptyObjectHasNoPanels() {
        assertEquals(emptyList(), objectPanels(emptyMap()))
    }
}

/**
 * Pure-logic coverage (issue #161) for [cacheStateView], the join the cache-state operator page leads with
 * (issue #540): each of this node's caches paired with the shared row's last-changed date, the shared-row
 * tables this node holds no cache for, and the (deliberately light) attention count.
 */
class CacheStateViewTest {

    private fun cache(name: String, pending: Boolean = false, lastSeen: String? = "2026-01-01T00:00:00Z") =
        mapOf(
            TCI.tableName to name, TCI.topic to "auth", TCI.numRows to 3, TCI.highCounter to 7,
            TCI.lastSeen to lastSeen, TCI.pendingReload to pending,
        )

    @Test
    fun joinsCachesWithSharedRowAndCountsAttention() {
        val report = mapOf(
            TCS.nodeId to "10.0.0.1:7070",
            TCS.isDisabled to false,
            TCS.caches to listOf(cache("authUsers"), cache("gedra", pending = true)),
            TCS.sharedState to mapOf(
                "authUsers" to "2026-01-02T00:00:00Z",
                "otherNodeTable" to "2026-01-03T00:00:00Z",
            ),
        )
        val v = cacheStateView(report)
        assertEquals("10.0.0.1:7070", v.nodeId)
        assertEquals(false, v.isDisabled)
        assertEquals(2, v.rows.size)

        // The join is by table name: authUsers carries the shared date and is not flagged.
        val au = v.rows.single { it.tableName == "authUsers" }
        assertEquals("2026-01-02T00:00:00Z", au.sharedChanged)
        assertEquals(false, au.needsAttention)

        // gedra has a local write pending reload -> flagged; the shared row does not mention it.
        val g = v.rows.single { it.tableName == "gedra" }
        assertEquals(true, g.needsAttention)
        assertEquals(null, g.sharedChanged)

        // otherNodeTable is in the shared row but this node holds no cache for it -> unheld.
        assertEquals(listOf("otherNodeTable" to "2026-01-03T00:00:00Z"), v.unheldShared)

        // Attention is the light count: one pending row plus one unheld shared table.
        assertEquals(2, v.attentionCount)
    }

    @Test
    fun reportsCurrentWhenNothingPendingAndEverySharedTableIsHeld() {
        val report = mapOf(
            TCS.nodeId to "n1",
            TCS.isDisabled to false,
            TCS.caches to listOf(cache("authUsers")),
            TCS.sharedState to mapOf("authUsers" to "2026-01-02T00:00:00Z"),
        )
        val v = cacheStateView(report)
        assertEquals(0, v.attentionCount)
        assertTrue(v.unheldShared.isEmpty())
    }

    @Test
    fun toleratesAMissingOrDisabledReport() {
        val v = cacheStateView(mapOf(TCS.isDisabled to true))
        assertEquals("", v.nodeId)
        assertEquals(true, v.isDisabled)
        assertTrue(v.rows.isEmpty())
        assertEquals(0, v.attentionCount)
    }
}

/** The operator's file-store browser, its pure half (issue #1297): the answers read, the addresses, the words. */
class FileStoreBrowserTest {
    private val meta = mapOf(
        FMETA.mimeType to "image/png", FMETA.size to 895, FMETA.hash to "ab".repeat(32),
        FMETA.uploadedAt to "2026-10-20T10:00:00.000Z", FMETA.fileName to "favicon-32.png", FMETA.uploadedBy to 7, FMETA.client to "acme",
    )

    @Test
    fun theBucketsAndTheStoreAreReadFromTheEnvelope() {
        val (buckets, store) = parseBuckets(
            mapOf(
                EP.items to listOf(mapOf(FSB.name to "web-assets", FSB.description to "Images.")),
                EP.summary to mapOf(FSB.provider to "local", FSB.location to "/srv/files"),
            ),
        )
        assertEquals(listOf("web-assets" to "Images."), buckets.map { it.name to it.description })
        assertEquals("local" to "/srv/files", store?.let { it.provider to it.location })
        assertNull(parseBuckets(mapOf(EP.items to emptyList<Any?>())).second)
    }

    @Test
    fun aFolderIsReadWithEachFilesRecordAndState() {
        val listing = parseListing(
            mapOf(
                FSB.bucket to "web-assets", FSB.prefix to "clients/acme/web/", FSB.folders to listOf("old/"),
                FSB.files to listOf(
                    mapOf(FSB.key to "clients/acme/web/favicon", FSB.name to "favicon", FSB.meta to meta, FSB.modifiedAt to "2026-10-20T10:00:00.000Z", FSB.state to FSB.stateOk),
                    mapOf(FSB.key to "clients/acme/web/half", FSB.name to "half", FSB.modifiedAt to "2026-10-20T11:00:00.000Z", FSB.state to FSB.stateNoMeta),
                ),
                FSB.more to true,
            ),
        )
        assertEquals("clients/acme/web/", listing.prefix)
        assertEquals(listOf("old/"), listing.folders)
        assertEquals(true, listing.more)
        val (favicon, half) = listing.files
        assertEquals("favicon", favicon.name)
        assertEquals("image/png", favicon.file?.mimeType)
        assertEquals(895L, favicon.file?.size)
        assertEquals(7L, favicon.uploadedBy)
        assertEquals("acme", favicon.client)
        assertEquals("acme, user 7", storedByText(favicon))
        assertNull(half.file)
        assertEquals(FSB.stateNoMeta, half.state)
        assertEquals("—", storedByText(half))
        // The top of a bucket has no prefix in the answer, and reads as none.
        assertEquals("", parseListing(mapOf(FSB.bucket to "web-assets", FSB.folders to listOf("clients/"))).prefix)
    }

    @Test
    fun theBreadcrumbLeadsBackThroughEachFolder() {
        assertEquals(emptyList(), breadcrumbs(""))
        assertEquals(listOf("clients" to "clients/", "acme" to "clients/acme/", "web" to "clients/acme/web/"), breadcrumbs("clients/acme/web/"))
    }

    @Test
    fun theAddressesNameThePageTheBucketAndTheFolder() {
        assertEquals("/operator/files", fileStoreHref())
        assertEquals("/operator/files?bucket=web-assets", fileStoreHref("web-assets"))
        assertEquals("/operator/files?bucket=web-assets&folder=clients%2Facme%2F", fileStoreHref("web-assets", "clients/acme/"))
        // The folder as the address names it, read as the endpoint names one: a typed address without the slash still matches.
        assertEquals("", fileStoreFolder(null))
        assertEquals("", fileStoreFolder("  "))
        assertEquals("clients/acme/", fileStoreFolder("clients/acme"))
        assertEquals("clients/acme/", fileStoreFolder("clients/acme/"))
        // The download address is `Http.apiUrl`'s, which reads the page's own origin: checked in the browser, not here.
    }

    @Test
    fun aFilesStateIsSaidOnlyWhenSomethingIsWrong() {
        assertNull(fileStateText(FSB.stateOk))
        assertEquals("No record: a write that stopped part-way.", fileStateText(FSB.stateNoMeta))
        assertEquals("No content: its record answers for a file that is not there.", fileStateText(FSB.stateNoContent))
        assertEquals("Its record cannot be read.", fileStateText(FSB.stateUnreadableMeta))
    }
}

/**
 * Pure-logic coverage for the catalog reader (issue #585): a `/schema/endpoints` `results` map into a
 * [Catalog], with the out-of-band closures -- cfacts and now layouts -- parsed beside the schema. Maps in,
 * model out; the fetch that produces the map is not under test here.
 */
class CatalogModelTest {
    private val questionnaire = "sample.traits.QuestionnaireData"

    private fun results(): Map<String, Any?> = mapOf(
        EI.endpoints to listOf(
            mapOf(
                EI.path to "/gedra/formDoc/create", EI.method to "POST", EI.kind to "general", EI.namespace to "gedra",
                EI.inputSchema to mapOf(SCH.type to SCT.kObject), EI.outputSchema to mapOf(SCH.type to SCT.kObject),
            ),
        ),
        SCH.dDefs to mapOf(
            questionnaire to mapOf(
                SCH.type to SCT.kObject,
                SCH.properties to mapOf("topic" to mapOf(SCH.type to SCT.string), "notes" to mapOf(SCH.type to SCT.string)),
            ),
            "sample.traits.Plain" to mapOf(SCH.type to SCT.kObject),
        ),
        EI.filtersAvailable to false,
        EI.cfacts to mapOf("kdr:hasAdminLevel" to false),
        // The layouts closure (issue #585): only the type that declares one has an entry.
        EI.fieldLayouts to mapOf(
            questionnaire to mapOf(
                SL.fragmentFileId to "acme",
                SL.schemaFields to listOf(
                    mapOf(SL.field to "topic", SL.label to "Topic"),
                    mapOf(SL.field to "notes", SL.label to "Anything else?"),
                ),
            ),
        ),
    )

    @Test
    fun parsesTheLayoutsClosureBesideTheSchema() {
        val catalog = parseCatalog(results())
        assertEquals(1, catalog.endpoints.size)
        assertTrue(!catalog.filtersAvailable)
        assertEquals(false, catalog.cfacts["kdr:hasAdminLevel"])
        // The layout is joined to the type by the same key `$defs` and `defTypes` use.
        assertTrue(catalog.defTypes.containsKey(questionnaire))
        val layout = catalog.fieldLayouts[questionnaire]!!
        assertEquals("acme", layout.fragmentFileId)
        assertEquals(listOf("topic", "notes"), layout.fieldNames)
        assertEquals("Anything else?", layout.fields[1].label)
        // A type with no layout has no entry -- absent, not empty.
        assertNull(catalog.fieldLayouts["sample.traits.Plain"])
        // The served schema carries no layout keyword: the closure is the only place a layout travels.
        assertTrue(!(catalog.defs[questionnaire] as Map<*, *>).containsKey(SCH.layout))
    }

    @Test
    fun aResponseWithNoLayoutsKeyParsesToNoLayouts() {
        // An older node, or a catalog of types that declare none: the schema renders alone, nothing faults.
        val catalog = parseCatalog(results() - EI.fieldLayouts)
        assertTrue(catalog.fieldLayouts.isEmpty())
        assertTrue(catalog.defTypes.containsKey(questionnaire))
    }

    @Test
    fun aCatalogRowShowsALinkAsItsTextAlone() {
        // Issue #1476: the endpoint's page draws the link; its row, which is itself a click, keeps only the words.
        assertEquals(
            "What each slot holds is in the client configuration reference.",
            catalogRowText("What each slot holds is in [the client configuration reference](/docs/reference/client-configuration.md)."),
        )
        assertEquals("Plain, with `code` kept.", catalogRowText("Plain, with `code` kept."))
        assertEquals("", catalogRowText(null))
    }
}

/**
 * The endpoint-catalog filters (issue #489): the tag options a page offers, and how the publicApi toggle and a
 * multi-tag OR selection narrow the list. Pure -- lists of endpoints in, a filtered list out, no React.
 */
class CatalogFilterTest {

    private fun ep(path: String, publicApi: Boolean = false, tags: List<String> = emptyList()) =
        EndpointInfo(path, "GET", "general", "ns", null, emptyMap(), emptyMap(), publicApi, tags)

    private val endpoints = listOf(
        ep("/health", tags = listOf("internal")),
        ep("/auth/self/info", publicApi = true, tags = listOf("frontend")),
        ep("/auth/ui/config", tags = listOf("frontend")),
        ep("/gedra/formDoc/create", publicApi = true),
        ep("/demo/schema/sample", tags = listOf("demo", "schema")),
    )

    @Test
    fun availableTagsAreTheDistinctTagsSorted() {
        assertEquals(listOf("demo", "frontend", "internal", "schema"), availableTags(endpoints))
    }

    @Test
    fun noFiltersKeepsEverything() {
        assertEquals(endpoints, filterEndpoints(endpoints, publicOnly = false, selectedTags = emptySet()))
    }

    @Test
    fun publicOnlyKeepsOnlyPublishedEndpoints() {
        assertEquals(
            listOf("/auth/self/info", "/gedra/formDoc/create"),
            filterEndpoints(endpoints, publicOnly = true, selectedTags = emptySet()).map { it.path },
        )
    }

    @Test
    fun tagFilterIsOrAcrossTags() {
        // internal OR demo: /health and /demo/schema/sample, not the frontend-only or untagged ones.
        assertEquals(
            listOf("/health", "/demo/schema/sample"),
            filterEndpoints(endpoints, publicOnly = false, selectedTags = setOf("internal", "demo")).map { it.path },
        )
    }

    @Test
    fun publicOnlyAndTagsCombineWithAnd() {
        // Published AND carrying the frontend tag: only /auth/self/info (the gedra one is published but untagged).
        assertEquals(
            listOf("/auth/self/info"),
            filterEndpoints(endpoints, publicOnly = true, selectedTags = setOf("frontend")).map { it.path },
        )
    }
}

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

/**
 * Parsing of hand-edited request JSON (issue #191). The interesting cases are all failure cases — a payload
 * spliced by hand is routinely half-finished when it is first looked at — so most of this is about whether the
 * message tells the person where they broke it.
 */
class RawPayloadTest {

    @Test
    fun parsesAnObjectIntoValues() {
        val parse = parseRawPayload("""{"name":"widget","score":3.5,"active":true}""")
        assertNull(parse.error)
        val values = assertNotNull(parse.values)
        assertEquals("widget", values["name"])
        assertEquals(3, values.size)
    }

    @Test
    fun keepsNestedStructureIntact() {
        val parse = parseRawPayload("""{"input":{"address":{"street":"1 Main St"}},"tags":["a","b"]}""")
        assertNull(parse.error)
        val values = assertNotNull(parse.values)
        val input = values["input"] as Map<*, *>
        val address = input["address"] as Map<*, *>
        assertEquals("1 Main St", address["street"])
        assertEquals(2, (values["tags"] as List<*>).size)
    }

    // Clearing the box to start over is a normal editing move, not a mistake to report.
    @Test
    fun blankTextIsAnEmptyPayloadRatherThanAnError() {
        for (text in listOf("", "   ", "\n\n")) {
            val parse = parseRawPayload(text)
            assertNull(parse.error, "blank text should not be an error")
            assertEquals(emptyMap(), parse.values)
        }
    }

    @Test
    fun reportsWhereMalformedJsonBroke() {
        // An unquoted key several lines in -- what you get pasting a JavaScript object literal instead of JSON,
        // and the reason the line number earns its keep on a long payload.
        val parse = parseRawPayload("{\n  \"name\": \"widget\",\n  score: 3.5\n}")
        assertNull(parse.values)
        val error = assertNotNull(parse.error)
        assertTrue(error.startsWith("Invalid JSON"), "should name the problem: $error")
        assertTrue(error.contains("line 3"), "should point at the offending line: $error")
    }

    // The parser treats commas as whitespace and does not police their placement, so neither a trailing comma
    // nor a missing one derails a splice. Asserted so that leniency is a known property rather than a surprise
    // to whoever next wonders why an obviously wrong payload sailed through.
    @Test
    fun commaPlacementIsNotPoliced() {
        val trailing = parseRawPayload("{\n  \"name\": \"widget\",\n}")
        assertNull(trailing.error)
        assertEquals("widget", assertNotNull(trailing.values)["name"])

        val missing = parseRawPayload("{\n  \"name\": \"widget\"\n  \"score\": 3.5\n}")
        assertNull(missing.error)
        assertEquals(2, assertNotNull(missing.values).size)
    }

    // The offset is what lets the editor put the caret on the mistake rather than only describing it, so it
    // has to point at the offending character itself.
    @Test
    fun carriesTheOffsetOfTheOffendingCharacter() {
        val text = "{\n  \"name\": \"ok\",\n  score: 3.5\n}"
        val parse = parseRawPayload(text)
        val offset = assertNotNull(parse.offset)
        assertEquals('s', text[offset], "offset should land on the unquoted key")
    }

    @Test
    fun reportsAnUnfinishedPaste() {
        val parse = parseRawPayload("""{"name":"widget",""")
        assertNull(parse.values)
        assertTrue(assertNotNull(parse.error).startsWith("Invalid JSON"))
    }

    // A form's values are a set of named fields, so a bare array (or scalar) has nowhere to land. Worth its own
    // message: "invalid JSON" would be a lie, since it parses perfectly well.
    @Test
    fun aNonObjectPayloadSaysSoRatherThanClaimingBadSyntax() {
        val parse = parseRawPayload("""["a","b"]""")
        assertNull(parse.values)
        val error = assertNotNull(parse.error)
        assertTrue(error.contains("JSON object"), "should explain an object is required: $error")
    }
}

/**
 * Assembling a standalone schema document for the raw view (issue #262).
 *
 * What is worth pinning down is not the pretty-printing but the `$defs` bag: shown without it every `$ref`
 * dangles, and shown with the *whole* catalog's defs it drowns one endpoint's contract in every other
 * endpoint's types. Both failures look like a working view until someone tries to read one.
 */
class RawDocumentTest {

    private fun ref(name: String) = mapOf(SCH.dRef to "#/${SCH.dDefs}/$name")

    private val defs: Map<String, Any?> = mapOf(
        "api.Address" to mapOf(
            SCH.type to SCT.kObject,
            SCH.properties to mapOf("location" to ref("api.GeoPoint")),
        ),
        "api.GeoPoint" to mapOf(SCH.type to SCT.kObject),
        // Reachable only through a discriminator's defaultMapping, which is a BARE REF STRING rather than a
        // {"$ref": ...} object -- the case a keyword search walks straight past.
        "api.Unknown" to mapOf(SCH.type to SCT.kObject),
        "api.Thing" to mapOf(
            SCH.type to SCT.kObject,
            SCH.oneOf to listOf(ref("api.Address")),
            SCH.discriminator to mapOf(
                SCH.propertyName to "kind",
                SCH.defaultMapping to "#/${SCH.dDefs}/api.Unknown",
            ),
        ),
        // Nothing points at this one.
        "api.Unrelated" to mapOf(SCH.type to SCT.kObject),
    )

    private val catalog = Catalog(emptyList(), defs)

    private fun defsOf(document: Map<String, Any?>): Map<*, *> =
        assertNotNull(document[SCH.dDefs] as? Map<*, *>, $$"document should carry a $defs bag")

    @Test
    fun carriesTheTypesAReferenceReaches() {
        val document = catalog.rawDocument(
            mapOf(SCH.type to SCT.kObject, SCH.properties to mapOf("address" to ref("api.Address"))),
        )
        val bag = defsOf(document)
        // GeoPoint is reached only *through* Address, so a one-level copy would miss it and leave a dangling ref.
        assertTrue("api.Address" in bag.keys, "the referenced type: ${bag.keys}")
        assertTrue("api.GeoPoint" in bag.keys, "and what that type itself references: ${bag.keys}")
    }

    // The whole point of closing over reachability rather than shipping `defs` wholesale: one endpoint's
    // contract should not arrive buried in every other endpoint's types.
    @Test
    fun leavesOutTypesNothingReaches() {
        val document = catalog.rawDocument(
            mapOf(SCH.type to SCT.kObject, SCH.properties to mapOf("address" to ref("api.Address"))),
        )
        assertFalse("api.Unrelated" in defsOf(document).keys)
        assertFalse("api.Thing" in defsOf(document).keys)
    }

    // The payoff of sharing the kernel's walk instead of writing a second one here.
    @Test
    fun reachesAUnionsDefaultBranchThroughItsBareRefString() {
        val document = catalog.rawDocument(mapOf(SCH.type to SCT.kObject, SCH.properties to mapOf("t" to ref("api.Thing"))))
        assertTrue("api.Unknown" in defsOf(document).keys, "default branch should be carried: ${defsOf(document).keys}")
    }

    // A schema that references nothing is already standalone; an empty bag would be noise in a view whose
    // whole job is to be read.
    @Test
    fun addsNoDefsBagWhenThereIsNothingToResolve() {
        val schema = mapOf(SCH.type to SCT.kObject, SCH.properties to mapOf("name" to mapOf(SCH.type to SCT.string)))
        val document = catalog.rawDocument(schema)
        assertEquals(schema, document)
        assertFalse(SCH.dDefs in document.keys)
    }
}

/**
 * The duck-typed file check behind the request-JSON panel (issue #260).
 *
 * It is asked about every value in a form's payload, so what it has to get right is everything that is *not* a
 * file — and null above all, since `typeof null` is "object" in JavaScript and a check written as a plain type
 * test therefore clears its first term and throws on the second.
 */
class BrowserFileTest {

    /** A stand-in for what the file picker emits: named, sized, sliceable. */
    private fun fakeFile(): dynamic = js("({ name: 'report.csv', size: 1024, slice: function () { return null; } })")

    // The regression. A null reaches here from any key the form does not already hold, which on a fresh form
    // is every key in a pasted payload -- so this one value decides whether "Apply to form" works at all.
    @Test
    fun nullIsNotAFile() {
        assertFalse(isBrowserFile(null))
    }

    @Test
    fun aPickedFileIsAFile() {
        assertTrue(isBrowserFile(fakeFile()))
    }

    // The ordinary inhabitants of a form's values. Note the map and the list: they are the ones that share
    // null's "object" type answer, so they are what the remaining terms of the check exist to separate out.
    @Test
    fun formValuesAreNotFiles() {
        for (v in listOf("widget", 3, 3.5, true, emptyMap<String, Any?>(), mapOf("name" to "widget"), listOf(1, 2))) {
            assertFalse(isBrowserFile(v), "should not be a file: $v")
        }
    }

    // Half a file is not a file: a payload can hold an object carrying a `name` of its own, and that must not
    // be mistaken for something to attach to a multipart request.
    @Test
    fun anObjectThatMerelyHasANameIsNotAFile() {
        assertFalse(isBrowserFile(js("({ name: 'report.csv' })")))
        assertFalse(isBrowserFile(js("({ name: 'report.csv', size: 1024 })")))
    }

    // The panel's text for a payload carrying a JSON null -- what a shared link's `v=` can hold. This is the
    // second place the null crash surfaced, and it fired during the URL restore, so the endpoint page loaded
    // with its carried input silently missing.
    @Test
    fun payloadTextRendersANullValueRatherThanThrowing() {
        val text = payloadText(mapOf("limit" to null, "name" to "widget"))
        val reparsed = assertNotNull(parseRawPayload(text).values, "the panel's own text should parse back")
        assertTrue(reparsed.containsKey("limit"), "the null-valued key should survive: $text")
        assertEquals("widget", reparsed["name"])
    }

    @Test
    fun payloadTextShowsAPickedFileAsALabel() {
        val text = payloadText(mapOf("upload" to fakeFile()))
        assertTrue(text.contains("report.csv"), "should name the chosen file: $text")
        assertTrue(text.contains("1024"), "should give its size: $text")
    }
}
