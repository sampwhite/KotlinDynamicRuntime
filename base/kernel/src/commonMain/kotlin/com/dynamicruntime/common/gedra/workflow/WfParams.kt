package com.dynamicruntime.common.gedra.workflow

import com.dynamicruntime.common.exception.KdrException
import com.dynamicruntime.common.user.normalizeUserLabels
import com.dynamicruntime.common.util.formatDate
import com.dynamicruntime.common.util.parseDateOrNull
import com.dynamicruntime.common.util.toJsonListOfMaps
import com.dynamicruntime.common.util.toJsonListOfStrings
import com.dynamicruntime.common.util.toJsonMapOrEmpty
import com.dynamicruntime.common.util.toOptInstant
import com.dynamicruntime.common.util.toOptLong
import com.dynamicruntime.common.util.toOptStr
import kotlin.time.Instant

/** Names for a workflow's parameters (issue #1356), shared with the frontend. */
@Suppress("ConstPropertyName")
object WFP {
    /** A scope's own values, or a setting's: a [WfParams] map, holding only the parameters it sets. */
    const val params = "params"

    /** As a page is answered: the values in force, each parameter from wherever it comes ([from]). */
    const val inForce = "inForce"

    /**
     * As a page is answered: where each parameter in force that is not the scope's own comes from, by key -- a
     * [WfParamsFrom] name. A key in [inForce] and not here is the scope's own.
     */
    const val from = "from"

    /** As a page is answered on a sandbox: its client's own setting ([WfParamsSetting]), when the client has one. */
    const val parent = "parent"

    /** As a page is answered: the defaults the client's source code declares (a [WfParams] map), when it declares any. */
    const val source = "source"

    /**
     * On a save: the parameters to stop setting here (issue #1386), by key, so each follows its fallback again -- a
     * sandbox its client's, a client the defaults its source code declares.
     */
    const val unset = "unset"

    /** The parameter holding a normal workflow's time windows ([WfWindows]). */
    const val windows = "windows"

    /** The parameter holding who sees a workflow, of any kind ([WfVisibility], issue #1384). */
    const val visibility = "visibility"

    /** In a [visibility]: a [WfVisibilityMode] name. */
    const val mode = "mode"

    /** In a [visibility]: the user labels whose holders see a restricted workflow, besides administrators. */
    const val labels = "labels"

    /** As a page is answered: the scope's own setting ([WfParamsSetting]); absent while it has none. */
    const val current = "current"

    /** The earlier settings, newest first ([WfParamsSetting]), at most [historyKept]. */
    const val history = "history"

    /** On a setting: when it was set. */
    const val setAt = "setAt"

    /** On a setting: the user who set it, by id. */
    const val setBy = "setBy"

    /** On a setting, as a page is answered: who set it, by name. */
    const val setByName = "setByName"

    /** How many earlier settings a workflow's parameters keep. */
    const val historyKept = 3
}

/** Who sees a workflow, in the large (issue #1384). */
@Suppress("EnumEntryName")
enum class WfVisibilityMode {
    /** Everybody who could otherwise: what a workflow nobody restricted has. */
    everyone,

    /** Its client's administrators, and anyone holding one of the visibility's labels. */
    restricted,
}

/**
 * Who sees a workflow (issue #1384): everybody, or -- [restricted] -- the client's administrators and anyone holding one
 * of [labels], the user labels an administrator puts on people (issue #786). Restricted with no labels is administrators
 * only; an empty list never means everybody.
 *
 * **One parameter, not two.** The mode and the labels resolve together, as one value of one scope, so a sandbox that
 * restricts a workflow never inherits its client's group without choosing it. The labels are kept when the mode is
 * [WfVisibilityMode.everyone], so restricting again brings the same group back.
 */
class WfVisibility(val mode: WfVisibilityMode, labels: List<String> = emptyList()) {
    /** The labels, each once and trimmed ([normalizeUserLabels], the rule a user's labels are stored by). */
    val labels: List<String> = normalizeUserLabels(labels)

    val restricted: Boolean get() = mode == WfVisibilityMode.restricted

    /** Whether a person sees the workflow: an administrator of its client always does, anyone else by the mode and their labels. */
    fun admits(isAdministrator: Boolean, personLabels: Collection<String>): Boolean =
        !restricted || isAdministrator || labels.any { it in personLabels }

