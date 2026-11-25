package com.dynamicruntime.common.schema

import kotlin.test.Test
import kotlin.test.assertEquals

/** What a person calls a field (issue #1918): one rule, for a page, a listing, and a report. */
class SchFieldLabelTest {
    private val type = parseSchemaTypes(
        mapOf(
            "c.Place" to mapOf(SCH.type to SCT.kObject, SCH.title to "A place"),
            "c.Talk" to mapOf(
                SCH.type to SCT.kObject,
                SCH.properties to mapOf(
                    "talkTitle" to mapOf(SCH.type to SCT.string, SCH.title to "The title"),
                    "startDay" to mapOf(SCH.type to SCT.string),
                    "venue" to mapOf(SCH.dRef to $$"#/$defs/c.Place"),
                ),
            ),
        ),
    ).getValue("c.Talk")

    private fun layout(field: String, label: String?) = SchLayout(
        fragmentFileId = null, label = null, fields = listOf(SchLayoutField(field, label = label, description = null, hint = null)),
    )

    private fun label(field: String, layoutLabel: String? = null) =
        fieldLabelOf(layout(field, layoutLabel), field, type.properties[field])

    @Test
    fun theLayoutsLabelThenTheFieldsTitleThenItsNameInWords() {
        assertEquals("Title", label("talkTitle", "Title"))
        assertEquals("The title", label("talkTitle"))
        assertEquals("Start day", label("startDay"))
        assertEquals("Start day", fieldLabelOf(null, "startDay", null))
    }

    @Test
    fun aRefFieldIsNotCalledByItsTypesTitle() {
        assertEquals("Venue", label("venue"))
    }

    @Test
    fun aTemplateIsForAPlaceThatCanFillItIn() {
        for (template in listOf($$"Title of ${x}", "%{@t(\"a.b\")}", " ")) assertEquals("The title", label("talkTitle", template))
        assertEquals("Title of mine", fieldLabelOf("talkTitle", type.properties["talkTitle"], "Title of mine"))
    }
}
