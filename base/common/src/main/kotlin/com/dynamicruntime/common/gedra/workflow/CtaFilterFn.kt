package com.dynamicruntime.common.gedra.workflow

import com.dynamicruntime.common.cfact.CFactParser
import com.dynamicruntime.common.cfact.CFactRegistry
import com.dynamicruntime.common.cfact.analyzeCFactOrAlways
import com.dynamicruntime.common.context.KdrCxt
import com.dynamicruntime.common.exception.KdrException
import com.dynamicruntime.common.schema.LogSchema

/**
 * The **execution** interface of the `ctaFilter` event (issue #1594): a resolved [WfFunction] that concludes, from the
 * form's data, **who** may act on a task while it is its workflow's call to action -- a CTA filter and its label --
 * and **what kind** of action it is -- a search keyword ([CTAK]). Run as the workflow's state is computed, so what it
 * concludes is stored with the CTA and recomputed on every write.
 *
 * The split it exists for: the form's data is folded in here, **on write**, into a filter that names only facts about
 * the person; a listing evaluates that filter **on read** against the viewer's facts, without the form. So a filter
 * may name only what [personFactNames] gives -- the request's facts and the task's viewer facts -- which the
 * resolution pass checks of [possibleFilters] and [ctaAssignment] checks again of what a function actually set.
 *
 * A concrete function lives in its own file (see [CtaFilterByValueFn]). The base->event cast is the companion [of] plus
 * the [ctaFilterFns] extension, so the one cast site is event-named.
 */
interface CtaFilterFn : WfFunction {
    /** Fixed for the event; the resolution pass already refused a `ctaFilter` function on the workflow-global list. */
    override val event: WfEventType get() = WfEventType.ctaFilter

    /**
     * Reads the form's entries from [params] and sets what it concludes through it, returning nothing -- output
     * travels in the parameters object, per the workflow-function implementation rule. Called once per computation
     * of the workflow's state, while the task is the CTA.
     */
    fun computeCtaFilter(cxt: KdrCxt, params: CtaFilterParams)

    /**
     * Every filter this function may set, for the resolution pass to hold to the person's facts before it runs; empty
     * when what it sets cannot be known ahead, and then only [ctaAssignment]'s check applies.
     */
    val possibleFilters: List<String>

    /**
     * The traits this function reads from the form, for the resolution pass to hold to the client's traits: any trait of
     * the form, not only the task's own, since what routes a CTA (a region, say) is usually collected earlier.
     */
    val readTraits: Set<String>

    companion object {
        /**
         * Narrows a resolved [WfFunction] to a [CtaFilterFn]. The resolution pass already checked the kind, so a
         * failure here is a wiring bug (a creation returning the wrong execution type) -- hence a raw throw.
         */
        fun of(fn: WfFunction): CtaFilterFn = fn as? CtaFilterFn
            ?: throw KdrException(
                "Workflow function '${fn.fn}' sits on a ctaFilter list but did not resolve to a ${CtaFilterFn::class.simpleName}.",
            )
    }
}

/**
 * What a [CtaFilterFn] is handed (issue #1594): the form's [entries], and slots for what it concludes. The first
 * function to set a slot decides it, in priority order, so a later function fills only what an earlier left open.
 */
class CtaFilterParams(val entries: List<Map<String, Any?>>) {
    var filter: String? = null
        private set
    var label: String? = null
        private set
    var keyword: String? = null
        private set

    /** Sets the filter and its [label], unless an earlier function already set a filter. */
    fun setFilter(filter: String, label: String?) {
        if (this.filter != null) return
        this.filter = filter
        this.label = label
    }

    /** Sets the search keyword ([CTAK]), unless an earlier function already set one. */
    fun setKeyword(keyword: String) {
        if (this.keyword == null) this.keyword = keyword
    }
}

/** This task's resolved `ctaFilter` functions, in priority order. */
fun WfTask.ctaFilterFns(): List<CtaFilterFn> =
    resolvedFunctions.filter { it.event == WfEventType.ctaFilter }.map { CtaFilterFn.of(it) }

/**
 * The facts a CTA filter on [task] may name (issue #1594): [requestNames], the ones computed from the request alone
 * (`CFactRegistry.requestNames`), and the ones the task's resolved `viewerCfacts` functions may emit -- facts about the
 * person, and so the only ones a listing can evaluate without the form.
 */
fun personFactNames(requestNames: Set<String>, task: WfTask): Set<String> =
    requestNames + task.viewerCfactsFns().flatMap { it.emittedCfacts }

/**
 * Why [filter] cannot be a CTA filter, or null when it can (issue #1594): it parses against [declared], the cfacts its
 * scope may write, and every fact it names is among [personFacts] ([personFactNames]). Shared by the resolution pass
 * and [ctaAssignment], so a filter is held to one rule whether it is checked before it runs or after.
 */
fun ctaFilterProblem(declared: Set<String>, personFacts: Set<String>, filter: String): String? {
    analyzeCFactOrAlways(filter, declared).problems.firstOrNull()?.let { return "does not parse: ${it.message}" }
    // The names as the cfact grammar reads them, not a word scan, which would split a rooted name at its colon.
    val notAboutThePerson = CFactParser.analyze(filter, emptySet()).names - personFacts
    return if (notAboutThePerson.isEmpty()) {
        null
    } else {
        "names ${notAboutThePerson.sorted()}, which are not facts about the person (the request's, or what a " +
            "viewerCfacts function on the task emits), so a listing could not evaluate it without the form"
    }
}

/** What a workflow's CTA carries (issue #1594): its search [keyword], and who may act on it -- [filter], with its [label]. */
class CtaAssignment(val keyword: String?, val filter: String?, val label: String?)

/**
 * What [task], as its workflow's CTA, carries for a form with [entries] (issue #1594): its `ctaFilter` functions run in
 * priority order, then the task's own keyword ([WfTask.ctaKeyword]) where none set one. A filter naming anything but
 * facts about the person is dropped with a warning -- reported, never stored -- since a listing could not evaluate it;
 * the resolution pass refuses such a filter before it runs wherever it can be known ahead.
 */
fun ctaAssignment(cxt: KdrCxt, registry: CFactRegistry, workflowId: String, task: WfTask, entries: List<Map<String, Any?>>): CtaAssignment {
    val params = CtaFilterParams(entries)
    task.ctaFilterFns().forEach { it.computeCtaFilter(cxt, params) }
    val filter = params.filter?.takeIf { filter ->
        val problem = ctaFilterProblem(registry.names, personFactNames(registry.requestNames, task), filter)
            ?: return@takeIf true
        LogSchema.warn(cxt) {
            "Workflow '$workflowId' task '${task.id}' concluded the CTA filter '$filter', which $problem. Not storing it."
        }
        false
    }
    // A keyword is held to the closed list as a task's own is; a function's that is not one is dropped, with a warning
    // as a bad filter is, and the task's own stands.
    val keyword = params.keyword?.takeIf { keyword ->
        (keyword in CTAK.all).also { known ->
            if (!known) {
                LogSchema.warn(cxt) {
                    "Workflow '$workflowId' task '${task.id}' concluded the keyword '$keyword', which is not a search keyword " +
                        "(${CTAK.all.joinToString(", ")}). Not storing it."
                }
            }
        }
    } ?: task.ctaKeyword
    return CtaAssignment(keyword, filter, filter?.let { params.label })
}
