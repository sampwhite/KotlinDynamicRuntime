package com.dynamicruntime.common.datafile

import com.dynamicruntime.common.context.KdrCxt
import com.dynamicruntime.common.context.ReadScope
import com.dynamicruntime.common.exception.EXC
import com.dynamicruntime.common.exception.KdrException
import com.dynamicruntime.common.filekind.DataFileFields
import com.dynamicruntime.common.filekind.DataFileUse
import com.dynamicruntime.common.filekind.FileKinds
import com.dynamicruntime.common.gedra.GD
import com.dynamicruntime.common.gedra.GDL
import com.dynamicruntime.common.gedra.GDT
import com.dynamicruntime.common.gedra.GE
import com.dynamicruntime.common.gedra.GedraDataRow
import com.dynamicruntime.common.gedra.GedraDataService
import com.dynamicruntime.common.gedra.GedraDataType
import com.dynamicruntime.common.gedra.GedraGuardedWrite
import com.dynamicruntime.common.gedra.GedraService
import com.dynamicruntime.common.gedra.GedraWriteContext
import com.dynamicruntime.common.gedra.GedraWriteGuard
import com.dynamicruntime.common.gedra.GedraWriteHook
import com.dynamicruntime.common.gedra.LogGedra
import com.dynamicruntime.common.gedra.gedraDataTopic
import com.dynamicruntime.common.gedra.traitDataTypeIn
import com.dynamicruntime.common.schema.SchType
import com.dynamicruntime.common.schema.fileSizeLimit
import com.dynamicruntime.common.schema.isDataTable
import com.dynamicruntime.common.sql.KdrTable
import com.dynamicruntime.common.sql.PF
import com.dynamicruntime.common.sql.SqlCxt
import com.dynamicruntime.common.sql.SqlStatement
import com.dynamicruntime.common.sql.SqlStmtUtil
import com.dynamicruntime.common.sql.SqlTopicService
import com.dynamicruntime.common.sql.SqlTopicTranProvider
import com.dynamicruntime.common.sql.SqlTopicUtil
import com.dynamicruntime.common.user.ReadScopeRules
import com.dynamicruntime.common.util.toOptLong
import com.dynamicruntime.common.util.toOptStr
import com.dynamicruntime.common.util.toOptInstant
import com.dynamicruntime.common.util.formatDate
import com.dynamicruntime.common.util.humanizeFieldName
import kotlin.time.Instant

/**
 * The links between forms and the data files their fields name (issue #1838), kept in [GDT.gedraFileLinks]: one row
 * per file field of a form's entries that names a file. [DataFileLinkHook] writes them in each of the form's writes;
 * they are what refuses removing a file a live form uses ([DataFileDeleteGuard]), what lets a caller read a file
 * through a form they can read ([readableThroughLink]), and what [usedBy] answers.
 *
 * **Lock order.** A form's write holds the form's lock and takes, in sorted order, the lock of each file it newly
 * links; a file's removal takes only the file's. So no two of them each hold a lock the other waits for, and whichever
 * takes the file's lock second sees what the first committed: a save after a removal finds the file gone, a removal
 * after a save finds the link.
 *
 * **Only a live form counts.** A form soft-deleted keeps its links, since it may be brought back, but holds no file in
 * use, opens no file to a reader, and is not said to use one.
 *
 * Themes: #DFILE, #DTABL.
 */
object DataFileLinks {
    private const val tranLinkFile = "linkDataFile"

    /** The table, from the schema store. */
    fun linkTable(cxt: KdrCxt): KdrTable = cxt.getGlobalSchema().tables[GDT.gedraFileLinks]
        ?: throw KdrException("${GDT.gedraFileLinks} table is not registered in the schema store.")

    /** Where a link is: the form's entry and the field in it. One file per place. */
    private data class Place(val traitId: String, val entryKey: String, val fieldPath: String)

