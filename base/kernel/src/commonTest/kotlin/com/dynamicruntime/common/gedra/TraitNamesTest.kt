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
    fun aNameIsPlainWords() {
        // Inline Markdown reads as its words: links and images by their text, the marks of code, strong, emphasis, and
        // strike dropped -- but an underscore inside a word is no mark.
        assertEquals("Site visit, see guide", headingName("## **Site** _visit_, see [guide](doc:visits)"))
        assertEquals("Use form_id here", headingName("`Use` ~~form_id~~ *here*"))
        assertEquals("Logo Expenses", headingName("# ![Logo](img:logo) __Expenses__"))
    }

    @Test
    fun aTemplatedHeadingNamesNothingSoTheTitleDoes() {
        // A frontend block only the form fills in, or a backend pull delivered unresolved, is no name in plain text.
        assertEquals("", headingName($$"## ${form.name} visit"))
        assertEquals("Site visit", traitNameOf("siteVisit", $$"## ${form.name} visit", "Site visit"))
        assertEquals("Visit", traitNameOf("visit", """%{@t("missing.key")}""", null))
        // The line as shown keeps the block: Design View says what the section shows.
        assertEquals($$"${form.name} visit", headingLine($$"## ${form.name} visit"))
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
