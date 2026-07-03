package com.dynamicruntime.common.startup

/**
 * Load-priority levels for components (see [ComponentDefinition.loadPriority]).
 * Lower numbers load earlier. These are deliberately plain `Int`s in an acronym
 * object rather than an enum: a caller typically supplies a value *relative* to a
 * level, such as `PRI.standard - 1` to sort just ahead of the standard components,
 * which an enum could not express.
 */
@Suppress("ConstPropertyName", "unused")
object PRI {
    const val veryEarly = 100
    const val early = 200
    const val standard = 300
    const val late = 400
    const val veryLate = 500
}
