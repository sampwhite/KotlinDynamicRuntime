package com.dynamicruntime.common.fileref

import com.dynamicruntime.common.content.CMK
import com.dynamicruntime.common.gedra.CCT
import com.dynamicruntime.common.naming.OwnedNameKind
import com.dynamicruntime.common.naming.clientNameProblem
import com.dynamicruntime.common.util.toOptStr

/**
 * A client's file refs (issues #1153, #1154): its named files, held in its configuration as pointers to bytes kept
 * elsewhere -- in the file store, or as a file on the server's own disk. The names here are the ones the two sides have to agree on -- the backend serves a ref at the address
 * the frontend builds ([staticPath]) under the name a listing hands it ([versionedName]).
 *
 * Not to be confused with `GedraDataType.fileRef`, a kind of **data** gedra -- a claim on a file held as data,
 * with no write path yet. A file ref in configuration is a client's named, published file, and shares only the word.
 */
@Suppress("ConstPropertyName")
object FREF {
    /** A ref whose bytes are in the file store, named by their hash; see [FileRefDef.source]. */
    const val sourceStore = "store"

    /**
     * A ref whose bytes are a file on the machine (issue #1154), named by a declared **root** and a path under it:
     * a source repository's artwork, a directory somebody maintains. Nothing is imported into the file store, and
     * no hash is authored -- it is the file's, as read. `local` as the file store's `local` type means it: on this
     * machine's disk.
     */
    const val sourceLocal = "local"

    /** Every source a ref may have. */
    val sources = listOf(sourceStore, sourceLocal)

    /** The longest a local ref's path may be: a file-store key's length (`FNAME.maxKeyLength`, which a test holds this to), whose rules it is held to. */
    const val maxPathLength = 512

    /** [rootProblem]'s rule as a pattern, for a schema to hold a root's name to; a test holds the two together. */
    const val rootPattern = "^[A-Za-z0-9_-]+\$"

    /** The longest a ref's id may be. */
    const val maxRefIdLength = 64

    /**
     * The most file refs a client is served. A load asks the file store once for each distinct file its refs name,
     * so this is what bounds a load; a ref beyond it is reported and not served, and a write that would add one is
     * refused by its trial.
     */
    const val maxRefs = 200

    /** The longest a ref's description may be. */
    const val maxDescriptionLength = 500

    /** [refIdProblem]'s character rule as a pattern, for a schema to hold an id to; a test holds the two together. */
    const val refIdPattern = "^[A-Za-z_][A-Za-z0-9_]*\$"

    /** [isHash] as a pattern. */
    const val hashPattern = "^[0-9a-f]{64}\$"

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

    /** The configuration holding a ref's entry: a stored one, or for a ref a client is shipped with, one in source code. */
    const val configName = "configName"

    /** Whether the client's own people are served this ref as it is listed -- false for a draft only its sandbox shows. */
    const val live = "live"

    /** Whether a ref's file is not there to be served: not in the file store, or for a local file not on the disk as an image. */
    const val missing = "missing"

    /** How a change took effect: live, or a draft; `EDM`'s values. */
    const val mode = "mode"

    /** In what a change answers: the ref as it now is; absent when the change removed it. */
    const val ref = "ref"

    /** Who uploaded a ref's file, as they were called then; kept with the file, since a user id is no name. */
    const val uploadedByName = "uploadedByName"


    /** The schema type of one listed ref, and of what a change answers. */
    const val refTypeName = "FileRef"
    const val changeTypeName = "FileRefChange"

    /** The schema type of one root of local files a client may name a file in (issue #1164). */
    const val rootTypeName = "FileRefRoot"

    /** The schema namespace of the endpoints and their types. */
    const val namespace = "kdr.clientFiles"

    /** Separates a ref's id from the hash of its content in a [versionedName], as it does a file from its build id. */
    const val nameSep = CMK.nameSep