    /** These, set to [mode] with their labels kept. */
    fun withMode(mode: WfVisibilityMode): WfVisibility = WfVisibility(mode, labels)

    /** These, holding [labels] in place of their own. */
    fun withLabels(labels: List<String>): WfVisibility = WfVisibility(mode, labels)

    fun toInfo(): Map<String, Any?> = mapOf(WFP.mode to mode.name, WFP.labels to labels)

    override fun equals(other: Any?): Boolean = other is WfVisibility && other.toInfo() == toInfo()

    override fun hashCode(): Int = toInfo().hashCode()

    companion object {
        /** What no parameter means: everybody sees the workflow. */
        val everyone = WfVisibility(WfVisibilityMode.everyone)

        /** Administrators only: what a workflow made from a template starts as. */
        val adminsOnly = WfVisibility(WfVisibilityMode.restricted)

        /** A visibility read off storage or the wire ([toInfo]); a mode that is none is refused. */
        fun fromInfo(raw: Any?): WfVisibility {
            val map = raw.toJsonMapOrEmpty()
            val name = map[WFP.mode].toOptStr()
            val mode = WfVisibilityMode.entries.firstOrNull { it.name == name }
                ?: throw KdrException.mkConv("A workflow's visibility has a mode, '$name', that is not ${WfVisibilityMode.entries.map { it.name }}.")
            return WfVisibility(mode, map[WFP.labels].toJsonListOfStrings())
        }
    }
}

/** Where a parameter in force comes from when it is not the scope's own (issue #1386). */
@Suppress("EnumEntryName")
enum class WfParamsFrom {
    /** A sandbox's client's own value, which the sandbox follows until it sets its own. */
    parent,

    /** The default the client's source code declares, followed until the client -- or a sandbox -- sets its own. */
    source,
}

/**
 * A workflow's parameters in one scope (issue #1356): values that are not part of its definition, set on the client's
 * page, in force at once with no draft and no publish, and kept per client -- a sandbox its own. Their keys are the
 * platform's to declare, not a definition's: [windows], a normal workflow's time windows, and [visibility], who sees a
 * workflow of any kind (issue #1384); a form quota to come.
 *
 * **Each parameter resolves on its own** (issue #1386). One these do not set (null) follows the fallback
 * below them -- a sandbox its client's, a client its source defaults -- and one they set is theirs, including
 * deliberately no limits ([WfWindows.none]). So a scope may set one parameter and follow the fallback for another, and
 * what nothing sets anywhere is no limit.
 */
class WfParams(val windows: WfWindows? = null, val visibility: WfVisibility? = null) {
    /** Whether these set no parameter: each follows the fallback. */
    val isEmpty: Boolean get() = windows == null && visibility == null

    /** The keys of the parameters these set. */
    val keys: Set<String>
        get() = buildSet {
            if (windows != null) add(WFP.windows)
            if (visibility != null) add(WFP.visibility)
        }

    /** The windows these give a workflow: their own, or no limits when they set none. */
    val windowsInForce: WfWindows get() = windows ?: WfWindows.none

    /** Who these let see a workflow: their own visibility, or everybody when they set none (issue #1384). */
    val visibilityInForce: WfVisibility get() = visibility ?: WfVisibility.everyone

    /** What is wrong with these parameters for [workflowId], or null: windows that do not nest, say. */
    fun problem(workflowId: String): String? = windows?.problem(workflowId)

    /** These, with each parameter they do not set taken from [fallback]. */
    fun over(fallback: WfParams): WfParams = WfParams(windows ?: fallback.windows, visibility ?: fallback.visibility)

    /** These, with the parameters [edit] sets replacing theirs, and those named in [unset] no longer set. */
    fun edited(edit: WfParams, unset: Set<String> = emptySet()): WfParams = WfParams(
        if (WFP.windows in unset) null else edit.windows ?: windows,
        if (WFP.visibility in unset) null else edit.visibility ?: visibility,
    )

    /** As stored and sent: each parameter set -- an empty one too, which is no limits -- and nothing for one that is not. */
    fun toInfo(): Map<String, Any?> = buildMap {
        windows?.let { put(WFP.windows, it.toInfo()) }
        visibility?.let { put(WFP.visibility, it.toInfo()) }
    }

