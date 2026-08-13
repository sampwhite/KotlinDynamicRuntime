package com.dynamicruntime.common.content

import com.dynamicruntime.common.context.ENV
import com.dynamicruntime.common.context.KdrCxt
import com.dynamicruntime.common.endpoint.SchModule
import com.dynamicruntime.common.endpoint.schemaModule
import com.dynamicruntime.common.exception.EXC
import com.dynamicruntime.common.exception.KdrException
import com.dynamicruntime.common.logging.LogStartup
import com.dynamicruntime.common.schema.JsonMappable
import com.dynamicruntime.common.schema.SCT
import com.dynamicruntime.common.util.TemplateIssue
import com.dynamicruntime.common.util.checkFragmentSyntax
import com.dynamicruntime.common.util.toOptStr
import com.dynamicruntime.common.http.request.ContentServer
import com.dynamicruntime.common.http.request.ContextFocus
import com.dynamicruntime.common.http.request.RequestHandler
import com.dynamicruntime.common.http.request.RequestService
import com.dynamicruntime.common.startup.ServiceInitializer
import com.dynamicruntime.common.util.parseMarkdownFragments
import java.util.concurrent.ConcurrentHashMap

/**
 * Serves Markdown *fragment* files as a two-tier `namespace -> (key -> value)` JSON map (issue #59), under the
 * static context root (`ContextRoot.st`). A [ContentServer], not a JSON API endpoint: it needs a permanent
 * `Cache-Control` and returns a free-form map, and it deliberately does **not** appear in the `/schema/endpoints`
 * API catalog. See `webapp/CLAUDE.md` for the frontend-facing contract.
 *
 * Request shape: `/<staticRoot>/<appId>/md/<fileId:buildId>`, e.g. `/st/myapp/md/emailForms:9f3ac1`.
 *  - `appId` is an opaque, frontend-constructed id (application + optional client/locale suffixes). It is
 *    **ignored for now**; a future backend may return different content per `appId`.
 *  - `buildId` is a cache-busting suffix (a content hash; see [fragmentBuildId]). It is **stripped and
 *    ignored** for the lookup -- its only job is to make the URL change when the file changes, so the
 *    permanent cache (browser or CDN) refetches. A rebuild with unchanged content keeps the same URL.
 *  - `fileId` names the resource read from `md-fragments/<fileId>.md` on the classpath. If the owning
 *    component/module is not in the deployment, the resource is simply absent and the request 404s.
 */
class MarkdownFragmentService : ServiceInitializer, ContentServer {
    override val serviceName: String = MarkdownFragmentService.serviceName

    /** Registers this content server with the dispatcher (idempotent), then checks the fragment files. */
    override fun checkInit(cxt: KdrCxt) {
        val requestService = RequestService.get(cxt) ?: return
        requestService.checkInit(cxt)
        requestService.addContentServer(this)
        checkFragmentsAtStartup(cxt)
    }

    /**
     * Validates every registered fragment file at boot, and does something different depending on where it is
     * running (see [FRAG.checkEnvVar]): a developer or a test is **stopped**, and a production node **logs and
     * serves**.
     *
     * That asymmetry is the whole point. A broken fragment is a content defect, and refusing to start a
     * production node over one piece of copy takes down every endpoint that had nothing to do with it -- while
     * the render path already contains a fragment failure (`RequestHandler.renderMsg` falls back to the key
     * path and warns). Where the author is still at the keyboard, the opposite is true: silence there is how
     * the defect reaches production in the first place.
     *
     * Deliberately keyed on the environment rather than `isTestInstance`: that flag is inferred from
     * in-memory-ness and the unit environment, so an ordinary local run against a real database is *not* a
     * test instance and would have quietly got production behavior on a developer's own machine.
     */
    fun checkFragmentsAtStartup(cxt: KdrCxt) {
        val mode = fragmentCheckMode(cxt)
        if (mode == FRAG.off) return
        val results = checkFragments(cxt)
        val broken = results.filter { it.issues.isNotEmpty() || !it.found }
        if (broken.isEmpty()) return
        val detail = broken.joinToString("; ") { r ->
            if (!r.found) {
                "'${r.fileId}' is declared but absent"
            } else {
                "'${r.fileId}': " + r.issues.joinToString(", ") { "${it.message} (line ${it.line})" }
            }
        }
        if (mode == FRAG.strict) {
            throw KdrException(
                "Markdown fragment files have problems: $detail. Fix them, or set ${FRAG.checkEnvVar}=" +
                    "${FRAG.warn} to start anyway (which is the default outside ${ENV.prod}).",
            )
        }
        LogStartup.warn(cxt, "Markdown fragment files have problems: $detail")
    }

