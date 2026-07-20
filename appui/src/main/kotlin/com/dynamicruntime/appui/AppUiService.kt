package com.dynamicruntime.appui

import com.dynamicruntime.common.context.KdrCxt
import com.dynamicruntime.common.exception.EXC
import com.dynamicruntime.common.http.request.ContentServer
import com.dynamicruntime.common.http.request.ContextFocus
import com.dynamicruntime.common.http.request.LogRequest
import com.dynamicruntime.common.http.request.RequestHandler
import com.dynamicruntime.common.http.request.RequestService
import com.dynamicruntime.common.logging.LogStartup
import com.dynamicruntime.common.startup.ServiceInitializer
import com.dynamicruntime.common.endpoint.EP
import com.dynamicruntime.common.util.crc32Hex
import com.dynamicruntime.common.util.toJsonStr

/**
 * Instance-config keys for the webapp host — *deployment* inputs, as opposed to [AUI], which holds the app's
 * own fixed paths and types. Defaults live at the read sites in [AppUiService].
 */
@Suppress("ConstPropertyName")
object AUIC {
    /**
     * The classpath directory the app's **branding** assets ([AUI.brandingAssets]) are read from, letting a
     * deployment ship its own artwork instead of forking `:webapp`. Absent (the default) means the built-in
     * set. A deployment sets this from its `:customConfig` project and puts the files on the runtime classpath
     * — the same project, being on that classpath already, is the natural place to carry them (see
     * `examples/custom-config.md`; note its example build script disables resources, which such a deployment
     * would re-enable).
     *
     * Resolution is **per asset**: a file the configured directory does not supply falls back to the built-in
     * one, so a deployment can override just its logo and inherit the rest. This swaps the *bytes* only — the
     * URLs are fixed, so both shells' `<link>` tags stay identical and static.
     */
    const val appUiBrandingDir: String = "appUiBrandingDir"
}

/**
 * Webapp host paths, resource locations, and MIME types. Kept short and referenced qualified, per the code
 * guide. The [defaultResourceDir] is the contract with `appui/build.gradle.kts`, which copies the webapp's
 * production distribution onto the classpath under that directory; the two must agree.
 */
@Suppress("ConstPropertyName")
object AUI {
    /** Classpath directory the build embeds the webapp's distribution at (see `appui/build.gradle.kts`). Also,
     *  the fallback for a branding asset a deployment's [AUIC.appUiBrandingDir] does not supply. */
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

    // --- Branding assets. A deployment may replace the bytes behind these (AUIC.appUiBrandingDir); the URLs
    // are fixed, so the shells' <link> tags never change. Each file name doubles as its application path
    // ("/" + file), which is why one constant serves both.

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
     * The legacy ICO tab icon. Served for completeness, but note browsers request `/favicon.ico` by convention
     * **at the origin root**, which is not this app's root — the runtime 404s an unknown context root — so
     * nothing asks for it here unless a deployment fronts the app at the origin root. The SVG and PNG links
     * are what actually cover browsers; see the webapp's resources README.
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

    /**
     * Cache forever, for an asset requested at a content-hashed URL (`webapp.js:<hash>`): the hash *is* the
     * cache key, so a change is a new URL and busts the cache automatically (issue #137). Matches the content
     * services' header. Bare (unhashed) URLs get no such header, so they revalidate.
     */
    const val immutableCacheControl = "public, max-age=31536000, immutable"

    /** The HTML shell is never cached: it must always be re-fetched so the browser sees the current hashed
     *  asset URLs after a deployment (issue #137). `no-cache` = revalidate before use. */
    const val shellCacheControl = "no-cache"

    /**
     * The brandable set: every asset a deployment may override, with how to serve it. A table rather than a
     * branch per file, because these are handled uniformly — resolve the override, then send — and a
     * deployment needs to be *told* which files it may supply, which this list is.
     */
    val brandingAssets = listOf(
        BrandingAsset(faviconFile, svgMimeType, binary = false),
        BrandingAsset(brandMarkFile, svgMimeType, binary = false),
        BrandingAsset(faviconPngFile, pngMimeType, binary = true),
        BrandingAsset(appleTouchIconFile, pngMimeType, binary = true),
        BrandingAsset(faviconIcoFile, icoMimeType, binary = true),
    )
}

