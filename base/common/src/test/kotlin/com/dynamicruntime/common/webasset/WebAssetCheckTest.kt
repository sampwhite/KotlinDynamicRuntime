package com.dynamicruntime.common.webasset

import com.dynamicruntime.common.exception.EXC
import com.dynamicruntime.common.exception.KdrException
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.xml.sax.SAXNotRecognizedException
import javax.xml.parsers.SAXParser
import javax.xml.parsers.SAXParserFactory

/** A SAX parser factory that knows only the standard secure-processing feature, and would parse without the rest. */
class RefusingSaxFactory : SAXParserFactory() {
    override fun newSAXParser(): SAXParser = throw IllegalStateException("No SVG may be read by a parser that was not made safe.")

    override fun setFeature(name: String, value: Boolean) {
        if (name != javax.xml.XMLConstants.FEATURE_SECURE_PROCESSING) throw SAXNotRecognizedException("Feature '$name' is not recognized.")
    }

    override fun getFeature(name: String): Boolean = false
}

/**
 * Whether an uploaded web asset is the image it says it is (issue #1130): the declared type checked against the
 * content, and an SVG held to being one that carries no script.
 */
class WebAssetCheckTest : StringSpec({
    val png = byteArrayOf(0x89.toByte(), 'P'.code.toByte(), 'N'.code.toByte(), 'G'.code.toByte(), 0x0D, 0x0A, 0x1A, 0x0A, 1, 2, 3)
    val jpeg = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE0.toByte(), 0, 16)
    val ico = byteArrayOf(0, 0, 1, 0, 1, 0, 16, 16)
    val webp = "RIFF".toByteArray() + byteArrayOf(4, 0, 0, 0) + "WEBPVP8 ".toByteArray()
    val svg = """<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 8 8"><circle cx="4" cy="4" r="3"/></svg>""".toByteArray()

    fun refusal(declared: String, bytes: ByteArray): String {
        val e = shouldThrow<KdrException> { WebAssetCheck.check("favicon", declared, bytes) }
        e.code shouldBe EXC.badInput
        return e.message.orEmpty()
    }

    "each kind is known by its content, under any type it is honestly declared as" {
        WebAssetCheck.check("favicon", "image/png", png) shouldBe WebAssetCheck.png
        WebAssetCheck.check("favicon", "image/jpeg", jpeg) shouldBe WebAssetCheck.jpeg
        WebAssetCheck.check("favicon", "image/jpg", jpeg) shouldBe WebAssetCheck.jpeg
        WebAssetCheck.check("favicon", "image/pjpeg", jpeg).mimeType shouldBe "image/jpeg"
        WebAssetCheck.check("favicon", "image/webp", webp) shouldBe WebAssetCheck.webp
        WebAssetCheck.check("favicon", "image/svg+xml", svg) shouldBe WebAssetCheck.svg
        // Browsers do not agree on an ICO's type; both are one kind, stored under one.
        WebAssetCheck.check("favicon", "image/x-icon", ico) shouldBe WebAssetCheck.ico
        WebAssetCheck.check("favicon", "image/vnd.microsoft.icon", ico).mimeType shouldBe "image/x-icon"
        WebAssetCheck.check("favicon", "image/ico", ico).mimeType shouldBe "image/x-icon"
        // What a file picker is told to offer is every type an upload is taken under, and no other.
        WebAssetCheck.acceptedMimeTypes.forEach { WebAssetCheck.declaredKind(it).shouldNotBeNull() }
        WebAssetCheck.acceptedMimeTypes.size shouldBe 9
        // A type's parameters and case are not part of what it declares.
        WebAssetCheck.check("favicon", "Image/SVG+XML; charset=utf-8", svg) shouldBe WebAssetCheck.svg
    }

    "a type that is no image of ours is refused, naming the kinds there are" {
        refusal("text/html", "<html></html>".toByteArray()) shouldContain "PNG, SVG, ICO, JPEG, WebP"
        refusal("application/octet-stream", png) shouldContain "'application/octet-stream'"
        refusal("", png) shouldContain "must be an image"
    }

    "content that is not what it was declared to be is refused, saying what it is" {
        refusal("image/png", jpeg) shouldContain "sent as a PNG image, but its content is a JPEG image"
        refusal("image/png", "<html>".toByteArray()) shouldContain "its content is not one"
        refusal("image/png", svg) shouldContain "its content is not one"
        refusal("image/svg+xml", png) shouldContain "sent as an SVG image, but its content is a PNG image"
        // A WebP is a RIFF file, and not every RIFF file is a WebP.
        refusal("image/webp", "RIFF".toByteArray() + byteArrayOf(4, 0, 0, 0) + "WAVEfmt ".toByteArray()) shouldContain "not one"
        refusal("image/png", ByteArray(0)) shouldContain "not one"
    }

    "an SVG must be well-formed XML whose root is svg" {
        WebAssetCheck.svgProblem(svg).shouldBeNull()
        WebAssetCheck.svgProblem("<svg><circle></svg>".toByteArray()).shouldNotBeNull() shouldContain "not well-formed"
        WebAssetCheck.svgProblem("just text".toByteArray()).shouldNotBeNull() shouldContain "not well-formed"
        WebAssetCheck.svgProblem("<html><body/></html>".toByteArray()) shouldBe "its root element is 'html', not 'svg'"
        refusal("image/svg+xml", "<html><body/></html>".toByteArray()) shouldContain "root element is 'html'"
    }

    "an SVG that carries script is refused: an element, an event handler, a javascript address" {
        fun problem(body: String) = WebAssetCheck.svgProblem("""<svg xmlns="http://www.w3.org/2000/svg" xmlns:xlink="http://www.w3.org/1999/xlink">$body</svg>""".toByteArray())

        problem("<script>alert(1)</script>").shouldNotBeNull() shouldContain "'script' element"
        problem("<g><SCRIPT/></g>").shouldNotBeNull() shouldContain "element"
        // An HTML script inside a foreignObject is in another namespace, and is a script all the same.
        problem("""<foreignObject><h:script xmlns:h="http://www.w3.org/1999/xhtml">x</h:script></foreignObject>""")
            .shouldNotBeNull() shouldContain "'h:script'"
        problem("""<circle onclick="x()"/>""").shouldNotBeNull() shouldContain "event handler, 'onclick'"
        problem("""<a xlink:href="javascript:alert(1)"><circle/></a>""").shouldNotBeNull() shouldContain "'xlink:href'"
        // What a browser drops from an address before reading it does not hide one.
        problem("<a href=\" java&#9;script:alert(1)\"><circle/></a>").shouldNotBeNull() shouldContain "'javascript:' address"
        // The root's own handler counts: it is the one that runs on load.
        WebAssetCheck.svgProblem("""<svg xmlns="http://www.w3.org/2000/svg" onload="x()"/>""".toByteArray())
            .shouldNotBeNull() shouldContain "'onload'"
        // And none of this mistakes an ordinary drawing: a link, a style, text that merely says "script".
        problem("""<a xlink:href="https://example.test/"><text>javascript: a script</text></a><style>circle{fill:red}</style>""").shouldBeNull()
    }

    "an SVG's DOCTYPE is read without fetching what it names, and one built to grow is not well-formed" {
        // What a drawing tool writes: a DTD on the web, which must never be asked for. An unroutable address, so
        // a parser that did ask would fail or hang rather than pass.
        val withDoctype = """<?xml version="1.0"?>
            <!DOCTYPE svg PUBLIC "-//W3C//DTD SVG 1.1//EN" "http://192.0.2.1/Graphics/SVG/1.1/DTD/svg11.dtd">
            <svg xmlns="http://www.w3.org/2000/svg"><circle r="3"/></svg>""".trimIndent().toByteArray()
        WebAssetCheck.svgProblem(withDoctype).shouldBeNull()

        // An external entity is given as empty, never read: nothing of this machine's files reaches the document.
        val external = """<?xml version="1.0"?>
            <!DOCTYPE svg [<!ENTITY secret SYSTEM "file:///etc/hosts">]>
            <svg xmlns="http://www.w3.org/2000/svg"><text>&secret;</text></svg>""".trimIndent().toByteArray()
        WebAssetCheck.svgProblem(external).shouldBeNull()

        val bomb = buildString {
            append("<?xml version=\"1.0\"?><!DOCTYPE svg [<!ENTITY a0 \"aaaaaaaaaa\">")
            for (i in 1..12) append("<!ENTITY a$i \"&a${i - 1};&a${i - 1};&a${i - 1};&a${i - 1};&a${i - 1};&a${i - 1};&a${i - 1};&a${i - 1};&a${i - 1};&a${i - 1};\">")
            append("]><svg xmlns=\"http://www.w3.org/2000/svg\"><text>&a12;</text></svg>")
        }.toByteArray()
        WebAssetCheck.svgProblem(bomb).shouldNotBeNull() shouldContain "not well-formed"
    }

    "a parser that cannot be told to fetch nothing is this server's fault, and no SVG is read by it" {
        // Another parser on the classpath, which the JDK finds by this property: one that does not know the
        // feature that stops a DTD being fetched. That is a SAXException, as a malformed file is.
        val property = SAXParserFactory::class.java.name
        val previous = System.getProperty(property)
        System.setProperty(property, RefusingSaxFactory::class.java.name)
        try {
            val e = shouldThrow<KdrException> { WebAssetCheck.svgProblem(svg) }
            // Not a word about the file, which is a perfectly good SVG -- and not a refusal the uploader is sent.
            e.code shouldBe EXC.internalError
            e.message.orEmpty() shouldContain "cannot be set up"
            shouldThrow<KdrException> { WebAssetCheck.check("favicon", "image/svg+xml", svg) }.code shouldBe EXC.internalError
        } finally {
            if (previous == null) System.clearProperty(property) else System.setProperty(property, previous)
        }
        WebAssetCheck.svgProblem(svg).shouldBeNull()
    }
})
