package com.dynamicruntime.common.gedra.workflow

import com.dynamicruntime.common.cfact.parseCFactOrAlways
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe

/**
 * The action text behind a Needs Review / Finished chip (issue #789): the CTA task's display branch for the caller,
 * in words, from stored state alone -- so a branch whose text still needs the form's data yields the task's own words.
 */
class SingletonWorkflowsTest : StringSpec({
    val names = setOf(WFC.reviewer)
    val approve = WfTask(
        id = "approve",
        label = "Approve",
        traits = emptyList(),
        saves = emptyList(),
        approval = WfApproval("auditApproved", "Read it first.", "Approve the audit"),
        display = WfDisplayBuilder().apply {
            whenCfacts(WFC.reviewer) { defaultRendering() }
            otherwise { text("Waiting for a reviewer.") }
        }.build(),
    )
    fun actionText(task: WfTask, facts: Set<String>) =
        SingletonWorkflows.actionText(task, facts, { parseCFactOrAlways(it, names) }, { it })

    "a text branch gives its text, and the task's own rendering its approval button" {
        actionText(approve, emptySet()) shouldBe "Waiting for a reviewer."
        actionText(approve, setOf(WFC.reviewer)) shouldBe "Approve the audit"
    }

    "a text still needing the form's data gives the task's own words, not the template" {
        val templated = WfTask(
            id = "record", label = "Record the audit", traits = emptyList(), saves = emptyList(),
            display = WfDisplayBuilder().apply { otherwise { text("Waiting on \${siteName}.") } }.build(),
        )
        actionText(templated, emptySet()) shouldBe "Record the audit"
        SingletonWorkflows.needsFormData("Plain copy, 100% done.") shouldBe false
    }
})
