package com.dynamicruntime.common.schema

import com.dynamicruntime.common.context.KdrCxt
import com.dynamicruntime.common.exception.KdrException
import com.dynamicruntime.common.startup.DefRepairContext
import com.dynamicruntime.common.startup.repairTypeDef
import com.dynamicruntime.common.util.toJsonMap
import com.dynamicruntime.common.util.toJsonMapOrEmpty
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.assertions.withClue
import io.kotest.core.spec.style.StringSpec
import io.kotest.core.spec.style.stringSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.types.shouldBeSameInstanceAs
import io.kotest.matchers.types.shouldNotBeSameInstanceAs

/**
 * The schema dialect's keywords, one part per keyword or family of them (issue #1738 gathered these): conditions
 * (`if`/`then`/`else`), derived fields, outer whitespace, invisible characters, open and sourced option lists, and how
 * strictly the standard and `g-` keywords are held to their shapes. Each part below is one of the specs that used to be
 * a file of its own, kept whole. `SchValidatorTest` stays the validator's own suite, and `SchOptionsParseTest` runs in
 * the kernel on both targets.
 */
class SchKeywordsTest : StringSpec({
    include(schConditionTests())
    include(schDerivedTests())
    include(schOuterWhitespaceTests())
    include(schVisibleOnlyTests())
    include(schOpenOptionsTests())
    include(schOptionsSourceTests())
    include(schGKeywordsTests())
    include(schStdKeywordsTests())
})

/**
 * Conditional presence: `if` / `then` / `else` in the one shape this layer reads (issue #253).
 *
 * The four-way table is the whole behavior — the watched field present or absent, matching or not — and the
 * absent case is the one that has to be tested rather than assumed. A bare `properties` check passes
 * *vacuously* on an absent property, so the natural way to write this construct demands the dependent field
 * from a payload that said nothing at all. That failure is invisible until someone submits an empty form.
 */
private fun schConditionTests() = stringSpec {

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
}

/**
 * `g-derived` and the direction it depends on (issue #254).
 *
 * The whole point is that one declaration behaves differently by direction, so every case here is really the
 * same question asked twice: what a request may carry, and what a response does carry. A test that only ever
 * asked one of the two would pass for a model that had collapsed them.
 */
private fun schDerivedTests() = stringSpec {

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
}

/**
 * `g-outerWhitespace` (issues #541, #765): leading/trailing whitespace on a string is stripped (`"trim"`),
 * refused (`"reject"`), or left alone (`"keep"`).
 *
 * The declared modes are the same rule read different ways, so the cases come in pairs: what `"trim"` cleans,
 * `"reject"` refuses. The load-bearing details are the *ordering* (the cleaned value is what `minLength`/options
 * measure) and the mode split (`"trim"` emits the cleaned value in coerce mode and checks against it in
 * validate-only). "Whitespace" here is the kernel's `<= ' '` test, which -- deliberately -- is not Kotlin's
 * Unicode `trim`.
 *
 * The **input default** (issue #765) is the other axis these cases cover: a plain string with no declared mode
 * trims on the `forInput` path (so bounds/options/the handler measure the trimmed value) and is left untouched
 * everywhere else; `"keep"` is the opt-out from that default.
 */
