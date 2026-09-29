package com.dynamicruntime.common.schema

import com.dynamicruntime.common.exception.KdrException
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain

/**
 * Our own `g-` keywords are strict (issue #822): a `g-` key that is not one of ours, or one of ours whose value has
 * the wrong shape, fails the parse by name -- where it used to be ignored, or read leniently back to a default.
 * Keys without the prefix stay a document's own business.
 */
class SchGKeywordsTest : StringSpec({

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
})
