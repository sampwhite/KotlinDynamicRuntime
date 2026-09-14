package com.dynamicruntime.common.gedra.workflow

import com.dynamicruntime.common.context.KdrCxt
import com.dynamicruntime.common.exception.KdrException
import com.dynamicruntime.common.gedra.ClientService
import com.dynamicruntime.common.gedra.LogGedra
import com.dynamicruntime.common.startup.SchemaService

/** Names the `cfactCalc` event uses that are not a function's own initialization-data fields. */
@Suppress("ConstPropertyName")
object CFC {
    /**
     * A `ClientDef.testFeatures` name that turns an undeclared emitted cfact from a logged drop into a thrown
     * error (issue #678, decision 8). In the spirit of a `testFeatures` gate: normally set only on a unit-test
     * client, so a real client silently drops a stray cfact while a test can insist one was declared.
     */
    const val strictUnknownCfact = "strictUnknownCfact"
}

/**
 * The **execution** interface of the `cfactCalc` event (issue #678): a resolved [WfFunction] that contributes
 * cfacts to a form's derived state, run inside the per-workflow derived-state recompute #675 drives on every
 * write. It lives in `base:common` because it takes a [KdrCxt] directly, which the kernel cannot see; the kernel
 * holds only the thin [WfFunction] base a definition parses and orders.
 *
 * A concrete function (its executor class and its creation object) lives in its own source file -- see
 * [ComputeCFactsFromDataFn]. The base->event cast is the companion [of] plus the [cfactCalcFns] extension, so the
 * one cast site is event-named: a caller reads `def.cfactCalcFns().forEach { it.computeCfacts(cxt, params) }` and
 * there is no ambiguity about which event is being run.
 */
interface CfactCalcFn : WfFunction {
    /** Fixed for the event; the resolution pass already refused a `cfactCalc` function on a task list. */
    override val event: WfEventType get() = WfEventType.cfactCalc

    /**
     * Reads the form's data from [params] and emits (through [CfactCalcParams.emit]) each cfact it concludes,
     * returning nothing -- output travels in the parameters object, per the workflow-function implementation
     * rule. Called once per write, inside the recompute transaction.
     */
    fun computeCfacts(cxt: KdrCxt, params: CfactCalcParams)

    companion object {
        /**
         * Narrows a resolved [WfFunction] to a [CfactCalcFn]. The resolution second pass has already checked that
         * a function on a `cfactCalc` list is a `cfactCalc` kind, so a failure here is a wiring bug (a creation
         * returning the wrong execution type), not a config error -- hence a raw throw.
         */
        fun of(fn: WfFunction): CfactCalcFn = fn as? CfactCalcFn
            ?: throw KdrException(
                "Workflow function '${fn.fn}' sits on a cfactCalc list but did not resolve to a ${CfactCalcFn::class.simpleName}.",
            )
    }
}

/**
 * What a [CfactCalcFn] is handed on execution (issue #678): the form's trait [entries] to read, and a sink for
 * the cfacts it concludes. A class with an output slot rather than a return value, per the implementation rule
 * that a function's execution method returns void and communicates through its parameters object.
 */
class CfactCalcParams(val entries: List<Map<String, Any?>>) {
    private val collected = LinkedHashSet<String>()

    /** The cfacts emitted so far, in emission order. */
    val emitted: Set<String> get() = collected

    /** Records that [cfact] holds about the form. Idempotent -- a cfact emitted twice counts once. */
    fun emit(cfact: String) {
        collected.add(cfact)
    }
}

/** This definition's resolved `cfactCalc` functions, in priority order -- the workflow-global list A resolved. */
fun WfDef.cfactCalcFns(): List<CfactCalcFn> =
    resolvedFunctions.filter { it.event == WfEventType.cfactCalc }.map { CfactCalcFn.of(it) }

/**
 * Runs [def]'s `cfactCalc` functions over a form's [entries] and returns the cfacts they emit that [client]
 * actually declares (issue #678). This is the per-workflow cfact contribution decision 5 folds into the
 * derived-state recompute: the survey's producer calls it and merges the result into its own form-singleton
 * `cfacts` entry, so there is one aggregation point rather than one `cfacts` entry per producer.
 *
 * A function's **literal** emitted cfacts are already boot-checked -- the resolution pass dropped a function that
 * named an undeclared one -- so the runtime narrowing here only bites a **computed** cfact (a later phase). An
 * undeclared emitted cfact is logged and dropped, unless the form's client opted into [CFC.strictUnknownCfact],
 * which escalates it to a thrown error (normally a unit-test client only).
 */
fun runCfactCalc(cxt: KdrCxt, def: WfDef, entries: List<Map<String, Any?>>, client: String?): Set<String> {
    val fns = def.cfactCalcFns()
    if (fns.isEmpty()) {
        return emptySet()
    }
    val params = CfactCalcParams(entries)
    fns.forEach { it.computeCfacts(cxt, params) }
    if (params.emitted.isEmpty()) {
        return emptySet()
    }
    val declared = SchemaService.get(cxt).cfactsFor(client).names
    val (known, unknown) = params.emitted.partition { it in declared }
    if (unknown.isNotEmpty()) {
        val clientDef = client?.let { ClientService.get(cxt).present(it) }
        val strict = clientDef != null && CFC.strictUnknownCfact in clientDef.testFeatures
        if (strict) {
            throw KdrException.mkConv(
                "A cfactCalc function emitted undeclared cfact(s) ${unknown.sorted()} for client '$client', " +
                    "and this client asks for that to be an error (${CFC.strictUnknownCfact}).",
            )
        }
        LogGedra.warn(cxt) {
            "Dropping cfact(s) ${unknown.sorted()} a cfactCalc function emitted for client '$client' that the " +
                "client does not declare."
        }
    }
    return known.toSet()
}
