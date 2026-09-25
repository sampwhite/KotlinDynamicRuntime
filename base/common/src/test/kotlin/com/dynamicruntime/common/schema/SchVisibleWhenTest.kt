package com.dynamicruntime.common.schema

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain

/**
 * `g-visibleWhen` is evaluated on the frontend (issue #564) and enforced on trait-data writes (issue #830). These
 * cover [visibleWhenProblems], the boot-time walk that hands each declared expression to a [check] and locates
 * whatever it reports -- it finds a gated field, nests, and leaves an unproblematic or ungated document alone --
 * with a plain stand-in for the parse/delivery check the service supplies; and [keepGatedFields], the write rule,
 * at the depths the sample's one flat field does not reach.
 */
class SchVisibleWhenTest : StringSpec({

    "an expression the check faults is reported, with its location and the returned detail" {
        val node: Map<String, Any?> = linkedMapOf(
            SCH.properties to linkedMapOf(
                "bad" to linkedMapOf(SCH.type to SCT.string, SCH.visibleWhen to "a &&& b"),
                "ok" to linkedMapOf(SCH.type to SCT.string, SCH.visibleWhen to "hasAdminLevel"),
            ),
        )
        val problems = visibleWhenProblems("Type 'X'", node) { expression ->
            if (expression.contains("&&&")) "does not parse" else null
        }
        problems.size shouldBe 1
        problems.single() shouldContain "Type 'X'"
        problems.single() shouldContain "a &&& b"
        problems.single() shouldContain "does not parse"
    }

    "a check that faults nothing yields no problems, and an ungated document never consults it" {
        val gated: Map<String, Any?> = linkedMapOf(
            SCH.properties to linkedMapOf("f" to linkedMapOf(SCH.visibleWhen to "hasAdminLevel")),
        )
        visibleWhenProblems("w", gated) { null } shouldBe emptyList()

        val plain: Map<String, Any?> = linkedMapOf(
            SCH.properties to linkedMapOf("f" to linkedMapOf(SCH.type to SCT.string)),
        )
        visibleWhenProblems("w", plain) { error("check must not run when nothing is gated") } shouldBe emptyList()
    }

    "the walk reaches an expression nested inside another object" {
        val nested: Map<String, Any?> = linkedMapOf(
            SCH.properties to linkedMapOf(
                "inner" to linkedMapOf(
                    SCH.type to SCT.kObject,
                    SCH.properties to linkedMapOf(
                        "deep" to linkedMapOf(SCH.type to SCT.string, SCH.visibleWhen to "deepExpr"),
                    ),
                ),
            ),
        )
        val seen = mutableListOf<String>()
        visibleWhenProblems("w", nested) { seen.add(it); null }
        seen shouldBe listOf("deepExpr")
    }

    // A gate on a required property (issue #564): the field is hidden client-side but still required by the
    // served schema, so a caller it hides could never submit -- refused at boot.
    "a required property that declares g-visibleWhen is refused; an optional one is fine" {
        fun obj(required: List<String>): Map<String, Any?> = linkedMapOf(
            SCH.type to SCT.kObject,
            SCH.properties to linkedMapOf(
                "name" to linkedMapOf(SCH.type to SCT.string),
                "secret" to linkedMapOf(SCH.type to SCT.string, SCH.visibleWhen to "hasAdminLevel"),
            ),
            SCH.required to required,
        )
        val gatedRequired = requiredVisibleWhenProblems("Type 'X'", obj(listOf("name", "secret")))
        gatedRequired.size shouldBe 1
        gatedRequired.single() shouldContain "secret"
        // Gated but optional: nothing.
        requiredVisibleWhenProblems("Type 'X'", obj(listOf("name"))) shouldBe emptyList()
    }

    "the required-gate check reaches a nested object" {
        val nested: Map<String, Any?> = linkedMapOf(
            SCH.type to SCT.kObject,
            SCH.properties to linkedMapOf(
                "inner" to linkedMapOf(
                    SCH.type to SCT.kObject,
                    SCH.properties to linkedMapOf(
                        "deep" to linkedMapOf(SCH.type to SCT.string, SCH.visibleWhen to "hasAdminLevel"),
                    ),
                    SCH.required to listOf("deep"),
                ),
            ),
        )
        requiredVisibleWhenProblems("w", nested).size shouldBe 1
    }

    // A type with a gated field at the top, inside a nested object, and inside a list's elements -- all gated on
    // "admin", which the caller below fails.
    val gatedType: SchType = parseSchemaTypes(
        mapOf(
            "t.Note" to linkedMapOf(
                SCH.type to SCT.kObject,
                SCH.properties to linkedMapOf(
                    "title" to linkedMapOf(SCH.type to SCT.string),
                    "note" to linkedMapOf(SCH.type to SCT.string, SCH.visibleWhen to "admin"),
                    "detail" to linkedMapOf(
                        SCH.type to SCT.kObject,
                        SCH.properties to linkedMapOf(
                            "open" to linkedMapOf(SCH.type to SCT.string),
                            "secret" to linkedMapOf(SCH.type to SCT.string, SCH.visibleWhen to "admin"),
                        ),
                    ),
                    "lines" to linkedMapOf(
                        SCH.type to SCT.array,
                        SCH.items to linkedMapOf(
                            SCH.type to SCT.kObject,
                            SCH.properties to linkedMapOf(
                                "text" to linkedMapOf(SCH.type to SCT.string),
                                "secret" to linkedMapOf(SCH.type to SCT.string, SCH.visibleWhen to "admin"),
                            ),
                        ),
                    ),
                ),
            ),
        ),
        existingTypes = emptyMap(),
    ).getValue("t.Note")
    val failsAdmin: (String) -> Boolean = { it != "admin" }
    val stored: Map<String, Any?> = mapOf(
        "title" to "T", "note" to "N",
        "detail" to mapOf("open" to "o", "secret" to "s"),
        "lines" to listOf(mapOf("text" to "a", "secret" to "x"), mapOf("text" to "b")),
    )

    "a caller who fails a gate keeps every stored gated value it left out, at any depth" {
        val sent = mapOf(
            "title" to "T2",
            "detail" to mapOf("open" to "o2"),
            "lines" to listOf(mapOf("text" to "a2"), mapOf("text" to "b2")),
        )
        val result = keepGatedFields(gatedType, stored, sent, failsAdmin)
        result.refused shouldBe emptyList()
        result.data shouldBe mapOf(
            "title" to "T2", "note" to "N",
            "detail" to mapOf("open" to "o2", "secret" to "s"),
            "lines" to listOf(mapOf("text" to "a2", "secret" to "x"), mapOf("text" to "b2")),
        )
    }

    "a changed gated value is refused wherever it sits, and a new entry has nothing to keep" {
        val sent = mapOf(
            "note" to "N",
            "detail" to mapOf("secret" to "changed"),
            "lines" to listOf(mapOf("secret" to "x"), mapOf("text" to "b", "secret" to "added")),
        )
        keepGatedFields(gatedType, stored, sent, failsAdmin).refused shouldBe listOf("detail.secret", "lines[1].secret")
        keepGatedFields(gatedType, null, mapOf("note" to "N"), failsAdmin).refused shouldBe listOf("note")
    }

    "a caller who passes the gate writes as sent, and the gate is asked only about gated fields" {
        val asked = mutableListOf<String>()
        val sent = mapOf("title" to "T", "note" to "new")
        val result = keepGatedFields(gatedType, stored, sent, { asked.add(it); true })
        result.refused shouldBe emptyList()
        result.data shouldBe sent
        asked.toSet() shouldBe setOf("admin")
    }
})
