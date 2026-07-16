package com.dynamicruntime.common.http.request

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe

/**
 * Coverage for [ContentData] — chiefly the `Content-Disposition` value it builds, since that is where a
 * filename stops being a string and becomes part of the response's headers.
 */
class ContentDataTest : StringSpec({

    "plain inline content sends no Content-Disposition -- inline is the default anyway" {
        ContentData("image/png").contentDispositionHeader() shouldBe null
    }

    "an attachment with no filename is still an attachment" {
        ContentData("text/csv", inLine = false).contentDispositionHeader() shouldBe "attachment"
    }

    "an attachment offers its filename" {
        ContentData("text/csv", saveAsFilename = "report.csv", inLine = false)
            .contentDispositionHeader() shouldBe "attachment; filename=\"report.csv\""
    }

    // A filename is advisory for inline content -- it names the file if the reader chooses to save it -- so
    // here the header does carry information and is worth sending.
    "inline content with a filename says so" {
        ContentData("application/pdf", saveAsFilename = "invoice.pdf")
            .contentDispositionHeader() shouldBe "inline; filename=\"invoice.pdf\""
    }

    // --- the filename is the untrusted part ---------------------------------------------------------------

    // The attack this exists to stop: a CR/LF in the name would end the header and let the rest of the value
    // inject headers of its own (a session cookie, a redirect) into the response.
    "a filename cannot inject headers with CR/LF" {
        val header = ContentData(
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
        ContentData("text/csv", saveAsFilename = "eviltest\".csv", inLine = false)
            .contentDispositionHeader() shouldBe "attachment; filename=\"eviltest_.csv\""
    }

    "a filename offers only its last segment, never a path" {
        ContentData("text/csv", saveAsFilename = "../../etc/passwd", inLine = false)
            .contentDispositionHeader() shouldBe "attachment; filename=\"passwd\""
        ContentData("text/csv", saveAsFilename = "C:\\Windows\\system.ini", inLine = false)
            .contentDispositionHeader() shouldBe "attachment; filename=\"system.ini\""
    }

    // A non-ASCII name cannot travel in the plain `filename` at all, so RFC 6266's extended form carries it
    // and the plain one keeps a degraded ASCII version for whatever ignores that.
    "a non-ASCII filename travels in the RFC 6266 extended form as well" {
        ContentData("application/pdf", saveAsFilename = "naïve.pdf", inLine = false)
            .contentDispositionHeader() shouldBe
            "attachment; filename=\"na_ve.pdf\"; filename*=UTF-8''na%C3%AFve.pdf"
    }

    "a filename of only unsafe characters offers no name rather than an empty one" {
        ContentData("text/csv", saveAsFilename = "", inLine = false)
            .contentDispositionHeader() shouldBe "attachment"
        ContentData("text/csv", saveAsFilename = "").contentDispositionHeader() shouldBe null
    }

    "a filename is length-bounded" {
        val header = ContentData("text/csv", saveAsFilename = "a".repeat(500), inLine = false)
            .contentDispositionHeader()
        header shouldBe "attachment; filename=\"${"a".repeat(ContentData.maxFilenameLength)}\""
    }

    "the hash is carried, and is not yet emitted anywhere" {
        ContentData("image/png", hash = "9f3ac1").hash shouldBe "9f3ac1"
        // Nothing reads it yet -- in particular it must not leak into the disposition.
        ContentData("image/png", hash = "9f3ac1").contentDispositionHeader() shouldBe null
    }

    // --- what the handler actually does with it -----------------------------------------------------------
    // The cases above pin the string ContentData builds; these pin that it reaches the response, which is the
    // part a caller depends on. A test-mode handler captures into its rpt* fields rather than a socket.

    "sending bytes as an attachment sets the type, the disposition, and the body" {
        val handler = RequestHandler("contentDataTest", "GET", "/cp/report.csv", emptyMap(), mutableMapOf())
        val body = byteArrayOf(1, 2, 3)
        handler.sendBytesResponse(body, 200, ContentData("text/csv", saveAsFilename = "report.csv", inLine = false))

        handler.rptStatusCode shouldBe 200
        handler.rptResponseMimeType shouldBe "text/csv"
        handler.rptResponseHeaders["content-disposition"] shouldBe
            mutableListOf("attachment; filename=\"report.csv\"")
        handler.rptResponseBytes?.toList() shouldBe body.toList()
        handler.hasResponseBeenSent() shouldBe true
    }

    "sending text with a bare mime type sets no disposition -- the existing callers are unchanged" {
        val handler = RequestHandler("contentDataTest", "GET", "/cp/page", emptyMap(), mutableMapOf())
        handler.sendStringResponse("hello", 200, "text/plain")

        handler.rptResponseMimeType shouldBe "text/plain"
        handler.rptResponseHeaders.containsKey("content-disposition") shouldBe false
        handler.rptResponseData shouldBe "hello"
    }
})
