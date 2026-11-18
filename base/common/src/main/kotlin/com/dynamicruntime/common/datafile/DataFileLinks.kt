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
import com.dynamicruntime.common.gedra.gedraDataTopic
import com.dynamicruntime.common.schema.fileSizeLimit
import com.dynamicruntime.common.sql.KdrTable
import com.dynamicruntime.common.sql.PF
import com.dynamicruntime.common.sql.SqlCxt
import com.dynamicruntime.common.sql.SqlStmtUtil
import com.dynamicruntime.common.sql.SqlTopicService
import com.dynamicruntime.common.sql.SqlTopicTranProvider
import com.dynamicruntime.common.sql.SqlTopicUtil
import com.dynamicruntime.common.user.ReadScopeRules
import com.dynamicruntime.common.util.toOptLong
import com.dynamicruntime.common.util.toOptStr

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
 * Theme: #DFILE.
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

    /**
     * Brings [row]'s links in line with the files its entries name, inside its write's transaction ([sqlCxt], holding
     * the form's lock). Every file newly linked is locked, in sorted order, and must be: a live data file of the form's
     * client, no longer uploading, of a kind and a size its field takes, and one the caller may use -- in their read
     * scope, or already linked from this form. The system's own writes (a recompute, a job) use whatever the form
     * already names. A refusal fails the write, as validation does.
     */
    fun syncLinks(cxt: KdrCxt, sqlCxt: SqlCxt, row: GedraDataRow) {
        if (!row.kind.carriesEntries) return
        val typeOf = GedraDataService.get(cxt).traitDataTypes(cxt, row.client, row.kind)
        val wanted = LinkedHashMap<Place, DataFileUse>()
        for (entry in row.entries) {
            val traitId = entry[GE.traitId].toOptStr() ?: continue
            val type = typeOf(traitId) ?: continue
            if (!DataFileFields.holdsFiles(type)) continue
            val entryKey = entry[GE.entryId].toOptStr() ?: continue
            for (use in DataFileFields.collect(type, entry[GE.data])) wanted[Place(traitId, entryKey, use.path)] = use
        }
        val table = linkTable(cxt)
        val holderId = row.gedraId.fullId
        val stored = sqlCxt.sqlDb.queryStatement(cxt, byHolderStmt(sqlCxt, table), mapOf(GDL.holderId to holderId))
        if (wanted.isEmpty() && stored.isEmpty()) return
        val storedAt = stored.associateBy { placeOf(it) }
        val adds = wanted.filter { (place, use) -> storedAt[place]?.get(GDL.fileId).toOptStr() != use.fileId }
        val removes = storedAt.filter { (place, link) -> wanted[place]?.fileId != link[GDL.fileId].toOptStr() }
        if (adds.isNotEmpty()) {
            val linkedHere = stored.mapNotNull { it[GDL.fileId].toOptStr() }.toSet()
            val newFiles = adds.values.map { it.fileId }.filter { it !in linkedHere }.toSortedSet()
            val locked = newFiles.filter { lockFile(sqlCxt, it) }.toSet()
            val files = adds.values.map { it.fileId }.distinct().associateWith { readFileRow(cxt, sqlCxt, it) }
            val callerScope = if (cxt.userProfile.authId == null) null else ReadScopeRules.forCaller(cxt)
            for ((place, use) in adds) {
                val newHere = use.fileId in newFiles
                checkUse(cxt, row, place, use, files[use.fileId]?.takeIf { !newHere || use.fileId in locked }, if (newHere) callerScope else null)
            }
        }
        for (place in removes.keys + adds.keys.filter { it in storedAt }) {
            sqlCxt.sqlDb.executeStatement(cxt, deleteStmt(sqlCxt, table), placeData(holderId, place))
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
     * Refuses [use], at [place] in [row], unless [file] -- the data file it names, read under its lock, or null when
     * there is none to use -- is one the field may hold. [callerScope], when given, is the caller's read scope, which
     * must admit the file: given only for a file this form did not already link, and not for the system's own writes.
     */
    private fun checkUse(cxt: KdrCxt, row: GedraDataRow, place: Place, use: DataFileUse, file: GedraDataRow?, callerScope: ReadScope?) {
        val field = "'${place.fieldPath}' of '${place.traitId}'"
        val usable = file != null && file.client == row.client &&
            (callerScope == null || GedraDataService.get(cxt).admitsGedra(callerScope, file))
        if (file == null || !usable) {
            throw KdrException.mkInput("The field $field names the file '${use.fileId}', which is no file you may use here.")
        }
        val record = DataFileService.fileOf(file)
        if (record[DFILE.status] == DFSTAT.uploading) {
            throw KdrException.mkInput("The field $field names the file '${use.fileId}', which is still being uploaded.")
        }
        val kind = FileKinds.byId(record[DFILE.kind].toOptStr().orEmpty())
        val limit = kind?.let { use.field.fileSizeLimit(it) }
        if (kind == null || limit == null) {
            val takes = use.field.fileKinds.joinToString(", ") { it.label }
            throw KdrException.mkInput("The field $field takes $takes files; '${use.fileId}' is ${kind?.label ?: "none of them"}.")
        }
        val size = record[DFILE.size].toOptLong() ?: 0L
        if (size > limit) {
            throw KdrException.mkInput("The field $field takes a ${kind.label} file of at most $limit bytes; '${use.fileId}' is $size.")
        }
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

    /**
     * The links to [fileId] from forms still live, each `{holderId, traitId, entryKey, fieldPath}`. Read from SQL --
     * inside whatever transaction [cxt] has open, else in a session of its own -- and each form's liveness from it too,
     * so a removal under the file's lock sees what a save committed a moment ago.
     */
    fun liveLinksTo(cxt: KdrCxt, fileId: String): List<Map<String, Any?>> {
        val sqlCxt = SqlTopicService.mkSqlCxt(cxt, gedraDataTopic)
        val table = linkTable(cxt)
        var out: List<Map<String, Any?>> = emptyList()
        // A session already open -- the removal's transaction -- is the one used.
        sqlCxt.sqlDb.withSession(cxt) {
            val links = sqlCxt.sqlDb.queryStatement(cxt, byFileStmt(sqlCxt, table), mapOf(GDL.fileId to fileId))
            if (links.isNotEmpty()) {
                val forms = GedraDataService.get(cxt).gedraDataTable(cxt)
                val select = SqlTopicUtil.mkTableSelectStmt(sqlCxt, forms)
                val live = links.mapNotNull { it[GDL.holderId].toOptStr() }.distinct()
                    .filter { sqlCxt.sqlDb.queryOneEnabled(cxt, select, mapOf(GD.gedraId to it)) != null }.toSet()
                out = links.filter { it[GDL.holderId].toOptStr() in live }
            }
        }
        return out
    }

    /** The live forms of [holderIds] the caller's read scope admits, by id, read as any form is. */
    private fun visibleForms(cxt: KdrCxt, holderIds: Collection<String>): Map<String, GedraDataRow> {
        val data = GedraDataService.get(cxt)
        val gedras = GedraService.get(cxt)
        val scope = ReadScopeRules.forCaller(cxt)
        return holderIds.groupBy { gedras.readId(it).dataType }.flatMap { (kind, ids) ->
            if (kind == null) emptyList() else data.readGedras(cxt, kind, ids, scope).entries.map { it.toPair() }
        }.toMap()
    }

    /**
     * The data file [fileId] when the caller may read it through a live form that links it, one their read scope
     * admits; null otherwise. What [DataFileService.viewable] falls back to after the caller's own scope.
     */
    fun readableThroughLink(cxt: KdrCxt, fileId: String): GedraDataRow? {
        if (fileIdOf(cxt, fileId) == null) return null
        val file = GedraDataService.get(cxt).queryGedra(cxt, fileId, GedraDataType.dataFile, ReadScope.unrestricted) ?: return null
        val holders = liveLinksTo(cxt, fileId).mapNotNull { it[GDL.holderId].toOptStr() }.distinct()
        return if (holders.isNotEmpty() && visibleForms(cxt, holders).isNotEmpty()) file else null
    }

    /**
     * Which live forms use [file] ([DFILE.usedByType]): each field of a form the caller may read that names it, and how
     * many forms they may not read use it too.
     */
    fun usedBy(cxt: KdrCxt, file: GedraDataRow): Map<String, Any?> {
        val links = liveLinksTo(cxt, file.gedraId.fullId)
        val holders = links.mapNotNull { it[GDL.holderId].toOptStr() }.distinct()
        val visible = visibleForms(cxt, holders)
        return linkedMapOf(
            DFILE.fileId to file.gedraId.fullId,
            DFILE.usedBy to links.filter { it[GDL.holderId].toOptStr() in visible }.map {
                linkedMapOf(
                    DFILE.formId to it[GDL.holderId], DFILE.traitId to it[GDL.traitId], DFILE.fieldPath to it[GDL.fieldPath],
                )
            },
            DFILE.hiddenForms to holders.count { it !in visible },
        )
    }

    /**
     * The files [row]'s entries name, as a form read carries them beside its data (`files`): by id, each one's name,
     * kind, size, and status -- so a form's data holds only the id, and what it says of a file is never stale. Only
     * live files of the form's own client, which is all a save links; empty when it names none.
     */
    fun filesOf(cxt: KdrCxt, row: GedraDataRow): Map<String, Any?> {
        if (!row.kind.carriesEntries) return emptyMap()
        val typeOf = GedraDataService.get(cxt).traitDataTypes(cxt, row.client, row.kind)
        val ids = row.entries.flatMap { entry ->
            val type = entry[GE.traitId].toOptStr()?.let(typeOf)
            if (type == null || !DataFileFields.holdsFiles(type)) emptyList() else DataFileFields.collect(type, entry[GE.data]).map { it.fileId }
        }.distinct().filter { fileIdOf(cxt, it) != null }
        if (ids.isEmpty()) return emptyMap()
        return GedraDataService.get(cxt).readGedras(cxt, GedraDataType.dataFile, ids, ReadScope.unrestricted)
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
        DataFileLinks.syncLinks(cxt, write.sqlCxt, write.row)
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
        val used = DataFileLinks.usedBy(cxt, write.row)
        @Suppress("UNCHECKED_CAST")
        val shown = (used[DFILE.usedBy] as List<Map<String, Any?>>).mapNotNull { it[DFILE.formId].toOptStr() }.distinct()
        val hidden = used[DFILE.hiddenForms] as Int
        if (shown.isEmpty() && hidden == 0) return null
        val named = shown.map { "'$it'" } + (if (hidden > 0) listOf("$hidden you cannot see") else emptyList())
        throw KdrException(
            "The file '${write.row.gedraId}' is used by ${shown.size + hidden} form${if (shown.size + hidden == 1) "" else "s"} " +
                "(${named.joinToString(", ")}); remove it from ${if (shown.size + hidden == 1) "it" else "them"} first.",
            code = EXC.conflict,
        )
    }
}
