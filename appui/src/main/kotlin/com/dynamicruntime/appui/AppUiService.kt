package com.dynamicruntime.appui

import com.dynamicruntime.common.content.ComponentImageService
import com.dynamicruntime.common.content.ContentResources
import com.dynamicruntime.common.content.MarkdownFragmentService
import com.dynamicruntime.common.context.KdrCxt
import com.dynamicruntime.common.exception.EXC
import com.dynamicruntime.common.exception.KdrException
import com.dynamicruntime.common.home.HFRAG
import com.dynamicruntime.common.http.request.ContentServer
import com.dynamicruntime.common.http.request.ContextFocus
import com.dynamicruntime.common.http.request.LogRequest
import com.dynamicruntime.common.http.request.RequestHandler
import com.dynamicruntime.common.http.request.RequestService
import com.dynamicruntime.common.startup.ServiceInitializer
import com.dynamicruntime.common.user.INVITE
import com.dynamicruntime.common.endpoint.EP
import com.dynamicruntime.common.util.crc32Hex
import com.dynamicruntime.common.util.renderMarkdownText
import com.dynamicruntime.common.util.toJsonStr
import com.dynamicruntime.common.filestore.StoredFile
import com.dynamicruntime.common.webasset.WAS
import com.dynamicruntime.common.webasset.WRES

/**
 * Webapp host paths, resource locations, and MIME types. Kept short and referenced qualified, per the code
 * guide. The [defaultResourceDir] is the contract with `appui/build.gradle.kts`, which copies the webapp's
 * production distribution onto the classpath under that directory; the two must agree.
 */
@Suppress("ConstPropertyName")
object AUI {
    /** Classpath directory the build embeds the webapp's distribution at (see `appui/build.gradle.kts`). Also,
     *  where a branding asset is read from when the deployment's default web resources package does not supply it. */
    const val defaultResourceDir = "webapp"

    // --- The app's own assets. Fixed: these are the application, not its livery, so they are never branded.
    // Notably the stylesheet: overriding it by file replacement would fork the whole sheet and re-create the
    // drift that having a single app.css exists to prevent. Theming belongs in CSS variables, not a copy.

    /** Application path (context-root-stripped) of the webapp's JS bundle, e.g., reached at `/wa/webapp.js`. */
    const val bundlePath = "/webapp.js"

    /** Application path of the bundle's sourcemap. */
    const val bundleMapPath = "/webapp.js.map"

    /** Classpath location the build embeds the bundle at. */
    const val bundleResource = "/webapp/webapp.js"

    /** Classpath location of the embedded sourcemap. */
    const val bundleMapResource = "/webapp/webapp.js.map"

    /** Application path of the webapp's stylesheet, e.g., reached at `/wa/app.css`; declared by [AppUiPage].
     *  It is the *same* sheet the dev server serves — the webapp authors exactly one. */
    const val stylesheetPath = "/app.css"

    /** Classpath location of the embedded stylesheet. */
    const val stylesheetResource = "/webapp/app.css"

    // --- Branding assets. The deployment's default web resources package (WRES.defaultKey, issue #1779) may supply
    // the bytes behind these, by slot; the URLs are fixed, so the shells' <link> tags never change. Each file name
    // doubles as its application path ("/" + file), which is why one constant serves both.

    /** The tab icon: the mark with heavier strokes, for 16px legibility. Declared by both shells. */
    const val faviconFile = "favicon.svg"

    /** The master mark at display size: the app bar's logo and the home hero. The frontend builds its URL from
     *  the live context root — see `brandMarkUrl` in the webapp. */
    const val brandMarkFile = "brand-mark.svg"

    /** The PNG tab icon — the fallback the shells declare for browsers that do not take the SVG one. */
    const val faviconPngFile = "favicon-32.png"

    /** The iOS home-screen icon (180×180, opaque). */
    const val appleTouchIconFile = "apple-touch-icon.png"

