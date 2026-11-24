package com.dynamicruntime.webapp

import com.dynamicruntime.common.counter.CTR
import com.dynamicruntime.common.counter.counterFieldsOf
import com.dynamicruntime.common.endpoint.EP
import com.dynamicruntime.common.endpoint.clientPath
import com.dynamicruntime.common.gedra.GDF
import com.dynamicruntime.common.gedra.GE
import com.dynamicruntime.common.gedra.GEP
import com.dynamicruntime.common.schema.SchType
import com.dynamicruntime.common.util.toJsonListOfMaps
import com.dynamicruntime.common.util.toJsonMapOrEmpty
import com.dynamicruntime.common.util.toOptStr
import react.StateSetter
import react.useEffect
import react.useState

/** An id a counter issued (issue #1849): the [value] its field shows, and the [token] the save sends back to store it. */
class IssuedId(val value: String, val token: String)

/** Asks a client's counters for ids (issue #1849). */
object CounterApi {
    /**
     * The next id of [counterId]: from [client]'s copy of the endpoint when one is named -- a form being made in another
     * client -- else the caller's own client's. Each call uses up an id.
     */
    suspend fun issue(counterId: String, client: String? = null): IssuedId {
        val path = if (client == null) GEP.counterIssue else clientPath(GEP.counterIssue, client)
        val results = Http.sendApi("POST", path, mapOf(CTR.counter to counterId))[EP.results].toJsonMapOrEmpty()
        return IssuedId(results[CTR.value].toOptStr().orEmpty(), results[CTR.token].toOptStr().orEmpty())
    }
}

/** A field a counter fills, on a trait of a form being created: the trait, the field, and the counter (issue #1849). */
data class CounterSlot(val traitId: String, val field: String, val counterId: String)

/**
 * The counters a catalog or a workflow view says give their ids when a form is saved ([CTR.issueOnSave], issue #1852),
 * from its [CTR.counters] map: a page asks for none of theirs and leaves their fields to the server on a create. Empty
 * when the map is absent -- a client with no counters, or an older node -- and every counter then issues as a form
 * opens. Pure, and covered under `jsNodeTest`.
 */
fun countersOnSaveOf(raw: Any?): Set<String> =
    raw.toJsonMapOrEmpty().filterValues { it.toJsonMapOrEmpty()[CTR.issue] == CTR.issueOnSave }.keys

/**
 * The counter fields of [traits] -- each trait id with its data type -- that [valuesOf] gives no value yet, each trait
 * once: what a create page asks ids for as it opens, so the person sees them before the form is saved. A counter in
 * [onSave] gives its ids when the form is saved instead (issue #1852), so its fields are not asked for here. Pure, and
 * covered under `jsNodeTest`.
 */
fun counterSlotsToFill(
    traits: List<Pair<String, SchType>>,
    onSave: Set<String> = emptySet(),
    valuesOf: (String) -> Map<String, Any?>?,
): List<CounterSlot> =
    traits.distinctBy { it.first }.flatMap { (traitId, type) ->
        val values = valuesOf(traitId) ?: return@flatMap emptyList()
        counterFieldsOf(type).filter { (field, id) -> id !in onSave && values[field].toOptStr().isNullOrEmpty() }
            .map { (field, counterId) -> CounterSlot(traitId, field, counterId) }
    }

/**
 * The fields of a form section of [type] holding [values] that a counter gives an id when the form is saved (issue
 * #1852): those the form ([opts]) leaves off for that reason alone. One its layout does not list, the viewer is not
 * offered, or the current answers rule out is given none. Pure, and covered under `jsNodeTest`.
 */
fun fieldsGivenAtSave(type: SchType, values: Map<String, Any?>, opts: FormOpts): Set<String> =
    editedFieldPlan(type, values, opts).filter { it.hidden == FieldHidden.givenAtSave }.map { it.name }.toSet()

/** What an edited form section of [type] does with each field, as [SchemaForm] plans it at its root. */
private fun editedFieldPlan(type: SchType, values: Map<String, Any?>, opts: FormOpts): List<PlannedField> =
    formFieldPlan(type, values, "", true, type.condition?.let { it.forbiddenWhen(it.holds(values)) }.orEmpty(), opts)

/**
 * Whether an edited form section of [type] holding [values] has nothing to show until the form is saved (issue #1852): a
 * counter is to give it an id then, and the form ([opts]) draws none of its other fields, so a page draws no section at
 * all. Pure, and covered under `jsNodeTest`.
 */
fun hiddenUntilSaved(type: SchType, values: Map<String, Any?>, opts: FormOpts): Boolean {
    val plan = editedFieldPlan(type, values, opts)
    return plan.any { it.hidden == FieldHidden.givenAtSave } && plan.none { it.hidden == null }
}

/**
 * The ids [onSave] counters gave a form when it was saved (issue #1852), out of its stored [entries]: what a page names
 * once the form is made, since the person never saw them before. [dataTypeOf] is a trait's data type, or null for one
 * the page does not know. Pure, and covered under `jsNodeTest`.
 */
