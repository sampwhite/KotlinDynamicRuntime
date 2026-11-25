package com.dynamicruntime.common.gedra

import com.dynamicruntime.common.endpoint.EP
import com.dynamicruntime.common.schema.SCH
import com.dynamicruntime.common.schema.SCT
import com.dynamicruntime.common.schema.SchFailCode
import com.dynamicruntime.common.schema.SchFailure
import com.dynamicruntime.common.schema.parseSchemaTypes
import com.dynamicruntime.common.util.toJsonListOfMaps
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe

/** What a refused write says of the entries it would store, and which field an impact finding is about (issue #1918). */
class EntryFailuresTest : StringSpec({
    val text = mapOf(SCH.type to SCT.string)
    val types = parseSchemaTypes(
        mapOf(
            "c.Yearly" to mapOf(
                SCH.type to SCT.kObject, SCH.primaryKey to listOf("year"), SCH.required to listOf("year"),
                SCH.properties to mapOf(
                    "year" to text,
                    "lines" to mapOf(
                        SCH.type to SCT.array,
                        SCH.items to mapOf(SCH.type to SCT.kObject, SCH.properties to mapOf("amount" to text)),
                    ),
                    "byPerson" to mapOf(SCH.type to SCT.kObject, SCH.additionalProperties to text),
                    "venue" to mapOf(SCH.type to SCT.kObject, SCH.properties to mapOf("city" to text)),
                ),
            ),
            "c.YearlyEntry" to mapOf(
                SCH.type to SCT.kObject, SCH.required to listOf(GE.traitId),
                SCH.properties to mapOf(GE.traitId to mapOf(SCH.const to "yearly"), GE.data to mapOf(SCH.dRef to $$"#/$defs/c.Yearly")),
            ),
            "c.Union" to mapOf(
                SCH.oneOf to listOf(mapOf(SCH.dRef to $$"#/$defs/c.YearlyEntry")),
                SCH.discriminator to mapOf(SCH.propertyName to GE.traitId),
            ),
        ),
    )
    val union = types.getValue("c.Union")

    "an entry of a keyed trait is told apart by its key, as data and in the words" {
        val entry = mapOf(GE.traitId to "yearly", GE.data to mapOf("year" to "2024"))
        val failure = EntryFailure.of(union, entry, SchFailure("data.venue", SchFailCode.wrongType, "Value must be an object."))
        failure.text shouldBe "yearly (2024), data.venue: Value must be an object."
        val e = entryFailuresException("Refused:", listOf(failure))
        e.message shouldBe "Refused: yearly (2024), data.venue: Value must be an object."
        e.extraData[EP.failures].toJsonListOfMaps().single() shouldBe mapOf(
            EP.failurePath to "data.venue", EP.failureCode to "wrongType", EP.failureMessage to "Value must be an object.",
            GE.traitId to "yearly", EP.failureEntryKey to mapOf("year" to "2024"),
        )
    }

    "an impact finding's field is the type's, never a row's place or a stored key" {
        val entryType = types.getValue("c.YearlyEntry")
        fun field(path: String) = ConfigImpact.fieldAnywhere(entryType, path)
        field("data.year") shouldBe "year"
        field("data.lines[3].amount") shouldBe "lines[].amount"
        field("data.lines[3]") shouldBe "lines[]"
        // A map's keys are people's data, and one a type does not declare is reported under what holds it.
        field("data.byPerson.ada") shouldBe "byPerson.*"
        field("data.venue.unknown") shouldBe "venue"
        field("data.unknown") shouldBe ""
        field("data") shouldBe ""
    }
})
