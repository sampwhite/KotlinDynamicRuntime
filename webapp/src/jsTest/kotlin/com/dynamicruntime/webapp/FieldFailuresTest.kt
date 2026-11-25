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
import com.dynamicruntime.common.schema.SchLayoutMode
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
                    "code" to text("Code") + (SCH.errors to mapOf("belowMinimum" to $$"${label} is short.")),
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
        "code" to "c1",
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
            saveError(e) { words(it, opts(field("talkTitle", "Title"))) }.text,
        )
        // A refusal that says no failures, or a request's, which have no trait, reads as it did.
        assertEquals("Raw.", saveError(refusal()) { "Never" }.text)
        assertEquals("Raw.", saveError(refusal(mapOf(EP.failurePath to "a", EP.failureMessage to "b"))) { "Never" }.text)
    }

    private fun words(f: RefusedFailure, opts: FormOpts) =
        labeledMessage(failureLabel(talk, values, f.path, opts), failureWords(talk, values, f, opts))

    private fun one(failure: Map<String, Any?>) = refusedFailures(refusal(failure)).single()

    @Test
    fun aListedFailureIsWordedAsItIsUnderItsField() {
        val short = failed("talk", "data.code", SchFailCode.belowMinimum, "Platform's.") + (EP.failureUserMessage to "The code is short.")
        // The schema's message with this form's label, not the one the server filled in with its own.
        assertEquals("Ticket: Ticket is short.", words(one(short), opts(field("code", "Ticket"))))
        // The layout's own message first.
        val worded = SchLayoutField(
            "code", label = "Ticket", description = null, hint = null,
            errors = mapOf("belowMinimum" to $$"No ${lower(label)} like '${value}'."),
        )
        assertEquals("Ticket: No ticket like 'c1'.", words(one(short), opts(worded)))
        // A layout message needing a value this form does not have gives way to the next that can be filled in.
        assertEquals("Ticket is short.", failureWords(talk, emptyMap(), one(short), opts(worded)))
        // A refused choice says the choices, as it does under a field.
        val choice = failed("talk", "data.talkTitle", SchFailCode.invalidOption, "'x' is not one of the choices.") +
            (EP.failureOptions to listOf(mapOf(SCH.value to "a", SCH.label to "Apples")))
        assertEquals("'x' is not one of the choices. Valid options: a — Apples.", failureWords(talk, values, one(choice), opts()))
        // A code from a newer server reads as it came.
        assertEquals("Newer.", failureWords(talk, values, RefusedFailure("talk", "talkTitle", "later", "Newer."), opts()))
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

    private val entryTypes = parseSchemaTypes(
        mapOf(
            "c.Yearly" to mapOf(
                SCH.type to SCT.kObject, SCH.primaryKey to listOf("year"), SCH.required to listOf("year"),
                SCH.properties to mapOf(
                    "year" to text(), "amount" to mapOf(SCH.type to SCT.integer), "lines" to listOfObjects("cost" to text()),
                ),
            ),
            "c.TalkEntry" to entry("talk", "c.Talk"),
            "c.YearlyEntry" to entry("yearly", "c.Yearly"),
            "c.Form" to mapOf(
                SCH.type to SCT.kObject,
                SCH.properties to mapOf(
                    GDF.entries to mapOf(
                        SCH.type to SCT.array,
                        SCH.items to mapOf(
                            SCH.oneOf to listOf(mapOf(SCH.dRef to $$"#/$defs/c.TalkEntry"), mapOf(SCH.dRef to $$"#/$defs/c.YearlyEntry")),
                            SCH.discriminator to mapOf(SCH.propertyName to GE.traitId),
                        ),
                    ),
                ),
            ),
        ),
        types,
    )

    private fun entry(traitId: String, type: String) = mapOf(
        SCH.type to SCT.kObject, SCH.required to listOf(GE.traitId),
        SCH.properties to mapOf(GE.traitId to mapOf(SCH.const to traitId), GE.data to mapOf(SCH.dRef to $$"#/$defs/$$type")),
    )

    private val formType = entryTypes.getValue("c.Form")
    private val form = mapOf(
        GDF.entries to listOf(
            mapOf(GE.traitId to "yearly", GE.data to mapOf("year" to "2023", "lines" to listOf(mapOf("cost" to "1")))),
            mapOf(
                GE.traitId to "yearly",
                GE.data to mapOf("year" to "2024", "lines" to listOf(mapOf("cost" to "1"), mapOf("cost" to "2"))),
            ),
            mapOf(GE.traitId to "talk", GE.data to values),
        ),
    )

    @Test
    fun anEntryIsNamedAsTheFormHeadsIt() {
        fun told(f: Map<String, Any?>, opts: FormOpts = opts()) = entryFailureText(formType, form, GDF.entries, one(f), opts)
        assertEquals(
            "Talk, Title: Value must be at least 3 characters.",
            told(
                failed("talk", "data.talkTitle", SchFailCode.belowMinimum, "Value must be at least 3 characters."),
                opts(field("talkTitle", "Title")),
            ),
        )
        // One of several entries of a keyed trait, found by its key, so its own lists are read.
        val keyed = failed("yearly", "data.lines[1].cost", SchFailCode.wrongType, "Value must be text.") +
            (EP.failureEntryKey to mapOf("year" to "2024"))
        assertEquals("Yearly — 2024, Lines, Entry 2, Cost: Value must be text.", told(keyed))
        // One that is not on the page is still told apart.
        val elsewhere = failed("yearly", "data.amount", SchFailCode.wrongType, "No.") + (EP.failureEntryKey to mapOf("year" to "2020"))
        assertEquals("Yearly — 2020, Amount: No.", told(elsewhere))
        // With no type to read it by, the trait and the path as they are.
        assertEquals("talk, talkTitle: Value 'x' is already used.", entryFailureText(null, form, GDF.entries, one(taken), opts()))
    }

    @Test
    fun aFieldIsPointedAtOnlyWhereTheFormDrawsIt() {
        fun path(field: String, opts: FormOpts = opts()) = drawnEntryField(formType, form, GDF.entries, "talk", field, opts)
        assertEquals("${GDF.entries}[2].data.talkTitle", path("talkTitle"))
        assertNull(path("gone"))
        assertNull(drawnEntryField(formType, form, GDF.entries, "absent", "talkTitle", opts()))
        // A layout that chooses the fields leaves this one out, and so there is no field to mark.
        val layout = SchLayout(
            fragmentFileId = null, label = null, fields = listOf(field("venue", null)), mode = SchLayoutMode.authoritative,
        )
        assertNull(path("talkTitle", FormOpts(friendly = true, fieldLayouts = mapOf("c.Talk" to layout))))
    }
}
