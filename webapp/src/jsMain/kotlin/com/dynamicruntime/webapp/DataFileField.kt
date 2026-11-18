package com.dynamicruntime.webapp

import com.dynamicruntime.common.datafile.DFILE
import com.dynamicruntime.common.datafile.DFSTAT
import com.dynamicruntime.common.endpoint.EP
import com.dynamicruntime.common.filekind.FKFAM
import com.dynamicruntime.common.filekind.FileKind
import com.dynamicruntime.common.filekind.FileKindSniff
import com.dynamicruntime.common.schema.SchType
import com.dynamicruntime.common.util.byteSizeText
import com.dynamicruntime.common.util.toJsonMapOrEmpty
import com.dynamicruntime.common.util.toOptLong
import com.dynamicruntime.common.util.toOptStr
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.await
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import react.FC
import react.Props
import react.dom.html.ReactHTML.a
import react.dom.html.ReactHTML.div
import react.dom.html.ReactHTML.img
import react.dom.html.ReactHTML.span
import react.dom.html.ReactHTML.video
import react.useEffect
import react.useRef
import react.useState
import web.cssom.ClassName
import kotlin.js.Promise

/**
 * A form's file field (issue #1840): the requests that upload a file and read its record, and the components that
 * pick one, upload it, and show it. The rules they follow are pure, in `DataFileUpload.kt`.
 *
 * Theme: #DFILE.
 */

private val dataFileScope = MainScope()

/**
 * The records of the files fields have shown or uploaded, by id, so a field shows a file without asking again: filled
 * from a form's `files` when a page reads one, from `/files/info` when a field meets a file it has not seen, and from an
 * upload's answer. A finished file's record does not change, so nothing here goes stale; one still uploading is not
 * kept.
 */
object DataFileCache {
    private val views = mutableMapOf<String, DataFileView>()

    fun get(fileId: String): DataFileView? = views[fileId]

    fun remember(view: DataFileView) {
        if (view.readable) views[view.fileId] = view
    }

    /** Each record of a form's `files` (issue #1838). */
    fun rememberAll(files: Any?) = dataFileViewsFrom(files).forEach(::remember)
}

/** What an upload stopped part-way leaves to resume it by: the file's id, the browser's file, its kind, and how far it came. */
class PendingUpload(val fileId: String, val file: dynamic, val kind: FileKind, val sent: Long)

/** The data-file requests (issue #1837): a file's record, its content's address, and an upload, whole or in chunks. */
object DataFileApi {
    /** The address [fileId]'s content is read from, a range at a time: an `<img>`'s or a `<video>`'s `src`, or a link. */
    fun contentUrl(fileId: String): String = Http.apiUrl(DFILE.contentPath, mapOf(DFILE.fileId to fileId))

    suspend fun info(fileId: String): DataFileView =
        dataFileViewFrom(fileId, Http.getApi(DFILE.infoPath + queryString(mapOf(DFILE.fileId to fileId)))[EP.item])

    suspend fun abort(fileId: String) {
        Http.sendApi("POST", DFILE.abortPath, mapOf(DFILE.fileId to fileId))
    }

