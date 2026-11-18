package com.dynamicruntime.common.datafile

import com.dynamicruntime.common.context.KdrCxt
import com.dynamicruntime.common.context.ReadScope
import com.dynamicruntime.common.exception.EXC
import com.dynamicruntime.common.exception.KdrException
import com.dynamicruntime.common.filekind.FileDisposition
import com.dynamicruntime.common.filekind.FileKind
import com.dynamicruntime.common.filekind.FileKindSniff
import com.dynamicruntime.common.filekind.FileKinds
import com.dynamicruntime.common.filekind.Mp4Video
import com.dynamicruntime.common.filekind.FKIND
import com.dynamicruntime.common.filestore.FNAME
import com.dynamicruntime.common.filestore.FileBucketDef
import com.dynamicruntime.common.filestore.FileStoreService
import com.dynamicruntime.common.filestore.StagedPart
import com.dynamicruntime.common.gedra.GD
import com.dynamicruntime.common.gedra.GDT
import com.dynamicruntime.common.gedra.GDU
import com.dynamicruntime.common.gedra.GedraDataRow
import com.dynamicruntime.common.gedra.GedraDataService
import com.dynamicruntime.common.gedra.GedraDataType
import com.dynamicruntime.common.gedra.GedraIdContext
import com.dynamicruntime.common.gedra.GedraService
import com.dynamicruntime.common.gedra.PlanLimits
import com.dynamicruntime.common.gedra.configOwnerOf
import com.dynamicruntime.common.gedra.gedraDataTopic
import com.dynamicruntime.common.gedra.mkGedraId
import com.dynamicruntime.common.http.request.StreamedContent
import com.dynamicruntime.common.sql.KdrTable
import com.dynamicruntime.common.sql.SqlCxt
import com.dynamicruntime.common.sql.SqlStmtUtil
import com.dynamicruntime.common.sql.SqlTopicService
import com.dynamicruntime.common.sql.SqlTopicTranProvider
import com.dynamicruntime.common.sql.SqlTopicUtil
import com.dynamicruntime.common.user.ReadScopeRules
import com.dynamicruntime.common.util.Sha256
import com.dynamicruntime.common.util.formatDate
import com.dynamicruntime.common.util.sha256Hex
import com.dynamicruntime.common.util.toJsonListOfMaps
import com.dynamicruntime.common.util.toJsonMapOrEmpty
import com.dynamicruntime.common.util.toOptInstant
import com.dynamicruntime.common.util.toOptLong
import com.dynamicruntime.common.util.toOptStr
import kotlin.time.Duration.Companion.hours

/**
 * **Data files** (issue #1837): files uploaded for a form's data, each held by a record of its own -- a `GedraData` row
 * of kind [GedraDataType.dataFile], its metadata under `data.file` -- whose id a trait's file field names. The record
 * is what is owned, scoped, cached, and soft-deleted, as any gedra is; the bytes are in the file store, named by their
 * SHA-256 under the client's folder (`clients/<client>/files/<hash>`), so two uploads of one file share them.
 *
 * **An upload comes in chunks** of [DFILE.chunkBytes], in order, so a lost connection costs a chunk and not the file:
 * [begin] says how large each must be; [chunk] takes one at the offset the upload has reached, checked against the
 * SHA-256 the sender says it has, and advances the hash of the whole -- saved in the upload's row, since the next chunk
 * may reach another node; [status] says where to resume; [finish] puts the chunks together, names the file by its hash,
 * and checks its bytes are the kind it was sent as -- and, for an MP4, that its video is H.264 ([Mp4Video]).
 *
 * **Who may do what.** Reading a file -- its record, its content -- is for whoever's read scope admits the record: its
 * owner, or an administrator of its client -- and for whoever may read a live form whose field names it ([viewable],
 * through [DataFileLinks]). Removing one is for the first alone, and refused while a live form names it. An upload is
 * its owner's alone.
 *
 * Theme: #DFILE.
 */
object DataFileService {
    private const val tranBegin = "beginDataFileUpload"
    private const val tranChunk = "dataFileChunk"
    private const val tranFinish = "finishDataFileUpload"
    private const val mebibyte = 1024L * 1024L

