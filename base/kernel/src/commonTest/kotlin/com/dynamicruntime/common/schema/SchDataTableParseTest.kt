package com.dynamicruntime.common.schema

import com.dynamicruntime.common.exception.KdrException
import com.dynamicruntime.common.filekind.FKIND
import com.dynamicruntime.common.filekind.FileKinds
import com.dynamicruntime.common.table.DTAB
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/** A table field declares its rows' type, whose properties are its columns (issue #1882). */
class SchDataTableParseTest {
    private val line: Map<String, Any?> = mapOf(
        SCH.type to SCT.kObject,
        SCH.properties to mapOf(
            "day" to mapOf(SCH.type to SCT.string, SCH.format to SFMT.date),
            "amount" to mapOf(SCH.type to SCT.number, SCH.minimum to 0),
        ),
        SCH.required to listOf("day", "amount"),
    )

    private fun table(vararg extra: Pair<String, Any?>): Map<String, Any?> =
        mapOf(SCH.type to SCT.string, SCH.format to SFMT.dataFile, SCH.fileKinds to listOf(FKIND.csv)) + extra

    private fun parse(field: Map<String, Any?>, rowType: Map<String, Any?> = line): Map<String, SchType> = parseSchemaTypes(
        mapOf(
            "ns.Line" to rowType,
            "ns.Claim" to mapOf(SCH.type to SCT.kObject, SCH.properties to mapOf("lines" to field)),
        ),
    )

    private fun field(field: Map<String, Any?>, rowType: Map<String, Any?> = line): SchType =
        parse(field, rowType).getValue("ns.Claim").properties.getValue("lines").valueType

    private fun refusal(field: Map<String, Any?>, rowType: Map<String, Any?> = line): String =
        assertFailsWith<KdrException> { parse(field, rowType) }.message.orEmpty()

    private fun rowsOf(rowType: Map<String, Any?>) = table(SCH.fileRows to rowType)

    private val byRef = table(SCH.fileRows to mapOf(SCH.dRef to "ns.Line"))

    @Test
    fun aTableFieldBindsItsRowTypeByReference() {
        val types = parse(byRef + (SCH.fileMaxRows to 200))
        val lines = types.getValue("ns.Claim").properties.getValue("lines").valueType
        assertTrue(lines.isDataTable())
        assertTrue(lines.isDataFile())
        assertSame(types.getValue("ns.Line"), lines.fileRowType)
        assertEquals(200, lines.tableRowLimit())
        assertEquals(listOf(FileKinds.csv), lines.fileKinds)
    }

    @Test
    fun aTableFieldTakesItsRowTypeInline() {
        val lines = field(rowsOf(line))
        assertEquals(listOf("day", "amount"), lines.fileRowType?.properties?.keys?.toList())
        assertEquals(DTAB.defaultMaxRows, lines.tableRowLimit())
    }

    @Test
    fun aFileFieldThatIsNoTableIsNotOne() {
        val photo = field(table(SCH.fileKinds to listOf(FKIND.png)))
        assertTrue(!photo.isDataTable())
        assertNull(photo.fileRowType)
    }

    @Test
    fun csvAndRowsComeTogether() {
        assertTrue("declares no '${SCH.fileRows}'" in refusal(table()))
        assertTrue("beside other kinds" in refusal(byRef + (SCH.fileKinds to listOf(FKIND.csv, FKIND.pdf))))
        assertTrue("must hold only 'csv'" in refusal(byRef + (SCH.fileKinds to listOf(FKIND.pdf))))
        assertTrue("'${SCH.fileMaxRows}'" in refusal(table(SCH.fileKinds to listOf(FKIND.pdf), SCH.fileMaxRows to 5)))
        // Off a file field, either keyword constrains nothing.
        val text = mapOf(SCH.type to SCT.string, SCH.fileRows to mapOf(SCH.dRef to "ns.Line"))
        assertTrue("'${SCH.fileRows}'" in refusal(text))
    }

    @Test
    fun theMostRowsIsAWholeNumberWithinTheCodecsLimit() {
        assertEquals(5000, field(byRef + (SCH.fileMaxRows to 5000)).tableRowLimit())
        for (bad in listOf(0, 5001, 10.5, "many")) {
            assertTrue("whole number of rows" in refusal(byRef + (SCH.fileMaxRows to bad)), "$bad")
        }
    }

    @Test
    fun aRowTypeMustBeAnObjectOfColumns() {
        assertTrue("an object" in refusal(rowsOf(mapOf(SCH.type to SCT.string))))
        assertTrue("declares no columns" in refusal(rowsOf(mapOf(SCH.type to SCT.kObject))))
        assertTrue("undeclared" in refusal(rowsOf(line + (SCH.additionalProperties to true))))
        assertTrue("unknown type" in refusal(table(SCH.fileRows to mapOf(SCH.dRef to "ns.Nothing"))))
        val wide = mapOf(SCH.type to SCT.kObject, SCH.properties to (1..101).associate { "c$it" to mapOf(SCH.type to SCT.string) })
        assertTrue("at most 100" in refusal(rowsOf(wide)))
    }

    @Test
    fun aColumnIsAValueACellCarries() {
        fun column(body: Map<String, Any?>, name: String = "c") = refusal(rowsOf(mapOf(SCH.type to SCT.kObject, SCH.properties to mapOf(name to body))))
        assertTrue("not a string, number" in column(mapOf(SCH.type to SCT.array, SCH.items to mapOf(SCH.type to SCT.string))))
        assertTrue("timestamp" in column(mapOf(SCH.type to SCT.string, SCH.format to SFMT.dateTime)))
        assertTrue("is a file" in column(table(SCH.fileKinds to listOf(FKIND.png))))
        assertTrue("is derived" in column(mapOf(SCH.type to SCT.number, SCH.derived to true)))
        assertTrue("is a secret" in column(mapOf(SCH.type to SCT.string, SCH.sensitive to true)))
        assertTrue("shown only by a fact" in column(mapOf(SCH.type to SCT.string, SCH.visibleWhen to "isAdmin")))
        assertTrue("spaces around its name" in column(mapOf(SCH.type to SCT.string), name = "amount "))
        // Choices, booleans, integers, and days are columns.
        val fine = mapOf(
            SCH.type to SCT.kObject,
            SCH.properties to mapOf(
                "kind" to mapOf(SCH.type to SCT.string, SCH.options to listOf(mapOf(SCH.value to "meal", SCH.label to "Meal"))),
                "receipt" to mapOf(SCH.type to SCT.boolean),
                "count" to mapOf(SCH.type to SCT.integer),
                "on" to mapOf(SCH.type to SCT.string, SCH.format to SFMT.date),
            ),
        )
        assertEquals(4, field(rowsOf(fine)).fileRowType?.properties?.size)
    }

    @Test
    fun aColumnMayBeAReferenceToASharedScalar() {
        val types = parseSchemaTypes(
            mapOf(
                "ns.Category" to mapOf(SCH.type to SCT.string, SCH.options to listOf(mapOf(SCH.value to "meal"))),
                "ns.Line" to mapOf(SCH.type to SCT.kObject, SCH.properties to mapOf("category" to mapOf(SCH.dRef to "ns.Category"))),
                "ns.Claim" to mapOf(SCH.type to SCT.kObject, SCH.properties to mapOf("lines" to byRef)),
            ),
        )
        assertTrue(types.getValue("ns.Claim").properties.getValue("lines").valueType.isDataTable())
    }
}
