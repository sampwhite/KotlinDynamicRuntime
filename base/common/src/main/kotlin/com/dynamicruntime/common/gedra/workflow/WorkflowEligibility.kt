package com.dynamicruntime.common.gedra.workflow

import com.dynamicruntime.common.cfact.CFactRegistry
import com.dynamicruntime.common.content.MarkdownFragmentService
import com.dynamicruntime.common.context.KdrCxt
import com.dynamicruntime.common.exception.KdrException
import com.dynamicruntime.common.gedra.GE
import com.dynamicruntime.common.gedra.GT
import com.dynamicruntime.common.util.toJsonMapOrEmpty
import com.dynamicruntime.common.util.toOptStr

/**
 * Evaluating a normal workflow's **eligibility tests** against a form (issue #783).
 *
 * ### What the tests see
 *
 * Only the **form's own cfacts** -- the [GT.cfacts] state the form's derivers emit (today the survey's two facts
 * and its `cfactCalc` output). Deliberately not the request-scoped cfacts `assembleFormCfacts` adds: eligibility
 * is stored, and computed inside whatever write triggered the recompute, so a caller's cfacts would make a form's
 * stored eligibility depend on *who happened to save it* -- and a batch job (#793) has no caller at all. A fact
 * about the owner that eligibility needs arrives as a form cfact (#784, #786), where it is the form's.
 *
 * ### Every test, every time
 *
 * No short-circuit: each failing test contributes its reason, so a person sees everything standing between
 * the form and the workflow at once rather than one reason per attempt.
 */
object WorkflowEligibility {
    /**
     * The cfacts a form's state [entries] assert: the facts of its [GT.cfacts] entries. The workflow deriver
     * passes the entries derived *this pass* (so they are current with the write); the engage gate passes the
     * stored state, which every write has already recomputed.
     */
    fun formFacts(entries: List<Map<String, Any?>>): Set<String> = entries
        .filter { it[GE.traitId].toOptStr() == GT.cfacts }
        .flatMap { (it[GE.data].toJsonMapOrEmpty()[GT.facts] as? List<*>).orEmpty().mapNotNull { f -> f.toOptStr() } }
        .toSet()

    /**
     * The ids of [def]'s eligibility tests that [facts] fails, in declaration order; empty means eligible.
     *
     * A test that no longer parses against [registry] **fails closed** -- counted as a failure, not skipped. The
     * boot check refuses such a workflow, so this is the belt to that brace (a client reload that removed a
     * cfact, say), and the safe direction is the one that keeps a form out of a workflow it might not qualify
     * for rather than letting it in.
     */
    fun failures(registry: CFactRegistry, def: WfDef, facts: Set<String>): List<String> =
        def.eligibility.filterNot { test ->
            try {
                registry.parse(test.test).matches(facts)
            } catch (_: KdrException) {
                false
            }
        }.map { it.id }

    /**
     * The stored form of [failureIds] (issue #783): each a [WFS.workflowEligibilityFailure] naming its test by id,
     * with the values captured for its explanation -- none yet, until ambient capture arrives.
     */
    fun failureEntries(failureIds: List<String>): List<Map<String, Any?>> =
        failureIds.map { linkedMapOf(WFD.id to it, WFS.captured to emptyMap<String, Any?>()) }

    /**
     * The reasons [failureIds] stand for, in order: each test's explanation found again **by id** on [def] and
     * run through the backend pass, as a label is. An id the definition no longer has (reworded away since the
     * state was stored) is left out -- there is no text left to say it with -- and an explanation whose pull
     * cannot resolve falls back to its raw text rather than failing whatever is presenting it.
     */
    fun explain(cxt: KdrCxt, def: WfDef, failureIds: List<String>): List<String> {
        val byId = def.eligibility.associateBy { it.id }
        val fragments = MarkdownFragmentService.get(cxt)
        return failureIds.mapNotNull { id ->
            val test = byId[id] ?: return@mapNotNull null
            try {
                fragments.backendPass(cxt, test.explanation)
            } catch (_: KdrException) {
                test.explanation
            }
        }
    }
}