    private fun placeOf(link: Map<String, Any?>) = Place(
        link[GDL.traitId].toOptStr().orEmpty(), link[GDL.entryKey].toOptStr().orEmpty(), link[GDL.fieldPath].toOptStr().orEmpty(),
    )

    // --- writing them ----------------------------------------------------------------------------------------------

    /** The files [entries] name, by where each is, read by [union]'s types. */
    private fun usesOf(union: SchType, entries: List<Map<String, Any?>>): Map<Place, DataFileUse> {
        val out = LinkedHashMap<Place, DataFileUse>()
        for (entry in entries) {
            val traitId = entry[GE.traitId].toOptStr() ?: continue
            val type = traitDataTypeIn(union, traitId) ?: continue
            if (!DataFileFields.holdsFiles(type)) continue
            val entryKey = entry[GE.entryId].toOptStr() ?: continue
            for (use in DataFileFields.collect(type, entry[GE.data])) out[Place(traitId, entryKey, use.path)] = use
        }
        return out
    }

    /**
     * Brings [row]'s links in line with the files its entries name, inside its write's transaction ([sqlCxt], holding
     * the form's lock). [priorEntries] are the entries as they stood before the write.
     *
     * **Only what this write put there is judged.** A file a field names that it did not name before the write -- at
     * that place -- is locked, in sorted order, and must be: a live data file of the form's client, no longer uploading,
     * of a kind and a size its field takes, and one the caller may use (in their read scope, or already linked from this
     * form). A value the write only carried along is left as it was, linked or not: one written before its field became a
     * file field, say, was never checked, so it is never linked, and never refuses an unrelated edit. A refusal fails a
     * caller's write, as validation does; the system's own write (a recompute storing computed values) is not failed for
     * it, but leaves that field unlinked and says so in the log.
     *
     * **A client whose traits hold no file field costs no query.** The trade: a client that takes away the last of its
     * file fields keeps its forms' links as they were, holding their files from removal, until a file field returns.
     */
    fun syncLinks(cxt: KdrCxt, sqlCxt: SqlCxt, row: GedraDataRow, priorEntries: List<Map<String, Any?>>) {
        if (!row.kind.carriesEntries) return
        val union = GedraDataService.get(cxt).entryUnion(cxt, row.client, row.kind) ?: return
        if (!DataFileFields.holdsFiles(union)) return
        val wanted = usesOf(union, row.entries)
        val carried = usesOf(union, priorEntries).map { (place, use) -> place to use.fileId }.toSet()
        val table = linkTable(cxt)
        val holderId = row.gedraId.fullId
        val stored = sqlCxt.sqlDb.queryStatement(cxt, byHolderStmt(sqlCxt, table), mapOf(GDL.holderId to holderId))
        if (wanted.isEmpty() && stored.isEmpty()) return
        val storedAt = stored.associateBy { placeOf(it) }
        val candidates = wanted.filter { (place, use) ->
            storedAt[place]?.get(GDL.fileId).toOptStr() != use.fileId && (place to use.fileId) !in carried
        }
        val removes = storedAt.filter { (place, link) -> wanted[place]?.fileId != link[GDL.fileId].toOptStr() }
        val system = cxt.userProfile.authId == null
        val adds = LinkedHashMap<Place, DataFileUse>()
        val linkedHere = stored.mapNotNull { it[GDL.fileId].toOptStr() }.toSet()
        val newFiles = candidates.values.map { it.fileId }.filter { it !in linkedHere }.toSortedSet()
        // A file this form stops naming everywhere (issue #1839): its record says when, which the sweep's grace runs from.
        val stillNamed = wanted.values.map { it.fileId }.toSet()
        val droppedFiles = removes.values.mapNotNull { it[GDL.fileId].toOptStr() }.filter { it !in stillNamed }.toSortedSet()
        // One sorted pass over every file whose lock the write needs, so two forms never take them in opposite orders.
        val locked = (newFiles + droppedFiles).toSortedSet().filter { lockFile(sqlCxt, it) }.toSet()
        if (candidates.isNotEmpty()) {
            val files = candidates.values.map { it.fileId }.distinct().associateWith { readFileRow(cxt, sqlCxt, it) }
            val callerScope = if (system) null else ReadScopeRules.forCaller(cxt)
            for ((place, use) in candidates) {
                val newHere = use.fileId in newFiles
                val file = files[use.fileId]?.takeIf { !newHere || use.fileId in locked }
                val problem = problemOf(cxt, row, place, use, file, if (newHere) callerScope else null)
                when {
                    problem == null -> adds[place] = use
                    system -> LogGedra.warn(cxt) { "Left a file field of '$holderId' unlinked: $problem" }
                    else -> throw KdrException.mkInput(problem)
                }
            }
        }
        for (place in removes.keys) {
            sqlCxt.sqlDb.executeStatement(cxt, deleteStmt(sqlCxt, table), placeData(holderId, place))
        }
        for (fileId in droppedFiles.filter { it in locked }) {
            readFileRow(cxt, sqlCxt, fileId)?.let { DataFileService.stampUnlinked(cxt, sqlCxt, it) }
        }
        val insert = SqlTopicUtil.mkTableInsertStmt(sqlCxt, table)
        for ((place, use) in adds) {
            val link = placeData(holderId, place)
            link[GDL.fileId] = use.fileId
            // The form's client, said outright: the write's context may be bound to its actor's.
            link[PF.client] = row.client
            SqlTopicUtil.prepForStdExecute(cxt, table, link)
            sqlCxt.sqlDb.executeStatement(cxt, insert, link)
        }
    }

