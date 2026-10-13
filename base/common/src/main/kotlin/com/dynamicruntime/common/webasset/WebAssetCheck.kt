package com.dynamicruntime.common.webasset

import com.dynamicruntime.common.content.HashedFileServing
import com.dynamicruntime.common.exception.ACT
import com.dynamicruntime.common.exception.EXC
import com.dynamicruntime.common.exception.KdrException
import com.dynamicruntime.common.exception.SRC
import org.xml.sax.Attributes
import org.xml.sax.InputSource
import org.xml.sax.SAXException
import org.xml.sax.helpers.DefaultHandler
import java.io.ByteArrayInputStream
import java.io.IOException
import java.io.StringReader
import javax.xml.XMLConstants
import javax.xml.parsers.ParserConfigurationException
import javax.xml.parsers.SAXParser
import javax.xml.parsers.SAXParserFactory

/**
 * One kind of image a web asset may be (issue #1130): what a person calls it, the content type it is stored and
 * served as, and the other types an upload may honestly declare it under -- browsers do not agree on an ICO's.
 */
class WebImageKind(
    val label: String,
    val mimeType: String,
    /** What a file of this kind is called after its dot, for a name offered when one is saved. */
    val extension: String,
    val alsoDeclaredAs: Set<String> = emptySet(),
)

/**
 * Whether an uploaded web asset is the image it says it is (issue #1130).
 *
 * **The declared type is checked against the bytes, and the bytes decide.** An upload's content type is the
 * browser's guess from a file name, which is to say the uploader's claim. What is stored is served to every one of
 * the client's people under that type, so a claim nobody checked would let an HTML page be stored as an "image":
 * the type recorded is the one the content was found to be, and an upload whose claim and content disagree is
 * refused rather than corrected, since it is more likely a wrong file than a wrong label.
 *
 * A raster image is known by how it begins. An SVG has no such mark -- it is a text document -- so it is parsed:
 * it must be well-formed XML whose root is `svg`, and **carry no script**: no `script` element, no event-handler
 * attribute (`onload`, `onclick`), no `javascript:` address. The app only ever draws one in an `<img>`, where a
 * browser runs none of that; the check is for the day somebody opens the image's address on its own, which the
 * headers it is served with guard as well.
 */
@Suppress("ConstPropertyName")
object WebAssetCheck {
    val png = WebImageKind("PNG", "image/png", "png")
    val svg = WebImageKind("SVG", HashedFileServing.svgMimeType, "svg")
    val ico = WebImageKind("ICO", "image/x-icon", "ico", setOf("image/vnd.microsoft.icon", "image/ico"))
    val jpeg = WebImageKind("JPEG", "image/jpeg", "jpg", setOf("image/jpg", "image/pjpeg"))
    val webp = WebImageKind("WebP", "image/webp", "webp")

    /** Every kind accepted, in the order a message lists them. */
    val kinds = listOf(png, svg, ico, jpeg, webp)

    /** The content types a file picker should offer: each kind's own and the others it is declared under. */
    val acceptedMimeTypes: List<String> = kinds.flatMap { listOf(it.mimeType) + it.alsoDeclaredAs }

