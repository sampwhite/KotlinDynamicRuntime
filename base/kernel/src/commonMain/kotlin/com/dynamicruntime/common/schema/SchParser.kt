package com.dynamicruntime.common.schema

import com.dynamicruntime.common.annotation.KdrPrivate
import com.dynamicruntime.common.exception.KdrException
import com.dynamicruntime.common.util.Parsed
import com.dynamicruntime.common.util.Problem
import com.dynamicruntime.common.util.ProblemLocation
import com.dynamicruntime.common.util.toJsonMap
import com.dynamicruntime.common.util.toJsonMapOrEmpty
import com.dynamicruntime.common.util.toOptStr
import com.dynamicruntime.common.util.toOptDouble

/**
 * Parses a `$defs`-style map of JSON Schema types (e.g., the output of
 * `schemaDefs { ... }`) into resolved [SchType] / [SchProperty] objects.
 *
 * Every `$ref` is checked: its target must be one of the types in [defs] or in
 * [existingTypes]; otherwise a [KdrException] is thrown. `oneOf` needs a declared discriminator (see
 * [parseVariants]) and `if`/`then`/`else` is read in one narrow shape (see [parseCondition]).
 *
 * **An unrecognized keyword is ignored, not rejected** — deliberately, so a document may carry keywords of its own
 * as documentation. Note what that costs, because it is not free: a keyword we do not read constrains nothing,
 * silently. Before issue #252 a `oneOf` parsed to a type with no `type`, no properties and `additionalProperties`
 * true, so a document could claim to be a discriminated union and enforce nothing at all, with no symptom. That is
 * the argument for reading a construct rather than deferring it, and the reason [SCH.discriminator] is *required*
 * alongside a `oneOf` we do read.
 *
 * It is also why the standard keywords that would imply large behavior we do not have are **refused** by name
 * ([refusedKeywords], issue #823) rather than ignored: `enum`, `allOf`, `anyOf`, `not` and `dependentSchemas`. A
 * denylist, not an allowlist: any other keyword stays allowed. Our own `g-` keywords are the opposite -- a closed
 * list, checked by [SchGKeywords].
 *
 * **A keyword we do read is held to its shape** ([SchStdKeywords], issue #1053): `type: "strng"`, `required:
 * "name"`, `properties: []` and `additionalProperties: "no"` are each refused by name, where each used to be read as
 * though the keyword were absent -- the same silence, reached by a typo in a value rather than in a key.
 *
 * @return the newly parsed types keyed by fully qualified name.
 */
fun parseSchemaTypes(
    defs: Map<String, Any?>,
    existingTypes: Map<String, SchType> = emptyMap(),
): Map<String, SchType> = parseSchemaTypesInto(SchParseState(), defs, existingTypes)

/**
 * The parse behind [parseSchemaTypes] and [analyzeSchemaTypes], recording in [state] where it is, so a fault it
 * throws can be located.
 */
@KdrPrivate
fun parseSchemaTypesInto(
    state: SchParseState,
    defs: Map<String, Any?>,
    existingTypes: Map<String, SchType>,
): Map<String, SchType> {
    val parsed = LinkedHashMap<String, SchType>()
    for ((name, raw) in defs) {
        if (raw is Map<*, *>) {
            state.enter(name)
            parsed[name] = parseNode(name, raw.toJsonMap(), state)
            state.exit()
        }
    }
    // Resolve $refs against the existing types plus the just-parsed ones.
    val registry = HashMap(existingTypes)
    registry.putAll(parsed)
    for (pending in state.pendingRefs) {
        val refName = pending.prop.refName ?: continue
        state.at(pending.path)
        pending.prop.valueType = registry[refName]
            ?: throw schemaFault(SchemaError.unknownRef, $$"Schema $ref to unknown type '$$refName'.")
    }
    // Bind array element types whose `items` was a $ref (deferred the same way as property refs, so a target
    // parsed later -- or a self-reference via items -- resolves without expanding during parsing).
    for (item in state.pendingItemRefs) {
        state.at(item.path)
        item.array.itemType = registry[item.refName]
            ?: throw schemaFault(SchemaError.unknownRef, $$"Schema $ref to unknown type '$${item.refName}'.")
    }
    // And a map's value type whose `additionalProperties` was a $ref, the same way (issue #1055).
    for (value in state.pendingMapValueRefs) {
        state.at(value.path)
        value.map.additionalValueType = registry[value.refName]
            ?: throw schemaFault(SchemaError.unknownRef, $$"Schema $ref to unknown type '$${value.refName}'.")
    }
    // Bind a union's branches for the same reason: a branch is normally a $ref, and one of them may refer
    // back to the union itself. Done a whole union at a time so the branches land in the order the document
    // declared them, mixed inline and $ref included -- "branch 3" in a boot-check message has to be the
    // reader's third branch, or the diagnostic sends them to the wrong place.
    for (union in state.pendingBranchRefs) {
        state.at(union.path)
        for (source in union.sources) {
            union.variants.branches.add(
                source.inline ?: registry[source.refName]
                    ?: throw schemaFault(SchemaError.unknownRef, $$"Schema $ref to unknown type '$${source.refName}'."),
            )
        }
        union.defaultRef?.let { ref ->
            union.variants.defaultBranch = registry[ref]
                ?: throw schemaFault(SchemaError.unknownRef, $$"Schema $ref to unknown type '$$ref'.")
        }
        indexVariants(union.owner, union.variants)
    }
    return parsed
}

