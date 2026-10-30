package com.dynamicruntime.common.schema

import com.dynamicruntime.common.context.KdrCxt
import com.dynamicruntime.common.exception.KdrException
import com.dynamicruntime.common.gedra.GCFG
import com.dynamicruntime.common.gedra.GE
import com.dynamicruntime.common.gedra.GU
import com.dynamicruntime.common.util.toJsonMapOrEmpty
import com.dynamicruntime.common.context.KdrCxtBase

/**
 * The `base:common` half of a schema function kind (issue #1501): the Kotlin that turns a kernel [SchFunctionUsage]
 * -- a `{fn, ...}` object in a type's `g-onWrite` -- into a runnable function, as a `WfFunctionCreation` does for a
 * workflow's. "Definition is data, computation is Kotlin": a type declares the usage, which a stored or
 * client-designed type can do as well as source code, and a component registers the kind
 * (`SchemaCollector.addSchemaFunction`).
 *
 * A kind declares the schema of its usages' settings ([initDataTypeName], [initDataDefs]), which is what a usage is
 * validated against and what the catalog publishes, so the two cannot disagree.
 */
interface SchFunctionCreation {
    /** The discriminator this builds -- the `fn` of the usages it handles, unique across every schema function kind. */
    val fn: String

    /** The life-cycle event functions of this kind run at; a usage of it is listed under that event's keyword. */
    val event: SchEventType

    /** The qualified name of the type a usage's settings are validated against, and published under. */
    val initDataTypeName: String

    /** The `$defs` declaring [initDataTypeName] and anything it references, each under its qualified name. */
    fun initDataDefs(cxt: KdrCxtBase): Map<String, Any?>

    /**
     * The runnable function [usage] declares on [dataType]: its settings validated against [initDataTypeName], and
     * held to the type it runs on (a property it names must be one the type declares, say). Throws a
     * [KdrException] saying what is wrong, which the check reports ([writeFunctionProblems]) and the run skips.
     */
    fun create(cxt: KdrCxt, usage: SchFunctionUsage, dataType: SchType): SchFunction
}

/** A resolved, runnable schema function (issue #1501): what a [SchFunctionCreation] builds from a usage. */
interface SchFunction {
    /** The kind it was built from -- its usage's `fn`. */
    val fn: String

    /** Run order within its list -- lower first, ties keeping the order written. */
    val priority: Int
}

/** A function of the [SchEventType.write] event (issue #1501): runs on an object's data each time it is written. */
interface SchWriteFunction : SchFunction {
    /** The properties it writes, so that two functions writing one property are caught when the type is checked. */
    val writes: Set<String>

    /**
     * The data to store, from [data] as the write has it -- an edit already merged, the functions listed before this
     * one already run. A fault that depends on the data (a value missing, a division by zero) never fails the write:
     * the function clears what it writes, and says so in the log when it was more than a value not filled in yet.
     */
    fun onWrite(cxt: KdrCxt, data: Map<String, Any?>): Map<String, Any?>
}

/** One usage's outcome on a type: the function it resolves to, or why it cannot run there. */
class SchFunctionResolution(val usage: SchFunctionUsage, val function: SchWriteFunction?, val problem: String?)

/**
 * Each of [dataType]'s `g-onWrite` usages resolved against the registered [creations] (issue #1501), in run order: a
 * usage that names no kind, a kind nobody registered, a kind of another event, or settings its kind refuses resolves to
 * a problem; so does one writing a property an earlier function already writes, since the two would race for it.
 */
