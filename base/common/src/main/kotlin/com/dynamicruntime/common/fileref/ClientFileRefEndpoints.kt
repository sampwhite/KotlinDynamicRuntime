package com.dynamicruntime.common.fileref

import com.dynamicruntime.common.content.HashedFileServing
import com.dynamicruntime.common.context.KdrCxt
import com.dynamicruntime.common.endpoint.HttpMethod
import com.dynamicruntime.common.endpoint.SchModule
import com.dynamicruntime.common.endpoint.schemaModule
import com.dynamicruntime.common.exception.EXC
import com.dynamicruntime.common.exception.KdrException
import com.dynamicruntime.common.filestore.FMETA
import com.dynamicruntime.common.filestore.FileMeta
import com.dynamicruntime.common.gedra.CCT
import com.dynamicruntime.common.gedra.ClientService
import com.dynamicruntime.common.gedra.configOwnerOf
import com.dynamicruntime.common.gedra.EDM
import com.dynamicruntime.common.gedra.overseenClient
import com.dynamicruntime.common.gedra.overseenClientField
import com.dynamicruntime.common.http.request.ContentData
import com.dynamicruntime.common.schema.SCT
import com.dynamicruntime.common.user.UADEP
import com.dynamicruntime.common.util.toOptStr
import com.dynamicruntime.common.webasset.WebAssetCheck

/**
 * A client's file refs, for its administrator (issue #1153): what refs it has, uploading a file as one, removing
 * one, and the file itself.
 *
 * In the **`clientAdmin`** section, each acting on the caller's own client or, for an administrator who sees every
 * client, a named one -- the rule the other client pages enforce (`overseenClient`).
 *
 * **What is listed is what the client's administrator is working on**: for a client with a sandbox, the sandbox's
 * refs -- the latest revision, drafts included -- each saying whether the client's own people are served it yet
 * ([FREF.live]); for any other client, simply its refs, all of them live. The listing answers the file store's
 * record of each file and never the file. The download beside it is the file, for the page of a client that is not
 * the caller's own and for looking at a draft from outside the sandbox: the static address serves only the
 * caller's own client's, and only what that client runs.
 */
fun clientFileRefSchema(cxt: KdrCxt): SchModule = schemaModule(cxt, FREF.namespace) {
    FileMeta.defineInfoType(this)

    type(FREF.refTypeName) {
        type = SCT.kObject
        description = "One file ref of a client: a named file its stored configuration holds, by the hash of its bytes."
        property(CCT.refId, "The ref's id within its client.", required = true)
        property(CCT.hash, "The SHA-256 of the file's bytes.", required = true)
        property(FREF.name, "The ref by name and content, `<refId>:<hash>`: what the static root serves it as, at `/<appId>/ref/<name>`.", required = true)
        property(CCT.description, "What the file is for, in the administrator's words.") { maxLength = FREF.maxDescriptionLength }
        property(FREF.configName, "The stored configuration holding the ref's entry.")
        property(FREF.live, "Whether the client's own people are served this ref as listed; false for a draft only its sandbox shows.", required = true) {
            type = SCT.boolean
        }
        property(FREF.missing, "Whether the file store holds no such file, so the ref is answered as not found.", required = true) {
            type = SCT.boolean
        }
        property(FREF.file, "The file store's record of the file; absent when it is missing.") { ref(FMETA.infoTypeName) }
        property(FREF.uploadedByName, "Who uploaded the file, as they were called then; absent when that was not recorded.")
    }

    type(FREF.changeTypeName) {
        type = SCT.kObject
        description = "What an upload or a removal of a file ref did: where it landed, and how it took effect."
        property(FREF.client, "The client whose configuration took the change: the one named, or a sandbox's parent.", required = true)
        property(CCT.refId, "The ref.", required = true)
        property(FREF.configName, "The stored configuration the change landed in.", required = true)
        property(FREF.mode, "How it took effect: live for the client at once, or a draft its sandbox shows until it is published.", required = true) {
            option(EDM.live, "Live")
            option(EDM.draft, "Draft")
        }
        property(FREF.ref, "The ref as it now is; absent when the change removed it.") { ref(FREF.refTypeName) }
    }

    listEndpoint(
        UADEP.clientFiles,
        "A client's file refs (issue #1153): the named files its stored configuration holds, each with the file " +
            "store's record of the file and none of its content. For a client with a sandbox these are the " +
            "sandbox's -- the latest revision, drafts included -- each saying whether the client's own people are " +
            "served it yet.",
        outputRef = FREF.refTypeName,
        // Bounded by the cap a write holds a client to.
        noLimit = true,
        inputFields = { overseenClientField(FREF.client) },
        needsClientConfig = true,
    ) { c, request ->
        val refs = FileRefService.get(c)
        val client = knownClient(c, request)
        listedRefs(c, refs, client)
    }

    fileUploadEndpoint(
        UADEP.clientFilesUpload,
        "Uploads a file as one of a client's file refs (issue #1153), replacing what the ref named: an image -- PNG, " +
            "SVG, ICO, JPEG or WebP -- that is what it says it is, up to the size a ref takes. The bytes are stored " +
            "under their hash and the ref's entry written into the client's stored configuration, in one call. Live " +
            "at once for a client without a sandbox; for one with, a draft its sandbox shows until it is published.",
        outputRef = FREF.changeTypeName,
        needsClientConfig = true,
        inputFields = {
            overseenClientField(FREF.client)
            field(CCT.refId, "The ref's id: a variable name. An id the client already has replaces that ref's file.", required = true)
            field(FREF.file, "The file.", required = true) { binaryContent() }
            field(CCT.description, "What the file is for, in at most ${FREF.maxDescriptionLength} characters. Absent keeps the ref's description; blank clears it.") {
                emptyIsAbsent = false
                maxLength = FREF.maxDescriptionLength
            }
        },
    ) { c, request ->
        val refs = FileRefService.get(c)
        val client = knownClient(c, request)
        // A `file` part sent as a plain field is a value, so it passes for "present": it is here that it is no file.
        val content = request[FREF.file] as? ContentData ?: throw KdrException.mkInput("No file was sent.")
        val description = if (request.containsKey(CCT.description)) request[CCT.description].toOptStr().orEmpty() else null
        changeItem(c, refs, ClientFileRefEdit.upload(c, client, request[CCT.refId].toOptStr().orEmpty(), content, description))
    }

    generalEndpoint(
        UADEP.clientFilesRemove,
        "Removes one of a client's file refs (issue #1153), live or as a draft as an upload is. The file it named " +
            "is left in the file store: an older revision of the configuration still names it.",
        HttpMethod.POST,
        outputRef = FREF.changeTypeName,
        needsClientConfig = true,
        inputFields = {
            overseenClientField(FREF.client)
            field(CCT.refId, "The ref to remove.", required = true)
        },
    ) { c, request ->
        val refs = FileRefService.get(c)
        val client = knownClient(c, request)
        changeItem(c, refs, ClientFileRefEdit.remove(c, client, request[CCT.refId].toOptStr().orEmpty()))
    }

    fileDownloadEndpoint(
        UADEP.clientFilesDownload,
        "The file of one of a client's file refs (issue #1153), as the listing shows it: what an administrator " +
            "previews, on the page of a client that is not their own or of a draft from outside the sandbox. A 404 " +
            "when there is no such ref, or its file is missing. Sent as a download, so opening its address shows no " +
            "page. An address that carries the hash of the file returned may be kept by the caller's own browser.",
        needsClientConfig = true,
        inputFields = {
            overseenClientField(FREF.client)
            field(CCT.refId, "The ref whose file to fetch.", required = true)
            field(CCT.hash, "The hash of the file expected, from the listing; with it the address names the file and the answer may be kept.")
        },
    ) { c, request ->
        val refs = FileRefService.get(c)
        val client = knownClient(c, request)
        val refId = request[CCT.refId].toOptStr().orEmpty()
        val file = refs.forClient(refs.shownAs(c, client)).refs[refId]?.let { refs.read(c, it) }
            ?: throw KdrException("Client '$client' has no file ref '$refId' whose file is there.", code = EXC.notFound)
        c.request?.webRequest?.let { HashedFileServing.downloadHeaders(it, file, request[CCT.hash].toOptStr()) }
        // Offered under the ref's own id and its kind's extension, never the name it was uploaded under: that name
        // is the first uploader's, of whatever ref first held these bytes, and its extension can say anything.
        val extension = WebAssetCheck.declaredKind(file.meta.mimeType)?.extension
        ContentData(file.bytes, file.meta.mimeType, saveAsFilename = listOfNotNull(refId, extension).joinToString("."), inLine = false)
    }
}

