package com.dynamicruntime.common.gedra

import com.dynamicruntime.common.schema.SCH
import com.dynamicruntime.common.schema.SCT
import com.dynamicruntime.common.schema.SL
import com.dynamicruntime.common.schema.SchType
import com.dynamicruntime.common.schema.parseSchLayout
import com.dynamicruntime.common.schema.parseSchemaTypes
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * A trait's name (issue #1520): its data type's layout heading, then its title, then its id humanized. In `commonTest`,
 * since the backend's lock refusals and every page name a trait by the same rule.
 */
class TraitNamesTest {
    @Test
    fun theHeadingWinsOverTheTitleAndTheTitleOverTheId() {
        assertEquals("Site Visit Data", traitNameOf("siteVisit", "Site Visit Data", "Site visit"))
        assertEquals("Site visit", traitNameOf("siteVisit", null, "Site visit"))
        assertEquals("Site visit", traitNameOf("siteVisit", null, null))
        // Blank is absent, at every step.
        assertEquals("Visit", traitNameOf("siteVisit", "  ", " Visit "))
        assertEquals("Site visit", traitNameOf("siteVisit", "\n", ""))
    }

    @Test
    fun aMarkdownHeadingNamesTheTraitByItsFirstLine() {
        // A heading may carry Markdown and copy under it; only the section heading shows the rest.
        assertEquals("Questionnaire", headingName("## Questionnaire\nChoose a topic, then answer."))
        assertEquals("Expenses", headingName("\n  # Expenses  \n\nMore."))
        assertEquals("", headingName("##"))
        assertEquals("Site visit", traitNameOf("siteVisit", "##", null))
    }

    @Test
    fun aDataTypeIsJoinedToItsLayoutByItsQualifiedName() {
        val types = parseSchemaTypes(
            mapOf(
                "t.VisitData" to mapOf(SCH.type to SCT.kObject, SCH.title to "Visit (title)"),
                "t.PlainData" to mapOf(SCH.type to SCT.kObject),
            ),
        )
        val layouts = mapOf("t.VisitData" to parseSchLayout("Type 't.VisitData'", mapOf(SL.label to "## Site Visit Data\nCopy.")))
        assertEquals("Site Visit Data", traitDisplayName("siteVisit", types.getValue("t.VisitData"), layouts))
        assertEquals("Visit (title)", traitDisplayName("siteVisit", types.getValue("t.VisitData"), emptyMap()))
        assertEquals("Plain trait", traitDisplayName("plainTrait", types.getValue("t.PlainData"), layouts))
        assertEquals("Unknown here", traitDisplayName("unknownHere", null as SchType?, layouts))
    }
}
