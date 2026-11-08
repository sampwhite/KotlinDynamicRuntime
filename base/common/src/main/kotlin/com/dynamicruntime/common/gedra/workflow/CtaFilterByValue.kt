package com.dynamicruntime.common.gedra.workflow

import com.dynamicruntime.common.context.KdrCxt
import com.dynamicruntime.common.context.KdrCxtBase
import com.dynamicruntime.common.gedra.GE
import com.dynamicruntime.common.schema.SCT
import com.dynamicruntime.common.schema.SchType
import com.dynamicruntime.common.schema.parseSchemaTypes
import com.dynamicruntime.common.schema.schemaDefs
import com.dynamicruntime.common.util.pluckDataPath
import com.dynamicruntime.common.util.toJsonListOfMaps
import com.dynamicruntime.common.util.toJsonMapOrEmpty
import com.dynamicruntime.common.util.toOptStr

/** The `ctaFilterByValue` function's name, its initialization-data fields, and its schema names (issue #1594). */
@Suppress("ConstPropertyName")
object CFV {
    /** The `fn` discriminator this function is declared under. */
    const val fn = "ctaFilterByValue"

    /** The trait whose entry data is read. Any trait of the form, not only one the task collects. */
    const val trait = "trait"

    /** A dotted path into the trait entry's data, whose value chooses the case. */
    const val valuePath = "valuePath"

    /** The `{value, filter, label?}` cases, tried in order; the first whose [value] matches decides. */
    const val cases = "cases"

    /** Under a [cases] entry: the data value that chooses it. */
    const val value = "value"

    /** Under a [cases] entry, or [otherwise]: the CTA filter, a cfact expression naming only facts about the person. */
    const val filter = "filter"

    /** Under a [cases] entry, or [otherwise]: the filter's label, a template saying who the CTA waits on. */
    const val label = "label"

    /**
     * The `{filter, label?}` used when no case matches, or the value is absent. Required, so a gap in the cases -- a
     * blank or unlisted value, a misspelled path -- leaves the CTA to whoever the author chose, never open to everyone.
     */
    const val otherwise = "otherwise"

    /** The search keyword ([CTAK]) to set, whatever the value. Optional. */
    const val keyword = "keyword"

    /** The schema namespace and type names for this function's initialization data. */
    const val namespace = "kdr.wffncfv"
    const val initDataType = "CtaFilterByValue"
    const val caseType = "CFVCase"
    const val otherwiseType = "CFVOtherwise"
}

/** One choice of a [CtaFilterByValueFn] (issue #1594): the [filter] and [label] it sets, chosen by [value] (null for `otherwise`). */
class CtaFilterChoice(val value: String?, val filter: String, val label: String?)

/**
 * The first `ctaFilter` executor (issue #1594): read a trait entry's data at a path, and set the CTA filter of the
 * first case whose value matches -- "the reviewer for this form's region", where a `region` field's `northwest` chooses
 * `reviewerNorthwest`, a cfact a `userHasLabel` function on the task emits for the region's reviewers. A value that is
 * a list (a multi-select field, a spread path) chooses the first case any of its values matches. With no match, or no
 * value yet, [otherwise] decides: it is required, so a gap in the cases never falls back to `saveWhen` alone, which
 * would open the CTA to everyone it admits. It may also set a search keyword, whatever the value.
 *
 * Every filter it can set, and the trait it reads, are known from its definition, so the resolution pass holds them to
 * the person's facts and the client's traits before it ever runs ([possibleFilters], [readTraits]).
 */
class CtaFilterByValueFn(
    override val priority: Int,
    private val trait: String,
    private val valuePath: String,
    private val cases: List<CtaFilterChoice>,
    private val otherwise: CtaFilterChoice,
    private val keyword: String?,
) : CtaFilterFn {
    override val fn: String = CFV.fn

    override val possibleFilters: List<String> = (cases + otherwise).map { it.filter }

    override val readTraits: Set<String> = setOf(trait)

    override val mayLabel: Boolean = (cases + otherwise).any { it.label != null }

    override fun computeCtaFilter(cxt: KdrCxt, params: CtaFilterParams) {
        keyword?.let { params.setKeyword(it) }
        val entry = params.entries.firstOrNull { it[GE.traitId].toOptStr() == trait }
        // A list (a multi-select, a spread path) is read as its values, a scalar as one, as computeCFactsFromData does.
        val values = when (val plucked = entry?.let { pluckDataPath(it[GE.data].toJsonMapOrEmpty(), valuePath) }) {
            null -> emptySet()
            is List<*> -> plucked.mapNotNull { it.toOptStr() }.toSet()
            else -> setOfNotNull(plucked.toOptStr())
        }
        val chosen = cases.firstOrNull { it.value in values } ?: otherwise
        params.setFilter(chosen.filter, chosen.label)
    }
}

/**
 * Builds a [CtaFilterByValueFn] from a usage's initialization data (issue #1594), validating it against
 * [initDataType] and throwing -- which the resolution pass turns into a config problem -- when it does not conform.
 * Registered in `CommonComponent` through `SchemaCollector.addWorkflowFunction`.
 */
object CtaFilterByValueCreation : WfFunctionCreation {
    override val fn: String = CFV.fn
    override val event: WfEventType = WfEventType.ctaFilter