    /** The bucket data files are kept in, declared by `CommonComponent`. */
    fun bucketDef(): FileBucketDef = FileBucketDef(DFILE.bucket, "Files uploaded for forms' data, named by their hash.")

    // --- the record ------------------------------------------------------------------------------------------------

    /** The `data.file` map of [row]. */
    fun fileOf(row: GedraDataRow): Map<String, Any?> = row.extra[DFILE.file].toJsonMapOrEmpty()

    /** The client whose folder [row]'s bytes are in: a sandbox's are its parent's, as its named images are. */
    private fun ownerOf(row: GedraDataRow): String = configOwnerOf(row.client)

    private fun folderOf(row: GedraDataRow): String = FNAME.clientKeyPrefix(ownerOf(row))

    private fun stagingKeyOf(row: GedraDataRow): String = "${folderOf(row)}staging/${row.gedraId.baseId}"

    private fun contentKeyOf(row: GedraDataRow, hash: String): String = "${folderOf(row)}files/$hash"

    /** The data file [fileId] when the caller's read scope admits it; a 404 otherwise, absent or not theirs alike. */
    fun readable(cxt: KdrCxt, fileId: String): GedraDataRow =
        GedraDataService.get(cxt).queryGedra(cxt, fileId, GedraDataType.dataFile, ReadScopeRules.forCaller(cxt))
            ?: throw KdrException("There is no such file.", code = EXC.notFound)

    /**
     * The data file [fileId] when the caller may read it (issue #1838): their read scope admits it ([readable]), or a
     * live form that names it does. A 404 otherwise, absent or not theirs alike.
     */
    fun viewable(cxt: KdrCxt, fileId: String): GedraDataRow =
        GedraDataService.get(cxt).queryGedra(cxt, fileId, GedraDataType.dataFile, ReadScopeRules.forCaller(cxt))
            ?: DataFileLinks.readableThroughLink(cxt, fileId)
            ?: throw KdrException("There is no such file.", code = EXC.notFound)

    /** The data file [fileId] when the caller is its owner, who alone uploads it. */
    private fun ownUpload(cxt: KdrCxt, fileId: String): GedraDataRow {
        val row = readable(cxt, fileId)
        if (row.userId != cxt.userProfile.userId) {
            throw KdrException("Only the user who began the upload of a file may send it.", code = EXC.notAuthorized)
        }
        return row
    }

    /** [row]'s record as an endpoint answers it ([DFILE.recordType]). */
    fun info(row: GedraDataRow): Map<String, Any?> {
        val file = fileOf(row)
        return linkedMapOf(
            DFILE.fileId to row.gedraId.fullId,
            DFILE.name to file[DFILE.name],
            DFILE.kind to file[DFILE.kind],
            DFILE.mimeType to file[DFILE.mimeType],
            DFILE.size to file[DFILE.size],
            DFILE.hash to file[DFILE.hash],
            DFILE.status to file[DFILE.status],
            DFILE.uploadedAt to file[DFILE.uploadedAt],
            DFILE.ownerId to row.userId,
        ).filterValues { it != null }
    }

    // --- uploading -----------------------------------------------------------------------------------------------

