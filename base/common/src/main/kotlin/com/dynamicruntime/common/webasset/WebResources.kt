package com.dynamicruntime.common.webasset

import com.dynamicruntime.common.content.ContentResources
import com.dynamicruntime.common.content.IMGS
import com.dynamicruntime.common.context.KdrCxt
import com.dynamicruntime.common.filestore.FileMeta
import com.dynamicruntime.common.filestore.StoredFile
import com.dynamicruntime.common.naming.OwnedNameKind
import com.dynamicruntime.common.naming.componentNameProblem
import com.dynamicruntime.common.util.sha256Hex
import kotlin.time.Instant

/**
 * A **web resources package** a component ships (issue #1301): the images a client presents itself with, read from the
 * classpath, which a client defined in source code names as its `webResourcesId`. Its rooted [id] (`sample:acme`) is
 * what the definition names, and [files] holds each slot's file ([WAS.favicon], [WAS.brandMark]) as a path under
 * [WRES.resourceDir] (`acme/favicon.svg`), whose extension says what kind of image it is. A package need not fill
 * every slot: one it leaves empty is the deployment's. An id under another owner's root names that root in
 * [contributesTo], as an image's does.
 *
 * A client's own upload stands in front of its package's image (`ClientWebAssetService`), and removing the upload
 * shows the package's again.
 */
class WebResourcesDef(val id: String, val files: Map<String, String>, val contributesTo: String? = null)

/** Declares a web resources package a component ships: see [WebResourcesDef]. Each slot's file is a path under `web-resources/`. */
fun webResources(id: String, favicon: String? = null, brandMark: String? = null, contributesTo: String? = null): WebResourcesDef =
    WebResourcesDef(
        id,
        buildMap {
            favicon?.let { put(WAS.favicon, it) }
            brandMark?.let { put(WAS.brandMark, it) }
        },
        contributesTo,
    )

/** A [def] as the boot collected it: with the component that declared it, and that component's owner root. */
class DeclaredWebResources(val def: WebResourcesDef, val component: String, val ownerRoot: String?)

/** Names for the web resources packages components ship (issue #1301). */
@Suppress("ConstPropertyName")
object WRES {
    /** Where the declared packages are kept in the instance config, as the boot collected them. */
    const val registryKey = "webResources"

    /** The classpath directory a package's files are read from. */
    const val resourceDir = "web-resources"

    /** The packages the boot collected, as declared. */
    @Suppress("UNCHECKED_CAST")
    fun declared(cxt: KdrCxt): List<DeclaredWebResources> =
        cxt.instanceConfig.get(registryKey) as? List<DeclaredWebResources> ?: emptyList()

    /** The ids of the packages the boot collected: what a client's definition may name. */
    fun declaredIds(cxt: KdrCxt): Set<String> = declared(cxt).mapTo(HashSet()) { it.def.id }

    /**
     * Whether [path] may name a file in [resourceDir]: plain directory names, then a plain file name with an
     * extension ([IMGS.isSafeFileName]) -- never `..`, an absolute path, or an empty segment.
     */
    fun isSafePath(path: String): Boolean {
        val segments = path.split('/')
        return segments.dropLast(1).all { ContentResources.isSafeFileId(it) } && IMGS.isSafeFileName(segments.last())
    }

    /** The kind of image a file named with [extension] is meant to be, or null when it names none we take. */
    fun kindOfExtension(extension: String): WebImageKind? =
        if (extension == "jpeg") WebAssetKinds.jpeg else WebAssetKinds.kinds.firstOrNull { it.extension == extension }
}

/**
 * Reads and checks every declared package (issue #1301), as `ComponentImageService` reads the images components ship:
 * each package's files by slot, and the faults found, each a sentence naming the component and the package. A
 * package's id is held to the rule an image's name is (rooted under the declaring component's owner root, declared
 * once); each file to the slot it fills, as an upload to that slot would be -- a slot there is, a safe path, present,
 * no larger than the slot takes, and the image its extension says it is ([WebAssetCheck.sniff]). A faulty file is left
 * out, and a package with a faulty id is left out whole.
 */
fun readWebResources(declared: List<DeclaredWebResources>, readAt: Instant): Pair<Map<String, Map<String, StoredFile>>, List<String>> {
    val findings = mutableListOf<String>()
    val read = LinkedHashMap<String, Map<String, StoredFile>>()
    val counts = declared.groupingBy { it.def.id }.eachCount()
    for (pkg in declared) {
        val id = pkg.def.id
        fun fault(why: String) = findings.add("Component '${pkg.component}' declares the web resources '$id': $why")
        val idProblem = componentNameProblem(OwnedNameKind.webResources, id, pkg.ownerRoot, pkg.def.contributesTo)
        when {
            idProblem != null -> fault(idProblem)
            (counts[id] ?: 0) > 1 -> fault("it is declared ${counts[id]} times; each package is declared once.")
            pkg.def.files.isEmpty() -> fault("it names no file for any slot.")
            else -> {
                val files = LinkedHashMap<String, StoredFile>()
                for ((slot, path) in pkg.def.files) {
                    val (file, problem) = readPackageFile(slot, path, readAt)
                    if (file != null) files[slot] = file else fault(problem.orEmpty())
                }
                read[id] = files
            }
        }
    }
    return read to findings.distinct()
}

/** The file at [path] as [slot]'s image, or why it cannot be. */
private fun readPackageFile(slot: String, path: String, readAt: Instant): Pair<StoredFile?, String?> {
    val def = WebAssetKinds.slotDef(slot)
        ?: return null to "it names a file for the slot '$slot'; the slots are ${WebAssetKinds.slotDefs.joinToString { it.slot }}."
    val what = def.label.lowercase()
    if (!WRES.isSafePath(path)) return null to "its $what '$path' is not a plain path to a file with an extension."
    val expected = WRES.kindOfExtension(path.substringAfterLast('.'))
        ?: return null to "its $what '$path' is not named as an image we take (${WebAssetKinds.kinds.joinToString { it.extension }})."
    val resource = "/${WRES.resourceDir}/$path"
    val bytes = WebResourcesDef::class.java.getResourceAsStream(resource)?.use { it.readNBytes(def.maxSize + 1) }
        ?: return null to "there is no resource '$resource' on the classpath."
    if (bytes.size > def.maxSize) return null to "its $what '$path' is larger than the ${def.maxSize} bytes a $what may be."
    val found = WebAssetCheck.sniff(bytes)
    if (found != expected) {
        val content = found?.let { "${it.withArticle()} image" } ?: "not an image we take (an SVG must carry nothing to run)"
        return null to "its $what '$path' is named as ${expected.withArticle()} image, but its content is $content."
    }
    val meta = FileMeta(mimeType = expected.mimeType, size = bytes.size.toLong(), hash = bytes.sha256Hex(), uploadedAt = readAt, fileName = path.substringAfterLast('/'))
    return StoredFile(bytes, meta) to null
}

/** The kind's label after "a" or "an", by how its first letter is said: "a PNG", "an SVG", "an ICO". */
private fun WebImageKind.withArticle(): String = (if (label.first() in "AEFHILMNORSX") "an " else "a ") + label
