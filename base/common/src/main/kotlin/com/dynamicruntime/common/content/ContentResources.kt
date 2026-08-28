package com.dynamicruntime.common.content

import com.dynamicruntime.common.util.crc32Hex
import java.util.concurrent.ConcurrentHashMap

/**
 * Classpath access for the Markdown content resources -- the fragment files ([MarkdownFragmentService], holding
 * addressable snippets) and the whole documents ([MarkdownDocService], served verbatim as pages). Both kinds
 * are `<dir>/<fileId>.md` resources addressed by a `fileId:buildId`, so the id check, the read, and the
 * memoized cache-busting build id live here once rather than in each server.
 */
@Suppress("ConstPropertyName")
object ContentResources {
    /**
     * Memoized build ids, keyed by `<dir>/<fileId>`. Classpath resources are immutable within a running
     * deployment, so a file's hash is computed once per process. The [absentMarker] empty-string sentinel
     * caches a "resource not present" result too (an absent file stays absent for the deployment).
     */
    private val buildIdCache = ConcurrentHashMap<String, String>()

    /** Sentinel stored in [buildIdCache] for a file whose resource is absent (distinct from any hash). */
    private const val absentMarker = ""

    /**
     * The permanent, shared-cache header a content server may return **only** for a content-addressed URL --
     * one whose supplied build id names exactly the bytes being returned. This is the entitlement a build id
     * grants; see [buildId] for the rule that governs when it may be used.
     */
    const val cacheControl = "public, max-age=31536000, immutable"

    /**
     * The header for every response a shared cache must **not** keep: a URL with no build id or a build id
     * that does not match the content, and a 404 (which one node can give for a resource another still serves
     * mid-deploy). The content is still returned -- nothing here is secret -- but never stored under a URL
     * that does not name it. See [buildId].
     */
    const val noStore = "no-store"

    /** A content file id must be a plain file-name token (guards the classpath lookup against traversal). */
    fun isSafeFileId(fileId: String): Boolean =
        fileId.isNotEmpty() && fileId.all { it.isLetterOrDigit() || it == '-' || it == '_' }

    /** The raw bytes of `<dir>/<fileId>.md`, or null when the id is unsafe or the resource is absent. */
    fun readBytes(dir: String, fileId: String): ByteArray? {
        if (!isSafeFileId(fileId)) {
            return null
        }
        return ContentResources::class.java.getResourceAsStream("/$dir/$fileId.md")?.use { it.readBytes() }
    }

    /** The text of `<dir>/<fileId>.md`, or null when absent. */
    fun readText(dir: String, fileId: String): String? = readBytes(dir, fileId)?.toString(Charsets.UTF_8)

    /**
     * The cache-busting build id for a content file: a content hash (CRC32, hex) of the resource bytes, or
     * null if the resource is absent. A content hash (rather than a timestamp) is jar-agnostic and changes
     * only when the content changes -- so an unchanged file keeps its URL across rebuilds. Computed once per
     * file per process; handed to a component as `fileId:buildId` by the UI-config endpoints.
     *
     * **The rule a build id exists to enforce** (both content servers turn on it, so it lives here, where the
     * id is minted): *do not return [cacheControl] -- the permanent, shared-cache header -- unless the caller
     * supplied a build id and it matches what this node has for that resource.* The general form is a
     * **content-addressed URL**: a permanently-cacheable URL must be derived from the bytes it returns, the
     * same idea as a hashed asset filename. The precise invariant is *the URL identifies exactly one immutable
     * resource* -- not "the id is the current one", which stops being well-defined the moment content can vary
     * by caller (as fragment overlays made it, #456). An absent or unmatched id still gets the content, under
     * [noStore]; only the header is withheld.
     *
     * The **status code** on a miss is a separate decision, made by each server from its caller's recovery
     * affordance, not by this rule. A document serves current content (a user need only navigate away and
     * back), while a fragment 404s so the shell notices its ref is stale (#469, #472). The header protects the
     * cache either way; the status is chosen by whether the client can recover without it.
     */
    fun buildId(dir: String, fileId: String): String? {
        if (!isSafeFileId(fileId)) {
            return null
        }
        val computed = buildIdCache.getOrPut("$dir/$fileId") {
            readBytes(dir, fileId)?.crc32Hex() ?: absentMarker
        }
        return computed.ifEmpty { null }
    }
}
