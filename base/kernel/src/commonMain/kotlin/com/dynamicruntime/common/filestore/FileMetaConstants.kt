package com.dynamicruntime.common.filestore

/**
 * The keys of a stored file's metadata (`FileMeta`, issue #1129), as its file and its schema type spell them.
 *
 * In the kernel since issue #1130: a page that lists files -- a client's web assets -- reads their metadata by
 * these names, and should break at compile time, not on screen, when one is renamed.
 */
@Suppress("ConstPropertyName")
object FMETA {
    const val mimeType = "mimeType"
    const val size = "size"
    const val hash = "hash"
    const val uploadedAt = "uploadedAt"
    const val fileName = "fileName"
    const val uploadedBy = "uploadedBy"
    const val client = "client"
    const val extra = "extra"

    /** The schema type `FileMeta.defineInfoType` declares. */
    const val infoTypeName = "FileMeta"
}
