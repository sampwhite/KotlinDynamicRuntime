package com.dynamicruntime.common.gedra.workflow

import com.dynamicruntime.common.context.KdrCxt
import com.dynamicruntime.common.schema.SCT
import com.dynamicruntime.common.schema.SchType
import com.dynamicruntime.common.schema.parseSchemaTypes
import com.dynamicruntime.common.schema.schemaDefs
import com.dynamicruntime.common.util.toOptStr

/** The `userHasLabel` function's name and initialization-data fields (issue #786). */
@Suppress("ConstPropertyName")
object ULH {
    /** The `fn` discriminator this function is declared under. */
    const val fn = "userHasLabel"

    /** The user label to test for; checked at boot against the declaring client's `userLabels` suggestions. */
    const val label = "label"

    /** The cfact to emit when the viewer carries [label]; must be declared, like any function-emitted cfact. */
    const val cfact = "cfact"

    /** The schema namespace and type name for this function's initialization data. */
    const val namespace = "wffnulh"
    const val initDataType = "UserHasLabel"
}

/**
 * The first `viewerCfacts` executor (issue #786): emit [cfact] when the person viewing the task carries the user
 * label [label]. The motivating case is approval authority -- `label = "reviewer"`, `cfact = WFC.reviewer` -- so
 * the approval task (#787) can tell a reviewer from somebody who must wait for one.
 *
 * It decides **presentation, never permission**, as every cfact does: showing a reviewer the approve button is
 * this function's job, while refusing a non-reviewer's approval is the approval endpoint's, which checks for
 * itself.
 */
class UserHasLabelFn(
    override val priority: Int,
    private val label: String,
    private val cfact: String,
) : ViewerCfactsFn {
    override val fn: String = ULH.fn

    override fun computeViewerCfacts(cxt: KdrCxt, params: ViewerCfactsParams) {
        if (label in params.viewerLabels) {
            params.emit(cfact)
        }
    }
}

/**
 * Builds a [UserHasLabelFn] from a usage's initialization data (issue #786), validating it against
 * [initDataType] and throwing -- which the resolution pass turns into a config problem -- when it does not
 * conform. Registered in `CommonComponent` through `SchemaCollector.addWorkflowFunction`.
 */
object UserHasLabelCreation : WfFunctionCreation {
    override val fn: String = ULH.fn
    override val event: WfEventType = WfEventType.viewerCfacts

    override fun create(cxt: KdrCxt, usage: WfFunctionUsage): WfFunction {
        val m = validatedInitData(initDataType(cxt), usage, ULH.fn)
        return UserHasLabelFn(
            priority = usage.priority,
            label = m[ULH.label].toOptStr() ?: "",
            cfact = m[ULH.cfact].toOptStr() ?: "",
        )
    }

    /** The cfact it emits, for the boot check that the client declares it. */
    override fun emittedCfacts(usage: WfFunctionUsage): Set<String> =
        usage.initData[ULH.cfact].toOptStr()?.let { setOf(it) } ?: emptySet()

    /** The label it tests for, for the boot check that the client suggests it. */
    override fun referencedUserLabels(usage: WfFunctionUsage): Set<String> =
        usage.initData[ULH.label].toOptStr()?.let { setOf(it) } ?: emptySet()

    // Parsed once and kept: the schema is a constant of the runtime, matching the other function kinds.
    private var parsed: SchType? = null

    private fun initDataType(cxt: KdrCxt): SchType =
        parsed ?: parseSchemaTypes(
            schemaDefs(cxt, ULH.namespace) {
                type(ULH.initDataType) {
                    type = SCT.kObject
                    description = "Emit a cfact about the viewer when they carry a user label."
                    property(WFD.fn, "The function discriminator; always '${ULH.fn}' here.", required = true)
                    property(WFD.priority, "Run order within the task's viewerCfacts list.") { type = SCT.integer }
                    property(ULH.label, "The user label to test for.", required = true)
                    property(ULH.cfact, "The cfact emitted when the viewer carries the label.", required = true)
                }
            },
        ).getValue("${ULH.namespace}.${ULH.initDataType}").also { parsed = it }
}

/**
 * Authors a [ULH.fn] usage's initialization data (issue #786), used inside a
 * `task(...) { function(userHasLabel { ... }) }` block.
 */
class UserHasLabelBuilder {
    /** The user label to test for. */
    var label: String = ""

    /** The cfact to emit; [WFC.reviewer] by default, the motivating case. */
    var cfact: String = WFC.reviewer

    /** Run order within the task's viewerCfacts list; unset leaves it at the default. */
    var priority: Int? = null

    /** The usage's initialization data, ready for `function(...)`. */
    fun build(): Map<String, Any?> = buildMap {
        put(WFD.fn, ULH.fn)
        priority?.let { put(WFD.priority, it) }
        put(ULH.label, label)
        put(ULH.cfact, cfact)
    }
}

/** Builds [ULH.fn] initialization data with [UserHasLabelBuilder]; see it. */
fun userHasLabel(build: UserHasLabelBuilder.() -> Unit): Map<String, Any?> = UserHasLabelBuilder().apply(build).build()
