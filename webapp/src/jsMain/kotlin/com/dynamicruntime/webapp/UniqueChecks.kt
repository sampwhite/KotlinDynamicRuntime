package com.dynamicruntime.webapp

import com.dynamicruntime.common.endpoint.EI
import com.dynamicruntime.common.endpoint.EP
import com.dynamicruntime.common.endpoint.clientPath
import com.dynamicruntime.common.gedra.GDF
import com.dynamicruntime.common.gedra.GE
import com.dynamicruntime.common.gedra.GEP
import com.dynamicruntime.common.gedra.UNQ
import com.dynamicruntime.common.gedra.normalizeUnique
import com.dynamicruntime.common.gedra.uniqueFieldsOf
import com.dynamicruntime.common.schema.SchFailCode
import com.dynamicruntime.common.schema.SchFailure
import com.dynamicruntime.common.schema.SchType
import com.dynamicruntime.common.util.toJsonListOfMaps
import com.dynamicruntime.common.util.toJsonMapOrEmpty
import com.dynamicruntime.common.util.toOptStr
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.launch
import react.useEffect
import react.useState

private val uniqueScope = MainScope()

/** A unique field's value to ask about (issue #1851): the trait, the field, and what it holds. */
data class UniqueAsk(val traitId: String, val field: String, val value: String)

/**
 * What to ask of [field] of a trait [traitId] of data type [type] holding [data] (issue #1851), or null when nothing: the
 * field need not be unique, or holds nothing a value is compared by. Pure, and covered under `jsNodeTest`.
 */
fun uniqueAskOf(traitId: String, type: SchType, field: String, data: Map<String, Any?>): UniqueAsk? {
    if (field !in uniqueFieldsOf(type)) return null
    val value = data[field].toOptStr()?.takeIf { normalizeUnique(it).isNotEmpty() } ?: return null
    return UniqueAsk(traitId, field, value)
}

private val entryFieldPath = Regex("""([A-Za-z]+)\[(\d+)\]\.${GE.data}\.([^.\[]+)""")

/**
 * What to ask of the field at [path] of a form whose [values] hold a list of entries, each a `{traitId, data}` (issue #1851):
 * a create's `entries` and a patch's `edits` alike. [dataTypeOf] is a trait's data type, or null for one the page does not
 * know. Null when there is nothing to ask. Pure, and covered under `jsNodeTest`.
 */
fun uniqueAskInEntries(values: Map<String, Any?>, path: String, dataTypeOf: (String) -> SchType?): UniqueAsk? {
    val (list, at, field) = entryFieldPath.matchEntire(path)?.destructured ?: return null
    val entry = values[list].toJsonListOfMaps().getOrNull(at.toInt()) ?: return null
    val traitId = entry[GE.traitId].toOptStr() ?: return null
    val type = dataTypeOf(traitId) ?: return null
    return uniqueAskOf(traitId, type, field, entry[GE.data].toJsonMapOrEmpty())
}

/** The data type of a trait in the [list] of entries of a form of [inputType]; null for one its variants do not list. */
fun entryDataType(inputType: SchType?, list: String, traitId: String): SchType? =
    inputType?.properties?.get(list)?.valueType?.itemType?.variants?.select(traitId)?.properties?.get(GE.data)?.valueType

/** What the form was told of a value it asked about: what to say when it is taken, or null when it is free. */
private class UniqueAnswer(val value: String, val message: String?)

/** What [useUniqueChecks] answers: [left] is called as a field is left, and [failures] are those to show now. */
class UniqueChecks(val left: (String) -> Unit, val failures: List<SchFailure>)

/**
 * Asks, as each unique field of a form is left, whether another of the owner's forms holds its value (issue #1851), and
 * shows the answer under the field for as long as the field holds that value. [askAt] is what to ask of the field at a
 * path, null for one not unique. The owner is the form's named by [gedraId], or the caller's own, or [userRef]'s for a form
 * made for someone; [client] names another client's copy of the endpoint. An ask that fails says nothing: the save's own
 * check is the one that holds the value.
 */
fun useUniqueChecks(
    client: String? = null,
    gedraId: String? = null,
    userRef: String? = null,
    askAt: (String) -> UniqueAsk?,
): UniqueChecks {
    val pendingState = useState<Set<String>>(emptySet())
    val pending by pendingState
    val updatePending = pendingState.component2()
    val answersState = useState<Map<String, UniqueAnswer>>(emptyMap())
    val answers by answersState
    val updateAnswers = answersState.component2()
    // A form made for someone else is asked about again as theirs.
    useEffect(userRef) { updateAnswers { emptyMap() } }
    // After the render that holds the value just left, as the form's other checks wait for it (`onFieldCommit`).
    useEffect(pending) {
        if (pending.isEmpty()) return@useEffect
        val asks = pending.mapNotNull { path -> askAt(path)?.let { path to it } }
        updatePending { emptySet() }
        uniqueScope.launch {
            for ((path, ask) in asks) {
                val query = queryString(
                    buildMap {
                        put(UNQ.traitId, ask.traitId)
                        put(UNQ.field, ask.field)
                        put(UNQ.value, ask.value)
                        gedraId?.let { put(GDF.gedraId, it) }
                        userRef?.let { put(EI.user, it) }
                    },
                )
                val endpoint = if (client == null) GEP.uniqueCheck else clientPath(GEP.uniqueCheck, client)
                val results = Http.getApiResult("$endpoint$query").valueOrNull()?.get(EP.results).toJsonMapOrEmpty()
                val message = if (results[UNQ.taken] == true) results[UNQ.message].toOptStr() else null
                updateAnswers { it + (path to UniqueAnswer(ask.value, message)) }
            }
        }
    }
    val failures = answers.mapNotNull { (path, answer) ->
        answer.message?.takeIf { askAt(path)?.value == answer.value }?.let { SchFailure(path, SchFailCode.badValue, it) }
    }
    return UniqueChecks({ path -> if (askAt(path) != null) updatePending { it + path } }, failures)
}

/** [failures] with [taken] beside them, for a form; null -- nothing checked yet -- stays so when none is taken. */
fun withTaken(failures: List<SchFailure>?, taken: List<SchFailure>): List<SchFailure>? =
    if (taken.isEmpty()) failures else failures.orEmpty() + taken