fun resolveWriteFunctions(
    cxt: KdrCxt,
    creations: Map<String, SchFunctionCreation>,
    dataType: SchType,
): List<SchFunctionResolution> {
    val written = HashSet<String>()
    return dataType.onWrite.map { usage ->
        val fn = usage.fn
        val creation = fn?.let { creations[it] }
        fun refused(problem: String) = SchFunctionResolution(usage, null, problem)
        when {
            fn == null -> refused("A '${SCH.onWrite}' function names no kind; each says which it is in '${SFN.fn}'.")
            creation == null -> refused(
                "A '${SCH.onWrite}' function names the kind '$fn', which no component registered" +
                    (if (creations.isEmpty()) "." else " (known: ${creations.keys.sorted().joinToString(", ")})."),
            )
            creation.event != SchEventType.write -> refused(
                "'$fn' is a function of the ${creation.event.name} event, so it belongs under '${creation.event.keyword}', " +
                    "not '${SCH.onWrite}'.",
            )
            else -> try {
                val function = creation.create(cxt, usage, dataType) as SchWriteFunction
                val clash = function.writes.firstOrNull { it in written }
                if (clash != null) {
                    refused("'$fn' writes '$clash', which a function listed before it already writes.")
                } else {
                    written.addAll(function.writes)
                    SchFunctionResolution(usage, function, null)
                }
            } catch (e: KdrException) {
                refused(e.message ?: "'$fn' cannot run on this type.")
            }
        }
    }
}

/**
 * What is wrong with the `g-onWrite` functions of each of [names] in [types] (issue #1501), as `(type name, message)`
 * pairs, for the caller to report as its config problems: a usage [resolveWriteFunctions] refuses, and -- since the
 * functions run where a trait's data is written and nowhere else -- functions on a type that is no trait's data type.
 * A refused usage is skipped when the type is written ([runWriteFunctions]), and the type's other functions run.
 */
fun writeFunctionProblems(
    cxt: KdrCxt,
    creations: Map<String, SchFunctionCreation>,
    types: Map<String, SchType>,
    names: Collection<String>,
): List<Pair<String, String>> {
    val traitDataTypes = GU.entryKinds.flatMap { kind ->
        types["${GCFG.globalNamespace}.${GU.unionName(kind)}"]?.variants?.branches.orEmpty()
            .mapNotNull { it.properties[GE.data]?.valueType }
    }.toSet()
    val out = mutableListOf<Pair<String, String>>()
    for (name in names) {
        val type = types[name] ?: continue
        if (type.onWrite.isEmpty()) continue
        if (type !in traitDataTypes) {
            out.add(
                name to "Type '$name' declares '${SCH.onWrite}' functions, which run only where a trait's data is " +
                    "written, and it is no trait's data type.",
            )
            continue
        }
        resolveWriteFunctions(cxt, creations, type).forEach { r -> r.problem?.let { out.add(name to "Type '$name': $it") } }
    }
    return out
}

/** What a skipped `g-onWrite` function's config problem says the node does instead. */
const val skippedWriteFunction = "Skipping that function; the type's other functions run."

/**
 * [data] with [functions] run over it in order (issue #1501), each seeing what the ones before it wrote. A function
 * that throws despite its contract is logged and its properties cleared, so a write is never failed by one.
 */
fun runWriteFunctions(cxt: KdrCxt, functions: List<SchWriteFunction>, data: Map<String, Any?>): Map<String, Any?> {
    var out = data
    for (function in functions) {
        out = try {
            function.onWrite(cxt, out)
        } catch (e: Exception) {
            LogSchema.warn(cxt) { "The '${function.fn}' write function failed; clearing ${function.writes}: ${e.message}" }
            out - function.writes
        }
    }
    return out
}

/**
 * [usage]'s settings validated and coerced against [type], or a thrown [KdrException] naming the kind and **every**
 * failure -- the step every [SchFunctionCreation.create] opens with, so the wording is one for every kind.
 */
fun validatedFunctionSettings(type: SchType, usage: SchFunctionUsage, fn: String): Map<String, Any?> {
    val result = coerceAndValidate(type, usage.initData)
    if (result.failures.isNotEmpty()) {
        throw KdrException.mkConv(
            "'$fn' settings are invalid: " +
                result.failures.joinToString("; ") { "${it.path.ifEmpty { "(root)" }}: ${it.message}" },
        )
    }
    return result.value.toJsonMapOrEmpty()
}