private fun schOuterWhitespaceTests() = stringSpec {

    val cxt = KdrCxt.mkSimpleCxt("test")

    /** A `name` field carrying [mode], beside an unruled `note`, so the default is asserted next to it. */
    fun recType(mode: String, required: Boolean = true, build: SchTypeBuilder.() -> Unit = {}): SchType =
        parseSchemaTypes(
            schemaDefs(cxt, "w") {
                type("Rec") {
                    type = SCT.kObject
                    property("name", "A name", required = required) { outerWhitespace = mode; build() }
                    property("note", "A note, unruled")
                }
            },
        ).getValue("w.Rec")

    fun trimType() = recType(SOWS.trim)
    fun rejectType() = recType(SOWS.reject)

    fun coercedName(type: SchType, name: String): Any? =
        (coerceAndValidate(type, mapOf("name" to name)).value as Map<*, *>)["name"]

    /** The coerced `name` on the **input** path, where the #765 trim default applies. */
    fun inputCoercedName(type: SchType, name: String): Any? =
        (coerceAndValidate(type, mapOf("name" to name), SchOpts(forInput = true)).value as Map<*, *>)["name"]

    /** A plain `name` field with no declared whitespace mode, so the default is what is under test. */
    fun plainType(build: SchTypeBuilder.() -> Unit = {}): SchType =
        parseSchemaTypes(
            schemaDefs(cxt, "w") {
                type("Rec") {
                    type = SCT.kObject
                    property("name", "A name", required = true) { build() }
                    property("note", "A note")
                }
            },
        ).getValue("w.Rec")

    // --- trim: strips in coerce mode, passes clean in validate-only ------------------------------------------

    "trim strips leading and trailing whitespace in coerce mode" {
        coercedName(trimType(), "  hi  ") shouldBe "hi"
        coercedName(trimType(), "\t\n hi \r\n") shouldBe "hi"
        // Interior whitespace is untouched -- only the edges.
        coercedName(trimType(), "  a b  ") shouldBe "a b"
    }

    "trim passes in validate-only, since the stored value will be clean" {
        validate(trimType(), mapOf("name" to "  hi  ")).shouldBeEmpty()
    }

    "the builder helper trimmed() sets trim mode" {
        val t = recType(SOWS.trim) // via attribute
        val h = parseSchemaTypes(
            schemaDefs(cxt, "w") {
                type("Rec") {
                    type = SCT.kObject
                    property("name", "A name", required = true) { trimmed() }
                    property("note", "A note, unruled")
                }
            },
        ).getValue("w.Rec")
        coercedName(t, " x ") shouldBe "x"
        coercedName(h, " x ") shouldBe "x"
    }

    // --- reject: fails a value with edge whitespace, alters nothing -----------------------------------------

    "reject fails a value with leading or trailing whitespace, in both modes" {
        for (bad in listOf(" hi", "hi ", "  hi  ", "\thi", "hi\n")) {
            withClue(bad) {
                validate(rejectType(), mapOf("name" to bad)).map { it.code } shouldContainExactly
                    listOf(SchFailCode.badValue)
                coerceAndValidate(rejectType(), mapOf("name" to bad)).failures.map { it.code } shouldContainExactly
                    listOf(SchFailCode.badValue)
            }
        }
    }

    "reject passes a clean value and leaves it exactly as it arrived" {
        validate(rejectType(), mapOf("name" to "hi there")).shouldBeEmpty()
        coercedName(rejectType(), "hi there") shouldBe "hi there"
    }

    "the builder helper noOuterWhitespace() sets reject mode" {
        val h = parseSchemaTypes(
            schemaDefs(cxt, "w") {
                type("Rec") {
                    type = SCT.kObject
                    property("name", "A code", required = true) { noOuterWhitespace() }
                }
            },
        ).getValue("w.Rec")
        validate(h, mapOf("name" to " x")).map { it.code } shouldContainExactly listOf(SchFailCode.badValue)
    }

    // --- the default: no keyword trims on input, keeps everywhere else (issue #765) -------------------------

    "with no keyword, the output/stored path keeps whitespace" {
        // forInput defaults to false, so validate/coerceAndValidate here exercise the output/stored path.
        validate(plainType(), mapOf("name" to "  hi  ")).shouldBeEmpty()
        coercedName(plainType(), "  hi  ") shouldBe "  hi  "
    }

    "with no keyword, the input path trims by default" {
        inputCoercedName(plainType(), "  hi  ") shouldBe "hi"
        inputCoercedName(plainType(), "\t hi \n") shouldBe "hi"
        // Interior whitespace is untouched -- only the edges, like an explicit trim.
        inputCoercedName(plainType(), "  a b  ") shouldBe "a b"
    }

    "the input trim default runs before minLength, closing the advertise-vs-enforce gap" {
        // The bug #765 fixes: " a " is length 3 and would pass minLength:3 untouched, then the handler stores
        // "a" (length 1). On the input path the bound now measures the trimmed value and fails.
        val t = plainType { minLength = 3 }
        coerceAndValidate(t, mapOf("name" to " a "), SchOpts(forInput = true)).failures.map { it.code } shouldContainExactly
            listOf(SchFailCode.belowMinimum)
        // A genuinely long-enough value passes after trimming, and arrives trimmed.
        inputCoercedName(t, "  abcd  ") shouldBe "abcd"
    }

    "keep opts a field out of the input trim default" {
        val kept = plainType { preserveWhitespace() }
        inputCoercedName(kept, "  hi  ") shouldBe "  hi  "
        validate(kept, mapOf("name" to "  hi  "), SchOpts(forInput = true)).shouldBeEmpty()
        // keep is resolved onto the type, and reads back on both paths (it is a no-op on output too).
        kept.properties.getValue("name").valueType.outerWhitespace shouldBe SchOuterWhitespace.keep
        coercedName(kept, "  hi  ") shouldBe "  hi  "
    }

    "an explicit trim strips on the output/stored path too, unlike the input-only default" {
        // The reason trimmed() still earns its keep: it forces the strip everywhere, not only on input.
        coercedName(trimType(), "  hi  ") shouldBe "hi"
    }

    "the input default only touches plain strings -- a non-string field is unaffected" {
        val withCount = parseSchemaTypes(
            schemaDefs(cxt, "w") {
                type("Rec") {
                    type = SCT.kObject
                    property("count", "A count", required = true) { type = SCT.integer }
                }
            },
        ).getValue("w.Rec")
        // "  5  " coerces to 5 on input; the whitespace default has no string to act on and never fires.
        (coerceAndValidate(withCount, mapOf("count" to "  5  "), SchOpts(forInput = true)).value as Map<*, *>)["count"] shouldBe 5L
    }

    "keep parses from its wire value and reads back the enum" {
        val raw = parseSchemaTypes(mapOf("w.Raw" to mapOf(SCH.type to SCT.string, SCH.outerWhitespace to SOWS.keep)))
        raw.getValue("w.Raw").outerWhitespace shouldBe SchOuterWhitespace.keep
    }

    // --- ordering: the cleaned value is what the bounds and options measure ----------------------------------

    "trim runs before minLength, so a value that is all-but-whitespace too short fails" {
        // " a " is length 3 and would pass minLength:3 untouched; trimmed it is "a", length 1, and must fail.
        val t = recType(SOWS.trim) { minLength = 3 }
        validate(t, mapOf("name" to " a ")).map { it.code } shouldContainExactly listOf(SchFailCode.belowMinimum)
        // A genuinely long-enough value still passes after trimming.
        validate(t, mapOf("name" to "  abcd  ")).shouldBeEmpty()
    }

    "trim runs before a closed choice list, so a padded value still matches its option" {
        val chooser = parseSchemaTypes(
            schemaDefs(cxt, "w") {
                type("Pick") {
                    type = SCT.kObject
                    property("name", "A choice") {
                        outerWhitespace = SOWS.trim
                        option("red", "Red")
                        option("green", "Green")
                    }
                }
            },
        ).getValue("w.Pick")
        validate(chooser, mapOf("name" to "  green  ")).shouldBeEmpty()
        coercedName(chooser, "  green  ") shouldBe "green"
    }

    "reject runs before a closed choice list, so a padded copy of an option is still refused" {
        val chooser = parseSchemaTypes(
            schemaDefs(cxt, "w") {
                type("Pick") {
                    type = SCT.kObject
                    property("name", "A choice") {
                        outerWhitespace = SOWS.reject
                        option("red", "Red")
                    }
                }
            },
        ).getValue("w.Pick")
        // Both the edge whitespace and the now-unlisted value are reported.
        validate(chooser, mapOf("name" to " red ")).map { it.code } shouldContainExactly
            listOf(SchFailCode.badValue, SchFailCode.invalidOption)
    }

    // --- interplay with emptyIsAbsent -----------------------------------------------------------------------

    "a whitespace-only value reads as absent under emptyIsAbsent, before either mode alters it" {
        // isBlank() drops it in the container ahead of the property check, so required -> missing, not badValue,
        // under both modes; the trimmed form would be "" anyway.
        validate(trimType(), mapOf("name" to "   ")).map { it.code } shouldContainExactly
            listOf(SchFailCode.missingRequired)
        validate(rejectType(), mapOf("name" to "   ")).map { it.code } shouldContainExactly
            listOf(SchFailCode.missingRequired)
        // Optional: dropped from the coerced output entirely, no failure.
        val optional = coerceAndValidate(recType(SOWS.trim, required = false), mapOf("name" to "  ", "note" to "n"))
        optional.failures.shouldBeEmpty()
        (optional.value as Map<*, *>).keys shouldBe setOf("note")
    }

    // --- the whitespace definition is <= ' ', not Unicode -------------------------------------------------

    "a no-break space is not outer whitespace here: the rule is <= ' ', matching the rest of the kernel" {
        // U+00A0 is above ' ', so neither mode touches it -- that character is g-visibleOnly's job, not this.
        coercedName(trimType(), " hi ") shouldBe " hi "
        validate(rejectType(), mapOf("name" to " hi ")).shouldBeEmpty()
    }

    // --- refused at parse time where it would constrain nothing ---------------------------------------------

    "g-outerWhitespace declared on a non-string type fails the parse by name" {
        shouldThrow<KdrException> {
            parseSchemaTypes(
                schemaDefs(cxt, "w") {
                    type("Bad") {
                        type = SCT.kObject
                        property("n", "A count") { type = SCT.integer; outerWhitespace = SOWS.trim }
                    }
                },
            )
        }.message.orEmpty() shouldContain SCH.outerWhitespace
    }

    "g-outerWhitespace on a date-format string fails the parse: the value is read as a date, not text" {
        shouldThrow<KdrException> {
            parseSchemaTypes(
                schemaDefs(cxt, "w") {
                    type("Bad") {
                        type = SCT.kObject
                        property("d", "A day") { dayOnlyDate(); outerWhitespace = SOWS.reject }
                    }
                },
            )
        }.message.orEmpty() shouldContain SFMT.date
    }

    "an unrecognized mode fails the parse rather than silently doing nothing" {
        shouldThrow<KdrException> {
            parseSchemaTypes(mapOf("w.Raw" to mapOf(SCH.type to SCT.string, SCH.outerWhitespace to "strip")))
        }.message.orEmpty() shouldContain "'strip'"
    }

    "the resolved mode is exposed on SchType" {
        trimType().properties.getValue("name").valueType.outerWhitespace shouldBe SchOuterWhitespace.trim
        rejectType().properties.getValue("name").valueType.outerWhitespace shouldBe SchOuterWhitespace.reject
    }
}