/** A property whose value is a `$ref`, awaiting binding in the resolution pass; [path] is where it was read. */
@KdrPrivate
class PendingRef(val prop: SchProperty, val path: String)

/** An array [SchType] whose `items` is a `$ref` ([refName]), awaiting binding in the resolution pass. */
@KdrPrivate
class PendingItemRef(val array: SchType, val refName: String, val path: String)

/** A map [SchType] whose `additionalProperties` is a `$ref` ([refName]), awaiting binding in the resolution pass. */
@KdrPrivate
class PendingMapValueRef(val map: SchType, val refName: String, val path: String)

/** One declared branch: parsed in place ([inline]) or named for the resolution pass ([refName]). */
@KdrPrivate
class BranchSource(val inline: SchType?, val refName: String?)

/**
 * A union awaiting branch binding. Held per union rather than per branch, so declaration order survives
 * resolution, and carries [owner] only so the boot check can name the type in its message.
 */
@KdrPrivate
class PendingBranchRef(
    val variants: SchVariants,
    val sources: List<BranchSource>,
    val defaultRef: String?,
    var owner: SchType?,
    val path: String,
)

/**
 * Checks a resolved union and builds its value-to-branch index (issue #252).
 *
 * **Every branch must declare a `const` for the discriminator property.** That is stricter than OpenAPI, which
 * lets `mapping` carry the association instead, and the strictness is the point: it is what makes the document
 * validate identically without the keyword, so a stock validator — which ignores `discriminator` entirely and
 * tries each branch — reaches our verdict rather than a different one. A union whose branches do not say what
 * they are would need our reader to be correct, which is exactly the dependency the design avoids.
 *
 * The message names the branch, because "some branch is missing a const" in a fifty-type document is the kind
 * of diagnostic that costs an afternoon.
 */
@KdrPrivate
fun indexVariants(owner: SchType?, variants: SchVariants) {
    val where = owner?.name?.let { " of '$it'" } ?: ""
    val byValue = LinkedHashMap<String, SchType>(variants.branches.size)
    variants.branches.forEachIndexed { index, branch ->
        val prop = branch.properties[variants.discriminator]
            ?: throw schemaFault(
                SchemaError.badUnion,
                "Branch ${index + 1}$where declares no '${variants.discriminator}' property, so nothing " +
                    "selects it. Every branch of a discriminated union must declare the discriminator with a " +
                    "'${SCH.const}'.",
            )
        val declared = prop.valueType.constValue.toOptStr()
            ?: throw schemaFault(
                SchemaError.badUnion,
                "Branch ${index + 1}$where has no '${SCH.const}' for '${variants.discriminator}', so nothing " +
                    "selects it.",
            )
        val clash = byValue.put(declared, branch)
        if (clash != null) {
            throw schemaFault(
                SchemaError.badUnion,
                "Branch ${index + 1}$where repeats the '${variants.discriminator}' value '$declared'; each " +
                    "branch must claim its own.",
            )
        }
    }
    variants.byValue = byValue
}

/**
 * Parses `oneOf` + `discriminator` into a [SchVariants], or null when the node declares no `oneOf`.
 *
 * **A `oneOf` without a `discriminator` is rejected**, and that is a deliberate new strictness rather than an
 * oversight. It does not conflict with ignoring keywords we do not know: that policy exists, so an unheard-of
 * keyword cannot reject a standard-valid document, whereas this is a keyword we now read and a construct we
 * decline to guess at. Try-every-branch is the thing we chose not to build — when nothing matches, it has no
 * principled way to say whose failures to report — so accepting the document and quietly enforcing nothing
 * would recreate exactly the silence this issue exists to end.
 */
@KdrPrivate
fun parseVariants(name: String?, map: Map<String, Any?>, state: SchParseState, depth: Int): SchVariants? {
    val rawBranches = map[SCH.oneOf] as? List<*> ?: return null
    val where = name?.let { " on '$it'" } ?: ""
    val rawDiscriminator = map[SCH.discriminator]
    if (rawDiscriminator !is Map<*, *>) {
        throw schemaFault(
            SchemaError.badUnion,
            "'${SCH.oneOf}'$where has no '${SCH.discriminator}'. A union has to say which property selects " +
                "the branch, so a failure can be reported against the branch that was meant.",
        )
    }
    val discriminator = rawDiscriminator.toJsonMap()[SCH.propertyName].toOptStr()
        ?: throw schemaFault(SchemaError.badUnion, "'${SCH.discriminator}'$where has no '${SCH.propertyName}'.")
    if (rawBranches.isEmpty()) {
        throw schemaFault(SchemaError.badUnion, "'${SCH.oneOf}'$where declares no branches.")
    }
    val unionPath = state.path().orEmpty()
    val sources = rawBranches.mapIndexedNotNull { i, raw ->
        val branchMap = (raw as? Map<*, *>)?.toJsonMap() ?: return@mapIndexedNotNull null
        val ref = branchMap[SCH.dRef].toOptStr()
        if (ref != null) {
            BranchSource(null, refTargetName(ref))
        } else {
            state.enter("${SCH.oneOf}[$i]")
            BranchSource(parseNode(null, branchMap, state, depth + 1), null).also { state.exit() }
        }
    }
    val variants = SchVariants(discriminator, mutableListOf(), null)
    state.pendingBranchRefs.add(
        PendingBranchRef(
            variants,
            sources,
            rawDiscriminator.toJsonMap()[SCH.defaultMapping].toOptStr()?.let { refTargetName(it) },
            owner = null,
            path = unionPath,
        ),
    )
    return variants
}

