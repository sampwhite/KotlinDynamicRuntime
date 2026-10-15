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
import com.dynamicruntime.common.filestore.fileMetaEndpoint
import com.dynamicruntime.common.gedra.CCT
import com.dynamicruntime.common.gedra.ClientService
import com.dynamicruntime.common.gedra.configOwnerOf
import com.dynamicruntime.common.gedra.expectedModeField
import com.dynamicruntime.common.gedra.expectedModeOf
import com.dynamicruntime.common.gedra.EDM
import com.dynamicruntime.common.gedra.overseenClient
import com.dynamicruntime.common.gedra.overseenClientField
import com.dynamicruntime.common.http.request.ContentData
import com.dynamicruntime.common.schema.SCT
import com.dynamicruntime.common.user.UADEP
import com.dynamicruntime.common.util.toOptStr
import com.dynamicruntime.common.webasset.WAS
import com.dynamicruntime.common.webasset.WebAssetCheck

/**
 * A client's file refs, for its administrator (issues #1153, #1154, #1164, #1176): what refs it has and what one
 * takes, uploading a file as one, naming a local file as one, rewording one, removing one, the roots open to
 * clients, and the file itself.
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
        description = "One file ref of a client: a named file its configuration holds, by where its bytes are."
        property(CCT.refId, "The ref's id within its client.", required = true)
        property(CCT.source, "Where the file's bytes are.", required = true) {
            option(FREF.sourceStore, "The file store")
            option(FREF.sourceLocal, "A local file")
            option(FREF.sourceClientLocal, "A local file in the client's own directory of a root open to clients")
        }
        property(CCT.root, "For a local file, the root it is under.")
        property(CCT.path, "For a local file, its path under the root -- or, for a client's own, under the client's directory there.")
        property(CCT.hash, "The SHA-256 of the file's bytes: the hash the ref names, or for a local file the hash of the file as it is now. Absent for a local file that is missing.")
        property(FREF.name, "The ref by name and content, `<refId>:<hash>`: what the static root serves it as, at `/<appId>/ref/<name>`. Absent with the hash.")
        property(CCT.description, "What the file is for, in the administrator's words.") { maxLength = FREF.maxDescriptionLength }
        property(FREF.configName, "The configuration holding the ref's entry: a stored one, or for a ref the client was shipped with, one in source code.")
        property(FREF.live, "Whether the client's own people are served this ref as listed; false for a draft only its sandbox shows.", required = true) {
            type = SCT.boolean
        }
        property(
            FREF.stored,
            "Whether a stored configuration holds the ref's entry. False for a ref the client was shipped with, in " +
                "source code: an upload overrides one, and it cannot be removed.",
            required = true,
        ) { type = SCT.boolean }
        property(FREF.missing, "Whether there is no such file to serve, so the ref is answered as not found.", required = true) {
            type = SCT.boolean
        }
        property(
            FREF.fromTemplate,
            "For a ref the client inherits from a template and has no file of its own for, the template whose file it " +
                "is served (issue #1180); absent when the file is the client's own.",
        )
        property(
            FREF.file,
            "The record of the file -- the file store's, or for a local file what the disk says of it, its " +
                "`uploadedAt` being when the file last changed; absent when it is missing.",
        ) { ref(FMETA.infoTypeName) }
        property(FREF.uploadedByName, "Who uploaded the file, as they were called then; absent when that was not recorded.")
    }

    type(FREF.changeTypeName) {
        type = SCT.kObject
        description = "What an upload, a naming or a removal of a file ref did: where it landed, and how it took effect."
        property(FREF.client, "The client whose configuration took the change: the one named, or a sandbox's parent.", required = true)
        property(CCT.refId, "The ref.", required = true)
        property(FREF.configName, "The stored configuration the change landed in.", required = true)
        property(FREF.mode, "How it took effect: live for the client at once, or a draft its sandbox shows until it is published.", required = true) {
            option(EDM.live, "Live")
            option(EDM.draft, "Draft")
        }
        property(FREF.ref, "The ref as it now is; absent when the change removed it.") { ref(FREF.refTypeName) }
    }

    type(FREF.rootTypeName) {
        type = SCT.kObject
        description = "One root of local files that is open to clients: a client's own administrator may name a file in it."
        property(CCT.root, "The root's name: what a ref's `root` is.", required = true)
    }

    type(FREF.takesTypeName) {
        type = SCT.kObject
        description = "What a client's file refs take: what a page needs to know before it offers to add one."
        property(WAS.maxSize, "The most bytes a ref's file may be.", required = true) { type = SCT.integer }
        property(WAS.mimeTypes, "The content types an upload may be sent as.", required = true) {
            type = SCT.array
            items { type = SCT.string }
        }
        property(WAS.kinds, "The kinds of image a ref's file may be, as a person names them.", required = true) {
            type = SCT.array
            items { type = SCT.string }
        }
        property(FREF.refLimit, "How many file refs a client is served.", required = true) { type = SCT.integer }
        property(FREF.roots, "The roots of local files open to clients, by name: where a file may be named and not uploaded. Empty on most deployments.", required = true) {
            type = SCT.array
            items { type = SCT.string }
        }
    }

    listEndpoint(
        UADEP.clientFiles,
        "A client's file refs (issues #1153, #1154): the named files its configuration holds, each with the record " +
            "of the file -- the file store's, or for a local file what the disk says of it -- and none of its content. For a client with a sandbox these are the " +
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
            expectedModeField()
        },
    ) { c, request ->
        val refs = FileRefService.get(c)
        val client = knownClient(c, request)
        // A `file` part sent as a plain field is a value, so it passes for "present": it is here that it is no file.
        val content = request[FREF.file] as? ContentData ?: throw KdrException.mkInput("No file was sent.")
        val description = if (request.containsKey(CCT.description)) request[CCT.description].toOptStr().orEmpty() else null
        changeItem(c, refs, ClientFileRefEdit.upload(c, client, request[CCT.refId].toOptStr().orEmpty(), content, description, expectedModeOf(request)))
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
            expectedModeField()
        },
    ) { c, request ->
        val refs = FileRefService.get(c)
        val client = knownClient(c, request)
        changeItem(c, refs, ClientFileRefEdit.remove(c, client, request[CCT.refId].toOptStr().orEmpty(), expectedModeOf(request)))
    }

    generalEndpoint(
        UADEP.clientFilesDescribe,
        "Rewords the description of one of a client's file refs (issue #1176), or clears it with a blank one, live " +
            "or as a draft as an upload is. The ref names the file it named. A 400 when no stored configuration " +
            "holds the ref: one the client was shipped with keeps its description until a file is uploaded over it.",
        HttpMethod.POST,
        outputRef = FREF.changeTypeName,
        needsClientConfig = true,
        inputFields = {
            overseenClientField(FREF.client)
            field(CCT.refId, "The ref to reword.", required = true)
            field(CCT.description, "What the file is for, in at most ${FREF.maxDescriptionLength} characters; blank clears it.", required = true) {
                emptyIsAbsent = false
                maxLength = FREF.maxDescriptionLength
            }
            expectedModeField()
        },
    ) { c, request ->
        val refs = FileRefService.get(c)
        val client = knownClient(c, request)
        val described = ClientFileRefEdit.describe(
            c, client, request[CCT.refId].toOptStr().orEmpty(), request[CCT.description].toOptStr().orEmpty(), expectedModeOf(request),
        )
        changeItem(c, refs, described)
    }

    generalEndpoint(
        UADEP.clientFilesLocal,
        "Names a local file as one of a client's file refs (issue #1164), replacing what the ref named: a file the " +
            "deployment keeps on the server, under a root it has opened to clients and in the client's own " +
            "directory there. Nothing is uploaded and nothing is copied -- the ref is served the file as it is, by " +
            "the hash of its bytes when it is read. The ref is of the `${FREF.sourceClientLocal}` kind, which reaches " +
            "the client's own directory and nothing else. Refused with a 403 for a root that is not open to clients " +
            "(a 400 for a caller who sees every client, who names any other file by a typed entry); and with a 400 " +
            "when the file is not there to be served as an image, which says one thing whatever the reason. Live " +
            "or a draft as an upload is.",
        HttpMethod.POST,
        outputRef = FREF.changeTypeName,
        needsClientConfig = true,
        inputFields = {
            overseenClientField(FREF.client)
            field(CCT.refId, "The ref's id: a variable name. An id the client already has replaces what that ref names.", required = true)
            field(CCT.root, "The root the file is under: one of those `${UADEP.clientFilesRoots}` lists.", required = true)
            field(CCT.path, "The file's path under the client's directory in that root, in segments of letters, digits, `.`, `_` and `-`.", required = true) {
                maxLength = FREF.maxPathLength
            }
            field(CCT.description, "What the file is for, in at most ${FREF.maxDescriptionLength} characters. Absent keeps the ref's description; blank clears it.") {
                emptyIsAbsent = false
                maxLength = FREF.maxDescriptionLength
            }
            expectedModeField()
        },
    ) { c, request ->
        val refs = FileRefService.get(c)
        val client = knownClient(c, request)
        val description = if (request.containsKey(CCT.description)) request[CCT.description].toOptStr().orEmpty() else null
        val change = ClientFileRefEdit.nameLocal(
            c, client, request[CCT.refId].toOptStr().orEmpty(), request[CCT.root].toOptStr().orEmpty(), request[CCT.path].toOptStr().orEmpty(), description,
            expectedModeOf(request),
        )
        changeItem(c, refs, change)
    }

    listEndpoint(
        UADEP.clientFilesRoots,
        "The roots of local files that are open to clients (issue #1164), by name: where a client's own " +
            "administrator may name a file as a ref, the path being looked for in the client's own directory " +
            "there. The names as the deployment declared them, and nothing of the disk -- not what is in a root, " +
            "nor whether there is yet a directory of that name. The same for every client; empty where the " +
            "deployment has opened none, which is the default.",
        outputRef = FREF.rootTypeName,
        // As many as the deployment declared in one variable.
        noLimit = true,
        inputFields = { overseenClientField(FREF.client) },
        needsClientConfig = true,
    ) { c, request ->
        // The answer is the deployment's and no client's, but who may ask is who may ask of a client's files at
        // all: the administrator of one, as every call beside this holds.
        knownClient(c, request)
        FileRefService.get(c).clientRoots().map { mapOf(CCT.root to it) }
    }

    itemEndpoint(
        UADEP.clientFilesTakes,
        "What a client's file refs take (issue #1176): the most bytes a file may be, the kinds of image and the " +
            "content types an upload may be sent as, how many refs a client is served, and the roots open to " +
            "clients (those `${UADEP.clientFilesRoots}` lists). The deployment's answer and no client's -- it is " +
            "what a page asks before it offers to add a ref, so that it says what the backend will hold it to.",
        HttpMethod.GET,
        outputRef = FREF.takesTypeName,
        inputFields = { overseenClientField(FREF.client) },
        needsClientConfig = true,
    ) { c, request ->
        knownClient(c, request)
        mapOf(
            WAS.maxSize to FREF.maxSize,
            WAS.mimeTypes to WebAssetCheck.acceptedMimeTypes,
            WAS.kinds to WebAssetCheck.kinds.map { it.label },
            FREF.refLimit to FREF.maxRefs,
            FREF.roots to FileRefService.get(c).clientRoots(),
        )
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
        val shown = shownRef(c, refs, request)
        val file = shown.ref?.let { refs.read(c, it) } ?: throw shown.noFile()
        val refId = shown.refId
        c.request?.webRequest?.let { HashedFileServing.downloadHeaders(it, file, request[CCT.hash].toOptStr()) }
        // Offered under the ref's own id and its kind's extension, never the name it was uploaded under: that name
        // is the first uploader's, of whatever ref first held these bytes, and its extension can say anything.
        val extension = WebAssetCheck.declaredKind(file.meta.mimeType)?.extension
        ContentData(file.bytes, file.meta.mimeType, saveAsFilename = listOfNotNull(refId, extension).joinToString("."), inLine = false)
    }

    fileMetaEndpoint(
        UADEP.clientFilesDownloadMeta,
        "What the file of one of a client's file refs is, without the file (issue #1131): the record the listing " +
            "shows of it -- the file store's, or for a local file what the disk says of it now. The download's inputs " +
            "and the same 404s: no such ref, or its file is missing.",
        needsClientConfig = true,
        inputFields = {
            overseenClientField(FREF.client)
            field(CCT.refId, "The ref whose file to describe.", required = true)
        },
    ) { c, request ->
        val refs = FileRefService.get(c)
        val shown = shownRef(c, refs, request)
        shown.ref?.let { refs.currentMetaOf(c, it) } ?: throw shown.noFile()
    }
}

/** The ref a download or its meta read names: of the client the request names, as the listing shows it ([listedRefs]). */
private class ShownRef(val client: String, val refId: String, val ref: FileRef?) {
    /** The 404 for a ref that is not there, or whose file is not: one answer from the download and its meta read. */
    fun noFile(): KdrException = KdrException("Client '$client' has no file ref '$refId' whose file is there.", code = EXC.notFound)
}

