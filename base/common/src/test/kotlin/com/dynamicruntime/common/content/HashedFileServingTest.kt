package com.dynamicruntime.common.content

import com.dynamicruntime.common.webasset.WebAssetCheck
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.maps.shouldContainExactly
import io.kotest.matchers.shouldBe

/**
 * The headers every file served by its content goes out with (issues #1130, #1153). The address parse and the
 * cache decision are driven end to end by `ClientWebAssetsTest` and `ClientFileRefsTest`, on a booted instance.
 */
class HashedFileServingTest : StringSpec({
    val guarded = mapOf(HashedFileServing.noSniffHeader to HashedFileServing.noSniff)
    val sandboxed = guarded + (HashedFileServing.contentPolicyHeader to HashedFileServing.svgContentPolicy)

    "every file is sent with nosniff, and an SVG with the policy that lets it run nothing" {
        HashedFileServing.guardHeaders("image/png") shouldContainExactly guarded
        HashedFileServing.guardHeaders("image/svg+xml") shouldContainExactly sandboxed
        HashedFileServing.guardHeaders("text/plain") shouldContainExactly guarded
    }

    // The type a file is served under comes from the store's record of it. Whatever admitted a file as an image
    // read that type leniently -- parameters and case aside -- so the policy has to be decided the same way, or a
    // record of `image/svg+xml; charset=utf-8` would be drawn as an SVG and sent without it.
    "an SVG is told as a browser tells one, not by an exact match" {
        for (type in listOf("IMAGE/SVG+XML", "image/svg+xml; charset=utf-8", " image/svg+xml ", "Image/Svg+Xml;charset=UTF-8")) {
            WebAssetCheck.declaredKind(type) shouldBe WebAssetCheck.svg
            HashedFileServing.guardHeaders(type) shouldContainExactly sandboxed
        }
        // One name for the type, which the upload check and the policy both read.
        WebAssetCheck.svg.mimeType shouldBe HashedFileServing.svgMimeType
    }
})
