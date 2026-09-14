package com.dynamicruntime.common.gedra.workflow

import com.dynamicruntime.common.context.KdrCxt
import com.dynamicruntime.common.exception.KdrException
import com.dynamicruntime.common.gedra.GE
import com.dynamicruntime.common.schema.SCT
import com.dynamicruntime.common.schema.SchType
import com.dynamicruntime.common.schema.coerceAndValidate
import com.dynamicruntime.common.schema.parseSchemaTypes
import com.dynamicruntime.common.schema.schemaDefs
import com.dynamicruntime.common.util.pluckDataPath
import com.dynamicruntime.common.util.toJsonListOfMaps
import com.dynamicruntime.common.util.toJsonMapOrEmpty
import com.dynamicruntime.common.util.toOptStr

/** The `computeCFactsFromData` function's name, its initialization-data fields, and its schema names (issue #678). */
@Suppress("ConstPropertyName")
object CFD {
    /** The `fn` discriminator this function is declared under. */
    const val fn = "computeCFactsFromData"

    /** The trait whose entry data is read. */
    const val trait = "trait"

    /** What is done with the value at [valuePath]; a closed set, [isInList] the only member today. */
    const val op = "op"

    /** [op]: [valuePath] resolves to a list, and a mapping's [value] is emitted as its [cfact] when it is in that list. */
    const val isInList = "isInList"

    /** A dotted path into the trait entry's data, resolved with array-spread, whose value the [op] tests. */
    const val valuePath = "valuePath"

    /** The list of `{value, cfact}` pairs the [op] tests against the value. */
    const val valueMapping = "valueMapping"

    /** Under a [valueMapping] entry: the data value to test for. */
    const val value = "value"

    /** Under a [valueMapping] entry: the cfact to emit when [value] matches. */
    const val cfact = "cfact"

    /** The schema namespace and type names for this function's initialization data. */
    const val namespace = "wffncfd"
    const val initDataType = "ComputeCFactsFromData"
    const val mappingType = "CFDMapping"
}

/**
 * The first `cfactCalc` executor (issue #678): read a trait entry's `data`, [pluckDataPath] a `valuePath` (with
 * array-spread), and under `op: isInList` emit each `valueMapping` entry's `cfact` whose `value` is in the
 * plucked list. The definition example the design gives -- a `projects` trait's `projectChoices` list mapped to
 * per-project cfacts.
 *
 * Definition is data, computation is Kotlin: this class is the computation, built from validated initialization
 * data by [ComputeCFactsFromDataCreation]. A value not in any mapping (or a trait/path that is absent) emits
 * nothing rather than faulting -- a form part-way through its survey is the normal case, not an error.
 */
class ComputeCFactsFromDataFn(
    override val priority: Int,
    private val trait: String,
    private val op: String,
    private val valuePath: String,
    /** `value` to `cfact`, in declaration order. */
    private val valueMapping: List<Pair<String, String>>,
) : CfactCalcFn {
    override val fn: String = CFD.fn

    override fun computeCfacts(cxt: KdrCxt, params: CfactCalcParams) {
        val entry = params.entries.firstOrNull { it[GE.traitId].toOptStr() == trait } ?: return
        @Suppress("MoveVariableDeclarationIntoWhen") val plucked = pluckDataPath(entry[GE.data].toJsonMapOrEmpty(), valuePath)
        // isInList works over a list; a spread path already yields one, and a bare scalar is read as a
        // one-element list, so a single-valued field still maps.
        val values = when (plucked) {
            null -> return
            is List<*> -> plucked.mapNotNull { it.toOptStr() }.toSet()
            else -> setOf(plucked.toOptStr() ?: return)
        }
        when (op) {
            CFD.isInList -> valueMapping.forEach { (v, cfact) -> if (v in values) params.emit(cfact) }
        }
    }
}

/**
 * Builds a [ComputeCFactsFromDataFn] from a usage's initialization data (issue #678), validating and coercing it
 * against [initDataType] and throwing -- which the resolution pass turns into a config problem -- when it does
 * not conform. Registered in `CommonComponent` through `SchemaCollector.addWorkflowFunction`.
 */
object ComputeCFactsFromDataCreation : WfFunctionCreation {
    override val fn: String = CFD.fn
    override val event: WfEventType = WfEventType.cfactCalc