    /** A ref named by its content: `logo:<hash>`. The frontend puts it in a URL as it is and never takes it apart. */
    fun versionedName(refId: String, hash: String): String = "$refId$nameSep$hash"

    /**
     * Where a ref is served, under the static context root: `/<appId>/ref/<refId:hash>`. [appId] is the frontend's
     * own and is not read by the backend, which serves the **caller's** client's ref whatever the address says.
     */
    fun staticPath(appId: String, versionedName: String): String = "/$appId/${CMK.ref}/$versionedName"

    /**
     * What is wrong with [root] as the name of a local-file root, or null: a plain name -- letters, digits, `_` and
     * `-` -- since it is a directory's own name, or a name a deployment declared, and never a path.
     */
    fun rootProblem(root: String): String? =
        if (root.isNotEmpty() && root.all { it in 'a'..'z' || it in 'A'..'Z' || it in '0'..'9' || it == '_' || it == '-' }) {
            null
        } else {
            "'$root' cannot name a root of local files: it has to be letters, digits, underscores and hyphens."
        }

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
 * **For a ref in the file store, the hash is the pointer.** Where the bytes are kept is derived from it and never
 * stored, so an entry names exactly one sequence of bytes and nothing can change them under it. **For a local ref
 * (issue #1154) the pointer is a [root] and a [path] under it**, and there is no hash to author: it is the file's,
 * as it is read.
 *
 * Either way, **what the file is -- its type, its size -- is not here**: it is the file store's own record, written
 * by the upload that checked the bytes, or what the local file is found to be each time it is read. An
 * administrator can type an entry through the configuration endpoints, and the content type a file is served under
 * must never come from something that can be typed.
 *
 * [configName] and [stored] are a stamp, not content: which configuration declared this and whether it was read
 * from the database, set by the builder and never written back.
 *
 * [extra] is whatever else a stored entry held: fields this release does not read -- a later one's -- kept and
 * written back as they were, so that an edit of anything else in the configuration, made on a node one release
 * behind, does not strip them. The rule a write follows for a whole slot it does not read, at the level of a field.
 */
class FileRefDef(
    /** The ref's id within its client: how it is addressed. */
    val refId: String,
    /** Where its bytes are, one of [FREF.sources]. Kept as written, so a source a later release adds is told apart, not mistaken. */
    val source: String,
    /** For [FREF.sourceStore]: the SHA-256 of its bytes, in hex. Null for a local ref, and for an entry that held none. */
    val hash: String?,
    val description: String? = null,
    val configName: String? = null,
    val stored: Boolean = false,
    val extra: Map<String, Any?> = emptyMap(),
    /** For [FREF.sourceLocal]: the name of the declared root its file is under. */
    val root: String? = null,
    /** For [FREF.sourceLocal]: the file's path under its [root], slash-separated. */
    val path: String? = null,
) {
    /**
     * What this ref points at, as one value: the same for two refs exactly when they name the same bytes by the
     * same means. How a listing tells whether what a client's people are served is what its administrator sees.
     */
    val pointer: String
        get() = if (source == FREF.sourceLocal) "$source:${root.orEmpty()}/${path.orEmpty()}" else "$source:${hash.orEmpty()}"

    /** This ref as its stored entry: what was authored and what was carried, and none of the stamp. */
    fun toEntry(): Map<String, Any?> = buildMap {
        put(CCT.refId, refId)
        put(CCT.source, source)
        hash?.let { put(CCT.hash, it) }
        root?.let { put(CCT.root, it) }
        path?.let { put(CCT.path, it) }
        description?.let { put(CCT.description, it) }
        putAll(extra)
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
            extra = entry.filterKeys { it !in readFields },
            root = entry[CCT.root].toOptStr(),
            path = entry[CCT.path].toOptStr(),
        )

        /** The fields of an entry this release reads; anything else is [extra]. */
        private val readFields = setOf(CCT.refId, CCT.source, CCT.hash, CCT.root, CCT.path, CCT.description)
    }
}
