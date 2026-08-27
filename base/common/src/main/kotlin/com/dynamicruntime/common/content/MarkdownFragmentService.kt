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
import java.util.concurrent.ConcurrentHashMap

/**
 * Serves Markdown *fragment* files as a two-tier `namespace -> (key -> value)` JSON map (issue #59), under the
 * static context root (`ContextRoot.st`). A [ContentServer], not a JSON API endpoint: it needs a permanent
 * `Cache-Control` and returns a free-form map, and it deliberately does **not** appear in the `/schema/endpoints`
 * API catalog. See `webapp/CLAUDE.md` for the frontend-facing contract.
 *
 * Request shape: `/<staticRoot>/<appId>/md/<fileId:buildId>`, e.g. `/st/myapp/md/emailForms:9f3ac1`.
 *  - `appId` stays **opaque and unread**. It is frontend-constructed, so whose content a caller receives must
 *    never be decided by it; the client comes from the request context. See [effectiveFragments].
 *  - `buildId` is a content hash of the **merged** content ([fragmentContentBuildId]), and since issue #456 it
 *    *selects* what is served rather than being stripped. Its cache-busting job is unchanged -- a new hash is
 *    a new URL, so a permanent cache refetches -- but a URL now names one document rather than "whatever this
 *    file currently is", which is what keeps a shared cache sound when two clients read different copy.
 *  - `fileId` names a declared fragment file. Its content is every layer that applies added up (issue #456):
 *    the resource `md-fragments/<fileId>.md`, any overlay over it, and any a client contributed.
 *
 * ### Fragment content is not access-controlled, and a client's copy is no exception
 *
 * Worth stating outright, because per-client copy invites the opposite assumption. This response is `public`
 * and cached forever, so anybody holding a URL can fetch it and any shared cache in front of the deployment
 * will serve it to whoever asks -- enforcing at the origin would buy nothing behind a CDN. The URL is the
 * capability, and a build id is a hash of content nobody else has, so it is not *discoverable*; it is not a
 * secret either. **Do not put anything confidential in a fragment**, per client or otherwise. Copy that
 * genuinely must not leak between clients needs a different transport, not a rule added here.
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
        val broken = results.filter { it.issues.isNotEmpty() || !it.found || it.orphans.isNotEmpty() }
        val findings = broken.map { r ->
            val where = r.fileId + (r.client?.let { " (client '$it')" } ?: "")
            when {
                !r.found -> "'$where' is declared but absent"
                r.issues.isNotEmpty() ->
                    "'$where': " + r.issues.joinToString(", ") { "${it.message} (line ${it.line})" }
                // An orphan is a finding rather than a note, and strict mode therefore refuses to boot on one.
                // It is the silent failure this check exists for: the overlay simply stops winning a lookup
                // that no longer happens, nothing throws, and the customer's wording reverts to the default.
                else -> "'$where': overlay keys no base declares: " + r.orphans.joinToString(", ")
            }
        }
        // Recorded before the refusal below, and unconditionally -- including when there is nothing to say.
        // A clean run is a fact the report needs (issue #303): "ran and found nothing" and "never ran" are
        // different states of a node and look identical in a report that lists only problems.
        BootCheckRegistry.get(cxt).record(BCHK.fragments, FRAG.checkEnvVar.name, mode, findings)
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
     * Syntax-checks every declared fragment file and does it **per variant** (issue #456): once for the
     * content everybody shares, and once more for each client that overlays the file.
     *
     * Per variant rather than per file, because a client's overlay is copy like any other and would otherwise
     * be the only copy on the node that nothing ever checked. It also checks what is actually *served* -- the
     * merged map -- rather than the base resource, so a broken value that an overlay replaces is correctly not
     * reported for the client whose overlay replaced it.
     *
     * A result per variant including the clean ones, so a caller can see what was covered rather than
     * inferring it from an empty problem list.
     */
    fun checkFragments(
        cxt: KdrCxt,
        only: String? = null,
        data: Map<String, Any?>? = null,
    ): List<FragmentCheckResult> {
        val sources = registeredFragmentSources(cxt)
        val fileIds = if (only != null) listOf(only) else sources.map { it.fileId }.distinct()
        return fileIds.flatMap { fileId ->
            val forFile = sources.filter { it.fileId == fileId }
            // A base layer belongs to no client, so a file whose base is absent, is absent for everybody. One
            // row rather than one per client: they would each report the same missing resource, and a boot
            // refusal listing it three times reads as three broken files.
            val clients = if (!mergeFragmentLayers(fileId, forFile, null).found) {
                listOf<String?>(null)
            } else {
                listOf<String?>(null) + forFile.mapNotNull { it.client }.distinct()
            }
            clients.map { client ->
                val merged = mergeFragmentLayers(fileId, forFile, client)
                val entries = merged.content.fragmentPaths().map { e ->
                    FragmentEntryReport(e.entry, e.paths, data?.let { e.paths.missingFrom(it) } ?: emptyList())
                }
                FragmentCheckResult(
                    fileId, client, merged.found,
                    issues = if (merged.found) merged.content.checkFragmentSyntax() else emptyList(),
                    entries = if (merged.found) entries else emptyList(),
                    orphans = merged.orphans,
                )
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
        val fileId = segments[3].substringBefore(':')
        val requestedBuildId = segments[3].substringAfter(':', "")
        if (!ContentResources.isSafeFileId(fileId)) {
            handler.sendStringResponse("Bad fragment file id.", EXC.badInput, "text/plain")
            return true
        }
        // The build id now **selects** the content rather than being stripped and ignored (issue #456).
        //
        // It had to. Content varies by client once a client can overlay it, and the response is cached
        // `public` and `immutable` with the URL as the whole key -- so a caller asking for another client's
        // URL and being answered with their own content would put that answer in a shared cache under that
        // client's URL, and the client would then be served it. Answering by build id makes the URL mean one
        // document again, which is the property the permanent cache was always resting on.
        val versioned = requestedBuildId.isNotEmpty()
        val effective = if (versioned) {
            fragmentsWithBuildId(cxt, fileId, requestedBuildId)
        } else {
            effectiveFragments(cxt, fileId)
        }
        if (effective == null || !effective.found) {
            // Explicitly uncached: a stale URL from a redeploy lands here, and a cached 404 would keep
            // answering for a file that exists. The frontend's next UI-config call hands it a current ref.
            handler.setResponseHeader("Cache-Control", noStore)
            handler.sendStringResponse("No fragment file '$fileId'.", EXC.notFound, "text/plain")
            return true
        }
        // **Only a versioned URL is cacheable**, and this is the whole of what makes the permanent header
        // safe. A bare `/st/<appId>/md/<fileId>` names no particular document -- it is answered with whatever
        // *this caller* reads -- so storing that answer under that URL is precisely the poisoning the build id
        // exists to prevent: the first requester's copy would be served to every client behind the cache.
        //
        // Answered rather than refused, because it is reachable without malice: a hand-driven `curl`, and a
        // frontend whose UI-config gave it no build id (`fetchUiConfig` defaults a missing one to empty). Both
        // want the content; neither may leave it in a shared cache.
        handler.setResponseHeader("Cache-Control", if (versioned) cacheControl else noStore)
        handler.sendJsonResponse(effective.content, EXC.ok)
        return true
    }

    /**
     * Merged content per `fileId|client`, computed once (issue #456). Layers are fixed at boot, so the merge
     * is too -- what varies is only which client it was done for.
     */
    private val effectiveCache = ConcurrentHashMap<String, EffectiveFragments>()

    /**
     * Merged content by `fileId|buildId`, which is how a request for a versioned URL is answered.
     *
     * Populated by computing every variant a file has -- the shared one, and one per client that overlays it.
     * That set is small (a file most clients do not touch has exactly one variant) and known at boot, so the
     * index is complete after one pass rather than filling in as callers arrive.
     */
    private val byBuildId = ConcurrentHashMap<String, EffectiveFragments>()

    /**
     * The content [cxt]'s client sees for [fileId], or null when nothing declares that file.
     *
     * The client is taken from the context rather than from the URL's `appId`, which stays opaque: `appId` is
     * frontend-constructed, so honoring it would let a caller choose whose copy they are served. Note the
     * consequence while `ClientDef.domainPrefix` and `customDomain` have no implementation yet -- an
     * anonymous visitor has no client, so a client's overlays reach its **signed-in** people and nobody else.
     * The mechanism is right; the missing half is domain-to-client routing, not this.
     */
    fun effectiveFragments(cxt: KdrCxt, fileId: String): EffectiveFragments? {
        val sources = registeredFragmentSources(cxt).filter { it.fileId == fileId }
        if (sources.isEmpty()) {
            return null
        }
        // A client with no overlay of its own merges to the shared content, so it is not given a variant --
        // which is what keeps one cache entry serving everybody who is not being treated differently.
        val client = cxt.client.takeIf { c -> sources.any { it.client == c } }
        return effectiveCache.getOrPut("$fileId|${client ?: ""}") {
            mergeFragmentLayers(fileId, sources, client).also { byBuildId["$fileId|${it.buildId}"] = it }
        }
    }

    /** The content of [fileId] that [buildId] names, or null when this node has no such version of it. */
    fun fragmentsWithBuildId(cxt: KdrCxt, fileId: String, buildId: String): EffectiveFragments? {
        val key = "$fileId|$buildId"
        byBuildId[key]?.let { return it }
        // Not seen yet: compute every variant of this file, which is what makes a miss here mean "no such
        // version" rather than "nobody has asked for that client yet".
        val sources = registeredFragmentSources(cxt).filter { it.fileId == fileId }
        if (sources.isEmpty()) {
            return null
        }
        for (client in listOf(null) + sources.mapNotNull { it.client }.distinct()) {
            val merged = effectiveCache.getOrPut("$fileId|${client ?: ""}") { mergeFragmentLayers(fileId, sources, client) }
            byBuildId["$fileId|${merged.buildId}"] = merged
        }
        return byBuildId[key]
    }

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
    fun resolveFragment(cxt: KdrCxt, fileId: String, namespace: String, key: String): String? =
        effectiveFragments(cxt, fileId)?.content?.get(namespace)?.get(key)

    @Suppress("ConstPropertyName")
    companion object {
        const val serviceName = "MarkdownFragmentService"

        /** The `md` path segment marking a Markdown-fragment request under the static root. */
        const val mdMarker = CMK.md

        /** Classpath resource directory holding the `<fileId>.md` fragment files. */
        const val resourceDir = "md-fragments"

        /** Permanent, shared cache: safe because the `buildId` in the URL changes whenever content changes. */
        const val cacheControl = "public, max-age=31536000, immutable"

        /** For an answer no shared cache may keep: a URL that does not name one document, and a 404. */
        const val noStore = "no-store"

        fun get(cxt: KdrCxt): MarkdownFragmentService =
            cxt.instanceConfig.get(serviceName) as? MarkdownFragmentService
                ?: throw KdrException("The $serviceName is not available on this node.")

        /**
         * The cache-busting build id of [fileId] as **this caller** will be served it, or null when nothing
         * declares the file or its resource is absent (issue #456).
         *
         * Per caller, because the id names merged content and a client's overlays change it. Handing every
         * caller the same id would be the whole bug: two clients would share one URL for two documents, and
         * the permanent cache on that response would settle which of them everybody got.
         */
        fun fragmentBuildId(cxt: KdrCxt, fileId: String): String? =
            get(cxt).effectiveFragments(cxt, fileId)?.takeIf { it.found }?.buildId

        /** Schema type name for a per-file check result. */
        const val checkTypeName = "FragmentCheck"

        /** Schema type name for one entry's data requirements within a check result. */
        const val entryTypeName = "FragmentEntryPaths"

        /**
         * Every fragment layer this node carries, collected at boot by `InstanceRegistry` (issue #456) -- the
         * components' files and overlays, and the overlays of every client config that was accepted.
         */
        fun registeredFragmentSources(cxt: KdrCxt): List<FragmentSource> =
            (cxt.instanceConfig.get(FRAG.registryKey) as? List<*>)?.filterIsInstance<FragmentSource>() ?: emptyList()


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
                property(FCHK.client, "The client this variant is for; absent for the content everybody shares.")
                property(
                    FCHK.orphans,
                    "Overlay keys, as 'namespace.key', that no base layer declares -- usually a base key that " +
                        "was renamed, which fails silently: the overlay stops winning and the default copy is " +
                        "served in its place.",
                    required = true,
                ) {
                    type = SCT.array
                    items { type = SCT.string }
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
    /** The client this variant is for, or null for the content everybody else gets (issue #456). */
    val client: String?,
    val found: Boolean,
    val issues: List<TemplateIssue>,
    val entries: List<FragmentEntryReport>,
    /** Overlay keys no base declares -- see `orphanedOverlayKeys`, and why silence is the failure mode. */
    val orphans: List<String>,
) : JsonMappable {
    override fun toJsonMap(): Map<String, Any?> {
        // An explicit map rather than `buildMap`: inside that block the receiver is a MutableMap, whose own
        // `entries` shadows this class's -- so `entries.map { ... }` silently means the wrong thing.
        val out = LinkedHashMap<String, Any?>()
        out[FCHK.fileId] = fileId
        // Omitted rather than null for the shared variant: "this row is about a client" is the unusual case,
        // and a column of nulls is how a reader stops noticing the rows that do have one.
        if (client != null) out[FCHK.client] = client
        out[FCHK.found] = found
        out[FCHK.issueCount] = issues.size
        out[FCHK.issues] = issues.map { it.toJsonMap() }
        out[FCHK.entries] = entries.map { it.toJsonMap() }
        out[FCHK.orphans] = orphans
        return out
    }
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
