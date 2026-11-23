package com.dynamicruntime.common.datafile

import com.dynamicruntime.common.context.KdrCxt
import com.dynamicruntime.common.annotation.KdrInternal
import com.dynamicruntime.common.context.ReadScope
import com.dynamicruntime.common.exception.EXC
import com.dynamicruntime.common.endpoint.ListPage
import com.dynamicruntime.common.exception.KdrException
import com.dynamicruntime.common.filekind.FileDisposition
import com.dynamicruntime.common.filekind.FileKind
import com.dynamicruntime.common.filekind.FileKindSniff
import com.dynamicruntime.common.filekind.FileKinds
import com.dynamicruntime.common.filekind.Mp4Video
import com.dynamicruntime.common.filekind.FKIND
import com.dynamicruntime.common.filestore.FNAME
import com.dynamicruntime.common.filestore.FileMeta
import com.dynamicruntime.common.filestore.FileBucketDef
import com.dynamicruntime.common.filestore.FileStoreService
import com.dynamicruntime.common.filestore.StagedPart
import com.dynamicruntime.common.filestore.StagedUpload
import com.dynamicruntime.common.gedra.GD
import com.dynamicruntime.common.gedra.GDT
import com.dynamicruntime.common.gedra.GDU
import com.dynamicruntime.common.gedra.GedraDataRow
import com.dynamicruntime.common.gedra.GedraDataService
import com.dynamicruntime.common.gedra.GedraDataType
import com.dynamicruntime.common.gedra.GedraIdContext
import com.dynamicruntime.common.gedra.GedraService
import com.dynamicruntime.common.gedra.LogGedra
import com.dynamicruntime.common.gedra.PlanLimits
import com.dynamicruntime.common.gedra.configOwnerOf
import com.dynamicruntime.common.gedra.gedraDataTopic
import com.dynamicruntime.common.gedra.mkGedraId
import com.dynamicruntime.common.gedra.sandboxOf
import com.dynamicruntime.common.http.request.StreamedContent
import com.dynamicruntime.common.sql.KdrTable
import com.dynamicruntime.common.sql.PF
import com.dynamicruntime.common.sql.SqlCxt
import com.dynamicruntime.common.sql.SqlStmtUtil
import com.dynamicruntime.common.sql.SqlTopicService
import com.dynamicruntime.common.sql.SqlTopicTranProvider
import com.dynamicruntime.common.sql.SqlTopicUtil
import com.dynamicruntime.common.table.Csv
import com.dynamicruntime.common.user.ReadScopeRules
import com.dynamicruntime.common.user.UserService
import com.dynamicruntime.common.util.Sha256
import com.dynamicruntime.common.util.formatDate
import com.dynamicruntime.common.util.mkUniqueId
import com.dynamicruntime.common.util.sha256Hex
import com.dynamicruntime.common.util.toJsonListOfMaps
import com.dynamicruntime.common.util.toJsonMapOrEmpty
import com.dynamicruntime.common.util.toOptInstant
import com.dynamicruntime.common.util.toOptInstantOrNull
import com.dynamicruntime.common.util.toOptLong
import com.dynamicruntime.common.util.toOptStr
import kotlin.time.Duration.Companion.hours
import kotlin.time.Instant

