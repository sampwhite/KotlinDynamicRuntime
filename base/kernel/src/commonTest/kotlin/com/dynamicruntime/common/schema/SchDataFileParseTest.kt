package com.dynamicruntime.common.schema

import com.dynamicruntime.common.exception.KdrException
import com.dynamicruntime.common.filekind.FKIND
import com.dynamicruntime.common.filekind.FileKinds
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** A file field names the kinds it holds, and its keywords are refused anywhere else (issue #1834). */
class SchDataFileParseTest {
    private fun field(body: Map<String, Any?>): SchType =
        parseSchemaTypes(mapOf("ns.T" to mapOf(SCH.type to SCT.kObject, SCH.properties to mapOf("f" to body))))
            .getValue("ns.T").properties.getValue("f").valueType

    private fun file(vararg extra: Pair<String, Any?>): Map<String, Any?> =
        mapOf(SCH.type to SCT.string, SCH.format to SFMT.dataFile) + extra

    private fun refusal(body: Map<String, Any?>): String =
        assertFailsWith<KdrException> { field(body) }.message.orEmpty()

    @Test
    fun aFileFieldParsesItsKindsAndLargestSize() {
        val f = field(file(SCH.fileKinds to listOf(FKIND.mp4, FKIND.webm), SCH.fileMaxSize to 1_000_000))
        assertTrue(f.isDataFile())
        assertEquals(listOf(FileKinds.mp4, FileKinds.webm), f.fileKinds)
        assertEquals(1_000_000L, f.fileMaxSize)
        assertEquals(1_000_000L, f.fileSizeLimit(FileKinds.mp4))
        assertNull(f.fileSizeLimit(FileKinds.png))
        // Unset, each kind's own largest applies.
        val open = field(file(SCH.fileKinds to listOf(FKIND.png, FKIND.mp4)))
        assertEquals(FileKinds.png.maxSize, open.fileSizeLimit(FileKinds.png))
        assertEquals(FileKinds.mp4.maxSize, open.fileSizeLimit(FileKinds.mp4))
        // Below one kind's largest and above another's: each kind is held to the smaller.
        val mixed = field(file(SCH.fileKinds to listOf(FKIND.png, FKIND.mp4), SCH.fileMaxSize to 100L * 1024 * 1024))
        assertEquals(FileKinds.png.maxSize, mixed.fileSizeLimit(FileKinds.png))
        assertEquals(100L * 1024 * 1024, mixed.fileSizeLimit(FileKinds.mp4))
    }

    @Test
    fun aFileFieldMustNameKindsThatExist() {
        assertTrue("must name the kinds" in refusal(file()))
        assertTrue("must name the kinds" in refusal(file(SCH.fileKinds to emptyList<String>())))
        val unknown = refusal(file(SCH.fileKinds to listOf("svg")))
        assertTrue("'svg'" in unknown && "mp4" in unknown, unknown)
        assertTrue("twice" in refusal(file(SCH.fileKinds to listOf(FKIND.png, FKIND.png))))
    }

    @Test
    fun aFileFieldCannotHoldACsvTableYet() {
        // A table's kind waits for a field that declares its columns (issue #1882).
        val csv = refusal(file(SCH.fileKinds to listOf(FKIND.png, FKIND.csv)))
        assertTrue("'csv'" in csv && "declares its columns" in csv, csv)
        assertTrue(", csv" !in csv && "pdf." in csv, csv)
    }

    @Test
    fun aLargestSizeMustBeAWholeNumberThatConstrains() {
        val kinds = SCH.fileKinds to listOf(FKIND.png)
        assertTrue("whole number" in refusal(file(kinds, SCH.fileMaxSize to 0)))
        assertTrue("whole number" in refusal(file(kinds, SCH.fileMaxSize to 10.5)))
        assertTrue("above the largest" in refusal(file(kinds, SCH.fileMaxSize to FileKinds.png.maxSize + 1)))
        assertTrue("a whole number of bytes" in refusal(file(kinds, SCH.fileMaxSize to "big")))
    }

    @Test
    fun theFileKeywordsAreRefusedOffAFileField() {
        val plain = mapOf(SCH.type to SCT.string, SCH.fileKinds to listOf(FKIND.png))
        assertTrue("'${SCH.fileKinds}'" in refusal(plain))
        val sized = mapOf(SCH.type to SCT.string, SCH.format to SFMT.binary, SCH.fileMaxSize to 10)
        assertTrue("'${SCH.fileMaxSize}'" in refusal(sized))
        assertTrue("is a string" in refusal(mapOf(SCH.type to SCT.integer, SCH.format to SFMT.dataFile)))
    }

    @Test
    fun aFileFieldIsNoFreeText() {
        val kinds = SCH.fileKinds to listOf(FKIND.png)
        assertTrue("'${SCH.pattern}'" in refusal(file(kinds, SCH.pattern to "^a")))
        assertTrue("'${SCH.options}'" in refusal(file(kinds, SCH.options to listOf("a"))))
        assertTrue("'${SCH.longText}'" in refusal(file(kinds, SCH.longText to true)))
    }

    @Test
    fun theKeywordsAreListedWithAShapeAMeaningAndAPlace() {
        for (keyword in listOf(SCH.fileKinds, SCH.fileMaxSize)) {
            assertTrue(keyword in SchGKeywords.keywords && keyword in SchGKeywords.meanings && keyword in SchGKeywords.places)
        }
        assertTrue(SchGKeywords.problem("f", SCH.fileKinds, "png") != null)
    }
}