private fun shownRef(cxt: KdrCxt, refs: FileRefService, request: Map<String, Any?>): ShownRef {
    val client = knownClient(cxt, request)
    val refId = request[CCT.refId].toOptStr().orEmpty()
    return ShownRef(client, refId, refs.forClient(refs.shownAs(cxt, client)).refs[refId])
}

/** The client a call acts on: the one it may ([overseenClient]), and one this node knows. */
private fun knownClient(cxt: KdrCxt, request: Map<String, Any?>): String =
    ClientService.get(cxt).requireKnown(overseenClient(cxt, request[FREF.client].toOptStr()))

/**
 * [client]'s refs as an administrator is shown them ([FileRefService.shownAs]): the sandbox's where it has one,
 * each marked by whether the client's own registry serves the same file under that id.
 */
fun listedRefs(cxt: KdrCxt, refs: FileRefService, client: String): List<Map<String, Any?>> {
    val running = refs.forClient(configOwnerOf(client)).refs
    return refs.forClient(refs.shownAs(cxt, client)).refs.values.map { refItem(cxt, refs, it, running[it.refId]?.def?.pointer == it.def.pointer) }
}

/**
 * One ref as the listing answers it; [live] is whether the client's own people are served the same file under its
 * id -- the same pointer, which for a local file is the same root and path, whatever the file now holds.
 */
