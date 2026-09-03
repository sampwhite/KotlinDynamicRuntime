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
