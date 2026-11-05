package com.dynamicruntime.common.gedra.workflow

import com.dynamicruntime.common.context.KdrCxt
import com.dynamicruntime.common.exception.KdrException
import com.dynamicruntime.common.user.UserService

/**
 * The **execution** interface of the `viewerCfacts` event (issue #786): a resolved [WfFunction] that concludes
 * **temporary** cfacts about the person viewing a task -- "may this person review it?" -- as the view is
 * assembled. Never stored: the answer differs per viewer, so it could never be a fact about the form, which is
 * what separates this event from the persisting `cfactCalc`.
 *
 * A concrete function lives in its own file (see [UserHasLabelFn]). The base->event cast is the companion [of]
 * plus the [viewerCfactsFns] extension, so the one cast site is event-named.
 */
interface ViewerCfactsFn : WfFunction {
    /** Fixed for the event; the resolution pass already refused a `viewerCfacts` function on the global list. */
    override val event: WfEventType get() = WfEventType.viewerCfacts

    /**
     * Reads what it needs about the viewer from [params] and emits (through [ViewerCfactsParams.emit]) each cfact
     * it concludes, returning nothing -- output travels in the parameters object, per the workflow-function
     * implementation rule. Called once per task as the view resolves.
     */
    fun computeViewerCfacts(cxt: KdrCxt, params: ViewerCfactsParams)

    /**
     * Every cfact this function may emit (issue #1594): what a CTA filter on the same task may name, since these are
     * facts about the person and nothing else.
     */
    val emittedCfacts: Set<String>

    companion object {
        /**
         * Narrows a resolved [WfFunction] to a [ViewerCfactsFn]. The resolution pass already checked the kind, so a
         * failure here is a wiring bug (a creation returning the wrong execution type) -- hence a raw throw.
         */
        fun of(fn: WfFunction): ViewerCfactsFn = fn as? ViewerCfactsFn
            ?: throw KdrException(
                "Workflow function '${fn.fn}' sits on a viewerCfacts list but did not resolve to a ${ViewerCfactsFn::class.simpleName}.",
            )
    }
}

/**
 * What a [ViewerCfactsFn] is handed (issue #786): the viewer's labels -- read only if a function asks, and then
 * once per **view** (see [ViewerCfacts]) -- and a sink for the cfacts it concludes.
 */
class ViewerCfactsParams(labels: () -> Set<String>) {
    /** The viewer's labels -- the acting user's, as an administrator applied them. Empty for a caller with no user row. */
    val viewerLabels: Set<String> by lazy(labels)

    /** The cfacts emitted so far, in emission order. */
    val emitted: Set<String>
        field = LinkedHashSet<String>()

    /** Records that [cfact] holds about the viewer. Idempotent. */
    fun emit(cfact: String) {
        emitted.add(cfact)
    }
}

/** This task's resolved `viewerCfacts` functions, in priority order. */
fun WfTask.viewerCfactsFns(): List<ViewerCfactsFn> =
    resolvedFunctions.filter { it.event == WfEventType.viewerCfacts }.map { ViewerCfactsFn.of(it) }

/**
 * The `viewerCfacts` evaluation for **one piece of work** (issues #786, #1595) on [client]'s workflows, as the person
 * [cxt] acts as: [forTask] answers each task's viewer facts, which the workflow view adds to the task's own target facts
 * so a selector can choose on them (today a task's display selector, issue #788; field-layout selectors are to follow)
 * -- and which the approve endpoint (#787) asks for the same way, so the button a page shows and the permission
 * the endpoint grants are one computation. They are only what the task's functions say: a CTA's filter rule never
 * reshapes them, and is evaluated beside them, by `passesCtaFilterRule`, into a fact of its own.
 *
 * What is about the *viewer* is worked out once per object, however many forms and tasks ask: the labels are read once
 * and each task's facts once. So a caller judging many forms -- a listing of them -- makes **one** and passes it to
 * each form's check, which then costs no lookup per row; the object lives as long as that work and no longer, so it
 * never answers from labels changed since. A task with no functions costs nothing, and a caller with no user row -- a
 * system context, an anonymous one, an identity the request carries without a row behind it -- is answered "no labels"
 * without a lookup, rather than missing the cache into SQL on every view.
 */
class ViewerCfacts(private val cxt: KdrCxt, private val client: String?) {
    private val labels: Set<String> by lazy { viewerLabels(cxt) }
    private val byTask = HashMap<WfTask, Set<String>>()

    /** [task]'s viewer facts: what its `viewerCfacts` functions emit, narrowed to the cfacts [client] declares. */
    fun forTask(task: WfTask): Set<String> = byTask.getOrPut(task) {
        val fns = task.viewerCfactsFns()
        if (fns.isEmpty()) {
            return@getOrPut emptySet()
        }
        val params = ViewerCfactsParams { labels }
        fns.forEach { it.computeViewerCfacts(cxt, params) }
        declaredCfactsOnly(cxt, client, params.emitted, "A viewerCfacts function on task '${task.id}'")
    }
}

/**
 * The labels of the user [cxt] is acting as, read through the `AuthUsers` cache -- or none, without a lookup, when
 * the caller is not backed by a user row (so nobody is a reviewer by default, nor sees a restricted workflow by a
 * label, issue #1384), or the node has no user management.
 */
internal fun viewerLabels(cxt: KdrCxt): Set<String> {
    val profile = cxt.userProfile
    if (!profile.isRowBacked) {
        return emptySet()
    }
    return UserService.getOrNull(cxt)?.queryByUserId(cxt, profile.userId)?.labels?.toSet() ?: emptySet()
}