    /**
     * Begins an upload of a file of [declaredSize] bytes, sent as [mimeType] and called [fileName], for the caller:
     * makes its record, [DFSTAT.uploading], and its upload's row, and answers how to send it ([DFILE.uploadType]).
     * Refuses a type that is no kind a data file may be, a size past the kind's largest, and a client whose plan has no
     * room for it.
     */
    fun begin(cxt: KdrCxt, fileName: String?, mimeType: String, declaredSize: Long): Map<String, Any?> {
        val kind = FileKinds.declaredKind(mimeType) ?: throw KdrException.mkInput(
            "A file may be one of these kinds: ${FileKinds.kinds.joinToString(", ") { it.label }}. This one was sent as '$mimeType'.",
        )
        if (declaredSize < 1) throw KdrException.mkInput("A file has at least one byte; this one says it has $declaredSize.")
        if (declaredSize > kind.maxSize) {
            throw KdrException("A ${kind.label} file is at most ${kind.maxSize} bytes, not $declaredSize.", code = EXC.payloadTooLarge)
        }
        PlanLimits.requireRoomForFile(cxt, cxt.client, declaredSize)
        val gedraService = GedraService.get(cxt)
        val gedraId = gedraService.intern(cxt.mkGedraId(GedraDataType.dataFile, cxt.client, GedraIdContext.ui))
        val now = cxt.instanceNow()
        val expires = now + DFILE.uploadHours.hours
        val file = linkedMapOf<String, Any?>(
            DFILE.name to fileName?.trim()?.takeIf { it.isNotEmpty() },
            DFILE.kind to kind.id,
            DFILE.mimeType to kind.mimeType,
            DFILE.size to declaredSize,
            DFILE.status to DFSTAT.uploading,
            DFILE.renditions to emptyList<Any?>(),
            DFILE.uploadedAt to now.formatDate(),
            // On the record too, so what a plan counts can leave out an upload abandoned, without reading its row.
            DFILE.expiresAt to expires.formatDate(),
        ).filterValues { it != null }
        val txCxt = cxt.mkTransactionSubContext(tranBegin)
        val sqlCxt = SqlTopicService.mkSqlCxt(txCxt, gedraDataTopic)
        val records = GedraDataService.get(cxt).gedraDataTable(txCxt)
        val record = mutableMapOf<String, Any?>(
            GD.gedraId to gedraId.fullId, GD.gedraKind to GedraDataType.dataFile.name, GD.data to mapOf(DFILE.file to file),
        )
        SqlTopicUtil.prepForStdExecute(txCxt, records, record)
        val row = GedraDataRow.extract(gedraService, record)
        // The staged upload is begun before its row is written, so a row never names a token that was not made. A
        // failure after it leaves an upload the store holds and no row names, which the store's check finds.
        val stagingKey = stagingKeyOf(row)
        val token = FileStoreService.get(cxt).forOwner(cxt, ownerOf(row)).beginStaged(cxt, DFILE.bucket, stagingKey, kind.mimeType)
        val uploads = uploadTable(txCxt)
        val upload = mutableMapOf<String, Any?>(
            GD.gedraId to gedraId.fullId,
            GDU.stagingKey to stagingKey,
            GDU.token to token,
            GDU.chunkSize to DFILE.chunkBytes.toLong(),
            GDU.declaredSize to declaredSize,
            GDU.nextOffset to 0L,
            GDU.expiresAt to expires,
            GDU.progress to mapOf(GDU.parts to emptyList<Any?>(), GDU.hashState to Sha256().toState()),
        )
        SqlTopicUtil.prepForStdExecute(txCxt, uploads, upload)
        SqlTopicTranProvider.executeTopicTran(sqlCxt, tranBegin, null, mapOf(GD.gedraId to gedraId.fullId)) {
            sqlCxt.sqlDb.executeStatement(txCxt, SqlTopicUtil.mkTableInsertStmt(sqlCxt, records), record)
            sqlCxt.sqlDb.executeStatement(txCxt, SqlTopicUtil.mkTableInsertStmt(sqlCxt, uploads), upload)
        }
        return uploadAnswer(gedraId.fullId, DFSTAT.uploading, upload)
    }

