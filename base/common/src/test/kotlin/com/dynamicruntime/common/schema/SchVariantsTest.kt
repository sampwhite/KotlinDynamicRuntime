package com.dynamicruntime.common.schema

import com.dynamicruntime.common.context.KdrCxt
import com.dynamicruntime.common.exception.KdrException
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain

/**
 * Discriminated unions: `oneOf` with a declared `discriminator` (issue #252).
 *
 * Two things are being pinned down here, and they pull in different directions. One is that selection works —
 * the right branch is chosen, and its failures come back at the payload's own paths. The other is that
 * selection is only ever about *which failures are reported*: every branch carries its own `const`, so a
 * validator that ignores the keyword entirely reaches the same verdict. The second is what keeps the emitted
 * document standard-valid, and it is the one worth guarding, because nothing about a passing selection test
 * would notice if it stopped being true.
 */
class SchVariantsTest : StringSpec({

    val cxt = KdrCxt.mkSimpleCxt("test")

    // An expense report keyed by year, and an approval carrying a free-text note -- two branches whose fields
    // differ, so a failure can be attributed to the wrong one and be seen to have been.
    fun entryDefs(defaultBranch: String? = null): Map<String, Any?> = schemaDefs(cxt, "gedra") {
        variantBranch("ExpenseReportEntry", "traitId", "expenseReport", "An expense report.") {
            property("year", "Reporting year.", required = true) { type = SCT.integer; minimum = 2000 }
            property("totalAmount", "Total claimed.") { type = SCT.number }
        }
        variantBranch("ApprovalEntry", "traitId", "managerApproval", "An approval.") {
            property("approved", "Whether it was approved.", required = true) { type = SCT.boolean }
        }
        variantDefault("OpaqueEntry", "traitId", "An entry this reader does not know.")
        variantType(
            "GedraEntry", "One stored entry.", on = "traitId",
            branches = listOf("ExpenseReportEntry", "ApprovalEntry"),
            defaultBranch = defaultBranch,
        )
    }

    fun entryType(defaultBranch: String? = null): SchType =
        parseSchemaTypes(entryDefs(defaultBranch)).getValue("gedra.GedraEntry")

    "a payload validates against the branch its discriminator names" {
        val result = coerceAndValidate(
            entryType(),
            mapOf("traitId" to "expenseReport", "year" to 2024, "totalAmount" to 4820.15),
        )
        result.failures.shouldBeEmpty()
    }

    // The point of declaring a discriminator at all: the failure is the selected branch's own, at the path the
    // payload actually has. Try-every-branch would also have to report that `approved` is missing, from a
    // shape the caller never meant to send.
    "a failure is reported against the selected branch alone" {
        val failures = validate(entryType(), mapOf("traitId" to "expenseReport", "year" to 1999))
        failures shouldHaveSize 1
        failures.first().path shouldBe "year"
        failures.first().code shouldBe SchFailCode.belowMinimum
    }

    "each branch enforces its own required fields" {
        val failures = validate(entryType(), mapOf("traitId" to "managerApproval"))
        failures shouldHaveSize 1
        failures.first().path shouldBe "approved"
        failures.first().code shouldBe SchFailCode.missingRequired
    }

    // Reported against the discriminator's own path rather than the union's, so the message sits on the field
    // someone has to fill in.
    "a missing discriminator is reported on the discriminator" {
        val failures = validate(entryType(), mapOf("year" to 2024))
        failures shouldHaveSize 1
        failures.first().path shouldBe "traitId"
        failures.first().code shouldBe SchFailCode.missingRequired
    }

    "an unrecognized discriminator names the values this reader knows" {
        val failures = validate(entryType(), mapOf("traitId" to "somethingElse"))
        failures shouldHaveSize 1
        val failure = failures.first()
        failure.path shouldBe "traitId"
        failure.code shouldBe SchFailCode.invalidOption
        failure.options.shouldNotBeNull().map { it.value } shouldContainExactly
            listOf("expenseReport", "managerApproval")
    }

    // With a defaultMapping an unknown trait is an ordinary event rather than a defect: definitions are
    // authored at runtime, so a reader meeting one it has never heard of has to stay readable.
    "an unrecognized discriminator falls to the default branch when there is one" {
        validate(entryType(defaultBranch = "OpaqueEntry"), mapOf("traitId" to "somethingElse")).shouldBeEmpty()
    }

    // The trap `variantDefault` exists to remove: a catch-all carrying a `const` rejects the very payload it
    // was added to keep readable. Pinned because the failure reads as nonsense -- "'somethingElse' is not
    // 'opaque'" -- rather than as "your default branch is wrong".
    "a default branch declared with a const would reject the unknown value it exists for" {
        val defs = schemaDefs(cxt, "gedra") {
            variantBranch("Known", "traitId", "known")
            variantBranch("Fallback", "traitId", "fallback") // wrong helper on purpose
            variantType(
                "Entry", "One entry.", on = "traitId",
                branches = listOf("Known"), defaultBranch = "Fallback",
            )
        }
        val type = parseSchemaTypes(defs).getValue("gedra.Entry")
        validate(type, mapOf("traitId" to "unheardOf")) shouldHaveSize 1
        // Declared the right way, the same payload passes.
        val fixed = schemaDefs(cxt, "gedra") {
            variantBranch("Known", "traitId", "known")
            variantDefault("Fallback", "traitId")
            variantType(
                "Entry", "One entry.", on = "traitId",
                branches = listOf("Known"), defaultBranch = "Fallback",
            )
        }
        validate(parseSchemaTypes(fixed).getValue("gedra.Entry"), mapOf("traitId" to "unheardOf")).shouldBeEmpty()
    }

    "a union rejects a value that is not an object at all" {
        val failures = validate(entryType(), "expenseReport")
        failures shouldHaveSize 1
        failures.first().code shouldBe SchFailCode.wrongType
    }

    // The property the whole design rests on. Selection decides which failures are reported, never whether a
    // payload is valid -- so validating the branch DIRECTLY, with no discriminator involved, has to reach the
    // same answer. If this ever stops holding, the emitted document means something different to a stock
    // validator than it does to us, and nothing else here would notice.
    "a branch alone reaches the same verdict as the union, because it carries its own const" {
        val types = parseSchemaTypes(entryDefs())
        val branch = types.getValue("gedra.ExpenseReportEntry")
        val union = types.getValue("gedra.GedraEntry")
        val wrongBranch = mapOf("traitId" to "managerApproval", "approved" to true)

        // Through the union: selected as an approval and accepted.
        validate(union, wrongBranch).shouldBeEmpty()
        // Against the expense branch alone: rejected, by the const rather than by anything of ours.
        val direct = validate(branch, wrongBranch)
        direct.map { it.path } shouldContainExactly listOf("traitId", "approved", "year")
        direct.first().code shouldBe SchFailCode.invalidOption
    }

    // The shape an endpoint actually presents: input is flat, so a union arrives as one PROPERTY of an
    // envelope rather than as the whole payload. Reproduced here after the form reported the property missing
    // for a payload the server accepted.
    "a union reached through a property validates, and survives coercion" {
        val defs = entryDefs("OpaqueEntry").toMutableMap()
        defs["gedra.Envelope"] = mapOf(
            SCH.type to SCT.kObject,
            SCH.required to listOf("entry"),
            SCH.properties to mapOf("entry" to mapOf(SCH.dRef to $$"#/$defs/gedra.GedraEntry")),
        )
        val envelope = parseSchemaTypes(defs).getValue("gedra.Envelope")
        val payload = mapOf("entry" to mapOf("traitId" to "expenseReport", "year" to 2024))

        validate(envelope, payload).shouldBeEmpty()
        val coerced = coerceAndValidate(envelope, payload)
        coerced.failures.shouldBeEmpty()
        // And the property is still there afterward -- a union coerced into nothing reads as "you did not
        // supply it", which is what the form reported.
        (coerced.value as Map<*, *>).keys shouldContainExactly listOf("entry")
    }

    "a union is usable as an array element type" {
        val defs = entryDefs().toMutableMap()
        defs["gedra.Gedra"] = mapOf(
            SCH.type to SCT.kObject,
            SCH.properties to mapOf(
                "entries" to mapOf(SCH.type to SCT.array, SCH.items to mapOf(SCH.dRef to $$"#/$defs/gedra.GedraEntry")),
            ),
        )
        val gedra = parseSchemaTypes(defs).getValue("gedra.Gedra")
        val failures = validate(
            gedra,
            mapOf(
                "entries" to listOf(
                    mapOf("traitId" to "expenseReport", "year" to 2024),
                    mapOf("traitId" to "managerApproval", "approved" to true),
                    mapOf("traitId" to "expenseReport", "year" to 1999),
                ),
            ),
        )
        // Only the third element is wrong, and the path says which one -- mixed traits in one array validate
        // element-wise against different branches.
        failures shouldHaveSize 1
        failures.first().path shouldBe "entries[2].year"
    }

    // --- boot checks ---------------------------------------------------------

    "a branch with no const for the discriminator is refused, and named" {
        val defs = schemaDefs(cxt, "gedra") {
            variantBranch("Good", "traitId", "good")
            type("Bad") {
                type = SCT.kObject
                property("traitId", "Which kind.", required = true) { type = SCT.string } // no const
            }
            variantType("Entry", "One entry.", on = "traitId", branches = listOf("Good", "Bad"))
        }
        val e = shouldThrow<KdrException> { parseSchemaTypes(defs) }
        e.message.shouldNotBeNull() shouldContain "Branch 2"
        e.message.shouldNotBeNull() shouldContain "gedra.Entry"
    }

    "a branch that does not declare the discriminator at all is refused" {
        val defs = schemaDefs(cxt, "gedra") {
            variantBranch("Good", "traitId", "good")
            type("Bad") { type = SCT.kObject; property("other", "Something else.") }
            variantType("Entry", "One entry.", on = "traitId", branches = listOf("Good", "Bad"))
        }
        shouldThrow<KdrException> { parseSchemaTypes(defs) }.message
            .shouldNotBeNull() shouldContain "declares no 'traitId' property"
    }

    "two branches claiming the same value are refused" {
        val defs = schemaDefs(cxt, "gedra") {
            variantBranch("One", "traitId", "same")
            variantBranch("Two", "traitId", "same")
            variantType("Entry", "One entry.", on = "traitId", branches = listOf("One", "Two"))
        }
        shouldThrow<KdrException> { parseSchemaTypes(defs) }.message
            .shouldNotBeNull() shouldContain "repeats"
    }

    // Deliberate new strictness: before this issue such a document parsed and enforced nothing at all.
    "a oneOf without a discriminator is refused rather than silently ignored" {
        val defs = mapOf(
            "gedra.Entry" to mapOf(
                SCH.oneOf to listOf(mapOf(SCH.type to SCT.kObject)),
            ),
        )
        shouldThrow<KdrException> { parseSchemaTypes(defs) }.message
            .shouldNotBeNull() shouldContain "has no 'discriminator'"
    }

    "branch order follows the document, so a boot-check message points at the right one" {
        val types = parseSchemaTypes(entryDefs())
        val variants = types.getValue("gedra.GedraEntry").variants.shouldNotBeNull()
        variants.values shouldContainExactly listOf("expenseReport", "managerApproval")
    }
})
