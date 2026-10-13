package com.dynamicruntime.common.fileref

import com.dynamicruntime.common.content.CMK
import com.dynamicruntime.common.gedra.CCT
import com.dynamicruntime.common.naming.OwnedNameKind
import com.dynamicruntime.common.naming.clientNameProblem
import com.dynamicruntime.common.util.toOptStr

/**
 * A client's file refs (issue #1153): its named files, held in its stored configuration as pointers to bytes kept
 * elsewhere. The names here are the ones the two sides have to agree on -- the backend serves a ref at the address
 * the frontend builds ([staticPath]) under the name a listing hands it ([versionedName]).
 *
 * Not to be confused with `GedraDataType.fileRef`, a kind of **data** gedra -- a claim on a file held as data,
 * with no write path yet. A file ref in configuration is a client's named, published file, and shares only the word.
 */
@Suppress("ConstPropertyName")
object FREF {
    /** A ref whose bytes are in the file store, named by their hash. The only source there is; see [FileRefDef.source]. */
    const val sourceStore = "store"

    /** The longest a ref's id may be. */
    const val maxRefIdLength = 64

    /** The most file refs one client's stored configuration may hold. A load reads the store once per distinct file. */
    const val maxRefs = 200

    /** The most bytes a ref's file may be. */
    const val maxSize = 2 * 1024 * 1024

    /** How long a hash is: a SHA-256 in hex. */
    const val hashLength = 64

    // --- Wire names: the inputs of the endpoints, and the fields of a ref as the listing answers it.

    /** The client whose refs these are; the caller's own when absent. */
    const val client = "client"

    /** An upload's file part, and in a listed ref the metadata of its file (absent when its bytes are missing). */
    const val file = "file"

    /** A ref by name and content, `<refId>:<hash>` ([versionedName]). */
    const val name = "name"

    /** The stored configuration holding a ref's entry. */
    const val configName = "configName"

    /** Whether the client's own people are served this ref as it is listed -- false for a draft only its sandbox shows. */
    const val live = "live"

    /** Whether a ref's bytes could not be found in the file store. */
    const val missing = "missing"

    /** How a change took effect: live, or a draft; `EDM`'s values. */
    const val mode = "mode"

    /** In what a change answers: the ref as it now is; absent when the change removed it. */
    const val ref = "ref"

    /** Who uploaded a ref's file, as they were called then; kept with the file, since a user id is no name. */
    const val uploadedByName = "uploadedByName"

    /** On the named download: the hash of the file the caller expects, which lets the caller's own browser keep the answer. */
    const val expectedHash = "hash"

    /** The schema type of one listed ref, and of what a change answers. */
    const val refTypeName = "FileRef"
    const val changeTypeName = "FileRefChange"

    /** The schema namespace of the endpoints and their types. */
    const val namespace = "kdr.clientFiles"

    /** Separates a ref's id from the hash of its content in a [versionedName], as it does a file from its build id. */
    const val nameSep = ':'

    /** A ref named by its content: `logo:<hash>`. The frontend puts it in a URL as it is and never takes it apart. */
    fun versionedName(refId: String, hash: String): String = "$refId$nameSep$hash"

    /**
     * Where a ref is served, under the static context root: `/<appId>/ref/<refId:hash>`. [appId] is the frontend's
     * own and is not read by the backend, which serves the **caller's** client's ref whatever the address says.
     */
    fun staticPath(appId: String, versionedName: String): String = "/$appId/${CMK.ref}/$versionedName"

    /** Whether [hash] is shaped as one: [hashLength] lowercase hex characters. */
    fun isHash(hash: String): Boolean = hash.length == hashLength && hash.all { it in '0'..'9' || it in 'a'..'f' }

    /**
     * What is wrong with [refId] as the id of a client's file ref, or null: a client's bare name, held to a variable
     * name's rule, at most [maxRefIdLength] long. No dot, slash or colon -- the id becomes a URL segment, and a dot
     * would read as an extension that can lie about what the file is.
     */
    fun refIdProblem(refId: String): String? =
        clientNameProblem(OwnedNameKind.fileRef, refId)
            ?: "'$refId' is too long to be a file ref id: at most $maxRefIdLength characters.".takeIf { refId.length > maxRefIdLength }
}

/**
 * One file ref a configuration declares (issue #1153), as its `kdr:fileRefDef` entry holds it.
 *
 * **The hash is the pointer.** Where the bytes are kept is derived from it and never stored, so an entry names
 * exactly one sequence of bytes and nothing can change them under it. And **what the file is -- its type, its size,
 * the name it was uploaded under -- is not here**: that is the file store's own record, written by the upload that
 * checked the bytes. An administrator can type an entry through the configuration endpoints, and the content type a
 * file is served under must never come from something that can be typed.
 *
 * [configName] and [stored] are a stamp, not content: which configuration declared this and whether it was read
 * from the database, set by the builder and never written back.
 */
class FileRefDef(
    /** The ref's id within its client: how it is addressed. */
    val refId: String,
    /** Where its bytes are: [FREF.sourceStore]. Kept as written, so a source a later release adds is told apart, not mistaken. */
    val source: String,
    /** The SHA-256 of its bytes, in hex; null only for an entry that held none. */
    val hash: String?,
    val description: String? = null,
    val configName: String? = null,
    val stored: Boolean = false,
) {
    /** This ref as its stored entry: what was authored, and none of the stamp. */
    fun toEntry(): Map<String, Any?> = buildMap {
        put(CCT.refId, refId)
        put(CCT.source, source)
        hash?.let { put(CCT.hash, it) }
        description?.let { put(CCT.description, it) }
    }

    companion object {
        /**
         * The ref [entry] declares. **Forgiving**: an entry whose id or hash is no good is still a ref -- one the
         * load reports and drops by itself -- since refusing it here would cost its whole configuration.
         */
        fun fromEntry(entry: Map<String, Any?>, configName: String?, stored: Boolean): FileRefDef = FileRefDef(
            refId = entry[CCT.refId].toOptStr().orEmpty(),
            source = entry[CCT.source].toOptStr().orEmpty(),
            hash = entry[CCT.hash].toOptStr(),
            description = entry[CCT.description].toOptStr(),
            configName = configName,
            stored = stored,
        )
    }
}
