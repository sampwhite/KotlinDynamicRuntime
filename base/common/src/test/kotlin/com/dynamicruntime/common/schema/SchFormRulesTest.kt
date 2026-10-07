package com.dynamicruntime.common.schema

import com.dynamicruntime.common.endpoint.EP
import com.dynamicruntime.common.exception.KdrException
import com.dynamicruntime.common.util.Parsed
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain

/**
 * A form's requirements (issue #1022): the layout keys that carry them, their load checks, and the kernel rule the
 * page, the workflow's save and its task status all run. What the schema withdraws for the current answers the form
 * never asks for, and what the schema already refuses is the validator's to report, not this rule's.
 */
class SchFormRulesTest : StringSpec({
    val types = parseSchemaTypes(
        mapOf(
            "client.demo.Contact" to mapOf(
                SCH.type to SCT.kObject,
                SCH.properties to mapOf("email" to mapOf(SCH.type to SCT.string), "phone" to mapOf(SCH.type to SCT.string)),
            ),
            "client.demo.Event" to mapOf(
                SCH.type to SCT.kObject,
                SCH.required to listOf("title"),
                SCH.properties to mapOf(
                    "title" to mapOf(SCH.type to SCT.string),
                    "venue" to mapOf(
                        SCH.type to SCT.string,
                        SCH.options to listOf(
                            mapOf(SCH.value to "office", SCH.label to "At the office"),
                            mapOf(SCH.value to "hotel", SCH.label to "A hotel"),
                            mapOf(SCH.value to "park", SCH.label to "A park"),
                        ),
                    ),
                    "attendees" to mapOf(SCH.type to SCT.integer),
                    "rainPlan" to mapOf(SCH.type to SCT.string),
                    "adminNote" to mapOf(SCH.type to SCT.string, SCH.visibleWhen to "kdr:hasAdminLevel"),
                    "total" to mapOf(SCH.type to SCT.number, SCH.derived to true),
                    "contact" to mapOf(SCH.dRef to $$"#/$defs/client.demo.Contact"),
                ),
                // `rainPlan` is asked for only for an event in the park.
                SCH.kIf to mapOf(SCH.required to listOf("venue"), SCH.properties to mapOf("venue" to mapOf(SCH.const to "park"))),
                SCH.kThen to mapOf(SCH.required to listOf("rainPlan")),
                SCH.kElse to mapOf(SCH.not to mapOf(SCH.required to listOf("rainPlan"))),
            ),
        ),
    )
    val event = types.getValue("client.demo.Event")
    val venue = event.properties.getValue("venue").valueType

    fun layout(vararg fields: Map<String, Any?>) = parseSchLayout("Type 'Event'", mapOf(SL.schemaFields to fields.toList()))
    fun choice(value: String, label: String? = null) = buildMap { put(SL.value, value); label?.let { put(SL.label, it) } }

    "the keys round-trip, and a builder writes the block the parser reads" {
        val parsed = layout(
            mapOf(SL.field to "attendees", SL.required to true),
            mapOf(SL.field to "venue", SL.choices to listOf(choice("park", "Outdoors"), choice("office"))),
        )
        parsed.fieldFor("attendees")!!.required shouldBe true
        parsed.fieldFor("venue")!!.choices!!.map { it.value } shouldBe listOf("park", "office")
        parseSchLayout("X", parsed.toJsonMap()).fieldFor("venue")!!.choices!!.first().label shouldBe "Outdoors"

        val built = SchLayoutBuilder(null).apply {
            field("venue", required = true, choices = listOf(SchLayoutChoice("hotel", "A conference hotel")))
        }.build()
        val entry = parseSchLayout("X", built).fieldFor("venue")!!
        entry.required shouldBe true
        entry.choices!!.single().label shouldBe "A conference hotel"
    }

    "a malformed requirement is refused: false, an empty or repeated list, an unknown key" {
        shouldThrow<KdrException> { layout(mapOf(SL.field to "title", SL.required to false)) }.message.orEmpty() shouldContain "true or absent"
        shouldThrow<KdrException> { layout(mapOf(SL.field to "venue", SL.choices to emptyList<Any?>())) }
        shouldThrow<KdrException> { layout(mapOf(SL.field to "venue", SL.choices to listOf(choice("park"), choice("park")))) }
            .message.orEmpty() shouldContain "repeats"
        shouldThrow<KdrException> { layout(mapOf(SL.field to "venue", SL.choices to listOf(mapOf(SL.value to "park", "image" to "x.png")))) }
            .message.orEmpty() shouldContain "unknown key"
    }

    "the load check refuses choices the schema does not offer, and a requirement nobody could always meet" {
        fun codes(vararg fields: Map<String, Any?>) = layoutFieldProblems("Type 'Event'", layout(*fields), event).map { it.code }
        codes(mapOf(SL.field to "venue", SL.choices to listOf(choice("garden")))) shouldBe listOf(LayoutError.notAChoice)
        codes(mapOf(SL.field to "title", SL.choices to listOf(choice("x")))) shouldBe listOf(LayoutError.choicesWithoutOptions)
        codes(mapOf(SL.field to "adminNote", SL.required to true)) shouldBe listOf(LayoutError.requiredUnfillable)
        codes(mapOf(SL.field to "total", SL.required to true)) shouldBe listOf(LayoutError.requiredUnfillable)
        // Withdrawn only for some answers is fine: the requirement waits until the schema asks for the field.
        codes(mapOf(SL.field to "rainPlan", SL.required to true), mapOf(SL.field to "venue", SL.choices to listOf(choice("park")))).shouldBeEmpty()
    }

    "the form offers its own list, in its order and words, of what the schema offers" {
        offeredChoices(venue, null) shouldBe venue.options
        val entry = layout(mapOf(SL.field to "venue", SL.choices to listOf(choice("park", "Outdoors"), choice("office")))).fieldFor("venue")
        offeredChoices(venue, entry) shouldBe listOf(SchOption("park", "Outdoors"), SchOption("office", "At the office"))
        offeredChoices(event.properties.getValue("title").valueType, entry) shouldBe null
    }

    "a field the form requires is asked for, unless the schema withdraws it or already requires it" {
        val forms = mapOf(
            "client.demo.Event" to layout(
                mapOf(SL.field to "attendees", SL.required to true),
                mapOf(SL.field to "rainPlan", SL.required to true),
                mapOf(SL.field to "title", SL.required to true),
            ),
        )
        // At the office: the schema withdraws the rain plan, so only attendees is missing. The title is the validator's.
        formRequirementFailures(event, forms, mapOf("venue" to "office")).map { it.path to it.code } shouldBe
            listOf("attendees" to SchFailCode.missingRequired)
        // In the park the schema itself asks for the rain plan -- reported once, by the validator, not here too.
        formRequirementFailures(event, forms, mapOf("venue" to "park", "attendees" to 30)).shouldBeEmpty()
        formRequirementFailures(event, forms, mapOf("venue" to "office", "attendees" to "  ")).single().path shouldBe "attendees"
        formRequirementFailures(event, emptyMap(), mapOf("venue" to "office")).shouldBeEmpty()
    }

    "a choice the schema allows but the form does not offer is refused, naming the form's choices" {
        val forms = mapOf("client.demo.Event" to layout(mapOf(SL.field to "venue", SL.choices to listOf(choice("park"), choice("office")))))
        val failure = formRequirementFailures(event, forms, mapOf("venue" to "hotel"), "data").single()
        failure.path shouldBe "data.venue"
        failure.code shouldBe SchFailCode.invalidOption
        // Marked as the form's, on the wire too; a schema failure's wire form carries nothing new.
        failure.formRequirement shouldBe true
        failure.toWireMap()[EP.failureFormRequirement] shouldBe true
        SchFailure("venue", SchFailCode.invalidOption, "x").toWireMap().containsKey(EP.failureFormRequirement) shouldBe false
        failure.options!!.map { it.value } shouldBe listOf("park", "office")
        // A value the schema refuses is the validator's failure, not a second one here.
        formRequirementFailures(event, forms, mapOf("venue" to "garden")).shouldBeEmpty()
    }

    "a named type reached through a field is judged under its own layout" {
        val forms = mapOf("client.demo.Contact" to layout(mapOf(SL.field to "phone", SL.required to true)))
        formRequirementFailures(event, forms, mapOf("contact" to mapOf("email" to "a@b.c"))).single().path shouldBe "contact.phone"
        // No contact at all: nothing below it to ask for.
        formRequirementFailures(event, forms, emptyMap<String, Any?>()).shouldBeEmpty()
    }

    "a workflow's restated choices replace the shared list whole" {
        val base = mapOf(SL.schemaFields to listOf(mapOf(SL.field to "venue", SL.label to "Venue", SL.choices to listOf(choice("office"), choice("hotel")))))
        val overlay = mapOf(SL.schemaFields to listOf(mapOf(SL.field to "venue", SL.choices to listOf(choice("park")))))
        val merged = overlayTypeOutcome("client.demo.Event", mapOf(SCH.layout to base), mapOf(SCH.layout to overlay)).value
        val entry = (parseTypeLayout("client.demo.Event", merged) as Parsed.Ok).value!!.fieldFor("venue")!!
        entry.choices!!.map { it.value } shouldBe listOf("park")
    }
})