    override fun equals(other: Any?): Boolean = other is WfParams && other.toInfo() == toInfo()

    override fun hashCode(): Int = toInfo().hashCode()

    companion object {
        val none = WfParams()

        /** The key of every parameter there is. */
        val allKeys: Set<String> = setOf(WFP.windows, WFP.visibility)

        /** Parameters read off storage or the wire ([toInfo]); a window bound that is no moment, or a mode that is none, is refused. */
        fun fromInfo(raw: Any?): WfParams {
            val map = raw.toJsonMapOrEmpty()
            return WfParams(
                if (map[WFP.windows] != null) WfWindows.fromInfo(map[WFP.windows].toJsonMapOrEmpty()) else null,
                if (map[WFP.visibility] != null) WfVisibility.fromInfo(map[WFP.visibility]) else null,
            )
        }
    }
}

/**
 * The layers a workflow's parameters resolve through in one scope (issue #1386), and what they make together: [own],
 * the scope's; [parent], on a sandbox its client's; [source], the defaults the client's source code declares. Each
 * parameter in force is the first layer's that sets it ([WfParams.over]), and one none sets is no limit. Shared with the
 * frontend, so the client's page says what the backend runs.
 */
class WfParamsLayers(val own: WfParams, val parent: WfParams = WfParams.none, val source: WfParams = WfParams.none) {
    /** What the scope would run with if it set nothing: its client's, then its source defaults. */
    val fallback: WfParams = parent.over(source)

    /** The parameters in force. */
    val inForce: WfParams = own.over(fallback)

    /** Where each parameter in force that is not the scope's own comes from, by key; see [WFP.from]. */
    val from: Map<String, WfParamsFrom> = buildMap { for (key in inForce.keys - own.keys) fallbackFrom(key)?.let { put(key, it) } }

    /** Where [key] would come from if the scope did not set it, or null when no fallback sets it. */
    fun fallbackFrom(key: String): WfParamsFrom? = when (key) {
        in parent.keys -> WfParamsFrom.parent
        in source.keys -> WfParamsFrom.source
        else -> null
    }

    /** These layers with the scope's own made [own]: what a save would leave in force. */
    fun withOwn(own: WfParams): WfParamsLayers = WfParamsLayers(own, parent, source)
}

/**
 * Builds the default parameters a client's source code declares for one of its workflows (issue #1386); see
 * `GedraConfigBuilder.workflowParams`. Each bound is a moment written as text -- `"2026-11-01T00:00:00Z"` -- and a
 * window given no bound, or not given at all, follows the one around it, as on the client's page.
 */
class WfParamsBuilder(private val workflowId: String) {
    private var lifetime: WfWindow? = null
    private var relevancy: WfWindow? = null
    private var engagement: WfWindow? = null

    /** When the workflow exists at all. */
    fun lifetime(start: String? = null, end: String? = null) {
        lifetime = window(WFD.lifetime, start, end)
    }

    /** When its state is calculated. */
    fun relevancy(start: String? = null, end: String? = null) {
        relevancy = window(WFD.relevancy, start, end)
    }

    /** When new forms may join it. */
    fun engagement(start: String? = null, end: String? = null) {
        engagement = window(WFD.engagement, start, end)
    }

    private fun window(name: String, start: String?, end: String?): WfWindow {
        fun moment(text: String?): Instant? = text?.let {
            it.parseDateOrNull() ?: throw KdrException.mkConv("The default $name window of workflow '$workflowId' has a bound, '$it', that is no moment.")
        }
        return WfWindow(moment(start), moment(end))
    }

    /** The parameters declared: the windows, when any was given, which must nest. */
    fun build(): WfParams {
        if (lifetime == null && relevancy == null && engagement == null) {
            throw KdrException.mkConv("The default parameters of workflow '$workflowId' declare nothing.")
        }
        val windows = WfWindows(lifetime ?: WfWindow.always, relevancy ?: WfWindow.always, engagement ?: WfWindow.always)
        windows.check(workflowId)
        return WfParams(windows)
    }
}

/**
 * One setting of a workflow's parameters (issue #1356): the values a scope set itself, when, and by whom -- the current
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
