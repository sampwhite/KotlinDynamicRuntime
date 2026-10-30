package com.dynamicruntime.common.schema

import com.dynamicruntime.common.context.KdrCxt
import com.dynamicruntime.common.exception.KdrException
import com.dynamicruntime.common.util.ScriptError
import com.dynamicruntime.common.util.ScriptExpression
import com.dynamicruntime.common.util.toOptStr
import com.dynamicruntime.common.context.KdrCxtBase

/** The `computeValue` function's name, its settings, and its schema names (issue #1501). Each name matches its value. */
@Suppress("ConstPropertyName")
object CVF {
    /** The `fn` discriminator this function is declared under. */
    const val fn = "computeValue"

    /** The property the computed value is stored in -- one the type declares `g-derived`. */
    const val target = "target"

    /** The script expression computing the value from the object's own data, written bare: `perItemAmount * itemCount`. */
    const val script = "script"

    /** The schema namespace and type name for this function's settings. */
    const val namespace = "kdr.sfncv"
    const val initDataType = "ComputeValue"
}

/**
 * The settings of a [CVF.fn] usage, for a type's `onWrite(...)` in the schema builder (issue #1501): store the value
 * of [script], computed over the object's own data, in [target]. The same shape a stored definition writes, so a
 * code-built and a stored usage are indistinguishable downstream.
 */
fun computeValue(target: String, script: String, priority: Int? = null): Map<String, Any?> = buildMap {
    put(SFU.fn, CVF.fn)
    if (priority != null) put(SFU.priority, priority)
    put(CVF.target, target)
    put(CVF.script, script)
}

/**
 * The first schema function (issue #1501): on each write, evaluate a script over the object's own data and store the
 * value in a `g-derived` property, coerced to that property's type by its `allowCoerce` rules. An expense report's
 * total, `perItemAmount * itemCount`, is the example it was built for.
 *
 * The script reads only the object's own data, so the value follows from what is stored beside it and nothing else.
 * When it cannot be computed -- an input not filled in yet, which is the normal case on a form part-way through, or a
 * fault such as a division by zero -- the property is cleared rather than the write failed, and a fault beyond a
 * missing input is logged.
 */
class ComputeValueFn(
    override val priority: Int,
    private val target: String,
    private val expression: ScriptExpression,
    private val targetType: SchType,
) : SchWriteFunction {
    override val fn: String = CVF.fn
    override val writes: Set<String> = setOf(target)
    override val reads: Set<String> = (expression.paths.required + expression.paths.optional).map { it.substringBefore('.') }.toSet()

    override fun onWrite(cxt: KdrCxt, data: Map<String, Any?>): Map<String, Any?> {
        val value = try {
            expression.evaluate(data)
        } catch (e: KdrException) {
            val code = e.extraData[KdrException.errorCodeKey]
            if (code != ScriptError.missingKey && code != ScriptError.nullValue) {
                LogSchema.warn(cxt) { "'${CVF.fn}' could not compute '$target' from '${expression.text}'; clearing it: ${e.message}" }
            }
            return data - target
        }
        if (value == null) return data - target
        val coerced = coerceAndValidate(targetType, wholeAsInteger(value))
        if (coerced.failures.isNotEmpty()) {
            LogSchema.warn(cxt) {
                "'${CVF.fn}' computed a value for '$target' its type does not take; clearing it: " +
                    coerced.failures.joinToString("; ") { it.message }
            }
            return data - target
        }
        return data + (target to coerced.value)
    }

    /**
     * [value] as a whole number when the target is an integer and the script computed one as a fraction-free double --
     * `a + b` over two amounts entered as decimals, say. A script's arithmetic keeps a double a double, and an integer
     * type takes no double, so without this every such total would be refused and cleared. Exactly whole only: a near
     * miss (`3.0000000000000004`) is the script's to round, with `round(...)`, rather than this one's to guess at.
     */
    private fun wholeAsInteger(value: Any): Any =
        if (targetType.jsonType == SCT.integer && value is Double && value % 1.0 == 0.0 && kotlin.math.abs(value) < 9.0e15) {
            value.toLong()
        } else {
            value
        }
}