/**
 * Parses `if` / `then` / `else` into a [SchCondition], or null when the node declares none (issue #253).
 *
 * **Only the shape the entity model needs is accepted**: an `if` testing one property against a `const`, and
 * `then` / `else` clauses that require or forbid properties. Anything else is refused with a message naming
 * what was not understood.
 *
 * That refusal is a deliberate behavior change, and worth being clear-eyed about: such a document parsed
 * before this and simply did nothing. General `if`/`then`/`else` applies an arbitrary subschema, which is a
 * far larger surface than the entity model has asked for, and honoring a fragment of it would produce a
 * schema that constrains *some* of what it appears to. A conditional that silently half-works is not
 * diagnosable from the outside — there is no failure to notice — which is the same argument that made a
 * discriminator-less `oneOf` an error in #252.
 *
 * It is not in tension with ignoring keywords we do not know: that policy protects a standard-valid document
 * from a keyword nobody here has heard of, whereas this is a keyword we now read and a construct we decline
 * to guess at.
 */
@KdrPrivate
fun parseCondition(name: String?, map: Map<String, Any?>): SchCondition? {
    val where = name?.let { " on '$it'" } ?: ""
    val rawIf = map[SCH.kIf]
    val rawThen = map[SCH.kThen]
    val rawElse = map[SCH.kElse]
    if (rawIf == null) {
        if (rawThen != null || rawElse != null) {
            throw schemaFault(
                SchemaError.badCondition,
                "'${SCH.kThen}'/'${SCH.kElse}'$where without an '${SCH.kIf}' decides nothing.",
            )
        }
        return null
    }
    if (rawThen == null && rawElse == null) {
        throw schemaFault(
            SchemaError.badCondition,
            "'${SCH.kIf}'$where has no '${SCH.kThen}' or '${SCH.kElse}', so it constrains nothing.",
        )
    }
    val ifMap = (rawIf as? Map<*, *>)?.toJsonMap()
        ?: throw schemaFault(SchemaError.badCondition, "'${SCH.kIf}'$where must be a schema object.")
    val tested = ifMap[SCH.properties].toJsonMapOrEmpty()
    if (tested.size != 1) {
        throw schemaFault(
            SchemaError.badCondition,
            "'${SCH.kIf}'$where must test exactly one property with a '${SCH.const}'; this layer reads that " +
                "shape only. Anything more general is not supported, and is refused rather than half-applied.",
        )
    }
    val (property, rawTest) = tested.entries.first()
    val test = (rawTest as? Map<*, *>)?.toJsonMap()
        ?: throw schemaFault(
            SchemaError.badCondition, "'${SCH.kIf}'$where must test '$property' with a '${SCH.const}'.",
        )
    if (SCH.const !in test) {
        throw schemaFault(
            SchemaError.badCondition,
            "'${SCH.kIf}'$where tests '$property' with something other than a '${SCH.const}'; only a " +
                "constant comparison is supported.",
        )
    }
    val (thenRequired, thenForbidden) = parseClause(SCH.kThen, rawThen, where)
    val (elseRequired, elseForbidden) = parseClause(SCH.kElse, rawElse, where)
    return SchCondition(property, test[SCH.const], thenRequired, thenForbidden, elseRequired, elseForbidden)
}

/**
 * One `then` or `else` clause: `required` names what must be present, `not: {required: […]}` what must be
 * absent. Those two are the whole supported vocabulary, so a clause carrying anything else is refused.
 */
private fun parseClause(keyword: String, raw: Any?, where: String): Pair<Set<String>, Set<String>> {
    if (raw == null) {
        return emptySet<String>() to emptySet()
    }
    val clause = (raw as? Map<*, *>)?.toJsonMap()
        ?: throw schemaFault(SchemaError.badCondition, "'$keyword'$where must be a schema object.")
    val required = parseRequired(clause[SCH.required])
    val forbidden = parseRequired(clause[SCH.not].toJsonMapOrEmpty()[SCH.required])
    val understood = setOf(SCH.required, SCH.not)
    val extra = clause.keys.filter { it !in understood }
    if (extra.isNotEmpty()) {
        throw schemaFault(
            SchemaError.badCondition,
            "'$keyword'$where carries ${extra.joinToString(", ") { "'$it'" }}; only '${SCH.required}' and " +
                "'${SCH.not}: {${SCH.required}: [...]}' are supported.",
        )
    }
    if (required.isEmpty() && forbidden.isEmpty()) {
        throw schemaFault(SchemaError.badCondition, "'$keyword'$where names no properties, so it constrains nothing.")
    }
    return required to forbidden
}