/**
 * **Data files** (issue #1837): files uploaded for a form's data, each held by a record of its own -- a `GedraData` row
 * of kind [GedraDataType.dataFile], its metadata under `data.file` -- whose id a trait's file field names. The record
 * is what is owned, scoped, cached, and soft-deleted, as any gedra is; the bytes are in the file store, named by their
 * SHA-256 under the client's folder (`clients/<client>/files/<hash>`), so two uploads of one file share them.
 *
 * **An upload comes in chunks** of [DFILE.chunkBytes], in order, so a lost connection costs a chunk and not the file:
 * [begin] says how large each must be; [chunk] takes one at the offset the upload has reached, checked against the
 * SHA-256 the sender says it has, and advances the hash of the whole -- saved in the upload's row, since the next chunk
 * may reach another node; [status] says where to resume. The chunk that completes the file finishes it ([finish]): puts
 * the chunks together, names the file by its hash, and checks its bytes are the kind it was sent as -- and, for an MP4,
 * that its video is H.264 ([Mp4Video]). A file of one chunk or less needs none of that ceremony: [uploadWhole] takes it
 * in one request (issue #1840).
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
     *
     * **The chunk that brings the upload to its declared size finishes it** ([finish]), in the same request (issue
     * #1840), and the answer carries the file's record under [DFILE.file]. A finish that failed part-way is taken up again
     * by sending that chunk again, which is answered as taken and finishes; one sent again after the file is finished is
     * answered with its record.
     */
    fun chunk(cxt: KdrCxt, fileId: String, offset: Long, chunkHash: String, bytes: ByteArray): Map<String, Any?> {
        val row = ownUpload(cxt, fileId)
        // No upload row: the file is finished -- this chunk's answer was lost -- or was never one being uploaded.
        val upload = readUploadNow(cxt, fileId) ?: return endedAnswer(row, offset, bytes.size.toLong())
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
        // Already taken, or not yet reachable: answered without writing anything -- and finished, when it is all taken.
        settled(row, upload, offset, chunkHash)?.let { return finishedWhenWhole(cxt, fileId, declared, it) }
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
            // Merged, so what else the row records of the upload -- a claim to finish it -- is kept.
            updated[GDU.progress] = progress + mapOf(GDU.parts to parts, GDU.hashState to hash.toState())
            writeUpload(txCxt, txSql, updated, current)
            answer = uploadAnswer(fileId, DFSTAT.uploading, updated)
        }
        val taken = answer ?: throw KdrException("The chunk at $offset of '$fileId' was neither taken nor refused.")
        return finishedWhenWhole(cxt, fileId, declared, taken)
    }

    /** [answer], a chunk's, when the upload has not reached [declared] bytes; else the upload finished, with its record. */
    private fun finishedWhenWhole(cxt: KdrCxt, fileId: String, declared: Long, answer: Map<String, Any?>): Map<String, Any?> {
        if (answer[DFILE.nextOffset].toOptLong() != declared) return answer
        return finishedAnswer(finish(cxt, fileId))
    }

    /** A chunk's answer for a finished file: where the upload ended, and the file's [record] under [DFILE.file]. */
    private fun finishedAnswer(record: Map<String, Any?>): Map<String, Any?> = linkedMapOf(
        DFILE.fileId to record[DFILE.fileId],
        DFILE.status to record[DFILE.status],
        DFILE.declaredSize to record[DFILE.size],
        DFILE.nextOffset to record[DFILE.size],
        DFILE.file to record,
    )

    /**
     * A chunk's answer for [row], which has no upload under way: its record when it is finished and the chunk, [count]
     * bytes at [offset], is its last -- the one whose answer was lost -- else a 409. Any other chunk of a finished file
     * is not one it took.
     */
    private fun endedAnswer(row: GedraDataRow, offset: Long, count: Long): Map<String, Any?> {
        val file = fileOf(row)
        if (file[DFILE.status] == DFSTAT.uploading || offset + count != file[DFILE.size].toOptLong()) throw notUploading(row)
        return finishedAnswer(info(row))
    }

    /**
     * Uploads [bytes], a whole file sent as [mimeType] and called [fileName], in one request (issue #1840): what [begin],
     * one [chunk], and its finish do, for a file of at most [DFILE.chunkBytes] -- an image, a document -- that a resume
     * would gain nothing for. Answers the file's record; refused as the three are, and a file refused leaves nothing.
     */
    fun uploadWhole(cxt: KdrCxt, fileName: String?, mimeType: String, bytes: ByteArray): Map<String, Any?> {
        if (bytes.size > DFILE.chunkBytes) {
            throw KdrException(
                "A file sent in one request is at most ${DFILE.chunkBytes} bytes; upload this one in chunks.", code = EXC.payloadTooLarge,
            )
        }
        val fileId = begin(cxt, fileName, mimeType, bytes.size.toLong())[DFILE.fileId].toOptStr()
            ?: throw KdrException("An upload began with no file id.")
        val answer = try {
            chunk(cxt, fileId, 0L, bytes.sha256Hex(), bytes)
        } catch (e: Throwable) {
            // Nobody holds its id to resume it, so whatever stopped it, it goes -- unless its refusal took it already.
            abandon(cxt, fileId)
            throw e
        }
        return answer[DFILE.file].toJsonMapOrEmpty()
    }

    /** Aborts the upload of [fileId], which nobody will resume, saying so in the log when that fails too. */
    private fun abandon(cxt: KdrCxt, fileId: String) {
        try {
            abort(cxt, fileId)
        } catch (e: Exception) {
            // Removed already, as a refused file is: nothing to do.
            if ((e as? KdrException)?.code == EXC.notFound) return
            LogGedra.warn(cxt) { "Could not abort the upload of '$fileId' after it failed, leaving it to the sweep: ${e.message}" }
        }
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
     * Finishes the upload of [fileId], all its chunks taken -- run by the [chunk] that takes the last of them: puts them
     * together, checks the result is the kind it was sent as -- by its bytes, and an MP4's video by its codec ([Mp4Video])
     * -- names it by its hash, sharing the bytes of a file of the same hash already stored, and marks the record
     * [DFSTAT.ready]. A file that fails a check -- one whose boxes are no MP4 we can read among them -- is removed, record
     * and bytes, and the refusal says why: what was sent is not a file this record can be.
     *
     * **One request finishes an upload at a time** ([claimFinish]): another is a 429, to be sent again shortly. The
     * claim is renewed between the steps, so a long finish keeps it.
     *
     * **It may run again after a failure part-way**, a store's or the network's: that the chunks were put together
     * is recorded in the upload's row (`completed`) the moment it is done, so a second run takes up from where the file
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
                "The upload of '$fileId' has taken $reached of its $declared bytes; send the rest to finish it.",
                code = EXC.conflict,
                extraData = linkedMapOf(DFILE.nextOffset to reached),
            )
        }
        val claim = claimFinish(cxt, fileId)
        try {
            // Read again now the claim is held: a finish that held it before may have got further.
            val claimed = readUploadNow(cxt, fileId) ?: throw notUploading(row)
            return finishClaimed(cxt, row, claimed, declared, claim)
        } catch (e: Throwable) {
            // So the last chunk sent again finishes it now, rather than once the claim runs out.
            runCatching { releaseFinish(cxt, fileId, claim) }
            throw e
        }
    }

    /**
     * [finish], once this request holds the claim to it ([claim]): every step after the chunks are all taken, the claim
     * renewed between them, so a finish whose steps take long in all is not taken for one that stopped.
     */
    private fun finishClaimed(
        cxt: KdrCxt, row: GedraDataRow, upload: Map<String, Any?>, declared: Long, claim: String,
    ): Map<String, Any?> {
        val fileId = row.gedraId.fullId
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
            markCompleted(cxt, fileId, claim, hash)
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
        renewFinish(cxt, fileId, claim)
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
     * or, for an MP4, its video is not H.264; or, for a kind told by parsing ([FileKinds.parsedKinds]), its bytes are
     * another kind's or do not parse as it. Its record is read once, and every range after it straight from the store.
     */
    private fun contentProblem(cxt: KdrCxt, files: FileStoreService.FileStoreView, key: String, kind: FileKind): String? {
        val content = files.streamed(cxt, DFILE.bucket, key)
            ?: throw KdrException("The file put together at '$key' is not there.")
        fun read(offset: Long, length: Int): ByteArray =
            if (length == 0) ByteArray(0) else content.open(offset, length.toLong()).use { it.readAllBytes() }
        val found = FileKindSniff.sniff(read(0, minOf(content.size, FileKindSniff.headSize.toLong()).toInt()))
        if (FileKinds.isParsed(kind)) return parsedProblem(kind, found, content.size, ::read)
        if (found?.id != kind.id) {
            val what = found?.let { "a ${it.label} file" } ?: "none of the kinds a file may be"
            return "This file was sent as a ${kind.label} file, but its content is $what."
        }
        if (kind.id == FKIND.mp4) return Mp4Video.h264Problem(Mp4Video.videoCodecs(content.size, ::read))
        return null
    }

    /**
     * What is wrong with a file of [size] bytes, sent as [kind], a kind told by parsing: its first bytes are another
     * kind's mark ([found]), or, read whole through [read], it is not the kind. A CSV table must be UTF-8 that
     * [Csv.parseBytes] reads, its first problem the one a person is told. Reading it whole is safe because the begin
     * held its size to the kind's largest, and every chunk to that size.
     *
     * Theme: #DTABL.
     */
    private fun parsedProblem(kind: FileKind, found: FileKind?, size: Long, read: (Long, Int) -> ByteArray): String? {
        if (found != null) return "This file was sent as a ${kind.label} file, but its content is a ${found.label} file."
        if (kind.id != FKIND.csv) throw KdrException("No check reads a file of the kind '${kind.id}'.")
        val parsed = Csv.parseBytes(read(0, size.toInt()))
        return parsed.problems.firstOrNull()?.let { "This file is not a CSV table we can read. ${it.message}" }
    }

    /** Ends the upload of [fileId] without a file: drops its chunks and removes its record. Whether there was one. */
    fun abort(cxt: KdrCxt, fileId: String): Boolean {
        val row = ownUpload(cxt, fileId)
        val upload = readUploadNow(cxt, fileId) ?: return false
        dropStaged(cxt, row, upload)
        endUpload(cxt, row)
        return GedraDataService.get(cxt).deleteGedra(cxt, fileId, GedraDataType.dataFile, ReadScope.unrestricted)
    }

    /**
     * Drops what the store holds of [row]'s [upload]: its parts, and the file put together from them when a finish that
     * failed part-way got that far -- which nothing else names, so nothing else would remove. The bytes under the file's
     * hash stay, since another record may be them.
     */
    private fun dropStaged(cxt: KdrCxt, row: GedraDataRow, upload: Map<String, Any?>) = dropStaged(cxt, ownerOf(row), upload)

    /** [dropStaged] for an [upload] whose files are in [owner]'s folder: what the sweep calls, which may have no record. */
    private fun dropStaged(cxt: KdrCxt, owner: String, upload: Map<String, Any?>) {
        val files = FileStoreService.get(cxt).forOwner(cxt, owner)
        val stagingKey = upload[GDU.stagingKey].toOptStr().orEmpty()
        files.abortStaged(cxt, DFILE.bucket, stagingKey, upload[GDU.token].toOptStr().orEmpty())
        if (files.meta(cxt, DFILE.bucket, stagingKey) != null) files.delete(cxt, DFILE.bucket, stagingKey)
    }

    // --- the sweep (issue #1839) -------------------------------------------------------------------------------------

    /** The upload rows of [client]: uploads under way, a small table, read from SQL. */
    @KdrInternal
    fun uploadsOf(cxt: KdrCxt, client: String): List<Map<String, Any?>> {
        val sqlCxt = SqlTopicService.mkSqlCxt(cxt, gedraDataTopic)
        val table = uploadTable(cxt)
        val stmt = SqlStmtUtil.prepareSql(
            sqlCxt, "qGedraFileUploadsByClient", table.columns,
            "select * from t:${GDT.gedraFileUploads} where c:${PF.client} = :${PF.client}",
        )
        var rows: List<Map<String, Any?>> = emptyList()
        sqlCxt.sqlDb.withSession(cxt) { rows = sqlCxt.sqlDb.queryStatement(cxt, stmt, mapOf(PF.client to client)) }
        return rows
    }

    /** [client]'s live data files, from the gedra cache's index of its rows. */
    private fun liveFiles(cxt: KdrCxt, client: String): Collection<GedraDataRow> {
        val data = GedraDataService.get(cxt)
        val ids = data.liveGedraIds(cxt, GedraDataType.dataFile, client)
        return data.readGedras(cxt, GedraDataType.dataFile, ids, ReadScope.unrestricted).values
    }

    /** The clients whose files are kept in [owner]'s folder: it, and its sandbox, whose files are its parent's. */
    private fun foldersClients(owner: String): List<String> = listOf(owner, sandboxOf(owner))

    /**
     * Whether [fileId] is an upload past its time ([DFSTAT.uploading], its [GDU.expiresAt] gone by, and no finish holding
     * it), which [sweepUpload] would remove. Read again at the moment, so an upload that moved on since a listing is left.
     */
    private fun expiredUpload(cxt: KdrCxt, fileId: String, row: GedraDataRow?, upload: Map<String, Any?>?): Boolean {
        if (row == null && upload == null) return false
        if (row != null && fileOf(row)[DFILE.status] != DFSTAT.uploading && upload == null) return false
        val now = cxt.instanceNow()
        val expires = upload?.get(GDU.expiresAt).toOptInstant()
            ?: row?.let { fileOf(it)[DFILE.expiresAt].toOptInstantOrNull() }
            ?: return false
        if (expires >= now) return false
        // A finish under way past the hour holds a claim; the sweep leaves it to finish.
        val finishing = upload?.get(GDU.progress).toJsonMapOrEmpty()[GDU.finishingAt].toOptInstantOrNull()
        return finishing == null || (now - finishing).inWholeMilliseconds >= DFILE.finishLeaseMs
    }

    /** The ids of [client]'s uploads past their time: records still uploading, and upload rows, whose time has gone by. */
    @KdrInternal
    fun expiredUploads(cxt: KdrCxt, client: String): List<String> {
        val now = cxt.instanceNow()
        val records = liveFiles(cxt, client).filter { fileOf(it)[DFILE.status] == DFSTAT.uploading }
            .filter { fileOf(it)[DFILE.expiresAt].toOptInstantOrNull()?.let { at -> at < now } == true }
            .map { it.gedraId.fullId }
        val rows = uploadsOf(cxt, client).filter { it[GDU.expiresAt].toOptInstant()?.let { at -> at < now } == true }
            .mapNotNull { it[GD.gedraId].toOptStr() }
        return (records + rows).distinct().sorted()
    }

    /**
     * Removes the upload of [fileId] when it is past its time (issue #1839): the store's parts and anything put together
     * from them, its row, and its record, which held no bytes yet. Whether it did, or with [dryRun] would; asked again
     * here, so one that finished or moved on since it was listed is left.
     */
    @KdrInternal
    fun sweepUpload(cxt: KdrCxt, fileId: String, dryRun: Boolean): Boolean {
        val row = GedraDataService.get(cxt).queryGedra(cxt, fileId, GedraDataType.dataFile, ReadScope.unrestricted)
        val upload = readUploadNow(cxt, fileId)
        if (!expiredUpload(cxt, fileId, row, upload)) return false
        if (dryRun) return true
        val client = row?.client ?: upload?.get(PF.client).toOptStr() ?: return false
        upload?.let { dropStaged(cxt, configOwnerOf(client), it) }
        endUpload(cxt, fileId)
        if (row != null && fileOf(row)[DFILE.status] == DFSTAT.uploading) {
            GedraDataService.get(cxt).deleteGedra(cxt, fileId, GedraDataType.dataFile, ReadScope.unrestricted)
        }
        return true
    }

    /**
     * The ids of [client]'s files no live form links (issue #1839), [DFSTAT.ready] and unused since before [cutoff]
     * ([unusedSince]). A deleted form's link does not keep a file: nothing restores a deleted form.
     */
    @KdrInternal
    fun unlinkedFiles(cxt: KdrCxt, client: String, cutoff: Instant): List<String> {
        val live = DataFileLinks.liveFormCounts(cxt, client).keys
        val anyLink = DataFileLinks.linkedFileIds(cxt, client)
        return liveFiles(cxt, client)
            .filter { fileOf(it)[DFILE.status] == DFSTAT.ready && it.gedraId.fullId !in live }
            // Only a file a deleted form still names asks SQL when that form went.
            .filter { row ->
                val deletedAt = if (row.gedraId.fullId in anyLink) DataFileLinks.linkState(cxt, row.gedraId.fullId).deletedAt else null
                unusedSince(row, deletedAt)?.let { it < cutoff } == true
            }
            .map { it.gedraId.fullId }.sorted()
    }

    /**
     * Since when [row]'s file has been unused (issue #1839): the latest of its upload, the last time a form stopped
     * naming it ([DFILE.unlinkedAt]), and [deletedAt], when the last deleted form that named it went. The grace runs from
     * it, so a file replaced or a form deleted by mistake keeps a whole grace for the undo, however old the file is.
     */
    private fun unusedSince(row: GedraDataRow, deletedAt: Instant?): Instant? {
        val file = fileOf(row)
        val uploaded = file[DFILE.uploadedAt].toOptInstantOrNull()
        return listOfNotNull(uploaded, file[DFILE.unlinkedAt].toOptInstantOrNull(), deletedAt).maxOrNull()
    }

    /**
     * Removes [fileId], a file no live form links, unused since before [cutoff] (issue #1839): soft-deleted as any removal
     * is, its bytes left for [sweepObject] once nothing names them. Asked again here -- the record, then its links -- and
     * once more under the file's lock by the removal's own guard ([DataFileDeleteGuard]), so a form that linked it since
     * it was listed keeps it. Whether it did, or with [dryRun] would.
     */
    @KdrInternal
    fun sweepUnlinked(cxt: KdrCxt, fileId: String, cutoff: Instant, dryRun: Boolean): Boolean {
        val data = GedraDataService.get(cxt)
        val row = data.queryGedra(cxt, fileId, GedraDataType.dataFile, ReadScope.unrestricted) ?: return false
        if (fileOf(row)[DFILE.status] != DFSTAT.ready) return false
        val links = DataFileLinks.linkState(cxt, fileId)
        if (links.live || unusedSince(row, links.deletedAt)?.let { it < cutoff } != true) return false
        if (dryRun) return true
        return try {
            data.deleteGedra(cxt, fileId, GedraDataType.dataFile, ReadScope.unrestricted)
        } catch (e: KdrException) {
            // A form linked it under the lock: it is no orphan.
            if (e.code != EXC.conflict) throw e
            false
        }
    }

    /**
     * The hashes whose bytes something in [owner]'s folder still needs (issue #1839): every live record's of the owner
     * and its sandbox, and every upload's whose chunks are put together but whose record does not say so yet.
     */
    private fun namedHashes(cxt: KdrCxt, owner: String): Set<String> {
        val clients = foldersClients(owner)
        val records = clients.flatMap { liveFiles(cxt, it) }.mapNotNull { fileOf(it)[DFILE.hash].toOptStr() }
        val finishing = clients.flatMap { uploadsOf(cxt, it) }
            .mapNotNull { it[GDU.progress].toJsonMapOrEmpty()[GDU.contentHash].toOptStr() }
        return (records + finishing).toSet()
    }

    /** The staging keys of the uploads under way in [owner]'s folder. */
    private fun stagingKeys(cxt: KdrCxt, owner: String): Set<String> =
        foldersClients(owner).flatMap { uploadsOf(cxt, it) }.mapNotNull { it[GDU.stagingKey].toOptStr() }.toSet()

    /** The tokens of the staged uploads under way in [owner]'s folder. */
    private fun stagingTokens(cxt: KdrCxt, owner: String): Set<String> =
        foldersClients(owner).flatMap { uploadsOf(cxt, it) }.mapNotNull { it[GDU.token].toOptStr() }.toSet()

    /** The objects of [owner]'s folder under [sub] (`files/`, `staging/`): each a key and when it was last written. */
    private fun objectsUnder(cxt: KdrCxt, owner: String, sub: String): List<Pair<String, Instant>> {
        val prefix = "${FNAME.clientKeyPrefix(owner)}$sub"
        val store = FileStoreService.get(cxt).store
        return store.objects(cxt, DFILE.bucket, prefix)
            .filter { !it.name.endsWith(FileMeta.metaSuffix) && store.tmpKeyOf(it.name) == null }
            .map { it.name to it.modifiedAt }
    }

    /**
     * The objects of [owner]'s folder nothing needs (issue #1839): under `files/`, bytes no live record or finishing upload
     * names, last written before [filesCutoff]; under `staging/`, a file put together for an upload no longer under way,
     * last written before [stagingCutoff]. Only an owner's folder: a sandbox keeps its files in its parent's.
     */
    @KdrInternal
    fun unnamedObjects(cxt: KdrCxt, owner: String, filesCutoff: Instant, stagingCutoff: Instant): List<String> {
        val named = namedHashes(cxt, owner)
        val staged = stagingKeys(cxt, owner)
        val files = objectsUnder(cxt, owner, "files/").filter { (key, at) -> at < filesCutoff && key.substringAfterLast('/') !in named }
        val staging = objectsUnder(cxt, owner, "staging/").filter { (key, at) -> at < stagingCutoff && key !in staged }
        return (files + staging).map { it.first }.sorted()
    }

    /**
     * Removes the object at [key] in [owner]'s folder when nothing needs it still (issue #1839), asked again here of this
     * object alone: its own date, and whether a record or an upload names it now. Whether it did, or with [dryRun] would.
     */
    @KdrInternal
    fun sweepObject(cxt: KdrCxt, owner: String, key: String, filesCutoff: Instant, stagingCutoff: Instant, dryRun: Boolean): Boolean {
        // The object and its metadata are all a listing from its own key holds.
        val written = FileStoreService.get(cxt).store.objects(cxt, DFILE.bucket, key).firstOrNull { it.name == key }?.modifiedAt
            ?: return false
        val unneeded = if (key.startsWith("${FNAME.clientKeyPrefix(owner)}files/")) {
            written < filesCutoff && key.substringAfterLast('/') !in namedHashes(cxt, owner)
        } else {
            written < stagingCutoff && key !in stagingKeys(cxt, owner)
        }
        if (!unneeded) return false
        if (dryRun) return true
        return FileStoreService.get(cxt).forOwner(cxt, owner).delete(cxt, DFILE.bucket, key)
    }

    /**
     * Of [staged], the store's staged uploads of the whole bucket (listed once for a run, since the listing does not depend
     * on the client), those in [owner]'s folder that no upload under way holds (issue #1839), begun before [cutoff]: a
     * begin stopped between the store and its row, or an upload whose row went without its parts.
     */
    @KdrInternal
    fun strayStaged(cxt: KdrCxt, owner: String, staged: List<StagedUpload>, cutoff: Instant): List<StagedUpload> {
        val folder = FNAME.clientKeyPrefix(owner)
        val held = stagingTokens(cxt, owner)
        return staged.filter { it.key.startsWith(folder) && it.startedAt < cutoff && it.token !in held }.sortedBy { it.key }
    }

    /**
     * Aborts the staged upload [token] at [key], begun at [startedAt], when it is still a stray: begun before [cutoff], and
     * held by no upload row now -- which is all that can have changed since it was listed. Whether it did, or would.
     */
    @KdrInternal
    fun sweepStaged(
        cxt: KdrCxt, owner: String, key: String, token: String, startedAt: Instant, cutoff: Instant, dryRun: Boolean,
    ): Boolean {
        if (startedAt >= cutoff || token in stagingTokens(cxt, owner)) return false
        if (dryRun) return true
        return FileStoreService.get(cxt).forOwner(cxt, owner).abortStaged(cxt, DFILE.bucket, key, token)
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
            dropStaged(cxt, row, upload)
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

    /**
     * The live data files the caller may read within their own client (issue #1863), most recently written first, at
     * most [limit]: their own uploads for an ordinary user, the client's for an editor or an administrator, as their read
     * scope says -- held to the client they act in, so a caller who reaches every client lists one at a time. Each row is
     * the file's record ([info]) with its owner's name and how many live forms link it ([DataFileLinks.liveFormCounts]).
     * Only those of [kind], in [status], or -- [linked] -- that some live form links or none does, when given.
     */
    fun list(cxt: KdrCxt, limit: Int, kind: String?, status: String?, linked: Boolean?): ListPage {
        val caller = ReadScopeRules.forCaller(cxt)
        val scope = ReadScope(
            client = caller.client ?: cxt.client, org = caller.org, userId = caller.userId, identityId = caller.identityId,
        )
        val forms = DataFileLinks.liveFormCounts(cxt, scope.client ?: cxt.client)
        val page = GedraDataService.get(cxt).listGedras(cxt, GedraDataType.dataFile, scope, limit, rowFilter = { row ->
            val file = fileOf(row)
            (kind == null || file[DFILE.kind] == kind) && (status == null || file[DFILE.status] == status) &&
                (linked == null || ((forms[row.gedraId.fullId] ?: 0) > 0) == linked)
        })
        val users = UserService.get(cxt)
        val names = page.rows.mapNotNull { it.userId }.distinct().associateWith { users.queryByUserId(cxt, it)?.name }
        val items = page.rows.map { row ->
            // While it uploads, when it stops being taken, so a page tells an abandoned upload from one under way.
            val expires = fileOf(row)[DFILE.expiresAt].takeIf { fileOf(row)[DFILE.status] == DFSTAT.uploading }
            info(row) + linkedMapOf(
                DFILE.ownerName to names[row.userId], DFILE.forms to (forms[row.gedraId.fullId] ?: 0), DFILE.expiresAt to expires,
            ).filterValues { it != null }
        }
        return ListPage(items, page.numAvailable, page.numAvailable > items.size)
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
    private fun endUpload(cxt: KdrCxt, row: GedraDataRow) = endUpload(cxt, row.gedraId.fullId)

    /** Ends [fileId]'s upload row, under the file's lock. */
    private fun endUpload(cxt: KdrCxt, fileId: String) {
        val txCxt = cxt.mkTransactionSubContext(tranFinish)
        val sqlCxt = SqlTopicService.mkSqlCxt(txCxt, gedraDataTopic)
        SqlTopicTranProvider.executeTopicTran(sqlCxt, tranFinish, null, mapOf(GD.gedraId to fileId)) {
            deleteUpload(txCxt, sqlCxt, fileId)
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

    /**
     * Claims, under the file's lock, the finish of [fileId]'s upload for this request (issue #1840), recording when and by
     * whom in its row ([GDU.finishingAt], [GDU.finishingBy]); answers the claim's token. A claim another request made or
     * renewed within [DFILE.finishLeaseMs] is a 429: the last chunk sent again while the first is still being finished
     * waits for it, rather than asking the store to complete the parts a second time, which it no longer has. A claim
     * older than that was left by a request that stopped, and is taken over.
     */
    @KdrInternal
    fun claimFinish(cxt: KdrCxt, fileId: String): String {
        val claim = cxt.mkUniqueId()
        val now = cxt.instanceNow()
        changeProgress(cxt, fileId) { progress ->
            val since = progress[GDU.finishingAt].toOptInstantOrNull()
            if (since != null && (now - since).inWholeMilliseconds < DFILE.finishLeaseMs) {
                throw KdrException(
                    "The upload of '$fileId' is being finished; send its last chunk again shortly.", code = EXC.tooManyRequests,
                )
            }
            progress + mapOf(GDU.finishingAt to now.formatDate(), GDU.finishingBy to claim)
        }
        return claim
    }

    /**
     * Renews [claim] to finish [fileId]'s upload, between the finish's steps. A claim another request has taken over --
     * this one went unrenewed past [DFILE.finishLeaseMs] -- is a 409: that request is finishing the upload now.
     */
    private fun renewFinish(cxt: KdrCxt, fileId: String, claim: String) {
        val now = cxt.instanceNow()
        changeProgress(cxt, fileId) { progress ->
            if (progress[GDU.finishingBy] != claim) {
                throw KdrException("The upload of '$fileId' is being finished by another request.", code = EXC.conflict)
            }
            progress + (GDU.finishingAt to now.formatDate())
        }
    }

    /** Gives up [claim] to finish [fileId]'s upload, after a finish that failed part-way; another request's is left. */
    @KdrInternal
    fun releaseFinish(cxt: KdrCxt, fileId: String, claim: String) {
        changeProgress(cxt, fileId) { progress ->
            if (progress[GDU.finishingBy] != claim) null else progress - GDU.finishingAt - GDU.finishingBy
        }
    }

    /**
     * Records on [file]'s record, inside the open transaction [sqlCxt] of a form's write that holds the file's lock, that a
     * form stopped naming it now (issue #1839): what the sweep's grace counts from ([DFILE.unlinkedAt]).
     */
    @KdrInternal
    fun stampUnlinked(cxt: KdrCxt, sqlCxt: SqlCxt, file: GedraDataRow) {
        val data = GedraDataService.get(cxt)
        val table = data.gedraDataTable(cxt)
        val id = file.gedraId.fullId
        val current = sqlCxt.sqlDb.queryOneEnabled(cxt, SqlTopicUtil.mkTableSelectStmt(sqlCxt, table), mapOf(GD.gedraId to id)) ?: return
        val stored = current[GD.data].toJsonMapOrEmpty()
        val record = stored[DFILE.file].toJsonMapOrEmpty() + (DFILE.unlinkedAt to cxt.instanceNow().formatDate())
        val bind = mutableMapOf<String, Any?>(GD.gedraId to id, GD.data to stored + (DFILE.file to record))
        SqlTopicUtil.prepForStdUpdate(cxt, table, bind, current)
        val update =
            data.mkScopedGedraUpdate(sqlCxt, table, ReadScope.unrestricted, "uDataFileUnlinked", "c:${GD.data} = :${GD.data}", bind)
        sqlCxt.sqlDb.executeStatement(cxt, update, bind)
    }

    /**
     * Records, under the file's lock, that the store has put [fileId]'s chunks together -- what a finish resumes from --
     * renewing [claim] as it does.
     */
    private fun markCompleted(cxt: KdrCxt, fileId: String, claim: String, hash: String) {
        val now = cxt.instanceNow()
        changeProgress(cxt, fileId) { progress ->
            if (progress[GDU.finishingBy] != claim) {
                throw KdrException("The upload of '$fileId' is being finished by another request.", code = EXC.conflict)
            }
            // The hash too (issue #1839): the bytes about to go under it are this upload's, which the sweep must know.
            progress + mapOf(GDU.completed to true, GDU.finishingAt to now.formatDate(), GDU.contentHash to hash)
        }
    }

    /**
     * Changes [fileId]'s upload's progress under the file's lock: [change] answers the new progress from the current, or
     * null to leave it. Nothing happens when the upload has ended.
     */
    private fun changeProgress(cxt: KdrCxt, fileId: String, change: (Map<String, Any?>) -> Map<String, Any?>?) {
        val txCxt = cxt.mkTransactionSubContext(tranFinish)
        val sqlCxt = SqlTopicService.mkSqlCxt(txCxt, gedraDataTopic)
        SqlTopicTranProvider.executeTopicTran(sqlCxt, tranFinish, null, mapOf(GD.gedraId to fileId)) {
            val current = readUpload(txCxt, sqlCxt, fileId) ?: return@executeTopicTran
            val progress = change(current[GDU.progress].toJsonMapOrEmpty()) ?: return@executeTopicTran
            val updated = current.toMutableMap()
            updated[GDU.progress] = progress
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
