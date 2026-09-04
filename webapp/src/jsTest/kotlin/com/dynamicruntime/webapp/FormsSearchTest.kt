package com.dynamicruntime.webapp

import com.dynamicruntime.common.endpoint.EI
import com.dynamicruntime.common.endpoint.EP
import com.dynamicruntime.common.gedra.UsageKind
import com.dynamicruntime.common.schema.SCH
import com.dynamicruntime.common.schema.SCT
import com.dynamicruntime.common.schema.SFMT
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * `searchGroups` (issue #538, grouped in #562): the forms-list search reads the listing endpoint's own input
 * schema, keeps every property that is not a reserved paging/scope field, in order, labelled by its `title`,
 * and gathers each trait's parameters into one control. Pure -- a schema map in, the groups out -- so no React
 * and no fetch.
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
    fun keepsTheDeclaredTraitsInOrderAndDropsTheReservedFields() {
        val groups = searchGroups(inputSchema)
        assertEquals(listOf("name", "year"), groups.map { it.traitId })
        assertEquals(listOf("Name", "Year"), groups.map { it.label })
        // A lone lower bound is still a range control, with no upper box.
        assertEquals("yearMin", groups[1].min)
        assertNull(groups[1].max)
    }

    @Test
    fun aTraitWithNoTitleFallsBackToItsId() {
        val schema = mapOf(SCH.properties to mapOf("siteAudit" to mapOf<String, Any?>()))
        assertEquals(listOf("siteAudit"), searchGroups(schema).map { it.label })
    }

    @Test
    fun theFreeTextTermIsReservedToo() {
        val schema = mapOf(SCH.properties to linkedMapOf(EI.q to mapOf<String, Any?>(), "name" to mapOf(SCH.title to "Name")))
        assertEquals(listOf("name"), searchGroups(schema).map { it.traitId })
    }

    // A client's full listing query as the catalog serves it (issue #562): text traits with and without a
    // substring parameter, a number range, a date range, and the reserved fields among them.
    private val groupedSchema = mapOf(
        SCH.properties to linkedMapOf(
            EP.offset to mapOf(SCH.type to SCT.integer),
            "name" to mapOf(SCH.title to "Name"),
            "nameContains" to mapOf(SCH.title to "Name (contains)"),
            EI.user to mapOf<String, Any?>(),
            "yearMin" to mapOf(SCH.title to "Year (min)", SCH.type to SCT.number),
            "yearMax" to mapOf(SCH.title to "Year (max)", SCH.type to SCT.number),
            "dueMin" to mapOf(SCH.title to "Due (min)", SCH.type to SCT.string, SCH.format to SFMT.date),
            "dueMax" to mapOf(SCH.title to "Due (max)", SCH.type to SCT.string, SCH.format to SFMT.date),
            "site" to mapOf(SCH.title to "Site"),
            EP.limit to mapOf(SCH.type to SCT.integer),
            EI.q to mapOf<String, Any?>(),
        ),
    )

    @Test
    fun groupsEachTraitsParametersIntoOneControl() {
        val groups = searchGroups(groupedSchema)
        assertEquals(listOf("name", "year", "due", "site"), groups.map { it.traitId })
        // The caption is the column label, the role's own words removed.
        assertEquals(listOf("Name", "Year", "Due", "Site"), groups.map { it.label })
        val name = groups[0]
        assertFalse(name.isRange)
        // A text box sends the substring parameter when there is one.
        assertEquals("nameContains", name.text)
        assertEquals("name", name.exact)
        val year = groups[1]
        assertTrue(year.isRange)
        assertEquals(UsageKind.number, year.kind)
        assertEquals("yearMin", year.min)
        assertEquals("yearMax", year.max)
        assertNull(year.text)
        assertEquals(UsageKind.date, groups[2].kind)
        // A text trait without a substring parameter searches by its exact one.
        val site = groups[3]
        assertNull(site.contains)
        assertEquals("site", site.text)
    }

    @Test
    fun saysTheAppliedFiltersInWords() {
        val groups = searchGroups(groupedSchema)
        assertEquals(emptyList(), activeFilterChips(groups, emptyMap()))
        val applied = mapOf(
            "nameContains" to "plan",
            "yearMin" to "2020",
            "yearMax" to "2025",
            "dueMax" to "2026-01-01",
            "site" to "North",
            // The term and the user scope have their own controls, so they are not chips.
            EI.q to "anything",
            EI.user to "7",
        )
        assertEquals(
            listOf("Name contains \"plan\"", "Year 2020 – 2025", "Due ≤ 2026-01-01", "Site is \"North\""),
            activeFilterChips(groups, applied),
        )
        // A blank value is no filter; one bound alone says which.
        assertEquals(listOf("Year ≥ 2020"), activeFilterChips(groups, mapOf("yearMin" to "2020", "yearMax" to " ")))
    }

    @Test
    fun aQueryWithNoSearchFieldsGivesAnEmptyList() {
        val schema = mapOf(
            SCH.properties to mapOf(
                EP.offset to mapOf(SCH.type to SCT.integer),
                EP.limit to mapOf(SCH.type to SCT.integer),
            ),
        )
        assertEquals(emptyList(), searchGroups(schema))
    }
}
