package com.dynamicruntime.common.webasset

import com.dynamicruntime.common.content.HashedFileServing
import com.dynamicruntime.common.context.KdrCxt
import com.dynamicruntime.common.endpoint.HttpMethod
import com.dynamicruntime.common.endpoint.SchModule
import com.dynamicruntime.common.endpoint.schemaModule
import com.dynamicruntime.common.exception.EXC
import com.dynamicruntime.common.exception.KdrException
import com.dynamicruntime.common.filestore.FMETA
import com.dynamicruntime.common.filestore.FileMeta
import com.dynamicruntime.common.filestore.fileMetaEndpoint
import com.dynamicruntime.common.gedra.overseenClient
import com.dynamicruntime.common.gedra.overseenClientField
import com.dynamicruntime.common.http.request.ContentData
import com.dynamicruntime.common.schema.SCT
import com.dynamicruntime.common.schema.SchTypeBuilder
import com.dynamicruntime.common.user.UADEP
import com.dynamicruntime.common.util.toOptStr

/**
 * A client's web assets, for its administrator (issue #1130): what each slot holds, uploading an image into one,
 * emptying one, and the image itself.
 *
 * In the **`clientAdmin`** section, each acting on the caller's own client or, for an administrator who sees every
 * client, a named one -- the rule the other client pages enforce (`overseenClient`), so a page calls these without
 * branching on who is asking.
 *
 * **The listing is the meta-only read**: it answers the metadata the file store keeps beside each image and never
 * the image, so a page says what is set -- its type, its size, who uploaded it and when -- without fetching one.
 * The image is the download beside it, which exists for the page of a client that is not the caller's own: the
 * static address everybody else's browser draws from serves only the caller's own client's.
 */
