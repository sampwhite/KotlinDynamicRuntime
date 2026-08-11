package com.dynamicruntime.common.schema


/**
 * A conditional-presence rule: JSON Schema's `if` / `then` / `else`, narrowed to the one shape the entity
 * model actually needs — *this property is required (or forbidden) depending on what that property holds*
 * (issue #253).
 *
 * The everyday case is the commonest shape a trait's data takes:
 *
 * ```json
 * { "hasValue": true, "value": "approve" }
 * ```
 *
 * with `value` permitted only when `hasValue` is true. A [SchVariants] discriminator cannot express it — that
 * selects between *named branches* on a *string* property — so this is a second mechanism rather than a
 * generalization of the first. The two share only their consequence for a form, which must redraw when the
 * watched field changes.
 *
 * **Only this shape is supported, and anything else is refused at parse time** rather than partly honored.
 * See [parseCondition] for what that costs and why it is still the right trade.
 *
 * **One condition per type.** JSON Schema allows a single `if`/`then`/`else` per schema object; expressing two
 * independent conditions needs `allOf`, which this layer does not read. A type wanting two conditional fields
 * is therefore not expressible yet — deliberately noted rather than worked around, since the workaround would
 * be a private construct nobody else understands.
 */
class SchCondition(
    /** The property whose value decides. */
    val property: String,
    /** The value [property] must hold for the `then` side to apply. */
    val value: Any?,
    /** Properties required when the condition holds. */
    val thenRequired: Set<String>,
    /** Properties forbidden when the condition holds. */
    val thenForbidden: Set<String>,
    /** Properties required when it does not. */
    val elseRequired: Set<String>,
    /** Properties forbidden when it does not. */
    val elseForbidden: Set<String>,
) {
    /**
     * Whether the condition holds for [data]: [property] is present **and** equal to [value].
     *
     * Presence is half the test, and the important half. JSON Schema's `if` evaluates a subschema, and a bare
     * `properties` check passes *vacuously* when the property is absent — so an omitted `hasValue` would
     * satisfy `{"hasValue": {"const": true}}` and demand the dependent field from a payload that said nothing.
     * Requiring presence here is what the emitted `if` also says with its own `required`, so the two agree and
     * a stock validator reaches the same answer.
     *
     * Equality is [constMatches], the same rule `const` uses: exact, else equal string form, so a value that
     * arrived as `"true"` from a query string is the same answer as `true` to the question being asked.
     *
     * Read from the payload as it arrived rather than as it coerced, so validating and coercing agree — the
     * coerced map does not exist in validate-only mode, and a rule that answered differently between the two
     * would be a rule nobody could reason about.
     */
    fun holds(data: Map<*, *>): Boolean =
        data.containsKey(property) && constMatches(value, data[property])

    /** The properties that must be present, given whether the condition [holds]. */
    fun requiredWhen(holds: Boolean): Set<String> = if (holds) thenRequired else elseRequired

    /** The properties that must be absent, given whether the condition [holds]. */
    fun forbiddenWhen(holds: Boolean): Set<String> = if (holds) thenForbidden else elseForbidden

    /** Every property this rule governs, either way — what a form has to redraw when [property] changes. */
    val governed: Set<String> get() = thenRequired + thenForbidden + elseRequired + elseForbidden
}
