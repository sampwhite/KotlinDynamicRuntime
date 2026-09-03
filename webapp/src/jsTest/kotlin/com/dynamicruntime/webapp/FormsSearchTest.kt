package com.dynamicruntime.webapp

import com.dynamicruntime.common.endpoint.EI
import com.dynamicruntime.common.endpoint.EP
import com.dynamicruntime.common.schema.SCH
import com.dynamicruntime.common.schema.SCT
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * `searchFields` (issue #538): the forms-list search box reads the listing endpoint's own input schema and
 * keeps every property that is not a reserved paging/scope field, in order, labelled by its `title`. Pure -- a
 * schema map in, the fields out -- so no React and no fetch.
 */
class FormsSearchTest {

    // A listing query as the catalog would serve it for a client: the reserved fields (offset, user, limit)
    // among the client's declared search fields, each search field carrying a title.
    private val inputSchema = mapOf(
        SCH.properties to linkedMapOf(
            EP.offset to mapOf(SCH.type to SCT.integer),
            "name" to mapOf(SCH.title to "Name"),
            "nameContains" to mapOf(SCH.title to "Name (contains)"),
            EI.user to mapOf<String, Any?>(),
            "yearMin" to mapOf(SCH.title to "Year (min)", SCH.type to SCT.number),
            EP.limit to mapOf(SCH.type to SCT.integer),
        ),
    )

    @Test
    fun keepsTheDeclaredSearchFieldsInOrderAndDropsTheReservedOnes() {
        val fields = searchFields(inputSchema)
        assertEquals(listOf("name", "nameContains", "yearMin"), fields.map { it.name })
        assertEquals(listOf("Name", "Name (contains)", "Year (min)"), fields.map { it.label })
    }

    @Test
    fun aFieldWithNoTitleFallsBackToItsParameterName() {
        val schema = mapOf(SCH.properties to mapOf("siteAudit" to mapOf<String, Any?>()))
        assertEquals(listOf("siteAudit"), searchFields(schema).map { it.label })
    }

    @Test
    fun aQueryWithNoSearchFieldsGivesAnEmptyList() {
        val schema = mapOf(
            SCH.properties to mapOf(
                EP.offset to mapOf(SCH.type to SCT.integer),
                EP.limit to mapOf(SCH.type to SCT.integer),
            ),
        )
        assertEquals(emptyList(), searchFields(schema))
    }
}