/**
 * `g-visibleOnly` (issue #543): a string may hold only characters that render visibly, plus the ordinary
 * space.
 *
 * The rule is one test on the General Category, so the cases are one representative per refused category
 * rather than an inventory, and a few names from scripts that a naive "letters only" filter gets wrong --
 * those are the values the rule must *not* refuse, and the half of the contract that is easiest to break
 * while tightening the other. Every invisible character is written as a `\uXXXX` escape: spelled literally
 * they would be exactly as unreadable here as the rule says they are.
 */
private fun schVisibleOnlyTests() = stringSpec {

    val cxt = KdrCxt.mkSimpleCxt("test")

    /** A ruled `name` field beside an unruled `note`, so the default is asserted next to the declaration. */
    fun recType(required: Boolean = true): SchType = parseSchemaTypes(
        schemaDefs(cxt, "v") {
            type("Rec") {
                type = SCT.kObject
                property("name", "A name", required = required) { visibleOnly = true }
                property("note", "A note, unruled")
            }
        },
    ).getValue("v.Rec")

    fun nameFailures(name: String): List<SchFailure> = validate(recType(), mapOf("name" to name))

    // --- what must pass -------------------------------------------------------------------------------------

    "visible text in any script passes, including combining marks, a supplementary pair, and the space" {
        for (name in listOf(
            "Jean-Luc O'Brien",
            "Nguyễn Văn A", // precomposed Vietnamese
            "Nguyễn", // the same syllable with its marks as combining characters (Mn)
            "日本語",
            "محمد",
            "😀 smile", // an emoji: a surrogate pair, both halves category SURROGATE
            "Col·legi", // the Catalan middle dot (Po)
        )) {
            withClue(name) { nameFailures(name).shouldBeEmpty() }
        }
    }

    "the rule is off unless declared" {
        validate(recType(), mapOf("name" to "ok", "note" to "tab\there\nand newline")).shouldBeEmpty()
    }

    // --- what must fail: one representative per refused category -------------------------------------------

    "each invisible category is refused" {
        val samples = mapOf(
            "tab (Cc)" to "a\tb",
            "newline (Cc)" to "a\nb",
            "carriage return (Cc)" to "a\rb",
            "NUL (Cc)" to "a\u0000b",
            "DEL (Cc)" to "a\u007Fb",
            "next line, a C1 control (Cc)" to "a\u0085b",
            "zero-width space (Cf)" to "a\u200Bb",
            "zero-width joiner (Cf)" to "a\u200Db",
            "right-to-left override (Cf)" to "\u202Eabc",
            "soft hyphen (Cf)" to "a\u00ADb",
            "byte order mark (Cf)" to "\uFEFFabc",
            "no-break space (Zs)" to "a\u00A0b",
            "em space (Zs)" to "a\u2003b",
            "ideographic space (Zs)" to "a\u3000b",
            "line separator (Zl)" to "a\u2028b",
            "paragraph separator (Zp)" to "a\u2029b",
            "private use (Co)" to "a\uE000b",
            "a lone high surrogate (Cs)" to "a\uD83Db",
            "a lone low surrogate (Cs)" to "a\uDE00b",
            "a tag character, supplementary Cf (U+E0041)" to "a\uDB40\uDC41b",
            "supplementary private use (U+F0000)" to "a\uDB80\uDC00b",
        )
        for ((label, value) in samples) {
            withClue(label) {
                nameFailures(value).map { it.code } shouldContainExactly listOf(SchFailCode.badValue)
            }
        }
    }

    "the message names the code point and its position in characters, so the invisible is locatable" {
        // The emoji before it is one character, so the no-break space is the third, not the fourth.
        val failure = nameFailures("😀a\u00A0b").single()
        failure.message shouldContain "U+00A0"
        failure.message shouldContain "position 3"
        // A supplementary offender is spelled with all its digits.
        nameFailures("\uDB40\uDC41").single().message shouldContain "U+E0041"
    }

    "reported in both modes: it is validation, not coercion" {
        validate(recType(), mapOf("name" to "a\u200Bb")).shouldHaveSize(1)
        coerceAndValidate(recType(), mapOf("name" to "a\u200Bb")).failures.shouldHaveSize(1)
    }

    "a coerced value is checked too" {
        // A non-string arriving at a coercible string field becomes its toString() and is then ruled on.
        val coercible = parseSchemaTypes(
            schemaDefs(cxt, "v") {
                type("C") {
                    type = SCT.kObject
                    property("name", "A name") { visibleOnly = true; allowCoerce = true }
                }
            },
        ).getValue("v.C")
        validate(coercible, mapOf("name" to 42)).shouldBeEmpty()
        validate(coercible, mapOf("name" to listOf("a\u00A0b"))).map { it.code } shouldContainExactly
            listOf(SchFailCode.badValue)
    }

    // --- interplay with the rules around it -----------------------------------------------------------------

    "a value made only of invisible characters reads as absent under emptyIsAbsent, before this rule sees it" {
        // Required: missing, not badValue. Optional: dropped from the coerced output, and no failure at all.
        validate(recType(), mapOf("name" to "\u00A0\u00A0")).map { it.code } shouldContainExactly
            listOf(SchFailCode.missingRequired)
        val optional =
            coerceAndValidate(recType(required = false), mapOf("name" to "\u00A0\u00A0", "note" to "n"))
        optional.failures.shouldBeEmpty()
        (optional.value as Map<*, *>).keys shouldBe setOf("note")
    }

    "it runs ahead of a closed choice list, so a listed value cannot vouch for an invisible character" {
        val chooser = parseSchemaTypes(
            schemaDefs(cxt, "v") {
                type("Pick") {
                    type = SCT.kObject
                    property("name", "A choice") {
                        visibleOnly = true
                        option("a\u00A0b", "A B")
                        option("c", "C")
                    }
                }
            },
        ).getValue("v.Pick")
        validate(chooser, mapOf("name" to "a\u00A0b")).map { it.code } shouldContainExactly
            listOf(SchFailCode.badValue)
    }

    // --- refused at parse time where it would constrain nothing ---------------------------------------------

    "g-visibleOnly declared on a non-string type fails the parse by name" {
        shouldThrow<KdrException> {
            parseSchemaTypes(
                schemaDefs(cxt, "v") {
                    type("Bad") {
                        type = SCT.kObject
                        property("n", "A count") { type = SCT.integer; visibleOnly = true }
                    }
                },
            )
        }.message.orEmpty() shouldContain SCH.visibleOnly
    }

    "g-visibleOnly on a date-format string fails the parse: the value is read as a date, not text" {
        shouldThrow<KdrException> {
            parseSchemaTypes(
                schemaDefs(cxt, "v") {
                    type("Bad") {
                        type = SCT.kObject
                        property("d", "A day") { dayOnlyDate(); visibleOnly = true }
                    }
                },
            )
        }.message.orEmpty() shouldContain SFMT.date
    }

    "a non-boolean value fails the parse rather than silently reading as off" {
        // Only reachable from a raw document; the builder's attribute is typed.
        shouldThrow<KdrException> {
            parseSchemaTypes(mapOf("v.Raw" to mapOf(SCH.type to SCT.string, SCH.visibleOnly to "yes")))
        }.message.orEmpty() shouldContain "'yes'"
    }

    "declared false anywhere is simply off" {
        parseSchemaTypes(mapOf("v.Off" to mapOf(SCH.type to SCT.integer, SCH.visibleOnly to false)))
            .getValue("v.Off").visibleOnly shouldBe false
    }
}

