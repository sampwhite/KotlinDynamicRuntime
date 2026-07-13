package com.dynamicruntime.common.util

import com.dynamicruntime.common.annotation.KdrPrivate
import com.dynamicruntime.common.exception.ACT
import com.dynamicruntime.common.exception.EXC
import com.dynamicruntime.common.exception.KdrException
import com.dynamicruntime.common.exception.SRC
import kotlin.math.min

fun Any?.toJsonStr(compact: Boolean = false, preserveNulls: Boolean = false): String {
    val sb = StringBuilder()
    appendJson(sb, this, 0, compact, preserveNulls, false)
    return sb.toString()
}

@KdrPrivate
fun appendJson(
    sb: StringBuilder, any: Any?, nestLevel: Int, compact: Boolean, preserveNulls: Boolean,
    ignoreLinkedHashMaps: Boolean
) {
    // Bounds the recursion (guarding against both accidental cycles and pathologically deep data). The
    // limit matches the parser so that anything we can parse we can also format back out.
    if (nestLevel > 50) {
        throw KdrException.mkConv("JSON structure is nested too deeply (over 50 levels) to format.")
    }
    if (any == null) {
        sb.append("null")
        return
    }
    when (any) {
        is Map<*, *> -> {
            val startEmpty = any.isEmpty()
            val copy: MutableList<Map.Entry<*, *>> = mutableListOf()
            // Note, not testing against interface `SequencedMap` because it does
            // not transpile to JavaScript.
            val doingSort = ignoreLinkedHashMaps || any !is LinkedHashMap<*, *>
            for (entry in any.entries) {
                val k: Any? = entry.key
                if (k != null) {
                    val v: Any? = entry.value
                    if (v != null || preserveNulls || !doingSort) {
                        copy.add(entry)
                    }
                }
            }
            if (copy.isEmpty()) {
                // Give a clue whether we suppressed some nulls.
                val appendStr = if (startEmpty) "{}" else "{ }"
                sb.append(appendStr)
                return
            }
            val mapBegin = if (compact) "{" else "{\n"
            sb.append(mapBegin)
            if (doingSort) {
                // Scalars sort ahead of container values (collections/maps); ties break by key.
                copy.sortWith(
                    compareBy(
                        { it.value is Collection<*> || it.value is Map<*, *> },
                        { it.key.toString() },
                    )
                )
            }

            var isFirst = true
            for (entry in copy) {
                val k: Any = entry.key!!
                val v: Any? = entry.value
                if (!isFirst) {
                    val nextItemStr = if (compact) "," else ",\n"
                    sb.append(nextItemStr)
                }
                val ks = k.toString()
                if (!compact) {
                    appendIndents(sb, nestLevel + 1)
                }
                sb.append("\"").appendLiteral(ks).append("\":")
                appendJson(sb, v, nestLevel + 1, compact, preserveNulls, ignoreLinkedHashMaps)
                isFirst = false
            }
            if (!compact && !isFirst) {
                sb.append('\n')
                appendIndents(sb, nestLevel)
            }
            sb.append("}")
        }

        is Collection<*> -> appendJsonArray(sb, any, nestLevel, compact, preserveNulls, ignoreLinkedHashMaps)

        // Arrays of every flavor are rendered as JSON arrays. Without these an array would fall through
        // to the `else` branch and be emitted as its (useless) default `toString()`, quoted as a string.
        is Array<*> -> appendJsonArray(sb, any.asList(), nestLevel, compact, preserveNulls, ignoreLinkedHashMaps)
        is IntArray -> appendJsonArray(sb, any.asList(), nestLevel, compact, preserveNulls, ignoreLinkedHashMaps)
        is LongArray -> appendJsonArray(sb, any.asList(), nestLevel, compact, preserveNulls, ignoreLinkedHashMaps)
        is DoubleArray -> appendJsonArray(sb, any.asList(), nestLevel, compact, preserveNulls, ignoreLinkedHashMaps)
        is FloatArray -> appendJsonArray(sb, any.asList(), nestLevel, compact, preserveNulls, ignoreLinkedHashMaps)
        is BooleanArray -> appendJsonArray(sb, any.asList(), nestLevel, compact, preserveNulls, ignoreLinkedHashMaps)
        is ShortArray -> appendJsonArray(sb, any.asList(), nestLevel, compact, preserveNulls, ignoreLinkedHashMaps)
        is ByteArray -> appendJsonArray(sb, any.asList(), nestLevel, compact, preserveNulls, ignoreLinkedHashMaps)
        is CharArray -> appendJsonArray(sb, any.asList(), nestLevel, compact, preserveNulls, ignoreLinkedHashMaps)

        is Number, is Boolean -> {
            sb.append(any.fmt())
        }
        else -> {
            sb.append('\"').appendLiteral(any.fmt()).append('\"')
        }
    }
}

