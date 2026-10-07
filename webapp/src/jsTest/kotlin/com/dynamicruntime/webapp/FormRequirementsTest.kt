package com.dynamicruntime.webapp

import com.dynamicruntime.common.schema.SCH
import com.dynamicruntime.common.schema.SCT
import com.dynamicruntime.common.schema.SL
import com.dynamicruntime.common.schema.SchFailCode
import com.dynamicruntime.common.schema.SchOption
import com.dynamicruntime.common.schema.parseSchLayout
import com.dynamicruntime.common.schema.parseSchemaTypes
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * A workflow form's requirements on the page (issue #1022): the check run before a save -- the kernel rule the
 * backend's save runs -- and the choice list a form's control shows, including a value saved elsewhere that the form
 * does not offer.
 */
class FormRequirementsTest {
    private val event = parseSchemaTypes(
        mapOf(
            "client.demo.Event" to mapOf(
                SCH.type to SCT.kObject,
                SCH.properties to mapOf(
                    "venue" to mapOf(
                        SCH.type to SCT.string,
                        SCH.options to listOf(
                            mapOf(SCH.value to "office", SCH.label to "At the office"),
                            mapOf(SCH.value to "hotel", SCH.label to "A hotel"),
                            mapOf(SCH.value to "park", SCH.label to "A park"),
                        ),
                    ),
                    "attendees" to mapOf(SCH.type to SCT.integer),
                ),
            ),
        ),
    ).getValue("client.demo.Event")
    private val layout = parseSchLayout(
        "Type 'Event'",
        mapOf(
            SL.schemaFields to listOf(
                mapOf(SL.field to "attendees", SL.required to true),
                mapOf(SL.field to "venue", SL.choices to listOf(mapOf(SL.value to "park", SL.label to "Outdoors"), mapOf(SL.value to "office"))),
            ),
        ),
    )
    private val layouts = mapOf("client.demo.Event" to layout)
    private val venue = event.properties.getValue("venue").valueType

    @Test
    fun theFormsRequirementsAreCheckedOnTopOfTheSchema() {
        val check = checkFormInput(event, mapOf("venue" to "hotel"), layouts)
        assertEquals(listOf("venue" to SchFailCode.invalidOption, "attendees" to SchFailCode.missingRequired), check.failures.map { it.path to it.code })
        assertNull(check.payload)
        assertTrue(check.failures.all { it.formRequirement })
        // The attendees arrive as text from the input and are judged as the number they coerce to.
        assertTrue(checkFormInput(event, mapOf("venue" to "park", "attendees" to "12"), layouts).isValid)
        // With no form layout, the schema alone decides.
        assertTrue(checkFormInput(event, mapOf("venue" to "hotel"), emptyMap()).isValid)
    }

    @Test
    fun theRailCountsTheFormsRequirementsAsTheServerDoes() {
        val task = WfTaskView("t", "T", traits = listOf(WfTraitView("event", true, event, "client.demo.Event", layout)), saves = emptyList())
        // An unsaved form missing what this form asks for needs information, as one missing a schema field would.
        assertEquals(listOf("event"), localTaskStatus(task, mapOf("event" to mapOf("venue" to "park")), layouts).missingTraits)
        val notOffered = localTaskStatus(task, mapOf("event" to mapOf("venue" to "hotel", "attendees" to 3)), layouts)
        assertTrue(notOffered.complete && !notOffered.valid)
        // Judged by the schema alone, the same values are fine.
        assertTrue(localTaskStatus(task, mapOf("event" to mapOf("venue" to "hotel")), emptyMap()).let { it.complete && it.valid })
    }

    @Test
    fun theControlListsTheFormsChoicesAndNamesAHeldOneItDoesNotOffer() {
        val entry = layout.fieldFor("venue")
        assertEquals(listOf(SchOption("park", "Outdoors"), SchOption("office", "At the office")), formChoiceList(venue, entry, null))
        assertEquals(
            listOf(SchOption("park", "Outdoors"), SchOption("office", "At the office"), SchOption("hotel", "A hotel (not offered on this form)")),
            formChoiceList(venue, entry, "hotel"),
        )
        // A value the schema does not know stays the validator's to report, and is not dressed as a choice.
        assertEquals(2, formChoiceList(venue, entry, "garden")!!.size)
        assertEquals(venue.options, formChoiceList(venue, null, "hotel"))
    }
}