fun refItem(cxt: KdrCxt, refs: FileRefService, ref: FileRef, live: Boolean): Map<String, Any?> {
    // Asked even for a ref the load found missing: the file may have arrived since. A store that cannot be asked
    // fails the listing, rather than have it say of every file that it is missing.
    val meta = refs.metaOf(cxt, ref)
    // The bytes' own hash where there are bytes; else the one the ref names, which a local file's ref does not.
    val hash = meta?.hash ?: ref.def.hash
    return buildMap {
        put(CCT.refId, ref.refId)
        put(CCT.source, ref.def.source)
        ref.def.root?.let { put(CCT.root, it) }
        ref.def.path?.let { put(CCT.path, it) }
        hash?.let {
            put(CCT.hash, it)
            put(FREF.name, FREF.versionedName(ref.refId, it))
        }
        ref.def.description?.let { put(CCT.description, it) }
        ref.def.configName?.let { put(FREF.configName, it) }
        put(FREF.live, live)
        put(FREF.stored, ref.def.stored)
        put(FREF.missing, meta == null)
        // Read from the template's directory, its default (issue #1180): the record says whose file it is.
        ref.inheritedFrom?.takeIf { meta?.client == it }?.let { put(FREF.fromTemplate, it) }
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
    refs.forClient(result.shownAs).refs[result.refId]?.let { put(FREF.ref, refItem(cxt, refs, it, running?.def?.pointer == it.def.pointer)) }
}