@KdrPrivate
fun appendJsonArray(
    sb: StringBuilder, items: Collection<*>, nestLevel: Int, compact: Boolean, preserveNulls: Boolean,
    ignoreLinkedHashMaps: Boolean
) {
    if (items.isEmpty()) {
        sb.append("[]")
        return
    }
    sb.append("[")
    if (nestLevel == 0 && !compact) {
        sb.append('\n')
        appendIndents(sb, 1)
    }
    var isFirst = true
    for (item in items) {
        if (!isFirst) {
            sb.append(",")
        }
        appendJson(sb, item, nestLevel + 1, compact, preserveNulls, ignoreLinkedHashMaps)
        isFirst = false
    }
    if (nestLevel == 0 && !compact) {
        sb.append("\n")
    }
    sb.append(']')
}

@KdrPrivate
fun appendIndents(sb: StringBuilder, nestLevel: Int) {
    repeat(nestLevel) { sb.append("  ") }
}


@KdrPrivate
@Suppress("EnumEntryName")
enum class ExpectedVal {
    array,
    map,
    any
}

@KdrPrivate
class PState(val str: String, val expectedVal: ExpectedVal = ExpectedVal.any) {
    val end: Int = str.length
    var offset: Int = 0
    var line: Int = 0
    var lineOffset: Int = 0
    var inArray: Boolean = false
    val sb = StringBuilder()

    // The following are all parser options that can be set.
    /** Whether to use LinkedHashMap objects instead of HashMap objects.  */
    var preserveOrder: Boolean = false

    /** Whether to fail on values that sometimes show up in actual JSON data.
     * This includes things like `NaN`. If `strictValues` is false, and
     * we come across one of these forgiven values, we map it to `null`. */
    var strictValues: Boolean = false

    /** Whether you can pass in null or a string with only whitespace to the parser. In that case it will return an empty
     * object of the appropriate type.  */
    var allowNonNullEmpty: Boolean = true

    /** Whether to allow a map to have the same key twice in the JSON.  */
    var allowDuplicateKeys: Boolean = false

    /** Whether to tolerate non-whitespace content after the top-level JSON value. Off by default (the
     * standard entry points leave it off); exposed for callers that parse from a larger stream. */
    var forgiveTrailingContent: Boolean = false

    // Used to report when the syntax issue started, not when it ended.
    var capturedState: PState? = null

    /** Used to track where we are parsing.  */
    fun inc(inc: Int, inCh: Char) {
        offset += inc
        if (inCh == '\n') {
            line++
            lineOffset = 0
        } else {
            lineOffset += inc
        }
    }

    fun copyFrom(otherState: PState) {
        offset = otherState.offset
        line = otherState.line
        lineOffset = otherState.lineOffset
        inArray = otherState.inArray
        preserveOrder = otherState.preserveOrder
        strictValues = otherState.strictValues
        allowNonNullEmpty = otherState.allowNonNullEmpty
        allowDuplicateKeys = otherState.allowDuplicateKeys
        forgiveTrailingContent = otherState.forgiveTrailingContent
    }

    fun captureState() {
        if (capturedState == null) {
            capturedState = PState(str, expectedVal)
        }
        capturedState!!.copyFrom(this)
    }

    override fun toString(): String {
        if (offset >= end) {
            return "<all-consumed>"
        }
        val l = min(end - offset, 128)
        return str.substring(offset, offset + l)
    }
}

fun String.json(): Any? {
    val state = PState(this, ExpectedVal.any)
    val result = parseJson(state, 0)
    checkNoTrailingContent(state)
    return result
}

fun String.jsonMap(): MutableMap<String, Any?>? {
    val state = PState(this, ExpectedVal.map)
    val result = parseJson(state, 0)
    checkNoTrailingContent(state)
    return result?.toT()
}

fun String.jsonArray(): MutableList<Any?>? {
    val state = PState(this, ExpectedVal.array)
    val result = parseJson(state, 0)
    checkNoTrailingContent(state)
    return result?.toT()
}

