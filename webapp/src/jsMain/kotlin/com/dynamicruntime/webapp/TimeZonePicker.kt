package com.dynamicruntime.webapp

import com.dynamicruntime.common.gedra.ClientPresentationFields
import react.FC
import react.Props
import react.useMemo
import react.useState
import kotlin.time.Clock
import kotlin.time.Instant

/**
 * One zone the time-zone picker can offer (issue #1257): its IANA name; how a person reads it, "Central Time (Chicago)
 * · GMT−5", written by `Intl` so the webapp ships no zone data and daylight saving comes out right; the offset part
 * alone, for matching a typed offset; and the name `Intl` resolves it to, which is how two spellings of one zone are
 * known to be the same (Chrome lists `Asia/Calcutta` and resolves `Asia/Kolkata` to it).
 */
class TimeZoneChoice(val zone: String, val label: String, val offset: String, val key: String)

/** A heading in the picker's popup, and the zones under it. */
class TimeZoneGroup(val title: String, val choices: List<TimeZoneChoice>)

/**
 * The zones offered before anything is typed (issue #1257): one per region people pick, in the order shown, with the
 * modern spelling of each -- which is the one saved, even where the browser lists an older one. Everything else is a
 * search away.
 */
val commonTimeZones: List<String> = listOf(
    // The US and Canada, west to east, then Alaska and Hawaii.
    "America/Los_Angeles", "America/Denver", "America/Phoenix", "America/Chicago", "America/New_York",
    "America/Halifax", "America/St_Johns", "America/Anchorage", "Pacific/Honolulu",
    // Latin America.
    "America/Mexico_City", "America/Bogota", "America/Santiago", "America/Sao_Paulo", "America/Argentina/Buenos_Aires",
    // Europe.
    "Europe/London", "Europe/Lisbon", "Europe/Paris", "Europe/Berlin", "Europe/Athens", "Europe/Istanbul",
    "Europe/Moscow",
    // Africa.
    "Africa/Lagos", "Africa/Cairo", "Africa/Johannesburg", "Africa/Nairobi",
    // Asia.
    "Asia/Dubai", "Asia/Karachi", "Asia/Kolkata", "Asia/Dhaka", "Asia/Bangkok", "Asia/Singapore", "Asia/Shanghai",
    "Asia/Tokyo", "Asia/Seoul",
    // Australia and the Pacific.
    "Australia/Perth", "Australia/Adelaide", "Australia/Brisbane", "Australia/Sydney", "Pacific/Auckland",
    ClientPresentationFields.utcZone,
)

/**
 * Every zone the picker can offer, each once (issue #1257): [commonTimeZones] first, then the rest of [allZones] --
 * the browser's list, `Intl.supportedValuesOf('timeZone')`, which leaves out `UTC` -- labeled for [at]. A zone listed
 * under two spellings is offered under the first, so a common zone keeps its modern one; a name the client check
 * would refuse (an `Etc/` offset), or one `Intl` cannot format, is left out. Pure over its arguments (it reads `Intl`
 * for the labels), and covered under `jsNodeTest`.
 */
fun timeZoneCatalog(allZones: List<String>, at: Instant): List<TimeZoneChoice> {
    val seen = HashSet<String>()
    return (commonTimeZones + allZones).filter { ClientPresentationFields.isPlaceZoneName(it) }.mapNotNull { zone ->
        timeZoneChoice(zone, at)?.takeIf { seen.add(it.key) }
    }
}

/**
 * [zone] as the picker shows it at [at] (issue #1257), or null when `Intl` does not know it. The name is `Intl`'s long
 * generic one ("Central Time"), else its long one ("Coordinated Universal Time", for which the generic is merely
 * "GMT"), and none where both are only an offset; the place is the id's last segment ("Chicago", "St Johns"), since
 * several places share a name; the offset is `Intl`'s short one, with a true minus sign.
 */