    /**
     * The image a link preview shows for the site (issue #1688): 1200×630, on its own ground, named by the shell's
     * `og:image`. A raster, because the previews (LinkedIn, Slack, messengers) take no SVG; its source,
     * `share-image.svg` in the webapp's resources, is not embedded.
     */
    const val shareImageFile = "share-image.png"

    /** The page's title where no copy gives one: the `home` file's `site` namespace is missing or its title empty. */
    const val defaultSiteTitle = "KotlinDynamicRuntime"

    /**
     * The legacy ICO tab icon. Served for completeness, but note browsers request `/favicon.ico` by convention
     * **at the origin root**, which is not this app's root — the runtime 404s an unknown context root — so
     * nothing asks for it here unless a deployment fronts the app at the origin root. The SVG and PNG links
     * are what actually cover browsers; see the webapp's resources README.
     *
     * Unlike its siblings it is addressed by **nothing** at a hashed URL — no shell `<link>` (those cover
     * browsers with the SVG/PNG) and no bootstrap entry (the browser, not the frontend, picks this URL) — so it
     * can only ever be served bare, under `no-store` (issue #529). Accepted rather than fixed: it is
     * near-never requested here (see above), and a browser-chosen fixed URL cannot be content-addressed anyway.
     */
    const val faviconIcoFile = "favicon.ico"

    const val htmlMimeType = "text/html; charset=utf-8"
    const val jsMimeType = "application/javascript; charset=utf-8"
    const val jsonMimeType = "application/json"
    const val svgMimeType = "image/svg+xml"
    const val cssMimeType = "text/css; charset=utf-8"
    const val pngMimeType = "image/png"

    /** The de-facto type every browser uses for an ICO (rather than the registered `image/vnd.microsoft.icon`). */
    const val icoMimeType = "image/x-icon"

    /** The HTML shell must be **revalidated before every use**, so the browser sees the current hashed asset
     *  URLs after a deployment (issue #137). `no-cache` permits storing the shell but requires that
     *  revalidation — deliberately distinct from [ContentResources.noStore] (`no-store`), which forbids storing
     *  at all. The permanent header for a hashed asset is [ContentResources.cacheControl]. */
    const val shellCacheControl = "no-cache"

    /**
     * The brandable set: every asset the deployment's default web resources package may supply (issue #1779), with
     * the package slot that supplies it and how it goes out. A table rather than a branch per file, because these are
     * handled uniformly -- the package's image when it has one of the kind the name says, else the built-in -- and a
     * package's author needs to be *told* which slots the shell links, which this list is. The ICO has no slot: the
     * built-in one is served.
     */
    val brandingAssets = listOf(
        BrandingAsset(faviconFile, svgMimeType, binary = false, slot = WAS.favicon),
        BrandingAsset(brandMarkFile, svgMimeType, binary = false, slot = WAS.brandMark),
        BrandingAsset(faviconPngFile, pngMimeType, binary = true, slot = WAS.faviconPng),
        BrandingAsset(appleTouchIconFile, pngMimeType, binary = true, slot = WAS.appleTouchIcon),
        BrandingAsset(shareImageFile, pngMimeType, binary = true, slot = WAS.shareImage),
        BrandingAsset(faviconIcoFile, icoMimeType, binary = true),
    )
}

/**
 * One brandable asset: the file a deployment may supply, and how it goes out. [binary] is not cosmetic —
 * [RequestHandler.sendStringResponse] is UTF-8 only and would corrupt an image, so a raster has to take
 * [RequestHandler.sendBytesResponse]. It is stated per asset rather than guessed from [mimeType], since SVG is
 * an image that *is* text. [slot] is the web resources package slot that may supply it (issue #1779), null for none.
 */
class BrandingAsset(val file: String, val mimeType: String, val binary: Boolean, val slot: String? = null) {
    /** The application path this asset is served at, e.g. `/favicon.svg` (reached at `/wa/favicon.svg`). */
    val appPath: String get() = "/$file"
}

