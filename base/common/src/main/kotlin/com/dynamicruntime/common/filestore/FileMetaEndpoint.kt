package com.dynamicruntime.common.filestore

import com.dynamicruntime.common.context.KdrCxt
import com.dynamicruntime.common.endpoint.HttpMethod
import com.dynamicruntime.common.endpoint.InputFieldsBuilder
import com.dynamicruntime.common.endpoint.SchModuleBuilder
import com.dynamicruntime.common.exception.EXC
import com.dynamicruntime.common.exception.KdrException

/**
 * An endpoint answering **what a stored file is, without the file** (issue #1131): its [FileMeta] -- type, size,
 * SHA-256, the name it was uploaded under, who stored it and when -- under `item`, and a 404 when [handler] finds
 * no such file. What a caller asks to learn whether a file is there, whether it changed (its hash), or whether
 * it is worth fetching, without fetching it.
 *
 * **It sits beside a download**, at that download's path with `/meta` after it, with the same
 * inputs, in the same section, behind the same check of the caller -- since who may read a file's metadata is who
 * may download the file. The file store has no access rules of its own (a bucket and a key say nothing about whose
 * a file is), so there is deliberately no endpoint reading metadata by bucket and key: the handler composes the key
 * from what it has checked, exactly as the download's does, and typically answers [FileStoreService.meta], which
 * reads the metadata kept beside the file and never the file.
 *
 * **The `FileMeta` type comes with it**: the builder declares it in the module, so a surface that offers a
 * meta-only read cannot leave its answer described by nothing. A module that also answers `FileMeta` elsewhere --
 * a listing's rows -- may declare it itself as well; declaring it again writes the same definition, so it is one
 * type either way.
 *
 * [handler] answers the metadata, or null for "no such file", or throws its own refusal -- a 404 that says *why*
 * there is no file, as the download beside it does, reads better than the builder's own.
 */
fun SchModuleBuilder.fileMetaEndpoint(
    path: String,
    description: String,
    inputRef: String? = null,
    inputFields: (InputFieldsBuilder.() -> Unit)? = null,
    forTestingOnly: Boolean = false,
    /** Part of the published API (issue #433). Advertisement, never access -- see `KdrEndpoint.publicApi`. */
    publicApi: Boolean = false,
    /** Free-form tags for slicing the catalog (issue #433); no runtime effect. */
    tags: Set<String> = emptySet(),
    /** Sync this node's client configuration before serving (issue #618); see `KdrEndpoint.needsClientConfig`. */
    needsClientConfig: Boolean = false,
    handler: (cxt: KdrCxt, request: Map<String, Any?>) -> FileMeta?,
) {
    FileMeta.defineInfoType(this)
    itemEndpoint(
        path, description, HttpMethod.GET, outputRef = FMETA.infoTypeName, inputRef = inputRef, inputFields = inputFields,
        forTestingOnly = forTestingOnly, publicApi = publicApi, tags = tags, needsClientConfig = needsClientConfig,
    ) { c, request ->
        val meta = handler(c, request) ?: throw KdrException("There is no such file.", null, EXC.notFound)
        meta.toJsonMap()
    }
}