fun timeZoneChoice(zone: String, at: Instant): TimeZoneChoice? = try {
    val epochMs = at.toEpochMilliseconds().toDouble()
    val name = listOf("longGeneric", "long").firstNotNullOfOrNull { style ->
        intlZoneName(epochMs, zone, style)?.takeUnless { it.startsWith("GMT") }
    }
    val offset = (intlZoneName(epochMs, zone, "shortOffset") ?: "").replace('-', '\u2212')
    val place = zone.substringAfterLast('/').replace('_', ' ')
    val label = (if (name == null) place else "$name ($place)") + if (offset.isEmpty()) "" else " \u00b7 $offset"
    TimeZoneChoice(zone, label, offset, intlResolvedZone(zone))
} catch (_: Throwable) {
    // An unknown zone is a RangeError from Intl.
    null
}

/**
 * The groups the picker's popup shows (issue #1257). Pinned at the top: the client's [currentZone] -- under exactly
 * the stored spelling, so the box shows it and an untouched draft does not count as a change, and even when the
 * [catalog] lacks it -- and the [browserZone], the person's own; one group when they are the same zone. Then, with no
 * [query], the [commonTimeZones]; with one, every zone in the catalog that matches it by place, name, id, or offset
 * (`-5`, `GMT+5:30`), the pins included only when they match too. Pure, and covered under `jsNodeTest`.
 */
fun timeZoneOptions(catalog: List<TimeZoneChoice>, query: String, browserZone: String?, currentZone: String?): List<TimeZoneGroup> {
    val byZone = catalog.associateBy { it.zone }
    fun known(zone: String) = byZone[zone] ?: catalog.firstOrNull { it.key == resolvedOrNull(zone) }
    val current = currentZone?.takeIf { it.isNotBlank() }?.let { zone ->
        known(zone)?.let { TimeZoneChoice(zone, it.label, it.offset, it.key) }
            ?: TimeZoneChoice(zone, zone, "", zone)
    }
    val browser = browserZone?.let { known(it) }
    val matches = timeZoneMatcher(query)
    val pins = buildList {
        if (current != null && browser != null && current.key == browser.key) {
            add(TimeZoneGroup("Current, and this browser's", listOf(current)))
        } else {
            current?.let { add(TimeZoneGroup("Current", listOf(it))) }
            browser?.let { add(TimeZoneGroup("This browser's", listOf(it))) }
        }
    }.mapNotNull { g -> g.choices.filter(matches).takeIf { it.isNotEmpty() }?.let { TimeZoneGroup(g.title, it) } }
    val pinned = pins.flatMap { g -> g.choices.map { it.key } }.toSet()
    val rest = if (query.isBlank()) {
        TimeZoneGroup("Common zones", commonTimeZones.mapNotNull { byZone[it] }.filter { it.key !in pinned })
    } else {
        TimeZoneGroup("Matching zones", catalog.filter { it.key !in pinned && matches(it) })
    }
    return if (rest.choices.isEmpty()) pins else pins + rest
}

/**
 * What a typed [query] matches (issue #1257): an offset -- `-5`, `+5:30`, `GMT+10`, `UTC-3` -- matches the zones at
 * exactly that offset now; anything else matches by substring of the id or the label, ignoring case, `_` read as a
 * space and either minus sign as one. A blank query matches everything.
 */
fun timeZoneMatcher(query: String): (TimeZoneChoice) -> Boolean {
    val q = normalizeZoneText(query)
    if (q.isEmpty()) return { true }
    val offset = offsetQuery.matchEntire(q)
    if (offset != null) {
        val (sign, hours, minutes) = offset.destructured
        val wanted = "gmt$sign${hours.trimStart('0').ifEmpty { "0" }}" + if (minutes.isEmpty() || minutes == "00") "" else ":$minutes"
        return { normalizeZoneText(it.offset) == wanted }
    }
    return { normalizeZoneText(it.zone).contains(q) || normalizeZoneText(it.label).contains(q) }
}

private val offsetQuery = Regex("""^(?:gmt|utc)?\s*([+-])(\d{1,2})(?::?(\d{2}))?$""")

private fun normalizeZoneText(text: String) = text.trim().lowercase().replace('_', ' ').replace('\u2212', '-')

/**
 * A client's [zone] as a read-only row shows it (issue #1257): the picker's label and the id beside it, since the label
 * is a reading and the id what is stored; the id alone when `Intl` does not know it; and what none means.
 */
fun timeZoneText(zone: String?, at: Instant): String {
    if (zone.isNullOrBlank()) return "None: the deployment's"
    return timeZoneChoice(zone, at)?.let { "${it.label} \u00b7 $zone" } ?: zone
}

