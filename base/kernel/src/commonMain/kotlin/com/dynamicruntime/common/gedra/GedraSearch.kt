package com.dynamicruntime.common.gedra

import com.dynamicruntime.common.endpoint.EI
import com.dynamicruntime.common.endpoint.EP
import com.dynamicruntime.common.schema.SCH
import com.dynamicruntime.common.schema.SCT
import com.dynamicruntime.common.schema.SFMT
import com.dynamicruntime.common.schema.qualifyTypeName
import com.dynamicruntime.common.util.parseDate
import com.dynamicruntime.common.util.toJsonMapOrEmpty

/**
 * Turns a client's trait-usage rules into forms-list **search** (issue #538): the parameters the listing
 * endpoint advertises, and the predicate that filters a page by them. Pure and in `base/kernel`, beside the
 * usage model it reads -- the same computed **display value** a listing shows in a column is the value a
 * search matches against, so what a person sees is what they search, in one place.
 *
 * A usage contributes a parameter by its [ClientTraitUsage.kind]: a `string` an exact one (and a substring one
 * when the usage asks -- [ClientTraitUsage.substring]); a `number` or `date` a `>=`/`<=` pair, so a value can
 * be bounded from either end or both. The parameter names are derived here and read nowhere by hand -- the
 * schema properties and the predicate are both built from [gedraSearchParams], so an advertised parameter and
 * the field the predicate reads cannot drift.
 */
@Suppress("EnumEntryName")
enum class SearchRole {
    /** A `string` exact match (case-insensitive). */
    exact,

    /** A `string` substring match (case-insensitive). */
    contains,

    /** A `number`/`date` lower bound: the value is `>=` this. */
    min,

    /** A `number`/`date` upper bound: the value is `<=` this. */
    max,
}

/**
 * One search parameter a usage contributes: its wire [name], the column [label] it belongs to, the [traitId]
 * whose display value it filters, the [role] (how to compare), and the [kind] (how to read both sides). Built
 * once by [gedraSearchParams] and used to generate the schema property *and* to run the predicate, so the two
 * stay in step.
 */
class GedraSearchParam(
    val name: String,
    val label: String,
    val traitId: String,
    val role: SearchRole,
    val kind: UsageKind,
)

/**
 * The search parameters [usages] contribute, in declaration order. A `string` gives an exact parameter named
 * for the trait (the design's "the trait id becomes a search parameter") and, when [ClientTraitUsage.substring]
 * is set, a `<traitId>Contains` beside it; a `number` or `date` gives `<traitId>Min` and `<traitId>Max`.
 */
fun gedraSearchParams(usages: List<ClientTraitUsage>): List<GedraSearchParam> = buildList {
    for (usage in usages) {
        when (usage.kind) {
            UsageKind.string -> {
                add(GedraSearchParam(usage.traitId, usage.label, usage.traitId, SearchRole.exact, usage.kind))
                if (usage.substring) {
                    add(GedraSearchParam("${usage.traitId}Contains", usage.label, usage.traitId, SearchRole.contains, usage.kind))
                }
            }
            UsageKind.number, UsageKind.date -> {
                add(GedraSearchParam("${usage.traitId}Min", usage.label, usage.traitId, SearchRole.min, usage.kind))
                add(GedraSearchParam("${usage.traitId}Max", usage.label, usage.traitId, SearchRole.max, usage.kind))
            }
        }
    }
}

/**
 * The JSON-Schema `properties` fragment for [params] -- a `{ name -> propertySchema }` map to merge into the
 * listing's query type. Every parameter is optional (a search is), and a query param arrives as text, so a
 * `number` bound carries `g-allowCoerce` (a `date` coerces from its format already; see the schema layer).
 */
fun searchParamProperties(params: List<GedraSearchParam>): Map<String, Any?> {
    val props = LinkedHashMap<String, Any?>()
    for (param in params) {
        val prop = LinkedHashMap<String, Any?>()
        prop[SCH.title] = searchLabel(param)
        when {
            param.role == SearchRole.contains ->
                prop[SCH.description] = "Substring match (case-insensitive) on the '${param.traitId}' value."
            param.role == SearchRole.exact ->
                prop[SCH.description] = "Exact match (case-insensitive) on the '${param.traitId}' value."
            param.kind == UsageKind.number -> {
                prop[SCH.type] = SCT.number
                prop[SCH.allowCoerce] = true
                prop[SCH.description] = boundDescription(param)
            }
            else -> { // a date bound
                prop[SCH.type] = SCT.string
                prop[SCH.format] = SFMT.date
                prop[SCH.description] = boundDescription(param)
            }
        }
        props[param.name] = prop
    }
    return props
}

/** The short label a search control shows for [param] -- the column label, with the variant said in words. */
private fun searchLabel(param: GedraSearchParam): String = when (param.role) {
    SearchRole.exact -> param.label
    SearchRole.contains -> "${param.label} (contains)"
    SearchRole.min -> "${param.label} (min)"
    SearchRole.max -> "${param.label} (max)"
}

private fun boundDescription(param: GedraSearchParam): String {
    val relation = if (param.role == SearchRole.min) "at or above" else "at or below"
    return "Keep rows whose '${param.traitId}' value is $relation this."
}