    /**
     * Takes [bytes], the chunk of file [fileId] at [offset], whose SHA-256 the sender says is [chunkHash]; answers where
     * the upload has reached. The chunk must be the next one -- at the offset reached, [DFILE.chunkBytes] long unless it
     * is the last -- and must hash to [chunkHash]. One sent again that was already taken, the same bytes, is answered as
     * though taken now, so a sender that lost the answer can send it again; any other is a 409 saying where the upload
     * is ([DFILE.nextOffset] in its extra data).
     *
     * The chunk is written to the store before the lock is taken, so the lock is never held across a store's network
     * call; under the lock the offset is checked again, and only then does the upload advance. A chunk written for an
     * offset another request reached first is replaced in the store by the one that advanced, or its completion refuses
     * the upload as not the parts it recorded -- never a file made of parts it did not check.
     */
    fun chunk(cxt: KdrCxt, fileId: String, offset: Long, chunkHash: String, bytes: ByteArray): Map<String, Any?> {
        val row = ownUpload(cxt, fileId)
        val upload = readUploadNow(cxt, fileId) ?: throw notUploading(row)
        requireLive(cxt, upload)
        val chunkSize = upload[GDU.chunkSize].toOptLong() ?: DFILE.chunkBytes.toLong()
        val declared = upload[GDU.declaredSize].toOptLong() ?: 0L
        if (offset < 0 || offset >= declared || offset % chunkSize != 0L) {
            throw KdrException.mkInput("A chunk begins at a multiple of $chunkSize below $declared, not at $offset.")
        }
        val expected = minOf(chunkSize, declared - offset)
        if (bytes.size.toLong() != expected) {
            throw KdrException.mkInput("The chunk at $offset is ${bytes.size} bytes; it must be $expected.")
        }
        if (bytes.sha256Hex() != chunkHash.lowercase()) {
            throw KdrException.mkInput("The chunk at $offset does not hash to the '$chunkHash' it was sent with: send it again.")
        }
        // Already taken, or not yet reachable: answered without writing anything.
        settled(row, upload, offset, chunkHash)?.let { return it }
        val number = (offset / chunkSize + 1).toInt()
        val part = FileStoreService.get(cxt).forOwner(cxt, ownerOf(row))
            .putPart(cxt, DFILE.bucket, upload[GDU.stagingKey].toOptStr().orEmpty(), upload[GDU.token].toOptStr().orEmpty(), number, bytes)
        val txCxt = cxt.mkTransactionSubContext(tranChunk)
        val txSql = SqlTopicService.mkSqlCxt(txCxt, gedraDataTopic)
        var answer: Map<String, Any?>? = null
        SqlTopicTranProvider.executeTopicTran(txSql, tranChunk, null, mapOf(GD.gedraId to fileId)) {
            val current = readUpload(txCxt, txSql, fileId) ?: throw notUploading(row)
            settled(row, current, offset, chunkHash)?.let { answer = it; return@executeTopicTran }
            val progress = current[GDU.progress].toJsonMapOrEmpty()
            val hash = Sha256.fromState(progress[GDU.hashState].toJsonMapOrEmpty()).update(bytes)
            val parts = progress[GDU.parts].toJsonListOfMaps() + mapOf(
                GDU.number to part.number, GDU.size to part.size, GDU.tag to part.tag, GDU.sha256 to chunkHash.lowercase(),
            )
            val updated = current.toMutableMap()
            updated[GDU.nextOffset] = offset + bytes.size
            updated[GDU.progress] = mapOf(GDU.parts to parts, GDU.hashState to hash.toState())
            writeUpload(txCxt, txSql, updated, current)
            answer = uploadAnswer(fileId, DFSTAT.uploading, updated)
        }
        return answer ?: throw KdrException("The chunk at $offset of '$fileId' was neither taken nor refused.")
    }

    /**
     * The answer for a chunk at [offset] that the upload has already settled, or null when it is the next to take: an
     * identical chunk already taken is answered with where the upload is; any other is a 409 saying so.
     */
    private fun settled(row: GedraDataRow, upload: Map<String, Any?>, offset: Long, chunkHash: String): Map<String, Any?>? {
        val next = upload[GDU.nextOffset].toOptLong() ?: 0L
        if (offset == next) return null
        val chunkSize = upload[GDU.chunkSize].toOptLong() ?: DFILE.chunkBytes.toLong()
        val number = offset / chunkSize + 1
        val taken = upload[GDU.progress].toJsonMapOrEmpty()[GDU.parts].toJsonListOfMaps()
            .firstOrNull { it[GDU.number].toOptLong() == number }
        if (offset < next && taken?.get(GDU.sha256).toOptStr() == chunkHash.lowercase()) {
            return uploadAnswer(row.gedraId.fullId, DFSTAT.uploading, upload)
        }
        throw KdrException(
            "The upload of '${row.gedraId}' has reached $next bytes; send the chunk that begins there, not one at $offset.",
            code = EXC.conflict,
            extraData = linkedMapOf(DFILE.nextOffset to next),
        )
    }

