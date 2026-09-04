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
import react.useEffect
import react.useRef
import react.useState
import web.cssom.ClassName

/** The fields on the reserved side of the listing query -- paging, the user scope and the free-text term --
 *  not search fields, and never shown as one. */
private val reservedQueryFields = setOf(EP.offset, EP.limit, EI.user, EI.q)

/** How long a type-ahead waits after a keystroke before it fetches, so a fast typist makes one call not many. */
private const val suggestDebounceMs = 200

/** At most this many user suggestions in the scope bar's dropdown -- a short list to pick from, not a listing. */
const val maxUserSuggestions = 8

/** How many value suggestions a filter box asks for -- a short dropdown, capped again on the backend. */
const val maxValueSuggestions = 10

/**
 * One user the scope-bar type-ahead offers (issue #581): the [label] shown (a name or public name with the
 * email, or the email alone) and the [value] a pick sends -- the email, which the listing's `user` parameter
 * resolves within the caller's own access.
 */
class UserPick(val label: String, val value: String)

/** The browser's `setTimeout`/`clearTimeout`, declared locally rather than reaching for a DOM wrapper -- the
 *  same idiom the app bar and the users page use for their debounces. */
private fun setTimer(block: () -> Unit, delayMs: Int): Int = js("setTimeout(block, delayMs)") as Int
private fun clearTimer(id: Int) {
    js("clearTimeout(id)")
}

/** antd `{label, value}` options for the user suggestions. */
private fun userPickOptions(picks: List<UserPick>): Array<dynamic> = picks.map { pick ->
    val o: dynamic = js("({})")
    o.label = pick.label
    o.value = pick.value
    o
}.toTypedArray()