    /**
     * What keeps [use], at [place] in [row], from naming [file] -- the data file it names, read under its lock, or null
     * when there is none to use -- as a sentence for the writer; null when nothing does. [callerScope], when given, is the
     * caller's read scope, which must admit the file: given only for a file this form did not already link, and not for
     * the system's own writes.
     */
    private fun problemOf(cxt: KdrCxt, row: GedraDataRow, place: Place, use: DataFileUse, file: GedraDataRow?, callerScope: ReadScope?): String? {
        if (place.fieldPath.length > DFILE.maxFieldPathLength) {
            return "A file field's path in '${place.traitId}' is ${place.fieldPath.length} characters; at most " +
                "${DFILE.maxFieldPathLength} are kept."
        }
        val field = "'${place.fieldPath}' of '${place.traitId}'"
        val usable = file != null && file.client == row.client &&
            (callerScope == null || GedraDataService.get(cxt).admitsGedra(callerScope, file))
        if (file == null || !usable) return "The field $field names the file '${use.fileId}', which is no file you may use here."
        val record = DataFileService.fileOf(file)
        if (record[DFILE.status] == DFSTAT.uploading) {
            return "The field $field names the file '${use.fileId}', which is still being uploaded."
        }
        val kind = FileKinds.byId(record[DFILE.kind].toOptStr().orEmpty())
        val limit = kind?.let { use.field.fileSizeLimit(it) }
        if (kind == null || limit == null) {
            val takes = use.field.fileKinds.map { it.label }.let { labels ->
                if (labels.size <= 2) labels.joinToString(" or ") else labels.dropLast(1).joinToString(", ") + ", or " + labels.last()
            }
            return "The field $field takes only $takes; '${use.fileId}' is ${kind?.label ?: "none of these"}."
        }
        val size = record[DFILE.size].toOptLong() ?: 0L
        if (size > limit) return "The field $field takes a ${kind.label} file of at most $limit bytes; '${use.fileId}' is $size."
        // A table field's file is read against its columns (issue #1882): a bad row refuses the save, as a bad value does.
        if (use.field.isDataTable()) return DataTables.problem(cxt, file, use.field, field)
        return null
    }