    /** Where the upload of [fileId] has reached, to resume it; for a file no longer uploading, its status alone. */
    fun status(cxt: KdrCxt, fileId: String): Map<String, Any?> {
        val row = ownUpload(cxt, fileId)
        val upload = readUploadNow(cxt, fileId)
        val status = fileOf(row)[DFILE.status].toOptStr() ?: DFSTAT.ready
        return if (upload == null) linkedMapOf(DFILE.fileId to fileId, DFILE.status to status) else uploadAnswer(fileId, status, upload)
    }

    /**
     * Finishes the upload of [fileId], all its chunks taken: puts them together, checks the result is the kind it was
     * sent as -- by its bytes, and an MP4's video by its codec ([Mp4Video]) -- names it by its hash, sharing the bytes of
     * a file of the same hash already stored, and marks the record [DFSTAT.ready]. A file that fails a check -- one whose
     * boxes are no MP4 we can read among them -- is removed, record and bytes, and the refusal says why: what was sent is
     * not a file this record can be.
     *
     * **It may be called again after a failure part-way**, a store's or the network's: that the chunks were put together
     * is recorded in the upload's row (`completed`) the moment it is done, so a second call takes up from where the file
     * is -- staged, or already under its hash -- rather than asking the store to complete an upload it has completed.
     */
    fun finish(cxt: KdrCxt, fileId: String): Map<String, Any?> {
        val row = ownUpload(cxt, fileId)
        val upload = readUploadNow(cxt, fileId) ?: throw notUploading(row)
        requireLive(cxt, upload)
        val declared = upload[GDU.declaredSize].toOptLong() ?: 0L
        val reached = upload[GDU.nextOffset].toOptLong() ?: 0L
        if (reached != declared) {
            throw KdrException(
                "The upload of '$fileId' has taken $reached of its $declared bytes; send the rest before finishing it.",
                code = EXC.conflict,
                extraData = linkedMapOf(DFILE.nextOffset to reached),
            )
        }
        val file = fileOf(row)
        val kind = FileKinds.byId(file[DFILE.kind].toOptStr().orEmpty())
            ?: throw KdrException("The record of '$fileId' names no kind of file.")
        val progress = upload[GDU.progress].toJsonMapOrEmpty()
        val hash = Sha256.fromState(progress[GDU.hashState].toJsonMapOrEmpty()).hexDigest()
        val files = FileStoreService.get(cxt).forOwner(cxt, ownerOf(row))
        val stagingKey = upload[GDU.stagingKey].toOptStr().orEmpty()
        val contentKey = contentKeyOf(row, hash)
        if (progress[GDU.completed] != true) {
            val parts = progress[GDU.parts].toJsonListOfMaps().map {
                StagedPart(it[GDU.number].toOptLong()?.toInt() ?: 0, it[GDU.size].toOptLong() ?: 0L, it[GDU.tag].toOptStr().orEmpty())
            }
            files.completeStaged(
                cxt, DFILE.bucket, stagingKey, upload[GDU.token].toOptStr().orEmpty(), parts, kind.mimeType, hash,
                fileName = file[DFILE.name].toOptStr(),
            )
            markCompleted(cxt, fileId)
        }
        // Where the whole file is now: still staged, or -- a call before this one moved it -- under its hash.
        val staged = files.meta(cxt, DFILE.bucket, stagingKey) != null
        if (!staged && files.meta(cxt, DFILE.bucket, contentKey) == null) {
            endUpload(cxt, row)
            GedraDataService.get(cxt).deleteGedra(cxt, fileId, GedraDataType.dataFile, ReadScope.unrestricted)
            throw KdrException("The file put together for '$fileId' is no longer in the store; upload it again.", code = EXC.conflict)
        }
        val source = if (staged) stagingKey else contentKey
        val problem = try {
            contentProblem(cxt, files, source, kind) ?: PlanLimits.fileRoomProblem(cxt, row.client)
        } catch (e: KdrException) {
            // A file the checks cannot read -- an MP4 with no index we take -- is refused as one that fails them.
            if (e.code != EXC.badInput) throw e
            e.message
        }
        if (problem != null) {
            // The bytes under the hash may be another record's too, so only a staged copy goes.
            if (staged) files.delete(cxt, DFILE.bucket, stagingKey)
            endUpload(cxt, row)
            GedraDataService.get(cxt).deleteGedra(cxt, fileId, GedraDataType.dataFile, ReadScope.unrestricted)
            throw KdrException.mkInput(problem)
        }
        if (staged) {
            // Named by its hash: a file of the same hash already stored is these bytes, so the staged copy goes.
            if (files.meta(cxt, DFILE.bucket, contentKey) != null) {
                files.delete(cxt, DFILE.bucket, stagingKey)
            } else {
                files.move(cxt, DFILE.bucket, stagingKey, contentKey)
            }
        }
        val ready = file - DFILE.expiresAt + mapOf(DFILE.size to declared, DFILE.hash to hash, DFILE.status to DFSTAT.ready)
        writeRecord(cxt, row, ready, endingUpload = true)
        return info(readable(cxt, fileId))
    }

