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
import com.dynamicruntime.common.util.ScriptError
import com.dynamicruntime.common.util.TemplateIssue
import com.dynamicruntime.common.util.TemplatePaths
import com.dynamicruntime.common.util.findReferenceCycles
import com.dynamicruntime.common.util.analyzeFragmentFile
import com.dynamicruntime.common.util.analyzeTemplate
import com.dynamicruntime.common.util.FragmentResolver
import com.dynamicruntime.common.util.evalTemplate
import com.dynamicruntime.common.util.resolveFragment
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
        fun where(r: FragmentCheckResult) = r.fileId + (r.client?.let { " (client '$it')" } ?: "")

        // Every finding category a broken result carries, all of them -- a file can be wrong in more than one
        // way, and reporting only the first would hide the rest until the first was fixed.
        val findings = results.flatMap { r ->
            val at = where(r)
            if (!r.found) {
                // Absent: nothing else can have been checked, so the other lists are empty and this stands alone.
                listOf("'$at' is declared but absent")
            } else {
                buildList {
                    // Ahead of the rest: a file that has gone private explains whatever else looks wrong about
                    // it, and its consequence lands somewhere else entirely -- every UI-config naming it now
                    // fails, with nothing at that end saying why.
                    if (r.audienceConflict) {
                        add(
                            "'$at' is declared both frontend and backend by different bases, so the whole file is " +
                                "treated as backend and is no longer delivered to any frontend",
                        )
                    }
                    // Audience violations (issue #514): a frontend file with a backend block, or a backend pull
                    // naming a non-backend file. Findings like the rest -- a strict boot refuses on them.
                    r.audienceIssues.forEach { add("'$at': $it") }
                    if (r.issues.isNotEmpty()) {
                        add("'$at': " + r.issues.joinToString(", ") { "${it.message} (line ${it.line})" })
                    }
                    // An orphan is a finding rather than a note, and strict mode therefore refuses to boot on
                    // one. It is a silent failure: the overlay stops winning a lookup that no longer happens,
                    // nothing throws, and the customer's wording reverts to the default.
                    if (r.orphans.isNotEmpty()) {
                        add("'$at': overlay keys no base declares: " + r.orphans.joinToString(", "))
                    }
                }
            }
        }
        // Notes are surfaced but never refuse a boot (issue #514): a backend file carrying a frontend pull is
        // not wrong, only resting on a human assertion. Logged so the fact is visible, and carried on the
        // operator report for a running node.
        val notes = results.flatMap { r -> r.notes.map { "'${where(r)}': $it" } }
        if (notes.isNotEmpty()) {
            LogStartup.info(cxt, "Markdown fragment notes: " + notes.joinToString("; "))
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
        // Cross-file backend cycles are a whole-registry fact, so they are found once here rather than per
        // file, and attached below to the shared row of each cycle's entry-point file (issue #505). Computed
        // over the full registry even when `only` narrows the report -- a cycle can pass through the named
        // file without starting in it; the narrowed view then shows it only if that file is the entry point.
        val backendCycles = backendReferenceCycles(cxt)
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
            // Whether the content everybody shares is already in conflict. A client row repeats that same fact,
            // and a boot refusal saying it once per client reads as several broken files -- so a client row
            // reports the conflict only when it is that client's *own*, i.e., not already said on the shared row.
            //
            // Scoped to the duplicate rather than to `client == null`, which is what it looks like it should be.
            // Client layers are all overlays today (only `GedraConfigBuilder.fragmentOverlay` sets a client, and
            // it cannot set `isOverlay`), so no client can currently declare a base and no client-only conflict
            // can arise. But `mergeFragmentLayers` does admit a client-scoped base -- it filters by client
            // before splitting bases from overlays -- so the day a client base becomes declarable, suppressing
            // by client would silently swallow exactly the case this check exists for: a file going private for
            // one client, with their UI-config failing and nothing at boot saying why.
            val sharedConflict = mergeFragmentLayers(fileId, forFile, null).audienceConflict
            clients.map { client ->
                val merged = mergeFragmentLayers(fileId, forFile, client)
                val findings = variantFindings(cxt, merged, data)
                // Cross-file cycle findings ride on the shared (client-null) row of their entry-point file --
                // the cycle is a property of the base content, not of any client's overlay.
                val cycleIssues = if (client == null) backendCycles[fileId].orEmpty() else emptyList()
                FragmentCheckResult(
                    fileId, client, merged.found,
                    issues = findings.issues + cycleIssues,
                    entries = findings.entries,
                    orphans = merged.orphans,
                    audience = merged.audience,
                    audienceConflict = merged.audienceConflict && !(client != null && sharedConflict),
                    audienceIssues = findings.audienceIssues,
                    notes = findings.notes,
                )
            }
        }
    }

    /** The per-variant checks, split by audience so a rule only runs where it means something. */
    private class VariantFindings(
        val issues: List<TemplateIssue>,
        val entries: List<FragmentEntryReport>,
        val audienceIssues: List<String>,
        val notes: List<String>,
    )

    /**
     * Runs the checks appropriate to [merged]'s audience over its content (issues #505, #514). An absent file
     * has nothing to check; the two present cases differ enough that they are separate methods rather than one
     * with branches, because almost every line would be behind an `if`.
     */
    private fun variantFindings(cxt: KdrCxt, merged: EffectiveFragments, data: Map<String, Any?>?): VariantFindings {
        if (!merged.found) return VariantFindings(emptyList(), emptyList(), emptyList(), emptyList())
        return if (merged.audience == FragmentAudience.backend) {
            backendVariantFindings(cxt, merged.content)
        } else {
            frontendVariantFindings(merged.content, data)
        }
    }

    /**
     * A frontend file: the ordinary check (issue #505) -- syntax, `@t` reference and cycle issues, and the
     * per-entry data requirements, all from the one `$`-prefix parse -- plus the audience rule that only a
     * frontend file has (issue #514, check 1).
     *
     * **A frontend file must contain no `%{...}` backend block.** It is served with no backend pass, so a
     * `%{...}` reaches the browser as literal text -- and because a `%{@t(...)}` is exactly how an author would
     * *intend* a cross-file pull, this is the one that is written by mistake. Caught per entry with the block
     * count from a `%`-prefix parse, which counts a real block and ignores a lone or doubled `%`.
     */
    private fun frontendVariantFindings(
        content: Map<String, Map<String, String>>,
        data: Map<String, Any?>?,
    ): VariantFindings {
        val analysis = content.analyzeFragmentFile()
        val entries = analysis.entryPaths.map { e ->
            FragmentEntryReport(e.entry, e.paths, data?.let { e.paths.missingFrom(it) } ?: emptyList())
        }
        val audienceIssues = content.flatMap { (ns, keys) ->
            keys.mapNotNull { (key, value) ->
                if (value.analyzeTemplate(backendPassPrefix).blockCount > 0) {
                    "$ns.$key contains a ${backendPassPrefix}{...} backend block, but this is a frontend file: it " +
                        "is served with no backend pass, so the block reaches the browser as literal text. Make it " +
                        "a backend file, or a $frontendPassPrefix{...} frontend block."
                } else {
                    null
                }
            }
        }
        return VariantFindings(analysis.syntaxIssues + analysis.referenceIssues, entries, audienceIssues, emptyList())
    }

    /**
     * A backend file (issue #514): private content pulled by a `%{@t(...)}`, never served. What is checkable
     * here per file, and what is not:
     *
     *  - **Syntax, both passes.** A `%{...}` block is checked with the backend prefix; a `${...}` block it
     *    carries onward is checked with the frontend prefix. Each parse treats the other's blocks as plain text,
     *    so an unterminated block of *either* kind is caught, and the two are disjoint rather than double-counted.
     *  - **Check 3 -- a backend pull names only a backend file.** A three-part `%{@t("fileId.ns.key")}` may pull
     *    only another backend file; naming a *frontend* file would drag that file's `${...}` (authored to resolve
     *    within its own delivery) into the carrier's context. One registry lookup per pull, no traversal.
     *  - **Check 2 -- a carried `${@t(...)}` is a note, not a finding.** A backend file may legitimately carry a
     *    frontend pull for the frontend to finish, but which file that resolves against is the *carrying element*
     *    at request time, which no boot check can see. So it is named for a human rather than failed.
     *
     * What is still *not* here: resolving the three-part pull's own `ns.key` against the registry (the target
     * file might lack it). That is the backend-pass reference validation, and it stays a follow-up -- this
     * checks the pull's *audience*, which is the half that has a silent-misresolution failure mode.
     */
    private fun backendVariantFindings(cxt: KdrCxt, content: Map<String, Map<String, String>>): VariantFindings {
        val issues = mutableListOf<TemplateIssue>()
        val audienceIssues = mutableListOf<String>()
        val notes = mutableListOf<String>()
        for ((ns, keys) in content) {
            for ((key, value) in keys) {
                val id = "$ns.$key"
                val byBackend = value.analyzeTemplate(backendPassPrefix)
                val byFrontend = value.analyzeTemplate(frontendPassPrefix)
                for (issue in byBackend.issues + byFrontend.issues) {
                    issues.add(TemplateIssue(issue.code, "$id: ${issue.message}", issue.offset, issue.line, issue.col))
                }
                // Backend pulls (three-part; a shorter one is malformed and caught as syntax). Two checks:
                // the target's **audience** (check 3 of issue #514 -- a pull must name a backend file), and,
                // when it does, whether the pulled key actually **resolves** there (issue #505).
                for (ref in byBackend.refs.filter { it.key.count { c -> c == '.' } == 2 }) {
                    val target = ref.key.substringBefore('.')
                    val rest = ref.key.substringAfter('.')
                    when (declaredAudience(cxt, target)) {
                        // Names a backend file -- now does the pulled `namespace.key` exist in it? A guarded
                        // pull that resolves to nothing is left to its `?:`, exactly as a frontend one is.
                        FragmentAudience.backend ->
                            if (!ref.tolerant && effectiveFragments(cxt, target)?.content?.resolveFragment(rest) == null) {
                                issues.add(
                                    TemplateIssue(
                                        ScriptError.fragmentNotFound,
                                        "$id: backend pull ${backendPassPrefix}{@t(\"${ref.key}\")} names no fragment " +
                                            "'$rest' in '$target'.",
                                        ref.offset, ref.line, ref.col,
                                    ),
                                )
                            }
                        FragmentAudience.frontend -> audienceIssues.add(
                            "$id pulls ${backendPassPrefix}{@t(\"${ref.key}\")}, but '$target' is a frontend file. A " +
                                "backend pull must name a backend file; pulling a frontend one drags its " +
                                "$frontendPassPrefix{...} into the pulling element's context, where it resolves against " +
                                "the wrong file.",
                        )
                        null -> audienceIssues.add(
                            "$id pulls ${backendPassPrefix}{@t(\"${ref.key}\")}, but '$target' is not a declared " +
                                "fragment file.",
                        )
                    }
                }
                // Check 2: a carried frontend pull, named as a note.
                for (ref in byFrontend.refs) {
                    notes.add(
                        "$id carries a frontend pull ${frontendPassPrefix}{@t(\"${ref.key}\")}, which the frontend " +
                            "resolves at request time against the element carrying this content -- not against this " +
                            "file. That binding is the backend author's to get right; it cannot be checked here.",
                    )
                }
            }
        }
        return VariantFindings(issues, emptyList(), audienceIssues, notes)
    }

    /**
     * The audience [fileId] is declared with, or null when no source on this node declares it (issue #514).
     * Read through [effectiveFragments] so it is exactly what a pull would resolve against, and so the
     * "any backend base wins" rule is applied in one place rather than restated here.
     */
    fun declaredAudience(cxt: KdrCxt, fileId: String): FragmentAudience? =
        effectiveFragments(cxt, fileId)?.audience

    /**
     * Cross-file backend reference cycles (issue #505), keyed by the fileId of each cycle's entry point so
     * [checkFragments] can attach the finding to a real file's row. A cycle among backend files -- `a` pulls
     * `b` pulls `a` -- would recurse without end at render; it is caught here, across the whole registry, which
     * the per-file walk in [backendVariantFindings] cannot see.
     *
     * The graph's nodes are three-part `fileId.namespace.key`, and only a **resolvable** backend edge takes
     * part -- a backend target whose key exists. A dangling pull (missing key) or a wrong-audience one is
     * already its own finding and is not also an edge, so it cannot manufacture or hide a cycle. Guarded (`?:`)
     * pulls that *do* resolve are edges like any other, matching the frontend walk: at render a guarded
     * reference that resolves is still followed.
     */
    private fun backendReferenceCycles(cxt: KdrCxt): Map<String, List<TemplateIssue>> {
        val backendFiles = registeredFragmentSources(cxt).map { it.fileId }.distinct()
            .filter { declaredAudience(cxt, it) == FragmentAudience.backend }
        val edges = LinkedHashMap<String, List<String>>()
        for (fileId in backendFiles) {
            val content = effectiveFragments(cxt, fileId)?.content ?: continue
            for ((ns, keys) in content) {
                for ((key, value) in keys) {
                    val targets = value.analyzeTemplate(backendPassPrefix).refs
                        .filter { it.key.count { c -> c == '.' } == 2 }
                        .filter { ref ->
                            val tf = ref.key.substringBefore('.')
                            declaredAudience(cxt, tf) == FragmentAudience.backend &&
                                effectiveFragments(cxt, tf)?.content?.resolveFragment(ref.key.substringAfter('.')) != null
                        }
                        .map { it.key }
                    if (targets.isNotEmpty()) edges["$fileId.$ns.$key"] = targets
                }
            }
        }
        val byFile = LinkedHashMap<String, MutableList<TemplateIssue>>()
        for (cycle in findReferenceCycles(edges)) {
            val fileId = cycle.first().substringBefore('.')
            byFile.getOrPut(fileId) { mutableListOf() }.add(
                TemplateIssue(
                    ScriptError.fragmentCycle,
                    "backend reference cycle: ${cycle.joinToString(" -> ")}.",
                    0, 1, 1,
                ),
            )
        }
        return byFile
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
        // A backend file is never delivered (issue #514): it exists to be pulled by a `%{@t(...)}`, and its
        // values may be resolved per request, which the permanent cache on this response could not hold. It is
        // refused as though absent -- the same 404, so a caller who names its URL cannot even tell it exists,
        // which is the whole of "private".
        if (effective == null || !effective.found || effective.audience == FragmentAudience.backend) {
            // Explicitly uncached: a stale URL from a redeploy lands here, and a cached 404 would keep
            // answering for a file that exists. The frontend's next UI-config call hands it a current ref.
            handler.setResponseHeader("Cache-Control", ContentResources.noStore)
            handler.sendStringResponse("No fragment file '$fileId'.", EXC.notFound, "text/plain")
            return true
        }
        // Only a versioned URL is cacheable -- the rule in ContentResources.buildId. Here the id does more
        // than match: it **selects** the content (above), because fragment content varies by client. So a bare
        // `/st/<appId>/md/<fileId>` names no particular document, and storing that answer under that URL is the
        // cross-client poisoning the build id exists to prevent (#456) -- the first requester's copy served to
        // every client behind the cache. Answered rather than refused, because it is reachable without malice:
        // a hand-driven `curl`, or a frontend whose UI-config gave it no build id.
        handler.setResponseHeader("Cache-Control", if (versioned) ContentResources.cacheControl else ContentResources.noStore)
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

    /**
     * A [FragmentResolver] for the **backend pass** (issue #505, Phase 4): it resolves a three-part
     * `fileId.namespace.key` reference across the whole registry, for [cxt]'s client.
     *
     * Three parts, not two, because the backend has every file where a frontend has only its own delivered
     * copy -- so a backend `%{@t(...)}` can pull cross-file. It composes the two rules already in place: the
     * `fileId` selects a file's merged map, and the remaining `namespace.key` resolves through the same kernel
     * [resolveFragment][com.dynamicruntime.common.util.resolveFragment] the frontend uses. Anything but a
     * well-formed three-part key names nothing and returns null.
     */
    fun backendResolver(cxt: KdrCxt): FragmentResolver = FragmentResolver { key ->
        val dot = key.indexOf('.')
        if (dot <= 0 || dot >= key.length - 1) {
            null
        } else {
            effectiveFragments(cxt, key.substring(0, dot))?.content?.resolveFragment(key.substring(dot + 1))
        }
    }

    /**
     * Runs the **backend pass** over [text] (issue #505, Phase 4): evaluates its `%{...}` blocks with the
     * backend [backendResolver] and [data], and leaves `${...}` blocks untouched for the frontend to resolve
     * later. This is how a `%{@t("otherFile.namespace.key")}` is resolved server-side before content ships.
     *
     * ### It throws, and a boot check now catches the static cases
     *
     * An unguarded reference to a fragment this node does not have raises, as everywhere in the template layer
     * -- so a mistyped or renamed key fails the *caller*, which for an endpoint handler means a 500 over one
     * piece of copy. Most such keys are now caught **before** any request: [checkFragments] validates every
     * literal backend pull registry-wide (issue #505) -- the target file's audience, that the pulled
     * `namespace.key` resolves there, and that no backend files form a reference cycle -- and a strict boot
     * refuses on a finding. What it cannot see is a **computed** key (`%{@t(chosenKey)}`), which names a
     * fragment only at evaluation time; that is what the runtime throw still backstops.
     *
     * So two ways to not be surprised by a computed miss: guard the pull (`%{@t(x) ?: "..."}`, the grammar's
     * one default mechanism), or catch around this call if the caller would rather degrade than fail. A caller
     * that does neither is choosing the loud failure.
     *
     * ### What it splices keeps the *caller's* later context, not its source's
     *
     * A backend-composed string **may** carry `${...}` for the frontend to finish -- that is the two-pass model
     * working as intended. What matters is that a surviving `${...}` is evaluated **later, by the frontend,
     * against the element that carried the string**, never against the file the text came from. The two kinds
     * of frontend block are affected very differently, and only one is a hazard:
     *
     *  - **A data substitution (`${count}`) is the ordinary case** and needs no file at all -- it reads the data
     *    the frontend supplies for that element. In practice this is nearly all of it: a backend-composed string
     *    does its *fragment* pulls on the backend and leaves only plain values for the frontend.
     *  - **A frontend fragment pull (`${@t("ns.key")}`) resolves against the element's declared `fileId`.** So a
     *    backend author writing one is asserting that whichever element carries this content names a file
     *    holding `ns.key`. That assertion is theirs to get right: the binding between content and element
     *    happens at request time, so no boot check can verify it. (A *backend* pull -- `%{@t(...)}` -- is
     *    different: it names its file outright, so [checkFragments] does validate it.)
     *
     * The trap to know: pulling something like `sample.email.body` (which reads `${code}`) into an element that
     * supplies no `code` does not fail here -- it ships a `${code}` for the frontend to fail on. Prefer pulling
     * text that is plain or whose `${...}` the carrying element genuinely supplies.
     */
    fun backendPass(cxt: KdrCxt, text: String, data: Map<String, Any?> = emptyMap()): String =
        text.evalTemplate(data, prefix = backendPassPrefix, resolver = backendResolver(cxt))

    @Suppress("ConstPropertyName")
    companion object {
        const val serviceName = "MarkdownFragmentService"

        /**
         * The block prefix for the **backend pass** (issue #505): `%{...}` is resolved on the backend, `${...}`
         * (the kernel default) on the frontend. `%` is free as a delimiter -- inside a block it is the modulo
         * operator, but `%{` opens no block anywhere else, and `#` was rejected because it already begins a
         * fragment-file directive line.
         *
         * Choosing it costs the two things every template prefix costs, and copy that runs a backend pass has
         * to live with both: a **doubled `%%` is the escape**, so it collapses to a single `%` (`"100%% off"`
         * renders `"100% off"` -- write `%%%` for a literal `%%`), and a stray **`%{` throws** as an
         * unterminated block rather than passing through. A lone `%` is untouched, which is the common case.
         */
        const val backendPassPrefix = '%'

        /**
         * The block prefix for the **frontend pass** -- the kernel [evalTemplate][String.evalTemplate] default.
         * Named here so the audience checks (issue #514) can say "frontend block" in code and message rather
         * than a bare `'$'` whose meaning a reader has to remember.
         */
        const val frontendPassPrefix = '$'

        /** The `md` path segment marking a Markdown-fragment request under the static root. */
        const val mdMarker = CMK.md

        /** Classpath resource directory holding the `<fileId>.md` fragment files. */
        const val resourceDir = "md-fragments"

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
         *
         * Null for a [backend][FragmentAudience.backend] file (issue #514): it is never served, so it has no
         * URL to bust -- and returning one would invite a fetch that [serve] then refuses.
         */
        fun fragmentBuildId(cxt: KdrCxt, fileId: String): String? =
            get(cxt).effectiveFragments(cxt, fileId)
                ?.takeIf { it.found && it.audience == FragmentAudience.frontend }?.buildId

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
                property(
                    FCHK.issueCount,
                    "How many findings this file has, across every kind: template issues, orphaned overlay " +
                        "keys, audience violations, and an audience conflict. This is the 'is it clean?' " +
                        "column, so it counts everything a strict boot would refuse to start on -- but not " +
                        "'notes', which are never findings.",
                    required = true,
                ) {
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
                property(
                    FCHK.audience,
                    "Who the file is for: 'frontend' (delivered to the browser) or 'backend' (private -- never " +
                        "served, pulled server-side by a backend '%{@t(...)}').",
                    required = true,
                )
                property(
                    FCHK.audienceConflict,
                    "Whether different bases declared this file both frontend and backend. It resolves to " +
                        "backend, so the file stops being delivered to any frontend -- which breaks every " +
                        "UI-config naming it, far from the declaration that caused it.",
                    required = true,
                ) {
                    type = SCT.boolean
                }
                property(
                    FCHK.audienceIssues,
                    "Audience-rule violations, as messages: a frontend file carrying a backend '%{...}' block, " +
                        "or a backend pull naming a file that is not backend. Findings -- a strict boot refuses " +
                        "on them.",
                    required = true,
                ) {
                    type = SCT.array
                    items { type = SCT.string }
                }
                property(
                    FCHK.notes,
                    $$"Non-fatal observations, as messages: a backend file carrying a frontend '${@t(...)}' pull, " +
                        "whose correctness rests on the carrying element at request time and so cannot be checked " +
                        "here. Never a boot refusal.",
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
    /** Who the file is for (issue #514); a backend file is never delivered to a frontend. */
    val audience: FragmentAudience,
    /** Whether its bases disagreed about [audience] -- see `EffectiveFragments.audienceConflict`. */
    val audienceConflict: Boolean,
    /**
     * Audience-rule violations (issue #514), as messages: a frontend file carrying a `%{...}` backend block, or
     * a backend pull naming a file that is not backend. Findings like [issues] and [orphans] -- a strict boot
     * refuses on them -- but a separate list because they are a fragment-file *deployment* fact, not a template
     * parse problem, exactly as [orphans] is.
     */
    val audienceIssues: List<String>,
    /**
     * Non-fatal observations (issue #514): a backend file carrying a frontend `${@t(...)}` pull, whose
     * correctness rests on the carrying element at request time and so cannot be checked at boot. Reported and
     * logged, never a boot refusal -- the file is not wrong, only resting on a human assertion worth surfacing.
     */
    val notes: List<String>,
) : JsonMappable {
    /**
     * How many **findings** this variant has -- everything a strict boot would refuse to start on, across all
     * of them: [issues], [orphans], [audienceIssues], and [audienceConflict] (which counts as one).
     *
     * Deliberately not `issues.size`. The counted column is what a reader scans to answer "is this file clean?",
     * and a count covering only the *template* problems answers that question wrongly for a file broken in any
     * of the other ways -- reporting 0 for a file the boot refuses on. [notes] are excluded, because they are
     * explicitly not findings: nothing refuses a boot over one.
     */
    val findingCount: Int =
        issues.size + orphans.size + audienceIssues.size + (if (audienceConflict) 1 else 0)

    override fun toJsonMap(): Map<String, Any?> {
        // An explicit map rather than `buildMap`: inside that block the receiver is a MutableMap, whose own
        // `entries` shadows this class's -- so `entries.map { ... }` silently means the wrong thing.
        val out = LinkedHashMap<String, Any?>()
        out[FCHK.fileId] = fileId
        // Omitted rather than null for the shared variant: "this row is about a client" is the unusual case,
        // and a column of nulls is how a reader stops noticing the rows that do have one.
        if (client != null) out[FCHK.client] = client
        out[FCHK.found] = found
        // Every finding, not just the template ones -- see [findingCount]. A reader scanning this column for
        // "is this file clean?" must not be told 0 about a file a strict boot would refuse to start on.
        out[FCHK.issueCount] = findingCount
        out[FCHK.issues] = issues.map { it.toJsonMap() }
        out[FCHK.entries] = entries.map { it.toJsonMap() }
        out[FCHK.orphans] = orphans
        out[FCHK.audience] = audience.name
        out[FCHK.audienceConflict] = audienceConflict
        out[FCHK.audienceIssues] = audienceIssues
        out[FCHK.notes] = notes
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
