package com.dynamicruntime.common.filestore

import com.dynamicruntime.common.schema.JsonMappable
import com.dynamicruntime.common.schema.SCT
import com.dynamicruntime.common.schema.SchTypesBuilder
import com.dynamicruntime.common.util.formatDate
import com.dynamicruntime.common.util.jsonMapOrNull
import com.dynamicruntime.common.util.parseDateOrNull
import com.dynamicruntime.common.util.toJsonMapOrEmpty
import com.dynamicruntime.common.util.toJsonStr
import kotlin.time.Instant

/**
 * What is known about a stored file, kept **beside** it as a file of its own (issue #1129): at [metaKey], as
 * JSON. It is what makes a file more than bytes -- what type it is, what it was called, who put it there and
 * when, and a hash that identifies exactly those bytes -- and it can be read without the file
 * ([FileStoreService.meta]), so "what is this?" never costs a download.
 *
 * **Everything here is readable by whoever may read the file**, [extra] included: nothing secret belongs in it.
 */
class FileMeta(
    /** The content's type, as it was stored: an upload's own claim unless whoever stored it checked. */
    val mimeType: String,
    /** The content's length in bytes. */
    val size: Long,
    /** The SHA-256 of the content, as 64 lowercase hex characters: what identifies exactly these bytes. */
    val hash: String,
    /** When the file was stored. */
    val uploadedAt: Instant,
    /**
     * The name the file arrived under, when it had one. **Metadata only**: it never chooses where the file is
     * kept ([FNAME.keyProblem]), and is offered back as the name to save a download as.
     */
    val fileName: String? = null,
    /** The user who stored it, or null when no signed-in user did (the system, a job). */
    val uploadedBy: Long? = null,
    /**
     * The client whose file this is: the client the context that stored it was **bound to** (`cxt.client`), as a
     * row's owning client is -- not who was acting. So a job storing a file for a client binds its context to
     * that client first; a context bound to none records the deployment's own client.
     */
    val client: String? = null,
    /**
     * Whatever else the code that stored the file wants kept with it, as JSON: what is read back is its JSON
     * form, so a value that is not JSON's comes back as the text it was written as.
     */
    val extra: Map<String, Any?> = emptyMap(),
) : JsonMappable {
    override fun toJsonMap(): Map<String, Any?> {
        val out = linkedMapOf<String, Any?>(
            FMETA.mimeType to mimeType,
            FMETA.size to size,
            FMETA.hash to hash,
            FMETA.uploadedAt to uploadedAt.formatDate(),
        )
        fileName?.let { out[FMETA.fileName] = it }
        uploadedBy?.let { out[FMETA.uploadedBy] = it }
        client?.let { out[FMETA.client] = it }
        if (extra.isNotEmpty()) out[FMETA.extra] = extra
        return out
    }

    /** This metadata as the text of the file kept beside the content. */
    fun toFileText(): String = toJsonMap().toJsonStr()

    @Suppress("ConstPropertyName")
    companion object {
        /** What follows a file's key to name its metadata: `logo` is described by `logo.meta.json`. */
        const val metaSuffix = ".meta.json"

        /** The type the metadata file itself is stored as. */
        const val metaMimeType = "application/json"

        /** Where the metadata of the file at [key] is kept, in the same bucket. */
        fun metaKey(key: String): String = key + metaSuffix

        /**
         * The metadata [map] holds, or null when it is not a whole one -- the type, size, hash or date missing.
         * Null rather than a refusal, since a file whose metadata cannot be read is a file that is not there.
         */
        fun fromJsonMap(map: Map<String, Any?>): FileMeta? {
            return FileMeta(
                mimeType = map[FMETA.mimeType] as? String ?: return null,
                size = (map[FMETA.size] as? Number)?.toLong() ?: return null,
                hash = map[FMETA.hash] as? String ?: return null,
                uploadedAt = (map[FMETA.uploadedAt] as? String)?.parseDateOrNull() ?: return null,
                fileName = map[FMETA.fileName] as? String,
                uploadedBy = (map[FMETA.uploadedBy] as? Number)?.toLong(),
                client = map[FMETA.client] as? String,
                extra = map[FMETA.extra].toJsonMapOrEmpty(),
            )
        }

        /** The metadata a metadata file's [text] holds, or null when it is not one ([fromJsonMap]). */
        fun fromFileText(text: String): FileMeta? = text.jsonMapOrNull()?.let { fromJsonMap(it) }

        /** The shape of [toJsonMap], kept with the class so the two cannot drift apart. */
        fun defineInfoType(builder: SchTypesBuilder) {
            builder.type(FMETA.infoTypeName) {
                type = SCT.kObject
                description = "What is known about a stored file: its type, size and hash, and who stored it when."
                property(FMETA.mimeType, "The content's type, as it was stored.", required = true)
                property(FMETA.size, "The content's length in bytes.", required = true) { type = SCT.integer }
                property(FMETA.hash, "The SHA-256 of the content, in hex: what identifies exactly these bytes.", required = true)
                property(FMETA.uploadedAt, "When the file was stored.", required = true) { dateTime() }
                property(FMETA.fileName, "The name the file arrived under, when it had one.")
                property(FMETA.uploadedBy, "The id of the user who stored it, when a signed-in user did.") { type = SCT.integer }
                property(FMETA.client, "The client whose file this is: the one the storing context was bound to.")
                property(FMETA.extra, "Whatever else was kept with the file by the code that stored it.") {
                    type = SCT.kObject
                    additionalProperties = true
                }
            }
        }
    }
}