    /**
     * Uploads [file], a browser `File` whose bytes are [kind], and answers its record, ready. A file of one chunk or
     * less goes in one request; a larger one is begun -- [onBegun] told its id, which is what a resume needs -- and sent
     * a chunk at a time, each with its SHA-256, the last of which finishes it. [onProgress] hears the bytes the server
     * has taken. [resume], an upload stopped part-way, takes it up from where the server says it reached.
     *
     * A request with no answer, or the server's own failure, is sent again after a wait ([retryDelayMs]); a 409 that
     * says where the upload is resumes from there; anything else is thrown, the [ApiError] or the [ApiFailure] it is.
     */
    suspend fun upload(
        file: dynamic, kind: FileKind, resume: PendingUpload?, onBegun: (String) -> Unit, onProgress: (Long) -> Unit,
    ): DataFileView {
        val size = (file.size as Number).toLong()
        val name = file.name as? String
        if (resume == null && sentWhole(size)) {
            val answer = retried { Http.sendRawApiResult(DFILE.uploadPath, mapOf(DFILE.name to name, DFILE.mimeType to kind.mimeType), file) }
            onProgress(size)
            val record = answer[EP.results].toJsonMapOrEmpty()
            return dataFileViewFrom(record[DFILE.fileId].toOptStr().orEmpty(), record)
        }
        val fileId: String
        val chunkSize: Long
        var offset: Long
        if (resume == null) {
            val begun = Http.sendApi("POST", DFILE.beginPath, mapOf(DFILE.name to name, DFILE.mimeType to kind.mimeType, DFILE.declaredSize to size))[EP.item]
                .toJsonMapOrEmpty()
            fileId = begun[DFILE.fileId].toOptStr() ?: throw ApiFailure("The upload began with no file id.")
            chunkSize = begun[DFILE.chunkSize].toOptLong() ?: DFILE.chunkBytes.toLong()
            offset = 0L
            onBegun(fileId)
        } else {
            fileId = resume.fileId
            val status = retried { Http.getApiResult(DFILE.statusPath + queryString(mapOf(DFILE.fileId to fileId))) }[EP.item].toJsonMapOrEmpty()
            // Finished already: the answer that said so was what was lost.
            if (status[DFILE.status] != DFSTAT.uploading) return info(fileId)
            chunkSize = status[DFILE.chunkSize].toOptLong() ?: DFILE.chunkBytes.toLong()
            offset = resumeOffset(status[DFILE.nextOffset].toOptLong() ?: 0L, size, chunkSize)
        }
        var attempt = 0
        while (true) {
            val end = chunkEnd(offset, size, chunkSize)
            val chunk = (file.slice(offset.toDouble(), end.toDouble()).arrayBuffer() as Promise<dynamic>).await()
            val args = mapOf(DFILE.fileId to fileId, DFILE.offset to offset, DFILE.chunkHash to sha256Hex(chunk))
            when (val sent = Http.sendRawApiResult(DFILE.chunkPath, args, chunk)) {
                is ApiResult.Ok -> {
                    attempt = 0
                    val results = sent.value[EP.results].toJsonMapOrEmpty()
                    results[DFILE.file]?.let { record -> onProgress(size); return dataFileViewFrom(fileId, record) }
                    offset = results[DFILE.nextOffset].toOptLong() ?: end
                    onProgress(offset)
                }
                is ApiResult.Refused -> {
                    val next = sent.error.extraData[DFILE.nextOffset].toOptLong()
                    when (sendFailure(sent.error.status, next != null)) {
                        SendFailure.resync -> {
                            // Counted as a retry, so a server that keeps disagreeing stops the upload rather than looping.
                            if (retryDelayMs(++attempt) == null) throw sent.error
                            offset = resumeOffset(next ?: 0L, size, chunkSize)
                            onProgress(minOf(next ?: 0L, size))
                        }
                        SendFailure.retry -> delay(retryDelayMs(++attempt) ?: throw sent.error)
                        SendFailure.stop -> throw sent.error
                    }
                }
                is ApiResult.Failed -> delay(retryDelayMs(++attempt) ?: throw sent.cause)
            }
        }
    }

    /** [send]'s answer, sent again after a wait when it had none, or the server failed; any other refusal thrown. */
    private suspend fun retried(send: suspend () -> ApiResult<Map<String, Any?>>): Map<String, Any?> {
        var attempt = 0
        while (true) {
            when (val sent = send()) {
                is ApiResult.Ok -> return sent.value
                is ApiResult.Refused ->
                    if (sendFailure(sent.error.status, false) == SendFailure.retry) delay(retryDelayMs(++attempt) ?: throw sent.error) else throw sent.error
                is ApiResult.Failed -> delay(retryDelayMs(++attempt) ?: throw sent.cause)
            }
        }
    }
}

/** The SHA-256 of [buffer], an `ArrayBuffer`, in hex: the browser's own digest, which the chunk is checked against. */
private suspend fun sha256Hex(buffer: dynamic): String {
    val digest = (js("crypto.subtle.digest('SHA-256', buffer)") as Promise<dynamic>).await()
    val bytes = js("new Uint8Array(digest)")
    val length = bytes.length as Int
    val out = StringBuilder(length * 2)
    for (i in 0 until length) {
        val b = bytes[i] as Int
        out.append("0123456789abcdef"[b ushr 4]).append("0123456789abcdef"[b and 0xF])
    }
    return out.toString()
}