fun clientWebSchema(cxt: KdrCxt): SchModule = schemaModule(cxt, WAS.namespace) {
    FileMeta.defineInfoType(this)

    type(WAS.slotTypeName) {
        type = SCT.kObject
        description = "One slot of a client's web assets: what it is for, what it takes, and the image it holds."
        property(WAS.slot, "Which slot.", required = true) { slotOptions() }
        property(WAS.label, "What a person calls the slot.", required = true)
        property(WAS.maxSize, "The most bytes its image may be.", required = true) { type = SCT.integer }
        property(WAS.mimeTypes, "The content types an upload to it may be sent as.", required = true) {
            type = SCT.array
            items { type = SCT.string }
        }
        property(WAS.kinds, "The kinds of image it takes, as a person names them.", required = true) {
            type = SCT.array
            items { type = SCT.string }
        }
        property(
            WAS.name,
            "The image by name and content, `<slot>:<hash>`, as the shell config carries it; absent when the slot is empty.",
        )
        property(WAS.file, "The metadata of the image the slot holds; absent when it is empty.") { ref(FMETA.infoTypeName) }
        property(WAS.uploadedByName, "Who uploaded the image, as they were called then; absent when that was not recorded.")
    }

    listEndpoint(
        UADEP.clientWeb,
        "A client's web assets (issue #1130): every slot -- the favicon, the brand mark -- with the metadata of the " +
            "image it holds and none of its content. A slot holding none is listed without one, and the client's " +
            "people then see the deployment's own image. A sandbox lists its parent's, which are what it shows.",
        outputRef = WAS.slotTypeName,
        // The slots are a fixed handful.
        noLimit = true,
        inputFields = { overseenClientField(WAS.client) },
        needsClientConfig = true,
    ) { c, request ->
        val assets = ClientWebAssetService.get(c)
        val client = assets.knownClient(c, overseenClient(c, request[WAS.client].toOptStr()))
        // The store's own answer, not this node's memo: the page that edits the slots shows what is there.
        val held = assets.slots(c, client, fresh = true)
        ClientWebAssetService.slotDefs.map { slotItem(it, held[it.slot]) }
    }

    fileUploadEndpoint(
        UADEP.clientWebUpload,
        "Uploads the image of one slot of a client's web assets (issue #1130), replacing the one there: a PNG, SVG, " +
            "ICO, JPEG or WebP, up to the slot's size. What the upload says it is must be what its content is; an SVG " +
            "must be well-formed and carry no script. It takes effect at once for everyone in the client -- an " +
            "upload is not part of the client's configuration, so there is no draft and no publish -- and is refused " +
            "for a sandbox, which shows its parent's.",
        outputRef = WAS.slotTypeName,
        needsClientConfig = true,
        inputFields = {
            overseenClientField(WAS.client)
            field(WAS.slot, "The slot the image is for.", required = true) { slotOptions() }
            field(WAS.file, "The image.", required = true) { binaryContent() }
        },
    ) { c, request ->
        val client = overseenClient(c, request[WAS.client].toOptStr())
        // A `file` part sent as a plain field is a value, so it passes for "present": it is here that it is no file.
        val content = request[WAS.file] as? ContentData ?: throw KdrException.mkInput("No file was sent.")
        val def = ClientWebAssetService.slotDef(request[WAS.slot].toOptStr().orEmpty())
        slotItem(def, ClientWebAssetService.get(c).upload(c, client, def.slot, content))
    }

    generalEndpoint(
        UADEP.clientWebRemove,
        "Empties one slot of a client's web assets (issue #1130), so its people see the deployment's own image " +
            "again, at once. Answers the slot as it now is; emptying one that held nothing changes nothing. Refused " +
            "for a sandbox, which shows its parent's.",
        HttpMethod.POST,
        outputRef = WAS.slotTypeName,
        needsClientConfig = true,
        inputFields = {
            overseenClientField(WAS.client)
            field(WAS.slot, "The slot to empty.", required = true) { slotOptions() }
        },
    ) { c, request ->
        val client = overseenClient(c, request[WAS.client].toOptStr())
        val def = ClientWebAssetService.slotDef(request[WAS.slot].toOptStr().orEmpty())
        ClientWebAssetService.get(c).remove(c, client, def.slot)
        slotItem(def, null)
    }

    fileDownloadEndpoint(
        UADEP.clientWebDownload,
        "The image in one slot of a client's web assets (issue #1130), as a file: what an administrator looking at " +
            "another client's page previews, since the static address serves only the caller's own client's. A 404 " +
            "when the slot is empty, or the node knows no such client. Sent as a download, so opening its address " +
            "shows no page. An address that carries the hash of the image returned may be kept by the caller's own " +
            "browser, for the session that was allowed it; any other is never kept.",
        needsClientConfig = true,
        inputFields = {
            overseenClientField(WAS.client)
            field(WAS.slot, "The slot whose image to fetch.", required = true) { slotOptions() }
            field(WAS.hash, "The hash of the image expected, from the listing; with it the address names the image and the answer may be kept.")
        },
    ) { c, request ->
        val assets = ClientWebAssetService.get(c)
        val client = assets.knownClient(c, overseenClient(c, request[WAS.client].toOptStr()))
        val def = ClientWebAssetService.slotDef(request[WAS.slot].toOptStr().orEmpty())
        val file = assets.file(c, client, def.slot)
            ?: throw KdrException("Client '$client' has no ${def.label.lowercase()} of its own.", code = EXC.notFound)
        c.request?.webRequest?.let { HashedFileServing.downloadHeaders(it, file, request[WAS.hash].toOptStr()) }
        file.toContentData(inLine = false)
    }

    fileMetaEndpoint(
        UADEP.clientWebDownloadMeta,
        "What the image in one slot of a client's web assets is, without the image (issue #1131): its type, size " +
            "and hash, the name it was uploaded under, who uploaded it and when. The download's inputs and the same " +
            "404s: an empty slot, or a client the node does not know. Read from the store as it is now, not from " +
            "what this node last read of it.",
        needsClientConfig = true,
        inputFields = {
            overseenClientField(WAS.client)
            field(WAS.slot, "The slot whose image to describe.", required = true) { slotOptions() }
        },
    ) { c, request ->
        val assets = ClientWebAssetService.get(c)
        val client = assets.knownClient(c, overseenClient(c, request[WAS.client].toOptStr()))
        val def = ClientWebAssetService.slotDef(request[WAS.slot].toOptStr().orEmpty())
        assets.meta(c, client, def.slot)
            ?: throw KdrException("Client '$client' has no ${def.label.lowercase()} of its own.", code = EXC.notFound)
    }
}

/** The slots as a field's choices, each by the name a person calls it. */
private fun SchTypeBuilder.slotOptions() {
    for (def in ClientWebAssetService.slotDefs) option(def.slot, def.label)
}

/**
 * One slot as the listing answers it: what the slot is and takes, and -- when it holds an image -- [meta], the
 * image's name by content, and who uploaded it.
 */
fun slotItem(def: WebSlotDef, meta: FileMeta?): Map<String, Any?> = buildMap {
    put(WAS.slot, def.slot)
    put(WAS.label, def.label)
    put(WAS.maxSize, def.maxSize)
    put(WAS.mimeTypes, WebAssetCheck.acceptedMimeTypes)
    put(WAS.kinds, WebAssetCheck.kinds.map { it.label })
    if (meta != null) {
        put(WAS.name, WAS.versionedName(def.slot, meta.hash))
        put(WAS.file, meta.toJsonMap())
        meta.extra[WAS.uploadedByName].toOptStr()?.let { put(WAS.uploadedByName, it) }
    }
}
