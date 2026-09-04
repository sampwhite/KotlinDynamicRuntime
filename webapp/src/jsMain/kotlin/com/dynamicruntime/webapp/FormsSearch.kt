package com.dynamicruntime.webapp

import com.dynamicruntime.common.endpoint.EI
import com.dynamicruntime.common.endpoint.EP
import com.dynamicruntime.common.gedra.SearchRole
import com.dynamicruntime.common.gedra.UsageKind
import com.dynamicruntime.common.gedra.decodeSearchParam
import com.dynamicruntime.common.schema.SCH
import com.dynamicruntime.common.util.toJsonMapOrEmpty
import react.FC
import react.Props
import react.dom.html.ReactHTML.div
import react.dom.html.ReactHTML.span
import web.cssom.ClassName

/** The fields on the reserved side of the listing query -- paging, the user scope and the free-text term --
 *  not search fields, and never shown as one. */
private val reservedQueryFields = setOf(EP.offset, EP.limit, EI.user, EI.q)

/**
 * One trait's search controls, grouped (issue #562): the parameters the listing declares for a single trait --
 * an exact and maybe a substring match for text, a lower and upper bound for a number or date -- folded into
 * the one control a person expects: a text box, or a from-to pair. Derived from the listing endpoint's own
 * input schema (issue #538), so it is exactly what the client's variant advertises -- a control appears only
 * when the backend will accept its parameter. The parameter *names* are what the box sends; the [label] is
 * the column's, with the role's own words removed, falling back to the trait id.
 */
class SearchGroup(
    val traitId: String,
    val label: String,
    val kind: UsageKind,
    /** The exact-match parameter, when declared. */
    val exact: String?,
    /** The substring parameter, when the usage asked for one. */
    val contains: String?,
    /** The lower bound, for a number or date. */
    val min: String?,
    /** The upper bound, for a number or date. */
    val max: String?,
) {
    /**
     * The parameter a text box sends: the substring one when it is declared, since "contains" is what a
     * person expects a search box to do, else the exact one. Null for a range.
     */
    val text: String? get() = contains ?: exact

    /** Whether the trait is searched as a from-to range rather than a text box. */
    val isRange: Boolean get() = min != null || max != null
}

/**
 * The search groups a listing endpoint's [inputSchema] declares (issue #562): every input property that is not
 * a reserved paging/scope field, gathered by trait into one [SearchGroup] each, in the order the traits first
 * appear. A client with no usage rules has none, so the caller shows no search at all. The grouping reads the
 * parameter names back through the kernel's own decoder ([decodeSearchParam]), so it is the generator's naming
 * inverted rather than a second spelling of it. Pure, and covered under `jsNodeTest`.
 */
fun searchGroups(inputSchema: Map<String, Any?>): List<SearchGroup> {
    class Draft(val traitId: String, val kind: UsageKind) {
        var label: String? = null
        var exact: String? = null
        var contains: String? = null
        var min: String? = null
        var max: String? = null
    }

    val properties = inputSchema[SCH.properties].toJsonMapOrEmpty()
    val drafts = LinkedHashMap<String, Draft>()
    for ((name, body) in properties) {
        if (name in reservedQueryFields) continue
        val prop = body.toJsonMapOrEmpty()
        val shape = decodeSearchParam(name, prop)
        val draft = drafts.getOrPut(shape.traitId) { Draft(shape.traitId, shape.kind) }
        // The caption is the column label: the parameter's title with the role's own words removed.
        if (draft.label == null) draft.label = (prop[SCH.title] as? String)?.removeSuffix(shape.role.labelSuffix)
        when (shape.role) {
            SearchRole.exact -> draft.exact = name
            SearchRole.contains -> draft.contains = name
            SearchRole.min -> draft.min = name
            SearchRole.max -> draft.max = name
        }
    }
    return drafts.values.map { SearchGroup(it.traitId, it.label ?: it.traitId, it.kind, it.exact, it.contains, it.min, it.max) }
}

/**
 * The filters in [applied] said in words, one per trait with a value, for the summary the collapsed panel
 * shows (issue #562): `Name contains "plan"`, `Year 2020 – 2025`, `Due ≤ 2026-01-01`. Only the groups'
 * own parameters count -- the free-text term and the user scope have their own controls -- and a blank value
 * is no filter. Pure, and covered under `jsNodeTest`.
 */
fun activeFilterChips(groups: List<SearchGroup>, applied: Map<String, Any?>): List<String> = groups.mapNotNull { g ->
    fun valueOf(name: String?): String? = name?.let { applied[it]?.toString()?.trim()?.ifEmpty { null } }
    if (g.isRange) {
        val lo = valueOf(g.min)
        val hi = valueOf(g.max)
        when {
            lo != null && hi != null -> "${g.label} $lo – $hi"
            lo != null -> "${g.label} ≥ $lo"
            hi != null -> "${g.label} ≤ $hi"
            else -> null
        }
    } else {
        val contains = valueOf(g.contains)
        val exact = valueOf(g.exact)
        when {
            contains != null -> "${g.label} contains \"$contains\""
            exact != null -> "${g.label} is \"$exact\""
            else -> null
        }
    }
}

/**
 * The scope bar (issue #562), for a caller who administers other users: whose forms the list shows. Promoted
 * above the search rather than filed among the filters, because it changes *whose* documents are on screen
 * where a filter narrows *which* -- and drawn in the elevated-privilege tint so it reads as the administrative
 * control it is. The box takes a user id or an email; the backend resolves either, confined to the caller's
 * own access, so an id outside it is refused with a message rather than silently emptied.
 */
