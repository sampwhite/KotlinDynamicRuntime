package com.dynamicruntime.common.schema

import com.dynamicruntime.common.context.KdrCxt
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import com.dynamicruntime.common.util.toJsonMap

/**
 * `g-derived` and the direction it depends on (issue #254).
 *
 * The whole point is that one declaration behaves differently by direction, so every case here is really the
 * same question asked twice: what a request may carry, and what a response does carry. A test that only ever
 * asked one of the two would pass for a model that had collapsed them.
 */
class SchDerivedTest : StringSpec({

    val cxt = KdrCxt.mkSimpleCxt("test")

    fun canType(): SchType = parseSchemaTypes(
        schemaDefs(cxt, "core") {
            type("Cans") {
                type = SCT.kObject
                property("gallonsPerCan", "Capacity of one can.", required = true) { type = SCT.number }
                property("canCount", "How many cans.", required = true) { type = SCT.integer }
                property("totalGallons", "Total capacity.", required = true) {
                    type = SCT.number
                    derived = true
                }
            }
        },
    ).getValue("core.Cans")

    "a derived property is not demanded of a request" {
        coerceAndValidate(canType(), mapOf("gallonsPerCan" to 2.5, "canCount" to 4), SchOpts(forInput = true))
            .failures.shouldBeEmpty()
    }

    // Read-modify-write is how a form works, so a client echoing back a value the server computed is doing
    // the ordinary thing. Dropped, not refused -- and dropped so the handler cannot mistake the caller's copy
    // for its own computation.
    "a derived property sent by a client is dropped rather than refused" {
        val result = coerceAndValidate(
            canType(),
            mapOf("gallonsPerCan" to 2.5, "canCount" to 4, "totalGallons" to 999.0),
            SchOpts(forInput = true),
        )
        result.failures.shouldBeEmpty()
        result.value!!.toJsonMap().keys.toList() shouldContainExactly listOf("gallonsPerCan", "canCount")
    }

    // The other direction, which is what makes this a projection rather than a deletion: on the way out the
    // field is an ordinary required value, and its absence is a real failure.
    "a derived property is an ordinary required value on a response" {
        coerceAndValidate(canType(), mapOf("gallonsPerCan" to 2.5, "canCount" to 4, "totalGallons" to 10.0))
            .failures.shouldBeEmpty()

        val missing = validate(canType(), mapOf("gallonsPerCan" to 2.5, "canCount" to 4))
        missing.map { it.path } shouldContainExactly listOf("totalGallons")
        missing.first().code shouldBe SchFailCode.missingRequired
    }

    // Both spellings are accepted from the start, so widening the keyword later is not a migration of stored
    // documents. Only the fact is read today; an object's content waits on a language to express it in.
    "the object form marks a property derived just as the boolean does" {
        val types = parseSchemaTypes(
            mapOf(
                "core.T" to mapOf(
                    SCH.type to SCT.kObject,
                    SCH.properties to mapOf(
                        "a" to mapOf(SCH.type to SCT.number, SCH.derived to true),
                        "b" to mapOf(SCH.type to SCT.number, SCH.derived to mapOf("expr" to "x * y")),
                        "c" to mapOf(SCH.type to SCT.number),
                    ),
                ),
            ),
        )
        val t = types.getValue("core.T")
        t.properties.getValue("a").valueType.derived shouldBe true
        t.properties.getValue("b").valueType.derived shouldBe true
        t.properties.getValue("c").valueType.derived shouldBe false
    }
})
