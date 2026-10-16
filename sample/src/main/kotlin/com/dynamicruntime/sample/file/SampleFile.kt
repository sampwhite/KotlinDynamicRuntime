package com.dynamicruntime.sample.file

import com.dynamicruntime.common.exception.EXC
import com.dynamicruntime.common.exception.KdrException
import com.dynamicruntime.common.filestore.FileBucketDef
import com.dynamicruntime.common.filestore.FileMeta
import com.dynamicruntime.common.schema.JsonMappable
import com.dynamicruntime.common.util.RandomUtil
import com.dynamicruntime.common.util.formatDate
import kotlin.time.Instant

/**
 * Field keys shared by the file schema types ([SampleFileService.schema]) and [SampleFileInfo]. Each
 * constant's name matches its JSON key, per the code guide.
 */
@Suppress("ConstPropertyName")
object SF {
    const val id = "id"
    const val fileName = "fileName"
    const val mimeType = "mimeType"
    const val size = "size"
    const val uploaded = "uploaded"

    /** The upload endpoint's binary input field — the part carrying the file itself. */
    const val file = "file"
}

/**
 * What is known about one uploaded file: the sample's own response shape, read from the metadata the file store
 * keeps beside the file ([FileMeta]). Kept as the sample's rather than
 * answering `FileMeta` itself so the endpoints answer exactly what they did before they moved onto the store.
 */
class SampleFileInfo(
    val id: String,
    /** The name the *client* gave the file, as the store records it (its last segment). Metadata only -- see
     *  [SFK] on why it never becomes a key. Handed back on download as the name to save it under. */
    val fileName: String,
    val mimeType: String,
    val size: Long,
    val uploaded: Instant,
) : JsonMappable {
    override fun toJsonMap(): Map<String, Any?> = linkedMapOf(
        SF.id to id,
        SF.fileName to fileName,
        SF.mimeType to mimeType,
        SF.size to size,
        SF.uploaded to uploaded.formatDate(),
    )

    companion object {
        /** The file [id], as the store's [meta] describes it. */
        fun of(id: String, meta: FileMeta): SampleFileInfo = SampleFileInfo(
            id = id,
            // A part with no filename is possible; name it for the id rather than inventing one.
            fileName = meta.fileName ?: "upload-$id",
            mimeType = meta.mimeType,
            size = meta.size,
            uploaded = meta.uploadedAt,
        )
    }
}

/**
 * Sample File Keys: where the sample keeps its uploads in the file store (issue #1144) -- a bucket of its own, and in
 * it a key per file composed from an id this code generates. An acronym object, as the file store's own `FNAME` is,
 * since it holds constants beside the rules over them.
 *
 * **The client's filename never becomes a key.** A file is stored under a generated [newId], and the name it arrived
 * with is metadata the store keeps beside it. This is not tidiness: a name is attacker-supplied, and letting it
 * choose where a file goes is how an upload becomes an overwrite of another file. Sanitizing a name well enough to be
 * a location is a losing game; *not deriving the location from it* is a guarantee rather than a filter. The store
 * then offers the name back on download, which is where it is safe -- as a suggestion, sanitized into a header.
 *
 * Ids come from the **secure** random source. These sample endpoints have no auth, so the id is the only thing
 * standing between a stored file and anyone who asks: a sequential or fast-random id would let a stranger walk
 * the store. An unguessable id makes the URL itself the capability.
 *
 * A sample, so it is deliberately plain: no quota, no cleanup. A node running in memory only keeps its uploads in
 * memory, and they go when it stops.
 */
@Suppress("ConstPropertyName")
object SFK {
    /** The bucket the sample's uploads are kept in. */
    const val bucket = "sample-files"

    /** The folder of the bucket each upload is a file in. */
    const val uploadsPrefix = "uploads/"

    /** 16 secure random bytes as hex: unguessable, and a valid key segment by construction. */
    const val idBytes = 16
    const val idLength = idBytes * 2

    /** The bucket's declaration, for `SampleComponent` to make. */
    fun bucketDef(): FileBucketDef = FileBucketDef(bucket, "Files uploaded through the sample's demo file endpoints.")

    /** A fresh file id: hex, from the secure source (see the note above on why it must be unguessable). */
    fun newId(): String = RandomUtil.secureBytes(idBytes).toHexString()

    /** Where the file [id] is kept: [checkedId] first, since an id arrives from a request. */
    fun keyOf(id: String): String = uploadsPrefix + checkedId(id)

    /** The id of the file at [key], one of [uploadsPrefix]'s; null for any other. */
    fun idOf(key: String): String? = key.removePrefix(uploadsPrefix).takeIf { key.startsWith(uploadsPrefix) && isId(it) }

    /**
     * Guards an id arriving from a request before it is made a key. Ids we mint are lowercase hex of [idLength], so
     * anything else is not one of ours, and is refused as that rather than looked up and not found.
     */
    fun checkedId(id: String): String {
        if (!isId(id)) {
            throw KdrException("Not a valid file id: '$id'.", null, EXC.badInput)
        }
        return id
    }

    fun isId(id: String): Boolean = id.length == idLength && id.all { it in "0123456789abcdef" }
}