    /** Takes [fileId]'s lock inside the open transaction; false when it names no gedra that has one. */
    private fun lockFile(sqlCxt: SqlCxt, fileId: String): Boolean {
        if (fileIdOf(sqlCxt.cxt, fileId) == null) return false
        return SqlTopicTranProvider.lockExisting(sqlCxt, tranLinkFile, mapOf(GD.gedraId to fileId))
    }

    /** The live data file [fileId] names, read from SQL inside the open transaction, or null. */
    private fun readFileRow(cxt: KdrCxt, sqlCxt: SqlCxt, fileId: String): GedraDataRow? {
        if (fileIdOf(cxt, fileId) == null) return null
        val table = GedraDataService.get(cxt).gedraDataTable(cxt)
        val raw = sqlCxt.sqlDb.queryOneEnabled(cxt, SqlTopicUtil.mkTableSelectStmt(sqlCxt, table), mapOf(GD.gedraId to fileId))
            ?: return null
        return GedraDataRow.extract(GedraService.get(cxt), raw).takeIf { it.kind == GedraDataType.dataFile }
    }

    /** [text] as a data file's id, or null when it is not one: a field's value is whatever was sent. */
    private fun fileIdOf(cxt: KdrCxt, text: String): String? = try {
        GedraService.get(cxt).readId(text).takeIf { it.dataType == GedraDataType.dataFile }?.fullId
    } catch (_: KdrException) {
        null
    }

    // --- reading them ----------------------------------------------------------------------------------------------

    /** The link rows of [stmt] for [key], read in [cxt]'s open session or one of its own. */
    private fun queryLinks(cxt: KdrCxt, key: Map<String, Any?>, stmt: (SqlCxt, KdrTable) -> SqlStatement): List<Map<String, Any?>> {
        val sqlCxt = SqlTopicService.mkSqlCxt(cxt, gedraDataTopic)
        var out: List<Map<String, Any?>> = emptyList()
        // A session already open -- a removal's transaction -- is the one used.
        sqlCxt.sqlDb.withSession(cxt) { out = sqlCxt.sqlDb.queryStatement(cxt, stmt(sqlCxt, linkTable(cxt)), key) }
        return out
    }

    /** The forms of [holderIds] that are live and that [scope] admits, by id, read as any form is: from the cache. */
    private fun formsIn(cxt: KdrCxt, holderIds: Collection<String>, scope: ReadScope): Map<String, GedraDataRow> {
        val data = GedraDataService.get(cxt)
        val gedras = GedraService.get(cxt)
        return holderIds.groupBy { gedras.readId(it).dataType }.flatMap { (kind, ids) ->
            if (kind == null) emptyList() else data.readGedras(cxt, kind, ids, scope).entries.map { it.toPair() }
        }.toMap()
    }

    /**
     * Which live forms link a file: the [links] from those the caller may read, and how many more there are, [hidden].
     */
    class FileUsage(val links: List<Map<String, Any?>>, val hidden: Int) {
        val visibleForms: List<String> get() = links.mapNotNull { it[GDL.holderId].toOptStr() }.distinct()
    }

    /**
     * Which live forms link [fileId], and which of them the caller may read. With [liveFromSql], whether a form is live
     * is read from SQL, in [cxt]'s open transaction: a removal, under the file's lock, must see a form a save made a
     * moment ago on another node, which the cache may not hold yet. Otherwise the cache answers it.
     */
    fun usage(cxt: KdrCxt, fileId: String, liveFromSql: Boolean): FileUsage {
        val links = queryLinks(cxt, mapOf(GDL.fileId to fileId), ::byFileStmt)
        if (links.isEmpty()) return FileUsage(emptyList(), 0)
        val holders = links.mapNotNull { it[GDL.holderId].toOptStr() }.distinct()
        val live = if (liveFromSql) {
            val sqlCxt = SqlTopicService.mkSqlCxt(cxt, gedraDataTopic)
            val select = SqlTopicUtil.mkTableSelectStmt(sqlCxt, GedraDataService.get(cxt).gedraDataTable(cxt))
            var found: Set<String> = emptySet()
            sqlCxt.sqlDb.withSession(cxt) {
                found = holders.filter { sqlCxt.sqlDb.queryOneEnabled(cxt, select, mapOf(GD.gedraId to it)) != null }.toSet()
            }
            found
        } else {
            formsIn(cxt, holders, ReadScope.unrestricted).keys
        }
        val visible = formsIn(cxt, holders.filter { it in live }, ReadScopeRules.forCaller(cxt)).keys
        return FileUsage(links.filter { it[GDL.holderId].toOptStr() in visible }, live.count { it !in visible })
    }

