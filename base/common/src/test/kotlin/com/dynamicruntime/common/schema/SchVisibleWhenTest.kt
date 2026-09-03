package com.dynamicruntime.common.schema

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain

/**
 * `g-visibleWhen` is evaluated on the frontend (issue #564), so nothing resolves it on the backend; all that
 * remains here is [visibleWhenProblems], the boot-time walk that hands each declared expression to a [check]
 * and locates whatever it reports. These cover the walk -- it finds a gated field, nests, and leaves an
 * unproblematic or ungated document alone -- with a plain stand-in for the parse/delivery check the service
 * supplies.
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
})