/**
 * Serves the self-contained webapp entirely from within the running server, under its own context root (the
 * `app` focus, e.g. `/wa`). Like [com.dynamicruntime.common.portal.PortalService] it is purely a
 * [ContentServer] -- registered with the [RequestService] during init -- and contributes no endpoints. It
 * serves:
 *
 *  - The HTML shell at the app root (`appPath == "/"`), rendered by [AppUiPage];
 *  - The webapp's JS bundle (and sourcemap), read from the classpath resource the build embedded; and
 *  - The app's static assets — its stylesheet, icons, and brand mark — embedded from the same `:webapp`
 *    distribution the dev server serves them from, so both shells load byte-identical assets. Text assets go
 *    out through [RequestHandler.sendStringResponse] and the raster icons through
 *    [RequestHandler.sendBytesResponse]; the split is not cosmetic, as the text path is UTF-8 only and would
 *    corrupt an image.
 *
 * The bundle is the Kotlin/JS `:webapp` module's *production* output. Because the page is served same-origin
 * with the API context root, the webapp's relative `/kda/...` calls reach the runtime directly -- no CORS, no
 * proxy, and no separate webpack dev server.
 */
class AppUiService : ServiceInitializer, ContentServer {
    override val serviceName: String = AppUiService.serviceName

    /** Memoized content hashes of the served shell resources (issue #137), keyed by classpath resource path. */
    private val assetHashes = java.util.concurrent.ConcurrentHashMap<String, String>()

    /** Registers this content server with the dispatcher (idempotent). */
    override fun checkInit(cxt: KdrCxt) {
        val requestService = RequestService.get(cxt)
        requestService.checkInit(cxt)
        requestService.addContentServer(this)
        // Publish the served bundle's hash once (issue #134), so RequestService.buildEnvelope can stamp it on
        // every response and the shell can inject it into the page's bootstrap. Immutable within a deployment,
        // so computed at init. Absent when the ':webapp' distribution was not built into this module.
        if (cxt.instanceConfig.get(EP.webAppHash) == null) {
            this::class.java.getResourceAsStream(AUI.bundleResource)?.use { it.readBytes() }?.let {
                cxt.instanceConfig.put(EP.webAppHash, it.crc32Hex())
            }
        }
    }

    /**
     * The image the deployment's default web resources package (issue #1779, [WRES.defaultId]) supplies for [asset],
     * or null when it names none, fills no such slot, or fills it with another kind of image than the file's name
     * says -- the built-in is served then. Looked up as it is asked for, not at init: the packages are read by
     * [ComponentImageService] in its own init, which need not come first, and the lookup is two map reads.
     */
    private fun packaged(cxt: KdrCxt, asset: BrandingAsset): StoredFile? {
        val slot = asset.slot ?: return null
        val packageId = WRES.defaultId(cxt) ?: return null
        return ComponentImageService.getOrNull(cxt)?.packageFile(packageId, slot)?.takeIf { it.meta.mimeType == asset.mimeType }
    }

    /** The hash a package's image is addressed by: the start of its content's own hash, which a changed image changes. */
    private fun packagedHash(file: StoredFile): String = file.meta.hash.take(packagedHashLength)

    /** The built-in classpath resource for a branding [file]. */
    private fun builtInBranding(file: String): String = "/${AUI.defaultResourceDir}/$file"