    /**
     * What is wrong with the file at [key] as one of [kind], or null: its first bytes are another kind, or none we take;
     * or, for an MP4, its video is not H.264. Its record is read once, and every range after it straight from the store.
     */
    private fun contentProblem(cxt: KdrCxt, files: FileStoreService.FileStoreView, key: String, kind: FileKind): String? {
        val content = files.streamed(cxt, DFILE.bucket, key)
            ?: throw KdrException("The file put together at '$key' is not there.")
        fun read(offset: Long, length: Int): ByteArray =
            if (length == 0) ByteArray(0) else content.open(offset, length.toLong()).use { it.readAllBytes() }
        val found = FileKindSniff.sniff(read(0, minOf(content.size, FileKindSniff.headSize.toLong()).toInt()))
        if (found?.id != kind.id) {
            val what = found?.let { "a ${it.label} file" } ?: "none of the kinds a file may be"
            return "This file was sent as a ${kind.label} file, but its content is $what."
        }
        if (kind.id == FKIND.mp4) return Mp4Video.h264Problem(Mp4Video.videoCodecs(content.size, ::read))
        return null
    }

    /** Ends the upload of [fileId] without a file: drops its chunks and removes its record. Whether there was one. */
    fun abort(cxt: KdrCxt, fileId: String): Boolean {
        val row = ownUpload(cxt, fileId)
        val upload = readUploadNow(cxt, fileId) ?: return false
        FileStoreService.get(cxt).forOwner(cxt, ownerOf(row))
            .abortStaged(cxt, DFILE.bucket, upload[GDU.stagingKey].toOptStr().orEmpty(), upload[GDU.token].toOptStr().orEmpty())
        endUpload(cxt, row)
        return GedraDataService.get(cxt).deleteGedra(cxt, fileId, GedraDataType.dataFile, ReadScope.unrestricted)
    }

    // --- reading and removing -------------------------------------------------------------------------------------

    /**
     * The content of [fileId], sent a range at a time: shown in place when its kind is (an image, a video), else offered
     * as a download under its name. A file still uploading has none yet.
     */
    fun content(cxt: KdrCxt, fileId: String): StreamedContent {
        val row = viewable(cxt, fileId)
        val file = fileOf(row)
        val hash = file[DFILE.hash].toOptStr()
        if (file[DFILE.status] == DFSTAT.uploading || hash == null) {
            throw KdrException("The file '$fileId' is still being uploaded.", code = EXC.conflict)
        }
        val kind = FileKinds.byId(file[DFILE.kind].toOptStr().orEmpty())
        val inLine = kind?.disposition == FileDisposition.inline
        val name = file[DFILE.name].toOptStr() ?: "file.${kind?.extension ?: "bin"}"
        return FileStoreService.get(cxt).forOwner(cxt, ownerOf(row))
            .streamed(cxt, DFILE.bucket, contentKeyOf(row, hash), inLine = inLine, saveAsFilename = name)
            ?: throw KdrException.mkFileIo("The content of the file '$fileId' is not in the store.", null, EXC.notFound)
    }

