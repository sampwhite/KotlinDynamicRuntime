package com.dynamicruntime.common.content

import com.dynamicruntime.common.context.ENV
import com.dynamicruntime.common.context.ENVGRP
import com.dynamicruntime.common.context.EnvVarDef
import com.dynamicruntime.common.context.KdrCxt
import com.dynamicruntime.common.exception.KdrException
import com.dynamicruntime.common.filestore.FileMeta
import com.dynamicruntime.common.filestore.StoredFile
import com.dynamicruntime.common.http.request.ContentServer
import com.dynamicruntime.common.logging.LogStartup
import com.dynamicruntime.common.http.request.RequestHandler
import com.dynamicruntime.common.http.request.RequestService
import com.dynamicruntime.common.naming.OwnedNameKind
import com.dynamicruntime.common.naming.componentNameProblem
import com.dynamicruntime.common.startup.BCHK
import com.dynamicruntime.common.startup.BootCheckMode
import com.dynamicruntime.common.startup.BootCheckRegistry
import com.dynamicruntime.common.startup.ServiceInitializer
import com.dynamicruntime.common.startup.bootCheckMode
import com.dynamicruntime.common.startup.modeOverride
import com.dynamicruntime.common.util.MDR
import com.dynamicruntime.common.util.componentImageNames
import com.dynamicruntime.common.util.sha256Hex
import com.dynamicruntime.common.webasset.WebAssetCheck
import kotlin.time.Instant

/**
 * An image a component ships in its resources (issue #1339): its rooted [name] (`sample:diagram`), what copy names it
 * by as `![alt](img:sample:diagram)`, and the [fileName] it is read from, under [IMGS.resourceDir] on the classpath. A
 * name under another owner's root names that root in [contributesTo], as a cfact's does.
 */
class ComponentImageDef(val name: String, val fileName: String, val contributesTo: String? = null)

/** Declares an image a component ships: see [ComponentImageDef]. */
fun componentImage(name: String, fileName: String, contributesTo: String? = null): ComponentImageDef =
    ComponentImageDef(name, fileName, contributesTo)

/** A [def] as the boot collected it: with the component that declared it, and that component's owner root. */
class DeclaredComponentImage(val def: ComponentImageDef, val component: String, val ownerRoot: String?)

/** Names for the images components ship (issue #1339). */
@Suppress("ConstPropertyName")
object IMGS {
    /** Where the declared images are kept in the instance config, as the boot collected them. */
    const val registryKey = "componentImages"

    /** The classpath directory a component's images are read from. */
    const val resourceDir = "component-images"

    /** What an image problem does at startup. */
    val checkEnvVar = EnvVarDef(
        "KDR_IMAGE_CHECK", group = ENVGRP.content, defaultDoc = "strict (`warn` in `prod`)",
        description = "What a problem with an image a component ships does at startup -- a file that is missing, too " +
            "large, or not an image, a name declared twice or under another owner's root: `strict`, `warn` or `off`. " +
            "Unset means strict everywhere except `prod`, where the image is left out and copy naming it draws its " +
            "alt text.",
    )

    /** The images the boot collected, as declared. */
    @Suppress("UNCHECKED_CAST")
    fun declared(cxt: KdrCxt): List<DeclaredComponentImage> =
        cxt.instanceConfig.get(registryKey) as? List<DeclaredComponentImage> ?: emptyList()

    /** The names of the images the boot collected: what copy may name. */
    fun declaredNames(cxt: KdrCxt): Set<String> = declared(cxt).mapTo(HashSet()) { it.def.name }

    /** Whether [fileName] may name a file in [resourceDir]: a plain name and an extension, never a path. */
    fun isSafeFileName(fileName: String): Boolean {
        val stem = fileName.substringBeforeLast('.', "")
        val extension = fileName.substringAfterLast('.', "")
        return ContentResources.isSafeFileId(stem) && extension.isNotEmpty() && extension.all { it in 'a'..'z' }
    }
}

/**
 * What copy every client shares names of component images no component ships (issue #1339): one finding per key and
 * name. For the fragment check at boot ([MarkdownFragmentService.checkFragmentsAtStartup]), which judges the files
 * every client shares; a client's own copy is judged with its configuration (`FileRefService`).
 */
fun sharedCopyNamingNoImage(cxt: KdrCxt, sources: List<FragmentSource>): List<String> {
    val declared = IMGS.declaredNames(cxt)
    val prefix = "${MDR.imageScheme}:"
    val findings = mutableListOf<String>()
    for (layer in sources) {
        if (layer.client != null) continue
        val content = layer.load() ?: continue
        for ((namespace, keys) in content) {
            for ((key, value) in keys) {
                if (!value.contains(prefix)) continue
                for (name in value.componentImageNames()) {
                    if (name !in declared) {
                        findings.add("'${layer.fileId}' $namespace.$key shows the image $prefix$name, which no component ships")
                    }
                }
            }
        }
    }
    return findings.distinct()
}