/** The `$defs` key of the listing's query type: [GEP.formDocsQuery] qualified in the gedra namespace. */
fun formDocsQueryDefName(): String = qualifyTypeName(GEP.formDocsQuery, GEP.gedraNamespace)

/**
 * The listing query's **stable** field names -- the paging offset, the appended limit, the user filter, and the
 * free-text term -- which a generated search parameter must not take. A usage whose search parameter would land on one of these
 * is refused at boot ([searchParamCollisions]); this guards the merge regardless, so a slipped-through one
 * cannot silently rewrite a stable field's schema.
 */
val reservedQueryFieldNames: Set<String> = setOf(EP.offset, EP.limit, EI.user, EI.q)

/**
 * The search parameter names [usages] would generate that collide with a [reservedQueryFieldNames] entry -- the
 * boot check behind the guard in [withSearchProperties]. A `string` usage on a trait named `user` (say) mints an
 * exact parameter `user`, which would otherwise overwrite the listing's own user filter. Empty is the ordinary
 * case; a non-empty result is a client-config mistake to report.
 */
fun searchParamCollisions(usages: List<ClientTraitUsage>): List<String> =
    gedraSearchParams(usages).map { it.name }.filter { it in reservedQueryFieldNames }.distinct()

/**
 * [baseDef] (a JSON-Schema object type -- the listing's stable query type) with the search properties [usages]
 * contribute merged into its `properties` (issue #538). The base is returned untouched when the usages
 * contribute nothing, so a scope with no usages shares the base rather than a distinct-but-equal copy -- which
 * is what lets the per-client build hand back the global type when a client's search fields do not differ.
 *
 * A search property never **overwrites** a stable field the base already declares ([reservedQueryFieldNames]):
 * the base keeps it, and the colliding usage is left unsearchable rather than allowed to rewrite the paging or
 * user-filter schema. The boot check refuses such a usage; this is the structural backstop for one that slips.
 */
fun withSearchProperties(baseDef: Any?, usages: List<ClientTraitUsage>): Map<String, Any?> {
    val base = baseDef.toJsonMapOrEmpty()
    val props = searchParamProperties(gedraSearchParams(usages))
    if (props.isEmpty()) return base
    val merged = LinkedHashMap(base[SCH.properties].toJsonMapOrEmpty())
    for ((name, prop) in props) {
        if (!merged.containsKey(name)) merged[name] = prop
    }
    return base + (SCH.properties to merged)
}

/**
 * Whether a row satisfies every active search parameter (AND across them), given the row's display values keyed
 * by trait id ([displayByTrait]) and the [active] parameters paired with the value the caller supplied. A row
 * that carries no value for a parameter's trait -- an empty display string -- matches none of that trait's
 * parameters, which drops it from a search rather than faulting the page.
 */
fun matchesSearch(displayByTrait: Map<String, String>, active: List<Pair<GedraSearchParam, String>>): Boolean =
    active.all { (param, query) -> matchesOne(displayByTrait[param.traitId].orEmpty(), param, query) }

/** The trait ids of the `string` usages -- the fields the free-text term ([EI.q]) searches across. */
fun textSearchTraitIds(usages: List<ClientTraitUsage>): List<String> =
    usages.filter { it.kind == UsageKind.string }.map { it.traitId }

/**
 * Whether a row's display values match the free-text term [query] (issue #562): a case-insensitive substring
 * match against **any** of the [textTraitIds] (OR across fields), so one box finds a document by whichever of
 * its searchable text values the person remembers. A blank term matches everything -- an empty box is no
 * search, not a search for nothing -- and a row with no text values matches only a blank term.
 */
fun matchesAnyText(displayByTrait: Map<String, String>, textTraitIds: List<String>, query: String): Boolean {
    val term = query.trim()
    if (term.isEmpty()) return true
    return textTraitIds.any { displayByTrait[it].orEmpty().contains(term, ignoreCase = true) }
}

private fun matchesOne(value: String, param: GedraSearchParam, query: String): Boolean = when (param.role) {
    SearchRole.exact -> value.trim().equals(query.trim(), ignoreCase = true)
    SearchRole.contains -> value.contains(query.trim(), ignoreCase = true)
    // A bound over a value that is not a number/date at all -- an empty or malformed display -- is a non-match,
    // not a fault: a range asks "is this within the bounds", and a value that is not on the line is not.
    SearchRole.min -> compareBound(value, param.kind, query)?.let { it >= 0 } ?: false
    SearchRole.max -> compareBound(value, param.kind, query)?.let { it <= 0 } ?: false
}

/** Compares a row's [value] to the [query] bound as [kind]; null when either side does not read as that kind. */
private fun compareBound(value: String, kind: UsageKind, query: String): Int? = when (kind) {
    UsageKind.number -> {
        val v = value.trim().toDoubleOrNull()
        val q = query.trim().toDoubleOrNull()
        if (v == null || q == null) null else v.compareTo(q)
    }
    UsageKind.date -> {
        val v = runCatching { value.trim().parseDate() }.getOrNull()
        val q = runCatching { query.trim().parseDate() }.getOrNull()
        if (v == null || q == null) null else v.compareTo(q)
    }
    // A string is never a bound; guarded by construction (a string usage makes no min/max param), null here.
    UsageKind.string -> null
}
