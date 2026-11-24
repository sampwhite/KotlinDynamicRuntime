package com.dynamicruntime.common.table

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The CSV codec (issue #1881), on the JVM and in JS alike. */
class CsvCodecTest {
    private fun ok(text: String, header: Boolean = true, delimiter: Char = CSVT.comma): CsvParsed {
        val parsed = Csv.parse(text, delimiter, header = header)
        assertTrue(parsed.ok, "expected no problems, got: ${parsed.problems}")
        return parsed
    }

    private fun refused(text: String, limits: CsvLimits = CsvLimits()): CsvProblem {
        val parsed = Csv.parse(text, limits = limits)
        assertTrue(!parsed.ok, "expected a problem with: $text")
        return parsed.problems.first()
    }

    @Test
    fun readsAHeaderAndRows() {
        val parsed = ok("name,amount\r\nlunch,12.50\r\ntaxi,30\r\n")
        assertEquals(listOf("name", "amount"), parsed.header)
        assertEquals(listOf(listOf("lunch", "12.50"), listOf("taxi", "30")), parsed.rows)
    }

    @Test
    fun readsQuotesDelimitersAndLineBreaksInsideQuotes() {
        val parsed = ok("a,b\n\"x, y\",\"say \"\"hi\"\"\"\n\"two\r\nlines\",\"\"\n")
        assertEquals(listOf(listOf("x, y", "say \"hi\""), listOf("two\r\nlines", "")), parsed.rows)
    }

    @Test
    fun readsEveryLineEnd() {
        val rows = listOf(listOf("1", "2"), listOf("3", "4"))
        assertEquals(rows, ok("a,b\r\n1,2\r\n3,4").rows)
        assertEquals(rows, ok("a,b\n1,2\n3,4\n").rows)
        assertEquals(rows, ok("a,b\r1,2\r3,4\r").rows)
    }

    @Test
    fun dropsALeadingByteOrderMark() {
        assertEquals(listOf("name"), ok("﻿name\r\nx\r\n").header)
    }

    @Test
    fun readsEmptyCells() {
        assertEquals(listOf(listOf("", "", "")), ok("a,b,c\n,,\n").rows)
        // A delimiter last in the text still has a cell after it.
        assertEquals(listOf(listOf("1", "")), ok("a,b\n1,").rows)
        // In a table of one column, a blank line is a row of one empty cell; one line end after the last row is not.
        assertEquals(listOf(listOf("x"), listOf("")), ok("a\nx\n\n").rows)
    }

    @Test
    fun readsAHeaderOnly() {
        val parsed = ok("a,b\r\n")
        assertEquals(listOf("a", "b"), parsed.header)
        assertEquals(emptyList(), parsed.rows)
    }

    @Test
    fun readsAPasteWithoutAHeader() {
        val parsed = ok("1\t2\t3\n4\t5\n", header = false, delimiter = CSVT.tab)
        assertEquals(emptyList(), parsed.header)
        assertEquals(listOf(listOf("1", "2", "3"), listOf("4", "5")), parsed.rows)
    }

    @Test
    fun refusesQuotesOutOfPlace() {
        val unclosed = refused("a,b\n1,\"open\n2,3\n")
        assertEquals(CsvError.unclosedQuote, unclosed.error)
        assertEquals(2, unclosed.line)
        assertEquals(CsvError.strayQuote, refused("a,b\n1,ab\"c\n").error)
        assertEquals(CsvError.strayQuote, refused("a,b\n1,\"ab\"c\n").error)
    }

    @Test
    fun readsQuotesOutOfPlaceAsTextWhenLenient() {
        fun lenient(text: String): CsvParsed = Csv.parse(text, CSVT.tab, header = false, lenientQuotes = true)
        // A quote inside a cell, a quoted start followed by more, and a quote that never closes are each text as typed.
        assertEquals(listOf(listOf("5\" screen", "\"5\" screen", "\"open")), lenient("5\" screen\t\"5\" screen\t\"open\n").rows)
        // A cell quoted for its line break is still one cell, beside a quote a spreadsheet left unquoted.
        val mixed = lenient("2026-11-02\t\"Hotel\nlate check-out\"\t5\" screen\n2026-11-03\tTaxi\t7\n")
        assertTrue(mixed.ok, "${mixed.problems}")
        assertEquals(listOf(listOf("2026-11-02", "Hotel\nlate check-out", "5\" screen"), listOf("2026-11-03", "Taxi", "7")), mixed.rows)
        // An unclosed quote reads only its own line again, not the rest of the paste.
        assertEquals(listOf(listOf("\"a", "b"), listOf("c", "d")), lenient("\"a\tb\nc\td").rows)
        // Every other problem still stops it.
        assertEquals(CsvError.controlCharacter, lenient("a\t\"b\u000Bc\n").problems.first().error)
        assertEquals(CsvError.controlCharacter, lenient("a\u000B\tb\n").problems.first().error)
        val long = Csv.parse("\"" + "x".repeat(20), CSVT.tab, CsvLimits(maxCellLength = 10), header = false, lenientQuotes = true)
        assertEquals(CsvError.cellTooLong, long.problems.first().error)
    }

    @Test
    fun refusesARaggedRowNamingItsLine() {
        val ragged = refused("a,b,c\n1,2,3\n4,5\n")
        assertEquals(CsvError.raggedRow, ragged.error)
        assertEquals(3, ragged.line)
        assertTrue("row 2" in ragged.message, ragged.message)
        // A line break inside quotes moves the line on.
        assertEquals(4, refused("a,b\n\"x\ny\",1\n2\n").line)
    }

    @Test
    fun refusesAControlCharacter() {
        assertEquals(CsvError.controlCharacter, refused("a,b\n1,\u0000\n").error)
        assertEquals(CsvError.controlCharacter, refused("a,b\n\"1\u0007\",2\n").error)
        // A tab is a character a cell may hold.
        assertEquals(listOf(listOf("x\ty", "2")), ok("a,b\nx\ty,2\n").rows)
    }

    @Test
    fun refusesABadHeader() {
        assertEquals(CsvError.emptyHeader, refused("").error)
        assertEquals(CsvError.emptyHeader, refused("﻿").error)
        assertEquals(CsvError.emptyHeader, refused("a,,c\n1,2,3\n").error)
        assertEquals(CsvError.duplicateHeader, refused("a,b,a\n1,2,3\n").error)
        // A name is read without the spaces around it, so a blank one has none, and two that differ only so are one.
        assertEquals(CsvError.emptyHeader, refused("a, ,c\n1,2,3\n").error)
        assertEquals(CsvError.duplicateHeader, refused("a, a\n1,2\n").error)
    }

    @Test
    fun readsHeaderNamesWithoutTheirSpaces() {
        assertEquals(listOf("day", "amount"), ok("day, amount \n1,2\n").header)
        // Only the header's: a cell keeps what was typed.
        assertEquals(listOf(listOf(" 1", "2 ")), ok("a,b\n 1,2 \n").rows)
    }

    @Test
    fun holdsTextToItsLimits() {
        val limits = CsvLimits(maxRows = 2, maxColumns = 3, maxCellLength = 5)
        assertEquals(CsvError.tooManyRows, refused("a\n1\n2\n3\n", limits).error)
        assertEquals(CsvError.tooManyColumns, refused("a,b,c,d\n", limits).error)
        assertEquals(CsvError.cellTooLong, refused("a\n123456\n", limits).error)
        assertEquals(CsvError.cellTooLong, refused("a\n\"123456\"\n", limits).error)
        assertTrue(Csv.parse("a\n1\n2\n", limits = limits).ok)
        // The empty cell after a delimiter that ends the text counts too.
        assertEquals(CsvError.tooManyColumns, Csv.parse("1,2,3,", limits = limits, header = false).problems.first().error)
        assertEquals(listOf(listOf("1", "2", "")), Csv.parse("1,2,", limits = limits, header = false).rows)
    }

    @Test
    fun reportsAtMostItsLimitOfProblems() {
        val text = "a,b\n" + (1..50).joinToString("") { "$it\n" }
        assertEquals(CSVT.maxProblems, Csv.parse(text).problems.size)
    }

    @Test
    fun refusesBytesThatAreNotUtf8() {
        val bad = byteArrayOf('a'.code.toByte(), '\n'.code.toByte(), 0xC3.toByte(), 0x28)
        val notUtf8 = Csv.parseBytes(bad).problems.first()
        assertEquals(CsvError.notUtf8, notUtf8.error)
        // Most often Excel's plain CSV, written in a Windows code page: the message names the save that works.
        assertTrue("CSV UTF-8" in notUtf8.message, notUtf8.message)
        assertEquals(listOf(listOf("é")), Csv.parseBytes("a\né\n".encodeToByteArray()).rows)
    }

    @Test
    fun writesWhatItReadsBack() {
        val rows = listOf(
            listOf("name", "note", "amount"),
            listOf("a, b", "say \"hi\"", "-5"),
            listOf("two\r\nlines", "", "=SUM(A1)"),
            listOf("", "", ""),
        )
        val text = Csv.write(rows)
        assertTrue(text.startsWith("﻿"))
        assertTrue(text.endsWith("\r\n"))
        val back = ok(text)
        assertEquals(rows.first(), back.header)
        // Stored text is never rewritten: the formula stays as it was written.
        assertEquals(rows.drop(1), back.rows)
    }

    @Test
    fun writesARowOfOneEmptyCellSoItReadsBack() {
        val rows = listOf(listOf("a"), listOf(""), listOf("x"))
        assertEquals("a\r\n\"\"\r\nx\r\n", Csv.write(rows, bom = false))
        assertEquals(rows.drop(1), ok(Csv.write(rows)).rows)
    }

    @Test
    fun writesTabSeparatedText() {
        assertEquals("a\t\"b\tc\"\r\n", Csv.write(listOf(listOf("a", "b\tc")), CSVT.tab, bom = false))
    }

    @Test
    fun quotesOnlyWhatMustBe() {
        assertEquals("plain", Csv.field("plain"))
        assertEquals("\"a,b\"", Csv.field("a,b"))
        assertEquals("\"a\"\"b\"", Csv.field("a\"b"))
        assertEquals("\"a\nb\"", Csv.field("a\nb"))
        assertEquals("a,b", Csv.field("a,b", CSVT.tab))
    }

    @Test
    fun makesACopyForASpreadsheet() {
        val stored = Csv.write(listOf(listOf("name", "amount"), listOf("=cmd|' /C calc'!A0", "-5"), listOf("a, b", "@x")))
        assertEquals("name,amount\r\n'=cmd|' /C calc'!A0,-5\r\n\"a, b\",'@x\r\n", Csv.spreadsheetCopy(stored))
        assertEquals(null, Csv.spreadsheetCopy("a\n\"open\n"))
    }

    @Test
    fun guardsFormulasButNotNumbers() {
        assertEquals("'=SUM(A1:A9)", Csv.spreadsheetSafe("=SUM(A1:A9)"))
        assertEquals("'+1 555 0100", Csv.spreadsheetSafe("+1 555 0100"))
        assertEquals("'-draft-", Csv.spreadsheetSafe("-draft-"))
        assertEquals("'@home", Csv.spreadsheetSafe("@home"))
        assertEquals("'\tx", Csv.spreadsheetSafe("\tx"))
        assertEquals("-5", Csv.spreadsheetSafe("-5"))
        assertEquals("-12.5", Csv.spreadsheetSafe("-12.5"))
        assertEquals("+1.5e3", Csv.spreadsheetSafe("+1.5e3"))
        assertEquals("a=b", Csv.spreadsheetSafe("a=b"))
        assertEquals("", Csv.spreadsheetSafe(""))
    }
}
