package com.dynamicruntime.common.filestore

import com.dynamicruntime.common.operator.FSB
import com.dynamicruntime.common.util.formatDate
import kotlin.time.Instant

/**
 * One file of a folder as the operator's browser lists it (issue #1297): its [key], its [name] (the key's last
 * segment), the store's record of it when there is one, the latest moment any of it changed, and its [state] -- whole
 * ([FSB.stateOk]), content with no record ([FSB.stateNoMeta]), a record with no content ([FSB.stateNoContent]), or
 * content beside a record that cannot be read ([FSB.stateUnreadableMeta]).
 */
class BrowseFile(val key: String, val name: String, val meta: FileMeta?, val modifiedAt: Instant, val state: String) {
    /** As the listing carries it; a file with no record carries no `meta`, rather than a null one. */
    fun toJsonMap(): Map<String, Any?> = buildMap {
        put(FSB.key, key)
        put(FSB.name, name)
        meta?.let { put(FSB.meta, it.toJsonMap()) }
        put(FSB.modifiedAt, modifiedAt.formatDate())
        put(FSB.state, state)
    }
}

/** What is directly under one folder of a bucket (issue #1297): its folders, its files, and whether it was cut short. */
class BrowseListing(val bucket: String, val prefix: String, val folders: List<String>, val files: List<BrowseFile>, val more: Boolean) {
    /** As the listing endpoint answers it; the top of a bucket carries no `prefix`. */
    fun toJsonMap(): Map<String, Any?> = buildMap {
        put(FSB.bucket, bucket)
        if (prefix.isNotEmpty()) put(FSB.prefix, prefix)
        put(FSB.folders, folders)
        put(FSB.files, files.map { it.toJsonMap() })
        put(FSB.more, more)
    }
}

/**
 * The folder view of a bucket (issue #1297), derived from the names the store holds: no provider keeps folders, so
 * under a prefix the next segment of each name is a folder when a `/` follows it and a file when not. A file's
 * record (`<key>.meta.json`) is folded into the file's row rather than listed, a directory store's temporary files
 * are left out, and a file missing either half is listed flagged rather than hidden -- what [FileStoreCheck] would
 * report, seen where it is.
 *
 * **Cut, never refused.** A folder holding more than the limit is answered up to it, folders first, with [BrowseListing.more]
 * set: an operator looking into a bucket wants to see something, where a listing that serves files refuses to be
 * cut short ([FileStoreService.list]). Only the files shown have their record read, so the cost is bounded by the
 * limit and not by the folder.
 *
 * **What a folder costs is every name beneath it**, however deep: the folders under a prefix are known only once
 * every object under it has been named, so the top of a bucket names the whole bucket (a list request per thousand
 * names on S3) to show its first folders. Fine at the scale a deployment keeps files today; a bucket of tens of
 * thousands of objects would want the delimiter listing S3 offers (`docs/development/deferred-work.md`).
 */
object FileStoreBrowse {
    /**
     * The listing of the folder [prefix] of [bucket] from [objects], every object under the prefix by name and date
     * ([FileStore.objects]). [tmpKeyOf] says which names are a store's temporary objects ([FileStore.tmpKeyOf]), and
     * [metaOf] reads a file's record, which is asked only of the files shown.
     */
    fun folderListing(
        bucket: String,
        prefix: String,
        objects: List<StoredObject>,
        limit: Int,
        tmpKeyOf: (String) -> String?,
        metaOf: (String) -> FileMeta?,
    ): BrowseListing {
        val folders = sortedSetOf<String>()
        val contentAt = HashMap<String, Instant>()
        val metaAt = HashMap<String, Instant>()
        for (obj in objects) {
            if (!obj.name.startsWith(prefix)) continue
            val rest = obj.name.removePrefix(prefix)
            val slash = rest.indexOf('/')
            if (slash >= 0) {
                folders.add(rest.substring(0, slash + 1))
                continue
            }
            if (tmpKeyOf(obj.name) != null) continue
            if (obj.name.endsWith(FileMeta.metaSuffix)) {
                metaAt[obj.name.removeSuffix(FileMeta.metaSuffix)] = obj.modifiedAt
            } else {
                contentAt[obj.name] = obj.modifiedAt
            }
        }
        val keys = (contentAt.keys + metaAt.keys).sorted()
        val shownFolders = folders.take(limit)
        val shownKeys = keys.take(maxOf(0, limit - shownFolders.size))
        val files = shownKeys.map { key ->
            val content = contentAt[key]
            val metaSeen = metaAt[key]
            // The record is read only where there is one to read; one that is there and cannot be read is no file.
            val meta = if (metaSeen != null) metaOf(key) else null
            val state = when {
                content != null && meta != null -> FSB.stateOk
                content != null && metaSeen != null -> FSB.stateUnreadableMeta
                content != null -> FSB.stateNoMeta
                else -> FSB.stateNoContent
            }
            BrowseFile(key, key.substringAfterLast('/'), meta, modifiedAt = listOfNotNull(content, metaSeen).max(), state = state)
        }
        return BrowseListing(bucket, prefix, shownFolders, files, more = folders.size + keys.size > limit)
    }

    /** [prefix] as a folder's: empty for the top of a bucket, else ending in `/`, since a folder is what is listed. */
    fun folderPrefix(prefix: String?): String {
        val trimmed = prefix?.trim().orEmpty()
        return if (trimmed.isEmpty() || trimmed.endsWith("/")) trimmed else "$trimmed/"
    }
}
