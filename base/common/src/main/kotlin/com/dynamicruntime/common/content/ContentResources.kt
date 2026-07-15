package com.dynamicruntime.common.content

import java.util.concurrent.ConcurrentHashMap
import java.util.zip.CRC32

/**
 * Classpath access for the Markdown content resources -- the fragment files ([MarkdownFragmentService], holding
 * addressable snippets) and the whole documents ([MarkdownDocService], served verbatim as pages). Both kinds
 * are `<dir>/<fileId>.md` resources addressed by a `fileId:buildId`, so the id check, the read, and the
 * memoized cache-busting build id live here once rather than in each server.
 */
object ContentResources {
    /**
     * Memoized build ids, keyed by `<dir>/<fileId>`. Classpath resources are immutable within a running
     * deployment, so a file's hash is computed once per process. The [absentMarker] empty-string sentinel
     * caches a "resource not present" result too (an absent file stays absent for the deployment).
     */
    private val buildIdCache = ConcurrentHashMap<String, String>()

    /** Sentinel stored in [buildIdCache] for a file whose resource is absent (distinct from any hash). */
    private const val absentMarker = ""

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
     * file per process; used by the code handing a component its `fileId:buildId` (the UI-config endpoints),
     * while the content servers themselves only strip it.
     */
    fun buildId(dir: String, fileId: String): String? {
        if (!isSafeFileId(fileId)) {
            return null
        }
        val computed = buildIdCache.getOrPut("$dir/$fileId") {
            val bytes = readBytes(dir, fileId)
            if (bytes == null) {
                absentMarker
            } else {
                val crc = CRC32()
                crc.update(bytes)
                crc.value.toString(16)
            }
        }
        return computed.ifEmpty { null }
    }
}
