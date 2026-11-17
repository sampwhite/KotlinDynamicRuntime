package com.dynamicruntime.common.filekind

import com.dynamicruntime.common.exception.KdrException
import com.dynamicruntime.common.schema.SCH
import com.dynamicruntime.common.schema.SCT
import com.dynamicruntime.common.schema.SFMT
import com.dynamicruntime.common.schema.SchType
import com.dynamicruntime.common.schema.parseSchemaTypes
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
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
            // A union: the branch the value selects says which of its fields is a file.
            "ns.Media" to mapOf(
                SCH.oneOf to listOf(
                    mapOf(
                        SCH.type to SCT.kObject,
                        SCH.properties to mapOf("kind" to mapOf(SCH.type to SCT.string, SCH.const to "video"), "src" to clip),
                    ),
                    mapOf(
                        SCH.type to SCT.kObject,
                        SCH.properties to mapOf(
                            "kind" to mapOf(SCH.type to SCT.string, SCH.const to "link"),
                            "src" to mapOf(SCH.type to SCT.string),
                        ),
                    ),
                ),
                SCH.discriminator to mapOf(SCH.propertyName to "kind"),
            ),
            // A type that refers to itself, as a folder of folders does.
            "ns.Folder" to mapOf(
                SCH.type to SCT.kObject,
                SCH.properties to mapOf("cover" to photo, "inner" to mapOf(SCH.dRef to "#/\$defs/ns.Folder")),
            ),
            "ns.Loop" to mapOf(
                SCH.type to SCT.kObject,
                SCH.properties to mapOf("next" to mapOf(SCH.dRef to "#/\$defs/ns.Loop")),
            ),
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

    @Test
    fun aUnionsFilesAreThoseOfTheBranchTheValueSelects() {
        val video = DataFileFields.collect(type("ns.Media"), mapOf("kind" to "video", "src" to "gd.df.acme.v1"))
        assertEquals(listOf("src=gd.df.acme.v1"), video.map { it.toString() })
        // The same field in a branch where it is plain text names no file.
        assertEquals(emptyList(), DataFileFields.collect(type("ns.Media"), mapOf("kind" to "link", "src" to "https://x")))
        assertTrue(DataFileFields.holdsFiles(type("ns.Media")))
    }

    @Test
    fun aTypeThatRefersToItselfIsWalkedAsDeepAsItsValue() {
        val value = mapOf("cover" to "gd.df.acme.a", "inner" to mapOf("inner" to mapOf("cover" to "gd.df.acme.c")))
        assertEquals(
            listOf("cover=gd.df.acme.a", "inner.inner.cover=gd.df.acme.c"),
            DataFileFields.collect(type("ns.Folder"), value).map { it.toString() },
        )
        assertTrue(DataFileFields.holdsFiles(type("ns.Folder")))
        // A loop with no file field anywhere ends, rather than following itself to the depth limit.
        assertFalse(DataFileFields.holdsFiles(type("ns.Loop")))
    }

    @Test
    fun aValueDeeperThanTheLimitIsRefused() {
        var value: Map<String, Any?> = mapOf("cover" to "gd.df.acme.deep")
        repeat(DataFileFields.maxDepth + 1) { value = mapOf("inner" to value) }
        assertFailsWith<KdrException> { DataFileFields.collect(type("ns.Folder"), value) }
    }

    @Test
    fun onlyAStringNamesAFile() {
        assertEquals(emptyList(), DataFileFields.collect(type("ns.Visit"), mapOf("clip" to 5)))
        assertEquals(emptyList(), DataFileFields.collect(type("ns.Visit"), mapOf("clip" to true)))
    }
}
