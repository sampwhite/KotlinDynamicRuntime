package com.dynamicruntime.common.table

import com.dynamicruntime.common.annotation.KdrPrivate
import com.dynamicruntime.common.gedra.report.plainNumberOrNull

/** The CSV codec's limits and marks (issue #1881). Each name matches its value. */
@Suppress("ConstPropertyName")
object CSVT {
    /** The most rows a table holds, its header aside. A field may only lower it. */
    const val maxRows = 5000

    /** The most columns a table holds. */
    const val maxColumns = 100

    /** The most characters one cell holds. */
    const val maxCellLength = 10_000

    /** The most problems a parse reports before it stops looking: enough to fix a file by, without a list of thousands. */
    const val maxProblems = 10

    /** The byte-order mark: stripped from the front of a parsed file, written at the front of a written one. */
    const val bom = '﻿'

    /** A comma: a CSV file's delimiter. */
    const val comma = ','

    /** A tab: the delimiter of what a spreadsheet puts on the clipboard. */
    const val tab = '\t'
}

/**
 * What is wrong with CSV text (issue #1881). A closed set: the codec's own codes, which a caller may branch on, while
 * what a person reads is the problem's message.
 */
@Suppress("EnumEntryName")
enum class CsvError {
    /** A quoted cell whose closing quote never comes. */
    unclosedQuote,

    /** A quote inside a cell that is not quoted, or something other than a delimiter or a line end after a closing quote. */
    strayQuote,

    /** A row with more or fewer cells than the header. */
    raggedRow,
    tooManyRows,
    tooManyColumns,
    cellTooLong,

    /** A control character other than a tab or a line end: what a binary file, not a table, is full of. */
    controlCharacter,

    /** No header, or a header with a column that has no name. */
    emptyHeader,

    /** Two columns of the header with the same name. */
    duplicateHeader,

    /** Bytes that are not UTF-8. */
    notUtf8,
}

/** One problem with CSV text: its [error], the [line] of the text it is on (from 1), and a [message] a person reads. */
class CsvProblem(val error: CsvError, val line: Int, val message: String) {
    override fun toString(): String = message
}

/** The limits a parse holds text to: by default the codec's own ([CSVT]). */
class CsvLimits(
    val maxRows: Int = CSVT.maxRows,
    val maxColumns: Int = CSVT.maxColumns,
    val maxCellLength: Int = CSVT.maxCellLength,
)

/**
 * Parsed CSV text: its [header] (empty when it was parsed without one), its [rows], and its [problems]. The rows are
 * whatever was read before a problem that stops a parse, so a caller reads them only when [ok].
 */
class CsvParsed(val header: List<String>, val rows: List<List<String>>, val problems: List<CsvProblem>) {
    val ok: Boolean get() = problems.isEmpty()
}

/**
 * The CSV codec (issue #1881): RFC 4180 read and written in the kernel, so the server judging an uploaded table and
 * the browser reading one or a spreadsheet's paste read it alike.
 *
 * **Reading** ([parse]):
 *
 * - A quoted cell may hold the delimiter, a line break, and a quote written twice (`""`).
 * - A line ends with CRLF, LF, or a lone CR. One line end after the last row ends that row; a second one is another
 *   row, of one empty cell.
 * - A leading byte-order mark is dropped: Excel writes one.
 * - Control characters other than a tab and the line ends are refused, as is a quote inside an unquoted cell --
 *   unless the parse is lenient about quotes, as a paste's is.
 * - The header's names are its columns, read without the spaces around them: each has one, and no two the same.
 *   Every row has as many cells.
 * - [CsvLimits] bounds what is read, so a large file is refused as it is read, not after.
 *
 * A quote, a control character, a cell too long, or too many rows or columns ends the parse at once. A ragged row or
 * a header's problem does not, and a parse reports at most [CSVT.maxProblems] of them.
 *
 * **Writing** ([write]): CRLF line ends and a leading byte-order mark, which is how Excel knows the file is UTF-8;
 * a cell quoted only when it must be ([field]).
 *
 * The text is **never rewritten** to make it safe for a spreadsheet. A stored table is data, and a round trip must not
 * change it. [spreadsheetSafe] is for a copy meant to be opened in one.
 *
 * Theme: #DTABL.
 */
object Csv {
    /**
     * Reads [text] as CSV with [delimiter] cells. With [header], the first row is the header and every row is held to
     * its width; without, every row is a row, of any width -- a spreadsheet's paste.
     *
     * With [lenientQuotes], a quote out of place is text rather than a problem, as a spreadsheet's clipboard needs: a
     * quote opens a quoted cell only at the cell's start, and a cell whose quote never closes, or is followed by more
     * than a delimiter or a line end, is read again from its start as unquoted text, its quotes kept. So `5" screen`
     * and `"5" screen` are cells as typed, while a quoted cell holding a line break is still one cell. Every other
     * problem still stops the parse.
     */
    fun parse(
        text: String,
        delimiter: Char = CSVT.comma,
        limits: CsvLimits = CsvLimits(),
        header: Boolean = true,
        lenientQuotes: Boolean = false,
    ): CsvParsed = CsvReader(text, delimiter, limits, header, lenientQuotes).read()

