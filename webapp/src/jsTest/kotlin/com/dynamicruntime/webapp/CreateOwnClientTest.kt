package com.dynamicruntime.webapp

import com.dynamicruntime.common.gedra.CLD
import com.dynamicruntime.common.webasset.WAS
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Pure-logic coverage for creating one's own client from the home page (issue #1228). */
class CreateOwnClientTest {

    @Test
    fun theRequestSendsTheNameAndOnlyWhatWasGiven() {
        assertEquals(
            mapOf(CLD.name to "Maker's Shop", CLD.description to "Mine.", CLD.timeZone to "America/Chicago"),
            createOwnClientRequest("  Maker's Shop ", " Mine. ", "America/Chicago"),
        )
        // No note and no zone: neither is sent, so the deployment's zone applies.
        assertEquals(mapOf(CLD.name to "Shop"), createOwnClientRequest("Shop", " ", ""))
    }

    @Test
    fun theRequestCarriesTheHomeCopyGivenAndLeavesOutABlankOne() {
        val request = createOwnClientRequest(
            "Shop", "", "", mapOf(CLD.homeBrand to " Shop Co ", CLD.homeIntro to "**Hi**", CLD.homeNote to "  ", "other" to "x"),
        )
        assertEquals(mapOf(CLD.name to "Shop", CLD.homeBrand to "Shop Co", CLD.homeIntro to "**Hi**"), request)
    }

    @Test
    fun anImageIsCheckedAgainstItsSlotBeforeTheClientIsMade() {
        assertNull(imageProblem(WAS.favicon, "image/png", 10_000))
        assertNull(imageProblem(WAS.brandMark, "image/svg+xml", 900_000))
        // Too large for the favicon, though a brand mark that size would do.
        assertEquals("The favicon is 300 KB; it may be at most 256 KB.", imageProblem(WAS.favicon, "image/png", 300 * 1024))
        assertNull(imageProblem(WAS.brandMark, "image/png", 300 * 1024))
        assertEquals("The brand mark must be PNG, SVG, ICO, JPEG or WebP.", imageProblem(WAS.brandMark, "image/gif", 10))
        assertEquals("The favicon file is empty.", imageProblem(WAS.favicon, "image/png", 0))
        assertEquals("PNG, SVG, ICO, JPEG or WebP, up to 256 KB.", imageLimitText(WAS.favicon))
    }

    @Test
    fun anImageThatDidNotUploadIsSaidAndTheCardStays() {
        assertNull(imageUploadsNote(emptyList()))
        assertEquals(
            "Your client was created, but the favicon couldn't be uploaded: Not a PNG. Add it from My client.",
            imageUploadsNote(listOf(ImageUploadFailure("Favicon", "Not a PNG."))),
        )
        val both = imageUploadsNote(listOf(ImageUploadFailure("Favicon", "A."), ImageUploadFailure("Brand mark", "B.")))!!
        assertTrue(both.contains("the favicon") && both.contains("the brand mark") && both.endsWith("Add them from My client."), both)
    }

    @Test
    fun aCreateThatDidNotMoveSaysTheImagesChosenWereNotUploaded() {
        assertNull(imagesNotUploadedNote(emptyList()))
        assertEquals("The favicon you chose wasn't uploaded.", imagesNotUploadedNote(listOf("Favicon")))
        assertEquals("The favicon and brand mark you chose weren't uploaded.", imagesNotUploadedNote(listOf("Favicon", "Brand mark")))
    }

    @Test
    fun theCardOffersTheCreateUntilTheClientIsMadeThenOpensItOrSaysItIsGone() {
        assertEquals(OwnClientCardMode.create, ownClientCardMode(null, madeAbsent = false))
        assertEquals(OwnClientCardMode.open, ownClientCardMode("free_x", madeAbsent = false))
        assertEquals(OwnClientCardMode.unavailable, ownClientCardMode("free_x", madeAbsent = true))
    }

    @Test
    fun theCardStartsOnTheBrowsersZoneOnlyWhenAClientMayNameIt() {
        assertEquals("America/Chicago", ownClientStartZone("America/Chicago"))
        assertEquals("UTC", ownClientStartZone("UTC"))
        // An offset a browser may report is refused by the create: start on none instead.
        assertEquals("", ownClientStartZone("Etc/GMT+5"))
        assertEquals("", ownClientStartZone(null))
    }

    @Test
    fun aCreateThatMovedMovesThePageAndOneThatDidNotRereadsWithTheReason() {
        val moved = createOwnClientNext(parseCreateOwnClientResult(mapOf(CLD.client to "free_a", CLD.switched to true, CLD.present to true)))
        assertTrue(moved.moves)
        assertNull(moved.note)
        val stuck = createOwnClientNext(
            parseCreateOwnClientResult(mapOf(CLD.client to "free_b", CLD.switched to false, CLD.present to true, CLD.userFailure to "Full.")),
        )
        assertTrue(stuck.rereads, "made, so the config is re-read and the create not offered again")
        assertTrue(stuck.note!!.contains("free_b"))
    }

    @Test
    fun aCreateThatMovedTheSessionSaysNothingMore() {
        val moved = parseCreateOwnClientResult(mapOf(CLD.client to "free_shopab12cd", CLD.switched to true, CLD.present to true))
        assertEquals("free_shopab12cd", moved.client)
        assertTrue(moved.switched)
        assertNull(createOwnClientFailureText(moved))
    }

    @Test
    fun aCreateThatDidNotMoveSaysWhyAndThatTheClientIsMade() {
        val noUser = parseCreateOwnClientResult(
            mapOf(CLD.client to "free_x", CLD.switched to false, CLD.present to true, CLD.userFailure to "The plan is full."),
        )
        assertEquals(
            "Your client 'free_x' was created, but your administrator user there could not be: The plan is full.",
            createOwnClientFailureText(noUser),
        )
        val absent = parseCreateOwnClientResult(mapOf(CLD.client to "free_y", CLD.switched to false, CLD.present to false))
        assertEquals("Your client 'free_y' was created, but this site does not carry it.", createOwnClientFailureText(absent))
        val loadFailed = parseCreateOwnClientResult(
            mapOf(CLD.client to "free_z", CLD.switched to false, CLD.present to false, CLD.loadFailure to "A trait is missing."),
        )
        assertEquals("Your client 'free_z' was created, but this site does not carry it: A trait is missing.", createOwnClientFailureText(loadFailed))
    }
}
