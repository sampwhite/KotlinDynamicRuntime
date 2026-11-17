package com.dynamicruntime.common.filekind

import com.dynamicruntime.common.schema.SCH
import com.dynamicruntime.common.schema.SCT
import com.dynamicruntime.common.schema.SFMT
import com.dynamicruntime.common.schema.SchType
import com.dynamicruntime.common.schema.parseSchemaTypes
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** The files a value names are found by its type, however deep (issue #1834). */
class DataFileFieldsTest {
    private val clip = mapOf(SCH.type to SCT.string, SCH.format to SFMT.dataFile, SCH.fileKinds to listOf(FKIND.mp4))
    private val photo = mapOf(SCH.type to SCT.string, SCH.format to SFMT.dataFile, SCH.fileKinds to listOf(FKIND.png))

    private val types = parseSchemaTypes(
        mapOf(
            "ns.Shot" to mapOf(
                SCH.type to SCT.kObject,
                SCH.properties to mapOf("caption" to mapOf(SCH.type to SCT.string), "photo" to photo),
            ),
            "ns.Visit" to mapOf(
                SCH.type to SCT.kObject,
                SCH.properties to mapOf(
                    "note" to mapOf(SCH.type to SCT.string),
                    "clip" to clip,
                    "shots" to mapOf(SCH.type to SCT.array, SCH.items to mapOf(SCH.dRef to "#/\$defs/ns.Shot")),
                    "byRoom" to mapOf(SCH.type to SCT.kObject, SCH.additionalProperties to photo),
                ),
            ),
            "ns.Plain" to mapOf(SCH.type to SCT.kObject, SCH.properties to mapOf("note" to mapOf(SCH.type to SCT.string))),
        ),
    )

    private fun type(name: String): SchType = types.getValue(name)

    @Test
    fun filesAreFoundInFieldsListsAndMaps() {
        val value = mapOf(
            "note" to "df.not.a.file",
            "clip" to "gd.df.acme.c1",
            "shots" to listOf(mapOf("caption" to "a", "photo" to "gd.df.acme.p1"), mapOf("caption" to "b")),
            "byRoom" to mapOf("kitchen" to "gd.df.acme.p2"),
        )
        val uses = DataFileFields.collect(type("ns.Visit"), value)
        assertEquals(
            listOf("clip=gd.df.acme.c1", "shots[0].photo=gd.df.acme.p1", "byRoom.kitchen=gd.df.acme.p2"),
            uses.map { it.toString() },
        )
        assertEquals(listOf(FileKinds.mp4), uses.first().field.fileKinds)
    }

    @Test
    fun aBlankOrAbsentFileNamesNothing() {
        assertEquals(emptyList(), DataFileFields.collect(type("ns.Visit"), mapOf("clip" to "", "shots" to emptyList<Any>())))
        assertEquals(emptyList(), DataFileFields.collect(type("ns.Visit"), null))
    }

    @Test
    fun aTypeWithNoFileFieldIsSaidToHoldNone() {
        assertTrue(DataFileFields.holdsFiles(type("ns.Visit")))
        assertTrue(DataFileFields.holdsFiles(type("ns.Shot")))
        assertFalse(DataFileFields.holdsFiles(type("ns.Plain")))
    }
}