/**
 * After the top-level value has been parsed, rejects any non-whitespace content that follows it (e.g.
 * `{"a":1} garbage`). Trailing whitespace is always allowed. Honors [PState.forgiveTrailingContent] for
 * callers who intend to parse a single value out of a larger stream.
 */
@KdrPrivate
fun checkNoTrailingContent(state: PState) {
    if (state.forgiveTrailingContent) return
    val str = state.str
    while (state.offset < state.end) {
        val ch = str[state.offset]
        if (ch > ' ') {
            throw mkJsonParseException(state, "Unexpected trailing content after the JSON value.")
        }
        state.inc(1, ch)
    }
}

@KdrPrivate
fun parseJson(state: PState, nestLevel: Int): Any? {
    // We are at the start of something that could be an array or an object.
    if (nestLevel > 50) {
        throw mkJsonParseException(state, "JSON is nested too deeply (over 50 levels).")
    }

    val startOffset = state.offset
    val inputInArray = state.inArray

    state.captureState()
    var entityCount = 0
    val str = state.str
    if (nestLevel == 0 && state.allowNonNullEmpty && !inputInArray && str == "null") {
        // A bare top-level `null` is forgiven and parsed as a null value.
        state.offset = state.end
        return null
    }

    for (i in startOffset..<state.end) {
        if (entityCount++ > 100000) {
            throw mkJsonParseException(
                state.capturedState, "JSON has over 100,000 whitespace characters before " +
                        "encountering JSON entity."
            )
        }
        val ch = str[i]
        var parsingValue = false
        var parsingInteriorObject = false
        var startArray = false
        var isEnding = false
        if (ch > ' ') {
            if (ch == '{' || ch == '[') {
                if (nestLevel == 0 && state.expectedVal == ExpectedVal.array && ch == '{') {
                    throw mkJsonParseException(
                        state, "Character '$ch' indicates a JSON map was present when an array was expected."
                    )
                } else if (nestLevel == 0 && state.expectedVal == ExpectedVal.map && ch == '[') {
                    throw mkJsonParseException(
                        state, "Character '$ch' indicates a JSON array was present when a map was expected."
                    )
                }
                parsingInteriorObject = true
                startArray = ch == '['
            } else {
                if (!inputInArray && (nestLevel == 0 || ch == ',' || ch == ']')) {
                    throw mkJsonParseException(
                        state,
                        "Unexpected character '$ch' when parsing JSON object."
                    )
                }
                when (ch) {
                    ']' -> {
                        isEnding = true
                        state.inArray = false
                    }
                    ',' -> {
                        isEnding = true
                    }
                    else -> {
                        parsingValue = true
                    }
                }
            }
        }

        // If parsing a value or ending with a comma, want to replay the first character for the value or allow
        // comma to be detected again as a separator.
        if (!parsingValue && !(isEnding && ch == ',')) {
            state.inc(1, ch)
        }

        if (isEnding) {
            return null
        }

        if (startArray) {
            state.inArray = true
            val retvalList: MutableList<Any?> = mutableListOf()
            var count = 0
            state.captureState()
            while (state.inArray) {
                if (count++ > 10000000) {
                    throw mkJsonParseException(state.capturedState, "Array has over ten million entries.")
                }
                val arrayElt = parseJson(state, nestLevel + 1)
                if (arrayElt != null || state.inArray) {
                    retvalList.add(arrayElt)
                }
                if (state.inArray) {
                    parseToNextComma(state)
                }
            }
            state.inArray = inputInArray
            return retvalList
        } else if (parsingInteriorObject) {
            state.inArray = false
            // Parsing object.
            val retval = parseInteriorJsonObject(nestLevel + 1, state)
            state.inArray = inputInArray
            return retval
        } else if (parsingValue) {
            return parseJsonValue(state)
        }
    }
    if (nestLevel == 0 && state.allowNonNullEmpty && !inputInArray) {
        return null
    }

    val msg =
        if (inputInArray) "Array did not end with a ']' character." else "No JSON entity found in expected location."
    throw mkJsonParseException(state.capturedState, msg)
}