/**
 * Open choice lists: options that suggest rather than bound (issue #418).
 *
 * Every assertion here is a **pair** against the closed equivalent. A test that only showed an open list
 * accepting an off-list value would pass just as well if the options had been dropped altogether, or if the
 * validator had stopped checking options for everybody -- and those are the two ways this could be wrong
 * while looking right. The closed half is what says the check still exists.
 */
private fun schOpenOptionsTests() = stringSpec {

    val cxt = KdrCxt.mkSimpleCxt("openOptions")

    fun defs(open: Boolean) = schemaDefs(cxt, "t") {
        type("Visit") {
            type = SCT.kObject
            property("purpose", "Why the visit happened.") {
                option("inspection")
                option("delivery")
                if (open) openOptions()
            }
        }
    }

    fun typeOf(open: Boolean): SchType = parseSchemaTypes(defs(open)).getValue("t.Visit")

    "an off-list value is accepted when the list is open and refused when it is closed" {
        validate(typeOf(open = true), mapOf("purpose" to "something else")).shouldBeEmpty()

        val refused = validate(typeOf(open = false), mapOf("purpose" to "something else"))
        refused.single().code shouldBe SchFailCode.invalidOption
    }

    "a listed value is accepted either way" {
        // The suggestions are still suggestions: opening a list must not stop the listed values working, which
        // is what a check written as "skip validation entirely" would break without any test noticing.
        validate(typeOf(open = true), mapOf("purpose" to "inspection")).shouldBeEmpty()
        validate(typeOf(open = false), mapOf("purpose" to "inspection")).shouldBeEmpty()
    }

    "an open list still enforces everything else about the field" {
        // Openness is about the *list*, not about the field: a value has to be the right shape, whatever the
        // suggestions say. Without this the keyword would be a way to switch a field's validation off.
        val strict = schemaDefs(cxt, "t2") {
            type("Sized") {
                type = SCT.kObject
                property("code", "A short code.") {
                    option("ab")
                    openOptions()
                    data[SCH.maxLength] = 4
                }
            }
        }
        val type = parseSchemaTypes(strict).getValue("t2.Sized")
        validate(type, mapOf("code" to "abc")).shouldBeEmpty()
        validate(type, mapOf("code" to "far too long")).single().code shouldBe SchFailCode.aboveMaximum
    }

    "the keyword survives parsing onto the type" {
        typeOf(open = true).properties.getValue("purpose").valueType.openOptions shouldBe true
        typeOf(open = false).properties.getValue("purpose").valueType.openOptions shouldBe false
    }

    // --- what a client may alter (issue #356's rules, under an open list) --------------------------------

    fun body(open: Boolean, vararg choices: String) = mapOf<String, Any?>(
        SCH.type to SCT.kObject,
        SCH.properties to mapOf(
            "purpose" to buildMap {
                put(SCH.options, choices.map { mapOf(SCH.label to it, SCH.value to it) })
                if (open) put(SCH.openOptions, true)
            },
        ),
    )

    "a client may offer different suggestions on an open list, and only a subset on a closed one" {
        // The pair that matters. On a closed list the subset rule protects what the type accepts; on an open
        // list there is nothing to protect, and refusing anyway would block the obvious use -- a suggestion
        // list assembled for one client.
        narrowingProblems("t.Visit", body(open = true, "inspection"), body(open = true, "audit")).shouldBeEmpty()

        narrowingProblems("t.Visit", body(open = false, "inspection"), body(open = false, "audit"))
            .single().message shouldContain "audit"
    }

    "a client may close an open list but may not open a closed one" {
        // The asymmetry, and the pair is the point: both directions read alike and only one of them widens.
        //
        // **Closing is allowed** -- an open list accepts anything, so a client that bounds it accepts a
        // subset. That is narrowing rule 2 said in the other keyword, and no more dangerous than trimming a
        // closed list's choices, which the rule has always permitted.
        narrowingProblems("t.Visit", body(open = true, "inspection"), body(open = false, "inspection"))
            .shouldBeEmpty()

        // **Opening is refused** -- values the base rejects would become storable here and invalid to
        // everybody else, which is the cross-client breakage the whole rule exists to prevent.
        narrowingProblems("t.Visit", body(open = false, "inspection"), body(open = true, "inspection"))
            .single().message shouldContain SCH.openOptions
    }

    "closing an open list may bound it to choices the base never listed" {
        // Follows from the same reasoning and is worth its own assertion, because it looks wrong: the
        // variant's choices are not a subset of the base's. They do not need to be -- the base accepted every
        // string, so any finite list is a narrowing of it, whatever is in it.
        narrowingProblems("t.Visit", body(open = true, "inspection"), body(open = false, "audit"))
            .shouldBeEmpty()
    }

    "adding an open suggestion list to an optionless base is allowed" {
        // The global field is free text (no choices); a client adds per-caller *suggestions* without bounding
        // it. That accepts exactly what the base did -- any string -- so it must not be refused. Adding a
        // *closed* list to the same optionless field is already allowed (narrowing rule 2), and refusing the
        // weaker, open one while permitting the stricter, closed one would be backwards.
        //
        // A `purpose` that is free text on the base and gains a list on the variant. Both carry `type: string`
        // so the only difference under test is the list -- otherwise the "everything else must match" rule
        // would flag the type, which is a different thing entirely.
        fun purpose(prop: Map<String, Any?>) = mapOf<String, Any?>(
            SCH.type to SCT.kObject,
            SCH.properties to mapOf("purpose" to (mapOf(SCH.type to SCT.string) + prop)),
        )
        val freeText = purpose(emptyMap())
        val withOpenList = purpose(
            mapOf(
                SCH.options to listOf(mapOf(SCH.label to "inspection", SCH.value to "inspection")),
                SCH.openOptions to true,
            ),
        )
        val withClosedList = purpose(
            mapOf(SCH.options to listOf(mapOf(SCH.label to "inspection", SCH.value to "inspection"))),
        )
        narrowingProblems("t.Visit", freeText, withOpenList).shouldBeEmpty()
        // The closed counterpart is likewise allowed -- applying a list where there was none narrows.
        narrowingProblems("t.Visit", freeText, withClosedList).shouldBeEmpty()
    }
}

