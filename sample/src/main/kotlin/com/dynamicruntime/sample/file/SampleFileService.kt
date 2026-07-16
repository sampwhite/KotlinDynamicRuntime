package com.dynamicruntime.sample.file

import com.dynamicruntime.common.context.KdrCxt
import com.dynamicruntime.common.endpoint.SchModule
import com.dynamicruntime.common.endpoint.schemaModule
import com.dynamicruntime.common.exception.EXC
import com.dynamicruntime.common.exception.KdrException
import com.dynamicruntime.common.http.request.ContentData
import com.dynamicruntime.common.schema.SCT
import com.dynamicruntime.common.startup.ServiceInitializer

/**
 * The sample app's file service: demonstrates the runtime's file endpoints (issue #96) end to end — upload a
 * file, list what has been uploaded, download one back. Follows [com.dynamicruntime.sample.todo.TodoService]:
 * the service owns the store and declares its endpoints inline in [schema], and `SampleComponent` wires them
 * in.
 *
 * The endpoints are all [com.dynamicruntime.common.endpoint.EndpointKind.file] — the kind that says "this one
 * trades in file content, not JSON" — except `list`, which is an ordinary list of metadata.
 *
 * Being a sample, the store keeps files on disk under the workspace with no auth, no quota and no cleanup; see
 * [SampleFileStore] for what that means and why the ids are nonetheless unguessable.
 */
class SampleFileService : ServiceInitializer {
    override val serviceName: String = SampleFileService.serviceName

    /** The instance's file store, at `<workspace>/sampleFiles`. */
    val store: SampleFileStore = SampleFileStore.forWorkspace()

    @Suppress("ConstPropertyName")
    companion object {
        const val serviceName = "SampleFileService"

        fun get(cxt: KdrCxt): SampleFileService =
            cxt.instanceConfig.get(serviceName) as? SampleFileService
                ?: throw KdrException("SampleFileService is not available.")

        /**
         * The file types and endpoints, contributed by the `SampleComponent`, in the `file` namespace.
         *
         * `upload` takes `multipart/form-data` — its `file` field is declared `binaryContent()`, which is
         * OpenAPI's `type: string, format: binary` — and answers with the stored file's metadata under
         * `results`. `download` returns the file itself as the response body, with no envelope. `list` is an
         * ordinary list endpoint over the metadata, so the catalog can find something to download.
         */
        fun schema(cxt: KdrCxt): SchModule = schemaModule(cxt, "file") {
            type("FileInfo") {
                type = SCT.kObject
                property(SF.id, "Unique id of the stored file; use it to download.", required = true)
                property(SF.fileName, "The name the file was uploaded under.", required = true)
                property(SF.mimeType, "The file's content type, as claimed at upload.", required = true)
                property(SF.size, "The file's size in bytes.", required = true) { type = SCT.integer }
                property(SF.uploaded, "When the file was uploaded.", required = true) { dateTime() }
            }

            fileUploadEndpoint(
                "/file/upload",
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
                get(c).store.save(content).toJsonMap()
            }

            listEndpoint(
                "/file/list",
                "List the uploaded files, newest first.",
                outputRef = "FileInfo",
            ) { c, _ ->
                get(c).store.list().map { it.toJsonMap() }
            }

            fileDownloadEndpoint(
                "/file/download",
                "Download a previously uploaded file by id; the response body is the file itself.",
                inputFields = {
                    field(SF.id, "Id of the file to download (from upload or list).", required = true)
                },
            ) { c, request ->
                val id = request[SF.id] as? String ?: throw KdrException.mkInput("No file id was supplied.")
                get(c).store.load(id)
                    ?: throw KdrException("No file with id '$id'.", null, EXC.notFound)
            }
        }
    }
}
