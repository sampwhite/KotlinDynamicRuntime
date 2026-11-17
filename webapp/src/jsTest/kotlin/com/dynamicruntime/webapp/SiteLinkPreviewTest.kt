package com.dynamicruntime.webapp

import com.dynamicruntime.common.webasset.WAS
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** What a link to the site shows (issue #1783): the endpoint's answer read, and the words and address the block draws. */
class SiteLinkPreviewTest {
    @Test
    fun theAnswerIsReadAndAnImageWithNoFileIsLeftOut() {
        val view = siteLinkPreviewFrom(
            mapOf(
                WAS.images to listOf(
                    mapOf(WAS.slot to WAS.shareImage, WAS.label to "Share image", WAS.appFile to WAS.shareImageFile,
                        WAS.source to WAS.sourceDefault, WAS.packageId to "kdr:gedra"),
                    mapOf(WAS.slot to WAS.faviconPng, WAS.label to "PNG favicon", WAS.appFile to WAS.faviconPngFile, WAS.source to WAS.sourceBuiltIn),
                    mapOf(WAS.slot to "fromANewerBackend", WAS.label to "Something"),
                ),
                WAS.title to "Try Gedra",
                WAS.description to "Forms, reviews, and approvals.",
            ),
        )
        assertEquals(listOf(WAS.shareImage, WAS.faviconPng), view.images.map { it.slot })
        assertEquals("Try Gedra", view.title)
        assertNull(view.publicUrl)
        assertEquals("From the deployment's default package, kdr:gedra.", shellImageSourceText(view.images[0]))
        assertEquals("Built in.", shellImageSourceText(view.images[1]))
    }

    @Test
    fun postInspectorIsAskedAboutTheSiteItself() {
        assertEquals(
            "https://www.linkedin.com/post-inspector/inspect/https%3A%2F%2Fdynamicruntime.org",
            postInspectorUrl("https://dynamicruntime.org"),
        )
        assertNull(siteLinkPreviewFrom(mapOf(WAS.publicUrl to " ")).publicUrl)
    }
}
