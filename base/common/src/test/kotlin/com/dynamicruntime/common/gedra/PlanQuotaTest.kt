package com.dynamicruntime.common.gedra

import com.dynamicruntime.common.context.ENV
import com.dynamicruntime.common.context.KdrCxt
import com.dynamicruntime.common.context.KdrInstanceConfig
import com.dynamicruntime.common.gedra.workflow.WfEntry
import com.dynamicruntime.common.gedra.workflow.WfSaveKind
import com.dynamicruntime.common.naming.clientNamespace
import com.dynamicruntime.common.schema.SCH
import com.dynamicruntime.common.schema.SCT
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe

/**
 * What a plan counts in a client's configuration (issue #1109), and when a change is over it: a type's own attributes,
 * the client's own types and workflows, and a change refused only when it takes a measure over **and** past what the
 * client holds.
 */
class PlanQuotaTest : StringSpec({
    val cxt = KdrCxt("plans", KdrInstanceConfig("plans", ENV.local, ENV.liveSource))

    "a type's attributes are its own properties, at any depth inline, and a reference counts one" {
        val body = mapOf(
            SCH.properties to mapOf(
                "a" to mapOf(SCH.type to SCT.string),
                "b" to mapOf(SCH.dRef to "#/\$defs/kdr.core.Address"),
                "c" to mapOf(SCH.type to SCT.kObject, SCH.properties to mapOf("c1" to mapOf<String, Any?>(), "c2" to mapOf<String, Any?>())),
                "d" to mapOf(SCH.type to SCT.array, SCH.items to mapOf(SCH.type to SCT.kObject, SCH.properties to mapOf("d1" to mapOf<String, Any?>()))),
                "e" to mapOf(SCH.type to SCT.array, SCH.items to mapOf(SCH.dRef to "#/\$defs/kdr.core.Address")),
            ),
        )
        // a, b, c (+ c1, c2), d (+ d1), e.
        schemaAttributeCount(body) shouldBe 8
    }

    "a client's usage is its own workflows and types: not a trait's entry type, a global type's name, or a template's copy" {
        val client = "plan1109"
        val ns = clientNamespace(client)
        val own = gedraConfig(cxt, "main", ns, client) {
            trait("QEntry", "q", setOf(GedraDataType.formDoc)) {
                property("one", "One.")
                property("two", "Two.")
            }
            type("Extra") {
                type = SCT.kObject
                property("x", "X.")
            }
            workflow("w1", WfEntry.normal) {
                task("t1", "T1") {
                    trait("q")
                    save("s1", "S1", WfSaveKind.edit)
                }
                task("t2", "T2") {
                    trait("q")
                    save("s2", "S2", WfSaveKind.edit)
                }
            }
        }
        val copied = GedraConfig(
            gedraId = GedraId.of(GedraConfigType.configDoc, client, "copied"), namespace = ns, traits = emptyMap(),
            defs = mapOf("$ns.Copied" to mapOf<String, Any?>()), inheritedFrom = "template1109",
        )
        val usage = ConfigUsage.of(listOf(own, copied), globalTypes = setOf("$ns.Extra"))
        usage.tasksByWorkflow shouldBe mapOf("w1" to 2)
        // The trait's data type is the client's own; its entry type is generated, `Extra` here reads as an
        // alteration of a global type, and the template's copy is the template's.
        usage.attributesBySchema shouldBe mapOf("$ns.QData" to 2)
    }

    "a change is over only where it goes past both the plan and what the client already holds" {
        val quotas = PlanQuotas(workflows = 4, tasksPerWorkflow = 2, schemas = 10, attributesPerSchema = 10)
        val current = ConfigUsage(mapOf("a" to 3, "b" to 1, "c" to 1, "d" to 1, "e" to 1), mapOf("s" to 12))
        // Already over, and no further: allowed -- a client over its plan may still change and shrink what it has.
        PlanLimits.configOverages(quotas, current, current).shouldBeEmpty()
        PlanLimits.configOverages(quotas, ConfigUsage(mapOf("a" to 3), mapOf("s" to 11)), current).shouldBeEmpty()
        // Any further: refused, each measure said.
        val further = ConfigUsage(current.tasksByWorkflow + ("f" to 3), mapOf("s" to 13))
        PlanLimits.configOverages(quotas, further, current) shouldBe listOf(
            "6 workflows (the plan allows 4)",
            "workflow 'f' with 3 tasks (the plan allows 2)",
            "schema 's' with 13 attributes (the plan allows 10)",
        )
    }

    "a self-serve client is the free prefix's, and a plan that is not one is held to the least" {
        isSelfServeClient("free_x7k2") shouldBe true
        isSelfServeClient("freeco") shouldBe false
        isSelfServeClient("acme_east") shouldBe false
        PLAN.quotasOf(PLAN.unlimited).isLimited shouldBe false
        PLAN.quotasOf("gold").forms shouldBe PLAN.freeQuotas.forms
        PlanUsage.fromInfo(PlanUsage(PLAN.free, listOf(QuotaMeasure(QUOTA.forms, 3, 100))).toInfo()) shouldBe
            PlanUsage(PLAN.free, listOf(QuotaMeasure(QUOTA.forms, 3, 100)))
    }
})
