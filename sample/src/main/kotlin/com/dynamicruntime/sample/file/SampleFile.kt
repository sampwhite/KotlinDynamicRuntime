package com.dynamicruntime.sample.file

import com.dynamicruntime.common.exception.EXC
import com.dynamicruntime.common.exception.KdrException
import com.dynamicruntime.common.http.request.ContentData
import com.dynamicruntime.common.schema.JsonMappable
import com.dynamicruntime.common.sql.AppPaths
import com.dynamicruntime.common.util.RandomUtil
import com.dynamicruntime.common.util.formatDate
import com.dynamicruntime.common.util.jsonMap
import com.dynamicruntime.common.util.parseDate
import com.dynamicruntime.common.util.toJsonStr
import java.io.File
import kotlin.time.Clock
import kotlin.time.Instant

/**
 * Field keys shared by the file schema types ([SampleFileService.schema]) and [SampleFileInfo]. Each
 * constant's name matches its JSON key, per the code guide.
 */
@Suppress("ConstPropertyName")
object SF {
    const val id = "id"
    const val fileName = "fileName"
    const val mimeType = "mimeType"
    const val size = "size"
    const val uploaded = "uploaded"

    /** The upload endpoint's binary input field — the part carrying the file itself. */
    const val file = "file"
}

/** What is known about one stored file. The content lives beside it on disk; this is the index entry. */
class SampleFileInfo(
    val id: String,
    /** The name the *client* gave the file. Metadata only — see [SampleFileStore] on why it never reaches the
     *  filesystem. Handed back on download so the browser saves it under the name it was uploaded with. */
    val fileName: String,
    val mimeType: String,
    val size: Long,
    val uploaded: Instant,
) : JsonMappable {
    override fun toJsonMap(): Map<String, Any?> = linkedMapOf(
        SF.id to id,
        SF.fileName to fileName,
        SF.mimeType to mimeType,
        SF.size to size,
        SF.uploaded to uploaded.formatDate(),
    )
}

/**
 * Stores uploaded files on disk, under `sampleFiles/` in the workspace directory — the same place the runtime
 * keeps its other deployment-relative data (`h2Database/`, `private/`), resolved through [AppPaths].
 *
 * **The client's filename never touches the filesystem.** A file is stored under a generated [newId], and the
 * name it arrived with is kept as metadata. This is not tidiness: a name is attacker-supplied, and letting it
 * choose a path is how an upload becomes a write to `../../etc/` or an overwrite of another user's file.
 * Sanitizing a name well enough to be a path is a losing game across platforms (`..`, separators, NUL, `C:`,
 * reserved names like `CON`, case-insensitive collisions, unicode look-alikes); *not deriving the path from
 * it* is a guarantee rather than a filter. [ContentData.saveAsFilename] then offers the name back on download,
 * which is where it is safe — as a suggestion, sanitized into a header.
 *
 * Ids come from the **secure** random source. These sample endpoints have no auth, so the id is the only thing
 * standing between a stored file and anyone who asks: a sequential or fast-random id would let a stranger walk
 * the store. An unguessable id makes the URL itself the capability.
 *
 * A sample, so it is deliberately plain: a file for the bytes and a JSON sidecar for the metadata, no index,
 * no database, no cleanup. Real storage would want all three.
 */
class SampleFileStore(val dir: File) {
    /** Reads the store's index: every file's metadata, newest first. Cheap enough at sample scale. */
    fun list(): List<SampleFileInfo> =
        (dir.listFiles { f -> f.name.endsWith(metaSuffix) } ?: emptyArray())
            .mapNotNull { readMeta(it) }
            .sortedByDescending { it.uploaded }

    /** Stores [content] under a fresh id and returns its index entry. */
    fun save(content: ContentData): SampleFileInfo {
        val bytes = content.contentBytes()
        val id = newId()
        val info = SampleFileInfo(
            id = id,
            // A part with no filename is possible; name it for the id rather than inventing one.
            fileName = content.saveAsFilename?.takeIf { it.isNotBlank() } ?: "upload-$id",
            mimeType = content.mimeType,
            size = bytes.size.toLong(),
            uploaded = Clock.System.now(),
        )
        dir.mkdirs()
        contentFile(id).writeBytes(bytes)
        metaFile(id).writeText(info.toJsonMap().toJsonStr())
        return info
    }

    /**
     * The file stored under [id] as content ready to send: its bytes, its recorded MIME type, and its original
     * name offered as the download name. Null when no such file exists.
     */
    fun load(id: String): ContentData? {
        val info = readMeta(metaFile(checkedId(id))) ?: return null
        val file = contentFile(info.id)
        if (!file.isFile) {
            return null
        }
        return ContentData(
            file.readBytes(),
            info.mimeType,
            saveAsFilename = info.fileName,
            inLine = false,
        )
    }

    /** The metadata for [id], or null when absent. */
    fun info(id: String): SampleFileInfo? = readMeta(metaFile(checkedId(id)))

    private fun readMeta(file: File): SampleFileInfo? {
        if (!file.isFile) {
            return null
        }
        val map = file.readText().jsonMap() ?: return null
        val id = map[SF.id] as? String ?: return null
        return SampleFileInfo(
            id = id,
            fileName = map[SF.fileName] as? String ?: id,
            mimeType = map[SF.mimeType] as? String ?: "application/octet-stream",
            size = (map[SF.size] as? Number)?.toLong() ?: 0L,
            uploaded = (map[SF.uploaded] as? String)?.let { runCatching { it.parseDate() }.getOrNull() }
                ?: Clock.System.now(),
        )
    }

    private fun contentFile(id: String): File = File(dir, "$id$contentSuffix")

    private fun metaFile(id: String): File = File(dir, "$id$metaSuffix")

    /**
     * Guards an id arriving from a request before it is turned into a path. Ids we mint are hex, so anything
     * else is not one of ours — and rejecting rather than sanitizing means a `..` or a separator never gets the
     * chance to be cleverer than the filter. Belt and braces alongside never using the client's filename.
     */
    private fun checkedId(id: String): String {
        if (id.isEmpty() || id.length > maxIdLength || !id.all { it in "0123456789abcdef" }) {
            throw KdrException("Not a valid file id: '$id'.", null, EXC.badInput)
        }
        return id
    }

    @Suppress("ConstPropertyName")
    companion object {
        /** The store's directory, under the workspace (beside `h2Database/` and `private/`). */
        const val storeDirName = "sampleFiles"

        const val contentSuffix = ".bin"
        const val metaSuffix = ".json"

        /** 16 secure random bytes as hex: unguessable, and safe as a filename by construction. */
        const val idBytes = 16
        const val maxIdLength = idBytes * 2

        /** The store for this deployment, at `<workspace>/sampleFiles`. */
        fun forWorkspace(): SampleFileStore = SampleFileStore(AppPaths.resolve(storeDirName))

        /** A fresh file id: hex, from the secure source (see the class note on why it must be unguessable). */
        fun newId(): String = RandomUtil.secureBytes(idBytes).joinToString("") { b ->
            val v = b.toInt() and 0xFF
            "0123456789abcdef"[v shr 4].toString() + "0123456789abcdef"[v and 0x0F]
        }
    }
}
