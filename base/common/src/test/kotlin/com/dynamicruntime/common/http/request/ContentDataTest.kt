package com.dynamicruntime.common.http.request

import com.dynamicruntime.common.exception.KdrException
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe

/**
 * Coverage for [ContentData]: that it carries its content honestly, and the `Content-Disposition` value it
 * builds — the latter being where a filename stops being a string and becomes part of the response's headers.
 */
class ContentDataTest : StringSpec({

    // --- carrying the content -----------------------------------------------------------------------------

    "text content is text, and says so" {
        val content = ContentData("a,b,c", "text/csv")
        content.isBinary shouldBe false
        content.text shouldBe "a,b,c"
        content.bytes shouldBe null
    }

    "binary content is bytes, and says so" {
        val content = ContentData(byteArrayOf(1, 2, 3), "image/png")
        content.isBinary shouldBe true
        content.bytes?.toList() shouldBe listOf<Byte>(1, 2, 3)
        content.text shouldBe null
    }

    // isBinary is a statement, not a guess at which field is populated: an SVG is an image that is text, so
    // the MIME type cannot settle it and neither can the content's shape.
    "an image that is text is not binary" {
        val svg = ContentData("<svg/>", "image/svg+xml")
        svg.isBinary shouldBe false
        svg.text shouldBe "<svg/>"
    }

    "contentBytes yields the content either way, encoding text as UTF-8" {
        ContentData(byteArrayOf(1, 2, 3), "image/png").contentBytes().toList() shouldBe listOf<Byte>(1, 2, 3)
        ContentData("naïve", "text/plain").contentBytes().toList() shouldBe
            "naïve".toByteArray(Charsets.UTF_8).toList()
    }

    "empty content is content -- a zero-length upload is not an absent one" {
        val empty = ContentData(ByteArray(0), "application/octet-stream")
        empty.isBinary shouldBe true
        empty.bytes?.size shouldBe 0
        empty.contentBytes().size shouldBe 0
    }

    // The flag and the content must agree, or a caller reads the wrong field and finds nothing. The two
    // convenience constructors cannot get this wrong; the full one can, so it is guarded at construction
    // rather than left to surface as an empty response somewhere downstream.
    "content that contradicts its own isBinary flag is refused at construction" {
        shouldThrow<KdrException> { ContentData("image/png", isBinary = true, bytes = null) }
        shouldThrow<KdrException> { ContentData("text/csv", isBinary = false, text = null) }
    }

    // --- Content-Disposition ------------------------------------------------------------------------------

    "plain inline content sends no Content-Disposition -- inline is the default anyway" {
        ContentData(byteArrayOf(1), "image/png").contentDispositionHeader() shouldBe null
    }

    "an attachment with no filename is still an attachment" {
        ContentData("a,b", "text/csv", inLine = false).contentDispositionHeader() shouldBe "attachment"
    }

    "an attachment offers its filename" {
        ContentData("a,b", "text/csv", saveAsFilename = "report.csv", inLine = false)
            .contentDispositionHeader() shouldBe "attachment; filename=\"report.csv\""
    }

    // A filename is advisory for inline content -- it names the file if the reader chooses to save it -- so
    // here the header does carry information and is worth sending.
    "inline content with a filename says so" {
        ContentData(byteArrayOf(1), "application/pdf", saveAsFilename = "invoice.pdf")
            .contentDispositionHeader() shouldBe "inline; filename=\"invoice.pdf\""
    }

    // --- the filename is the untrusted part ---------------------------------------------------------------

    // The attack this exists to stop: a CR/LF in the name would end the header and let the rest of the value
    // inject headers of its own (a session cookie, a redirect) into the response.
    "a filename cannot inject headers with CR/LF" {
        val header = ContentData(
            "a,b",
            "text/csv",
            saveAsFilename = "ok.csv\r\nSet-Cookie: session=stolen",
            inLine = false,
        ).contentDispositionHeader()
        header shouldBe "attachment; filename=\"ok.csvSet-Cookie: session=stolen\""
        header!!.contains('\r') shouldBe false
        header.contains('\n') shouldBe false
    }

    // A quote would otherwise close the quoted string early and let what follows be read as parameters.
    "a filename cannot break out of the quoted string" {
        ContentData("a,b", "text/csv", saveAsFilename = "eviltest\".csv", inLine = false)
            .contentDispositionHeader() shouldBe "attachment; filename=\"eviltest_.csv\""
    }

    "a filename offers only its last segment, never a path" {
        ContentData("a,b", "text/csv", saveAsFilename = "../../etc/passwd", inLine = false)
            .contentDispositionHeader() shouldBe "attachment; filename=\"passwd\""
        ContentData("a,b", "text/csv", saveAsFilename = "C:\\Windows\\system.ini", inLine = false)
            .contentDispositionHeader() shouldBe "attachment; filename=\"system.ini\""
    }

    // A non-ASCII name cannot travel in the plain `filename` at all, so RFC 6266's extended form carries it
    // and the plain one keeps a degraded ASCII version for whatever ignores that.
    "a non-ASCII filename travels in the RFC 6266 extended form as well" {
        ContentData(byteArrayOf(1), "application/pdf", saveAsFilename = "naïve.pdf", inLine = false)
            .contentDispositionHeader() shouldBe
            "attachment; filename=\"na_ve.pdf\"; filename*=UTF-8''na%C3%AFve.pdf"
    }

    "a filename of only unsafe characters offers no name rather than an empty one" {
        ContentData("a,b", "text/csv", saveAsFilename = "", inLine = false)
            .contentDispositionHeader() shouldBe "attachment"
        ContentData("a,b", "text/csv", saveAsFilename = "").contentDispositionHeader() shouldBe null
    }

    "a filename is length-bounded" {
        val header = ContentData("a,b", "text/csv", saveAsFilename = "a".repeat(500), inLine = false)
            .contentDispositionHeader()
        header shouldBe "attachment; filename=\"${"a".repeat(ContentData.maxFilenameLength)}\""
    }

    "the hash is carried, and is not yet emitted anywhere" {
        ContentData(byteArrayOf(1), "image/png", hash = "9f3ac1").hash shouldBe "9f3ac1"
        // Nothing reads it yet -- in particular it must not leak into the disposition.
        ContentData(byteArrayOf(1), "image/png", hash = "9f3ac1").contentDispositionHeader() shouldBe null
    }

    // --- what the handler actually does with it -----------------------------------------------------------
    // The cases above pin what ContentData holds and builds; these pin that it reaches the response, which is
    // the part a caller depends on. A test-mode handler captures into its rpt* fields rather than a socket.

    "sending binary content sets the type, the disposition, and the bytes" {
        val handler = RequestHandler("contentDataTest", "GET", "/cp/chart.png", emptyMap(), mutableMapOf())
        val body = byteArrayOf(1, 2, 3)
        handler.sendContentResponse(
            ContentData(body, "image/png", saveAsFilename = "chart.png", inLine = false),
            200,
        )

        handler.rptStatusCode shouldBe 200
        handler.rptResponseMimeType shouldBe "image/png"
        handler.rptResponseHeaders["content-disposition"] shouldBe
            mutableListOf("attachment; filename=\"chart.png\"")
        handler.rptResponseBytes?.toList() shouldBe body.toList()
        // A binary response is bytes only -- it is never also offered as a (lossy) String.
        handler.rptResponseData shouldBe null
        handler.hasResponseBeenSent() shouldBe true
    }

    "sending text content captures text, not bytes" {
        val handler = RequestHandler("contentDataTest", "GET", "/cp/report.csv", emptyMap(), mutableMapOf())
        handler.sendContentResponse(
            ContentData("a,b,c", "text/csv", saveAsFilename = "report.csv", inLine = false),
            200,
        )

        handler.rptResponseMimeType shouldBe "text/csv"
        handler.rptResponseData shouldBe "a,b,c"
        handler.rptResponseBytes shouldBe null
        handler.rptResponseHeaders["content-disposition"] shouldBe
            mutableListOf("attachment; filename=\"report.csv\"")
    }

    "sending with a bare mime type sets no disposition -- the existing callers are unchanged" {
        val handler = RequestHandler("contentDataTest", "GET", "/cp/page", emptyMap(), mutableMapOf())
        handler.sendStringResponse("hello", 200, "text/plain")

        handler.rptResponseMimeType shouldBe "text/plain"
        handler.rptResponseHeaders.containsKey("content-disposition") shouldBe false
        handler.rptResponseData shouldBe "hello"
    }
})