    /**
     * How many live forms link each of [client]'s data files that any links (issue #1863), by file id: what a listing
     * of files says of each, and how it tells a linked file from one nothing uses. One query of the client's links, and
     * the forms from the cache, which holds only live ones. Every live form counts, read by the caller or not, since
     * any of them keeps the file from removal.
     */
    fun liveFormCounts(cxt: KdrCxt, client: String): Map<String, Int> {
        val links = queryLinks(cxt, mapOf(PF.client to client), ::byClientStmt)
        if (links.isEmpty()) return emptyMap()
        val live = formsIn(cxt, links.mapNotNull { it[GDL.holderId].toOptStr() }.distinct(), ReadScope.unrestricted).keys
        return links.filter { it[GDL.holderId].toOptStr() in live }
            .groupBy { it[GDL.fileId].toOptStr().orEmpty() }
            .mapValues { (_, rows) -> rows.mapNotNull { it[GDL.holderId].toOptStr() }.distinct().size }
    }

    /**
     * The ids of [client]'s data files that any link row names (issue #1839), whatever its form's state: the ones whose
     * deleted forms' deletion the sweep asks when, since a deleted form keeps its link rows.
     */
    fun linkedFileIds(cxt: KdrCxt, client: String): Set<String> =
        queryLinks(cxt, mapOf(PF.client to client), ::byClientStmt).mapNotNull { it[GDL.fileId].toOptStr() }.toSet()

    /**
     * Where [fileId] stands with the forms that name it (issue #1839), read from SQL: [LinkState.live] when a live form
     * links it, else when the last of the deleted forms that do was deleted ([LinkState.deletedAt]), null when none
     * does. A deleted form keeps its link rows, and nothing restores a deleted form, so only a live one keeps a file.
     */
    fun linkState(cxt: KdrCxt, fileId: String): LinkState {
        val holders = queryLinks(cxt, mapOf(GDL.fileId to fileId), ::byFileStmt).mapNotNull { it[GDL.holderId].toOptStr() }.distinct()
        if (holders.isEmpty()) return LinkState(false, null)
        val sqlCxt = SqlTopicService.mkSqlCxt(cxt, gedraDataTopic)
        val select = SqlTopicUtil.mkTableSelectStmt(sqlCxt, GedraDataService.get(cxt).gedraDataTable(cxt))
        var rows: List<Map<String, Any?>> = emptyList()
        sqlCxt.sqlDb.withSession(cxt) { rows = holders.mapNotNull { sqlCxt.sqlDb.queryOneStatement(cxt, select, mapOf(GD.gedraId to it)) } }
        if (rows.any { it[PF.enabled] == true }) return LinkState(true, null)
        return LinkState(false, rows.mapNotNull { it[PF.updatedAt].toOptInstant() }.maxOrNull())
    }

    /** What [linkState] answers: whether a live form links a file, and else when the last form linking it was deleted. */
    class LinkState(val live: Boolean, val deletedAt: Instant?)