@KdrPrivate
fun parseNode(
    name: String?,
    map: Map<String, Any?>,
    state: SchParseState,
    depth: Int = 0,
    // What a refusal names: the type, or -- for a property's inline body -- the property.
    where: String = name?.let { "Type '$it'" } ?: "A schema",
): SchType {
    // Guard against runaway recursion -- e.g., a raw schema Map that references itself (see JsonUtil for the
    // same nesting guard on formatting). A legitimate schema never nests anywhere near this deep.
    if (depth > 20) {
        throw schemaFault(
            SchemaError.tooDeep, "Schema is nested too deeply (over 20 levels); it may contain a self-reference.",
        )
    }
    // Our own keywords are strict (issue #822): an unknown `g-` key, or one of ours with a value of the wrong shape,
    // fails the parse rather than being read leniently.
    SchGKeywords.problems(where, map).firstOrNull()?.let { throw it.toException() }
    refusedKeywordProblem(where, map)?.let { throw it.toException() }
    // And the standard keywords read below are held to their shapes (issue #1053), so the lenient reads that follow
    // -- `as? Boolean`, `is Map`, `is List` -- only ever meet a value of the right kind, or none.
    SchStdKeywords.problems(where, map).firstOrNull()?.let { throw it.toException() }
    val properties = LinkedHashMap<String, SchProperty>()
    val rawProps = map[SCH.properties]
    if (rawProps is Map<*, *>) {
        for ((k, v) in rawProps) {
            val pName = k.toOptStr() ?: continue
            if (v is Map<*, *>) {
                properties[pName] = parseProperty(pName, v.toJsonMap(), state, depth)
            }
        }
    }
    // The element schema of an array. A `$ref` here is deferred (bound in the resolution pass, like a property
    // ref), so a not-yet-parsed target -- or a self-reference via items -- resolves instead of expanding.
    val rawItems = map[SCH.items]
    var itemType: SchType? = null
    var itemRefName: String? = null
    if (rawItems is Map<*, *>) {
        val itemsMap = rawItems.toJsonMap()
        val itemRef = itemsMap[SCH.dRef].toOptStr()
        if (itemRef != null) {
            itemRefName = refTargetName(itemRef)
        } else {
            state.enter(SCH.items)
            // Named for where it is: a fault in an item schema is not the array's own (issue #1055).
            itemType = parseNode(null, itemsMap, state, depth + 1, where = "$where (in its item schema)")
            state.exit()
        }
    }
    // The value schema of a map (issue #1055): `additionalProperties` given as a schema. Read as `items` is, a
    // `$ref` deferred to the resolution pass. True or false is the record's own switch, read below.
    val rawAdditional = map[SCH.additionalProperties]
    var additionalValueType: SchType? = null
    var additionalRefName: String? = null
    if (rawAdditional is Map<*, *>) {
        val additionalMap = rawAdditional.toJsonMap()
        val additionalRef = additionalMap[SCH.dRef].toOptStr()
        if (additionalRef != null) {
            additionalRefName = refTargetName(additionalRef)
        } else {
            state.enter(SCH.additionalProperties)
            additionalValueType = parseNode(null, additionalMap, state, depth + 1, where = "$where (in its value schema)")
            state.exit()
        }
    }
    val jsonType = map[SCH.type].toOptStr()
    val format = map[SCH.format].toOptStr()
    val variants = parseVariants(name, map, state, depth)
    val (minBound, minExclusive) = parseBound(where, map, jsonType, lower = true)
    val (maxBound, maxExclusive) = parseBound(where, map, jsonType, lower = false)
    val schType = SchType(
        name = name,
        jsonType = jsonType,
        // Numeric types and recognized date formats are coercible by default; everything else is strict.
        allowCoerce = (map[SCH.allowCoerce] as? Boolean) ?: coercesByDefault(jsonType, format),
        // Scalars read an empty value as "not supplied"; arrays/objects opt in, and an untyped field -- which
        // constrains nothing -- is left alone.
        emptyIsAbsent = (map[SCH.emptyIsAbsent] as? Boolean) ?: isScalarType(jsonType),
        visibleOnly = parseVisibleOnly(map[SCH.visibleOnly], name, jsonType, format),
        outerWhitespace = parseOuterWhitespace(map[SCH.outerWhitespace], name, jsonType, format),
        pattern = parsePattern(where, map[SCH.pattern], jsonType, format),
        format = format,
        title = map[SCH.title].toOptStr(),
        description = map[SCH.description].toOptStr(),
        properties = properties,
        required = parseRequired(map[SCH.required]),
        // Default false when the type declares properties, true when it declares none (generic map). A schema
        // there makes the object a map of such values: undeclared properties are what it is for.
        additionalProperties = (rawAdditional as? Boolean) ?: (rawAdditional is Map<*, *> || properties.isEmpty()),
        itemType = itemType,
        options = parseOptions(where, map[SCH.options], jsonType, format),
        openOptions = map[SCH.openOptions] == true,
        constValue = parseConst(where, map[SCH.const], jsonType, format),
        // `true` or an object; either says the value is produced elsewhere, and only that much is read today.
        // An object's content is deliberately not kept: there is nothing to consume it, and a ride-along raw
        // map would be a field nobody reads that still has to be maintained.
        derived = map[SCH.derived].let { it == true || it is Map<*, *> },
        schemaDocument = map[SCH.schemaDocument] == true,
        variants = variants,
        condition = parseCondition(name, map),
        default = map[SCH.default],
        errorMessages = parseErrorMessages(map[SCH.errors], name),
        minBound = minBound,
        maxBound = maxBound,
        minExclusive = minExclusive,
        maxExclusive = maxExclusive,
        uniqueItems = parseUniqueItems(where, map[SCH.uniqueItems]),
        // Ordered, so a composite key keeps the order it was declared in (issue #487).
        primaryKey = (map[SCH.primaryKey] as? List<*>)?.mapNotNull { it.toOptStr() } ?: emptyList(),
        // A display hint only (issue #540): carried through unread by validation, for a read-only renderer.
        presentation = map[SCH.presentation].toOptStr(),
        additionalValueType = additionalValueType,
    )
    if (additionalRefName != null) {
        state.pendingMapValueRefs.add(
            PendingMapValueRef(schType, additionalRefName, childPath(state.path().orEmpty(), SCH.additionalProperties)),
        )
    }
    if (itemRefName != null) {
        state.pendingItemRefs.add(PendingItemRef(schType, itemRefName, childPath(state.path().orEmpty(), SCH.items)))
    }
    // The union was parsed before the type that owns it existed; give the boot check the name to complain
    // about now that it does.
    if (variants != null) {
        state.pendingBranchRefs.lastOrNull { it.variants === variants }?.owner = schType
    }
    return schType
}

