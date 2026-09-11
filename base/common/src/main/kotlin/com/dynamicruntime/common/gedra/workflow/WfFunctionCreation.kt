package com.dynamicruntime.common.gedra.workflow

import com.dynamicruntime.common.context.KdrCxt

/**
 * The `base:common` half of a workflow function kind (issue #677): the Kotlin that turns a kernel
 * [WfFunctionUsage] -- its `{fn, ...}` initialization data -- into a runnable [WfFunction].
 *
 * Registered through `SchemaCollector.addWorkflowFunction`, the seam a state deriver and a cfact source
 * register through, and organized by `WorkflowService` into the creation maps the resolution second pass builds
 * against (see [resolveWorkflowFunctions]). "Definition is data; computation is Kotlin": the usage is data, this
 * is the code -- a concrete creation object lives in its function's own source file, beside its execution class
 * and its initialization-data schema.
 */
interface WfFunctionCreation {
    /** The discriminator this builds -- the `fn` of the usages it handles, unique across every function kind. */
    val fn: String

    /** The event (and [WfEventType.scope]) functions of this kind attach to. */
    val event: WfEventType

    /**
     * Builds a runnable function from [usage], **validating and coercing** its initialization data against this
     * kind's own schema and throwing when it does not conform -- the resolution pass turns that into a config
     * problem. The result is an instance of this event's execution interface, so it is-a [WfFunction].
     */
    fun create(cxt: KdrCxt, usage: WfFunctionUsage): WfFunction

    /**
     * The trait ids [usage] reads, for the admission check that a client actually supports them; empty when it
     * reads none, or when the set is not statically knowable (checked at runtime instead).
     */
    fun referencedTraits(usage: WfFunctionUsage): Set<String> = emptySet()

    /**
     * The cfacts [usage] emits as **literals**, for the check that the client declared them; empty when it emits
     * none or computes them, which the design catches at runtime rather than at boot.
     */
    fun emittedCfacts(usage: WfFunctionUsage): Set<String> = emptySet()
}
