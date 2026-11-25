package com.dynamicruntime.webapp

import com.dynamicruntime.common.endpoint.EP
import com.dynamicruntime.common.gedra.GDF
import com.dynamicruntime.common.gedra.GE
import com.dynamicruntime.common.gedra.UNQ
import com.dynamicruntime.common.schema.SCH
import com.dynamicruntime.common.schema.SCT
import com.dynamicruntime.common.schema.SchFailCode
import com.dynamicruntime.common.schema.SchLayout
import com.dynamicruntime.common.schema.SchLayoutField
import com.dynamicruntime.common.schema.SchType
import com.dynamicruntime.common.schema.parseSchemaTypes
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull

/** How a failure's field is named where it is listed away from it, and what a refused save says (issue #1918). */
class FieldFailuresTest {
    private fun text(title: String? = null) = buildMap {
        put(SCH.type, SCT.string)
        title?.let { put(SCH.title, it) }
    }

    private fun listOfObjects(vararg props: Pair<String, Any?>, more: Map<String, Any?> = emptyMap()) = mapOf(
        SCH.type to SCT.array,
        SCH.items to mapOf(SCH.type to SCT.kObject, SCH.properties to mapOf(*props)) + more,
    )

    private val types = parseSchemaTypes(
        mapOf(
            "c.Place" to mapOf(SCH.type to SCT.kObject, SCH.title to "A place", SCH.properties to mapOf("city" to text())),
            "c.Talk" to mapOf(
                SCH.type to SCT.kObject,
                SCH.properties to mapOf(
                    "talkTitle" to text("Talk title"),
                    "venue" to mapOf(SCH.dRef to $$"#/$defs/c.Place"),
                    "speakers" to listOfObjects("email" to text("Email")),
                    "lines" to listOfObjects("amount" to text()),
                    "years" to listOfObjects(
                        "year" to text(), "amount" to text(),
                        more = mapOf(SCH.primaryKey to listOf("year"), SCH.required to listOf("year")),
                    ),
                    "tags" to mapOf(SCH.type to SCT.array, SCH.items to text()),
                    "extra" to mapOf(SCH.type to SCT.kObject, SCH.additionalProperties to text()),
                ),
            ),
        ),
    )
    private val talk: SchType = types.getValue("c.Talk")

    private fun opts(vararg fields: SchLayoutField) = FormOpts(
        friendly = true, entryLabel = $$"Entry ${n}",
        fieldLayouts = mapOf("c.Talk" to SchLayout(fragmentFileId = null, label = null, fields = fields.toList())),
    )

    private fun field(name: String, label: String?) = SchLayoutField(name, label = label, description = null, hint = null)

    private val values = mapOf(
        "talkTitle" to "x",
        "speakers" to listOf(mapOf("email" to "a"), mapOf("email" to "b")),
        "lines" to listOf(emptyMap<String, Any?>(), emptyMap()),
        "years" to listOf(mapOf("year" to "2024"), mapOf("year" to "2025")),
        "tags" to listOf("a", "b"),
    )

    @Test
    fun aFieldIsNamedAsTheFormLabelsIt() {
        assertEquals("Talk title", failureLabel(talk, values, "talkTitle", opts()))
        assertEquals("Title", failureLabel(talk, values, "talkTitle", opts(field("talkTitle", "Title"))))
        // A layout's label is filled in with the form's data, as its heading is.
        assertEquals("Title of x", failureLabel(talk, values, "talkTitle", opts(field("talkTitle", $$"Title of ${talkTitle}"))))
        // A `$ref` field has its own name, never its type's title, which every field of the type shares.
        assertEquals("Venue, City", failureLabel(talk, values, "venue.city", opts()))
        assertEquals("", failureLabel(talk, values, "", opts()))
    }

