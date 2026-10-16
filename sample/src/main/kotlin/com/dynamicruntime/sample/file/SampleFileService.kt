package com.dynamicruntime.sample.file

import com.dynamicruntime.common.context.KdrCxt
import com.dynamicruntime.common.endpoint.SchModule
import com.dynamicruntime.common.endpoint.schemaModule
import com.dynamicruntime.common.exception.EXC
import com.dynamicruntime.common.exception.KdrException
import com.dynamicruntime.common.filestore.FileStoreService
import com.dynamicruntime.common.http.request.ContentData
import com.dynamicruntime.common.schema.SCT

/**
 * The sample app's file endpoints: demonstrate the runtime's file endpoints (issue #96) end to end — upload a
 * file, list what has been uploaded, download one back — kept in the runtime's file store (issue #1144), which
 * is how an application keeps an upload: `FileStoreService.put` under a key it composes, in a bucket its component
 * declared ([SFK]).
 *
 * The endpoints are all [com.dynamicruntime.common.endpoint.EndpointKind.file] — the kind that says "this one
 * trades in file content, not JSON" — except `list`, which is an ordinary list of metadata.
 *
 * Being a sample, it has no auth, no quota and no cleanup; see [SFK] for why the ids are nonetheless
 * unguessable.
 */
object SampleFileService {
    /**
     * The file types and endpoints, contributed by the `SampleComponent`, in the `file` namespace.
     *
     * `upload` takes `multipart/form-data` — its `file` field is declared `binaryContent()`, which is
     * OpenAPI's `type: string, format: binary` — and answers with the stored file's metadata under
     * `results`. `download` returns the file itself as the response body, with no envelope. `list` is an
     * ordinary list endpoint over the metadata, so the catalog can find something to download.
     */
    fun schema(cxt: KdrCxt): SchModule = schemaModule(cxt, "sample.file") {
        type("FileInfo") {
            type = SCT.kObject
            property(SF.id, "Unique id of the stored file; use it to download.", required = true)
            property(SF.fileName, "The name the file was uploaded under.", required = true)
            property(SF.mimeType, "The file's content type, as claimed at upload.", required = true)
            property(SF.size, "The file's size in bytes.", required = true) { type = SCT.integer }
            property(SF.uploaded, "When the file was uploaded.", required = true) { dateTime() }
        }

        fileUploadEndpoint(
            "/demo/file/upload",
            "Upload a file; answers with the stored file's metadata.",
            outputRef = "FileInfo",
            inputFields = {
                field(SF.file, "The file to upload.", required = true) { binaryContent() }
            },
        ) { c, request ->
            // The binary field's value is a ContentData -- the validator leaves it alone rather than
            // coercing it to a string (see SFMT.binary).
            val content = request[SF.file] as? ContentData
                ?: throw KdrException.mkInput("No file was supplied in the '${SF.file}' field.")
            val id = SFK.newId()
            val meta = FileStoreService.get(c).put(c, SFK.bucket, SFK.keyOf(id), content)
            SampleFileInfo.of(id, meta).toJsonMap()
        }

        listEndpoint(
            "/demo/file/list",
            "List the uploaded files, newest first.",
            outputRef = "FileInfo",
        ) { c, _ ->
            // The store answers in key order, which for random ids is no order at all; newest first is the sample's.
            FileStoreService.get(c).list(c, SFK.bucket, SFK.uploadsPrefix)
                .mapNotNull { entry -> SFK.idOf(entry.key)?.let { SampleFileInfo.of(it, entry.meta) } }
                .sortedByDescending { it.uploaded }
                .map { it.toJsonMap() }
        }

        fileDownloadEndpoint(
            "/demo/file/download",
            "Download a previously uploaded file by id; the response body is the file itself.",
            inputFields = {
                field(SF.id, "Id of the file to download (from upload or list).", required = true)
            },
        ) { c, request ->
            val id = request[SF.id] as? String ?: throw KdrException.mkInput("No file id was supplied.")
            FileStoreService.get(c).get(c, SFK.bucket, SFK.keyOf(id))?.toContentData(inLine = false)
                ?: throw KdrException("No file with id '$id'.", null, EXC.notFound)
        }
    }
}
