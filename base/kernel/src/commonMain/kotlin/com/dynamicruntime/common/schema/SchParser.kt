package com.dynamicruntime.common.schema

import com.dynamicruntime.common.annotation.KdrPrivate
import com.dynamicruntime.common.exception.KdrException
import com.dynamicruntime.common.util.toJsonMap
import com.dynamicruntime.common.util.toJsonMapOrEmpty
import com.dynamicruntime.common.util.toOptStr
import com.dynamicruntime.common.util.toOptDouble

/**
 * Parses a `$defs`-style map of JSON Schema types (e.g., the output of
 * `schemaDefs { ... }`) into resolved [SchType] / [SchProperty] objects.
 *
 * Every `$ref` is checked: its target must be one of the types in [defs] or in
 * [existingTypes]; otherwise a [KdrException] is thrown. `anyOf` and `allOf` are
 * not handled yet; `oneOf` needs a declared discriminator (see [parseVariants])
 * and `if`/`then`/`else` is read in one narrow shape (see [parseCondition]).
 *
 * **An unrecognized keyword is ignored, not rejected** — deliberately, since being strict about standard
 * keywords would reject documents a stock validator accepts. Note what that costs, because it is not free: a
 * keyword we do not read constrains nothing, silently. Before issue #252 a `oneOf` parsed to a type with no
 * `type`, no properties and `additionalProperties` true, so a document could claim to be a discriminated union
 * and enforce nothing at all, with no symptom. That is the argument for reading a construct rather than
 * deferring it, and the reason [SCH.discriminator] is *required* alongside a `oneOf` we do read.
 *
 * @return the newly parsed types keyed by fully qualified name.
 */
fun parseSchemaTypes(
    defs: Map<String, Any?>,
    existingTypes: Map<String, SchType> = emptyMap(),
): Map<String, SchType> {
    val pendingRefs = mutableListOf<SchProperty>()
    val pendingItemRefs = mutableListOf<PendingItemRef>()
    val pendingBranchRefs = mutableListOf<PendingBranchRef>()
    val parsed = LinkedHashMap<String, SchType>()
    for ((name, raw) in defs) {
        if (raw is Map<*, *>) {
            parsed[name] = parseNode(name, raw.toJsonMap(), pendingRefs, pendingItemRefs, pendingBranchRefs)
        }
    }
    // Resolve $refs against the existing types plus the just-parsed ones.
    val registry = HashMap(existingTypes)
    registry.putAll(parsed)
    for (prop in pendingRefs) {
        val refName = prop.refName ?: continue
        prop.valueType = registry[refName]
            ?: throw KdrException.mkConv($$"Schema $ref to unknown type '$$refName'.")
    }
    // Bind array element types whose `items` was a $ref (deferred the same way as property refs, so a target
    // parsed later -- or a self-reference via items -- resolves without expanding during parsing).
    for (item in pendingItemRefs) {
        item.array.itemType = registry[item.refName]
            ?: throw KdrException.mkConv($$"Schema $ref to unknown type '$${item.refName}'.")
    }
    // Bind a union's branches for the same reason: a branch is normally a $ref, and one of them may refer
    // back to the union itself. Done a whole union at a time so the branches land in the order the document
    // declared them, mixed inline and $ref included -- "branch 3" in a boot-check message has to be the
    // reader's third branch, or the diagnostic sends them to the wrong place.
    for (union in pendingBranchRefs) {
        for (source in union.sources) {
            union.variants.branches.add(
                source.inline ?: registry[source.refName]
                    ?: throw KdrException.mkConv($$"Schema $ref to unknown type '$${source.refName}'."),
            )
        }
        union.defaultRef?.let { ref ->
            union.variants.defaultBranch = registry[ref]
                ?: throw KdrException.mkConv($$"Schema $ref to unknown type '$$ref'.")
        }
        indexVariants(union.owner, union.variants)
    }
    return parsed
}