@KdrPrivate
fun parseInteriorJsonObject(nestLevel: Int, state: PState): MutableMap<String, Any?> {
    // Looking for key value pairs.
    val m: MutableMap<String, Any?> =
        if (state.preserveOrder) LinkedHashMap() else HashMap()
    state.captureState()
    var count = 0
    val str = state.str
    while (state.offset < state.end) {
        val ch = str[state.offset]
        var isEnding = false
        var parseKeyVal = false
        // We treat commas like spaces. We do not enforce any proper comma placement.
        if (ch > ' ' && ch != ',') {
            if (ch == '}') {
                isEnding = true
            } else if (ch != '\"') {
                throw mkJsonParseException(
                    state,
                    "Unexpected character '$ch' when parsing object key."
                )
            }
            parseKeyVal = true
        } else {
            if (count++ > 100000) {
                throw mkJsonParseException(
                    state.capturedState,
                    "JSON Object has over 100,000 whitespace characters between keys."
                )
            }
        }

        state.inc(1, ch)
        if (isEnding) {
            return m
        }
        if (parseKeyVal) {
            val key = parseInteriorJsonString(state)
            parseToNextColon(state)
            val value = parseJson(state, nestLevel + 1)

            if (!state.allowDuplicateKeys) {
                if (m.containsKey(key)) {
                    throw mkJsonParseException(
                        state,
                        "Duplicate key '$key' found in JSON object."
                    )
                }
            }
            m[key] = value

            state.captureState()
            count = 0
        }
    }
    throw mkJsonParseException(state, "Interior object did not end with a '}'.")
}


@KdrPrivate
fun parseJsonValue(state: PState): Any? {
    // We are parsing a value, which is either a boolean true, false, and integer, a floating point or a literal string.
    // The first character will tell us what we are dealing with.
    val startOffset = state.offset
    state.captureState()
    val str = state.str
    var count = 0
    for (i in startOffset..<state.end) {
        if (count++ > 10000) {
            throw mkJsonParseException(
                state.capturedState, "JSON value has over 10,000 whitespace characters before " +
                        "encountering value."
            )
        }
        val ch = str[i]
        if (ch > ' ') {
            val isNumeric = (ch in '0'..'9') || ch == '-'
            if (!isNumeric && (ch !in 'a'..'z') && (ch !in 'A'..'Z') && ch != '\"') {
                throw mkJsonParseException(
                    state,
                    "Unexpected start character '$ch' when parsing JSON value."
                )
            }

            if (ch == '\"') {
                state.inc(1, ch)
                return parseInteriorJsonString(state)
            }
            // Allowing replay of the first character, if desired.
            return parseNonStringValue(state, isNumeric)
        }
        state.inc(1, ch)
    }
    throw mkJsonParseException(state, "Input ended before JSON object, array, or value was extracted.")
}

@KdrPrivate
fun parseNonStringValue(state: PState, isNumeric: Boolean): Any? {
    state.captureState()
    state.sb.setLength(0)
    val str = state.str
    val firstCh = str[state.offset]
    state.sb.append(firstCh)
    state.inc(1, firstCh)
    val startOffset = state.offset
    var count = 0
    var isFloatingPoint = false
    for (i in startOffset..<state.end) {
        if (count++ > 25) {
            throw mkJsonParseException(
                state.capturedState, "JSON numeric or boolean value has over " +
                        "25 characters before finding end of value."
            )
        }
        val ch = str[i]

        val isNumericCh = ch in '0'..'9'
        if (!isNumericCh && (ch !in 'a'..'z') && (ch !in 'A'..'Z') && ch != '.' && ch != '-' && ch != '+') {
            // Found termination character. We will allow replay of that character.
            state.inc(state.sb.length - 1, firstCh)
            if (isNumeric) {
                return if (isFloatingPoint) {
                    try {
                        state.sb.toString().toDouble()
                    } catch (e: NumberFormatException) {
                        throw mkJsonParseException(state, "JSON floating point value '${state.sb}' is not a valid number.", e)
                    }
                } else {
                    try {
                        state.sb.toString().toLong()
                    } catch (e: NumberFormatException) {
                        throw mkJsonParseException(state, "JSON integer value '${state.sb}' is not a valid number.", e)
                    }
                }
            } else {
                when (val s = state.sb.toString()) {
                    "true" -> {
                        return true
                    }
                    "false" -> {
                        return false
                    }
                    else -> {
                        if (s != "null" && (!s.startsWith("N") || state.strictValues)) {
                            throw mkJsonParseException(
                                state,
                                "JSON value '$s' is not a valid boolean or numeric value."
                            )
                        }
                        return null
                    }
                }
            }
        } else if (!isNumericCh && isNumeric) {
            // We must be doing a floating point value.
            isFloatingPoint = true
        }
        state.sb.append(ch)
    }
    throw mkJsonParseException(
        state.capturedState,
        "Input ended before JSON numeric or boolean value was extracted."
    )
}

