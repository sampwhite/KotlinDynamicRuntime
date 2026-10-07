package com.dynamicruntime.common.schema

import com.dynamicruntime.common.annotation.KdrPrivate
import com.dynamicruntime.common.exception.KdrException
import com.dynamicruntime.common.util.Problem
import com.dynamicruntime.common.util.ProblemCode
import com.dynamicruntime.common.util.ProblemLocation

/**
 * What can be wrong with a schema document (issue #909): the problem codes of [analyzeSchemaTypes] and of the
 * keyword checks it shares with the client-definition repair. One set for the document's parse; each code names
 * the *kind* of fault, and the message says which keyword and what to write instead.
 */
@Suppress("EnumEntryName")
enum class SchemaError : ProblemCode {
    /** A `$ref` whose target is neither in the document nor among the types it was parsed against. */
    unknownRef,

    /** A standard keyword refused by name, because it implies behavior this layer does not have (issue #823). */
    refusedKeyword,

    /** A `g-` key that is not one of our keywords -- usually a misspelling (issue #822). */
    unknownKeyword,

    /**
     * One of our keywords where it does nothing (issue #985): `g-merge` anywhere but the top of a client's alteration of
     * a global type, the only place a merge applies it.
     */
    misplacedKeyword,

    /** A keyword whose value has the wrong shape: text where true/false belongs, a bound that is not a number. */
    badValue,

    /** A keyword on a type it cannot constrain -- `pattern` on an integer, `options` on a date (issue #815). */
    notApplicable,

    /** A `pattern` that is not valid, or that the two regex engines would not read alike (issue #823). */
    badPattern,

    /** A `oneOf` outside the one discriminated shape this layer reads, or a branch nothing selects (issue #252). */
    badUnion,

    /** An `if`/`then`/`else` outside the one shape this layer reads (issue #253). */
    badCondition,

    /** Nesting past the depth cap -- in practice, a raw map that refers to itself. */
    tooDeep,
}

/**
 * A schema document's report (issue #909): the parsed [types] when it parsed, or the [problems] when it did not.
 *
 * **One problem, not all of them.** A schema is parsed in one recursive pass whose later steps depend on its
 * earlier ones (a `$ref` is bound to the type parsed for it), so recovery after a fault is not cheap, and the
 * parse stops at the first -- as the throwing [parseSchemaTypes] always has. What the report adds is that the
 * fault is coded ([SchemaError]) and located: its [ProblemLocation.path] is the place in the document, the
 * type's qualified name followed by the keys down to the fault (`acme.Q.properties.topic.items`, a union branch
 * as `oneOf[2]`); a `pattern` fault adds the offset in the pattern. The message is the same sentence as ever.
 */
class SchemaAnalysis(val types: Map<String, SchType>?, val problems: List<Problem>)

/**
 * [parseSchemaTypes] as a report (issue #909): never throws for a fault in [defs], which comes back as
 * [SchemaAnalysis.problems] instead.
 *
 * The parser's recursive descent reports a fault by throwing, as the JSON reader does; this is the one place the
 * throw is caught, and only a [KdrException] is -- anything else is a bug in the parser and propagates. The
 * throwing form runs the same parse without the catch, so it raises exactly what it always raised.
 */
fun analyzeSchemaTypes(defs: Map<String, Any?>, existingTypes: Map<String, SchType> = emptyMap()): SchemaAnalysis {
    val state = SchParseState()
    return try {
        SchemaAnalysis(parseSchemaTypesInto(state, defs, existingTypes), emptyList())
    } catch (e: KdrException) {
        val problem = Problem(
            e.extraData[KdrException.errorCodeKey] as? SchemaError ?: SchemaError.badValue,
            e.message.orEmpty(),
            ProblemLocation(path = state.path(), offset = e.extraData[KdrException.offsetKey] as? Int),
        )
        SchemaAnalysis(null, listOf(problem))
    }
}

/** A schema fault: a [KdrException] from [factory] carrying [code], for [analyzeSchemaTypes] to read back. */
@KdrPrivate
fun schemaFault(
    code: SchemaError,
    message: String,
    factory: (String) -> KdrException = { KdrException.mkConv(it) },
): KdrException = factory(message).also { it.extraData[KdrException.errorCodeKey] = code }

/**
 * What one parse of a schema document carries down its recursion: the references deferred to the resolution pass,
 * and where in the document the parse is -- a stack of path segments, pushed on the way into a property, an
 * `items` or a union branch and popped on the way out. Deliberately **not** popped in a `finally`: when a fault is
 * thrown the stack is left as it stood, so [path] is where the fault was.
 */
@KdrPrivate
class SchParseState {
    val pendingRefs = mutableListOf<PendingRef>()
    val pendingItemRefs = mutableListOf<PendingItemRef>()
    val pendingMapValueRefs = mutableListOf<PendingMapValueRef>()
    val pendingBranchRefs = mutableListOf<PendingBranchRef>()
    private val segments = ArrayDeque<String>()

    fun enter(segment: String) = segments.addLast(segment)
    fun exit() {
        segments.removeLast()
    }

    /** Puts the parse back at [path] -- for the resolution pass, which reports against where a reference was read. */
    fun at(path: String) {
        segments.clear()
        segments.addLast(path)
    }

    /** Where the parse is, as a path; null before the first type is entered. */
    fun path(): String? = segments.takeIf { it.isNotEmpty() }?.joinToString(".")
}