    /**
     * Parses and syntax-checks every fragment file the components declared. Returns a result per file --
     * including the ones that are clean -- so a caller can see what was actually covered rather than inferring
     * it from an empty problem list.
     */
    fun checkFragments(cxt: KdrCxt, only: String? = null): List<FragmentCheckResult> {
        val declared = registeredFragmentFiles(cxt)
        val fileIds = if (only != null) listOf(only) else declared
        return fileIds.map { fileId ->
            val text = ContentResources.readText(resourceDir, fileId)
            if (text == null) {
                FragmentCheckResult(fileId, found = false, issues = emptyList())
            } else {
                FragmentCheckResult(fileId, found = true, issues = text.parseMarkdownFragments().checkFragmentSyntax())
            }
        }
    }

    @Suppress("DuplicatedCode")
    override fun serve(cxt: KdrCxt, handler: RequestHandler): Boolean {
        if (handler.focus != ContextFocus.static) {
            return false
        }
        // appPath is context-root-stripped: "/<appId>/md/<fileId:buildId>" -> ["", appId, "md", fileWithBuild].
        val segments = handler.appPath.split('/')
        if (segments.size != 4 || segments[2] != mdMarker) {
            return false
        }
        val fileId = segments[3].substringBefore(':') // strip the ":buildId" cache-busting suffix
        if (!ContentResources.isSafeFileId(fileId)) {
            handler.sendStringResponse("Bad fragment file id.", EXC.badInput, "text/plain")
            return true
        }
        val text = ContentResources.readText(resourceDir, fileId)
        if (text == null) {
            handler.sendStringResponse("No fragment file '$fileId'.", EXC.notFound, "text/plain")
            return true
        }
        val fragments = text.parseMarkdownFragments()
        // Immutable, long-lived cache: the versioned URL is the cache key, so a new buildId is a new URL.
        handler.setResponseHeader("Cache-Control", cacheControl)
        handler.sendJsonResponse(fragments, EXC.ok)
        return true
    }

    /** Fragment files parsed once and memoized by fileId. Classpath resources today, fixed at build. */
    private val parsedByFileId = ConcurrentHashMap<String, Map<String, Map<String, String>>>()

    /**
     * The value at `<fileId>.md` → [namespace] → [key], or null when the file or entry is absent. Used
     * server-side (issue #108: rendering error copy) rather than over HTTP, and memoized because it is hit per
     * error -- it must not re-read and reparse the source each time.
     *
     * An **instance** method taking [cxt], deliberately not a static helper: the source is the classpath today,
     * but this is where a future version resolves a fragment through [cxt] -- a database, or an HTTP call to
     * another service, for a per-client or per-version copy. Callers reach it via [get]; the seam is in place
     * so that change stays inside here.
     */
    fun resolveFragment(cxt: KdrCxt, fileId: String, namespace: String, key: String): String? {
        val parsed = parsedByFileId.getOrPut(fileId) {
            ContentResources.readText(resourceDir, fileId)?.parseMarkdownFragments() ?: emptyMap()
        }
        return parsed[namespace]?.get(key)
    }