/** The first bytes of [file], as many as the kernel's sniffer reads, to judge what it is before it is sent. */
private suspend fun headBytes(file: dynamic): ByteArray {
    val head = (file.slice(0, FileKindSniff.headSize).arrayBuffer() as Promise<dynamic>).await()
    return js("new Int8Array(head)").unsafeCast<ByteArray>()
}

/** A file's record as a field has it: the [view] once read, or [missing] when it cannot be read. */
class DataFileState(val view: DataFileView?, val missing: Boolean)

/**
 * [fileId]'s record, from [DataFileCache] or asked for: no view while it is being read, and [DataFileState.missing] when
 * it cannot be -- removed, or not the caller's to read.
 */
fun useDataFile(fileId: String?): DataFileState {
    var state by useState { DataFileState(fileId?.let { DataFileCache.get(it) }, false) }
    val latest = useRef<String>(null)
    useEffect(fileId) {
        latest.current = fileId
        if (fileId == null) {
            state = DataFileState(null, false)
            return@useEffect
        }
        DataFileCache.get(fileId)?.let {
            state = DataFileState(it, false)
            return@useEffect
        }
        state = DataFileState(null, false)
        dataFileScope.launch {
            val read = apiResult { DataFileApi.info(fileId) }
            // A field whose value changed meanwhile shows its new file, not the answer about the old one.
            if (latest.current != fileId) return@launch
            state = when (read) {
                is ApiResult.Ok -> DataFileState(read.value.also { DataFileCache.remember(it) }, false)
                else -> DataFileState(null, true)
            }
        }
    }
    return state
}

/** How a file a field holds is shown: its name, a link to it, what it is, and, for an image or a video, the thing itself. */
private fun react.ChildrenBuilder.dataFileShown(fileId: String, state: DataFileState) {
    val view = state.view
    when {
        state.missing -> div {
            className = ClassName("data-file-shown")
            span { className = ClassName("field-value"); +"File unavailable" }
            span { className = ClassName("type-hint"); +" · $fileId" }
        }
        view == null -> span { className = ClassName("type-hint"); +"Reading the file…" }
        else -> div {
            className = ClassName("data-file-shown")
            div {
                if (view.readable) {
                    a {
                        href = DataFileApi.contentUrl(view.fileId)
                        asDynamic()["target"] = "_blank"
                        asDynamic()["rel"] = "noopener"
                        +view.title
                    }
                } else {
                    span { className = ClassName("field-value"); +view.title }
                }
                val facts = listOfNotNull(
                    view.kind?.label, view.size?.let { byteSizeText(it) }, if (view.status == DFSTAT.uploading) "still uploading" else null,
                )
                if (facts.isNotEmpty()) span { className = ClassName("type-hint"); +" · ${facts.joinToString(" · ")}" }
            }
            if (view.readable) {
                when (view.kind?.family) {
                    FKFAM.image -> img {
                        className = ClassName("data-file-preview")
                        src = DataFileApi.contentUrl(view.fileId)
                        alt = view.title
                    }
                    // Its index is read, not the whole video: a range request, and seeking asks for more ranges.
                    FKFAM.video -> video {
                        className = ClassName("data-file-preview")
                        controls = true
                        asDynamic()["preload"] = "metadata"
                        src = DataFileApi.contentUrl(view.fileId)
                    }
                }
            }
        }
    }
}

external interface DataFileValueProps : Props {
    var fileId: String
}

/** A file a read-only form shows: its record and, for an image or a video, the thing itself. */
val DataFileValue = FC<DataFileValueProps> { props ->
    dataFileShown(props.fileId, useDataFile(props.fileId))
}

external interface DataFileFieldProps : Props {
    /** The field's schema: the kinds of file it takes, and how large. */
    var type: SchType
    /** The file's id the field holds, or null. */
    var value: String?
    var describedBy: String?
    var onEmit: (String?) -> Unit
    /** The field settled: a file uploaded, or removed. */
    var onCommit: (() -> Unit)?
}

/**
 * A form's file field (issue #1840): picks a file of a kind the field takes, judges it by its first bytes before
 * anything is sent, uploads it with its progress shown, and sets the field to its id once it is ready -- never before,
 * since a form naming a file still uploading is refused at its save. What is held shows as [DataFileValue] does.
 *
 * **An upload stopped part-way can be resumed** while the page stays open: the file's id and the browser's file are
 * kept, and Resume takes it up from where the server says it reached. A reload loses them, since a page cannot pick a
 * file again by itself, and the sweep (#1839) removes the upload left behind. The upload belongs to this component,
 * not to the form's values, which hold only a finished file's id.
 */