/**
 * Builds a [ComputeValueFn] from a usage (issue #1501), holding it to the type it runs on: the target must be a
 * property the type declares `g-derived` (so no caller supplies it) and does not gate with `g-visibleWhen` (whose
 * question is who may change it, while this changes it for everyone); the script must parse, pull no fragment copy,
 * and read only properties the type declares. Registered in `CommonComponent` through
 * `SchemaCollector.addSchemaFunction`.
 */
object ComputeValueCreation : SchFunctionCreation {
    override val fn: String = CVF.fn
    override val event: SchEventType = SchEventType.write
    override val initDataTypeName: String = "${CVF.namespace}.${CVF.initDataType}"

    override fun initDataDefs(cxt: KdrCxtBase): Map<String, Any?> = schemaDefs(cxt, CVF.namespace) {
        type(CVF.initDataType) {
            type = SCT.kObject
            description = "Store the value of a script, computed over the object's own data, in one of its derived properties."
            property(SFU.fn, "The function's kind; always '${CVF.fn}' here.", required = true) { const = CVF.fn }
            property(SFU.priority, "Run order among the type's write functions, lower first.") { type = SCT.integer }
            property(CVF.target, "The property the value is stored in, one the type declares '${SCH.derived}'.", required = true)
            property(
                CVF.script,
                "A script expression over the object's own properties, written bare (perItemAmount * itemCount). A " +
                    "null result, or one that cannot be computed, clears the target.",
                required = true,
            )
        }
    }

    override fun create(cxt: KdrCxt, usage: SchFunctionUsage, dataType: SchType): SchFunction {
        val m = validatedFunctionSettings(initDataType(cxt), usage, CVF.fn)
        val target = m[CVF.target].toOptStr().orEmpty()
        val script = m[CVF.script].toOptStr().orEmpty()
        fun refuse(problem: String): Nothing = throw KdrException.mkConv("'${CVF.fn}' of '$target': $problem")
        val prop = dataType.properties[target] ?: refuse("the type declares no property '$target' to store the value in.")
        if (!prop.valueType.derived) {
            refuse("'$target' is not '${SCH.derived}', so a caller could supply it and this would overwrite what they sent.")
        }
        if (prop.visibleWhen != null) {
            refuse("'$target' is gated by '${SCH.visibleWhen}', which decides who may change it; this changes it for everyone.")
        }
        val expression = try {
            ScriptExpression.parse(script)
        } catch (e: KdrException) {
            refuse("the script '$script' does not parse: ${e.message}")
        }
        if (expression.pullsFragments) {
            refuse("the script '$script' pulls copy with '@t', which there is none of where data is written.")
        }
        // Reading its own target, or another function's not yet computed, is refused where the type's functions are
        // resolved together (`resolveWriteFunctions`), which knows what each of them writes.
        val read = expression.paths.required + expression.paths.optional
        read.firstOrNull { !declaresPath(dataType, it) }?.let {
            refuse("the script reads '$it', which the type does not declare.")
        }
        return ComputeValueFn(usage.priority, target, expression, prop.valueType)
    }

    /**
     * Whether [path] (dotted) names a property [type] declares, drilling through declared object properties. A step
     * into a value whose type declares no properties -- a map, a list, an untyped value -- is not judged further.
     */
    private fun declaresPath(type: SchType, path: String): Boolean {
        var at = type
        for (segment in path.split('.')) {
            if (at.properties.isEmpty()) return true
            at = at.properties[segment]?.valueType ?: return false
        }
        return true
    }

    // Parsed once and kept: the schema is a constant of the runtime, as each workflow function kind keeps its own.
    // Volatile, since boots, reloads, and trials resolve on their own threads; a race only parses it twice.
    @Volatile
    private var parsed: SchType? = null

    private fun initDataType(cxt: KdrCxt): SchType =
        parsed ?: parseSchemaTypes(initDataDefs(cxt)).getValue(initDataTypeName).also { parsed = it }
}
