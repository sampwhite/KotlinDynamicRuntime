package com.dynamicruntime.common.datafile

/**
 * The names of a **data file** (issue #1837): a file uploaded for a form's data, held by a record of its own
 * (`GedraDataType.dataFile`) whose id a trait's file field names. In the kernel, so the browser uploading one and the
 * server taking it spell everything the same way.
 *
 * Theme: #DFILE.
 */
@Suppress("ConstPropertyName")
object DFILE {
    /** The section every data-file endpoint is in: any signed-in user, reaching as far as their read scope. */
    const val section = "files"

    /** The bucket the bytes are kept in, each client's under its folder. */
    const val bucket = "data-files"

    // --- the record: the keys under the gedra's `data.file` ---
    /** Under the gedra's `data`: the file's record, a map of the keys below. */
    const val file = "file"
    const val name = "name"
    const val kind = "kind"
    const val mimeType = "mimeType"
    const val size = "size"
    const val hash = "hash"
    const val status = "status"
    const val renditions = "renditions"
    const val uploadedAt = "uploadedAt"

    // --- endpoint input and output ---
    /** The data file's id: its gedra id, `gd.df.<client>.<base>`. */
    const val fileId = "fileId"
    const val declaredSize = "declaredSize"
    const val offset = "offset"
    const val chunkHash = "chunkHash"
    const val chunkSize = "chunkSize"
    const val nextOffset = "nextOffset"
    /** When an upload is no longer taken: in an upload's answer, and on the record of a file still uploading. */
    const val expiresAt = "expiresAt"
    const val ownerId = "ownerId"

    /** In the answer to an abort or a removal: whether there was a file, or an upload, to remove. */
    const val removed = "removed"

    // --- which forms use a file (issue #1838) ---
    /** The fields of live forms the caller may read that name the file, each a [formId], [traitId], and [fieldPath]. */
    const val usedBy = "usedBy"
    const val formId = "formId"
    const val traitId = "traitId"
    const val fieldPath = "fieldPath"

    /** How many live forms the caller may not read use the file too. */
    const val hiddenForms = "hiddenForms"

    /** Schema type names. */
    const val usedByType = "DataFileUsedBy"
    const val useType = "DataFileUse"
    const val recordType = "DataFile"
    const val uploadType = "DataFileUpload"
    const val goneType = "DataFileGone"

    // --- endpoint paths ---
    const val beginPath = "/$section/upload/begin"
    const val chunkPath = "/$section/upload/chunk"
    const val finishPath = "/$section/upload/finish"
    const val abortPath = "/$section/upload/abort"
    const val statusPath = "/$section/upload/status"
    const val infoPath = "/$section/info"
    const val contentPath = "/$section/content"
    const val deletePath = "/$section/delete"
    const val usedByPath = "/$section/usedBy"

    /**
     * How many bytes each chunk of an upload is, all but the last: what the server asks for, at least the 5 MiB the
     * file store holds every part but the last to, and what one request carries.
     */
    const val chunkBytes = 8 * 1024 * 1024

    /** How long an upload may take, from its beginning, before it is no longer taken and the sweep removes it. */
    const val uploadHours = 24
}

/**
 * Where a data file is in its life (issue #1837). Only [uploading] and [ready] are reached today: [pending] and
 * [failed] are for converting a video, which is not built (`docs/development/deferred-work.md`).
 */
@Suppress("ConstPropertyName")
object DFSTAT {
    /** Begun, and taking chunks; no bytes are served. */
    const val uploading = "uploading"

    /** Whole, checked, and served. */
    const val ready = "ready"

    /** Whole and served as it is, while a conversion makes another rendition. */
    const val pending = "pending"

    /** A conversion that did not complete; the file is served as it was uploaded. */
    const val failed = "failed"

    val all: List<String> = listOf(uploading, ready, pending, failed)
}
