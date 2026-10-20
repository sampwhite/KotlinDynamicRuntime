package com.dynamicruntime.common.filestore

import com.dynamicruntime.common.content.HashedFileServing
import com.dynamicruntime.common.context.KdrCxt
import com.dynamicruntime.common.endpoint.ETAG
import com.dynamicruntime.common.endpoint.HttpMethod
import com.dynamicruntime.common.endpoint.ListPage
import com.dynamicruntime.common.endpoint.SchModule
import com.dynamicruntime.common.endpoint.schemaModule
import com.dynamicruntime.common.exception.EXC
import com.dynamicruntime.common.exception.KdrException
import com.dynamicruntime.common.http.request.ContentData
import com.dynamicruntime.common.http.request.SECT
import com.dynamicruntime.common.operator.FSB
import com.dynamicruntime.common.operator.OPS
import com.dynamicruntime.common.schema.SCT
import com.dynamicruntime.common.util.sha256Hex
import com.dynamicruntime.common.util.toOptStr
import com.dynamicruntime.common.webasset.WebAssetCheck

/**
 * The operator's browser over the file store (issue #1297): the buckets, a bucket folder by folder, and any file as
 * a download -- what Linode's console shows of a bucket, for whichever store the deployment runs on. In the
 * `operator` section ([SECT.operator]: the operator level, and `allClients`), and registered app-only, since an
 * edge keeps no files.
 *
 * **A raw view.** It reads the provider itself ([FileStoreService.store]), past the rule the service's own calls
 * keep that a client's files are reached through [FileStoreService.forOwner]: that rule is for application code
 * storing files in the right place, and an operator sees every folder. What it shows is what the store holds, as
 * [FileStoreCheck] would see it: a file missing either half is listed flagged, not hidden.
 *
 * **Read-only.** Nothing here writes or removes: `CheckFileStore --repair` is what removes what a write left.
 */