    /**
     * Removes [fileId] for a caller whose read scope admits it -- its owner, or an administrator of its client --
     * aborting its upload when one is under way. The record is soft-deleted, as a form is. The bytes stay: another file
     * of the same hash may be them, and the sweep (#1839) takes those no record names. Refused (409) while a live form's
     * field names it ([DataFileDeleteGuard]), under the file's lock.
     */
    fun delete(cxt: KdrCxt, fileId: String): Boolean {
        val row = readable(cxt, fileId)
        readUploadNow(cxt, fileId)?.let { upload ->
            FileStoreService.get(cxt).forOwner(cxt, ownerOf(row))
                .abortStaged(cxt, DFILE.bucket, upload[GDU.stagingKey].toOptStr().orEmpty(), upload[GDU.token].toOptStr().orEmpty())
            endUpload(cxt, row)
        }
        return GedraDataService.get(cxt).deleteGedra(cxt, fileId, GedraDataType.dataFile, ReadScopeRules.forCaller(cxt))
    }

    /**
     * The bytes [client]'s live data files take: each one's size, one being uploaded at the size it said -- unless its
     * upload ran past its time, which no longer takes chunks and so holds no room. Read from the gedra cache's index of the
     * client's rows, in memory, so it costs no query however many files the client holds.
     */
    fun usedBytes(cxt: KdrCxt, client: String): Long {
        val service = GedraDataService.get(cxt)
        val now = cxt.instanceNow()
        val ids = service.liveGedraIds(cxt, GedraDataType.dataFile, client)
        return service.readGedras(cxt, GedraDataType.dataFile, ids, ReadScope.unrestricted).values.sumOf { row ->
            val file = fileOf(row)
            val abandoned = file[DFILE.status] == DFSTAT.uploading && (file[DFILE.expiresAt].toOptInstant()?.let { it < now } == true)
            if (abandoned) 0L else file[DFILE.size].toOptLong() ?: 0L
        }
    }

    /** [bytes] as whole megabytes, rounded up: what a plan counts. */
    fun megabytesOf(bytes: Long): Int = ((bytes + mebibyte - 1) / mebibyte).toInt()

    // --- the rows ---------------------------------------------------------------------------------------------------

    private fun uploadTable(cxt: KdrCxt): KdrTable = cxt.getGlobalSchema().tables[GDT.gedraFileUploads]
        ?: throw KdrException("${GDT.gedraFileUploads} table is not registered in the schema store.")

    /** [fileId]'s upload row, read inside a transaction already open on [sqlCxt]. */
    private fun readUpload(cxt: KdrCxt, sqlCxt: SqlCxt, fileId: String): Map<String, Any?>? {
        val table = uploadTable(cxt)
        return sqlCxt.sqlDb.queryOneEnabled(cxt, SqlTopicUtil.mkTableSelectStmt(sqlCxt, table), mapOf(GD.gedraId to fileId))
    }

    /** [fileId]'s upload row, read in a session of its own: outside the lock, for what a request does before taking it. */
    private fun readUploadNow(cxt: KdrCxt, fileId: String): Map<String, Any?>? {
        val sqlCxt = SqlTopicService.mkSqlCxt(cxt, gedraDataTopic)
        var upload: Map<String, Any?>? = null
        sqlCxt.sqlDb.withSession(cxt) { upload = readUpload(cxt, sqlCxt, fileId) }
        return upload
    }

    private fun writeUpload(cxt: KdrCxt, sqlCxt: SqlCxt, updated: MutableMap<String, Any?>, prior: Map<String, Any?>) {
        val table = uploadTable(cxt)
        SqlTopicUtil.prepForStdUpdate(cxt, table, updated, prior)
        sqlCxt.sqlDb.executeStatement(cxt, SqlTopicUtil.mkTableUpdateStmt(sqlCxt, table), updated)
    }

    /** Ends [row]'s upload row, under the file's lock. */
    private fun endUpload(cxt: KdrCxt, row: GedraDataRow) {
        val txCxt = cxt.mkTransactionSubContext(tranFinish)
        val sqlCxt = SqlTopicService.mkSqlCxt(txCxt, gedraDataTopic)
        SqlTopicTranProvider.executeTopicTran(sqlCxt, tranFinish, null, mapOf(GD.gedraId to row.gedraId.fullId)) {
            deleteUpload(txCxt, sqlCxt, row.gedraId.fullId)
        }
    }