val DataFileField = FC<DataFileFieldProps> { props ->
    val vt = props.type
    val shown = useDataFile(props.value)
    var sent by useState<Long?>(null)
    var total by useState(0L)
    var problem by useState<DisplayError?>(null)
    var pending by useState<PendingUpload?>(null)
    val job = useRef<Job>(null)
    val begunId = useRef<String>(null)
    val reached = useRef(0L)

    /** Ends the upload of [fileId] on the server, dropping what it took; nothing waits for the answer. */
    fun discard(fileId: String?) {
        fileId?.let { dataFileScope.launch { apiResult { DataFileApi.abort(it) } } }
    }

    fun start(file: dynamic, kind: FileKind, resume: PendingUpload?) {
        problem = null
        pending = null
        total = (file.size as Number).toLong()
        reached.current = resume?.sent ?: 0L
        sent = resume?.sent ?: 0L
        begunId.current = resume?.fileId
        job.current = dataFileScope.launch {
            try {
                val done = DataFileApi.upload(
                    file, kind, resume,
                    onBegun = { begunId.current = it },
                    onProgress = { reached.current = it; sent = it },
                )
                DataFileCache.remember(done)
                sent = null
                props.onEmit(done.fileId)
                props.onCommit?.invoke()
            } catch (e: CancellationException) {
                sent = null
                throw e
            } catch (e: Throwable) {
                sent = null
                problem = userFacingError(e)
                // Stopped by the connection or the server's own failure, a chunked upload can go on; a refusal cannot.
                val id = begunId.current
                val status = (e as? ApiError)?.status
                if (id != null && (e is ApiFailure || (status != null && sendFailure(status, false) == SendFailure.retry))) {
                    pending = PendingUpload(id, file, kind, reached.current ?: 0L)
                }
            }
        }
    }

    div {
        className = ClassName("data-file-field")
        asDynamic()["role"] = "group"
        props.describedBy?.let { asDynamic()["aria-describedby"] = it }
        props.value?.let { dataFileShown(it, shown) }
        val uploading = sent
        if (uploading != null) {
            div {
                className = ClassName("data-file-progress")
                Progress {
                    percent = uploadPercent(uploading, total)
                    status = "active"
                    size = "small"
                }
                Button {
                    type = "link"
                    onClick = {
                        job.current?.cancel()
                        sent = null
                        discard(begunId.current)
                    }
                    +"Cancel upload"
                }
            }
        } else {
            div {
                className = ClassName("data-file-actions")
                FilePickButton {
                    label = if (props.value == null) "Upload…" else "Replace…"
                    accept = dataFileAccept(vt)
                    busy = false
                    disabled = false
                    primary = false
                    onPicked = { file, size ->
                        discard(pending?.fileId)
                        pending = null
                        dataFileScope.launch {
                            val judged = judgePick(vt, headBytes(file.asDynamic()), size)
                            val kind = judged.kind
                            if (kind == null) {
                                problem = DisplayError.expected(judged.problem ?: "This file cannot be uploaded here.")
                            } else {
                                start(file.asDynamic(), kind, null)
                            }
                        }
                    }
                }
                if (props.value != null) {
                    Button {
                        type = "link"
                        onClick = {
                            props.onEmit(null)
                            props.onCommit?.invoke()
                        }
                        +"Remove"
                    }
                }
            }
        }
        pending?.let { stopped ->
            div {
                className = ClassName("data-file-actions")
                span { className = ClassName("type-hint"); +"The upload stopped at ${uploadPercent(stopped.sent, total)}%." }
                Button {
                    type = "link"
                    onClick = { start(stopped.file, stopped.kind, stopped) }
                    +"Resume"
                }
                Button {
                    type = "link"
                    onClick = {
                        discard(stopped.fileId)
                        pending = null
                        problem = null
                    }
                    +"Discard"
                }
            }
        }
        problem?.let { errorText(it) }
        span { className = ClassName("type-hint"); +dataFileHint(vt) }
    }
}