    /**
     * [bytes] read as a CSV table with a header, or the problems that keep it from being one: the bytes are not UTF-8,
     * or the text is not a table [parse] reads.
     */
    fun parseBytes(bytes: ByteArray, limits: CsvLimits = CsvLimits()): CsvParsed {
        val text = try {
            bytes.decodeToString(throwOnInvalidSequence = true)
        } catch (_: CharacterCodingException) {
            return CsvParsed(emptyList(), emptyList(), listOf(CsvProblem(CsvError.notUtf8, 1, notUtf8Message)))
        }
        return parse(text, limits = limits)
    }

    /**
     * What a file that is not UTF-8 is told: most often one saved from Excel as plain "CSV", which on Windows is written
     * in the computer's own code page, so the fix is named.
     */
    const val notUtf8Message = "The file is not UTF-8 text. In Excel, save it as \"CSV UTF-8 (Comma delimited)\"; " +
        "elsewhere, choose UTF-8 as its encoding."

    /**
     * [text], CSV read without a header, written again with every cell [spreadsheetSafe]: a copy to open in a
     * spreadsheet, never one to store. Written without a byte-order mark, which whoever saves it adds. Null when the
     * text is not CSV the codec reads.
     */
    fun spreadsheetCopy(text: String): String? {
        val parsed = parse(text, header = false)
        if (!parsed.ok) return null
        return write(parsed.rows.map { row -> row.map { spreadsheetSafe(it) } }, bom = false)
    }

    /**
     * [rows] as CSV text: each row a line ended by CRLF, each cell [field]-quoted, and the text begun with a byte-order
     * mark when [bom]. A row of a single empty cell is written `""`, so it is not read back as a blank line.
     */
    fun write(rows: List<List<String>>, delimiter: Char = CSVT.comma, bom: Boolean = true): String {
        val out = StringBuilder()
        if (bom) out.append(CSVT.bom)
        for (row in rows) {
            if (row.size == 1 && row[0].isEmpty()) {
                out.append("\"\"")
            } else {
                row.forEachIndexed { at, cell ->
                    if (at > 0) out.append(delimiter)
                    out.append(field(cell, delimiter))
                }
            }
            out.append("\r\n")
        }
        return out.toString()
    }

    /** One cell as RFC 4180 writes it: quoted when it holds [delimiter], a quote, or a line break, its quotes doubled. */
    fun field(text: String, delimiter: Char = CSVT.comma): String =
        if (text.any { it == delimiter || it == '"' || it == '\n' || it == '\r' }) "\"" + text.replace("\"", "\"\"") + "\"" else text

    /**
     * [cell] made safe to open in a spreadsheet: a cell beginning `=`, `+`, `-`, `@`, a tab, or a carriage return is
     * read by Excel and its kin as a **formula**, and a cell's text is whatever somebody typed. Such a cell gets a
     * leading apostrophe, which a spreadsheet shows as the text it is. A number (`-5`, `+1.5e3`) is left as it is, so a
     * negative amount stays a number. Only for a copy meant for a spreadsheet, never for stored data.
     */
    fun spreadsheetSafe(cell: String): String =
        if (cell.isNotEmpty() && cell[0] in "=+-@\t\r" && plainNumberOrNull(cell) == null) "'$cell" else cell
}

/** One parse of [text] ([Csv.parse]): a loop over its characters, so its depth does not grow with the text. */
@KdrPrivate
class CsvReader(val text: String, val delimiter: Char, val limits: CsvLimits, val header: Boolean, val lenientQuotes: Boolean = false) {
    val problems = ArrayList<CsvProblem>()
    val rows = ArrayList<List<String>>()
    var headerRow: List<String> = emptyList()

    /** The line the reader is on, from 1, counting the line breaks inside quoted cells. */
    var line = 1

    /** Whether the parse stopped at a problem it cannot read past. */
    var stopped = false

    fun problem(error: CsvError, at: Int, message: String) {
        if (problems.size < CSVT.maxProblems) problems.add(CsvProblem(error, at, "Line $at: $message"))
        if (problems.size >= CSVT.maxProblems) stopped = true
    }

    fun stop(error: CsvError, at: Int, message: String) {
        problem(error, at, message)
        stopped = true
    }