    /**
     * Serves the webapp shell and its bundle. Only engages with app-focused requests, matching on the
     * context-root-stripped [RequestHandler.appPath] (so the configured app root, e.g. `wa`, is transparent here).
     *
     * **The frontend decides what a path means** (issue #1370, stage 1 of #1125's path routing), so the server holds
     * no list of routes: anything under the app root that is not asset-shaped is answered with the shell, which then
     * routes it -- `/wa/docs/x` as well as `/wa`. Asset-shaped is [isAssetShaped]: a single segment carrying a dot
     * (`webapp.js`, `app.css`, the branding files) or a colon (the `:hash` an asset URL carries). A known asset is
     * served; any other asset-shaped name passes on, to the 404. No route name has either character, so the two
     * cannot collide, and `/wa/:bogus` still 404s rather than answering as the app root (issue #529).
     */
    override fun serve(cxt: KdrCxt, handler: RequestHandler): Boolean {
        if (handler.focus != ContextFocus.app) {
            return false
        }
        // An asset URL carries a `:<hash>` cache-busting suffix (issue #137, mirroring the content servers).
        // Split it off the **last path segment only, and only after a non-empty name**, then carry the hash
        // down so the cache header follows the content-addressed rule (ContentResources.buildId): the permanent
        // header only when the hash matches the asset's own, else no-store (never "no header", which would leave
        // a wrong-hash URL to the browser's heuristic cache). Requiring a name before the colon is what stops a
        // leading-colon path like `/:bogus` collapsing to `"/"` and being served the shell -- a whole-path
        // `substringBefore(':')` did that, answering 200 for any `/wa/:anything` (issue #529).
        val path = handler.appPath
        val colon = path.indexOf(':', startIndex = path.lastIndexOf('/') + 2)
        val bareAppPath = if (colon < 0) path else path.substring(0, colon)
        val suppliedHash = if (colon < 0) "" else path.substring(colon + 1)
        if (!isAssetShaped(path)) {
            // A page is fetched, so only a GET or HEAD is answered with the shell; any other method on a route path
            // passes on, to the 404, as it did before route paths were served -- else every URL under the root would
            // answer a POST with a 200 page.
            if (!handler.method.equals("GET", ignoreCase = true) && !handler.method.equals("HEAD", ignoreCase = true)) {
                return false
            }
            // The shell is not content-addressed: it always revalidates so a reload picks up the current
            // hashed asset URLs after a deploy (never no-store -- it may be stored, just not used stale). Its asset
            // URLs are absolute (`/<appRoot>/...`), so it renders the same at any depth.
            handler.setResponseHeader("Cache-Control", AUI.shellCacheControl)
            val html = AppUiPage.render(
                bootstrapJson(cxt), handler.contextRoot, { versionedName(cxt, it) }, shellMeta(cxt, handler.contextRoot),
            )
            handler.sendStringResponse(html, EXC.ok, AUI.htmlMimeType)
            return true
        }
        return when (bareAppPath) {
            AUI.bundlePath -> serveTextResource(cxt, handler, AUI.bundleResource, AUI.jsMimeType, suppliedHash)
            AUI.bundleMapPath -> serveTextResource(cxt, handler, AUI.bundleMapResource, AUI.jsonMimeType, suppliedHash)
            AUI.stylesheetPath -> serveTextResource(cxt, handler, AUI.stylesheetResource, AUI.cssMimeType, suppliedHash)
            else -> serveBranding(cxt, handler, bareAppPath, suppliedHash)
        }
    }

    /**
     * Whether [appPath] names an asset rather than a route (issue #1370): one segment directly under the app root,
     * carrying a dot or a colon. A route is `/`, a bare name (`/docs`), or deeper (`/docs/x`) -- and a deeper segment
     * may hold either character (an id), which is why only a single segment is judged.
     */
    private fun isAssetShaped(appPath: String): Boolean {
        val segment = appPath.removePrefix("/")
        return segment.isNotEmpty() && '/' !in segment && ('.' in segment || ':' in segment)
    }

    /** The cache header for an asset response: the shared content-addressed decision ([ContentResources.cacheHeaderFor]),
     *  driven by whether the URL's [suppliedHash] matches this resource's own CRC32 hash (issues #137, #529). An
     *  empty (absent) or wrong hash does not match, so it gets [ContentResources.noStore]. */
    private fun assetCacheHeader(suppliedHash: String, resourcePath: String): String =
        ContentResources.cacheHeaderFor(suppliedHash.isNotEmpty() && suppliedHash == assetHash(resourcePath))

    /**
     * The application filename with its content-hash suffix (`webapp.js:1a2b3c`), for the shell to link so the
     * asset is cache-bustable (issue #137). Falls back to the bare name when the resource is absent or unhashed
     * (nothing to bust), so the link is always valid.
     */
    private fun versionedName(cxt: KdrCxt, file: String): String {
        // A branding asset the default package supplies is addressed by that image's own hash (issue #1779).
        val hash = AUI.brandingAssets.firstOrNull { it.file == file }?.let { packaged(cxt, it) }?.let(::packagedHash)
            ?: resourceForAsset(file)?.let { assetHash(it) }
        return if (hash.isNullOrEmpty()) file else "$file:$hash"
    }