    @Suppress("ConstPropertyName")
    companion object {
        const val serviceName = "MarkdownFragmentService"

        /** The `md` path segment marking a Markdown-fragment request under the static root. */
        const val mdMarker = CMK.md

        /** Classpath resource directory holding the `<fileId>.md` fragment files. */
        const val resourceDir = "md-fragments"

        /** Permanent, shared cache: safe because the `buildId` in the URL changes whenever content changes. */
        const val cacheControl = "public, max-age=31536000, immutable"

        fun get(cxt: KdrCxt): MarkdownFragmentService? =
            cxt.instanceConfig.get(serviceName) as? MarkdownFragmentService

        /**
         * The cache-busting build id for a fragment file (see [ContentResources.buildId]): a memoized content
         * hash, or null if the resource is absent. Used by the code that hands a component its
         * `fileId:buildId` (the UI-config endpoints); the fragment request itself only strips it.
         */
        fun fragmentBuildId(fileId: String): String? = ContentResources.buildId(resourceDir, fileId)

        /** Schema type name for a per-file check result. */
        const val checkTypeName = "FragmentCheck"

        /** The fragment files every loaded component declared, collected at boot by `InstanceRegistry`. */
        fun registeredFragmentFiles(cxt: KdrCxt): List<String> =
            (cxt.instanceConfig.get(FRAG.registryKey) as? List<*>)?.mapNotNull { it as? String } ?: emptyList()

        /**
         * What a fragment problem does at startup. An explicit [FRAG.checkEnvVar] decides it; otherwise it is
         * [FRAG.strict] everywhere except [ENV.prod], where it is [FRAG.warn].
         */
        fun fragmentCheckMode(cxt: KdrCxt): String {
            val explicit = cxt.getEnvVar(FRAG.checkEnvVar)?.trim()?.lowercase()
            if (explicit == FRAG.strict || explicit == FRAG.warn || explicit == FRAG.off) return explicit
            return if (cxt.instanceConfig.env == ENV.prod) FRAG.warn else FRAG.strict
        }

        /**
         * The operator-section endpoint for checking fragments on a **running** instance -- which is how a
         * production node, whose boot only warned, gets told what is wrong without a restart. `fileId` narrows
         * it to one file; omitted, it checks everything the components declared.
         *
         * Under `operator` rather than open: a fragment issue names files, line numbers and copy internals.
         */
        fun schema(cxt: KdrCxt): SchModule = schemaModule(cxt, "fragmentCheck") {
            type(checkTypeName) {
                type = SCT.kObject
                property(FCHK.fileId, "The fragment file checked.", required = true)
                property(FCHK.found, "Whether the declared file was actually present.", required = true) {
                    type = SCT.boolean
                }
                property(FCHK.issueCount, "How many problems were found in it.", required = true) {
                    type = SCT.integer
                }
                property(FCHK.issues, "The problems, each with its position in the file.", required = true) {
                    type = SCT.array
                    items { type = SCT.kObject }
                }
            }
            listEndpoint(
                "/operator/fragments/check",
                "Syntax-checks this instance's Markdown fragment files, reporting problems with positions.",
                outputRef = checkTypeName,
                inputFields = {
                    field(FCHK.fileId, "A single fragment file to check; omit to check every declared file.")
                },
            ) { c, request ->
                val service = get(c) ?: throw KdrException("MarkdownFragmentService is not available.")
                service.checkFragments(c, request[FCHK.fileId].toOptStr()?.trim()?.ifEmpty { null })
                    .map { it.toJsonMap() }
            }
        }
    }
}

/**
 * What checking one fragment file found. [found] is separate from an empty [issues] list on purpose: a file
 * that is declared but missing is a different failure from one that is present and clean, and both would
 * otherwise report zero problems.
 */
class FragmentCheckResult(
    val fileId: String,
    val found: Boolean,
    val issues: List<TemplateIssue>,
) : JsonMappable {
    override fun toJsonMap(): Map<String, Any?> = mapOf(
        FCHK.fileId to fileId,
        FCHK.found to found,
        FCHK.issueCount to issues.size,
        FCHK.issues to issues.map { it.toJsonMap() },
    )
}
