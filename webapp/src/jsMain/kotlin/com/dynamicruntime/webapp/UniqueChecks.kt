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
import com.dynamicruntime.common.schema.failure
import com.dynamicruntime.common.util.toJsonListOfMaps
import com.dynamicruntime.common.util.toJsonMapOrEmpty
import com.dynamicruntime.common.util.toOptStr
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.launch
import react.useEffect
import react.useState

private val uniqueScope = MainScope()

/** A unique field's value to ask about (issue #1851): the trait, the field, its type, and what it holds. */
data class UniqueAsk(val traitId: String, val field: String, val fieldType: SchType, val value: String)

/**
 * What to ask of [field] of a trait [traitId] of data type [type] holding [data] (issue #1851), or null when nothing: the
 * field need not be unique, or holds nothing a value is compared by. Pure, and covered under `jsNodeTest`.
 */
fun uniqueAskOf(traitId: String, type: SchType, field: String, data: Map<String, Any?>): UniqueAsk? {
    if (field !in uniqueFieldsOf(type)) return null
    val value = data[field].toOptStr()?.takeIf { normalizeUnique(it).isNotEmpty() } ?: return null
    return UniqueAsk(traitId, field, type.properties.getValue(field).valueType, value)
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

/** What was asked: of the field at [path], whether the forms of [owner] -- of whoever the form is, as asked -- hold [value]. */
data class UniqueAsked(val owner: String, val path: String, val value: String)

/**
 * The failures to show of [answers] -- what each ask was told, null for a value that is free -- on a form of [owner]: those
 * of a field that still holds the value asked about, which [askAt] says. Each is a failure of its field, so the schema's and
 * the layout's messages for [SchFailCode.valueTaken] reword it. Pure, and covered under `jsNodeTest`.
 */
fun takenFailures(answers: Map<UniqueAsked, String?>, owner: String, askAt: (String) -> UniqueAsk?): List<SchFailure> =
    answers.mapNotNull { (asked, message) ->
        val ask = askAt(asked.path)?.takeIf { asked.owner == owner && it.value == asked.value }
        if (ask == null || message == null) return@mapNotNull null
        // Made under the field's own name, which a message may give, and then shown where the page has it.
        ask.fieldType.failure(ask.field, SchFailCode.valueTaken, message, value = ask.value).copy(path = asked.path)
    }

/** What [useUniqueChecks] answers: [left] is called as a field is left, and [failures] are those to show now. */
class UniqueChecks(val left: (String) -> Unit, val failures: List<SchFailure>)

/**
 * Asks, as each unique field of a form is left, whether another of the owner's forms holds its value (issue #1851), and
 * shows the answer under the field for as long as the field holds that value. [askAt] is what to ask of the field at a
 * path, null for one not unique. The owner is the form's named by [gedraId], or the caller's own, or [userRef]'s for a form
 * made for someone; [client] names another client's copy of the endpoint. A change of any of them asks again about the
 * fields already left, and an answer about the one before is not shown. An ask that fails says nothing: the save's own
 * check is the one that holds the value.
 */
fun useUniqueChecks(
    client: String? = null,
    gedraId: String? = null,
    userRef: String? = null,
    askAt: (String) -> UniqueAsk?,
): UniqueChecks {
    val owner = listOf(client, gedraId, userRef).joinToString("/") { it.orEmpty() }
    val pendingState = useState<Set<String>>(emptySet())
    val pending by pendingState
    val updatePending = pendingState.component2()
    val leftState = useState<Set<String>>(emptySet())
    val leftPaths by leftState
    val updateLeft = leftState.component2()
    val answersState = useState<Map<UniqueAsked, String?>>(emptyMap())
    val answers by answersState
    val updateAnswers = answersState.component2()
    useEffect(owner) { if (leftPaths.isNotEmpty()) updatePending { it + leftPaths } }
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
                updateAnswers { it + (UniqueAsked(owner, path, ask.value) to message) }
            }
        }
    }
    fun left(path: String) {
        updateLeft { it + path }
        if (askAt(path) != null) updatePending { it + path }
    }
    return UniqueChecks(::left, takenFailures(answers, owner, askAt))
}

/** [failures] with [taken] beside them, for a form; null -- nothing checked yet -- stays so when none is taken. */
fun withTaken(failures: List<SchFailure>?, taken: List<SchFailure>): List<SchFailure>? =
    if (taken.isEmpty()) failures else failures.orEmpty() + taken