    override fun create(cxt: KdrCxt, usage: WfFunctionUsage): WfFunction {
        val m = validatedInitData(initDataType(cxt), usage, CFV.fn)
        return CtaFilterByValueFn(
            priority = usage.priority,
            trait = m[CFV.trait].toOptStr() ?: "",
            valuePath = m[CFV.valuePath].toOptStr() ?: "",
            cases = m[CFV.cases].toJsonListOfMaps().map { choiceOf(it[CFV.value].toOptStr(), it) },
            otherwise = choiceOf(null, m[CFV.otherwise].toJsonMapOrEmpty()),
            keyword = m[CFV.keyword].toOptStr(),
        )
    }

    // Read off the usage as written: a keyword not on the list is refused when the usage is created, and lists nothing then.
    override fun searchKeywords(usage: WfFunctionUsage): Set<String> =
        setOfNotNull(usage.initData[CFV.keyword].toOptStr()?.takeIf { it in CTAK.all })

    private fun choiceOf(value: String?, m: Map<String, Any?>) =
        CtaFilterChoice(value, m[CFV.filter].toOptStr() ?: "", m[CFV.label].toOptStr())

    // Parsed once and kept: the schema is a constant of the runtime, matching the other function kinds.
    private var parsed: SchType? = null

    override val initDataTypeName: String = "${CFV.namespace}.${CFV.initDataType}"

    override fun initDataDefs(cxt: KdrCxtBase): Map<String, Any?> = schemaDefs(cxt, CFV.namespace) {
        type(CFV.caseType) {
            type = SCT.kObject
            description = "One case: the CTA filter set when the data value matches."
            property(CFV.value, "The data value that chooses this case.", required = true)
            property(CFV.filter, "The CTA filter: a cfact expression naming only facts about the person.", required = true)
            property(CFV.label, "The filter's label: a template saying who the CTA waits on.")
        }
        type(CFV.otherwiseType) {
            type = SCT.kObject
            description = "The CTA filter set when no case matches, or the value is absent."
            property(CFV.filter, "The CTA filter: a cfact expression naming only facts about the person.", required = true)
            property(CFV.label, "The filter's label: a template saying who the CTA waits on.")
        }
        type(CFV.initDataType) {
            type = SCT.kObject
            description = "Set a task's CTA filter from a value in the form's data, and optionally its search keyword."
            property(WFD.fn, "The function discriminator; always '${CFV.fn}' here.", required = true) { const = CFV.fn }
            property(WFD.priority, "Run order within the task's ctaFilter list.") { type = SCT.integer }
            property(CFV.trait, "The trait whose entry data is read; any trait of the form.", required = true)
            property(CFV.valuePath, "A dotted path into the trait entry's data, whose value chooses the case.", required = true)
            property(CFV.cases, "The cases, tried in order; the first whose value matches decides.", required = true) {
                type = SCT.array
                allowCoerce = true
                items { ref(CFV.caseType) }
            }
            property(CFV.otherwise, "The filter when no case matches, or the value is absent.", required = true) {
                ref(CFV.otherwiseType)
            }
            property(CFV.keyword, "The search keyword to set, whatever the value.") { CTAK.all.forEach { option(it) } }
        }
    }

    private fun initDataType(cxt: KdrCxt): SchType =
        parsed ?: parseSchemaTypes(initDataDefs(cxt)).getValue(initDataTypeName).also { parsed = it }
}

/**
 * Authors a [CFV.fn] usage's initialization data (issue #1594), used inside a `task(...) { function(ctaFilterByValue
 * { ... }) }` block. It emits the shape [CtaFilterByValueCreation] parses, so a code-built and a stored usage are alike.
 */
class CtaFilterByValueBuilder {
    /** The trait whose entry data is read. */
    var trait: String = ""

    /** A dotted path into the trait entry's data. */
    var valuePath: String = ""

    /** The search keyword ([CTAK]) to set, whatever the value; unset sets none. */
    var keyword: String? = null

    /** Run order within the task's ctaFilter list; unset leaves it at the default. */
    var priority: Int? = null

    private val cases = mutableListOf<Map<String, Any?>>()
    private var otherwise: Map<String, Any?>? = null

    /** The filter (and its label) set when the value is [value]. */
    fun case(value: String, filter: String, label: String? = null) {
        cases.add(buildMap { put(CFV.value, value); put(CFV.filter, filter); label?.let { put(CFV.label, it) } })
    }

    /** The filter (and its label) set when no case matches, or the value is absent. Required: see [CFV.otherwise]. */
    fun otherwise(filter: String, label: String? = null) {
        otherwise = buildMap { put(CFV.filter, filter); label?.let { put(CFV.label, it) } }
    }

    /** The usage's initialization data, ready for `function(...)`. */
    fun build(): Map<String, Any?> = buildMap {
        put(WFD.fn, CFV.fn)
        priority?.let { put(WFD.priority, it) }
        put(CFV.trait, trait)
        put(CFV.valuePath, valuePath)
        put(CFV.cases, cases.toList())
        otherwise?.let { put(CFV.otherwise, it) }
        keyword?.let { put(CFV.keyword, it) }
    }
}

/** Builds [CFV.fn] initialization data with [CtaFilterByValueBuilder]; see it. */
fun ctaFilterByValue(build: CtaFilterByValueBuilder.() -> Unit): Map<String, Any?> = CtaFilterByValueBuilder().apply(build).build()