/** An array [SchType] whose `items` is a `$ref` ([refName]), awaiting binding in the resolution pass. */
@KdrPrivate
class PendingItemRef(val array: SchType, val refName: String)

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
            ?: throw KdrException.mkConv(
                "Branch ${index + 1}$where declares no '${variants.discriminator}' property, so nothing " +
                    "selects it. Every branch of a discriminated union must declare the discriminator with a " +
                    "'${SCH.const}'.",
            )
        val declared = prop.valueType.constValue.toOptStr()
            ?: throw KdrException.mkConv(
                "Branch ${index + 1}$where has no '${SCH.const}' for '${variants.discriminator}', so nothing " +
                    "selects it.",
            )
        val clash = byValue.put(declared, branch)
        if (clash != null) {
            throw KdrException.mkConv(
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
fun parseVariants(
    name: String?,
    map: Map<String, Any?>,
    pendingRefs: MutableList<SchProperty>,
    pendingItemRefs: MutableList<PendingItemRef>,
    pendingBranchRefs: MutableList<PendingBranchRef>,
    depth: Int,
): SchVariants? {
    val rawBranches = map[SCH.oneOf] as? List<*> ?: return null
    val where = name?.let { " on '$it'" } ?: ""
    val rawDiscriminator = map[SCH.discriminator]
    if (rawDiscriminator !is Map<*, *>) {
        throw KdrException.mkConv(
            "'${SCH.oneOf}'$where has no '${SCH.discriminator}'. A union has to say which property selects " +
                "the branch, so a failure can be reported against the branch that was meant.",
        )
    }
    val discriminator = rawDiscriminator.toJsonMap()[SCH.propertyName].toOptStr()
        ?: throw KdrException.mkConv(
            "'${SCH.discriminator}'$where has no '${SCH.propertyName}'.",
        )
    if (rawBranches.isEmpty()) {
        throw KdrException.mkConv("'${SCH.oneOf}'$where declares no branches.")
    }
    val sources = rawBranches.mapNotNull { raw ->
        val branchMap = (raw as? Map<*, *>)?.toJsonMap() ?: return@mapNotNull null
        val ref = branchMap[SCH.dRef].toOptStr()
        if (ref != null) {
            BranchSource(null, refTargetName(ref))
        } else {
            BranchSource(
                parseNode(null, branchMap, pendingRefs, pendingItemRefs, pendingBranchRefs, depth + 1),
                null,
            )
        }
    }
    val variants = SchVariants(discriminator, mutableListOf(), null)
    pendingBranchRefs.add(
        PendingBranchRef(
            variants,
            sources,
            rawDiscriminator.toJsonMap()[SCH.defaultMapping].toOptStr()?.let { refTargetName(it) },
            owner = null,
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
            throw KdrException.mkConv(
                "'${SCH.kThen}'/'${SCH.kElse}'$where without an '${SCH.kIf}' decides nothing.",
            )
        }
        return null
    }
    if (rawThen == null && rawElse == null) {
        throw KdrException.mkConv(
            "'${SCH.kIf}'$where has no '${SCH.kThen}' or '${SCH.kElse}', so it constrains nothing.",
        )
    }
    val ifMap = (rawIf as? Map<*, *>)?.toJsonMap()
        ?: throw KdrException.mkConv("'${SCH.kIf}'$where must be a schema object.")
    val tested = ifMap[SCH.properties].toJsonMapOrEmpty()
    if (tested.size != 1) {
        throw KdrException.mkConv(
            "'${SCH.kIf}'$where must test exactly one property with a '${SCH.const}'; this layer reads that " +
                "shape only. Anything more general is not supported, and is refused rather than half-applied.",
        )
    }
    val (property, rawTest) = tested.entries.first()
    val test = (rawTest as? Map<*, *>)?.toJsonMap()
        ?: throw KdrException.mkConv("'${SCH.kIf}'$where must test '$property' with a '${SCH.const}'.")
    if (SCH.const !in test) {
        throw KdrException.mkConv(
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
        ?: throw KdrException.mkConv("'$keyword'$where must be a schema object.")
    val required = parseRequired(clause[SCH.required])
    val forbidden = parseRequired(clause[SCH.not].toJsonMapOrEmpty()[SCH.required])
    val understood = setOf(SCH.required, SCH.not)
    val extra = clause.keys.filter { it !in understood }
    if (extra.isNotEmpty()) {
        throw KdrException.mkConv(
            "'$keyword'$where carries ${extra.joinToString(", ") { "'$it'" }}; only '${SCH.required}' and " +
                "'${SCH.not}: {${SCH.required}: [...]}' are supported.",
        )
    }
    if (required.isEmpty() && forbidden.isEmpty()) {
        throw KdrException.mkConv("'$keyword'$where names no properties, so it constrains nothing.")
    }
    return required to forbidden
}

@KdrPrivate
fun parseNode(
    name: String?,
    map: Map<String, Any?>,
    pendingRefs: MutableList<SchProperty>,
    pendingItemRefs: MutableList<PendingItemRef>,
    pendingBranchRefs: MutableList<PendingBranchRef>,
    depth: Int = 0,
): SchType {
    // Guard against runaway recursion -- e.g., a raw schema Map that references itself (see JsonUtil for the
    // same nesting guard on formatting). A legitimate schema never nests anywhere near this deep.
    if (depth > 20) {
        throw KdrException.mkConv("Schema is nested too deeply (over 20 levels); it may contain a self-reference.")
    }
    val properties = LinkedHashMap<String, SchProperty>()
    val rawProps = map[SCH.properties]
    if (rawProps is Map<*, *>) {
        for ((k, v) in rawProps) {
            val pName = k.toOptStr() ?: continue
            if (v is Map<*, *>) {
                properties[pName] =
                    parseProperty(pName, v.toJsonMap(), pendingRefs, pendingItemRefs, pendingBranchRefs, depth)
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
            itemType = parseNode(null, itemsMap, pendingRefs, pendingItemRefs, pendingBranchRefs, depth + 1)
        }
    }
    val jsonType = map[SCH.type].toOptStr()
    val format = map[SCH.format].toOptStr()
    val variants = parseVariants(name, map, pendingRefs, pendingItemRefs, pendingBranchRefs, depth)
    val schType = SchType(
        name = name,
        jsonType = jsonType,
        // Numeric types and recognized date formats are coercible by default; everything else is strict.
        allowCoerce = (map[SCH.allowCoerce] as? Boolean) ?: coercesByDefault(jsonType, format),
        // Scalars read an empty value as "not supplied"; arrays/objects opt in, and an untyped field -- which
        // constrains nothing -- is left alone.
        emptyIsAbsent = (map[SCH.emptyIsAbsent] as? Boolean) ?: isScalarType(jsonType),
        format = format,
        title = map[SCH.title].toOptStr(),
        description = map[SCH.description].toOptStr(),
        properties = properties,
        required = parseRequired(map[SCH.required]),
        // Default false when the type declares properties, true when it declares none (generic map).
        additionalProperties = (map[SCH.additionalProperties] as? Boolean) ?: properties.isEmpty(),
        itemType = itemType,
        options = parseOptions(map[SCH.options]),
        openOptions = map[SCH.openOptions] == true,
        constValue = map[SCH.const],
        // `true` or an object; either says the value is produced elsewhere, and only that much is read today.
        // An object's content is deliberately not kept: there is nothing to consume it, and a ride-along raw
        // map would be a field nobody reads that still has to be maintained.
        derived = map[SCH.derived].let { it == true || it is Map<*, *> },
        variants = variants,
        condition = parseCondition(name, map),
        default = map[SCH.default],
        errorMessages = parseErrorMessages(map[SCH.errors], name),
        minBound = map[minBoundKeyword(jsonType)].toOptDouble(),
        maxBound = map[maxBoundKeyword(jsonType)].toOptDouble(),
    )
    if (itemRefName != null) {
        pendingItemRefs.add(PendingItemRef(schType, itemRefName))
    }
    // The union was parsed before the type that owns it existed; give the boot check the name to complain
    // about now that it does.
    if (variants != null) {
        pendingBranchRefs.lastOrNull { it.variants === variants }?.owner = schType
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

/** Whether a JSON Schema type is one of the numeric types (the [SCH.allowCoerce] default). */
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
 * be no spelling of `5` or of `true` that worked. Strings, arrays and objects are left strict because they
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

/** Parses the custom `options` construct: a list of `{label, value}` entries.
 *  A missing `label` defaults to the `value`. */
@KdrPrivate
fun parseOptions(raw: Any?): List<SchOption>? {
    if (raw !is List<*>) return null
    return raw.mapNotNull { entry ->
        if (entry is Map<*, *>) {
            val value = entry[SCH.value].toOptStr() ?: return@mapNotNull null
            SchOption(value, entry[SCH.label].toOptStr() ?: value)
        } else {
            null
        }
    }
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
fun parseErrorMessages(raw: Any?, typeName: String?): Map<String, String> {
    if (raw !is Map<*, *>) return emptyMap()
    val out = LinkedHashMap<String, String>(raw.size)
    for ((k, v) in raw) {
        val key = k.toOptStr() ?: continue
        if (key != SCH.errorDefault && SchFailCode.entries.none { it.name == key }) {
            val valid = (SchFailCode.entries.map { it.name } + SCH.errorDefault).joinToString(", ")
            throw KdrException(
                "'${SCH.errors}'${typeName?.let { " on '$it'" } ?: ""} names '$key', which is not a failure " +
                    "code. Valid keys: $valid."
            )
        }
        v.toOptStr()?.let { out[key] = it }
    }
    return out
}

@KdrPrivate
fun parseProperty(
    name: String,
    map: Map<String, Any?>,
    pendingRefs: MutableList<SchProperty>,
    pendingItemRefs: MutableList<PendingItemRef>,
    pendingBranchRefs: MutableList<PendingBranchRef>,
    depth: Int,
): SchProperty {
    val description = map[SCH.description].toOptStr()
    // On the property, not only its value type -- see [SchProperty.title] for why a `$ref` field needs its own.
    val title = map[SCH.title].toOptStr()
    val ref = map[SCH.dRef].toOptStr()
    if (ref != null) {
        val prop = SchProperty(name, description, refTargetName(ref), title)
        pendingRefs.add(prop) // valueType bound in the resolution pass
        return prop
    }
    val prop = SchProperty(name, description, refName = null, title = title)
    prop.valueType = parseNode(null, map, pendingRefs, pendingItemRefs, pendingBranchRefs, depth + 1)
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