    /** The classpath resource behind a linked shell asset (by filename), or null when it is not one we serve. */
    private fun resourceForAsset(file: String): String? = when (file) {
        AUI.bundlePath.removePrefix("/") -> AUI.bundleResource
        AUI.stylesheetPath.removePrefix("/") -> AUI.stylesheetResource
        else -> AUI.brandingAssets.firstOrNull { it.file == file }?.let { builtInBranding(it.file) } // the built-in icons and mark
    }

    /** A memoized CRC32-hex hash of an embedded resource's bytes (issue #137), or null when it is absent.
     *  Immutable within a deployment, so computed once per resource. */
    private fun assetHash(resourcePath: String): String? {
        val cached = assetHashes.getOrPut(resourcePath) {
            this::class.java.getResourceAsStream(resourcePath)?.use { it.readBytes() }?.crc32Hex() ?: ""
        }
        return cached.ifEmpty { null }
    }

    /**
     * Serves the branding asset the request names, or passes on (false) when it names none: the default package's
     * image for it ([packaged], issue #1779), else the built-in, so the two are the same address -- only the bytes
     * differ. A package's image is cached for good under its own hash, as the built-in is under its.
     */
    private fun serveBranding(cxt: KdrCxt, handler: RequestHandler, bareAppPath: String, suppliedHash: String): Boolean {
        val asset = AUI.brandingAssets.firstOrNull { it.appPath == bareAppPath } ?: return false
        packaged(cxt, asset)?.let { file ->
            handler.setResponseHeader("Cache-Control", ContentResources.cacheHeaderFor(suppliedHash == packagedHash(file)))
            if (asset.binary) {
                handler.sendBytesResponse(file.bytes, EXC.ok, asset.mimeType)
            } else {
                handler.sendStringResponse(file.bytes.toString(Charsets.UTF_8), EXC.ok, asset.mimeType)
            }
            return true
        }
        val resource = builtInBranding(asset.file)
        return if (asset.binary) {
            serveBinaryResource(cxt, handler, resource, asset.mimeType, suppliedHash)
        } else {
            serveTextResource(cxt, handler, resource, asset.mimeType, suppliedHash)
        }
    }

    /** Serves an embedded **text** resource (the shell's stylesheet, the bundle, an SVG), decoded as UTF-8. The
     *  cache header follows the content-addressed rule via [assetCacheHeader]: permanent on a matched
     *  [suppliedHash], `no-store` otherwise (issues #137, #529). */
    private fun serveTextResource(
        cxt: KdrCxt,
        handler: RequestHandler,
        resourcePath: String,
        mimeType: String,
        suppliedHash: String,
    ): Boolean {
        val body = readResource(cxt, handler, resourcePath) ?: return false
        handler.setResponseHeader("Cache-Control", assetCacheHeader(suppliedHash, resourcePath))
        handler.sendStringResponse(body.toString(Charsets.UTF_8), EXC.ok, mimeType)
        return true
    }

    /**
     * Serves an embedded **binary** resource (a PNG, the ICO) — the bytes are written straight out, never
     * decoded to a String. Going through [RequestHandler.sendStringResponse] would corrupt them: it is UTF-8
     * only, so every byte that is not valid UTF-8 would come back out as U+FFFD.
     *
     * Caching matches [serveTextResource]: the header is [assetCacheHeader] over the [suppliedHash].
     */
    private fun serveBinaryResource(
        cxt: KdrCxt,
        handler: RequestHandler,
        resourcePath: String,
        mimeType: String,
        suppliedHash: String,
    ): Boolean {
        val body = readResource(cxt, handler, resourcePath) ?: return false
        handler.setResponseHeader("Cache-Control", assetCacheHeader(suppliedHash, resourcePath))
        handler.sendBytesResponse(body, EXC.ok, mimeType)
        return true
    }

