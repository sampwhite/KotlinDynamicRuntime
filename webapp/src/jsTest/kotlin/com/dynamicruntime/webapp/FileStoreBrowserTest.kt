package com.dynamicruntime.webapp

import com.dynamicruntime.common.endpoint.EP
import com.dynamicruntime.common.filestore.FMETA
import com.dynamicruntime.common.operator.FSB
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

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
        assertEquals("#page=fileStore", fileStoreHref())
        assertEquals("#page=fileStore&bucket=web-assets", fileStoreHref("web-assets"))
        assertEquals("#page=fileStore&bucket=web-assets&folder=clients%2Facme%2F", fileStoreHref("web-assets", "clients/acme/"))
        // The folder as the hash names it, read as the endpoint names one: a typed address without the slash still matches.
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
