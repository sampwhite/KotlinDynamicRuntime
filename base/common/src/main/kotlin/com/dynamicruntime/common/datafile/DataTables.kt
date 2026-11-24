package com.dynamicruntime.common.datafile

import com.dynamicruntime.common.context.KdrCxt
import com.dynamicruntime.common.context.ReadScope
import com.dynamicruntime.common.exception.EXC
import com.dynamicruntime.common.exception.KdrException
import com.dynamicruntime.common.filekind.FKIND
import com.dynamicruntime.common.gedra.GedraDataRow
import com.dynamicruntime.common.gedra.GedraDataService
import com.dynamicruntime.common.gedra.GedraDataType
import com.dynamicruntime.common.gedra.LogGedra
import com.dynamicruntime.common.schema.SchType
import com.dynamicruntime.common.schema.isDataTable
import com.dynamicruntime.common.schema.tableRowLimit
import com.dynamicruntime.common.table.Csv
import com.dynamicruntime.common.table.CsvLimits
import com.dynamicruntime.common.table.CsvParsed
import com.dynamicruntime.common.table.TableRead
import com.dynamicruntime.common.table.readTable
import com.dynamicruntime.common.user.ReadScopeRules
import com.dynamicruntime.common.util.toOptStr

/**
 * A table field's file read as its table (issue #1882): the bytes of a CSV data file, parsed by the kernel's codec and
 * read against the field's row type ([readTable]) -- what a save checks a newly named table against, and what a
 * table's summaries are computed from.
 *
 * **A node-local cache of parsed files**, by content hash: a file's bytes never change under its hash, so an entry is
 * never stale, only evicted -- the least recently used past [cacheSize]. A save that names a file it just uploaded
 * parses it once, and its summaries read it again from here. Each entry keeps the reads of it against the last few row
 * types asked for ([readsKept]), by the type itself, so a reload of a client's schema -- which makes new types -- reads
 * it afresh, and the reads against the types it replaced are dropped in turn rather than kept alive.
 *
 * Theme: #DTABL.
 */
object DataTables {
    /** How many files' parses a node keeps: each is at most a 4 MiB file's rows. */
    const val cacheSize = 32

    /** How many reads of one file, each against a row type and a most rows, its entry keeps: the most recently used. */
    const val readsKept = 4