@KdrPrivate
fun parseInteriorJsonString(state: PState): String {
    state.sb.setLength(0)
    var count = 0
    state.captureState()
    val str = state.str
    while (state.offset < state.end) {
        val i = state.offset

        // Because of recursive encoding of strings in some data exchange, we need to allow a single
        // JSON string to be pretty large.
        if (count++ > 1000000) {
            throw mkJsonParseException(state.capturedState, "JSON String value has over one million characters.")
        }
        val ch = str[i]
        var isEnding = false
        if (ch == '\"') {
            isEnding = true
            state.inc(1, ch)
        } else {
            if (ch == '\\' && i + 1 < state.end) {
                val nextCh = str[i + 1]
                state.inc(1, ch)
                state.inc(1, nextCh)
                val nch: Char
                when (nextCh) {
                    'n' -> nch = '\n'
                    't' -> nch = '\t'
                    'r' -> nch = '\r'
                    'b' -> nch = '\b'
                    'f' -> nch = '\u000c'
                    'u' -> if (i + 5 < state.end) {
                        // Need a different string builder than the one building the current string.
                        // Happen to have one in the captured state.
                        state.capturedState!!.sb.setLength(0)
                        var j = i + 2
                        while (j < i + 6) {
                            val c = str[j]
                            if (c in '0'..'9' || c in 'A'..'F' || c in 'a'..'f') {
                                state.capturedState!!.sb.append(c)
                            } else {
                                throw mkJsonParseException(
                                    state,
                                    "Invalid hex character in unicode escape sequence '${str.substring(i + 2, i + 6)}'.")
                            }
                            j++
                        }
                        val hex = state.capturedState!!.sb.toString()
                        try {
                            nch = hex.toInt(16).toChar()
                        } catch (e: NumberFormatException) {
                            throw mkJsonParseException(state, "Invalid unicode escape sequence '$hex'.", e)
                        }
                        state.inc(4, 'u')
                    } else {
                        nch = 'u'
                    }

                    else -> nch = nextCh
                }
                state.sb.append(nch)
            } else {
                state.sb.append(ch)
                state.inc(1, ch)
            }
        }
        if (isEnding) {
            return state.sb.toString()
        }
    }
    throw mkJsonParseException(state, "JSON String did not end with a double quote ('\"').")
}

@KdrPrivate
fun parseToNextColon(state: PState) {
    val startOffset = state.offset
    var count = 0
    state.captureState()
    val str = state.str
    for (i in startOffset..<state.end) {
        if (count++ > 20) {
            throw mkJsonParseException(
                state.capturedState, "There were over 20 characters between the end of " +
                        "the key and the colon that should follow it."
            )
        }
        val ch = str[i]
        state.inc(1, ch)
        if (ch == ':') {
            return
        }
    }
    throw mkJsonParseException(state.capturedState, "Key was not followed by an expected colon.")
}

@KdrPrivate
fun parseToNextComma(state: PState) {
    val startOffset = state.offset
    var count = 0
    state.captureState()
    val str = state.str
    for (i in startOffset..<state.end) {
        if (count++ > 1000) {
            throw mkJsonParseException(
                state.capturedState, "There were over 1000 characters between the end of " +
                        "the array value and the comma or close bracket that should follow it."
            )
        }
        val ch = str[i]
        state.inc(1, ch)
        if (ch == ',') {
            return
        }
        if (ch == ']') {
            state.inArray = false
            return
        }
        if (ch > ' ') {
            throw mkJsonParseException(
                state.capturedState,
                "Unexpected character '$ch' when looking for comma or close bracket for array."
            )
        }
    }
    throw mkJsonParseException(state.capturedState, "Array did not end with a close bracket.")
}

@KdrPrivate
fun mkJsonParseException(state: PState?, msg: String, ex: Exception? = null): KdrException {
    val errMsg = "$msg Error originates at offset ${state?.offset ?: 0} in input."
    // A parse failure is bad input (400), not an internal error: the malformed text came from a caller.
    val ke = KdrException(errMsg, ex, EXC.badInput, SRC.system, ACT.conversion)
    if (state != null) {
        ke.extraData[KdrException.offsetKey] = state.offset
        ke.extraData[KdrException.lineKey] = state.line + 1
        ke.extraData[KdrException.lineColKey] = state.lineOffset + 1
    }
    return ke
}