/**
 * Which of JSON Schema's four lower-bound keywords applies to [jsonType] — `minimum` for a number,
 * `minLength` for a string, `minItems` for an array, `minProperties` for an object — or null when the type
 * measures nothing (a boolean, or an unconstrained field).
 *
 * A keyword belonging to a *different* type is simply not read, which is what JSON Schema itself does: a
 * validation keyword that does not apply to the instance type is ignored, not an error. Deliberately unlike
 * the check on `g-errors` keys, which is ours to define and so can afford to be strict — being strict here
 * would mean rejecting documents a standard validator accepts.
 */
@KdrPrivate
fun minBoundKeyword(jsonType: String?): String = when {
    isNumericType(jsonType) -> SCH.minimum
    jsonType == SCT.string -> SCH.minLength
    jsonType == SCT.array -> SCH.minItems
    jsonType == SCT.kObject -> SCH.minProperties
    else -> ""
}

/** The upper-bound counterpart of [minBoundKeyword]. */
@KdrPrivate
fun maxBoundKeyword(jsonType: String?): String = when {
    isNumericType(jsonType) -> SCH.maximum
    jsonType == SCT.string -> SCH.maxLength
    jsonType == SCT.array -> SCH.maxItems
    jsonType == SCT.kObject -> SCH.maxProperties
    else -> ""
}

/**
 * The lower ([lower]) or upper bound [map] declares for [jsonType], and whether it is exclusive -- (null, false)
 * when it declares none. The inclusive keyword is [minBoundKeyword] / [maxBoundKeyword]'s; a numeric type may also
 * declare `exclusiveMinimum` / `exclusiveMaximum` (issue #823), and where it declares both kinds on one side the
 * stricter wins, so one bound and one flag say exactly what is accepted. On any other type the exclusive keywords
 * are not read, as JSON Schema does not apply them there.
 *
 * An exclusive bound must be a number, as JSON Schema 2020-12 has it: draft 4's boolean form (`exclusiveMinimum:
 * true` beside `minimum`) is refused by name rather than read as no bound at all.
 */
@KdrPrivate
fun parseBound(where: String, map: Map<String, Any?>, jsonType: String?, lower: Boolean): Pair<Double?, Boolean> {
    val inclusive = map[if (lower) minBoundKeyword(jsonType) else maxBoundKeyword(jsonType)].toOptDouble()
    if (!isNumericType(jsonType)) return inclusive to false
    val keyword = if (lower) SCH.exclusiveMinimum else SCH.exclusiveMaximum
    val raw = map[keyword] ?: return inclusive to false
    val exclusive = (raw as? Number)?.toDouble()
        ?: throw schemaFault(
            SchemaError.badValue,
            "$where sets '$keyword' to ${if (raw is String) "'$raw'" else raw}; it must be a number (the bound " +
                "itself, as JSON Schema 2020-12 has it, not draft 4's true/false beside '${
                    if (lower) SCH.minimum else SCH.maximum
                }').",
        )
    val exclusiveWins = inclusive == null || (if (lower) exclusive >= inclusive else exclusive <= inclusive)
    return if (exclusiveWins) exclusive to true else inclusive to false
}

/**
 * Reads JSON Schema `pattern` (issue #823) into a [SchPattern], or null when absent. Only a plain string may carry
 * it -- the parser refuses it elsewhere, as it does `g-visibleOnly`: on a date or binary format the value is parsed
 * or is not text, so the pattern would check nothing where a standard validator checks the string; and on a
 * non-string it would constrain nothing, which is a mistake worth naming.
 */
@KdrPrivate
fun parsePattern(where: String, raw: Any?, jsonType: String?, format: String?): SchPattern? {
    if (raw == null) return null
    val source = raw as? String
        ?: throw schemaFault(SchemaError.badValue, "$where sets '${SCH.pattern}' to something other than text.")
    if (jsonType != SCT.string || isDateFormat(format) || isBinaryFormat(format)) {
        val actual = if (jsonType == SCT.string) "a '$format' string" else "'${jsonType ?: "untyped"}'"
        throw schemaFault(
            SchemaError.notApplicable,
            "$where has '${SCH.pattern}', which applies to a plain string, and this type is $actual. It would " +
                "constrain nothing there.",
        )
    }
    return SchPattern.compileResult(where, source).orThrow()
}