    /** Removes [fileId]'s upload row: an upload that ended keeps nothing, its parts and hash state of no further use. */
    private fun deleteUpload(cxt: KdrCxt, sqlCxt: SqlCxt, fileId: String) {
        val table = uploadTable(cxt)
        val stmt = SqlStmtUtil.prepareSql(
            sqlCxt, "dGedraFileUpload", table.columns, "delete from t:${GDT.gedraFileUploads} where c:${GD.gedraId} = :${GD.gedraId}",
        )
        sqlCxt.sqlDb.executeStatement(cxt, stmt, mapOf(GD.gedraId to fileId))
    }

    /** Records, under the file's lock, that the store has put [fileId]'s chunks together: what [finish] resumes from. */
    private fun markCompleted(cxt: KdrCxt, fileId: String) {
        val txCxt = cxt.mkTransactionSubContext(tranFinish)
        val sqlCxt = SqlTopicService.mkSqlCxt(txCxt, gedraDataTopic)
        SqlTopicTranProvider.executeTopicTran(sqlCxt, tranFinish, null, mapOf(GD.gedraId to fileId)) {
            val current = readUpload(txCxt, sqlCxt, fileId) ?: return@executeTopicTran
            val updated = current.toMutableMap()
            updated[GDU.progress] = current[GDU.progress].toJsonMapOrEmpty() + (GDU.completed to true)
            writeUpload(txCxt, sqlCxt, updated, current)
        }
    }

    /** Writes [file] as [row]'s record under its lock, ending its upload row in the same transaction when [endingUpload]. */
    private fun writeRecord(cxt: KdrCxt, row: GedraDataRow, file: Map<String, Any?>, endingUpload: Boolean) {
        val txCxt = cxt.mkTransactionSubContext(tranFinish)
        val sqlCxt = SqlTopicService.mkSqlCxt(txCxt, gedraDataTopic)
        val data = GedraDataService.get(cxt)
        val table = data.gedraDataTable(txCxt)
        SqlTopicTranProvider.executeTopicTran(sqlCxt, tranFinish, null, mapOf(GD.gedraId to row.gedraId.fullId)) {
            val current = sqlCxt.sqlDb.queryOneEnabled(txCxt, SqlTopicUtil.mkTableSelectStmt(sqlCxt, table), mapOf(GD.gedraId to row.gedraId.fullId))
                ?: throw KdrException("The file '${row.gedraId}' was removed while its upload finished.", code = EXC.conflict)
            val stored = current[GD.data].toJsonMapOrEmpty() + (DFILE.file to file)
            val bind = mutableMapOf<String, Any?>(GD.gedraId to row.gedraId.fullId, GD.data to stored)
            SqlTopicUtil.prepForStdUpdate(txCxt, table, bind, current)
            sqlCxt.sqlDb.executeStatement(
                txCxt, data.mkScopedGedraUpdate(sqlCxt, table, ReadScope.unrestricted, "uDataFileRecord", "c:${GD.data} = :${GD.data}", bind), bind,
            )
            if (endingUpload) deleteUpload(txCxt, sqlCxt, row.gedraId.fullId)
        }
    }

    private fun uploadAnswer(fileId: String, status: String, upload: Map<String, Any?>): Map<String, Any?> = linkedMapOf(
        DFILE.fileId to fileId,
        DFILE.status to status,
        DFILE.chunkSize to upload[GDU.chunkSize].toOptLong(),
        DFILE.declaredSize to upload[GDU.declaredSize].toOptLong(),
        DFILE.nextOffset to upload[GDU.nextOffset].toOptLong(),
        DFILE.expiresAt to upload[GDU.expiresAt].toOptInstant()?.formatDate(),
    )

    private fun requireLive(cxt: KdrCxt, upload: Map<String, Any?>) {
        val expires = upload[GDU.expiresAt].toOptInstant() ?: return
        if (cxt.instanceNow() > expires) {
            throw KdrException("This upload ran past its ${DFILE.uploadHours} hours and is no longer taken; begin it again.", code = EXC.conflict)
        }
    }

    private fun notUploading(row: GedraDataRow): KdrException =
        KdrException("The file '${row.gedraId}' is not being uploaded.", code = EXC.conflict)
}