    fun read(): CsvParsed {
        var i = if (text.isNotEmpty() && text[0] == CSVT.bom) 1 else 0
        val end = text.length
        var cells = ArrayList<String>()
        val cell = StringBuilder()
        var recordLine = line
        var anyRecord = false
        while (i < end && !stopped) {
            // One cell, from its first character to its delimiter or line end.
            if (text[i] == '"') {
                val cellStart = i
                val opened = line
                i++
                var closed = false
                while (i < end) {
                    val c = text[i]
                    if (c == '"') {
                        if (i + 1 < end && text[i + 1] == '"') {
                            cell.append('"')
                            i += 2
                            continue
                        }
                        i++
                        closed = true
                        break
                    }
                    if (!checkChar(c)) break
                    if (c == '\n' || (c == '\r' && !(i + 1 < end && text[i + 1] == '\n'))) line++
                    cell.append(c)
                    i++
                    if (cell.length > limits.maxCellLength) break
                }
                if (stopped) break
                val tooLong = cell.length > limits.maxCellLength
                val strayAfter = closed && i < end && text[i] != delimiter && text[i] != '\r' && text[i] != '\n'
                if (lenientQuotes && (tooLong || !closed || strayAfter)) {
                    // Not a quoted cell after all: read again from its quote as text.
                    i = cellStart
                    line = opened
                    cell.clear()
                    i = readPlain(i, cell)
                    if (stopped) break
                } else {
                    if (tooLong) {
                        stop(CsvError.cellTooLong, opened, "a cell is longer than ${limits.maxCellLength} characters.")
                        break
                    }
                    if (!closed) {
                        stop(CsvError.unclosedQuote, opened, "a quoted cell has no closing quote.")
                        break
                    }
                    if (strayAfter) {
                        stop(CsvError.strayQuote, line, "a quoted cell is followed by '${text[i]}', not by a delimiter or a line end.")
                        break
                    }
                }
            } else {
                i = readPlain(i, cell)
                if (stopped) break
            }
            cells.add(cell.toString())
            cell.clear()
            // What ends the cell: a delimiter, another cell follows; a line end or the text's end, the row ends.
            val delimited = i < end && text[i] == delimiter
            // A delimiter last in the text still has a cell after it, an empty one.
            if (delimited && i + 1 == end) cells.add("")
            if (cells.size > limits.maxColumns) {
                stop(CsvError.tooManyColumns, recordLine, "a row has more than ${limits.maxColumns} cells.")
                break
            }
            if (delimited) {
                i++
                if (i < end) continue
            } else if (i < end) {
                i += if (text[i] == '\r' && i + 1 < end && text[i + 1] == '\n') 2 else 1
                line++
            }
            anyRecord = true
            record(cells, recordLine)
            cells = ArrayList()
            recordLine = line
        }
        if (!stopped && cells.isNotEmpty()) {
            anyRecord = true
            record(cells, recordLine)
        }
        if (!stopped && header && !anyRecord) problem(CsvError.emptyHeader, 1, "there is no header naming the columns.")
        return CsvParsed(headerRow, rows, problems)
    }

    /**
     * An unquoted cell read into [cell] from [from] to its delimiter or line end, returning where it ended: a quote in it
     * refused, or, with [lenientQuotes], kept as text.
     */
    fun readPlain(from: Int, cell: StringBuilder): Int {
        var i = from
        while (i < text.length) {
            val c = text[i]
            if (c == delimiter || c == '\r' || c == '\n') break
            if (c == '"' && !lenientQuotes) {
                stop(CsvError.strayQuote, line, "a quote inside a cell that is not quoted; quote the cell and write the quote twice.")
                break
            }
            if (!checkChar(c)) break
            cell.append(c)
            i++
            if (cell.length > limits.maxCellLength) {
                stop(CsvError.cellTooLong, line, "a cell is longer than ${limits.maxCellLength} characters.")
                break
            }
        }
        return i
    }

    /** Whether [c] may be in a cell: anything but a control character other than a tab and the line ends. */
    fun checkChar(c: Char): Boolean {
        if (c < ' ' && c != '\t' && c != '\r' && c != '\n') {
            stop(CsvError.controlCharacter, line, "a control character (code ${c.code}) is in a cell; this is not text.")
            return false
        }
        return true
    }

    /** Takes [cells], the row that began on [at]: the header when it is the first and the parse has one, else a row. */
    fun record(cells: List<String>, at: Int) {
        if (header && headerRow.isEmpty()) {
            // A name is read without the spaces around it: `day, amount` names `amount`.
            headerRow = cells.map { it.trim() }
            headerRow.forEachIndexed { index, name ->
                if (name.isEmpty()) problem(CsvError.emptyHeader, at, "column ${index + 1} of the header has no name.")
            }
            headerRow.withIndex().groupBy { it.value }.filter { it.key.isNotEmpty() && it.value.size > 1 }.forEach { (name, _) ->
                problem(CsvError.duplicateHeader, at, "the header names '$name' more than once.")
            }
            return
        }
        if (rows.size >= limits.maxRows) {
            stop(CsvError.tooManyRows, at, "the table has more than ${limits.maxRows} rows.")
            return
        }
        if (header && cells.size != headerRow.size) {
            problem(CsvError.raggedRow, at, "row ${rows.size + 1} has ${cells.size} cells; the header has ${headerRow.size}.")
        }
        rows.add(cells)
    }
}
