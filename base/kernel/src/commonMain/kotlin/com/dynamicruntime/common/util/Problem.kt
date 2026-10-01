package com.dynamicruntime.common.util

import com.dynamicruntime.common.exception.KdrException
import com.dynamicruntime.common.schema.JsonMappable
import com.dynamicruntime.common.schema.childPath

/**
 * A failure an input is expected to have, reported as a value rather than thrown (issue #909).
 *
 * The rule behind it: a failure the caller expects and forgives on the spot -- a value that is not a date, a
 * string that is not JSON -- comes back as a result, so whatever is still **thrown** is by definition unexpected
 * and can be treated as a fault rather than swallowed along with the malformed input. A non-throwing call
 * returns a [Parsed] carrying these; its throwing counterpart is built on top of it, so a non-throwing call never
 * creates and discards an exception internally.
 *
 * The parts: a [code] from the parser's own closed set (an enum implementing [ProblemCode]), a human [message],
 * where in the input it is ([location], when the parser knows), and how bad it is ([severity]). A structured
 * input's richer report (a template's paths, a document's headings) carries a list of these beside its own
 * structure; that is a later phase of #909, and this is the shape it builds on.
 */
class Problem(
    val code: ProblemCode,
    val message: String,
    val location: ProblemLocation? = null,
    val severity: ProblemSeverity = ProblemSeverity.error,
) : JsonMappable {
    /**
     * This problem as a [KdrException] from [factory], with its code and location in the standard extra data. The
     * code goes in as the code itself (the enum entry), as the template engine and the Markdown reader have always
     * put theirs, so a caller compares it with `==` against its parser's enum.
     */
    fun toException(factory: (String) -> KdrException = { KdrException.mkConv(it) }): KdrException =
        factory(message).also { ex ->
            ex.extraData[KdrException.errorCodeKey] = code
            location?.offset?.let { ex.extraData[KdrException.offsetKey] = it }
            location?.line?.let { ex.extraData[KdrException.lineKey] = it }
            location?.col?.let { ex.extraData[KdrException.lineColKey] = it }
        }

    override fun toJsonMap(): Map<String, Any?> = buildMap {
        put(PRB.code, code.name)
        put(PRB.message, message)
        location?.let { put(PRB.location, it.toJsonMap()) }
        put(PRB.severity, severity.name)
    }

    override fun toString(): String = message
}

/**
 * A problem's code: one entry of a parser's own closed set (issue #909). Implemented by an **enum** per parser, so
 * a caller can branch on the codes exhaustively and a test can assert one, while code shared across parsers
 * handles any of them through this interface. On the wire a code is its [name], so a code is a contract.
 */
interface ProblemCode {
    val name: String
}

/** How bad a [Problem] is: [error] means no usable result; [warning] means a result, delivered degraded. */
@Suppress("EnumEntryName")
enum class ProblemSeverity {
    error,
    warning,
}

/**
 * Where in an input a [Problem] is: a position in text ([offset], 0-based; [line] and [col], 1-based) and/or a
 * [path] into structured data, in the validator's failure-path spelling (`entries[0].data.year`). Either half may
 * be absent. Composable: a problem found inside a value at some path keeps its text position and gains the path
 * ([under]), so a caller never has to build "where" into the message.
 */
class ProblemLocation(
    val path: String? = null,
    val offset: Int? = null,
    val line: Int? = null,
    val col: Int? = null,
) : JsonMappable {
    /** This location as seen from [parentPath]: the path is prefixed, the text position kept. */
    fun under(parentPath: String): ProblemLocation =
        ProblemLocation(path?.let { childPath(parentPath, it) } ?: parentPath, offset, line, col)

    override fun toJsonMap(): Map<String, Any?> = buildMap {
        path?.let { put(PRB.path, it) }
        offset?.let { put(PRB.offset, it) }
        line?.let { put(PRB.line, it) }
        col?.let { put(PRB.col, it) }
    }
}

/** The keys of a [Problem]'s and a [ProblemLocation]'s JSON form. */
@Suppress("ConstPropertyName")
object PRB {
    const val code = "code"
    const val message = "message"
    const val location = "location"
    const val severity = "severity"
    const val path = "path"
    const val offset = "offset"
    const val line = "line"
    const val col = "col"
}

/**
 * The problem codes of the **simple-value** conversions (issue #909): a date, a number, a gedra id, a JSON text.
 * One set for all of them, since the question each asks is only "is this a valid one?".
 */
@Suppress("EnumEntryName")
enum class ConvProblem : ProblemCode {
    /** Nothing to convert: an empty or blank string where a value was required. */
    blank,

    /** Text that does not spell a value of the wanted kind. */
    badFormat,

    /** A value of a type that cannot be converted at all (a map where a number was wanted). */
    wrongType,
}

/**
 * The outcome of a non-throwing parse or conversion (issue #909): [Ok], with the value and any warnings, or
 * [Failed], with at least one error. A caller that only wants the value takes [valueOrNull]; one that reports
 * takes [problems]; and the throwing form of a call is this with [orThrow] -- which is how a throwing call is
 * built on its non-throwing one rather than the reverse.
 */
sealed class Parsed<out T> {
    abstract val problems: List<Problem>

    /** A usable [value], possibly delivered with warnings. */
    class Ok<out T>(val value: T, override val problems: List<Problem> = emptyList()) : Parsed<T>()

    /** No usable value; [problems] holds at least one error. */
    class Failed(override val problems: List<Problem>) : Parsed<Nothing>() {
        constructor(problem: Problem) : this(listOf(problem))
    }

    /** The value, or null when this failed. */
    fun valueOrNull(): T? = when (this) {
        is Ok -> value
        is Failed -> null
    }

    /**
     * The value, or the first problem thrown as an exception from [factory] -- a conversion failure
     * ([KdrException.mkConv]) unless the caller's contract says otherwise (bad input, say).
     */
    fun orThrow(factory: (String) -> KdrException = { KdrException.mkConv(it) }): T = when (this) {
        is Ok -> value
        is Failed -> throw problems.first().toException(factory)
    }

    companion object {
        /** A failure with one error problem. */
        fun failed(code: ProblemCode, message: String, location: ProblemLocation? = null): Failed =
            Failed(Problem(code, message, location))
    }
}
