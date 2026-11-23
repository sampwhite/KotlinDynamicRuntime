package com.dynamicruntime.common.datafile

import com.dynamicruntime.common.context.KdrCxt
import com.dynamicruntime.common.context.ReadScope
import com.dynamicruntime.common.exception.EXC
import com.dynamicruntime.common.exception.KdrException
import com.dynamicruntime.common.filekind.FKIND
import com.dynamicruntime.common.gedra.GedraDataRow
import com.dynamicruntime.common.gedra.GedraDataService
import com.dynamicruntime.common.gedra.GedraDataType
import com.dynamicruntime.common.schema.SchType
import com.dynamicruntime.common.schema.isDataTable
import com.dynamicruntime.common.schema.tableRowLimit
import com.dynamicruntime.common.table.Csv
import com.dynamicruntime.common.table.CsvLimits
import com.dynamicruntime.common.table.CsvParsed
import com.dynamicruntime.common.table.TableRead
import com.dynamicruntime.common.table.readTable
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
     * no such file, another client's, still uploading, another kind -- or its bytes are gone. For a computation on a
     * form's data (its summaries), which reads what a field names rather than checking it.
     */
    fun readFor(cxt: KdrCxt, fileId: String, field: SchType, client: String): TableRead? {
        if (!field.isDataTable()) return null
        val file = try {
            GedraDataService.get(cxt).queryGedra(cxt, fileId, GedraDataType.dataFile, ReadScope.unrestricted)
        } catch (_: KdrException) {
            null
        } ?: return null
        val record = DataFileService.fileOf(file)
        if (file.client != client || record[DFILE.status] != DFSTAT.ready || record[DFILE.kind] != FKIND.csv) return null
        return try {
            read(cxt, file, field)
        } catch (_: KdrException) {
            null
        }
    }
}
