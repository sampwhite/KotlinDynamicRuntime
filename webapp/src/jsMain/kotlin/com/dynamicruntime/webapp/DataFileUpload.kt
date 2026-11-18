package com.dynamicruntime.webapp

import com.dynamicruntime.common.datafile.DFILE
import com.dynamicruntime.common.datafile.DFSTAT
import com.dynamicruntime.common.filekind.FileKind
import com.dynamicruntime.common.filekind.FileKindSniff
import com.dynamicruntime.common.filekind.FileKinds
import com.dynamicruntime.common.schema.SchType
import com.dynamicruntime.common.schema.fileSizeLimit
import com.dynamicruntime.common.util.byteSizeText
import com.dynamicruntime.common.util.toJsonMapOrEmpty
import com.dynamicruntime.common.util.toOptLong
import com.dynamicruntime.common.util.toOptStr

/**
 * A form's file field (issue #1840), the pure half: what a field asks of a picked file, how an upload of it is sent and
 * resumed, and what a file's record says. Covered under `jsNodeTest`; the requests and the component are in
 * `DataFileField.kt`.
 *
 * Theme: #DFILE.
 */

/** A data file's record as a field shows it, read from what `/files/info` or a form's `files` answers. */
class DataFileView(
    val fileId: String,
    val name: String?,
    val kind: FileKind?,
    val size: Long?,
    val status: String?,
) {
    /** Whether its bytes can be read: an upload under way has none yet. */
    val readable: Boolean get() = status != null && status != DFSTAT.uploading

    /** What the field calls it: its name, else its kind's word for it, else its id. */
    val title: String get() = name ?: kind?.let { "${it.label} file" } ?: fileId
}

/** [record], a data file's, as a [DataFileView] of [fileId]: the record's own id when it carries one. */
fun dataFileViewFrom(fileId: String, record: Any?): DataFileView {
    val map = record.toJsonMapOrEmpty()
    return DataFileView(
        fileId = map[DFILE.fileId].toOptStr() ?: fileId,
        name = map[DFILE.name].toOptStr(),
        kind = map[DFILE.kind].toOptStr()?.let { FileKinds.byId(it) },
        size = map[DFILE.size].toOptLong(),
        status = map[DFILE.status].toOptStr(),
    )
}

/** Each record of a form's `files` (issue #1838), id to record, as a [DataFileView]. */
fun dataFileViewsFrom(files: Any?): List<DataFileView> =
    files.toJsonMapOrEmpty().mapNotNull { (id, record) -> id.takeIf { it.isNotEmpty() }?.let { dataFileViewFrom(it, record) } }

/** What a file field's picker offers: each of its kinds' MIME types and extension, as `accept` lists them. */
fun dataFileAccept(vt: SchType): List<String> =
    vt.fileKinds.flatMap { listOf(it.mimeType) + it.alsoDeclaredAs + ".${it.extension}" }.distinct()

/** [words] joined as a sentence lists them, as a choice: `A`, `A or B`, `A, B, or C` -- file kinds, a workflow's labels. */
fun orList(words: List<String>): String = when (words.size) {
    0 -> ""
    1 -> words[0]
    2 -> "${words[0]} or ${words[1]}"
    else -> words.dropLast(1).joinToString(", ") + ", or " + words.last()
}

/**
 * The note under a file field: the kinds it takes and, when the field holds them smaller than the kinds do, how large:
 * `PNG, JPEG, or WebP, up to 5 MB.` Sizes the kinds alone set are not said, since a field's own limit is the one a
 * person meets.
 */
fun dataFileHint(vt: SchType): String {
    val kinds = orList(vt.fileKinds.map { it.label })
    val limit = vt.fileMaxSize?.takeIf { max -> vt.fileKinds.any { it.maxSize > max } }
    return if (limit == null) "$kinds." else "$kinds, up to ${byteSizeText(limit)}."
}

/** A picked file judged against its field: the [kind] its bytes are, or the [problem] that keeps it out. */
class PickedFile(val kind: FileKind?, val problem: String?)

/**
 * Judges a picked file for field [vt] before anything is sent, from its first bytes [head] (at least
 * [FileKindSniff.headSize] of them, or the whole file) and its [size]: by what its **bytes** are, as the server judges
 * it, never by the name or the type the browser guessed -- a phone's photo named `.png` that is a JPEG is found here,
 * not after its upload. The kind found is what the upload is sent as.
 */
fun judgePick(vt: SchType, head: ByteArray, size: Long): PickedFile {
    if (size < 1) return PickedFile(null, "This file is empty.")
    val takes = orList(vt.fileKinds.map { it.label })
    val kind = FileKindSniff.sniff(head, vt.fileKinds)
        ?: return PickedFile(null, "This field takes only $takes; this file is ${FileKindSniff.sniff(head)?.let { "a ${it.label} file" } ?: "none of these"}.")
    val limit = vt.fileSizeLimit(kind) ?: return PickedFile(null, "This field takes only $takes.")
    if (size > limit) {
        return PickedFile(null, "A ${kind.label} file here is at most ${byteSizeText(limit)}; this one is ${byteSizeText(size)}.")
    }
    return PickedFile(kind, null)
}

/** Whether a file of [size] bytes is sent in one request (`/files/upload`) rather than in chunks. */
fun sentWhole(size: Long): Boolean = size <= DFILE.chunkBytes

/** Where the chunk beginning at [offset] of a file of [size] ends: [chunkSize] on, or at the end of the file. */
fun chunkEnd(offset: Long, size: Long, chunkSize: Long): Long = minOf(offset + chunkSize, size)

/**
 * Where to send from after the server said the upload has reached [nextOffset]: there, while bytes remain; and when
 * every byte is taken but the file is not finished -- its finish failed part-way -- the last chunk again, which the
 * server answers as taken and finishes.
 */
fun resumeOffset(nextOffset: Long, size: Long, chunkSize: Long): Long =
    if (nextOffset < size) nextOffset else ((size - 1) / chunkSize) * chunkSize

/** How far an upload of [size] bytes has come at [sent] bytes, as a whole percent. */
fun uploadPercent(sent: Long, size: Long): Int = if (size <= 0) 0 else ((sent * 100) / size).toInt().coerceIn(0, 100)

/** What a request of an upload that did not succeed calls for. */
@Suppress("EnumEntryName")
enum class SendFailure {
    /** No answer, or the server's own failure: the same request again, after a wait. */
    retry,

    /** The server is somewhere else in the upload, and said where: send from there. */
    resync,

    /** Refused for a reason sending again cannot change: say it. */
    stop,
}

/**
 * What a failed request calls for, from its [status] (null: no answer at all) and whether its refusal said where the
 * upload is ([saysWhere], a 409 carrying `nextOffset`). A 409 that does not -- an upload past its hours -- is a stop.
 */
fun sendFailure(status: Int?, saysWhere: Boolean): SendFailure = when {
    status == null -> SendFailure.retry
    status == 409 && saysWhere -> SendFailure.resync
    status == 429 || status >= 500 -> SendFailure.retry
    else -> SendFailure.stop
}

/** How many times a request is sent again before the upload stops, to be resumed by hand. */
const val uploadRetries = 4

/** How long to wait before retry [attempt] (from 1): 1, 2, 4, then 8 seconds; null past [uploadRetries]. */
fun retryDelayMs(attempt: Int): Long? = if (attempt < 1 || attempt > uploadRetries) null else 1000L shl (attempt - 1)