/** Reads JSON Schema `uniqueItems` (issue #823): absent is false, and anything but true or false is refused. */
@KdrPrivate
fun parseUniqueItems(where: String, raw: Any?): Boolean {
    if (raw == null) return false
    return raw as? Boolean
        ?: throw schemaFault(
            SchemaError.badValue, "$where sets '${SCH.uniqueItems}' to $raw; it must be true or false.",
        )
}

/**
 * The standard keywords refused at parse time (issue #823), each with what to use instead. Each would imply
 * behavior we do not have -- general composition, negation, schema-valued dependencies -- or duplicates a
 * construct of ours, and ignoring one would leave a schema that looks as if it constrains and does not.
 *
 * A **denylist**, deliberately: any other keyword this layer does not read stays allowed, so a document may carry
 * keywords of its own. (`not` stays legal inside an `if`/`then`/`else` clause, where [parseCondition] reads it.)
 */
@KdrPrivate
val refusedKeywords: Map<String, String> = linkedMapOf(
    SCH.enum to "Declare the choices with '${SCH.options}' (the builder's option(...)); they export as 'enum'.",
    SCH.allOf to "Compose by extending a type, or declare the properties on the type itself.",
    SCH.anyOf to "Use a discriminated '${SCH.oneOf}', whose branches say which one they are.",
    SCH.not to "Constrain what is accepted directly; a negated schema is not supported.",
    SCH.dependentSchemas to "Use '${SCH.kIf}'/'${SCH.kThen}'/'${SCH.kElse}' to require or forbid properties by " +
        "another's value.",
)

/**
 * The first keyword of [map] in [refusedKeywords], as a [SchemaError.refusedKeyword] problem naming it and
 * [where]; null when there is none.
 */
@KdrPrivate
fun refusedKeywordProblem(where: String, map: Map<String, Any?>): Problem? {
    val keyword = refusedKeywords.keys.firstOrNull { it in map } ?: return null
    return Problem(
        SchemaError.refusedKeyword,
        "$where uses '$keyword', which is not supported. ${refusedKeywords.getValue(keyword)}",
    )
}

/**
 * Reads the custom `g-visibleOnly` keyword (issue #543): absent is off, `true`/`false` say so, and anything
 * else fails the parse.
 *
 * **Strict where the standard keywords are lenient**, on the same reasoning as `g-errors`: this keyword is
 * ours to define, so a document a stock validator accepts is not at stake, and the failure it prevents is the
 * silent kind. A `"yes"` read as off would leave a field believing it was protected; so would `true` on an
 * integer, where the check never runs because no integer is a string. Both are refused by name at boot rather
 * than left to constrain nothing. A date-format or binary-format string is refused with the non-strings: its
 * value is parsed as a date, or is not text at all, so there are no characters for the rule to see.
 */
@KdrPrivate
fun parseVisibleOnly(raw: Any?, typeName: String?, jsonType: String?, format: String?): Boolean {
    if (raw == null) return false
    val where = typeName?.let { " on '$it'" } ?: ""
    val on = raw as? Boolean
        ?: throw schemaFault(SchemaError.badValue, "'${SCH.visibleOnly}'$where must be true or false, not '$raw'.")
    if (on && (jsonType != SCT.string || isDateFormat(format) || isBinaryFormat(format))) {
        val actual = if (jsonType == SCT.string) "a '$format' string" else "'${jsonType ?: "untyped"}'"
        throw schemaFault(
            SchemaError.notApplicable,
            "'${SCH.visibleOnly}'$where applies to a plain string, and this type is $actual. It would " +
                "constrain nothing there."
        )
    }
    return on
}

/**
 * Reads the custom `g-outerWhitespace` keyword (issues #541, #765): absent is null, `"trim"` / `"reject"` /
 * `"keep"` map to [SchOuterWhitespace], and anything else fails the parse. Null is not the same as `"keep"`:
 * the validator trims a null-mode plain string on the input path by default (#765), and `"keep"` is the opt-out.
 *
 * **Strict where the standard keywords are lenient**, on the same reasoning as [parseVisibleOnly]: the keyword
 * is ours, so no stock-validator document is at stake, and the failure it prevents is the silent kind -- a
 * `"strip"` misspelling read as "leave whitespace alone" would quietly disable the protection, and `"trim"` on
 * an integer would constrain nothing (no integer is a string, so the check never runs). Both are refused by
 * name at boot. A date-format or binary-format string is refused with the non-strings: its value is parsed as
 * a date, or is not text at all, so there is no edge whitespace for the rule to see.
 */