fun idsGivenAtSave(entries: List<Map<String, Any?>>, onSave: Set<String>, dataTypeOf: (String) -> SchType?): List<String> {
    if (onSave.isEmpty()) return emptyList()
    return entries.flatMap { entry ->
        val type = entry[GE.traitId].toOptStr()?.let(dataTypeOf) ?: return@flatMap emptyList()
        val data = entry[GE.data].toJsonMapOrEmpty()
        counterFieldsOf(type).filterValues { it in onSave }.keys.mapNotNull { data[it].toOptStr()?.ifBlank { null } }
    }
}

/**
 * The counter fields of [entries] -- a create's `{traitId, data}` entries -- that have no id yet, one slot an entry and
 * field, so two entries of a keyed trait are each given their own. [dataTypeOf] is a trait's data type, or null for one
 * the page does not know; an [onSave] counter's fields are left to the server (issue #1852). Pure, and covered under
 * `jsNodeTest`.
 */
fun counterSlotsInEntries(
    entries: List<Map<String, Any?>>,
    onSave: Set<String> = emptySet(),
    dataTypeOf: (String) -> SchType?,
): List<CounterSlot> =
    entries.flatMap { entry ->
        val traitId = entry[GE.traitId].toOptStr() ?: return@flatMap emptyList()
        counterSlotsToFill(listOfNotNull(dataTypeOf(traitId)?.let { traitId to it }), onSave) { entry[GE.data].toJsonMapOrEmpty() }
    }

/**
 * [entries] -- a create's `{traitId, data}` entries -- with [value] put in [slot]'s field of the first entry of its trait
 * that has none, or as they were when every one has. How an id the page asked for lands where it was asked for, even
 * if the person added or moved sections while it was on its way.
 */
fun withCounterId(entries: List<Map<String, Any?>>, slot: CounterSlot, value: String): List<Map<String, Any?>> {
    val at = entries.indexOfFirst {
        it[GE.traitId] == slot.traitId && it[GE.data].toJsonMapOrEmpty()[slot.field].toOptStr().isNullOrEmpty()
    }
    if (at < 0) return entries
    val entry = entries[at]
    return entries.toMutableList().also { it[at] = entry + (GE.data to (entry[GE.data].toJsonMapOrEmpty() + (slot.field to value))) }
}

/** What [useCounterIds] answers: the tokens a create sends with the ids it was given, and a failure to get one. */
class CounterIds(val tokens: List<String>, val error: DisplayError?)

/**
 * The ids of a create page's counter fields (issue #1849), for a page whose form is a create's `entries` against
 * [inputType] -- the trait picker and the create for another user: each section of a trait a counter numbers is given
 * its id as it is added, so the person sees it before the form is saved, and [values] are updated through
 * [updateValues]. Asked of [client]'s copy of the endpoint when one is named. The ask runs again only when the sections
 * still lacking an id change, not on each keystroke; a section removed while its id is on its way leaves a gap. An
 * [onSave] counter's fields are asked nothing: the server gives their ids when the form is saved (issue #1852).
 */
fun useCounterIds(
    inputType: SchType?,
    values: Map<String, Any?>,
    updateValues: StateSetter<Map<String, Any?>>,
    client: String? = null,
    onSave: Set<String> = emptySet(),
): CounterIds {
    val tokensState = useState<List<String>>(emptyList())
    val tokens by tokensState
    val updateTokens = tokensState.component2()
    var error by useState<DisplayError?>(null)
    val variants = inputType?.properties?.get(GDF.entries)?.valueType?.itemType?.variants
    val slots = variants?.let { v ->
        counterSlotsInEntries(values[GDF.entries].toJsonListOfMaps(), onSave) { v.select(it)?.properties?.get(GE.data)?.valueType }
    }.orEmpty()
    useEffect(slots.joinToString { "${it.traitId}.${it.field}" }, client) {
        if (slots.isEmpty()) return@useEffect
        try {
            // Every id asked for before any lands: one landing changes the slots, which would start the ask over and use up
            // the ids still on their way.
            val issued = slots.map { it to CounterApi.issue(it.counterId, client) }
            updateValues { current ->
                current + (GDF.entries to issued.fold(current[GDF.entries].toJsonListOfMaps()) { acc, (slot, id) -> withCounterId(acc, slot, id.value) })
            }
            updateTokens { it + issued.map { (_, id) -> id.token } }
        } catch (e: Throwable) {
            error = userFacingError(e)
        }
    }
    return CounterIds(tokens, error)
}

/**
 * The ids counters that give theirs at save gave a form created through [ep] of [catalog] (issue #1852), out of the
 * stored form [item] the create answered with: what the listing names once the person is taken back to it. Each
 * trait's data type is read off the create's input, as [useCounterIds] reads it.
 */
fun givenIdsOf(catalog: Catalog, ep: EndpointInfo, item: Map<String, Any?>): List<String> {
    if (catalog.countersOnSave.isEmpty()) return emptyList()
    val variants = catalog.inputType(ep).properties[GDF.entries]?.valueType?.itemType?.variants ?: return emptyList()
    return idsGivenAtSave(item[GDF.entries].toJsonListOfMaps(), catalog.countersOnSave) {
        variants.select(it)?.properties?.get(GE.data)?.valueType
    }
}

/** [payload] with [tokens] beside it, as a create sends the tokens of the ids it stores (issue #1849). */
fun withCounterTokens(payload: Map<String, Any?>, tokens: List<String>): Map<String, Any?> =
    if (tokens.isEmpty()) payload else payload + (CTR.counterTokens to tokens)