    /**
     * The data file [fileId] when the caller may read it through a live form that links it, one their read scope
     * admits; null otherwise. What [DataFileService.viewable] falls back to after the caller's own scope: one query of
     * the links, and the forms from the cache, which holds only live ones.
     */
    fun readableThroughLink(cxt: KdrCxt, fileId: String): GedraDataRow? {
        if (fileIdOf(cxt, fileId) == null) return null
        val file = GedraDataService.get(cxt).queryGedra(cxt, fileId, GedraDataType.dataFile, ReadScope.unrestricted) ?: return null
        val holders = queryLinks(cxt, mapOf(GDL.fileId to fileId), ::byFileStmt).mapNotNull { it[GDL.holderId].toOptStr() }.distinct()
        return if (holders.isNotEmpty() && formsIn(cxt, holders, ReadScopeRules.forCaller(cxt)).isNotEmpty()) file else null
    }

    /**
     * Which live forms use [file] ([DFILE.usedByType]): each field of a form the caller may read that names it, and how
     * many forms they may not read use it too.
     */
    fun usedBy(cxt: KdrCxt, file: GedraDataRow): Map<String, Any?> {
        val usage = usage(cxt, file.gedraId.fullId, liveFromSql = false)
        val forms = formsIn(cxt, usage.visibleForms, ReadScopeRules.forCaller(cxt))
        val data = GedraDataService.get(cxt)
        return linkedMapOf(
            DFILE.fileId to file.gedraId.fullId,
            DFILE.usedBy to usage.links.map { link ->
                val holder = forms[link[GDL.holderId].toOptStr()]
                val traitId = link[GDL.traitId].toOptStr().orEmpty()
                val fieldPath = link[GDL.fieldPath].toOptStr().orEmpty()
                // The trait and its field as the form's pages name them (issue #1863), so a person reads which form
                // and which field, not ids.
                val dataType = holder?.let { traitDataTypeIn(data.entryUnion(cxt, it.client, it.kind), traitId) }
                val field = fieldPath.substringBefore('.').substringBefore('[')
                linkedMapOf(
                    DFILE.formId to link[GDL.holderId], DFILE.traitId to traitId, DFILE.fieldPath to fieldPath,
                    DFILE.traitTitle to (dataType?.title ?: humanizeFieldName(traitId.substringAfterLast(':'))),
                    // The field's own title, as a form labels it, never its target type's, which every field of
                    // that type shares.
                    DFILE.fieldLabel to (dataType?.properties?.get(field)?.title ?: humanizeFieldName(field)),
                    DFILE.formCreatedAt to holder?.createdAt?.formatDate(),
                ).filterValues { it != null }
            },
            DFILE.hiddenForms to usage.hidden,
        )
    }

    /**
     * The files [row]'s entries name and link, as a form read carries them beside its data (`files`): by id, each one's
     * name, kind, size, and status -- so a form's data holds only the id, and what it says of a file is never stale.
     * Only a file the form links, so a value its save never checked tells a reader nothing of a file; empty when it
     * names none, and then no query is made.
     */
    fun filesOf(cxt: KdrCxt, row: GedraDataRow): Map<String, Any?> {
        if (!row.kind.carriesEntries) return emptyMap()
        val union = GedraDataService.get(cxt).entryUnion(cxt, row.client, row.kind) ?: return emptyMap()
        if (!DataFileFields.holdsFiles(union)) return emptyMap()
        val named = usesOf(union, row.entries).values.map { it.fileId }.toSet()
        if (named.isEmpty()) return emptyMap()
        val linked = queryLinks(cxt, mapOf(GDL.holderId to row.gedraId.fullId), ::byHolderStmt)
            .mapNotNull { it[GDL.fileId].toOptStr() }.filter { it in named }.distinct()
        if (linked.isEmpty()) return emptyMap()
        return GedraDataService.get(cxt).readGedras(cxt, GedraDataType.dataFile, linked, ReadScope.unrestricted)
            .filterValues { it.client == row.client }
            .mapValues { (_, file) ->
                val record = DataFileService.fileOf(file)
                linkedMapOf(
                    DFILE.name to record[DFILE.name], DFILE.kind to record[DFILE.kind],
                    DFILE.size to record[DFILE.size], DFILE.status to record[DFILE.status],
                ).filterValues { it != null }
            }
    }

