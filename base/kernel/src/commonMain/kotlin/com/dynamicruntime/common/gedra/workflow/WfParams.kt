package com.dynamicruntime.common.gedra.workflow

import com.dynamicruntime.common.util.formatDate
import com.dynamicruntime.common.util.toJsonListOfMaps
import com.dynamicruntime.common.util.toJsonMapOrEmpty
import com.dynamicruntime.common.util.toOptInstant
import com.dynamicruntime.common.util.toOptLong
import com.dynamicruntime.common.util.toOptStr
import kotlin.time.Instant

/** Names for a workflow's parameters (issue #1356), shared with the frontend. */
@Suppress("ConstPropertyName")
object WFP {
    /** The values in force: a [WfParams] map. */
    const val params = "params"

    /** The parameter holding a normal workflow's time windows ([WfWindows]). */
    const val windows = "windows"

    /** As a page is answered: the setting in force ([WfParamsSetting]). */
    const val current = "current"

    /** The earlier settings, newest first ([WfParamsSetting]), at most [historyKept]. */
    const val history = "history"

    /** On a setting: when it was set. */
    const val setAt = "setAt"

    /** On a setting: the user who set it, by id. */
    const val setBy = "setBy"

    /** On a setting, as a page is answered: who set it, by name. */
    const val setByName = "setByName"

    /**
     * On a sandbox's parameters as a page is answered: the sandbox has none of its own, and reads its parent's, which
     * these are.
     */
    const val fromParent = "fromParent"

    /** How many earlier settings a workflow's parameters keep. */
    const val historyKept = 3
}

/**
 * A workflow's parameters in one scope (issue #1356): values that are not part of its definition, set on the client's
 * page, in force at once with no draft and no publish, and kept per client -- a sandbox its own. Their keys are the
 * platform's to declare, not a definition's: [windows] now, a form quota to come. A value left unset is no limit.
 */
class WfParams(val windows: WfWindows = WfWindows.none) {
    /** Whether nothing is set: the workflow runs with no limits. */
    val isEmpty: Boolean get() = windows.isEmpty

    /** What is wrong with these parameters for [workflowId], or null: windows that do not nest, say. */
    fun problem(workflowId: String): String? = windows.problem(workflowId)

    /** As stored and sent: each parameter set, and nothing for one that is not. */
    fun toInfo(): Map<String, Any?> = buildMap {
        if (!windows.isEmpty) put(WFP.windows, windows.toInfo())
    }

    override fun equals(other: Any?): Boolean = other is WfParams && other.toInfo() == toInfo()

    override fun hashCode(): Int = toInfo().hashCode()

    companion object {
        val none = WfParams()

        /** Parameters read off storage or the wire ([toInfo]); a window bound that is no moment is refused. */
        fun fromInfo(raw: Any?): WfParams = WfParams(WfWindows.fromInfo(raw.toJsonMapOrEmpty()[WFP.windows].toJsonMapOrEmpty()))
    }
}

/**
 * One setting of a workflow's parameters (issue #1356): the values, when they were set, and by whom -- the current
 * one, or one of the earlier ones a workflow's parameters keep ([WFP.historyKept]). [setByName] is filled in only
 * where a page is answered, from [setBy].
 */
class WfParamsSetting(val params: WfParams, val setAt: Instant?, val setBy: Long?, val setByName: String? = null) {
    fun toInfo(): Map<String, Any?> = buildMap {
        put(WFP.params, params.toInfo())
        setAt?.let { put(WFP.setAt, it.formatDate()) }
        setBy?.let { put(WFP.setBy, it) }
        setByName?.let { put(WFP.setByName, it) }
    }

    companion object {
        fun fromInfo(raw: Map<String, Any?>): WfParamsSetting = WfParamsSetting(
            WfParams.fromInfo(raw[WFP.params]), raw[WFP.setAt].toOptInstant(), raw[WFP.setBy].toOptLong(),
            raw[WFP.setByName].toOptStr(),
        )

        /** A history as stored or sent: newest first. */
        fun listFrom(raw: Any?): List<WfParamsSetting> = raw.toJsonListOfMaps().map { fromInfo(it) }
    }
}
