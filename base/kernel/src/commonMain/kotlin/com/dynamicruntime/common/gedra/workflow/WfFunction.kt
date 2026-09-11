package com.dynamicruntime.common.gedra.workflow

import com.dynamicruntime.common.exception.KdrException
import com.dynamicruntime.common.schema.JsonMappable
import com.dynamicruntime.common.util.toOptStr

/** Whether an event fires for the whole workflow or per task -- which list a function of it lives in. */
@Suppress("EnumEntryName")
enum class WfEventScope { global, task }

/**
 * The workflow events a function can attach to (issue #677). Each declares its [scope], which fixes whether a
 * function of it lives on a `WfDef` (global) or a `WfTask` (task) -- the attachment-by-scope rule.
 *
 * The **execution** of each event lives in `base:common` (the event's execution interface, which takes a
 * `KdrCxt`); the kernel needs only the identity and scope, so a definition can be parsed, ordered and
 * serialized without seeing how an event runs.
 */
@Suppress("EnumEntryName")
enum class WfEventType(val scope: WfEventScope) {
    /** Contributes cfacts to a form's derived state, recomputed on every write (issue #678). Global. */
    cfactCalc(WfEventScope.global),

    /** Supplies default values for a task's fields as the view is assembled (issue #679). Per task. */
    prefillData(WfEventScope.task),
}

/**
 * A **usage** of a workflow function as a definition declares it (issue #677): the raw `{fn, priority?, ...}`
 * initialization data, and nothing more. This is what a `WfDef` / `WfTask` parses and serializes -- it is pure
 * kernel data, because the function's *kind* (its schema, its execution) is defined in `base:common` and is not
 * known until every component has registered.
 *
 * A usage is turned into a runnable [WfFunction] by a **second pass** in `base:common` (once the creation
 * registry is complete), which fills the resolved store on the def/task -- the two-pass initialization
 * `kd2-design/thoughts-workflow-functions.md` describes, and the shape Cedar used for the same reason. So the
 * usage deliberately does **not** know its [WfEventType]: which event a `fn` belongs to is a `base:common` fact,
 * settled at resolution, where the scope-matches-placement check also runs.
 */
class WfFunctionUsage(val initData: Map<String, Any?>) : JsonMappable {
    /** The function-kind discriminator, e.g. `computeCFactsFromData`. */
    val fn: String = initData[WFD.fn].toOptStr()
        ?: throw KdrException.mkConv("A workflow function usage has no '${WFD.fn}' to say which function it is.")

    /** Run order within a scope's list -- lower first, ties keeping declaration order. Zero when unstated. */
    val priority: Int = (initData[WFD.priority] as? Number)?.toInt() ?: 0

    /** The usage is its own initialization data, so a code-built and a stored definition round-trip identically. */
    override fun toJsonMap(): Map<String, Any?> = initData
}

/**
 * A resolved, runnable workflow function (issue #677) -- what the `base:common` second pass builds from a
 * [WfFunctionUsage] and stores on a `WfDef` / `WfTask`.
 *
 * A function's *execution* interface lives in `base:common` and takes a `KdrCxt`, which the kernel cannot see;
 * so this base is thin -- enough to identify it ([fn]), place it ([event] and its [WfEventType.scope]) and order
 * it ([priority]). Common narrows a `WfFunction` to its true event interface with that interface's companion
 * `of` and a `WfDef.<event>Fns()` extension (in the `base:common` workflow-function package -- not linked here,
 * since Dokka cannot cross into `base:common`).
 */
interface WfFunction {
    /** The function-kind discriminator this was built from -- the usage's `fn`. */
    val fn: String

    /** The event this function attaches to; its [WfEventType.scope] fixes whether it belongs on a def or a task. */
    val event: WfEventType

    /** Run order within an event's list -- lower first, ties keeping declaration order. */
    val priority: Int
}