    /**
     * Reads an embedded classpath resource's bytes. A missing resource means the `:webapp` distribution was
     * never built into this module's resources; that is logged and the caller passes on (returns false) so the
     * friendly content 404 fires rather than a hard error.
     */
    private fun readResource(cxt: KdrCxt, handler: RequestHandler, resourcePath: String): ByteArray? {
        val bytes = this::class.java.getResourceAsStream(resourcePath)?.use { it.readBytes() }
        if (bytes == null) {
            LogRequest.warn(
                cxt,
                "Webapp resource '$resourcePath' is not on the classpath (build ':webapp' first?); " +
                    "passing on ${handler.logRequestSummary()}.",
            )
        }
        return bytes
    }

    /**
     * The frontend bootstrap config as JSON, for injection into the page: the context roots (by focus), plus
     * the served web-app bundle's hash (issue #134) under [EP.webAppHash] when there is one, so the running tab
     * knows its own hash and can compare it against the hash every response carries to notice a new deployment.
     */
    private fun bootstrapJson(cxt: KdrCxt): String {
        val base = RequestService.get(cxt).frontendConfig()
        val hash = cxt.instanceConfig.get(EP.webAppHash) as? String
        // The brand mark is fetched by the frontend itself (not linked in the shell head), so it needs its
        // content-addressed name here to build a hashed URL and be cached immutably rather than refetched every
        // load (#529). `versionedName` supplies the whole `brand-mark.svg:<hash>`, so the frontend appends no
        // suffix of its own -- the `:` grammar stays one fact, owned by `versionedName`. Absent when the mark is
        // unhashed (no bytes to bust); the frontend then serves it bare, which is exactly the dev-server case.
        val markName = versionedName(cxt, AUI.brandMarkFile)
        val cfg = buildMap {
            putAll(base)
            if (!hash.isNullOrEmpty()) put(EP.webAppHash, hash)
            if (markName != AUI.brandMarkFile) put(EP.brandMarkName, markName)
        }
        return cfg.toJsonStr()
    }

    /**
     * What the shell says of the site where it is linked from elsewhere (issue #1688). The title and description are
     * the `home` file's `site` copy for the caller's client (anonymous, as a preview's crawler is, that is the default
     * client's), so a deployment or a flavor words them as it words the home page, reduced to plain text. The image is
     * the share image at its hashed name.
     *
     * The addresses are absolute only where the deployment says what its public URL is (`KDR_PUBLIC_URL`): a preview
     * needs an absolute image address, and only that setting gives one that no request can choose -- a `Host` header
     * is the caller's to send. Without it the image's address is the app root's path, which some previews resolve,
     * and there is no `og:url`.
     */
    private fun shellMeta(cxt: KdrCxt, appRoot: String): ShellMeta {
        val fragments = MarkdownFragmentService.get(cxt)
        fun copy(key: String): String? =
            fragments.resolveFragment(cxt, HFRAG.home, HFRAG.siteNs, key)?.renderMarkdownText()?.trim()?.ifEmpty { null }
        val publicUrl = INVITE.configuredPublicUrl(cxt)
        val appUrl = "${publicUrl.orEmpty()}/$appRoot"
        return ShellMeta(
            title = copy(HFRAG.title) ?: AUI.defaultSiteTitle,
            description = copy(HFRAG.siteDescription).orEmpty(),
            imageUrl = "$appUrl/${versionedName(cxt, AUI.shareImageFile)}",
            url = publicUrl?.let { appUrl },
        )
    }

    @Suppress("ConstPropertyName")
    companion object {
        const val serviceName = "AppUiService"

        /** How much of a package image's content hash its address carries (issue #1779): enough to tell versions apart. */
        const val packagedHashLength = 12

        fun get(cxt: KdrCxt): AppUiService = cxt.instanceConfig.get(serviceName) as? AppUiService
            ?: throw KdrException("The $serviceName is not available on this node.")
    }
}