    // --- statements ------------------------------------------------------------------------------------------------

    private fun placeData(holderId: String, place: Place): MutableMap<String, Any?> = mutableMapOf(
        GDL.holderId to holderId, GDL.traitId to place.traitId, GDL.entryKey to place.entryKey, GDL.fieldPath to place.fieldPath,
    )

    private fun byHolderStmt(sqlCxt: SqlCxt, table: KdrTable) = SqlStmtUtil.prepareSql(
        sqlCxt, "qGedraFileLinksByHolder", table.columns, "select * from t:${GDT.gedraFileLinks} where c:${GDL.holderId} = :${GDL.holderId}",
    )

    private fun byFileStmt(sqlCxt: SqlCxt, table: KdrTable) = SqlStmtUtil.prepareSql(
        sqlCxt, "qGedraFileLinksByFile", table.columns, "select * from t:${GDT.gedraFileLinks} where c:${GDL.fileId} = :${GDL.fileId}",
    )

    private fun byClientStmt(sqlCxt: SqlCxt, table: KdrTable) = SqlStmtUtil.prepareSql(
        sqlCxt, "qGedraFileLinksByClient", table.columns,
        "select c:${GDL.fileId}, c:${GDL.holderId} from t:${GDT.gedraFileLinks} where c:${PF.client} = :${PF.client}",
    )

    private fun deleteStmt(sqlCxt: SqlCxt, table: KdrTable) = SqlStmtUtil.prepareSql(
        sqlCxt, "dGedraFileLink", table.columns,
        "delete from t:${GDT.gedraFileLinks} where c:${GDL.holderId} = :${GDL.holderId} and c:${GDL.traitId} = :${GDL.traitId} " +
            "and c:${GDL.entryKey} = :${GDL.entryKey} and c:${GDL.fieldPath} = :${GDL.fieldPath}",
    )
}

/**
 * Keeps each form's links to its data files in line with what its fields name (issue #1838), in the form's own write:
 * a create, a patch, an import, a recompute that stores computed values. Registered **before** `DerivedStateWriteHook`,
 * which rebinds the write's context to the form's owner: the caller's read scope, which a file newly linked must
 * admit, is judged as the caller's.
 *
 * Theme: #DFILE.
 */
object DataFileLinkHook : GedraWriteHook {
    override fun afterWrite(cxt: KdrCxt, write: GedraWriteContext) {
        DataFileLinks.syncLinks(cxt, write.sqlCxt, write.row, write.priorEntries)
    }
}

/**
 * Refuses removing a data file while a live form's field names it (issue #1838), under the file's lock: the forms the
 * caller may read are named, and the rest counted. A form's field must stop naming the file first.
 *
 * Theme: #DFILE.
 */
object DataFileDeleteGuard : GedraWriteGuard {
    override fun check(cxt: KdrCxt, write: GedraGuardedWrite): ((List<Map<String, Any?>>) -> List<Map<String, Any?>>)? {
        if (!write.deletesGedra || write.row.kind != GedraDataType.dataFile) return null
        val usage = DataFileLinks.usage(cxt, write.row.gedraId.fullId, liveFromSql = true)
        val shown = usage.visibleForms
        val total = shown.size + usage.hidden
        if (total == 0) return null
        val named = shown.map { "'$it'" } + (if (usage.hidden > 0) listOf("${usage.hidden} you cannot see") else emptyList())
        throw KdrException(
            "The file '${write.row.gedraId}' is used by $total form${if (total == 1) "" else "s"} " +
                "(${named.joinToString(", ")}); remove it from ${if (total == 1) "it" else "them"} first.",
            code = EXC.conflict,
        )
    }
}
