package com.dynamicruntime.common.schema

import com.dynamicruntime.common.context.KdrCxt
import com.dynamicruntime.common.exception.KdrException
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain

/**
 * Conditional presence: `if` / `then` / `else` in the one shape this layer reads (issue #253).
 *
 * The four-way table is the whole behavior — the watched field present or absent, matching or not — and the
 * absent case is the one that has to be tested rather than assumed. A bare `properties` check passes
 * *vacuously* on an absent property, so the natural way to write this construct demands the dependent field
 * from a payload that said nothing at all. That failure is invisible until someone submits an empty form.
 */
class SchConditionTest : StringSpec({

    val cxt = KdrCxt.mkSimpleCxt("test")

    // `value` is required when `hasValue` is true, and inadmissible otherwise.
    fun choiceType(): SchType = parseSchemaTypes(
        schemaDefs(cxt, "core") {
            type("OptionalChoice") {
                type = SCT.kObject
                property("hasValue", "Whether a choice was made.", required = true) { type = SCT.boolean }
                property("value", "The chosen option.")
                presentWhen("value", on = "hasValue", value = true)
            }
        },
    ).getValue("core.OptionalChoice")

    "the dependent field is required when the condition holds" {
        validate(choiceType(), mapOf("hasValue" to true, "value" to "approve")).shouldBeEmpty()

        val failures = validate(choiceType(), mapOf("hasValue" to true))
        failures shouldHaveSize 1
        failures.first().path shouldBe "value"
        failures.first().code shouldBe SchFailCode.missingRequired
    }

    "the dependent field is refused when the condition does not hold" {
        validate(choiceType(), mapOf("hasValue" to false)).shouldBeEmpty()

        val failures = validate(choiceType(), mapOf("hasValue" to false, "value" to "approve"))
        failures shouldHaveSize 1
        failures.first().path shouldBe "value"
        failures.first().code shouldBe SchFailCode.notAllowed
    }

    // The trap `presentWhen` exists to close. An `if` written without its own `required` passes vacuously on
    // an absent property, so `then` fires and the dependent field is demanded from a payload that said
    // nothing. Here the only complaint is about the field actually missing.
    "an absent watched field does not demand the dependent one" {
        val failures = validate(choiceType(), emptyMap<String, Any?>())
        failures.map { it.path } shouldBe listOf("hasValue")
        failures.first().code shouldBe SchFailCode.missingRequired
    }

    // Wording matters on both sides and they are opposites: saying "not allowed when hasValue is true" to
    // somebody whose box is *unticked* describes a state they are not in.
    "the refusal names the field that decided, and says which way" {
        val whenFalse = validate(choiceType(), mapOf("hasValue" to false, "value" to "x")).first()
        whenFalse.message shouldContain "only allowed when 'hasValue' is 'true'"
    }

    // A cleared field is absent, exactly as it is for `required` -- the two notions of "not supplied" have to
    // be one notion, or emptying a box in a form satisfies the rule while leaving a forbidden key on the wire.
    "a field cleared to empty counts as absent on both sides of the rule" {
        // Empty string: `emptyIsAbsent` defaults true for a string, so this is "not supplied".
        validate(choiceType(), mapOf("hasValue" to false, "value" to "")).shouldBeEmpty()
        val failures = validate(choiceType(), mapOf("hasValue" to true, "value" to ""))
        failures shouldHaveSize 1
        failures.first().code shouldBe SchFailCode.missingRequired
    }

    // A boolean does not coerce unless the schema opts in, so this declares it -- which is the realistic case
    // for a flag that can arrive from a query string. What is being pinned is that the rule reads such a value
    // as the answer it plainly is, rather than failing to match and quietly taking the other branch.
    "a watched value that arrived as text still answers the question" {
        val coercible = parseSchemaTypes(
            schemaDefs(cxt, "core") {
                type("Choice") {
                    type = SCT.kObject
                    property("hasValue", "Whether a choice was made.", required = true) {
                        type = SCT.boolean
                        allowCoerce = true
                    }
                    property("value", "The chosen option.")
                    presentWhen("value", on = "hasValue", value = true)
                }
            },
        ).getValue("core.Choice")

        coerceAndValidate(coercible, mapOf("hasValue" to "true", "value" to "approve")).failures.shouldBeEmpty()
        // And the other way: text that says false must not satisfy a condition looking for true.
        val failures = coerceAndValidate(coercible, mapOf("hasValue" to "false", "value" to "approve")).failures
        failures shouldHaveSize 1
        failures.first().code shouldBe SchFailCode.notAllowed
    }

    "a condition works inside a union branch" {
        val types = parseSchemaTypes(
            schemaDefs(cxt, "gedra") {
                variantBranch("ApprovalEntry", "traitId", "managerApproval") {
                    property("approved", "Whether it was approved.", required = true) { type = SCT.boolean }
                    property("rejectionReason", "Why it was rejected.")
                    presentWhen("rejectionReason", on = "approved", value = false)
                }
                variantType("Entry", "One entry.", on = "traitId", branches = listOf("ApprovalEntry"))
            },
        )
        val entry = types.getValue("gedra.Entry")
        validate(entry, mapOf("traitId" to "managerApproval", "approved" to false, "rejectionReason" to "late"))
            .shouldBeEmpty()
        val failures = validate(entry, mapOf("traitId" to "managerApproval", "approved" to false))
        failures shouldHaveSize 1
        failures.first().path shouldBe "rejectionReason"
    }

    // --- what is refused at parse time ---------------------------------------

    fun conditionalDefs(node: Map<String, Any?>): Map<String, Any?> = mapOf("core.T" to node)

    "a general if is refused rather than half-applied" {
        val defs = conditionalDefs(
            mapOf(
                SCH.type to SCT.kObject,
                // Two properties tested at once: legal JSON Schema, outside the shape this layer reads.
                SCH.kIf to mapOf(
                    SCH.properties to mapOf(
                        "a" to mapOf(SCH.const to 1),
                        "b" to mapOf(SCH.const to 2),
                    ),
                ),
                SCH.kThen to mapOf(SCH.required to listOf("c")),
            ),
        )
        shouldThrow<KdrException> { parseSchemaTypes(defs) }.message
            .shouldNotBeNull() shouldContain "exactly one property"
    }

    "an if testing something other than a const is refused" {
        val defs = conditionalDefs(
            mapOf(
                SCH.type to SCT.kObject,
                SCH.kIf to mapOf(SCH.properties to mapOf("a" to mapOf(SCH.minimum to 3))),
                SCH.kThen to mapOf(SCH.required to listOf("c")),
            ),
        )
        shouldThrow<KdrException> { parseSchemaTypes(defs) }.message
            .shouldNotBeNull() shouldContain "other than a 'const'"
    }

    "a then clause carrying more than required or not-required is refused" {
        val defs = conditionalDefs(
            mapOf(
                SCH.type to SCT.kObject,
                SCH.kIf to mapOf(SCH.properties to mapOf("a" to mapOf(SCH.const to 1))),
                SCH.kThen to mapOf(SCH.properties to mapOf("c" to mapOf(SCH.type to SCT.string))),
            ),
        )
        shouldThrow<KdrException> { parseSchemaTypes(defs) }.message
            .shouldNotBeNull() shouldContain "only 'required'"
    }

    "an if with no then or else is refused, since it constrains nothing" {
        val defs = conditionalDefs(
            mapOf(SCH.type to SCT.kObject, SCH.kIf to mapOf(SCH.properties to mapOf("a" to mapOf(SCH.const to 1)))),
        )
        shouldThrow<KdrException> { parseSchemaTypes(defs) }.message
            .shouldNotBeNull() shouldContain "constrains nothing"
    }

    "a then without an if is refused" {
        val defs = conditionalDefs(mapOf(SCH.type to SCT.kObject, SCH.kThen to mapOf(SCH.required to listOf("c"))))
        shouldThrow<KdrException> { parseSchemaTypes(defs) }.message
            .shouldNotBeNull() shouldContain "decides nothing"
    }
})