external interface FormsScopeBarProps : Props {
    /** What the box holds. */
    var value: String

    /** The user the list is currently confined to, or null for everyone the caller may see. */
    var applied: String?

    /** Records a keystroke in the box. */
    var onChange: (String) -> Unit

    /** Confines the list to the user in the box. */
    var onApply: () -> Unit

    /** Drops the user and shows everyone again. */
    var onShowEveryone: () -> Unit
}

val FormsScopeBar = FC<FormsScopeBarProps> { props ->
    div {
        className = ClassName("row forms-scope")
        span {
            className = ClassName("forms-scope-label")
            +"Showing forms for"
        }
        Input {
            placeholder = "everyone you administer — or a user id or email"
            value = props.value
            allowClear = true
            style = js("({ width: 340 })")
            onChange = { event -> props.onChange(event.target.value as? String ?: "") }
            onPressEnter = { props.onApply() }
        }
        Button {
            onClick = { props.onApply() }
            +"Apply"
        }
        if (props.applied != null) {
            Button {
                type = "link"
                onClick = { props.onShowEveryone() }
                +"Show everyone"
            }
        }
    }
}

/**
 * The forms-list search (issue #538, regrouped in #562): one box that searches every text field at once, and
 * behind a toggle the per-trait filters the client's usage rules declared -- one text box per text trait, a
 * from-to pair per number or date -- so the list keeps the screen and the filters are there when wanted.
 * While the panel is closed, the applied filters are summarized as chips so a narrowed list never looks like
 * the whole.
 *
 * Presentational -- the parent owns the values and every action -- so it knows nothing about usages or
 * endpoints, only the groups it was handed. Every control is a plain text input (a number or date bound is
 * coerced by the backend), which is also the one antd control a browser test can drive.
 */
external interface FormsSearchProps : Props {
    /** The declared search groups, in display order. */
    var groups: List<SearchGroup>

    /** The current value of each parameter, keyed by name; the free-text term is under [EI.q]. */
    var values: Map<String, String>

    /** The parameters the list is currently filtered by, for the chips and the count. */
    var applied: Map<String, Any?>

    /** Whether the filter panel is open. */
    var panelOpen: Boolean

    /** Opens or closes the filter panel. */
    var onTogglePanel: () -> Unit

    /** Records a keystroke in one parameter's box. */
    var onChange: (String, String) -> Unit

    /** Applies the current values as the list's filter. */
    var onSearch: () -> Unit

    /** Clears the term and every filter (not the user scope, which has its own control). */
    var onClear: () -> Unit
}

val FormsSearch = FC<FormsSearchProps> { props ->
    val groups = props.groups
    // The free-text term searches the text fields, so the box is offered only where there is one to search.
    val hasText = groups.any { it.kind == UsageKind.string }
    val chips = activeFilterChips(groups, props.applied)
    val termApplied = props.applied[EI.q]?.toString()?.isNotBlank() == true
    div {
        className = ClassName("row forms-toolbar")
        if (hasText) {
            Input {
                placeholder = "Search all text fields"
                value = props.values[EI.q] ?: ""
                allowClear = true
                style = js("({ width: 300 })")
                onChange = { event -> props.onChange(EI.q, event.target.value as? String ?: "") }
                onPressEnter = { props.onSearch() }
            }
            Button {
                type = "primary"
                onClick = { props.onSearch() }
                +"Search"
            }
        }
        Button {
            onClick = { props.onTogglePanel() }
            +(if (props.panelOpen) "Hide filters" else if (chips.isEmpty()) "Filters" else "Filters (${chips.size})")
        }
        if (termApplied || chips.isNotEmpty()) {
            Button {
                type = "link"
                onClick = { props.onClear() }
                +"Clear"
            }
        }
    }
    if (!props.panelOpen && chips.isNotEmpty()) {
        div {
            className = ClassName("forms-chips")
            chips.forEach { chip ->
                span {
                    className = ClassName("filter-chip")
                    +chip
                }
            }
        }
    }
    if (props.panelOpen) {
        div {
            className = ClassName("forms-filters")
            groups.forEach { group ->
                div {
                    className = ClassName("filter-group")
                    span {
                        className = ClassName("filter-label")
                        +group.label
                    }
                    if (group.isRange) {
                        val hint = if (group.kind == UsageKind.date) " yyyy-mm-dd" else ""
                        div {
                            className = ClassName("filter-range")
                            boundInput(props, group.min, "from$hint")
                            span {
                                className = ClassName("type-hint")
                                +"to"
                            }
                            boundInput(props, group.max, "to$hint")
                        }
                    } else {
                        group.text?.let { name ->
                            Input {
                                placeholder = if (group.contains != null) "contains" else "is exactly"
                                value = props.values[name] ?: ""
                                allowClear = true
                                style = js("({ width: 260 })")
                                onChange = { event -> props.onChange(name, event.target.value as? String ?: "") }
                                onPressEnter = { props.onSearch() }
                            }
                        }
                    }
                }
            }
            div {
                className = ClassName("row filter-actions")
                Button {
                    type = "primary"
                    onClick = { props.onSearch() }
                    +"Apply filters"
                }
                Button {
                    type = "link"
                    onClick = { props.onClear() }
                    +"Clear filters"
                }
            }
        }
    }
}

/** One end of a from-to pair: a text box for the bound parameter [name], or nothing when the pair lacks it. */
private fun react.ChildrenBuilder.boundInput(props: FormsSearchProps, name: String?, placeholder: String) {
    if (name == null) return
    Input {
        this.placeholder = placeholder
        value = props.values[name] ?: ""
        allowClear = true
        style = js("({ width: 150 })")
        onChange = { event -> props.onChange(name, event.target.value as? String ?: "") }
        onPressEnter = { props.onSearch() }
    }
}
