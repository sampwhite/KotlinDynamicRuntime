package com.dynamicruntime.common.gedra.workflow

import com.dynamicruntime.common.cfact.CFactRegistry
import com.dynamicruntime.common.exception.KdrException

/**
 * Evaluating a normal workflow's **singleton-cfact rules** (issue #784): which of the framework's singleton
 * cfacts ([WSC]) a workflow contributes to the form, given its current cfacts.
 *
 * The rule is a plain cfact expression, so this is the same evaluation eligibility runs; what differs is the
 * direction a broken rule fails in. An eligibility test that no longer parses counts as a *failure*, keeping
 * a form out; a singleton rule that no longer parses **emits nothing**, since a `Needs Review` or `Finished`
 * chip nobody earned is the wrong way to be wrong. The boot check refuses such a workflow, so either is only
 * the backstop.
 */
object WorkflowSingletons {
    /** The singleton cfacts [def]'s rules emit over [facts], in rule order. */
    fun emitted(registry: CFactRegistry, def: WfDef, facts: Set<String>): List<String> =
        def.singletons.filter { rule ->
            try {
                registry.parse(rule.whenExpr).matches(facts)
            } catch (_: KdrException) {
                false
            }
        }.map { it.cfact }
}
