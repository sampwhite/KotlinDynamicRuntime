package com.dynamicruntime.webapp

import com.dynamicruntime.common.endpoint.EI
import com.dynamicruntime.common.schema.SCH
import com.dynamicruntime.common.schema.SCT
import com.dynamicruntime.common.schema.SL
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Pure-logic coverage for the catalog reader (issue #585): a `/schema/endpoints` `results` map into a
 * [Catalog], with the out-of-band closures -- cfacts and now layouts -- parsed beside the schema. Maps in,
 * model out; the fetch that produces the map is not under test here.
 */
class CatalogModelTest {
    private val questionnaire = "sampleconfig.QuestionnaireData"

    private fun results(): Map<String, Any?> = mapOf(
        EI.endpoints to listOf(
            mapOf(
                EI.path to "/gedra/formDoc/create", EI.method to "POST", EI.kind to "general", EI.namespace to "gedra",
                EI.inputSchema to mapOf(SCH.type to SCT.kObject), EI.outputSchema to mapOf(SCH.type to SCT.kObject),
            ),
        ),
        SCH.dDefs to mapOf(
            questionnaire to mapOf(
                SCH.type to SCT.kObject,
                SCH.properties to mapOf("topic" to mapOf(SCH.type to SCT.string), "notes" to mapOf(SCH.type to SCT.string)),
            ),
            "sampleconfig.Plain" to mapOf(SCH.type to SCT.kObject),
        ),
        EI.filtersAvailable to false,
        EI.cfacts to mapOf("hasAdminLevel" to false),
        // The layouts closure (issue #585): only the type that declares one has an entry.
        EI.layouts to mapOf(
            questionnaire to mapOf(
                SL.fragmentFileId to "acme",
                SL.schemaFields to listOf(
                    mapOf(SL.field to "topic", SL.label to "Topic"),
                    mapOf(SL.field to "notes", SL.label to "Anything else?"),
                ),
            ),
        ),
    )

    @Test
    fun parsesTheLayoutsClosureBesideTheSchema() {
        val catalog = parseCatalog(results())
        assertEquals(1, catalog.endpoints.size)
        assertTrue(!catalog.filtersAvailable)
        assertEquals(false, catalog.cfacts["hasAdminLevel"])
        // The layout is joined to the type by the same key `$defs` and `defTypes` use.
        assertTrue(catalog.defTypes.containsKey(questionnaire))
        val layout = catalog.layouts[questionnaire]!!
        assertEquals("acme", layout.fragmentFileId)
        assertEquals(listOf("topic", "notes"), layout.fieldNames)
        assertEquals("Anything else?", layout.fields[1].label)
        // A type with no layout has no entry -- absent, not empty.
        assertNull(catalog.layouts["sampleconfig.Plain"])
        // The served schema carries no layout keyword: the closure is the only place a layout travels.
        assertTrue(!(catalog.defs[questionnaire] as Map<*, *>).containsKey(SCH.layout))
    }

    @Test
    fun aResponseWithNoLayoutsKeyParsesToNoLayouts() {
        // An older node, or a catalog of types that declare none: the schema renders alone, nothing faults.
        val catalog = parseCatalog(results() - EI.layouts)
        assertTrue(catalog.layouts.isEmpty())
        assertTrue(catalog.defTypes.containsKey(questionnaire))
    }
}
