package com.dynamicruntime.common.content

import com.dynamicruntime.common.context.ENV
import com.dynamicruntime.common.context.KdrCxt
import com.dynamicruntime.common.startup.BCHK
import com.dynamicruntime.common.startup.BootCheckMode
import com.dynamicruntime.common.startup.BootCheckRegistry
import com.dynamicruntime.common.startup.bootCheckMode
import com.dynamicruntime.common.startup.modeOverride
import com.dynamicruntime.common.endpoint.SchModule
import com.dynamicruntime.common.endpoint.schemaModule
import com.dynamicruntime.common.exception.EXC
import com.dynamicruntime.common.exception.KdrException
import com.dynamicruntime.common.logging.LogStartup
import com.dynamicruntime.common.schema.JsonMappable
import com.dynamicruntime.common.schema.SCT
import com.dynamicruntime.common.util.TemplateIssue
import com.dynamicruntime.common.util.TemplatePaths
import com.dynamicruntime.common.util.checkFragmentSyntax
import com.dynamicruntime.common.util.fragmentPaths
import com.dynamicruntime.common.util.missingFrom
import com.dynamicruntime.common.util.jsonMap
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
        val requestService = RequestService.get(cxt)
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
        if (mode == BootCheckMode.off) return
        val results = checkFragments(cxt)
        val broken = results.filter { it.issues.isNotEmpty() || !it.found }
        val findings = broken.map { r ->
            if (!r.found) {
                "'${r.fileId}' is declared but absent"
            } else {
                "'${r.fileId}': " + r.issues.joinToString(", ") { "${it.message} (line ${it.line})" }
            }
        }
        // Recorded before the refusal below, and unconditionally -- including when there is nothing to say.
        // A clean run is a fact the report needs (issue #303): "ran and found nothing" and "never ran" are
        // different states of a node and look identical in a report that lists only problems.
        BootCheckRegistry.get(cxt).record(BCHK.fragments, FRAG.checkEnvVar, mode, findings)
        if (findings.isEmpty()) return
        val detail = findings.joinToString("; ")
        if (mode == BootCheckMode.strict) {
            throw KdrException(
                "Markdown fragment files have problems: $detail. Fix them, or set ${FRAG.checkEnvVar}=" +
                    "${BootCheckMode.warn} to start anyway (which is the default outside ${ENV.prod}).",
            )
        }
        LogStartup.warn(cxt, "Markdown fragment files have problems: $detail")
    }

    /**
     * Parses and syntax-checks every fragment file the components declared. Returns a result per file --
     * including the ones that are clean -- so a caller can see what was actually covered rather than inferring
     * it from an empty problem list.
     */
    fun checkFragments(
        cxt: KdrCxt,
        only: String? = null,
        data: Map<String, Any?>? = null,
    ): List<FragmentCheckResult> {
        val declared = registeredFragmentFiles(cxt)
        val fileIds = if (only != null) listOf(only) else declared
        return fileIds.map { fileId ->
            val text = ContentResources.readText(resourceDir, fileId)
            if (text == null) {
                FragmentCheckResult(fileId, found = false, issues = emptyList(), entries = emptyList())
            } else {
                val parsed = text.parseMarkdownFragments()
                val entries = parsed.fragmentPaths().map { e ->
                    FragmentEntryReport(e.entry, e.paths, data?.let { e.paths.missingFrom(it) } ?: emptyList())
                }
                FragmentCheckResult(fileId, found = true, issues = parsed.checkFragmentSyntax(), entries = entries)
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
    fun resolveFragment(@Suppress("unused") cxt: KdrCxt, fileId: String, namespace: String,
                        key: String): String? {
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

        fun get(cxt: KdrCxt): MarkdownFragmentService =
            cxt.instanceConfig.get(serviceName) as? MarkdownFragmentService
                ?: throw KdrException("The $serviceName is not available on this node.")

        /**
         * The cache-busting build id for a fragment file (see [ContentResources.buildId]): a memoized content
         * hash, or null if the resource is absent. Used by the code that hands a component its
         * `fileId:buildId` (the UI-config endpoints); the fragment request itself only strips it.
         */
        fun fragmentBuildId(fileId: String): String? = ContentResources.buildId(resourceDir, fileId)

        /** Schema type name for a per-file check result. */
        const val checkTypeName = "FragmentCheck"

        /** Schema type name for one entry's data requirements within a check result. */
        const val entryTypeName = "FragmentEntryPaths"

        /** The fragment files every loaded component declared, collected at boot by `InstanceRegistry`. */
        fun registeredFragmentFiles(cxt: KdrCxt): List<String> =
            (cxt.instanceConfig.get(FRAG.registryKey) as? List<*>)?.filterIsInstance<String>() ?: emptyList()

        /**
         * What a fragment problem does at startup. An explicit [FRAG.checkEnvVar] decides it; otherwise it is
         * [BootCheckMode.strict] everywhere except [ENV.prod], where it is [BootCheckMode.warn].
         *
         * A broken fragment is the archetypal defect *on the side* -- one piece of copy, with the render path
         * already containing the failure -- which is why production degrades rather than refusing. See
         * [bootCheckMode] for the shared half of this and `SqlSchemaDrift` for a check that answers the
         * production question the other way.
         */
        fun fragmentCheckMode(cxt: KdrCxt): BootCheckMode =
            bootCheckMode(cxt, modeOverride(cxt, FRAG.checkEnvVar), prodMode = BootCheckMode.warn)

        /**
         * The operator-section endpoint for checking fragments on a **running** instance -- which is how a
         * production node, whose boot only warned, gets told what is wrong without a restart. `fileId` narrows
         * it to one file; omitted, it checks everything the components declared.
         *
         * Under `operator` rather than open: a fragment issue names files, line numbers, and copy internals.
         */
        fun schema(cxt: KdrCxt): SchModule = schemaModule(cxt, "fragmentCheck") {
            // Described rather than a bare object: `required` and `optional` are a distinction a caller has to
            // understand to read the report, and an untyped map made them look like one undifferentiated list.
            type(entryTypeName) {
                type = SCT.kObject
                property(FCHK.entry, "The entry, as 'namespace.key' within the fragment file.", required = true)
                property(
                    FCHK.required,
                    "Data paths the entry reads where an absent value would fail the render.",
                    required = true,
                ) {
                    type = SCT.array
                    items { type = SCT.string }
                }
                property(
                    FCHK.optional,
                    "Data paths the entry reads only where it already handles absence itself -- behind '?:', " +
                        "as a conditional's test, or in a comparison against null -- so leaving these out is safe.",
                    required = true,
                ) {
                    type = SCT.array
                    items { type = SCT.string }
                }
                property(
                    FCHK.missing,
                    "Required paths the supplied 'data' does not provide. Empty when no data was supplied. " +
                        "This is a presence check only: it does not evaluate, so a value of the wrong type is " +
                        "not reported here.",
                    required = true,
                ) {
                    type = SCT.array
                    items { type = SCT.string }
                }
            }
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
                property(FCHK.entries, "Each entry that reads data, and what it reads.", required = true) {
                    type = SCT.array
                    items { ref(entryTypeName) }
                }
            }
            listEndpoint(
                "/operator/fragments/check",
                "Syntax-checks this instance's Markdown fragment files, reporting problems with positions, and " +
                    "reports the data each entry reads. With 'data', also reports required paths it lacks; that " +
                    "is a presence check and does not detect a value of the wrong type.",
                outputRef = checkTypeName,
                inputFields = {
                    field(FCHK.fileId, "A single fragment file to check; omit to check every declared file.")
                    field(
                        FCHK.data,
                        "A JSON object to check the required paths against, reported per entry as 'missing'. " +
                            "Omit to report requirements without checking them.",
                    )
                },
            ) { c, request ->
                val service = get(c)
                // Taken as a JSON string rather than a nested object: this is a GET, and the shape being
                // checked is by definition free-form -- it is whatever a caller's data map happens to be.
                val data = request[FCHK.data].toOptStr()?.trim()?.ifEmpty { null }?.let { text ->
                    text.jsonMap() ?: throw KdrException.mkInput("The '${FCHK.data}' value is not a JSON object.")
                }
                service.checkFragments(c, request[FCHK.fileId].toOptStr()?.trim()?.ifEmpty { null }, data)
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
    val entries: List<FragmentEntryReport>,
) : JsonMappable {
    override fun toJsonMap(): Map<String, Any?> = mapOf(
        FCHK.fileId to fileId,
        FCHK.found to found,
        FCHK.issueCount to issues.size,
        FCHK.issues to issues.map { it.toJsonMap() },
        FCHK.entries to entries.map { it.toJsonMap() },
    )
}

/**
 * What one fragment entry asks of its data, and -- when a caller supplied a map to check against -- which of
 * those it would not get.
 *
 * `required` and `optional` are reported even with no data to check, because that is the useful half on its
 * own: it says what the Kotlin building this entry's map has to provide, which is a question nobody could
 * answer before without reading the copy.
 */
class FragmentEntryReport(
    val entry: String,
    val paths: TemplatePaths,
    val missing: List<String>,
) : JsonMappable {
    override fun toJsonMap(): Map<String, Any?> = mapOf(
        FCHK.entry to entry,
        FCHK.required to paths.required.sorted(),
        FCHK.optional to paths.optional.sorted(),
        FCHK.missing to missing,
    )
}