    @Test
    fun anElementIsNamedByTheFormsHeadingForIt() {
        val speakers = SchLayoutField("speakers", label = null, description = null, hint = null, entryLabel = $$"Co-speaker ${n}")
        assertEquals("Co-speaker 2, Email", failureLabel(talk, values, "speakers[1].email", opts(speakers)))
        assertEquals("2025, Amount", failureLabel(talk, values, "years[1].amount", opts()))
        // The form's default heading does not say what it is an entry of, so the list does.
        assertEquals("Lines, Entry 2, Amount", failureLabel(talk, values, "lines[1].amount", opts()))
        assertEquals("Tags, Entry 1", failureLabel(talk, values, "tags[0]", opts()))
        // A map's key is data; an element that is not there is still numbered.
        assertEquals("Extra, anyKey", failureLabel(talk, values, "extra.anyKey", opts()))
        assertEquals("Lines, Entry 8, Amount", failureLabel(talk, values, "lines[7].amount", opts()))
    }

    private fun refusal(vararg failures: Map<String, Any?>) =
        ApiError("Raw.", fromFragment = false, status = 400, errorCode = null, traceId = null, extraData = mapOf(EP.failures to failures.toList()))

    private fun failed(traitId: String, path: String, code: SchFailCode, message: String, value: String? = null) = mapOf(
        GE.traitId to traitId, EP.failurePath to path, EP.failureCode to code.name, EP.failureMessage to message, UNQ.value to value,
    )

    private val taken = failed("talk", "data.talkTitle", SchFailCode.valueTaken, "Value 'x' is already used.", "x")

    @Test
    fun aRefusedSaveNamesEachFieldAsThePageDoes() {
        val e = refusal(
            failed("talk", "data.talkTitle", SchFailCode.belowMinimum, "Value must be at least 3 characters."),
            failed("talk", "data", SchFailCode.wrongType, "Value must be an object."),
        )
        assertEquals(listOf("talkTitle", ""), refusedFailures(e).map { it.path })
        assertEquals(
            "Title: Value must be at least 3 characters. Value must be an object.",
            saveError(e) { failureLabel(talk, values, it.path, opts(field("talkTitle", "Title"))) }.text,
        )
        // A refusal that says no failures, or a request's, which have no trait, reads as it did.
        assertEquals("Raw.", saveError(refusal()) { "Never" }.text)
        assertEquals("Raw.", saveError(refusal(mapOf(EP.failurePath to "a", EP.failureMessage to "b"))) { "Never" }.text)
        // The schema author's words, when there are some.
        val worded = refusal(failed("talk", "data.talkTitle", SchFailCode.belowMinimum, "m") + (EP.failureUserMessage to "Say more."))
        assertEquals("Title: Say more.", saveError(worded) { "Title" }.text)
    }

    @Test
    fun aTakenValueRefusedGoesUnderItsField() {
        val titleType = talk.properties.getValue("talkTitle").valueType
        fun answers(e: ApiError, held: String?) = refusedAnswers(refusedFailures(e), "me", { _, field -> "p/$field" }) { path ->
            held?.let { UniqueAsk("talk", "talkTitle", titleType, it) }.takeIf { path == "p/talkTitle" }
        }
        val placed = mapOf(UniqueAsked("me", "p/talkTitle", "X!") to "Value 'x' is already used.")
        // The same value, by what it is compared as: the save trims, and the field may not.
        assertEquals(placed, answers(refusal(taken), "X!"))
        // One the field no longer holds, one not on the page, and one refused for anything more are the page's to say.
        assertNull(answers(refusal(taken), "other"))
        assertNull(answers(refusal(taken), null))
        assertNull(answers(refusal(taken, failed("talk", "data.venue", SchFailCode.wrongType, "m")), "X!"))
        assertNull(answers(refusal(), "X!"))
        val shown = takenFailures(placed, "me") { UniqueAsk("talk", "talkTitle", titleType, "X!") }.single()
        assertEquals(SchFailCode.valueTaken, shown.code)
        assertFalse(shown.message.contains("Title"))
    }

    @Test
    fun anEntrysFieldIsFoundAndNamedInAForm() {
        val form = mapOf(GDF.entries to listOf(mapOf(GE.traitId to "other"), mapOf(GE.traitId to "talk", GE.data to values)))
        assertEquals("${GDF.entries}[1].data.talkTitle", entryFieldPath(form, GDF.entries, "talk", "talkTitle"))
        assertNull(entryFieldPath(form, GDF.entries, "absent", "talkTitle"))
        // With no type to read it by, the trait and the path as they are.
        assertEquals("talk, talkTitle", entryFailureLabel(null, form, GDF.entries, refusedFailures(refusal(taken)).single(), opts()))
    }
}