/**
 * Sourced choice lists: the render-time resolution and the checks that refuse a bad one at boot (issue #413).
 *
 * The weight here is on **sharing**, not on the substitution. Producing the right list is the obvious half and
 * fails loudly; leaving the compiled store untouched while doing it is the half that fails silently -- a node
 * would serve one caller's clients to everybody afterwards, and the only symptom would be a correct-looking
 * dropdown. So the assertions are mostly about which objects came back, and the first thing the substitution
 * test does is check that the input was not the thing that changed.
 */
private fun schOptionsSourceTests() = stringSpec {

    val cxt = KdrCxt.mkSimpleCxt("optionsSource")

    // Two providers, so a test can tell "the right one ran" from "a provider ran". The second reports the
    // property name it was handed, which is the only way to see that the walk names a property by its key.
    val providers = mapOf<String, SchOptionsProvider>(
        "colours" to { _, _ -> listOf(SchOption("red", "Red"), SchOption("green", "Green")) },
        "echoName" to { _, name -> listOf(SchOption(name, "Field $name")) },
    )

    fun optionsAt(node: Map<String, Any?>, property: String): List<Map<String, Any?>> =
        node[SCH.properties].toJsonMapOrEmpty()[property].toJsonMapOrEmpty()[SCH.options]
            .let { it as List<*> }.map { it.toJsonMapOrEmpty() }

    "a sourced list becomes an ordinary options list, and the source key does not travel" {
        val type = linkedMapOf<String, Any?>(
            SCH.type to SCT.kObject,
            SCH.properties to linkedMapOf<String, Any?>(
                "colour" to linkedMapOf<String, Any?>(
                    SCH.description to "Pick one.",
                    SCH.optionsSource to "colours",
                    SCH.dComment to "after",
                ),
            ),
        )
        val out = resolveOptionsSources(cxt, type, providers)

        optionsAt(out, "colour").map { it[SCH.value] } shouldBe listOf("red", "green")
        optionsAt(out, "colour").map { it[SCH.label] } shouldBe listOf("Red", "Green")
        // The id is consumed: a reader gets a choice list and no second way to have one.
        out[SCH.properties].toJsonMapOrEmpty()["colour"].toJsonMapOrEmpty().containsKey(SCH.optionsSource) shouldBe false
        // ...in the position the id held, so two callers differ in values and not in key order.
        out[SCH.properties].toJsonMapOrEmpty()["colour"].toJsonMapOrEmpty().keys.toList() shouldBe
            listOf(SCH.description, SCH.options, SCH.dComment)
    }

    "the source document is never touched" {
        val field = linkedMapOf<String, Any?>(SCH.optionsSource to "colours")
        val properties = linkedMapOf<String, Any?>("colour" to field)
        val type = linkedMapOf<String, Any?>(SCH.type to SCT.kObject, SCH.properties to properties)

        resolveOptionsSources(cxt, type, providers)

        // The whole argument for copy-on-write. Were this to fail, a node would answer every later caller --
        // in every client -- with whichever caller happened to arrive first, and look right doing it.
        field.keys.toList() shouldBe listOf(SCH.optionsSource)
        properties["colour"] shouldBeSameInstanceAs field
        type[SCH.properties] shouldBeSameInstanceAs properties
    }

    "a document with nothing to resolve comes back as the same object" {
        val type = linkedMapOf<String, Any?>(
            SCH.type to SCT.kObject,
            SCH.properties to linkedMapOf<String, Any?>(
                "colour" to linkedMapOf<String, Any?>(SCH.options to listOf("red")),
            ),
        )
        // Identity, not equality: the catalog resolves every response, and the overwhelming majority of nodes
        // have no sourced list. Copying them anyway would be a whole-document clone per request.
        resolveOptionsSources(cxt, type, providers) shouldBeSameInstanceAs type
    }

    "only the branch containing a source is copied" {
        val untouched = linkedMapOf<String, Any?>(SCH.type to SCT.string, SCH.description to "Plain.")
        val type = linkedMapOf<String, Any?>(
            SCH.properties to linkedMapOf<String, Any?>(
                "plain" to untouched,
                "colour" to linkedMapOf<String, Any?>(SCH.optionsSource to "colours"),
            ),
        )
        val out = resolveOptionsSources(cxt, type, providers)

        out shouldNotBeSameInstanceAs type
        out[SCH.properties].toJsonMapOrEmpty()["plain"] shouldBeSameInstanceAs untouched
    }

    "a provider is told the property's own name" {
        val type = linkedMapOf<String, Any?>(
            SCH.properties to linkedMapOf<String, Any?>(
                "client" to linkedMapOf<String, Any?>(SCH.optionsSource to "echoName"),
            ),
        )
        val out = resolveOptionsSources(cxt, type, providers)
        optionsAt(out, "client").single()[SCH.value] shouldBe "client"
    }

    "an array's items inherit the enclosing property's name" {
        val type = linkedMapOf<String, Any?>(
            SCH.properties to linkedMapOf<String, Any?>(
                "tags" to linkedMapOf<String, Any?>(
                    SCH.type to SCT.array,
                    SCH.items to linkedMapOf<String, Any?>(SCH.optionsSource to "echoName"),
                ),
            ),
        )
        val out = resolveOptionsSources(cxt, type, providers)
        val items = out[SCH.properties].toJsonMapOrEmpty()["tags"].toJsonMapOrEmpty()[SCH.items].toJsonMapOrEmpty()
        // A multi-select's choices belong to the field, not to a nameless element, so "tags" is the answer a
        // provider needs -- there is no better name available and an empty one would be useless.
        (items[SCH.options] as List<*>).map { it.toJsonMapOrEmpty()[SCH.value] } shouldBe listOf("tags")
    }

    "an unregistered id faults rather than resolving to nothing" {
        val type = linkedMapOf<String, Any?>(
            SCH.properties to linkedMapOf<String, Any?>(
                "colour" to linkedMapOf<String, Any?>(SCH.optionsSource to "nope"),
            ),
        )
        // A booted node cannot reach this (checkInit refuses first), but a hand-built store has no such pass,
        // and an empty choice list is the failure that reads as a working page.
        shouldThrow<KdrException> { resolveOptionsSources(cxt, type, providers) }
            .fullMessage() shouldContain "nope"
    }

    // ---- the boot checks -------------------------------------------------------------------------------

    "a clean document reports no problems" {
        val type = mapOf<String, Any?>(
            SCH.properties to mapOf("colour" to mapOf(SCH.optionsSource to "colours")),
        )
        optionsSourceProblems("Type 'x'", type, providers).shouldBeEmpty()
    }

    "an unregistered id is a boot problem, named with its property" {
        val type = mapOf<String, Any?>(
            SCH.properties to mapOf("colour" to mapOf(SCH.optionsSource to "missing")),
        )
        val problems = optionsSourceProblems("Type 'core.Paint'", type, providers)
        problems shouldHaveSize 1
        problems.single() shouldContain "core.Paint"
        problems.single() shouldContain "colour"
        problems.single() shouldContain "missing"
    }

    "declaring both a list and a source is a boot problem" {
        val type = mapOf<String, Any?>(
            SCH.properties to mapOf(
                "colour" to mapOf(SCH.optionsSource to "colours", SCH.options to listOf("red")),
            ),
        )
        // Refused rather than merged: a merge would need a rule, and an answer to whether the written half
        // still binds. Neither has a use yet, and permitting it now would settle both by accident.
        optionsSourceProblems("Type 'x'", type, providers).single() shouldContain "one place or the other"
    }

    "problems are found inside nested structure, not only at the top" {
        val type = mapOf<String, Any?>(
            SCH.properties to mapOf(
                "addresses" to mapOf(
                    SCH.type to SCT.array,
                    SCH.items to mapOf(
                        SCH.properties to mapOf("country" to mapOf(SCH.optionsSource to "missing")),
                    ),
                ),
            ),
        )
        // Through an array's `items`: a choice list on the element of a repeating group is an ordinary thing
        // to declare, and a check that stopped at the top would pass it.
        optionsSourceProblems("Type 'x'", type, providers).single() shouldContain "country"
    }

    "problems are found inside a union's branches" {
        val type = mapOf<String, Any?>(
            SCH.oneOf to listOf(
                mapOf(SCH.properties to mapOf("kind" to mapOf(SCH.type to SCT.string))),
                mapOf(SCH.properties to mapOf("colour" to mapOf(SCH.optionsSource to "missing"))),
            ),
        )
        // A **list** of schemas, which is a shape the walk meets nowhere else -- and the shape every gedra
        // entry type is, so a check blind to it would be blind to most of the document that matters.
        optionsSourceProblems("Type 'x'", type, providers).single() shouldContain "colour"
    }

    "a union branch resolves like anything else" {
        val branch = linkedMapOf<String, Any?>(SCH.properties to linkedMapOf<String, Any?>("k" to linkedMapOf<String, Any?>()))
        val type = linkedMapOf<String, Any?>(
            SCH.oneOf to listOf(
                branch,
                linkedMapOf<String, Any?>(
                    SCH.properties to linkedMapOf<String, Any?>(
                        "colour" to linkedMapOf<String, Any?>(SCH.optionsSource to "colours"),
                    ),
                ),
            ),
        )
        val out = resolveOptionsSources(cxt, type, providers)
        val branches = out[SCH.oneOf] as List<*>
        // The untouched branch is shared; only the one holding the source was rebuilt.
        branches[0] shouldBeSameInstanceAs branch
        optionsAt(branches[1].toJsonMapOrEmpty(), "colour").map { it[SCH.value] } shouldBe listOf("red", "green")
    }

    "a non-string source is refused rather than ignored" {
        val type = mapOf<String, Any?>(SCH.properties to mapOf("colour" to mapOf(SCH.optionsSource to 7)))
        optionsSourceProblems("Type 'x'", type, providers).single() shouldContain "not an id"
    }

    // Which sources a stored definition may name (issue #1193): the repair judges by who holds the type.
    val repairContext = DefRepairContext(providers.keys, storedConfigMayName = setOf("echoName")) { null }

    fun sourcedType(source: String) =
        mapOf<String, Any?>(SCH.properties to mapOf("colour" to mapOf(SCH.optionsSource to source)))

    "source-declared schema may name any registered source" {
        val type = sourcedType("colours")
        val (kept, repairs) = repairTypeDef("Type 'x'", type, repairContext)
        repairs.shouldBeEmpty()
        kept shouldBeSameInstanceAs type
    }

    "stored schema may name only a source that allows it" {
        val stored = { true }
        repairTypeDef("Type 'x'", sourcedType("echoName"), repairContext, heldByStoredConfig = stored).second.shouldBeEmpty()
        val (kept, repairs) = repairTypeDef("Type 'x'", sourcedType("colours"), repairContext, heldByStoredConfig = stored)
        repairs.single().message shouldContain "which stored configuration may not name"
        kept[SCH.properties].toJsonMapOrEmpty()["colour"].toJsonMapOrEmpty().containsKey(SCH.optionsSource) shouldBe false
    }

    "where the base names a source, a stored definition may restate it there and nowhere else" {
        val base = sourcedType("colours")
        fun repairsOver(body: Map<String, Any?>) =
            repairTypeDef("Type 'x'", body, repairContext, altersGlobal = true, heldByStoredConfig = { true }, base = base).second
        repairsOver(sourcedType("colours")).shouldBeEmpty()
        // The same list on another field of the type is a source the base does not name *there*.
        val moved = mapOf<String, Any?>(SCH.properties to mapOf("shade" to mapOf(SCH.optionsSource to "colours")))
        repairsOver(moved).single().message shouldContain "property 'shade'"
    }

    "who holds the type is asked only when it could matter" {
        var asked = 0
        val holder = { asked++; true }
        repairTypeDef("Type 'x'", sourcedType("echoName"), repairContext, heldByStoredConfig = holder)
        repairTypeDef("Type 'x'", mapOf(SCH.properties to mapOf("plain" to mapOf<String, Any?>())), repairContext, heldByStoredConfig = holder)
        asked shouldBe 0
    }
}