/**
 * One brandable asset: the file a deployment may supply, and how it goes out. [binary] is not cosmetic —
 * [RequestHandler.sendStringResponse] is UTF-8 only and would corrupt an image, so a raster has to take
 * [RequestHandler.sendBytesResponse]. It is stated per asset rather than guessed from [mimeType], since SVG is
 * an image that *is* text.
 */
class BrandingAsset(val file: String, val mimeType: String, val binary: Boolean) {
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

    /**
     * Branding file name -> the classpath resource that serves it: the deployment's own where it supplied that
     * file, else the built-in. Resolved once at init — classpath resources are immutable within a running
     * deployment, so this asks the classloader at startup rather than on every request.
     */
    var brandingResources: Map<String, String> = emptyMap()

    /** Memoized content hashes of the served shell resources (issue #137), keyed by classpath resource path. */
    private val assetHashes = java.util.concurrent.ConcurrentHashMap<String, String>()

    /** Registers this content server with the dispatcher and resolves the branding set (idempotent). */
    override fun checkInit(cxt: KdrCxt) {
        val requestService = RequestService.get(cxt) ?: return
        requestService.checkInit(cxt)
        requestService.addContentServer(this)
        // Resolution is never empty once done (it always yields an entry per asset), so this runs once.
        if (brandingResources.isEmpty()) {
            brandingResources = resolveBranding(cxt)
        }
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
     * Binds each brandable asset to the resource that will serve it. A deployment names a classpath directory
     * ([AUIC.appUiBrandingDir]) and ships its artwork there — typically from its `:customConfig` project, which
     * is already on the runtime classpath. Resolution is per asset, so a deployment can override just its logo
     * and inherit the rest.
     */
    private fun resolveBranding(cxt: KdrCxt): Map<String, String> {
        val dir = (cxt.instanceConfig.get(AUIC.appUiBrandingDir) as? String)?.trim('/')?.takeIf { it.isNotEmpty() }
        val resolved = AUI.brandingAssets.associate { it.file to brandingResource(dir, it.file) }
        if (dir != null) {
            // A configured directory that overrides nothing is almost always a mistake (a typo, or resources
            // not on the classpath), and it would otherwise be invisible: the app just serves its built-in
            // artwork and looks fine. Say so at startup rather than let someone hunt it.
            val overridden = resolved.filterValues { it.startsWith("/$dir/") }.keys
            if (overridden.isEmpty()) {
                LogStartup.warn(
                    cxt,
                    "Branding directory '$dir' (${AUIC.appUiBrandingDir}) supplies none of the branding " +
                        "assets, so the built-in set is being served. Is it on the runtime classpath? " +
                        "Expected any of: ${AUI.brandingAssets.joinToString { it.file }}.",
                )
            } else {
                LogStartup.info(
                    cxt,
                    "Branding directory '$dir' overrides ${overridden.joinToString()}; " +
                        "any other branding asset falls back to the built-in one.",
                )
            }
        }
        return resolved
    }

    /** The resource for one branding [file]: the deployment's when [dir] supplies it, else the built-in. */
    private fun brandingResource(dir: String?, file: String): String {
        if (dir != null) {
            val override = "/$dir/$file"
            if (this::class.java.getResource(override) != null) {
                return override
            }
        }
        return "/${AUI.defaultResourceDir}/$file"
    }

    /**
     * Serves the webapp shell and its bundle; passes on anything it does not own. Only engages with
     * app-focused requests, matching on the context-root-stripped [RequestHandler.appPath] (so the configured
     * app root, e.g. `wa`, is transparent here).
     */
    override fun serve(cxt: KdrCxt, handler: RequestHandler): Boolean {
        if (handler.focus != ContextFocus.app) {
            return false
        }
        // An asset URL carries a `:<hash>` cache-busting suffix (issue #137, mirroring the content services);
        // strip it to resolve the resource, and remember whether one was present -- a hashed request is served
        // immutably (the hash is the cache key), a bare one revalidates.
        val bareAppPath = handler.appPath.substringBefore(':')
        val versioned = bareAppPath.length != handler.appPath.length
        return when (bareAppPath) {
            "/" -> {
                // The shell is never cached, so a reload always fetches the current hashed asset URLs.
                handler.setResponseHeader("Cache-Control", AUI.shellCacheControl)
                val html = AppUiPage.render(bootstrapJson(cxt), handler.contextRoot, ::versionedName)
                handler.sendStringResponse(html, EXC.ok, AUI.htmlMimeType)
                true
            }
            AUI.bundlePath -> serveTextResource(cxt, handler, AUI.bundleResource, AUI.jsMimeType, versioned)
            AUI.bundleMapPath -> serveTextResource(cxt, handler, AUI.bundleMapResource, AUI.jsonMimeType, versioned)
            AUI.stylesheetPath -> serveTextResource(cxt, handler, AUI.stylesheetResource, AUI.cssMimeType, versioned)
            else -> serveBranding(cxt, handler, bareAppPath, versioned)
        }
    }

    /**
     * The application filename with its content-hash suffix (`webapp.js:1a2b3c`), for the shell to link so the
     * asset is cache-bustable (issue #137). Falls back to the bare name when the resource is absent or unhashed
     * (nothing to bust), so the link is always valid.
     */
    private fun versionedName(file: String): String {
        val hash = resourceForAsset(file)?.let { assetHash(it) }
        return if (hash.isNullOrEmpty()) file else "$file:$hash"
    }

    /** The classpath resource behind a linked shell asset (by filename), or null when it is not one we serve. */
    private fun resourceForAsset(file: String): String? = when (file) {
        AUI.bundlePath.removePrefix("/") -> AUI.bundleResource
        AUI.stylesheetPath.removePrefix("/") -> AUI.stylesheetResource
        else -> brandingResources[file] // the icons / brand mark (their resolved override or built-in)
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
     * Serves the branding asset the request names, or passes on (false) when it names none. Which resource
     * that is was settled at init by [resolveBranding], so a deployment's override and the built-in are the
     * same code path here — only the bytes differ.
     */
    private fun serveBranding(cxt: KdrCxt, handler: RequestHandler, bareAppPath: String, versioned: Boolean): Boolean {
        val asset = AUI.brandingAssets.firstOrNull { it.appPath == bareAppPath } ?: return false
        val resource = brandingResources[asset.file] ?: return false
        return if (asset.binary) {
            serveBinaryResource(cxt, handler, resource, asset.mimeType, versioned)
        } else {
            serveTextResource(cxt, handler, resource, asset.mimeType, versioned)
        }
    }

    /** Serves an embedded **text** resource (the shell's stylesheet, the bundle, an SVG), decoded as UTF-8. When
     *  [versioned] (requested at a content-hashed URL), it is cached immutably (issue #137). */
    private fun serveTextResource(
        cxt: KdrCxt,
        handler: RequestHandler,
        resourcePath: String,
        mimeType: String,
        versioned: Boolean,
    ): Boolean {
        val body = readResource(cxt, handler, resourcePath) ?: return false
        if (versioned) handler.setResponseHeader("Cache-Control", AUI.immutableCacheControl)
        handler.sendStringResponse(body.toString(Charsets.UTF_8), EXC.ok, mimeType)
        return true
    }

    /**
     * Serves an embedded **binary** resource (a PNG, the ICO) — the bytes are written straight out, never
     * decoded to a String. Going through [RequestHandler.sendStringResponse] would corrupt them: it is UTF-8
     * only, so every byte that is not valid UTF-8 would come back out as U+FFFD.
     */
    private fun serveBinaryResource(
        cxt: KdrCxt,
        handler: RequestHandler,
        resourcePath: String,
        mimeType: String,
        versioned: Boolean,
    ): Boolean {
        val body = readResource(cxt, handler, resourcePath) ?: return false
        if (versioned) handler.setResponseHeader("Cache-Control", AUI.immutableCacheControl)
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
                    "passing on ${handler.logRequestUri}.",
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
        val base = RequestService.get(cxt)?.frontendConfig() ?: emptyMap()
        val hash = cxt.instanceConfig.get(EP.webAppHash) as? String
        val cfg = if (hash.isNullOrEmpty()) base else base + (EP.webAppHash to hash)
        return cfg.toJsonStr()
    }

    @Suppress("ConstPropertyName")
    companion object {
        const val serviceName = "AppUiService"

        fun get(cxt: KdrCxt): AppUiService? = cxt.instanceConfig.get(serviceName) as? AppUiService
    }
}