/**
 * Serves the images components ship (issue #1339), under the static root at `/<appId>/img/<name:hash>`
 * ([IMG.staticPath]), as file refs and web assets are served ([HashedFileServing]):
 * kept for good when the hash is the image's own, the current image under `no-store` for any other, and a
 * `no-store` 404 for no such image.
 *
 * Every declared image is read once, at boot, from the classpath -- the classpath is not enumerable, so what is
 * declared is what is read -- and held: a component ships a few small images, each no larger than a file ref may be
 * ([IMG.maxSize]). Each is checked as a file a client keeps is ([WebAssetCheck.sniff]: a raster image by its bytes, an
 * SVG with nothing in it to run), and its name as a cfact's is: rooted under the declaring component's owner root,
 * declared once. A problem is a boot problem ([IMGS.checkEnvVar]): refused outside production, and in it reported,
 * the image left out so copy naming it draws its alt text. The same for every caller, and on every kind of node, as
 * the fragments are: nothing here is a client's or kept in the file store.
 */
class ComponentImageService : ServiceInitializer, ContentServer {
    override val serviceName: String = ComponentImageService.serviceName

    /** Each image served, by its rooted name. */
    @Volatile
    var served: Map<String, StoredFile> = emptyMap()
        private set

    private var isInit = false

    /** Reads and checks every declared image, and registers this with the dispatcher as a content server (idempotent). */
    override fun checkInit(cxt: KdrCxt) {
        if (isInit) return
        val findings = mutableListOf<String>()
        val read = LinkedHashMap<String, StoredFile>()
        val declared = IMGS.declared(cxt)
        val counts = declared.groupingBy { it.def.name }.eachCount()
        for (image in declared) {
            val name = image.def.name
            fun fault(why: String) = findings.add("Component '${image.component}' declares the image '$name': $why")
            val nameProblem = componentNameProblem(OwnedNameKind.image, name, image.ownerRoot, image.def.contributesTo)
            val (file, problem) = when {
                nameProblem != null -> null to nameProblem
                (counts[name] ?: 0) > 1 -> null to "it is declared ${counts[name]} times; each image is declared once."
                else -> readImage(image.def, cxt.now())
            }
            if (file != null) read[name] = file else fault(problem.orEmpty())
        }
        served = read
        val mode = bootCheckMode(cxt, modeOverride(cxt, IMGS.checkEnvVar), prodMode = BootCheckMode.warn)
        BootCheckRegistry.get(cxt).record(BCHK.images, IMGS.checkEnvVar.name, mode, findings.distinct())
        if (findings.isNotEmpty() && mode != BootCheckMode.off) {
            val detail = findings.distinct().joinToString("; ")
            if (mode == BootCheckMode.strict) {
                throw KdrException(
                    "Images components ship have problems: $detail. Fix them, or set ${IMGS.checkEnvVar}=" +
                        "${BootCheckMode.warn} to start anyway (which is the default in ${ENV.prod}).",
                )
            }
            LogStartup.warn(cxt, "Images components ship have problems, and are left out: $detail")
        }
        RequestService.get(cxt).addContentServer(this)
        isInit = true
    }

    /** [def]'s file as it would be served, or why it cannot be. */
    private fun readImage(def: ComponentImageDef, readAt: Instant): Pair<StoredFile?, String?> {
        if (!IMGS.isSafeFileName(def.fileName)) {
            return null to "its file '${def.fileName}' is not a plain file name with an extension."
        }
        val path = "/${IMGS.resourceDir}/${def.fileName}"
        val bytes = ComponentImageService::class.java.getResourceAsStream(path)?.use { it.readNBytes(IMG.maxSize + 1) }
            ?: return null to "there is no resource '$path' on the classpath."
        if (bytes.size > IMG.maxSize) return null to "its file '${def.fileName}' is larger than ${IMG.maxSize} bytes."
        val kind = WebAssetCheck.sniff(bytes) ?: return null to "its file '${def.fileName}' is not an image this serves " +
            "(PNG, JPEG, WebP, ICO, or an SVG with nothing in it to run)."
        val meta = FileMeta(mimeType = kind.mimeType, size = bytes.size.toLong(), hash = bytes.sha256Hex(), uploadedAt = readAt, fileName = def.fileName)
        return StoredFile(bytes, meta) to null
    }

    /** Each image served, by its rooted name, to its name with its hash: what the shell config hands the webapp. */
    fun servedNames(): Map<String, String> = served.mapValues { (name, file) -> IMG.versionedName(name, file.meta.hash) }

    override fun serve(cxt: KdrCxt, handler: RequestHandler): Boolean {
        // A rooted name holds one separator of its own (issue #1339), so the hash follows the second.
        val address = HashedFileServing.addressOf(handler, CMK.img, nameSeps = 1) ?: return false
        HashedFileServing.send(handler, address, served[address.name], "No image '${address.name}'.")
        return true
    }

    @Suppress("ConstPropertyName")
    companion object {
        const val serviceName = "ComponentImageService"

        fun get(cxt: KdrCxt): ComponentImageService =
            getOrNull(cxt) ?: throw KdrException("The $serviceName is not available on this node.")

        fun getOrNull(cxt: KdrCxt): ComponentImageService? = cxt.instanceConfig.get(serviceName) as? ComponentImageService
    }
}
