package com.dynamicruntime.webapp

import com.dynamicruntime.common.filestore.FMETA
import com.dynamicruntime.common.webasset.WAS
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Pure-logic coverage (issue #161) for a client's own web assets (issue #1130): what the shell reads out of its
 * config and draws, and how the Clients page reads and words the listing.
 */
class WebAssetsTest {
    private fun slot(file: WebAssetFile? = null, by: String? = null, slot: String = WAS.favicon, label: String = "Favicon") =
        WebAssetSlot(slot, label, 256 * 1024, listOf("image/png", "image/svg+xml"), listOf("PNG", "SVG", "ICO"), file, by)

    private val pngFile = WebAssetFile("image/png", 12_345, "abc123", "2026-10-12T19:49:51.568Z", "our icon.png")

    @Test
    fun theShellReadsOnlyTheSlotsItKnowsEachWithAName() {
        val raw = mapOf(WAS.favicon to "favicon:aa", WAS.brandMark to "brandMark:bb", "banner" to "banner:cc")
        assertEquals(mapOf(WAS.favicon to "favicon:aa", WAS.brandMark to "brandMark:bb"), webAssetNamesFrom(raw))
        // A value that is no name is no asset; neither is a config with none, or one from a backend that sends none.
        assertEquals(emptyMap(), webAssetNamesFrom(mapOf(WAS.favicon to "", WAS.brandMark to 7)))
        assertEquals(emptyMap(), webAssetNamesFrom(null))
        assertEquals(emptyMap(), webAssetNamesFrom("favicon:aa"))
    }

    @Test
    fun theMarkIsTheClientsWhereItHasOneAndTheDeploymentsOtherwise() {
        val url = { name: String -> "/st/kdr.en/web/$name" }
        assertEquals("/st/kdr.en/web/brandMark:bb", shellMarkUrl(mapOf(WAS.brandMark to "brandMark:bb"), "/wa/brand-mark.svg:1a", url))
        // A client that set only its favicon keeps the deployment's mark; so does the bar before any config.
        assertEquals("/wa/brand-mark.svg:1a", shellMarkUrl(mapOf(WAS.favicon to "favicon:aa"), "/wa/brand-mark.svg:1a", url))
        assertEquals("/wa/brand-mark.svg:1a", shellMarkUrl(null, "/wa/brand-mark.svg:1a", url))
    }

    @Test
    fun theStaticAddressIsTheKernelsSoTheBackendServesWhatTheFrontendAsksFor() {
        assertEquals("/kdr.en/web/favicon:aa", WAS.staticPath("kdr.en", "favicon:aa"))
        assertEquals("favicon:aa", WAS.versionedName(WAS.favicon, "aa"))
    }

    @Test
    fun theListingIsReadSlotBySlotWithTheFileWhereThereIsOne() {
        val items = listOf(
            mapOf(
                WAS.slot to WAS.favicon, WAS.label to "Favicon", WAS.maxSize to 262144,
                WAS.mimeTypes to listOf("image/png"), WAS.kinds to listOf("PNG"), WAS.name to "favicon:abc123",
                WAS.uploadedByName to "Chief One",
                WAS.file to mapOf(
                    FMETA.mimeType to "image/png", FMETA.size to 12345, FMETA.hash to "abc123",
                    FMETA.uploadedAt to "2026-10-12T19:49:51.568Z", FMETA.fileName to "our icon.png",
                ),
            ),
            mapOf(WAS.slot to WAS.brandMark, WAS.label to "Brand mark", WAS.maxSize to 1048576),
            mapOf(WAS.label to "No slot at all"),
        )
        val slots = parseWebAssetSlots(items)
        assertEquals(listOf(WAS.favicon, WAS.brandMark), slots.map { it.slot })
        val favicon = slots[0]
        assertEquals(262144L, favicon.maxSize)
        assertEquals("abc123", favicon.file?.hash)
        assertEquals(12345L, favicon.file?.size)
        assertEquals("our icon.png", favicon.file?.fileName)
        assertEquals("Chief One", favicon.uploadedByName)
        // An empty slot has no file, and what it takes when the listing did not say is nothing rather than a guess.
        assertNull(slots[1].file)
        assertEquals(emptyList(), slots[1].kinds)
    }

    @Test
    fun aSizeReadsRoundedUpInTheUnitAPersonWouldUse() {
        assertEquals("0 bytes", byteSizeText(0))
        assertEquals("640 bytes", byteSizeText(640))
        assertEquals("1 KB", byteSizeText(1024))
        assertEquals("2 KB", byteSizeText(1025))
        assertEquals("256 KB", byteSizeText(256 * 1024))
        assertEquals("1 MB", byteSizeText(1024 * 1024))
        assertEquals("1.1 MB", byteSizeText(1024 * 1024 + 1))
        assertEquals("1.5 MB", byteSizeText(1536 * 1024))
        assertEquals("2 MB", byteSizeText(2 * 1024 * 1024))
    }

    @Test
    fun aSlotSaysWhatItTakesAndWhatItHolds() {
        assertEquals("PNG, SVG or ICO, up to 256 KB.", webAssetTakesText(slot()))
        assertEquals("Not set: the deployment's favicon is shown.", webAssetHoldsText(slot()))
        assertNull(webAssetUploadedText(slot()))

        val held = slot(pngFile, by = "Chief One")
        assertEquals("image/png · 13 KB · our icon.png", webAssetHoldsText(held))
        assertEquals("Uploaded 2026-10-12 19:49 UTC by Chief One", webAssetUploadedText(held))
        // Who is said only when it was recorded, and a file that came with no name is described without one.
        assertEquals("Uploaded 2026-10-12 19:49 UTC", webAssetUploadedText(slot(pngFile)))
        assertEquals("image/png · 13 KB", webAssetHoldsText(slot(WebAssetFile("image/png", 12_345, "h", "", null))))
        assertEquals("Uploaded", webAssetUploadedText(slot(WebAssetFile("image/png", 1, "h", "", null))))
    }

    @Test
    fun aFileTooLargeIsSaidBeforeItIsSentAndNothingElseIsJudgedHere() {
        assertNull(webAssetFileProblem(slot(), 256 * 1024))
        assertEquals("This file is 257 KB; a favicon may be at most 256 KB.", webAssetFileProblem(slot(), 256 * 1024 + 1))
        // A listing that named no limit leaves the judging to the backend.
        assertNull(webAssetFileProblem(WebAssetSlot(WAS.favicon, "Favicon", 0, emptyList(), emptyList(), null, null), 9_999_999))
    }

    @Test
    fun thePreviewIsTheNamedDownloadWithTheImagesHashSoAReplacedImageIsANewAddress() {
        assertEquals(
            mapOf(WAS.client to "acme", WAS.slot to WAS.favicon, WAS.hash to "abc123"),
            webAssetPreviewArgs("acme", slot(pngFile)),
        )
        assertNull(webAssetPreviewArgs("acme", slot()))
    }
}
