package com.dynamicruntime.common.schema

/**
 * Boot-time collection of `g-visibleWhen` problems (issues #545, #564).
 *
 * `g-visibleWhen` is a per-property cfact expression, **evaluated on the frontend** (issue #564): the served
 * schema keeps the keyword, and the client hides a property whose expression the caller's delivered cfacts
 * fail. So there is nothing to resolve on the backend -- the field is never dropped here, the way [SCH.visibleWhen]
 * describes -- only a boot check that every declared expression is sound.
 *
 * This walks a schema document collecting every declared expression's problems. [check] decides what, if
 * anything, is wrong with one expression: it returns a problem detail, or null when the expression is fine
 * (both a parse and the "names only frontend-delivered cfacts" check live in the caller's [check], since only
 * it holds the registry). [where] names the location so a failure points at the declaration.
 */
fun visibleWhenProblems(where: String, node: Any?, check: (expression: String) -> String?): List<String> {
    val problems = mutableListOf<String>()
    fun walk(n: Any?) {
        when (n) {
            is Map<*, *> -> {
                (n[SCH.visibleWhen] as? String)?.let { expression ->
                    check(expression)?.let { detail ->
                        problems.add("$where: '${SCH.visibleWhen}' expression '$expression' $detail")
                    }
                }
                n.values.forEach { walk(it) }
            }
            is List<*> -> n.forEach { walk(it) }
        }
    }
    walk(node)
    return problems
}

/**
 * Collects `g-visibleWhen` gates declared on a **required** property (issue #564).
 *
 * A gate hides the field on the frontend, but the served schema still lists it in `required`, so a caller the
 * gate hides could never submit: validation rejects the missing field, pointing at one they cannot see. So
 * `g-visibleWhen` is for optional fields only, and a required one is refused at boot. This walks every object
 * node and matches a gated `properties` child against that node's own `required` array. An endpoint field
 * carries its required-ness on the field rather than in the schema, so its check is separate (see the caller);
 * this covers the type-definition shape. [where] names the location.
 */
fun requiredVisibleWhenProblems(where: String, node: Any?): List<String> {
    val problems = mutableListOf<String>()
    fun walk(n: Any?) {
        when (n) {
            is Map<*, *> -> {
                val props = n[SCH.properties] as? Map<*, *>
                if (props != null) {
                    val required = (n[SCH.required] as? List<*>).orEmpty().filterIsInstance<String>().toSet()
                    for ((child, body) in props) {
                        val name = child as? String ?: continue
                        if (name in required && (body as? Map<*, *>)?.get(SCH.visibleWhen) is String) {
                            problems.add(requiredGateProblem(where, name))
                        }
                    }
                }
                n.values.forEach { walk(it) }
            }
            is List<*> -> n.forEach { walk(it) }
        }
    }
    walk(node)
    return problems
}

/** The message a required-and-gated property earns (issue #564), shared by the type-def and endpoint checks. */
fun requiredGateProblem(where: String, property: String): String =
    "$where: property '$property' is required but declares '${SCH.visibleWhen}'. The gate hides the field on " +
        "the frontend while the schema still requires it, so a caller it hides could not submit. " +
        "'${SCH.visibleWhen}' is for optional fields only."
