package com.dynamicruntime.common.schema

import com.dynamicruntime.common.annotation.KdrPrivate
import com.dynamicruntime.common.config.KdrConfigData
import com.dynamicruntime.common.context.KdrCxtBase
import com.dynamicruntime.common.util.deepClone
import com.dynamicruntime.common.util.toT
import kotlin.reflect.KProperty

/**
 * Map-backed nullable delegate for a single schema attribute stored under [key].
 * Reading returns `null` when the key is absent; writing `null` removes it. Lets
 * a builder expose optional keywords as plain `var`s (`b.title = "..."`).
 */
class SchAttr<T>(private val data: MutableMap<String, Any?>, private val key: String) {
    operator fun getValue(thisRef: Any?, property: KProperty<*>): T? = data[key]?.toT()

    operator fun setValue(thisRef: Any?, property: KProperty<*>, value: T?) {
        if (value == null) data.remove(key) else data[key] = value
    }
}

/**
 * The `g-errors` block of a field: one function per [SchFailCode], plus [default] for anything not named.
 *
 * Named functions rather than a `code to message` map on purpose. A map would accept a misspelled key and
 * fail at boot; here the mistake cannot be written at all, and adding a failure code to the enum makes every
 * schema that should say something about it fail to compile until it does — which is the point at which
 * somebody is actually thinking about that code.
 */
class SchErrors(private val data: MutableMap<String, Any?>) {
    /** Shown when the field is required and was not supplied. */
    fun missingRequired(message: String) = set(SchFailCode.missingRequired, message)

    /** Shown when the value was the wrong shape before its content was even looked at. */
    fun wrongType(message: String) = set(SchFailCode.wrongType, message)

    /** Shown when the content was inspected and would not parse or coerce — a malformed date or number. */
    fun badValue(message: String) = set(SchFailCode.badValue, message)

    /** Shown when the value is not one of the field's declared options. */
    fun invalidOption(message: String) = set(SchFailCode.invalidOption, message)

    /** Shown when the field was supplied but a conditional rule says it may not be, given some other field. */
    fun notAllowed(message: String) = set(SchFailCode.notAllowed, message)

    /**
     * Shown when the value falls short of the field's lower bound — whichever of `minimum`, `minLength`,
     * `minItems` or `minProperties` its type declares. One function, because a field has one type and so one
     * of those can apply.
     */
    fun belowMinimum(message: String) = set(SchFailCode.belowMinimum, message)

    /** Shown when the value exceeds the field's upper bound; the counterpart of [belowMinimum]. */
    fun aboveMaximum(message: String) = set(SchFailCode.aboveMaximum, message)

    /**
     * Shown for any failure this block does not name. Not itself a failure code — the fallback *after* the
     * specific ones and *before* the validator's built-in wording.
     */
    fun default(message: String) {
        data[SCH.errorDefault] = message
    }

    private fun set(code: SchFailCode, message: String) {
        data[code.name] = message
    }
}

/** Qualifies a bare type [name] with [namespace]. A name that already contains a
 *  '.' is treated as fully qualified and returned unchanged. */
fun qualifyTypeName(name: String, namespace: String): String =
    if (name.contains('.')) name else "$namespace.$name"

/** JSON Pointer to a type under `$defs`, e.g. "#/${'$'}defs/core.Count". A bare
 *  [name] is resolved within [namespace]; a dotted name is used as-is. */
fun typeRefPath(name: String, namespace: String): String =
    "#/${SCH.dDefs}/${qualifyTypeName(name, namespace)}"

/**
 * Builder for a single JSON Schema "type" (a schema node, usually an object
 * schema). Map-backed like the application-config builders ([KdrConfigData]), so
 * a built type is just an insertion-ordered `Map<String,Any?>` ready to be
 * rendered as JSON Schema.
 *
 * Carries the [namespace] it is declared in so that `$ref`s to sibling types can
 * be written with bare names. Required is tracked ON THE SIDE: marking a property
 * required records its name in this type's `required` array rather than as a flag
 * on the field.
 */