    private val pngStart = byteArrayOf(0x89.toByte(), 'P'.code.toByte(), 'N'.code.toByte(), 'G'.code.toByte(), 0x0D, 0x0A, 0x1A, 0x0A)
    private val jpegStart = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte())
    private val icoStart = byteArrayOf(0, 0, 1, 0)
    private val riff = "RIFF".toByteArray(Charsets.US_ASCII)
    private val webpMark = "WEBP".toByteArray(Charsets.US_ASCII)

    /** Where a WebP's own mark sits: after `RIFF` and the four bytes of its length. */
    private const val webpMarkAt = 8

    private fun ByteArray.holds(mark: ByteArray, at: Int = 0): Boolean =
        size >= at + mark.size && mark.indices.all { this[at + it] == mark[it] }

    /** The kind [mimeType] declares -- its parameters and case aside -- or null when it is none of ours. */
    fun declaredKind(mimeType: String): WebImageKind? {
        val bare = mimeType.substringBefore(';').trim().lowercase()
        return kinds.firstOrNull { it.mimeType == bare || bare in it.alsoDeclaredAs }
    }

    /** The raster kind [bytes] begin as, or null: not a raster image we take (an SVG has no such beginning). */
    fun rasterKind(bytes: ByteArray): WebImageKind? = when {
        bytes.holds(pngStart) -> png
        bytes.holds(jpegStart) -> jpeg
        bytes.holds(riff) && bytes.holds(webpMark, webpMarkAt) -> webp
        bytes.holds(icoStart) -> ico
        else -> null
    }

    /**
     * The kind of image [bytes] are, having been uploaded as [declaredType] for [what] ("favicon"). Refuses, as bad
     * input and in words the uploader can act on, a type we do not take, content that is not what it was declared
     * to be, and an SVG that is not one or carries script.
     */
    fun check(what: String, declaredType: String, bytes: ByteArray): WebImageKind {
        val accepted = kinds.joinToString { it.label }
        val declared = declaredKind(declaredType) ?: throw KdrException.mkInput(
            "A $what must be an image of one of these kinds: $accepted. This file was sent as '$declaredType'.",
        )
        val raster = rasterKind(bytes)
        if (declared == svg) {
            if (raster != null) {
                throw KdrException.mkInput("This file was sent as an SVG image, but its content is a ${raster.label} image.")
            }
            svgProblem(bytes)?.let { throw KdrException.mkInput("This file is not an SVG image we can take: $it.") }
            return svg
        }
        if (raster != declared) {
            val found = raster?.let { "a ${it.label} image" } ?: "not one"
            throw KdrException.mkInput("This file was sent as a ${declared.label} image, but its content is $found.")
        }
        return declared
    }

    /**
     * What stops [bytes] being an SVG we take, or null: not well-formed XML, a root that is not `svg`, or script
     * (see the class note).
     *
     * Parsed by the JDK's own parser, told to fetch nothing: an SVG may open with a `DOCTYPE` naming a DTD on the
     * web -- drawing tools write one -- and reading that would have this server calling out to an address an
     * uploader chose. Its limits on entity expansion are left on, so a document built to grow without end fails
     * here as not well-formed.
     */
    fun svgProblem(bytes: ByteArray): String? {
        val parser = safeParser()
        val handler = SvgScan()
        try {
            parser.parse(ByteArrayInputStream(bytes), handler)
        } catch (e: SAXException) {
            return "it is not well-formed XML (${e.message})"
        } catch (e: IOException) {
            return "it could not be read as XML (${e.message})"
        }
        return when {
            handler.root == null -> "it holds no element"
            !handler.root.equals(svgRoot, ignoreCase = true) -> "its root element is '${handler.root}', not '$svgRoot'"
            else -> handler.script
        }
    }

    /**
     * A parser told to fetch nothing and to bound what it expands. **Made apart from the parse**, so that a
     * parser which cannot be told so -- another implementation on the classpath, one that does not know a
     * feature named here -- is this server's fault and is reported as one: an error, never a refusal telling an
     * uploader their file is malformed. And never a parse without the protections: no SVG is read by a parser
     * that would not take them.
     */
    private fun safeParser(): SAXParser =
        try {
            val factory = SAXParserFactory.newInstance()
            factory.isNamespaceAware = true
            factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true)
            factory.setFeature(externalGeneralEntities, false)
            factory.setFeature(externalParameterEntities, false)
            factory.setFeature(loadExternalDtd, false)
            factory.newSAXParser()
        } catch (e: SAXException) {
            // A feature this parser does not recognize or will not set is a SAXException too.
            throw parserFault(e)
        } catch (e: ParserConfigurationException) {
            throw parserFault(e)
        }

    private fun parserFault(e: Exception): KdrException =
        KdrException("This JVM's XML parser cannot be set up to read an SVG safely: ${e.message}", e, EXC.internalError, SRC.config, ACT.code)

    /** Reads an SVG's elements and attributes for its root and for the first script it carries. */
    private class SvgScan : DefaultHandler() {
        var root: String? = null
        var script: String? = null

        // An entity the document asks for from elsewhere is given as empty, and never fetched.
        override fun resolveEntity(publicId: String?, systemId: String?): InputSource = InputSource(StringReader(""))

        override fun startElement(uri: String?, localName: String, qName: String, attributes: Attributes) {
            if (root == null) root = localName
            if (script != null) return
            // By local name whatever its namespace: an HTML `script` inside a `foreignObject` is one too.
            if (localName.equals(scriptElement, ignoreCase = true)) {
                script = "it holds a '$qName' element"
                return
            }
            for (i in 0 until attributes.length) {
                val name = attributes.getLocalName(i)
                // Control characters and spaces are dropped by a browser reading an address, so they are here.
                val value = attributes.getValue(i).filter { it > ' ' }
                if (name.startsWith(handlerPrefix, ignoreCase = true)) {
                    script = "its '$qName' element has an event handler, '${attributes.getQName(i)}'"
                } else if (value.startsWith(scriptScheme, ignoreCase = true)) {
                    script = "its '$qName' element has a '$scriptScheme' address in '${attributes.getQName(i)}'"
                }
                if (script != null) return
            }
        }
    }

    private const val svgRoot = "svg"
    private const val scriptElement = "script"
    private const val handlerPrefix = "on"
    private const val scriptScheme = "javascript:"
    private const val externalGeneralEntities = "http://xml.org/sax/features/external-general-entities"
    private const val externalParameterEntities = "http://xml.org/sax/features/external-parameter-entities"
    private const val loadExternalDtd = "http://apache.org/xml/features/nonvalidating/load-external-dtd"
}