/** antd `{value}` options for value suggestions, where the shown label is the value itself. */
private fun stringOptions(values: List<String>): Array<dynamic> = values.map { v ->
    val o: dynamic = js("({})")
    o.value = v
    o
}.toTypedArray()

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
        val shape = decodeSearchParam(name, prop, properties.keys)
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
 * The scope bar (issue #562, a type-ahead in #581), for a caller who administers other users: whose forms the
 * list shows. Promoted above the search rather than filed among the filters, because it changes *whose*
 * documents are on screen where a filter narrows *which* -- and drawn in the elevated-privilege tint so it
 * reads as the administrative control it is.
 *
 * A type-ahead over the users the caller administers: typing a name, username, or email fetches matching users
 * (debounced) and picking one confines the list to them. A raw id or email typed and applied without picking
 * still resolves through the listing's `user` parameter, which confines whatever is sent to the caller's own
 * access -- the suggestions only make the box easier to fill, never wider.
 */
external interface FormsScopeBarProps : Props {
    /** What the box holds. */
    var value: String

    /** The user the list is currently confined to, or null for everyone the caller may see. */
    var applied: String?

    /** Records a keystroke in the box. */
    var onChange: (String) -> Unit

    /** Confines the list to the user in the box (the raw-entry path: a typed id or email). */
    var onApply: () -> Unit

    /** Drops the user and shows everyone again. */
    var onShowEveryone: () -> Unit

    /**
     * Fetches user suggestions for a typed `term`, calling back with them (issue #581). Owned by the parent so
     * this stays free of the API; the box debounces the calls. A failure calls back with an empty list, which
     * leaves the box working as plain text.
     */
    var fetchUsers: (String, (List<UserPick>) -> Unit) -> Unit

    /** Confines the list to the picked user (by email), keeping the applied filters -- the pick path, distinct
     *  from [onApply]'s raw-entry path, so a stale draft never rides along. */
    var onPick: (String) -> Unit
}

val FormsScopeBar = FC<FormsScopeBarProps> { props ->
    var suggestions by useState<List<UserPick>>(emptyList())
    val timer = useRef<Int>(null)

    // Debounce a suggestion fetch on the typed value. A short term is not worth a search, so it clears the list
    // (and a name/email fragment is meaningful only from a couple of characters). Cleanup on the next run, the
    // codebase's idiom, so a fast typist never leaves two fetches racing.
    useEffect(props.value) {
        timer.current?.let { clearTimer(it) }
        timer.current = null
        val term = props.value.trim()
        if (term.length < 2) {
            suggestions = emptyList()
        } else {
            timer.current = setTimer({ props.fetchUsers(term) { suggestions = it } }, suggestDebounceMs)
        }
    }

    div {
        className = ClassName("row forms-scope")
        span {
            className = ClassName("forms-scope-label")
            +"Showing forms for"
        }
        AutoComplete {
            placeholder = "everyone you administer — or a name, email, or id"
            value = props.value
            options = userPickOptions(suggestions)
            allowClear = true
            // Show every fetched suggestion; the backend already narrowed them to the term.
            filterOption = false
            style = js("({ width: 360 })")
            onChange = { v -> props.onChange((v as? String) ?: "") }
            onSelect = { v ->
                suggestions = emptyList()
                props.onPick((v as? String) ?: "")
            }
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
 * The forms-list search (issue #538, regrouped in #562, type-ahead in #581): one box that searches every text
 * field at once, and behind a toggle the per-trait filters the client's usage rules declared -- one text box
 * per text trait, a from-to pair per number or date -- so the list keeps the screen and the filters are there
 * when wanted. While the panel is closed, the applied filters are summarized as chips so a narrowed list never
 * looks like the whole. A text trait's box suggests the distinct values that trait takes, when the caller's
 * surface can supply them ([fetchValues]).
 *
 * The parent owns the values and every action; the widgets own only their own suggestion state and debounce.
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

    /**
     * Fetches distinct value suggestions for a text trait (issue #581): given the trait id and a typed prefix,
     * calls back with the values. Null when the caller's surface carries no values endpoint (an older node),
     * and then the text boxes stay plain inputs. A failure calls back empty, leaving the box usable as text.
     */
    var fetchValues: ((String, String, (List<String>) -> Unit) -> Unit)?
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
                            val placeholder = if (group.contains != null) "contains" else "is exactly"
                            val fetch = props.fetchValues
                            if (fetch != null) {
                                FilterValueBox {
                                    this.traitId = group.traitId
                                    this.placeholder = placeholder
                                    this.value = props.values[name] ?: ""
                                    this.onChange = { v -> props.onChange(name, v) }
                                    this.fetchValues = fetch
                                }
                            } else {
                                Input {
                                    this.placeholder = placeholder
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

/**
 * One text-trait filter box with a value type-ahead (issue #581): as the person types, it suggests the distinct
 * values that trait takes across the caller's documents. Its own component so each box holds its own suggestion
 * state and debounce. The value it holds is still a plain "contains" fragment -- picking a suggestion only
 * fills the box, and the panel's Apply is what searches -- so an empty or failed fetch leaves it a text input.
 */
private external interface FilterValueBoxProps : Props {
    var traitId: String
    var placeholder: String
    var value: String
    var onChange: (String) -> Unit
    var fetchValues: (String, String, (List<String>) -> Unit) -> Unit
}

private val FilterValueBox = FC<FilterValueBoxProps> { props ->
    var suggestions by useState<List<String>>(emptyList())
    val timer = useRef<Int>(null)

    useEffect(props.value) {
        timer.current?.let { clearTimer(it) }
        timer.current = null
        val term = props.value.trim()
        if (term.isEmpty()) {
            suggestions = emptyList()
        } else {
            timer.current = setTimer({ props.fetchValues(props.traitId, term) { suggestions = it } }, suggestDebounceMs)
        }
    }

    AutoComplete {
        placeholder = props.placeholder
        value = props.value
        options = stringOptions(suggestions)
        allowClear = true
        filterOption = false
        style = js("({ width: 260 })")
        onChange = { v -> props.onChange((v as? String) ?: "") }
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