@KdrPrivate
fun parseOuterWhitespace(raw: Any?, typeName: String?, jsonType: String?, format: String?): SchOuterWhitespace? {
    if (raw == null) return null
    val where = typeName?.let { " on '$it'" } ?: ""
    val mode = when (raw) {
        SOWS.trim -> SchOuterWhitespace.trim
        SOWS.reject -> SchOuterWhitespace.reject
        SOWS.keep -> SchOuterWhitespace.keep
        else -> throw schemaFault(
            SchemaError.badValue,
            "'${SCH.outerWhitespace}'$where must be '${SOWS.trim}', '${SOWS.reject}' or '${SOWS.keep}', not '$raw'."
        )
    }
    if (jsonType != SCT.string || isDateFormat(format) || isBinaryFormat(format)) {
        val actual = if (jsonType == SCT.string) "a '$format' string" else "'${jsonType ?: "untyped"}'"
        throw schemaFault(
            SchemaError.notApplicable,
            "'${SCH.outerWhitespace}'$where applies to a plain string, and this type is $actual. It would " +
                "constrain nothing there."
        )
    }
    return mode
}

/** Whether a JSON Schema type is one of the numeric types (part of the [SCH.allowCoerce] default). */
@KdrPrivate
fun isNumericType(jsonType: String?): Boolean = jsonType == SCT.integer || jsonType == SCT.number

/**
 * Whether a JSON Schema type is a single value rather than a container (the [SCH.emptyIsAbsent] default).
 * An unconstrained (null) type is deliberately not scalar: there is no basis for reading its emptiness.
 */
@KdrPrivate
fun isScalarType(jsonType: String?): Boolean =
    jsonType == SCT.string || jsonType == SCT.boolean || isNumericType(jsonType)

/**
 * Whether a type coerces from a string unless the schema says otherwise -- the [SCH.allowCoerce] default
 * (issue #439).
 *
 * **The rule is "could this only ever arrive as text?"** A query string and a form encoding carry nothing but
 * strings, so a parameter of one of these types could not be supplied *at all* without coercion: there would
 * be no spelling of `5` or of `true` that worked. Strings, arrays, and objects are left strict because they
 * have a faithful spelling on those transports already, or no sensible one at all.
 *
 * Booleans were the odd omission, and the asymmetry was invisible until it bit: `?publicApi=true` failed as a
 * `wrongType` while `?limit=5` beside it worked, with nothing at either declaration to say why. Note that
 * admitting them costs less strictness than it sounds -- see `parseExactBool`, which recognizes a closed set
 * of spellings rather than guessing.
 */
@KdrPrivate
fun coercesByDefault(jsonType: String?, format: String?): Boolean =
    isNumericType(jsonType) || jsonType == SCT.boolean || isDateFormat(format)

/** Whether a `format` value is one of the date formats we validate/coerce ([SFMT.date] / [SFMT.dateTime]). */
@KdrPrivate
fun isDateFormat(format: String?): Boolean = format == SFMT.date || format == SFMT.dateTime

/**
 * Whether [format] marks file content ([SFMT.binary]) — OpenAPI's `type: string, format: binary`.
 *
 * The one question everything else asks: the validator asks it to leave the value alone (it is a `ContentData`,
 * not text), the dispatcher asks it to decide a request is a multipart upload, and the frontend asks it to
 * render a file picker instead of a text box.
 */
fun isBinaryFormat(format: String?): Boolean = format == SFMT.binary

/**
 * Parses the custom `options` construct: a list whose entries are each a `{value, label}` map or a **bare value**
 * (issue #816), mixed as the author likes. A bare value, and a map with no `label`, is labeled by its value -- the
 * same two forms the narrowing check reads. An entry that is neither -- a map without a `value`, a list, a null --
 * fails the parse, naming its position, rather than being dropped: `["a","b"]` once parsed to an empty closed list
 * that refused every value.
 *
 * **Only a plain string may carry one** (issue #815). A choice is a string -- its `value` is read as text -- so on
 * any other declared type a closed list rejected every correctly typed value, and on a date or binary format it was
 * never consulted at all, since those return before the choice check. Both are refused rather than left to
 * misbehave. An **untyped** field may carry one: the list itself says the value is text, and it is checked as such.
 */
@KdrPrivate
fun parseOptions(where: String, raw: Any?, jsonType: String?, format: String?): List<SchOption>? {
    if (raw !is List<*>) return null
    if (jsonType != null && jsonType != SCT.string || isDateFormat(format) || isBinaryFormat(format)) {
        val actual = if (jsonType == SCT.string) "a '$format' string" else "'$jsonType'"
        throw schemaFault(
            SchemaError.notApplicable,
            "$where has '${SCH.options}', which apply to a plain string, and this type is $actual. A choice is " +
                "text, so a list here would refuse every value or check none.",
        )
    }
    return raw.mapIndexed { i, entry ->
        val value = when (entry) {
            is Map<*, *> -> entry[SCH.value].takeIf { it is String || it is Number || it is Boolean }.toOptStr()
            is String, is Number, is Boolean -> entry.toOptStr()
            else -> null
        } ?: throw schemaFault(
            SchemaError.badValue,
            "$where has '${SCH.options}' entry ${i + 1}, which is neither a value nor a " +
                "{'${SCH.value}', '${SCH.label}'} object with a '${SCH.value}'.",
        )
        SchOption(value, (entry as? Map<*, *>)?.get(SCH.label).toOptStr() ?: value)
    }
}

/**
 * Reads JSON Schema `const`, or null when absent. Refused on a date or binary format (issue #815): those fields
 * are validated by parsing or passed through before the `const` check is reached, so it would constrain nothing.
 */