fun fileStoreOperatorSchema(cxt: KdrCxt): SchModule = schemaModule(cxt, "kdr.fileStore") {
    type(FSB.bucketTypeName) {
        type = SCT.kObject
        property(FSB.name, "The bucket's name, as the application calls it.", required = true)
        property(FSB.description, "What the component that declared it keeps there.", required = true)
    }
    type(FSB.storeTypeName) {
        type = SCT.kObject
        property(FSB.provider, "Which kind of store this node keeps files in: memory, local or s3.", required = true)
        property(FSB.location, "Where it keeps them, in words: the directory, or the S3 endpoint and region.", required = true)
    }
    listEndpoint(
        OPS.fileStoreBucketsPath,
        "The file store's buckets -- every one a component declared -- and, in the summary, what the store is.",
        outputRef = FSB.bucketTypeName,
        noLimit = true,
        summaryRef = FSB.storeTypeName,
        tags = setOf(ETAG.internal),
    ) { c, _ ->
        val service = FileStoreService.get(c)
        val buckets = service.declaredBuckets().sortedBy { it.name }.map { mapOf(FSB.name to it.name, FSB.description to it.description) }
        ListPage(buckets, buckets.size, false, summary = mapOf(FSB.provider to service.providerType, FSB.location to service.store.location))
    }

    FileMeta.defineInfoType(this)
    type(FSB.fileTypeName) {
        type = SCT.kObject
        property(FSB.key, "The file's key in the bucket.", required = true)
        property(FSB.name, "The key's last segment.", required = true)
        property(FSB.meta, "The store's record of the file, where it holds one.") { ref(FMETA.infoTypeName) }
        property(FSB.modifiedAt, "When any part of the file last changed.", required = true) { dateTime() }
        property(FSB.state, "Whether the file is whole, or missing its record or its content.", required = true) {
            option(FSB.stateOk, "A file")
            option(FSB.stateNoMeta, "Content with no record: a write that stopped part-way")
            option(FSB.stateNoContent, "A record with no content")
            option(FSB.stateUnreadableMeta, "Content beside a record that cannot be read")
        }
    }
    type(FSB.listingTypeName) {
        type = SCT.kObject
        property(FSB.bucket, "The bucket listed.", required = true)
        property(FSB.prefix, "The folder listed; absent for the top of the bucket.")
        property(FSB.folders, "The folders directly under it, each ending in '/'.", required = true) {
            type = SCT.array
            items { type = SCT.string }
        }
        property(FSB.files, "The files directly under it.", required = true) {
            type = SCT.array
            items { ref(FSB.fileTypeName) }
        }
        property(FSB.more, "Whether the folder holds more than was answered.", required = true) { type = SCT.boolean }
    }
    generalEndpoint(
        OPS.fileStoreListPath,
        "The folders and files directly under one folder of a bucket. Folders are read off the keys, as the store " +
            "keeps none; a file's record is folded into its row, and a file missing its record or its content is " +
            "listed flagged. A folder holding more than the limit is cut, folders first, and says so.",
        HttpMethod.GET,
        outputRef = FSB.listingTypeName,
        inputFields = {
            field(FSB.bucket, "The bucket to list.", required = true)
            field(FSB.prefix, "The folder to list, as its key prefix ending in '/'; empty, or absent, for the top of the bucket.")
            field(FSB.limit, "The most folders and files to answer (${FSB.defaultLimit} unless said, at most ${FSB.maxLimit}).") { type = SCT.integer }
        },
        tags = setOf(ETAG.internal),
    ) { c, request ->
        val service = FileStoreService.get(c)
        val bucket = declaredBucket(service, request[FSB.bucket].toOptStr().orEmpty())
        val prefix = FNAME.checkKeyPrefix(FileStoreBrowse.folderPrefix(request[FSB.prefix].toOptStr()))
        val limit = (request[FSB.limit] as? Number)?.toInt() ?: FSB.defaultLimit
        if (limit !in 1..FSB.maxLimit) {
            throw KdrException("A listing's limit must be from 1 to ${FSB.maxLimit}, not $limit: each file shown costs a read of its record.", null, EXC.badInput)
        }
        val store = service.store
        FileStoreBrowse.folderListing(bucket, prefix, store.objects(c, bucket, prefix), limit, store::tmpKeyOf) { key ->
            store.meta(c, bucket, key)
        }.toJsonMap()
    }

    fileDownloadEndpoint(
        OPS.fileStoreDownloadPath,
        "One file of a bucket, as a download under the name it was stored under, else its key's last segment with " +
            "its kind's extension -- checked against the hash its record holds, as every read is, so a damaged file " +
            "is refused rather than handed out as good. A 404 when there is no such file.",
        inputFields = {
            field(FSB.bucket, "The bucket the file is in.", required = true)
            field(FSB.key, "The file's key in it.", required = true)
        },
        tags = setOf(ETAG.internal),
    ) { c, request ->
        val service = FileStoreService.get(c)
        val bucket = declaredBucket(service, request[FSB.bucket].toOptStr().orEmpty())
        val key = FNAME.checkKey(request[FSB.key].toOptStr().orEmpty())
        val file = wholeFile(c, service.store, bucket, key) ?: throw KdrException("There is no file '$key' in the bucket '$bucket'.", null, EXC.notFound)
        c.request?.webRequest?.let { HashedFileServing.downloadHeaders(it, file, null) }
        ContentData(file.bytes, file.meta.mimeType, saveAsFilename = downloadName(key, file.meta), inLine = false, hash = file.meta.hash)
    }

    fileMetaEndpoint(
        OPS.fileStoreDownloadMetaPath,
        "What one file of a bucket is, without the file: its record. The download's inputs, and the same 404.",
        inputFields = {
            field(FSB.bucket, "The bucket the file is in.", required = true)
            field(FSB.key, "The file's key in it.", required = true)
        },
        tags = setOf(ETAG.internal),
    ) { c, request ->
        val service = FileStoreService.get(c)
        val bucket = declaredBucket(service, request[FSB.bucket].toOptStr().orEmpty())
        service.store.meta(c, bucket, FNAME.checkKey(request[FSB.key].toOptStr().orEmpty()))
    }
}

/**
 * What a download of the file at [key] is saved as: the name it was stored under, which carries its extension; else
 * the key's last segment with the extension of its kind, where the kind is one the application knows -- a bare hash
 * saves as nothing a system can open.
 */
private fun downloadName(key: String, meta: FileMeta): String =
    meta.fileName ?: listOfNotNull(key.substringAfterLast('/'), WebAssetCheck.declaredKind(meta.mimeType)?.extension).joinToString(".")

/** [bucket], when a component declared it; else a 404 naming the buckets there are -- an operator may know them. */
private fun declaredBucket(service: FileStoreService, bucket: String): String {
    val declared = service.declaredBuckets().map { it.name }
    if (bucket !in declared) {
        throw KdrException("No bucket '$bucket'; the buckets are ${declared.joinToString().ifEmpty { "none" }}.", null, EXC.notFound)
    }
    return bucket
}

/**
 * The file at [key] as [FileStoreService.FileStoreView.get] reads one: its content checked against the hash its
 * record holds, read again a few times across a replace, and refused as damaged when the two go on differing.
 */
private fun wholeFile(cxt: KdrCxt, store: FileStore, bucket: String, key: String): StoredFile? {
    var read = store.get(cxt, bucket, key) ?: return null
    for (pauseMs in FileStoreService.rereadPausesMs) {
        if (read.bytes.sha256Hex() == read.meta.hash) return read
        Thread.sleep(pauseMs)
        read = store.get(cxt, bucket, key) ?: return null
    }
    if (read.bytes.sha256Hex() == read.meta.hash) return read
    throw KdrException.mkFileIo(
        "The file '$key' in the bucket '$bucket' does not match its record (its content hashes to something other " +
            "than the recorded ${read.meta.hash}).",
        null, EXC.internalError,
    )
}