/** The browser's own zone, as `Intl` reports it, or null when it reports none. */
fun browserTimeZone(): String? = try {
    (js("Intl.DateTimeFormat().resolvedOptions().timeZone") as? String)?.ifBlank { null }
} catch (_: Throwable) {
    null
}

/** Every zone the browser knows (`Intl.supportedValuesOf`), or none where it cannot list them. */
fun intlTimeZones(): List<String> = try {
    (js("Intl.supportedValuesOf('timeZone')") as Array<String>).toList()
} catch (_: Throwable) {
    emptyList()
}

private fun resolvedOrNull(zone: String): String? = try {
    intlResolvedZone(zone)
} catch (_: Throwable) {
    null
}

/** The name `Intl` resolves [zone] to (`Asia/Kolkata` to `Asia/Calcutta`, in Chrome); throws for a zone it does not know. */
@Suppress("UNUSED_VARIABLE")
private fun intlResolvedZone(zone: String): String {
    val options: dynamic = js("({})")
    options.timeZone = zone
    // Two steps, as in intlZoneName: in one, `js()` reads `new X(...).resolvedOptions()` as a constructor call.
    val format: dynamic = js("new Intl.DateTimeFormat('en-US', options)")
    return format.resolvedOptions().timeZone as String
}

/** [zone]'s name in the [style] `Intl` names it (`longGeneric`, `shortOffset`, ...) at [epochMs]; null if it gives none. */
@Suppress("UNUSED_VARIABLE")
private fun intlZoneName(epochMs: Double, zone: String, style: String): String? {
    val options: dynamic = js("({})")
    options.timeZone = zone
    options.timeZoneName = style
    // Two steps, not one expression: `js()` reads `new X(...).formatToParts(...)` as a constructor call.
    val format: dynamic = js("new Intl.DateTimeFormat('en-US', options)")
    val date: dynamic = js("new Date(epochMs)")
    val parts: Array<dynamic> = format.formatToParts(date)
    return parts.firstOrNull { it.type == "timeZoneName" }?.value as? String
}

/** The options antd's Select takes for [groups]: a `{ label, options }` group each, its zones as `{ label, value }`. */
fun timeZoneGroupsToJs(groups: List<TimeZoneGroup>): Array<dynamic> = groups.map { g ->
    val group: dynamic = js("({})")
    group.label = g.title
    group.title = g.title
    group.options = choiceOptions(g.choices.map { it.label to it.zone })
    group
}.toTypedArray()

external interface TimeZonePickerProps : Props {
    /** The zone chosen, an IANA name; blank for none (the deployment's). */
    var value: String
    var disabled: Boolean
    /** The zone picked, or blank when the box is cleared. */
    var onChange: (String) -> Unit
}

/**
 * A client's time zone, picked rather than typed (issue #1257): an antd `Select` whose options it builds itself
 * ([timeZoneOptions]) from what has been typed, rather than leaving antd to filter -- the short list before anything
 * is typed would otherwise be all ~420 zones. Clearing it is "none: the deployment's". The search text is the one
 * piece of state, owned here and handed back to antd as `searchValue`, so the box and the list cannot disagree; it
 * empties on a pick and when the popup closes.
 */
val TimeZonePicker = FC<TimeZonePickerProps> { props ->
    // Labeled once, when the picker is drawn: 420 Intl formats per keystroke would show.
    val catalog = useMemo { timeZoneCatalog(intlTimeZones(), Clock.System.now()) }
    val browserZone = useMemo { browserTimeZone() }
    var query by useState("")
    val search: dynamic = js("({ filterOption: false })")
    search.searchValue = query
    search.onSearch = { text: String -> query = text }
    Select {
        value = props.value.ifBlank { null }
        options = timeZoneGroupsToJs(timeZoneOptions(catalog, query, browserZone, props.value))
        showSearch = search
        allowClear = true
        placeholder = "None: the deployment's"
        notFoundContent = "No zone matches. Try a city, a name, or an offset such as -5."
        disabled = props.disabled
        style = js("({ minWidth: 340 })")
        onChange = { v ->
            query = ""
            props.onChange((v as? String).orEmpty())
        }
        onOpenChange = { open -> if (!open) query = "" }
    }
}
