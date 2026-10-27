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
    fun aSlotSaysWhatItTakesAndWhatItHolds() {
        assertEquals("PNG, SVG or ICO, up to 256 KB.", webAssetTakesText(slot()))
        assertEquals("Not set: the deployment's favicon is shown.", webAssetHoldsText(slot()))
        assertNull(webAssetUploadedText(slot()))

        val held = slot(pngFile, by = "Chief One")
        assertEquals("image/png · 13 KB · our icon.png", webAssetHoldsText(held))
        assertEquals("Uploaded Oct 12, 2026, 11:49 AM GMT-8 by Chief One", webAssetUploadedText(held))
        // Who is said only when it was recorded, and a file that came with no name is described without one.
        assertEquals("Uploaded Oct 12, 2026, 11:49 AM GMT-8", webAssetUploadedText(slot(pngFile)))
        assertEquals("image/png · 13 KB", webAssetHoldsText(slot(WebAssetFile("image/png", 12_345, "h", "", null))))
        assertEquals("Uploaded", webAssetUploadedText(slot(WebAssetFile("image/png", 1, "h", "", null))))
        // A file whose type the listing did not say is described by what it did say, with no separator left over.
        assertEquals("13 KB · a.png", webAssetHoldsText(slot(WebAssetFile("", 12_345, "h", "", "a.png"))))
    }

    @Test
    fun whatASlotTakesIsWordedFromWhateverTheListingSaid() {
        fun takes(kinds: List<String>, maxSize: Long) = webAssetTakesText(WebAssetSlot(WAS.favicon, "Favicon", maxSize, emptyList(), kinds, null, null))
        assertEquals("PNG, up to 1 MB.", takes(listOf("PNG"), 1024 * 1024))
        assertEquals("PNG or SVG, up to 256 KB.", takes(listOf("PNG", "SVG"), 256 * 1024))
        // A listing that named no kinds, or no limit, is not filled in with a guess at either.
        assertEquals("An image, up to 256 KB.", takes(emptyList(), 256 * 1024))
        assertEquals("PNG or SVG.", takes(listOf("PNG", "SVG"), 0))
        assertEquals("An image.", takes(emptyList(), 0))
    }

    @Test
    fun aListingsOddShapesAreReadAsWhatTheyAre() {
        // A `file` that is there and empty is no file; a number that arrived as a double is the number it is.
        val slots = parseWebAssetSlots(
            listOf(
                mapOf(WAS.slot to WAS.favicon, WAS.file to emptyMap<String, Any?>(), WAS.maxSize to 262144.0),
                mapOf(WAS.slot to WAS.brandMark, WAS.file to mapOf(FMETA.hash to "h", FMETA.size to 2048.0)),
            ),
        )
        assertNull(slots[0].file)
        assertEquals(262144L, slots[0].maxSize)
        // The label falls back to the slot's own name, and a file that says little is still a file.
        assertEquals(WAS.favicon, slots[0].label)
        assertEquals(2048L, slots[1].file?.size)
        assertEquals("", slots[1].file?.mimeType)
        assertNull(slots[1].file?.fileName)
    }

    @Test
    fun aRemovalIsAskedAboutNamingTheClientItActsOnAndIsDrawnFromItsAnswer() {
        assertEquals("Remove the favicon of acme (live data)?", webAssetRemoveQuestion(slot(pngFile), "acme"))
        // With no package behind it, the deployment's comes back.
        assertEquals("Its people see the deployment's favicon again, at once.", webAssetRemoveConsequence(slot(pngFile)))
        assertEquals(true, webAssetRemovable(slot(pngFile)))
        assertEquals(
            "Remove the brand mark of globex (live data)?",
            webAssetRemoveQuestion(slot(pngFile, slot = WAS.brandMark, label = "Brand mark"), "globex"),
        )

        // What is under way names its slot and its action, so a removal is not shown on the upload's control.
        assertEquals("favicon/remove", webAssetBusyKey(WAS.favicon, WACT.remove))
        assertEquals(false, webAssetBusyKey(WAS.favicon, WACT.remove) == webAssetBusyKey(WAS.favicon, WACT.upload))
        assertEquals(false, webAssetBusyKey(WAS.favicon, WACT.remove) == webAssetBusyKey(WAS.brandMark, WACT.remove))

        // The answer takes its slot's place and leaves the others; with nothing listed yet there is nothing to place it in.
        val listed = listOf(slot(pngFile), slot(pngFile, slot = WAS.brandMark, label = "Brand mark"))
        val after = withWebAssetSlot(listed, slot())!!
        assertNull(after[0].file)
        assertEquals("abc123", after[1].file?.hash)
        assertNull(withWebAssetSlot(null, slot()))
    }

    @Test
    fun aPackagesImageSaysWhoseItIsAndHasNoUploadToRemove() {
        val svg = mapOf(FMETA.mimeType to "image/svg+xml", FMETA.size to 600, FMETA.hash to "f00d", FMETA.uploadedAt to "2026-10-27T16:00:00Z", FMETA.fileName to "favicon.svg")
        fun item(vararg more: Pair<String, Any?>) = mapOf(WAS.slot to WAS.favicon, WAS.label to "Favicon") + more
        val (shipped, uploaded, empty) = parseWebAssetSlots(
            listOf(
                item(WAS.file to svg, WAS.source to WAS.sourcePackage, WAS.packageId to "sample:acme", WAS.packaged to true),
                item(WAS.file to svg, WAS.source to WAS.sourceUpload, WAS.packageId to "sample:acme", WAS.packaged to true),
                // A source with no file says nothing: the slot is empty.
                item(WAS.source to WAS.sourcePackage),
            ),
        )
        // The package's image is the client's source code's: said as such, with no upload to remove.
        assertEquals(true, webAssetFromPackage(shipped))
        assertEquals(false, webAssetRemovable(shipped))
        assertEquals(
            "From the client's web resources package, sample:acme, shipped with its source code. An upload replaces it for the client's people.",
            webAssetUploadedText(shipped),
        )
        // An upload in front of it is removable, and removing it brings the package's back.
        assertEquals(true, webAssetRemovable(uploaded))
        assertEquals("Its people see the favicon of its web resources package again, at once.", webAssetRemoveConsequence(uploaded))
        assertNull(empty.source)
        assertEquals(false, webAssetFromPackage(empty))
        assertEquals(false, webAssetRemovable(empty))
    }

    @Test
    fun aFileTooLargeIsSaidBeforeItIsSentAndNothingElseIsJudgedHere() {
        assertNull(webAssetFileProblem(slot(), 256 * 1024))
        assertEquals("This file is 257 KB; a favicon may be at most 256 KB.", webAssetFileProblem(slot(), 256 * 1024 + 1))
        // Said in megabytes from the first size that rounds to one, as the limit beside it is.
        val brand = WebAssetSlot(WAS.brandMark, "Brand mark", 1024 * 1024, emptyList(), emptyList(), null, null)
        assertEquals("This file is 1.1 MB; a brand mark may be at most 1 MB.", webAssetFileProblem(brand, 1024 * 1024 + 1))
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