    override fun create(cxt: KdrCxt, usage: WfFunctionUsage): WfFunction {
        val result = coerceAndValidate(initDataType(cxt), usage.initData)
        if (result.failures.isNotEmpty()) {
            throw KdrException.mkConv(
                "'${CFD.fn}' initialization data is invalid: " +
                    result.failures.joinToString("; ") { "${it.path.ifEmpty { "(root)" }}: ${it.message}" },
            )
        }
        val m = result.value.toJsonMapOrEmpty()
        return ComputeCFactsFromDataFn(
            priority = usage.priority,
            trait = m[CFD.trait].toOptStr() ?: "",
            op = m[CFD.op].toOptStr() ?: CFD.isInList,
            valuePath = m[CFD.valuePath].toOptStr() ?: "",
            valueMapping = mappingsOf(m[CFD.valueMapping]),
        )
    }

    /** The literal cfacts the mapping emits, boot-checked against the client's declared cfacts. */
    override fun emittedCfacts(usage: WfFunctionUsage): Set<String> =
        mappingsOf(usage.initData[CFD.valueMapping]).map { it.second }.toSet()

    /** The one trait this function reads, for the admission check that the client supports it. */
    override fun referencedTraits(usage: WfFunctionUsage): Set<String> =
        usage.initData[CFD.trait].toOptStr()?.let { setOf(it) } ?: emptySet()

    private fun mappingsOf(raw: Any?): List<Pair<String, String>> =
        raw.toJsonListOfMaps().mapNotNull { entry ->
            val v = entry[CFD.value].toOptStr()
            val c = entry[CFD.cfact].toOptStr()
            if (v != null && c != null) v to c else null
        }

    // Parsed once and kept: the schema is a constant of the runtime, matching how WfDefSchema keeps its own.
    private var parsed: SchType? = null

    private fun initDataType(cxt: KdrCxt): SchType =
        parsed ?: parseSchemaTypes(
            schemaDefs(cxt, CFD.namespace) {
                type(CFD.mappingType) {
                    type = SCT.kObject
                    description = "One value-to-cfact mapping: emit the cfact when the value is in the plucked list."
                    property(CFD.value, "The data value to test for.", required = true)
                    property(CFD.cfact, "The cfact to emit when the value matches.", required = true)
                }
                type(CFD.initDataType) {
                    type = SCT.kObject
                    description = "Emit cfacts from a trait entry's data by mapping listed values to cfacts."
                    property(WFD.fn, "The function discriminator; always '${CFD.fn}' here.", required = true)
                    property(WFD.priority, "Run order within the workflow's cfactCalc list.") { type = SCT.integer }
                    property(CFD.trait, "The trait whose entry data is read.", required = true)
                    property(CFD.op, "What is tested against the plucked value.") { option(CFD.isInList) }
                    property(CFD.valuePath, "A dotted path into the trait entry's data, resolved with array-spread.", required = true)
                    property(CFD.valueMapping, "The value-to-cfact mappings.", required = true) {
                        type = SCT.array
                        allowCoerce = true
                        items { ref(CFD.mappingType) }
                    }
                }
            },
        ).getValue("${CFD.namespace}.${CFD.initDataType}").also { parsed = it }
}

/**
 * Authors a [CFD.fn] usage's initialization data as JSON (issue #678) -- the first per-`fn` DSL builder, used
 * inside a `workflow { function(computeCFactsFromData { ... }) }` block. It emits the same shape
 * [ComputeCFactsFromDataCreation] parses, so a code-built and a stored usage are indistinguishable downstream.
 */
class ComputeCFactsFromDataBuilder {
    /** The trait whose entry data is read. */
    var trait: String = ""

    /** A dotted path into the trait entry's data. */
    var valuePath: String = ""

    /** The operation; [CFD.isInList] by default. */
    var op: String = CFD.isInList

    /** Run order within the workflow's cfactCalc list; unset leaves it at the default. */
    var priority: Int? = null

    private val mappings = mutableListOf<Map<String, Any?>>()

    /** Emit [cfact] when [value] is in the plucked list. */
    fun map(value: String, cfact: String) {
        mappings.add(linkedMapOf(CFD.value to value, CFD.cfact to cfact))
    }

    /** The usage's initialization data, ready for `function(...)`. */
    fun build(): Map<String, Any?> = buildMap {
        put(WFD.fn, CFD.fn)
        priority?.let { put(WFD.priority, it) }
        put(CFD.trait, trait)
        put(CFD.op, op)
        put(CFD.valuePath, valuePath)
        put(CFD.valueMapping, mappings.toList())
    }
}

/** Builds [CFD.fn] initialization data with [ComputeCFactsFromDataBuilder]; see it. */
fun computeCFactsFromData(build: ComputeCFactsFromDataBuilder.() -> Unit): Map<String, Any?> =
    ComputeCFactsFromDataBuilder().apply(build).build()
