package com.dynamicruntime.common.schema

import com.dynamicruntime.common.context.KdrCxt
import com.dynamicruntime.common.exception.KdrException
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.assertions.withClue
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain

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
class SchVisibleOnlyTest : StringSpec({

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

    "declared on a non-string type, it fails the parse by name" {
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

    "declared on a date-format string, it fails the parse: the value is parsed as a date, not read as text" {
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
})