@KdrPrivate
fun parseConst(where: String, raw: Any?, jsonType: String?, format: String?): Any? {
    if (raw != null && jsonType == SCT.string && (isDateFormat(format) || isBinaryFormat(format))) {
        throw schemaFault(
            SchemaError.notApplicable,
            "$where has '${SCH.const}' on a '$format' string, which is never compared against it. It would " +
                "constrain nothing there.",
        )
    }
    return raw
}

/**
 * Parses the custom `g-errors` construct: a map from error key to the message that failure should show.
 *
 * **Every key is checked here, and an unrecognized one fails the parse** rather than being ignored. A message
 * filed under a misspelled code is silent in the worst way — the schema looks like it says something, and the
 * only symptom is the framework's own wording turning up where "custom copy" was expected. The valid keys are a
 * small closed set, so saying which one is wrong costs nothing. This is the same reasoning that puts named
 * functions on the builder: from code the mistake is unwriteable, and this catches the documents that did not
 * come through it.
 *
 * A value that is not a string is skipped rather than rejected: the object form is reserved for a future
 * markdown-fragment reference (`{"fragment": "score.required"}`), so a document using it early degrades to
 * the built-in wording instead of failing to load.
 */
@KdrPrivate
fun parseErrorMessages(raw: Any?, typeName: String?): Map<String, String> =
    readErrorMessages(raw, typeName).orThrow { KdrException(it) }

/**
 * [parseErrorMessages] without the throw (issue #909): the messages, or the first key that names no failure code
 * as a [SchemaError.badValue] problem located at that key -- for a layout's error override, which collects its
 * problems rather than stopping at one.
 */
@KdrPrivate
fun readErrorMessages(raw: Any?, typeName: String?): Parsed<Map<String, String>> {
    if (raw !is Map<*, *>) return Parsed.Ok(emptyMap())
    val out = LinkedHashMap<String, String>(raw.size)
    for ((k, v) in raw) {
        val key = k.toOptStr() ?: continue
        if (key != SCH.errorDefault && SchFailCode.entries.none { it.name == key }) {
            val valid = (SchFailCode.entries.map { it.name } + SCH.errorDefault).joinToString(", ")
            return Parsed.failed(
                SchemaError.badValue,
                "'${SCH.errors}'${typeName?.let { " on '$it'" } ?: ""} names '$key', which is not a failure " +
                    "code. Valid keys: $valid.",
                ProblemLocation(path = key),
            )
        }
        v.toOptStr()?.let { out[key] = it }
    }
    return Parsed.Ok(out)
}

@KdrPrivate
fun parseProperty(name: String, map: Map<String, Any?>, state: SchParseState, depth: Int): SchProperty {
    state.enter("${SCH.properties}.$name")
    val ref = map[SCH.dRef].toOptStr()
    if (ref != null) {
        // The keywords on the property itself, which a `$ref` property's target never sees (issue #822); the
        // refused standard ones (issue #823), which would otherwise be ignored beside a `$ref`; and the standard
        // ones read here -- its description, its title, the `$ref` itself (issue #1053). Only beside a `$ref`: an
        // inline property's own map is the node `parseNode` is handed below, which runs the same three checks.
        SchGKeywords.problems("Property '$name'", map).firstOrNull()?.let { throw it.toException() }
        refusedKeywordProblem("Property '$name'", map)?.let { throw it.toException() }
        SchStdKeywords.problems("Property '$name'", map).firstOrNull()?.let { throw it.toException() }
    }
    val description = map[SCH.description].toOptStr()
    // On the property, not only its value type -- see [SchProperty.title] for why a `$ref` field needs its own.
    val title = map[SCH.title].toOptStr()
    // Read before the $ref/inline split so an edit's `data` -- a $ref -- carries it too (issue #487).
    val optionalContents = map[SCH.optionalContents] == true
    // Read before the split too (issue #540): a hint beside a `$ref` belongs to the site, not the shared target.
    val presentation = map[SCH.presentation].toOptStr()
    // Read before the split too (issue #564): a visibility gate beside a `$ref` belongs to the use site, and it
    // must reach the parsed property so the frontend -- which re-parses the served schema -- can evaluate it.
    val visibleWhen = map[SCH.visibleWhen].toOptStr()
    if (ref != null) {
        val prop = SchProperty(name, description, refTargetName(ref), title, optionalContents, presentation, visibleWhen)
        state.pendingRefs.add(PendingRef(prop, state.path().orEmpty())) // valueType bound in the resolution pass
        state.exit()
        return prop
    }
    val prop = SchProperty(
        name, description, refName = null, title = title, optionalContents = optionalContents,
        presentation = presentation, visibleWhen = visibleWhen,
    )
    prop.valueType = parseNode(null, map, state, depth + 1, where = "Property '$name'")
    state.exit()
    return prop
}

@KdrPrivate
fun parseRequired(raw: Any?): Set<String> =
    if (raw is List<*>) raw.mapNotNullTo(LinkedHashSet()) { it.toOptStr() } else emptySet()

/** Extracts the type name from a `$ref` like "#/${'$'}defs/core.Count". */
@KdrPrivate
fun refTargetName(ref: String): String {
    val prefix = "#/${SCH.dDefs}/"
    return if (ref.startsWith(prefix)) ref.substring(prefix.length) else ref
}