    private class Entry(val parsed: CsvParsed) {
        val reads = object : LinkedHashMap<Pair<SchType, Int>, TableRead>(readsKept, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Pair<SchType, Int>, TableRead>?): Boolean =
                size > readsKept
        }
    }

    private val entries = object : LinkedHashMap<String, Entry>(cacheSize, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Entry>?): Boolean = size > cacheSize
    }

    /**
     * [file], a ready CSV data file, read as table field [field]'s table: against its row type and its most rows. Throws
     * when the store no longer holds its bytes ([EXC.notFound]).
     */
    fun read(cxt: KdrCxt, file: GedraDataRow, field: SchType): TableRead {
        val rowType = field.fileRowType ?: throw KdrException("A table field has no row type.")
        val hash = DataFileService.fileOf(file)[DFILE.hash].toOptStr()
            ?: throw KdrException("The file '${file.gedraId.fullId}' has no content yet.")
        val key = rowType to field.tableRowLimit()
        synchronized(entries) { entries[hash]?.reads?.get(key)?.let { return it } }
        val parsed = synchronized(entries) { entries[hash]?.parsed } ?: run {
            val bytes = DataFileService.bytesOf(cxt, file)
                ?: throw KdrException("The content of the file '${file.gedraId.fullId}' is not in the store.", code = EXC.notFound)
            Csv.parseBytes(bytes, CsvLimits())
        }
        val read = readTable(rowType, parsed, field.tableRowLimit())
        synchronized(entries) {
            val entry = entries.getOrPut(hash) { Entry(parsed) }
            entry.reads[key] = read
        }
        return read
    }

    /**
     * What keeps [file] from being table field [field]'s table, as a sentence naming the field ([where]); null when it is
     * one. A file of another kind is the link hook's to refuse, and is not judged here.
     */
    fun problem(cxt: KdrCxt, file: GedraDataRow, field: SchType, where: String): String? {
        if (!field.isDataTable() || DataFileService.fileOf(file)[DFILE.kind] != FKIND.csv) return null
        val problems = try {
            read(cxt, file, field).problems
        } catch (e: KdrException) {
            // Bytes gone from the store refuse the save as any unusable file does; any other fault is a fault.
            if (e.code != EXC.notFound) throw e
            return "The field $where names the file '${file.gedraId.fullId}', whose content is no longer stored; upload it again."
        }
        val first = problems.firstOrNull() ?: return null
        val more = when (val rest = problems.size - 1) {
            0 -> ""
            1 -> " (And 1 more problem.)"
            else -> " (And $rest more problems.)"
        }
        return "The table in the field $where does not fit its columns. ${first.message}$more"
    }

    /**
     * The table of [fileId] as [field]'s, when [fileId] is a live, ready CSV file of [client]; null when it is not one --
     * no such file, another client's, still uploading, another kind -- or its bytes cannot be read. Whom it is read for
     * is the caller's to judge; a form's summary asks [summaryRead].
     */
    fun readFor(cxt: KdrCxt, fileId: String, field: SchType, client: String): TableRead? {
        val file = tableFileOf(cxt, fileId, field, client) ?: return null
        return readOrNull(cxt, file, field)
    }

    /**
     * The table of [fileId] as table field [field]'s, read for a summary of a form a write in [cxt] is for (issue #1883),
     * or null when there is none to sum up: [readFor]'s, in [cxt]'s client -- a write runs in its form's -- and only a
     * file the writer may read. **The system** reads only a file some live form links ([DataFileLinks.linkState]): it acts
     * on nobody's behalf, and a value stored before its field held tables was never checked. **A person** reads one their
     * read scope admits, or one some live form links -- a file another user put on a form they may edit -- never one they
     * could not have named.
     *
     * The answer is kept on [cxt] for the next call with the same file, field, and writer, so a trait's several summaries
     * of one table look it up once. One answer only, in [KdrCxt.session], which no sub-context inherits: a recompute's
     * context for one form, or a request's, holds no more than its last table.
     */
    fun summaryRead(cxt: KdrCxt, fileId: String, field: SchType): TableRead? {
        val system = cxt.userProfile.authId == null
        val last = cxt.session[summaryMemoKey] as? SummaryMemo
        if (last != null && last.fileId == fileId && last.field === field && last.system == system && last.client == cxt.client) {
            return last.read
        }
        val read = tableFileOf(cxt, fileId, field, cxt.client)?.let { file ->
            val usable = if (system) {
                DataFileLinks.linkState(cxt, fileId).live
            } else {
                GedraDataService.get(cxt).admitsGedra(ReadScopeRules.forCaller(cxt), file) || DataFileLinks.linkState(cxt, fileId).live
            }
            if (usable) readOrNull(cxt, file, field) else null
        }
        cxt.session[summaryMemoKey] = SummaryMemo(fileId, field, system, cxt.client, read)
        return read
    }

    private const val summaryMemoKey = "dataTables.summaryRead"

    private class SummaryMemo(val fileId: String, val field: SchType, val system: Boolean, val client: String?, val read: TableRead?)

    /** The live, ready CSV data file [fileId] of [client], for table field [field]; null when it is not one. */
    private fun tableFileOf(cxt: KdrCxt, fileId: String, field: SchType, client: String): GedraDataRow? {
        if (!field.isDataTable()) return null
        val file = try {
            GedraDataService.get(cxt).queryGedra(cxt, fileId, GedraDataType.dataFile, ReadScope.unrestricted)
        } catch (_: KdrException) {
            null
        } ?: return null
        val record = DataFileService.fileOf(file)
        if (file.client != client || record[DFILE.status] != DFSTAT.ready || record[DFILE.kind] != FKIND.csv) return null
        return file
    }

    /**
     * [file] read as [field]'s table, or null when its bytes cannot be read -- whatever the store threw, since a value
     * computed from a table is cleared, never the write failed, for a fault in reading it. A fault beyond bytes simply
     * gone from the store is logged.
     */
    private fun readOrNull(cxt: KdrCxt, file: GedraDataRow, field: SchType): TableRead? = try {
        read(cxt, file, field)
    } catch (e: Exception) {
        if (e !is KdrException || e.code != EXC.notFound) {
            LogGedra.warn(cxt) { "Could not read the table in '${file.gedraId.fullId}'; computing without it: ${e.message}" }
        }
        null
    }
}