open class SchTypeBuilder(
    cxt: KdrCxtBase,
    val namespace: String,
    data: MutableMap<String, Any?> = LinkedHashMap(),
) : KdrConfigData(cxt, data) {

    var type: String? by SchAttr(data, SCH.type)
    var description: String? by SchAttr(data, SCH.description)
    var format: String? by SchAttr(data, SCH.format)

    /** Custom `allowCoerce` keyword. When unset, the parser defaults it (true for
     *  numeric types, false otherwise). */
    var allowCoerce: Boolean? by SchAttr(data, SCH.allowCoerce)

    /**
     * Custom `emptyIsAbsent` keyword: whether an empty value for this field means the field was not supplied.
     * When unset, the parser defaults it (true for scalars, false for arrays, objects, and untyped fields).
     *
     * Set it to **false** on a string field whose empty value is meaningful -- notably an update endpoint
     * where `""` means "clear this", as opposed to omitting the field to mean "leave it alone". Set it to
     * **true** on a list or object field to have an empty one read as no value at all.
     */
    var emptyIsAbsent: Boolean? by SchAttr(data, SCH.emptyIsAbsent)

    /** Whether undeclared properties are allowed. When unset, the parser defaults it (false when the type
     *  has declared properties, true when it has none). Set explicitly to allow extras on a defined type. */
    var additionalProperties: Boolean? by SchAttr(data, SCH.additionalProperties)

    // JSON Schema's four min/max pairs (issue #203). Each is spelled with the standard keyword for its type,
    // because that is what the document has to say; the parser folds whichever one applies into a single
    // bound on SchType, since a type declares at most one pair. A pair belonging to another type is ignored
    // rather than rejected -- that is what a standard validator does with an inapplicable keyword.
    // `exclusiveMinimum`/`exclusiveMaximum` are deliberately not supported: rare in practice, and they would
    // complicate both the bound and its wording for no case anything here has.

    /** Smallest accepted value, for a number or integer field. */
    var minimum: Number? by SchAttr(data, SCH.minimum)

    /** Largest accepted value, for a number or integer field. */
    var maximum: Number? by SchAttr(data, SCH.maximum)

    /** Fewest accepted characters, for a string field. Counted in code points, so an emoji counts once. */
    var minLength: Number? by SchAttr(data, SCH.minLength)

    /** Most accepted characters, for a string field; see [minLength] on how they are counted. */
    var maxLength: Number? by SchAttr(data, SCH.maxLength)

    /** Fewest accepted elements, for an array field. */
    var minItems: Number? by SchAttr(data, SCH.minItems)

    /** Most accepted elements, for an array field. */
    var maxItems: Number? by SchAttr(data, SCH.maxItems)

    /** Fewest accepted properties, for an object field. */
    var minProperties: Number? by SchAttr(data, SCH.minProperties)

    /** Most accepted properties, for an object field. */
    var maxProperties: Number? by SchAttr(data, SCH.maxProperties)

    /** JSON Schema `default` value. A required property with a default does not
     *  fail validation when missing, and coercion injects the default. */
    var default: Any? by SchAttr(data, SCH.default)

    /**
     * Custom `g-derived` keyword: this value is produced by something other than the caller (issue #254), so
     * it is neither asked for on the way in nor accepted from them.
     *
     * `true` is the everyday form and says only *that* it is produced elsewhere -- a code-backed pre-processor
     * supplies it. The parser also accepts an object, reserved for saying *how* once there is a language to
     * express a computation in; accepting both now means widening later is not a migration of stored
     * documents.
     */
    var derived: Any? by SchAttr(data, SCH.derived)

    /**
     * JSON Schema `const`: the one value this field admits.
     *
     * Its everyday use is a union branch declaring which branch it is — and there it is what makes the branch
     * selectable **without** our discriminator keyword, so a stock validator trying every branch reaches the
     * same answer we do. Set through "variantBranch" rather than by hand for that case.
     */
    var const: Any? by SchAttr(data, SCH.const)

    /**
     * Declares that [field] is present exactly when [on] holds [value] — JSON Schema's `if`/`then`/`else`,
     * emitted in the one shape this layer reads (issue #253).
     *
     * The commonest shape a trait's data takes: `{"hasValue": true, "value": "approve"}`, where `value` is
     * required when the box is ticked and inadmissible when it is not, written as
     * `presentWhen("value", on = "hasValue", value = true)`.
     *
     * **The emitted `if` repeats `required`, and that is the point of having a helper at all.** JSON Schema's
     * `if` evaluates a subschema, and a bare `{"properties": {"hasValue": {"const": true}}}` passes
     * *vacuously* when the property is absent — so an omitted `hasValue` would satisfy it, the `then` clause
     * would fire, and the validator would demand `value` from a payload that said nothing. Written by hand
     * that trap is invisible until someone submits an empty form; written here it is impossible.
     *
     * At most one per type, since JSON Schema allows one `if` per schema object — see [SchCondition].
     */
    fun presentWhen(field: String, on: String, value: Any?) {
        data[SCH.kIf] = linkedMapOf<String, Any?>(
            SCH.required to listOf(on),
            SCH.properties to linkedMapOf(on to linkedMapOf(SCH.const to value)),
        )
        data[SCH.kThen] = linkedMapOf<String, Any?>(SCH.required to listOf(field))
        data[SCH.kElse] = linkedMapOf<String, Any?>(
            SCH.not to linkedMapOf<String, Any?>(SCH.required to listOf(field)),
        )
    }

    /**
     * Makes this schema a `$ref` to another type. A bare [name] resolves within
     * this builder's [namespace]; a dotted name (e.g. "core.Count") is used as-is.
     */
    fun ref(name: String) {
        data[SCH.dRef] = typeRefPath(name, namespace)
    }

    /**
     * Adds a choice to the custom `options` construct: a [value] (the stored data)
     * and an optional display [label], which defaults to the value when redundant.
     */
    fun option(value: String, label: String = value) {
        optionsList().add(linkedMapOf(SCH.label to label, SCH.value to value))
    }

    /**
     * Adds every value of an enum as a choice ([Enum.name] is the stored value), so the enum is the single
     * source of truth for a field's valid values -- the schema constraint here, the edge validation the
     * validator runs from it, and the code's `when` all follow the same "enum" e.g., `options(ClockOp.entries)`.
     */
    fun options(values: Iterable<Enum<*>>) {
        for (v in values) option(v.name)
    }

    @KdrPrivate
    fun optionsList(): MutableList<Any?> =
        data.getOrPut(SCH.options) { ArrayList<Any?>() }!!.toT()

    /**
     * Declares what a failure against this field should say, in place of the validator's own wording
     * (issue #202):
     *
     * ```
     * property("score", "Numeric score.", required = true) {
     *     type = SCT.integer
     *     errors {
     *         missingRequired("Please enter a score.")
     *         default("That score does not look right.")
     *     }
     * }
     * ```
     *
     * The block offers one function per failure code, so a code that does not exist cannot be written — which
     * is why the parser's check on the same keys only has to catch documents that were not built here.
     */
    fun errors(build: SchErrors.() -> Unit) {
        SchErrors(data.getOrPut(SCH.errors) { LinkedHashMap<String, Any?>() }!!.toT()).apply(build)
    }

    /**
     * Adds a property (field) subschema. A [description] is MANDATORY for fields
     * (unlike a type's description, which is optional). The field's type defaults
     * to `string` unless [build] sets a `type` or makes it a `$ref`. When
     * [required] is true the property name is also added to this type's `required`
     * array (required is tracked on the side).
     */
    fun property(
        name: String,
        description: String,
        required: Boolean = false,
        build: SchTypeBuilder.() -> Unit = {},
    ) {
        val sub = SchTypeBuilder(cxt, namespace)
        sub.description = description
        sub.apply(build)
        // Default field type to string unless the build set a type or a $ref.
        if (SCH.type !in sub.data && SCH.dRef !in sub.data) {
            sub.type = SCT.string
        }
        propertiesMap()[name] = sub.data
        if (required) this.required(name)
    }

    /**
     * Adds a previously declared, reusable [property] (see [schemaProperty]). The
     * property's schema is deep-cloned, so the original is untouched, then [mutate]
     * can adjust the clone for this use (e.g., refine its description or
     * constraints). When [required] is true its name is added to `required`.
     */
    fun property(property: SchBuilderProperty, required: Boolean = false, mutate: SchTypeBuilder.() -> Unit = {}) {
        val sub = SchTypeBuilder(cxt, namespace, property.data.deepClone())
        sub.apply(mutate)
        propertiesMap()[property.name] = sub.data
        if (required) this.required(property.name)
    }

    // Conceptually private helper; left open per the code guide and marked rather
    // than hidden.
    @KdrPrivate
    fun propertiesMap(): MutableMap<String, Any?> =
        data.getOrPut(SCH.properties) { LinkedHashMap<String, Any?>() }!!.toT()

    /** Records one or more property names in this type's `required` array. */
    fun required(vararg names: String) {
        val req: MutableList<String> = data.getOrPut(SCH.required) { ArrayList<String>() }!!.toT()
        for (n in names) if (n !in req) req.add(n)
    }

    /** Defines the element schema for an array type (`items`). */
    fun items(build: SchTypeBuilder.() -> Unit) {
        data[SCH.items] = SchTypeBuilder(cxt, namespace).apply(build).data
    }

    /** Marks this schema as a day-only date string (JSON Schema `format: "date"`, e.g. `2021-06-01`). */
    fun dayOnlyDate() {
        type = SCT.string
        format = SFMT.date
    }

    /** Marks this schema as a full date-time string (JSON Schema `format: "date-time"`). */
    fun dateTime() {
        type = SCT.string
        format = SFMT.dateTime
    }

    /**
     * Marks this schema as **file content**: OpenAPI's `{"type": "string", "format": "binary"}`.
     *
     * On an endpoint's input field it declares an upload — the request arrives as `multipart/form-data` and
     * the field's value is a `ContentData`. On an endpoint's output it declares that the response body *is*
     * the file rather than a JSON envelope. See [SFMT.binary] for why a file is spelled as a string.
     */
    fun binaryContent() {
        type = SCT.string
        format = SFMT.binary
    }
}