/** The client a call acts on: the one it may ([overseenClient]), and one this node knows. */
private fun knownClient(cxt: KdrCxt, request: Map<String, Any?>): String =
    ClientService.get(cxt).requireKnown(overseenClient(cxt, request[FREF.client].toOptStr()))

/**
 * [client]'s refs as an administrator is shown them ([FileRefService.shownAs]): the sandbox's where it has one,
 * each marked by whether the client's own registry serves the same bytes under that id.
 */
fun listedRefs(cxt: KdrCxt, refs: FileRefService, client: String): List<Map<String, Any?>> {
    val running = refs.forClient(configOwnerOf(client)).refs
    return refs.forClient(refs.shownAs(cxt, client)).refs.values.map { refItem(cxt, refs, it, running[it.refId]?.hash == it.hash) }
}

/** One ref as the listing answers it; [live] is whether the client's own people are served these bytes under its id. */
fun refItem(cxt: KdrCxt, refs: FileRefService, ref: FileRef, live: Boolean): Map<String, Any?> {
    // Asked of the store even for a ref the load found missing: the file may have arrived since. A store that
    // cannot be asked fails the listing, rather than have it say of every file that it is missing.
    val meta = refs.metaOf(cxt, ref.owner, ref.hash)
    return buildMap {
        put(CCT.refId, ref.refId)
        put(CCT.hash, ref.hash)
        put(FREF.name, ref.name)
        ref.def.description?.let { put(CCT.description, it) }
        ref.def.configName?.let { put(FREF.configName, it) }
        put(FREF.live, live)
        put(FREF.missing, meta == null)
        if (meta != null) {
            put(FREF.file, meta.toJsonMap())
            meta.extra[FREF.uploadedByName].toOptStr()?.let { put(FREF.uploadedByName, it) }
        }
    }
}

/** What a change answers: where it landed, and the ref as its administrator is now shown it (none, once removed). */
private fun changeItem(cxt: KdrCxt, refs: FileRefService, result: ClientFileRefEdit.Result): Map<String, Any?> = buildMap {
    put(FREF.client, result.client)
    put(CCT.refId, result.refId)
    put(FREF.configName, result.configName)
    put(FREF.mode, result.mode)
    val running = refs.forClient(result.client).refs[result.refId]
    refs.forClient(result.shownAs).refs[result.refId]?.let { put(FREF.ref, refItem(cxt, refs, it, running?.hash == it.hash)) }
}