/**
 * Our own `g-` keywords are strict (issue #822): a `g-` key that is not one of ours, or one of ours whose value has
 * the wrong shape, fails the parse by name -- where it used to be ignored, or read leniently back to a default.
 * Keys without the prefix stay a document's own business.
 */
private fun schGKeywordsTests() = stringSpec {

    /** Parses one type whose `v` property carries [keyword] = [value]; returns the refusal's message, or null. */
    fun refusal(keyword: String, value: Any?): String? = try {
        parseSchemaTypes(
            mapOf(
                "g.Raw" to mapOf(
                    SCH.type to SCT.kObject,
                    SCH.properties to mapOf("v" to mapOf(SCH.type to SCT.string, keyword to value)),
                ),
            ),
        )
        null
    } catch (e: KdrException) {
        e.message.orEmpty()
    }

    "an unknown g- key fails the parse, naming it and the property" {
        val message = refusal("g-allowCoerse", true)
        message.shouldNotBeNull() shouldContain "'g-allowCoerse'"
        message shouldContain "Property 'v'"
    }

    // Each keyword that used to be read leniently -- `as? Boolean`, `== true`, unchecked text, a non-list read as
    // empty -- now refuses a value of the wrong shape, naming the keyword.
    "an ill-typed value of each formerly lenient keyword fails the parse by name" {
        val cases = listOf(
            SCH.allowCoerce to "yes",
            SCH.emptyIsAbsent to "no",
            SCH.openOptions to "true",
            SCH.schemaDocument to 1L,
            SCH.optionalContents to "x",
            SCH.presentation to "fancy",
            SCH.primaryKey to "id",
            SCH.derived to "yes",
            SCH.appliesTo to "formDoc",
        )
        for ((keyword, value) in cases) {
            refusal(keyword, value).shouldNotBeNull() shouldContain "'$keyword'"
        }
    }

    "a well-shaped keyword, a null, and a key of the document's own all pass" {
        refusal(SCH.allowCoerce, true) shouldBe null
        refusal(SCH.presentation, PRES.identifier) shouldBe null
        refusal(SCH.allowCoerce, null) shouldBe null
        refusal("x-myNote", "anything") shouldBe null
        refusal("myOwnKey", listOf(1, 2)) shouldBe null
    }

    // The check is on the type too, not only its properties, and on a `$ref` property, whose own keywords its
    // target never sees.
    "a bad keyword on a type, or beside a ref, fails too" {
        shouldThrow<KdrException> {
            parseSchemaTypes(mapOf("g.Top" to mapOf(SCH.type to SCT.kObject, SCH.primaryKey to "id")))
        }.message.orEmpty() shouldContain "Type 'g.Top'"
        shouldThrow<KdrException> {
            parseSchemaTypes(
                mapOf(
                    "g.Target" to mapOf(SCH.type to SCT.string),
                    "g.Holder" to mapOf(
                        SCH.type to SCT.kObject,
                        SCH.properties to mapOf(
                            "t" to mapOf(SCH.dRef to "#/${SCH.dDefs}/g.Target", SCH.optionalContents to "yes"),
                        ),
                    ),
                ),
            )
        }.message.orEmpty() shouldContain "'${SCH.optionalContents}'"
    }
}

