package com.dynamicruntime.common.schema

import com.dynamicruntime.common.context.KdrCxt
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain

/**
 * Open choice lists: options that suggest rather than bound (issue #418).
 *
 * Every assertion here is a **pair** against the closed equivalent. A test that only showed an open list
 * accepting an off-list value would pass just as well if the options had been dropped altogether, or if the
 * validator had stopped checking options for everybody -- and those are the two ways this could be wrong
 * while looking right. The closed half is what says the check still exists.
 */
class SchOpenOptionsTest : StringSpec({

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
            .single() shouldContain "audit"
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
            .single() shouldContain SCH.openOptions
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

})
