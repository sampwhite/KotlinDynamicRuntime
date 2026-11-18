package com.dynamicruntime.common.datafile

import com.dynamicruntime.common.context.KdrCxt
import com.dynamicruntime.common.endpoint.EP
import com.dynamicruntime.common.endpoint.HttpMethod
import com.dynamicruntime.common.endpoint.SchModule
import com.dynamicruntime.common.endpoint.schemaModule
import com.dynamicruntime.common.exception.KdrException
import com.dynamicruntime.common.filekind.FileKinds
import com.dynamicruntime.common.http.request.ContentData
import com.dynamicruntime.common.schema.SCT
import com.dynamicruntime.common.util.toOptLong
import com.dynamicruntime.common.util.toOptStr

/** The namespace of the data-file endpoints' types. */
private const val namespace = "kdr.${DFILE.section}"


/**
 * The data-file endpoints (issue #1837), in the `files` section, which any signed-in user reaches: a small file's
 * upload in one request (#1840); a larger one's begin, chunks -- the last of which finishes it (#1840) -- abort, and
 * status; a file's record, its content a range at a time, which forms use it (#1838), and its removal. How far a caller
 * reaches is their read scope, and an upload is its owner's alone ([DataFileService]).
 *
 * Theme: #DFILE.
 */
fun dataFileSchema(cxt: KdrCxt): SchModule = schemaModule(cxt, namespace) {
    type(DFILE.recordType) {
        type = SCT.kObject
        description = "A file uploaded for a form's data: what it is, and how far it has come."
        property(DFILE.fileId, "The file's id, which a form's file field names.", required = true)
        property(DFILE.name, "The name it was uploaded under.")
        property(DFILE.kind, "Its kind, told by its bytes.", required = true) { FileKinds.kinds.forEach { option(it.id, it.label) } }
        property(DFILE.mimeType, "The type it is served as.", required = true)
        property(DFILE.size, "Its size in bytes; while uploading, the size it said it would be.", required = true) { type = SCT.integer }
        property(DFILE.hash, "The SHA-256 of its bytes, once its upload has finished.")
        property(DFILE.status, "Where it is: uploading, or ready to be read.", required = true) { DFSTAT.all.forEach { option(it) } }
        property(DFILE.uploadedAt, "When its upload began.") { dateTime() }
        property(DFILE.ownerId, "The user who uploaded it.", required = true) { type = SCT.integer }
    }
    type(DFILE.uploadType) {
        type = SCT.kObject
        description = "An upload under way: how to send the rest of it."
        property(DFILE.fileId, "The file's id.", required = true)
        property(DFILE.status, "Where the file is.", required = true) { DFSTAT.all.forEach { option(it) } }
        property(DFILE.chunkSize, "How many bytes each chunk is, all but the last.") { type = SCT.integer }
        property(DFILE.declaredSize, "How many bytes the file is.") { type = SCT.integer }
        property(DFILE.nextOffset, "Where the next chunk begins: the bytes taken so far.") { type = SCT.integer }
        property(DFILE.expiresAt, "When the upload is no longer taken.") { dateTime() }
        property(DFILE.file, "The file's record, once the chunk completing it has finished the upload.") { ref(DFILE.recordType) }
    }
    type(DFILE.useType) {
        type = SCT.kObject
        description = "A field of a form that names a file."
        property(DFILE.formId, "The form's id.", required = true)
        property(DFILE.traitId, "The trait of the entry holding the field.", required = true)
        property(DFILE.fieldPath, "Where in the entry's data the field is.", required = true)
    }
    type(DFILE.usedByType) {
        type = SCT.kObject
        description = "Which live forms use a file."
        property(DFILE.fileId, "The file's id.", required = true)
        property(DFILE.usedBy, "Each field of a live form you may read that names it.", required = true) {
            type = SCT.array
            items { ref(DFILE.useType) }
        }
        property(DFILE.hiddenForms, "How many live forms you may not read use it too.", required = true) { type = SCT.integer }
    }
    type(DFILE.goneType) {
        type = SCT.kObject
        property(DFILE.fileId, "The file's id.", required = true)
        property(DFILE.removed, "Whether there was a file, or an upload, to remove.", required = true) { type = SCT.boolean }
    }

    fileChunkEndpoint(
        DFILE.uploadPath,
        "Uploads a whole file for a form's data in one request (issue #1840): its bytes as the request's raw body, sent " +
            "as application/octet-stream, at most ${DFILE.chunkBytes} bytes. Answers the file's record, ready. Refused as " +
            "an upload in chunks is, and a larger file is uploaded in chunks (`${DFILE.beginPath}`).",
        outputRef = DFILE.recordType,
        maxBodyBytes = DFILE.chunkBytes,
        inputFields = {
            field(DFILE.name, "The file's name, as it was picked.")
            field(DFILE.mimeType, "The type the file is sent as; its bytes must be that kind.", required = true)
        },
    ) { c, request ->
        val body = request[EP.rawBody] as? ContentData ?: throw KdrException.mkInput("A file is the request's body.")
        DataFileService.uploadWhole(c, request[DFILE.name].toOptStr(), request[DFILE.mimeType].toOptStr().orEmpty(), body.contentBytes())
    }

    itemEndpoint(
        DFILE.beginPath,
        "Begins an upload of a file for a form's data in chunks (issue #1837), answering how to send it: chunks of " +
            "`chunkSize` bytes in order, each to `${DFILE.chunkPath}`; the last finishes the upload. Refused for a type no " +
            "file may be, a size past its kind's largest, or a plan with no room for it.",
        HttpMethod.POST,
        outputRef = DFILE.uploadType,
        inputFields = {
            field(DFILE.name, "The file's name, as it was picked.")
            field(DFILE.mimeType, "The type the file is sent as; its bytes must be that kind.", required = true)
            field(DFILE.declaredSize, "The file's size in bytes.", required = true) { type = SCT.integer }
        },
    ) { c, request ->
        DataFileService.begin(c, request[DFILE.name].toOptStr(), request[DFILE.mimeType].toOptStr().orEmpty(), request[DFILE.declaredSize].toOptLong() ?: 0L)
    }

    fileChunkEndpoint(
        DFILE.chunkPath,
        "Takes one chunk of an upload as the request's raw body, sent as application/octet-stream: the next one, at the " +
            "offset the upload has reached, with its SHA-256. A chunk taken already is answered again; any other is a 409 " +
            "saying where the upload is (`${DFILE.nextOffset}`). The chunk completing the file finishes the upload: checks " +
            "the file is the kind it was sent as -- an MP4's video must be H.264 -- and answers its record under " +
            "`${DFILE.file}`. A file that fails is removed, and the refusal says why; a finish that failed part-way is " +
            "taken up again by sending that chunk again.",
        outputRef = DFILE.uploadType,
        maxBodyBytes = DFILE.chunkBytes,
        inputFields = {
            field(DFILE.fileId, "The file being uploaded.", required = true)
            field(DFILE.offset, "Where in the file the chunk begins.", required = true) { type = SCT.integer }
            field(DFILE.chunkHash, "The chunk's SHA-256, in hex.", required = true)
        },
    ) { c, request ->
        val body = request[EP.rawBody] as? ContentData ?: throw KdrException.mkInput("A chunk is the request's body.")
        DataFileService.chunk(
            c, request[DFILE.fileId].toOptStr().orEmpty(), request[DFILE.offset].toOptLong() ?: -1L,
            request[DFILE.chunkHash].toOptStr().orEmpty(), body.contentBytes(),
        )
    }

    generalEndpoint(
        DFILE.abortPath,
        "Ends an upload without a file, dropping what was sent.",
        HttpMethod.POST,
        outputRef = DFILE.goneType,
        inputFields = { field(DFILE.fileId, "The file being uploaded.", required = true) },
    ) { c, request ->
        val fileId = request[DFILE.fileId].toOptStr().orEmpty()
        mapOf(DFILE.fileId to fileId, DFILE.removed to DataFileService.abort(c, fileId))
    }

    itemEndpoint(
        DFILE.statusPath,
        "Where an upload has reached, to resume it after a lost connection.",
        HttpMethod.GET,
        outputRef = DFILE.uploadType,
        inputFields = { field(DFILE.fileId, "The file being uploaded.", required = true) },
    ) { c, request -> DataFileService.status(c, request[DFILE.fileId].toOptStr().orEmpty()) }

    itemEndpoint(
        DFILE.infoPath,
        "What a file is: its name, kind, size, hash, and status.",
        HttpMethod.GET,
        outputRef = DFILE.recordType,
        inputFields = { field(DFILE.fileId, "The file.", required = true) },
    ) { c, request -> DataFileService.info(DataFileService.viewable(c, request[DFILE.fileId].toOptStr().orEmpty())) }

    itemEndpoint(
        DFILE.usedByPath,
        "Which live forms use a file: each field of a form you may read that names it, and how many forms you may not " +
            "read use it too. A file a live form uses is not removed.",
        HttpMethod.GET,
        outputRef = DFILE.usedByType,
        inputFields = { field(DFILE.fileId, "The file.", required = true) },
    ) { c, request -> DataFileLinks.usedBy(c, DataFileService.viewable(c, request[DFILE.fileId].toOptStr().orEmpty())) }

    fileDownloadEndpoint(
        DFILE.contentPath,
        "A file's content, a range at a time: an image or a video shown in place, anything else a download. Honors " +
            "`Range`, `If-Range`, and `If-None-Match`.",
        inputFields = { field(DFILE.fileId, "The file.", required = true) },
    ) { c, request -> DataFileService.content(c, request[DFILE.fileId].toOptStr().orEmpty()) }

    generalEndpoint(
        DFILE.deletePath,
        "Removes a file, aborting its upload if one is under way. Its owner may, and an administrator of its client. " +
            "Refused while a live form's field names it.",
        HttpMethod.DELETE,
        outputRef = DFILE.goneType,
        inputFields = { field(DFILE.fileId, "The file.", required = true) },
    ) { c, request ->
        val fileId = request[DFILE.fileId].toOptStr().orEmpty()
        mapOf(DFILE.fileId to fileId, DFILE.removed to DataFileService.delete(c, fileId))
    }
}
