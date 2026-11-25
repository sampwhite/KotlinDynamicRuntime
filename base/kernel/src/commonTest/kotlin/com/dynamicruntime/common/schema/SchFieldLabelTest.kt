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

/** An author's message that gives the field's label (issue #1918): the label of the place that shows it. */
class SchLabelMessageTest {
    private val shortText = mapOf(
        SCH.type to SCT.string, SCH.minLength to 3, SCH.errors to mapOf("belowMinimum" to $$"${label} needs ${min}."),
    )

    private val types = parseSchemaTypes(
        mapOf(
            "c.Place" to mapOf(SCH.type to SCT.kObject, SCH.properties to mapOf("city" to shortText)),
            "c.Talk" to mapOf(
                SCH.type to SCT.kObject,
                SCH.required to listOf("startDay"),
                SCH.properties to mapOf(
                    "talkTitle" to shortText + (SCH.title to "The title"),
                    "startDay" to mapOf(
                        SCH.type to SCT.string, SCH.errors to mapOf("missingRequired" to $$"Give the ${lower(label)} (${field})."),
                    ),
                    "venue" to mapOf(SCH.dRef to $$"#/$defs/c.Place"),
                    "tags" to mapOf(SCH.type to SCT.array, SCH.items to shortText),
                    "note" to mapOf(SCH.type to SCT.string, SCH.minLength to 3, SCH.errors to mapOf("belowMinimum" to "Say more.")),
                ),
            ),
        ),
    )
    private val talk = types.getValue("c.Talk")
    private val data = mapOf("talkTitle" to "x", "venue" to mapOf("city" to "y"), "tags" to listOf("z"), "note" to "n")

    private fun layouts(titleLabel: String, city: String) = mapOf(
        "c.Talk" to SchLayout(null, null, listOf(SchLayoutField("talkTitle", titleLabel, null, null))),
        "c.Place" to SchLayout(null, null, listOf(SchLayoutField("city", city, null, null))),
    )

    private fun messages(labelOf: SchLabelSource? = null): Map<String, String?> =
        validate(talk, data, SchOpts(labelOf = labelOf)).associate { it.path to it.userMessage }

    @Test
    fun withNoFormTheFieldHasItsTitleOrItsName() {
        assertEquals(
            mapOf(
                "talkTitle" to "The title needs 3.", "venue.city" to "City needs 3.", "tags[0]" to "Tags needs 3.",
                "note" to "Say more.", "startDay" to "Give the start day (startDay).",
            ),
            messages(),
        )
    }

    @Test
    fun aFormsLabelsAreItsOwn() {
        // A client's layout, and a workflow's over it: one field, named by the place that checks it.
        val client = messages(plainLabels(layouts("Title", "Town")))
        assertEquals("Title needs 3." to "Town needs 3.", client["talkTitle"] to client["venue.city"])
        assertEquals("Headline needs 3.", messages(plainLabels(layouts("Headline", "Town")))["talkTitle"])
        // A template nothing here can fill in leaves the field its title.
        assertEquals("The title needs 3.", messages(plainLabels(layouts($$"Title of ${x}", "Town")))["talkTitle"])
    }

    @Test
    fun aPageWordsItAgainWithItsHeading() {
        val found = validate(talk, data).associateBy { it.path }
        assertEquals("My title needs 3.", found.getValue("talkTitle").called("My title").userMessage)
        // One that does not give the label is left as it is.
        assertEquals("Say more.", found.getValue("note").called("Note it").userMessage)
        // On the wire, the words alone.
        assertEquals(setOf("path", "code", "message", "userMessage"), found.getValue("talkTitle").toWireMap().keys)
    }

    @Test
    fun aFormsOwnRequirementNamesItToo() {
        val type = parseSchemaTypes(
            mapOf(
                "c.Talk" to mapOf(
                    SCH.type to SCT.kObject,
                    SCH.properties to mapOf(
                        "speaker" to mapOf(SCH.type to SCT.string, SCH.errors to mapOf("default" to $$"${label}, please.")),
                    ),
                ),
            ),
        ).getValue("c.Talk")
        val layout = SchLayout(null, null, listOf(SchLayoutField("speaker", "Presenter", null, null, required = true)))
        val failure = formRequirementFailures(type, mapOf("c.Talk" to layout), emptyMap<String, Any?>()).single()
        assertEquals("Presenter, please." to true, failure.userMessage to failure.formRequirement)
    }
}