/**
 * The standard keywords this layer reads are held to their shapes (issue #1053): a value of the wrong kind fails the
 * parse by name -- where it used to be read as though the keyword were absent, so that a typo in a value turned
 * validation off and nothing said so. A keyword the layer does not read stays a document's own business, and a
 * client's stored definition is repaired rather than refused: the keyword goes, and the fault costs only itself.
 */
private fun schStdKeywordsTests() = stringSpec {

    /** Parses one type whose `v` property is [property]; returns the refusal's message, or null. */
    fun refusal(property: Map<String, Any?>, vararg onType: Pair<String, Any?>): String? = try {
        parseSchemaTypes(mapOf("s.Raw" to mapOf(SCH.type to SCT.kObject, SCH.properties to mapOf("v" to property)) + onType))
        null
    } catch (e: KdrException) {
        e.message.orEmpty()
    }

    "a type that is no type is refused, where it used to be kept and then constrain nothing" {
        val message = refusal(mapOf(SCH.type to "strng"))
        message.shouldNotBeNull() shouldContain "Property 'v' sets 'type' to 'strng'"
        message shouldContain "one of string, number, integer, boolean, array, object, null"
        refusal(mapOf(SCH.type to 5L)).shouldNotBeNull() shouldContain "sets 'type' to 5"
        // A list of types is legal JSON Schema and not read here: said, rather than left to look like a typo. So
        // are a schema per item, and true or false standing for a schema. (A schema for undeclared properties was
        // among them until it was read: a map of typed values, issue #1055.)
        refusal(mapOf(SCH.type to listOf(SCT.string, SCT.kNull))).shouldNotBeNull() shouldContain "a list of types is not supported"
        refusal(mapOf(SCH.type to SCT.kObject, SCH.additionalProperties to mapOf(SCH.type to SCT.string))) shouldBe null
        refusal(mapOf(SCH.type to SCT.array, SCH.items to listOf(mapOf(SCH.type to SCT.string)))).shouldNotBeNull() shouldContain
            "a schema per position is not supported"
        refusal(mapOf(SCH.type to SCT.array, SCH.items to true)).shouldNotBeNull() shouldContain "an empty object accepts anything"
        refusal(mapOf(SCH.type to SCT.kObject, SCH.properties to mapOf("any" to true))).shouldNotBeNull()
            .let { it shouldContain "declares property 'any' as true"; it shouldContain "not supported here" }
        for (type in listOf(SCT.string, SCT.number, SCT.integer, SCT.boolean, SCT.array, SCT.kObject, SCT.kNull)) {
            refusal(mapOf(SCH.type to type)) shouldBe null
        }
    }

    "each keyword read leniently before now refuses a value of the wrong shape, naming the keyword and the place" {
        // On a property: the keywords a property's own schema carries.
        val onProperty = listOf(
            mapOf(SCH.type to SCT.kObject, SCH.required to "name") to "'required'",
            mapOf(SCH.type to SCT.kObject, SCH.required to listOf("name", 5L)) to "lists 5 in 'required'",
            mapOf(SCH.type to SCT.kObject, SCH.properties to listOf("name")) to "'properties'",
            mapOf(SCH.type to SCT.kObject, SCH.properties to mapOf("name" to "text")) to "declares property 'name' as 'text'",
            mapOf(SCH.type to SCT.kObject, SCH.additionalProperties to "no") to "'additionalProperties'",
            mapOf(SCH.type to SCT.array, SCH.items to "string") to "'items'",
            mapOf(SCH.type to SCT.string, SCH.format to 5L) to "'format'",
            mapOf(SCH.type to SCT.string, SCH.description to listOf("x")) to "'description'",
            mapOf(SCH.type to SCT.string, SCH.title to true) to "'title'",
            mapOf(SCH.dRef to mapOf("to" to "s.Other")) to "'\$ref'",
            mapOf(SCH.type to SCT.string, SCH.minLength to "three") to "'minLength'",
            mapOf(SCH.type to SCT.integer, SCH.maximum to listOf(5L)) to "'maximum'",
            mapOf(SCH.oneOf to "s.Other") to "'oneOf'",
        )
        for ((property, named) in onProperty) {
            val message = refusal(property).shouldNotBeNull()
            message shouldContain named
            message shouldContain "Property 'v'"
        }
        // Beside a `$ref`, whose target never sees the property's own keywords: its title and description are read
        // from the property itself.
        fun beside(vararg own: Pair<String, Any?>) = shouldThrow<KdrException> {
            parseSchemaTypes(
                mapOf(
                    "s.Other" to mapOf(SCH.type to SCT.string),
                    "s.Holder" to mapOf(SCH.type to SCT.kObject, SCH.properties to mapOf("o" to mapOf(SCH.dRef to "#/\$defs/s.Other") + own)),
                ),
            )
        }.message.orEmpty()
        beside(SCH.title to 5L) shouldContain "Property 'o' sets 'title' to 5"
        beside(SCH.description to listOf("x")) shouldContain "'description'"
        // And on a type.
        refusal(mapOf(SCH.type to SCT.string), SCH.required to "v").shouldNotBeNull() shouldContain "Type 's.Raw'"
        refusal(mapOf(SCH.type to SCT.string), SCH.additionalProperties to 0L).shouldNotBeNull() shouldContain "true, false or a schema object"
    }

    "a well-shaped keyword, a null, a bound spelled as text, and a keyword of the document's own all pass" {
        refusal(mapOf(SCH.type to SCT.string, SCH.minLength to 3L, SCH.maxLength to "64")) shouldBe null
        refusal(mapOf(SCH.type to null, SCH.required to null, SCH.items to null)) shouldBe null
        // A keyword this layer does not read is the document's own: a stock validator's document still parses.
        refusal(mapOf(SCH.type to SCT.string, "examples" to "not a list", "\$comment" to 5L, "multipleOf" to "x")) shouldBe null
        refusal(mapOf(SCH.type to SCT.string, "x-note" to mapOf(SCH.type to "strng"))) shouldBe null
        // Nor is a keyword's word in a value: a default is data.
        refusal(mapOf(SCH.type to SCT.kObject, SCH.default to mapOf(SCH.type to "refund", SCH.required to "soon"))) shouldBe null
        // A null property is one not set, as an alteration merged into a global type removes one.
        parseSchemaTypes(mapOf("s.Null" to mapOf(SCH.type to SCT.kObject, SCH.properties to mapOf("gone" to null))))
            .getValue("s.Null").properties.keys.shouldBeEmpty()
    }

    "the fault is coded and located for a report, as any schema fault is" {
        val analysis = analyzeSchemaTypes(
            mapOf(
                "s.Deep" to mapOf(
                    SCH.type to SCT.kObject,
                    SCH.properties to mapOf("list" to mapOf(SCH.type to SCT.array, SCH.items to mapOf(SCH.type to "text"))),
                ),
            ),
        )
        val problem = analysis.problems.single()
        problem.code shouldBe SchemaError.badValue
        problem.location.shouldNotBeNull().path shouldBe "s.Deep.properties.list.items"
        shouldThrow<KdrException> { parseSchemaTypes(mapOf("s.Top" to mapOf(SCH.type to "objct"))) }.message.orEmpty() shouldContain "Type 's.Top'"
    }

    val context = DefRepairContext(emptySet()) { null }

    "a client's stored definition is repaired, not refused: the keyword goes and the rest of the definition stands" {
        val body = mapOf(
            SCH.type to SCT.kObject,
            SCH.required to listOf("name", 5L, listOf("x")),
            SCH.additionalProperties to "no",
            SCH.properties to mapOf(
                "name" to mapOf(SCH.type to "strng", SCH.minLength to "three", SCH.maxLength to "64"),
                "tags" to mapOf(SCH.type to SCT.array, SCH.items to mapOf(SCH.type to SCT.string, SCH.format to 7L)),
                "junk" to "not a schema",
            ),
        )
        val (repaired, repairs) = repairTypeDef("Type 'client.x.Card'", body, context)
        // Each fault is said, with the place it is in, and what was done about it.
        repairs.map { it.message }.let { messages ->
            messages.size shouldBe 6
            messages.single { "'additionalProperties'" in it } shouldContain "Type 'client.x.Card' sets"
            messages.single { "in 'required'" in it } shouldContain "lists 5, a list"
            messages.single { "property 'junk'" in it } shouldContain "must be an object"
            messages.single { "'type'" in it } shouldContain "Type 'client.x.Card' property 'name'"
            messages.single { "'minLength'" in it } shouldContain "'three'"
            messages.single { "'format'" in it } shouldContain "property 'tags'"
        }
        repairs.all { it.degradedTo.startsWith("Dropping") } shouldBe true
        repairs.single { "in 'required'" in it.message }.degradedTo shouldContain "that part of 'required'"
        // What is left of `required` is exactly what the lenient reading made of it -- the names its entries spell,
        // a number read as its text -- and the bad property and the keywords at fault are gone, with everything
        // sound kept: the bound that reads as a number among it.
        repaired[SCH.required] shouldBe listOf("name", "5")
        repaired.containsKey(SCH.additionalProperties) shouldBe false
        val properties = repaired[SCH.properties] as Map<*, *>
        properties.keys shouldBe setOf("name", "tags")
        properties["name"] shouldBe mapOf(SCH.maxLength to "64")
        properties["tags"] shouldBe mapOf(SCH.type to SCT.array, SCH.items to mapOf(SCH.type to SCT.string))
        // And it now parses, to the type it was read as before.
        val card = parseSchemaTypes(mapOf("client.x.Card" to repaired)).getValue("client.x.Card")
        card.required shouldBe setOf("name", "5")
        card.additionalProperties shouldBe false
        card.properties.getValue("name").valueType.jsonType shouldBe null
        // A sound definition is handed back as it was.
        val sound = mapOf(SCH.type to SCT.kObject, SCH.properties to mapOf("a" to mapOf(SCH.type to SCT.string)))
        repairTypeDef("Type 'client.x.Sound'", sound, context).let { (same, none) ->
            (same === sound) shouldBe true
            none.shouldBeEmpty()
        }
    }

    "a keyword's word in data is not a keyword: a default, a const and a keyword of the document's own are left alone" {
        val body = mapOf(
            SCH.type to SCT.kObject,
            SCH.default to mapOf(SCH.type to "refund", SCH.required to "soon", SCH.properties to listOf(1L)),
            "examples" to listOf(mapOf(SCH.type to "refund", SCH.items to "three")),
            SCH.properties to mapOf(
                "kind" to mapOf(SCH.type to SCT.kObject, SCH.const to mapOf(SCH.type to "refund", SCH.additionalProperties to "no")),
                // A null is how an alteration merged into a global type removes a property: an instruction, kept.
                "gone" to null,
            ),
        )
        val (same, repairs) = repairTypeDef("Type 'client.x.Data'", body, context, altersGlobal = true)
        repairs.shouldBeEmpty()
        (same === body) shouldBe true
    }
}
