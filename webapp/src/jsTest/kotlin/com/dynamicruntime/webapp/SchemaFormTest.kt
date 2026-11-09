package com.dynamicruntime.webapp

import com.dynamicruntime.common.home.HFRAG
import com.dynamicruntime.common.schema.SCH
import com.dynamicruntime.common.schema.SCT
import com.dynamicruntime.common.schema.SFMT
import com.dynamicruntime.common.schema.SchFailCode
import com.dynamicruntime.common.schema.SchType
import com.dynamicruntime.common.schema.parseSchemaTypes
import com.dynamicruntime.common.schema.validate
import com.dynamicruntime.common.util.humanizeFieldName
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

// The schema form's pure rules (issue #1740 gathered these into one file): which control a field draws, a boolean's
// control, what a branch switch keeps, a field's title, the input check, derived fields and cfact gates, the free-form
// and map fields, keyed elements, and a field's bounds. Each class is the file it used to be, kept whole with its own
// helpers.

/**
 * Pure-logic coverage (issue #781) for [controlKind] — which input control a field draws from its schema. This
 * is the form's single most consequential presentation rule, and it was previously exercised only by driving a
 * browser; here it is schema in, [ControlKind] out. The types are built by parsing real schema through the
 * kernel's own [parseSchemaTypes], so what is under test is the schema a backend actually writes.
 */
class ControlKindTest {

    /** The value-type of field `f`, built from [fieldSchema], with the parent marking it [required] or not. */
    private fun fieldType(fieldSchema: Map<String, Any?>, required: Boolean = false): SchType {
        val schema = mapOf(
            SCH.type to SCT.kObject,
            SCH.properties to mapOf("f" to (mapOf<String, Any?>(SCH.description to "A field") + fieldSchema)),
            SCH.required to if (required) listOf("f") else emptyList(),
        )
        return parseSchemaTypes(mapOf("t.T" to schema)).getValue("t.T").properties.getValue("f").valueType
    }

    private fun kindOf(fieldSchema: Map<String, Any?>, required: Boolean = false, editable: Boolean = true) =
        controlKind(fieldType(fieldSchema, required), required, editable)

    private val opts = listOf(mapOf(SCH.value to "a", SCH.label to "A"), mapOf(SCH.value to "b", SCH.label to "B"))
    private fun string(extra: Map<String, Any?> = emptyMap()) = mapOf<String, Any?>(SCH.type to SCT.string) + extra
    private fun array(items: Map<String, Any?>) = mapOf<String, Any?>(SCH.type to SCT.array, SCH.items to items)

    @Test
    fun notEditableIsReadOnlyWhateverTheType() {
        // A field that would otherwise be a Choice, a checkbox, or a date is ReadOnly when not editable --
        // editability is checked before the type, so the read-only view never draws a live control.
        assertEquals(ControlKind.ReadOnly, kindOf(string(mapOf(SCH.options to opts)), editable = false))
        assertEquals(ControlKind.ReadOnly, kindOf(mapOf(SCH.type to SCT.boolean), required = true, editable = false))
        assertEquals(ControlKind.ReadOnly, kindOf(string(mapOf(SCH.format to SFMT.date)), editable = false))
    }

    @Test
    fun aReadOnlyValueOnAFormPeopleFillInReadsAsWords() {
        // Issue #1438: a yes/no answer as Yes or No, a choice by its label, each of a list too; a value the schema does
        // not list, and anything else, as it is.
        val choice = fieldType(string(mapOf(SCH.options to opts)))
        assertEquals("Yes", friendlyValueText(fieldType(mapOf(SCH.type to SCT.boolean)), true))
        assertEquals("No", friendlyValueText(fieldType(mapOf(SCH.type to SCT.boolean)), false))
        assertEquals("A", friendlyValueText(choice, "a"))
        assertEquals("c", friendlyValueText(choice, "c"))
        assertEquals("A, B", friendlyValueText(fieldType(array(string(mapOf(SCH.options to opts)))), listOf("a", "b")))
        assertEquals("12", friendlyValueText(fieldType(mapOf(SCH.type to SCT.integer)), 12))
        assertEquals("", friendlyValueText(choice, null))
    }

    @Test
    fun multiSelectFromAnArrayOfChoices() {
        assertEquals(ControlKind.MultiSelect, kindOf(array(string(mapOf(SCH.options to opts)))))
        // An open element type accepts a value not offered -> tags mode.
        assertEquals(
            ControlKind.MultiSelectOpen,
            kindOf(array(string(mapOf(SCH.options to opts, SCH.openOptions to true)))),
        )
        // An array without item options is not a multi-select (it is a growing column elsewhere) -> falls to Text.
        assertEquals(ControlKind.Text, kindOf(array(string())))
    }

    @Test
    fun singleChoiceClosedAndOpen() {
        assertEquals(ControlKind.Choice, kindOf(string(mapOf(SCH.options to opts))))
        assertEquals(ControlKind.OpenChoice, kindOf(string(mapOf(SCH.options to opts, SCH.openOptions to true))))
    }

    @Test
    fun booleanIsCheckboxWhenTwoStateElseTristateSelect() {
        // Required (or with a default) -> two reachable states -> checkbox; a plain optional boolean keeps a
        // reachable "absent" third state, which a checkbox cannot express -> the tri-state Select.
        assertEquals(ControlKind.Checkbox, kindOf(mapOf(SCH.type to SCT.boolean), required = true))
        assertEquals(ControlKind.Checkbox, kindOf(mapOf(SCH.type to SCT.boolean, SCH.default to false)))
        assertEquals(ControlKind.TristateBoolean, kindOf(mapOf(SCH.type to SCT.boolean)))
    }

    @Test
    fun dateFormatsFileAndJsonMap() {
        assertEquals(ControlKind.Date, kindOf(string(mapOf(SCH.format to SFMT.date))))
        assertEquals(ControlKind.DateTime, kindOf(string(mapOf(SCH.format to SFMT.dateTime))))
        assertEquals(ControlKind.File, kindOf(string(mapOf(SCH.format to SFMT.binary))))
        // A property-less object is a free-form map editor, not a text box.
        assertEquals(ControlKind.JsonMap, kindOf(mapOf(SCH.type to SCT.kObject)))
    }

    @Test
    fun plainScalarsAreText() {
        assertEquals(ControlKind.Text, kindOf(string()))
        assertEquals(ControlKind.Text, kindOf(mapOf(SCH.type to SCT.integer)))
        assertEquals(ControlKind.Text, kindOf(mapOf(SCH.type to SCT.number)))
    }

    @Test
    fun longTextIsTheSchemasWordOnPlainText() {
        // Issue #1550: a string the schema marks `g-longText` is a text area on every form, the endpoint catalog's too,
        // since it reads the schema and not a layout. Unmarked text is one line, and a read-only field is its value.
        val long = string(mapOf(SCH.longText to true))
        assertEquals(ControlKind.LongText, kindOf(long))
        assertEquals(ControlKind.Text, kindOf(string()))
        assertEquals(ControlKind.ReadOnly, kindOf(long, editable = false))
    }

    @Test
    fun aTextAreaFitsItsTextBetweenItsBounds() {
        assertEquals(3, fittedRows("", 3, 12))
        assertEquals(5, fittedRows("a\nb\nc\nd\ne", 3, 12))
        assertEquals(12, fittedRows("x\n".repeat(40), 3, 12))
        // A paragraph typed without a break counts the lines it wraps to: 200 characters at 80 a line is three.
        assertEquals(4, fittedRows("a".repeat(200) + "\nb", 3, 12))
        // A layout's lines above the fit's cap still start that tall.
        assertEquals(20, fittedRows("", 20, maxOf(20, 12)))
    }

    @Test
    fun optionsWinOverTheBaseType() {
        // The ordering that matters: a field carrying a choice list is a choice widget, not the text box its base
        // type would get. A list is allowed only on a plain string or an untyped field -- the parser refuses one on
        // a boolean or an integer (issue #815), where it would have rejected every correctly typed value.
        assertEquals(ControlKind.Choice, kindOf(mapOf(SCH.type to SCT.string, SCH.options to opts), required = true))
        assertEquals(ControlKind.Choice, kindOf(mapOf(SCH.options to opts)))
    }

    // --- typeWord: the read-only outline's "type in words", which shares controlKind's choice reading ---

    private fun wordOf(fieldSchema: Map<String, Any?>) = typeWord(fieldType(fieldSchema))

    @Test
    fun typeWordDistinguishesOpenFromClosedChoices() {
        // The distinction the outline must not lose: an open list is "not the whole of what is allowed", so its
        // word says so -- the same reading controlKind turns into OpenChoice vs Choice.
        assertEquals("choice", wordOf(string(mapOf(SCH.options to opts))))
        assertEquals("open choice", wordOf(string(mapOf(SCH.options to opts, SCH.openOptions to true))))
        assertEquals("choices", wordOf(array(string(mapOf(SCH.options to opts)))))
        assertEquals("open choices", wordOf(array(string(mapOf(SCH.options to opts, SCH.openOptions to true)))))
        assertEquals("list", wordOf(array(string())))
        assertEquals("file", wordOf(string(mapOf(SCH.format to SFMT.binary))))
        assertEquals(SFMT.date, wordOf(string(mapOf(SCH.format to SFMT.date))))
        assertEquals("boolean", wordOf(mapOf(SCH.type to SCT.boolean)))
        assertEquals("string", wordOf(string()))
    }
}

/**
 * Pure-logic coverage (issue #161) for the two decisions the endpoint form makes about a boolean field:
 * [booleanIsTwoState], which control it gets (issue #261), and [checkboxDraw], what that control shows for the
 * value currently held (issue #322). Schema in, verdict out: no React, no DOM, no server.
 *
 * The types are built by parsing real schema through the kernel's own [parseSchemaTypes] rather than by
 * constructing a [SchType] by hand, so what is under test is the schema a backend actually writes — including
 * the two things the rule reads, `default` and the parent's `required` list, which sit in different places.
 */
class BooleanWidgetTest {

    /** A one-field object type: `flag`, boolean, with whatever [attrs] and required-ness the case needs. */
    private fun flagType(attrs: Map<String, Any?> = emptyMap(), required: Boolean = false): SchType {
        val schema = mapOf(
            SCH.type to SCT.kObject,
            SCH.properties to mapOf("flag" to (mapOf(SCH.type to SCT.boolean, SCH.description to "A flag") + attrs)),
            SCH.required to if (required) listOf("flag") else emptyList(),
        )
        return parseSchemaTypes(mapOf("t.Flags" to schema)).getValue("t.Flags")
    }

    /** [booleanIsTwoState] as the form asks it: the field's type, plus its parent's statement of required. */
    private fun twoState(attrs: Map<String, Any?> = emptyMap(), required: Boolean = false): Boolean {
        val type = flagType(attrs, required)
        return booleanIsTwoState(type.properties.getValue("flag").valueType, "flag" in type.required)
    }

    /**
     * The case the issue is about: a plain optional boolean has three states, because `emptyIsAbsent` keeps
     * absent apart from `false`. A checkbox cannot say which, so this one gets the Select.
     */
    @Test
    fun aPlainOptionalBooleanNeedsThreeStates() {
        assertEquals(false, twoState())
    }

    /** Absent is invalid, so `true` and `false` are the only outcomes: a checkbox says everything there is. */
    @Test
    fun aRequiredBooleanIsTwoState() {
        assertEquals(true, twoState(required = true))
    }

    /** A default is what absent means, so absent is not an outcome of its own — either way round. */
    @Test
    fun aDefaultCollapsesTheThirdState() {
        assertEquals(true, twoState(mapOf(SCH.default to false)))
        assertEquals(true, twoState(mapOf(SCH.default to true)))
    }

    /** Required *and* defaulted is the injection case the validator handles; still two states. */
    @Test
    fun requiredWithADefaultIsTwoState() {
        assertEquals(true, twoState(mapOf(SCH.default to false), required = true))
    }

    /**
     * `default: null` is not a default — the parser drops it (an absent default and a null one are the same
     * statement) — so the field keeps its third state rather than quietly losing the way back to absent.
     */
    @Test
    fun anExplicitNullDefaultIsNotADefault() {
        assertEquals(false, twoState(mapOf(SCH.default to null)))
    }

    // --- what the checkbox draws for the value it is holding (issue #322) -----------------------------

    /** [checkboxDraw] as the widget asks it: the field's own type, and whatever the form currently holds. */
    private fun draw(value: Any?, attrs: Map<String, Any?> = emptyMap(), required: Boolean = false): CheckDraw =
        checkboxDraw(flagType(attrs, required).properties.getValue("flag").valueType, value)

    /**
     * The bug: a required boolean nobody has answered yet drew as an unticked box, which is what `false` looks
     * like — so the field read as decided while the payload said nothing, and a conditional watching it never
     * fired. It now draws as unanswered instead.
     */
    @Test
    fun aRequiredBooleanWithNoAnswerYetIsUnanswered() {
        assertEquals(CheckDraw.unanswered, draw(null, required = true))
    }

    /** Once answered it draws the answer, including the `false` that used to be indistinguishable from absent. */
    @Test
    fun anAnsweredBooleanDrawsItsAnswer() {
        assertEquals(CheckDraw.on, draw(true, required = true))
        assertEquals(CheckDraw.off, draw(false, required = true))
    }

    /**
     * A default is what absent means, so there is nothing unanswered about it — and it draws the default
     * either way round, since a `default: true` field drawn unticked would report the opposite of what it
     * sends. This is the case #261 got right and #322 leaves alone.
     */
    @Test
    fun absentDrawsTheDefaultWhenThereIsOne() {
        assertEquals(CheckDraw.off, draw(null, mapOf(SCH.default to false)))
        assertEquals(CheckDraw.on, draw(null, mapOf(SCH.default to true)))
        // An answer beats the default rather than the other way about.
        assertEquals(CheckDraw.off, draw(false, mapOf(SCH.default to true)))
    }

    /**
     * A non-boolean can reach the field through the request-JSON panel, and it is *present* — so it draws off
     * and collects the validator's `wrongType`, rather than being shown as an empty field the user never
     * filled in. (`allowCoerce` is off for booleans, so `"true"` is a type failure, not a `true`.)
     */
    @Test
    fun aPresentButWrongValueIsNotTreatedAsUnanswered() {
        assertEquals(CheckDraw.off, draw("true", required = true))
        assertEquals(CheckDraw.off, draw(0, required = true))
    }
}

/**
 * Pure-logic coverage (issue #161) for what [SchemaForm] keeps when a discriminated union switches branch
 * ([valuesAfterBranchSwitch] / [fieldCarriesAcrossBranches], issue #417).
 *
 * The report this fixes: editing a form, a section switched from one trait to another kept the old trait's
 * `data` sub-object — a field named `data` on both branches but a *different shape* on each — so the payload
 * carried the old trait's keys against a new branch that forbids them, and the errors ("Additional property
 * 'topic' is not allowed") named fields the form was no longer even drawing. Same-named same-typed envelope
 * fields (a verb, an id) must still carry across, so switching does not blank everything beside the choice.
 *
 * Generic on purpose — this is a display-engine rule, not a gedra one — but shaped like the edit union that
 * exposed it: two branches, shared scalar envelope fields, and a branch-specific `data` object.
 */
class VariantSwitchTest {

    /** A branch-specific data object: [name] the type, [props] its fields. */
    private fun dataType(props: Map<String, Any?>): Map<String, Any?> = mapOf(
        SCH.type to SCT.kObject,
        SCH.properties to props,
    )

    /** One union branch: its discriminator const [kind], the shared scalar envelope, and a `data` of [dataRef]. */
    private fun branch(kind: String, dataRef: String): Map<String, Any?> = mapOf(
        SCH.type to SCT.kObject,
        SCH.properties to mapOf(
            "kind" to mapOf(SCH.type to SCT.string, SCH.const to kind),
            "action" to mapOf(SCH.type to SCT.string),
            "entryId" to mapOf(SCH.type to SCT.string),
            "data" to mapOf(SCH.dRef to dataRef),
        ),
    )

    /**
     * A union of two branches whose `data` shapes differ: `alpha` carries `{topic, explanation}`, `beta` carries
     * `{year}`. Pass [betaData] to point beta's `data` at a different type name to make the two shapes agree.
     */
    private fun unionType(betaData: String = "t.BetaData"): SchType {
        val defs = mapOf(
            "t.AlphaData" to dataType(
                mapOf("topic" to mapOf(SCH.type to SCT.string), "explanation" to mapOf(SCH.type to SCT.string)),
            ),
            "t.BetaData" to dataType(mapOf("year" to mapOf(SCH.type to SCT.integer))),
            "t.Alpha" to branch("alpha", "t.AlphaData"),
            "t.Beta" to branch("beta", betaData),
            "t.Edit" to mapOf(
                SCH.oneOf to listOf(mapOf(SCH.dRef to "t.Alpha"), mapOf(SCH.dRef to "t.Beta")),
                SCH.discriminator to mapOf(SCH.propertyName to "kind"),
            ),
        )
        return parseSchemaTypes(defs).getValue("t.Edit")
    }

    /**
     * The reported bug: switching branch drops the old branch's `data`, whose keys the new branch would reject,
     * while the shared scalar envelope (`action`, `entryId`) carries across so the switch is not a full reset.
     */
    @Test
    fun switchingBranchDropsTheOldBranchSpecificData() {
        val variants = unionType().variants!!
        val from = variants.select("alpha")
        val to = variants.select("beta")
        val values = mapOf(
            "kind" to "alpha",
            "action" to "addOrReplace",
            "entryId" to "e1",
            "data" to mapOf("topic" to "expenses Q1", "explanation" to "misc"),
        )

        val result = valuesAfterBranchSwitch(values, "kind", from, to, "beta")

        assertEquals("beta", result["kind"])
        assertEquals("addOrReplace", result["action"])
        assertEquals("e1", result["entryId"])
        // The old trait's data would fail beta's closed schema and is not on screen to fix -- so it is dropped.
        assertFalse(result.containsKey("data"), "stale branch-specific data must not carry across")
    }

    /** A same-named field of the *same* type does carry across, so a shared sub-object is not needlessly reset. */
    @Test
    fun switchingBranchKeepsAStructuredFieldOfTheSameType() {
        // Both branches' `data` point at t.AlphaData, so the shape agrees and the value is safe to keep.
        val variants = unionType(betaData = "t.AlphaData").variants!!
        val from = variants.select("alpha")
        val to = variants.select("beta")
        val values = mapOf("kind" to "alpha", "data" to mapOf("topic" to "kept"))

        val result = valuesAfterBranchSwitch(values, "kind", from, to, "beta")

        assertEquals(mapOf("topic" to "kept"), result["data"])
    }

    /** A choice that names no branch (or a cleared one) keeps nothing but the discriminator it just set. */
    @Test
    fun clearingTheChoiceKeepsOnlyTheDiscriminator() {
        val variants = unionType().variants!!
        val from = variants.select("alpha")
        val values = mapOf("kind" to "alpha", "action" to "addOrReplace", "data" to mapOf("topic" to "x"))

        val result = valuesAfterBranchSwitch(values, "kind", from, to = null, picked = null)

        assertEquals(setOf("kind"), result.keys)
        assertEquals(null, result["kind"])
    }

    /**
     * With no branch selected before the switch (from == null) there is no old shape to compare against, so the
     * fields the *new* branch declares are kept rather than all dropped. This is the case that reaches the form
     * when values are applied with a missing/unknown discriminator (e.g. the catalog's "Apply to form") and a
     * branch is then chosen: the type-by-type rule alone would silently discard everything the branch accepts.
     */
    @Test
    fun withNoOldBranchTheNewBranchsFieldsAreKept() {
        val variants = unionType().variants!!
        val to = variants.select("beta")
        // Fields carried in without a prior branch: `year` belongs to beta's data (kept via the plain rule),
        // while `topic` belongs to no beta field and is dropped.
        val values = mapOf("data" to mapOf("year" to 2025), "stray" to "x")

        val result = valuesAfterBranchSwitch(values, "kind", from = null, to = to, picked = "beta")

        assertEquals("beta", result["kind"])
        assertEquals(mapOf("year" to 2025), result["data"], "a field the new branch declares is kept")
        assertEquals(false, result.containsKey("stray"), "a field the new branch does not declare is dropped")
    }

    /** [fieldCarriesAcrossBranches] directly: a scalar carries on matching JSON type; two data shapes do not. */
    @Test
    fun theCarryRuleSeparatesScalarsFromDifferingObjects() {
        val variants = unionType().variants!!
        val alpha = variants.select("alpha")!!
        val beta = variants.select("beta")!!

        val alphaAction = alpha.properties.getValue("action").valueType
        val betaAction = beta.properties.getValue("action").valueType
        assertTrue(fieldCarriesAcrossBranches(alphaAction, betaAction), "shared scalars carry across")

        val alphaData = alpha.properties.getValue("data").valueType
        val betaData = beta.properties.getValue("data").valueType
        assertFalse(fieldCarriesAcrossBranches(alphaData, betaData), "differently shaped objects do not")
    }
}

/**
 * Pure-logic coverage for where a friendly field label's `title` comes from (issue #408).
 *
 * The case worth protecting is the last: a `$ref` field's `valueType` is the **shared** target instance, so a
 * title read from there would name every field referencing that type alike. `SchProperty.title` keeps a
 * field's label its own.
 */
class FieldTitleTest {

    /** Parses one document and returns the named type. */
    private fun typeOf(defs: Map<String, Any?>, name: String): SchType = parseSchemaTypes(defs).getValue(name)

    /** An inline field's title lands on the property, so it labels that field. */
    @Test
    fun anInlineFieldKeepsItsOwnTitle() {
        val defs = mapOf(
            "t.Form" to mapOf(
                SCH.type to SCT.kObject,
                SCH.properties to mapOf(
                    "perItemAmount" to mapOf(SCH.type to SCT.number, SCH.title to "Amount per item"),
                ),
            ),
        )
        assertEquals("Amount per item", typeOf(defs, "t.Form").properties.getValue("perItemAmount").title)
    }

    /** A field that declares no title has none; the form humanizes its key instead. */
    @Test
    fun anUntitledFieldHasNoTitle() {
        val defs = mapOf(
            "t.Form" to mapOf(
                SCH.type to SCT.kObject,
                SCH.properties to mapOf("perItemAmount" to mapOf(SCH.type to SCT.number)),
            ),
        )
        assertNull(typeOf(defs, "t.Form").properties.getValue("perItemAmount").title)
        assertEquals("Per item amount", humanizeFieldName("perItemAmount"))
    }

    /**
     * Two `$ref` fields over one titled type keep their own identities: neither inherits "Address", and a
     * title written beside a `$ref` belongs to that field alone.
     */
    @Test
    fun refFieldsDoNotInheritTheTargetTypeTitle() {
        val defs = mapOf(
            "t.Address" to mapOf(
                SCH.type to SCT.kObject,
                SCH.title to "Address",
                SCH.properties to mapOf("postcode" to mapOf(SCH.type to SCT.string)),
            ),
            "t.Contact" to mapOf(
                SCH.type to SCT.kObject,
                SCH.properties to mapOf(
                    "homeAddress" to mapOf(SCH.dRef to "t.Address"),
                    // The same target, this one labeled for itself.
                    "workAddress" to mapOf(SCH.dRef to "t.Address", SCH.title to "Work address"),
                ),
            ),
        )
        val contact = typeOf(defs, "t.Contact")
        // The shared target does carry the title -- it is the type's own, and the union's branch labels read it.
        assertEquals("Address", contact.properties.getValue("homeAddress").valueType.title)
        // ...but neither field takes it as its label.
        assertNull(contact.properties.getValue("homeAddress").title)
        assertEquals("Work address", contact.properties.getValue("workAddress").title)
        // So the two rows stay distinguishable, which is the whole point.
        assertEquals("Home address", humanizeFieldName("homeAddress"))
    }
}

/**
 * Pure-logic coverage (issue #161) for [humanizeFieldName], the friendly-form fallback that turns a wire key
 * into a readable label when a field declares no `title` (issue #408).
 */
class HumanizeFieldNameTest {

    @Test
    fun camelCaseBecomesASentence() {
        assertEquals("Expense report", humanizeFieldName("expenseReport"))
        assertEquals("Per item amount", humanizeFieldName("perItemAmount"))
        assertEquals("Site visit", humanizeFieldName("siteVisit"))
    }

    @Test
    fun aSingleWordIsJustCapitalized() {
        assertEquals("Year", humanizeFieldName("year"))
        assertEquals("Notes", humanizeFieldName("notes"))
    }

    @Test
    fun aTrailingCapitalStartsANewWord() {
        // The case that keeps "id" from reading as part of the word before it.
        assertEquals("Trait id", humanizeFieldName("traitId"))
    }

    @Test
    fun emptyStaysEmpty() {
        assertEquals("", humanizeFieldName(""))
    }
}

/**
 * Pure-logic coverage (issue #161) for [checkInput], the shared coerce-and-validate the endpoint catalog and
 * the new-form page hold their input to (issue #408): a clean check hands back a payload to send, a failing
 * one withholds it so nothing invalid reaches the wire.
 */
class SchemaRunTest {

    /** A one-field object type: `title`, string, required so absence is a failure. */
    private fun titleType(): SchType {
        val schema = mapOf(
            SCH.type to SCT.kObject,
            SCH.properties to mapOf("title" to mapOf(SCH.type to SCT.string, SCH.description to "A title")),
            SCH.required to listOf("title"),
        )
        return parseSchemaTypes(mapOf("t.Form" to schema)).getValue("t.Form")
    }

    @Test
    fun validInputProducesAPayload() {
        val check = checkInput(titleType(), mapOf("title" to "Expenses"))
        assertTrue(check.isValid)
        assertEquals(mapOf("title" to "Expenses"), check.payload)
    }

    @Test
    fun invalidInputWithholdsThePayload() {
        val check = checkInput(titleType(), emptyMap())
        assertTrue(check.failures.isNotEmpty())
        assertNull(check.payload)
    }
}

/**
 * Pure-logic coverage for the content/envelope line the read-only form views draw (issue #712):
 * [FormOpts.revealsDerivedAt] decides whether a `derived` field is shown read-only (a trait's computed value,
 * like an expense total) or stays hidden (a system stamp, like a gedra id or an entry's audit fields). The
 * render that then draws a revealed field is browser-driven; this pins the decision it rests on.
 */
class DerivedRevealTest {
    // The core details workflow View Info: the whole form root is one trait's data type, so every derived-with-value
    // field is content.
    private val coreDetailsOpts = FormOpts(showDerivedValues = true, derivedRootIsTraitData = true)

    // The raw read-only view over a whole document: trait content lives under a `data` payload; everything
    // outside it (the document id, an entry's stamps) is envelope.
    private val rawOpts = FormOpts(showDerivedValues = true, traitDataField = "data")

    @Test
    fun coreDetailsRootRevealsADerivedContentValue() {
        // Root of a trait data form, a non-blank derived value, read-only.
        assertTrue(coreDetailsOpts.revealsDerivedAt("totalAmount", editable = false, value = 50))
    }

    @Test
    fun aBlankDerivedValueStaysHidden() {
        assertFalse(coreDetailsOpts.revealsDerivedAt("totalAmount", editable = false, value = null))
        assertFalse(coreDetailsOpts.revealsDerivedAt("totalAmount", editable = false, value = ""))
    }

    @Test
    fun anEditableFormNeverReveals() {
        // No control to draw for a derived field, so editing hides it regardless of value.
        assertFalse(coreDetailsOpts.revealsDerivedAt("totalAmount", editable = true, value = 50))
    }

    @Test
    fun optingOutRevealsNothing() {
        // showDerivedValues off, or no boundary set: the master switch and a boundary are both required.
        assertFalse(FormOpts().revealsDerivedAt("totalAmount", editable = false, value = 50))
        assertFalse(FormOpts(showDerivedValues = true).revealsDerivedAt("entries[0].data.totalAmount", editable = false, value = 50))
    }

    @Test
    fun rawViewRevealsContentUnderTheDataBoundary() {
        // A derived value inside a trait's data payload is content and shows.
        assertTrue(rawOpts.revealsDerivedAt("entries[0].data.totalAmount", editable = false, value = 50))
    }

    @Test
    fun rawViewHidesEnvelopeOutsideTheDataBoundary() {
        // The document id at the root, and an entry's audit stamps beside (not under) its data, are envelope.
        assertFalse(rawOpts.revealsDerivedAt("gedraId", editable = false, value = "gd.fd.acme.x"))
        assertFalse(rawOpts.revealsDerivedAt("entries[0].entryId", editable = false, value = "e-1"))
        assertFalse(rawOpts.revealsDerivedAt("entries[0].createdAt", editable = false, value = "2026-01-01T00:00:00Z"))
    }

    @Test
    fun rawViewRevealsContentNestedBelowData() {
        // A derived value deeper inside the data payload (a computed field on a nested object) is still content.
        assertTrue(rawOpts.revealsDerivedAt("entries[0].data.summary.total", editable = false, value = 7))
    }
}

/**
 * `buildCfactGate` (issue #564): the frontend `g-visibleWhen` evaluator over the delivered cfacts. Pure -- a
 * `{name -> present}` map in, a predicate out -- reusing the kernel `CFactParser`, so no React and no DOM.
 */
class CfactGateTest {

    // The delivered vocabulary: every frontend cfact, present or not. hasAdminLevel present, hasEnvAuth not.
    private val cfacts = mapOf("kdr:hasAdminLevel" to true, "kdr:hasEnvAuth" to false)
    private val gate = buildCfactGate(cfacts)

    @Test
    fun aPresentCfactShowsTheFieldAndAnAbsentOneHidesIt() {
        assertTrue(gate("kdr:hasAdminLevel"))
        assertFalse(gate("kdr:hasEnvAuth"))
    }

    @Test
    fun negationReadsAnAbsentCfactAsTrue() {
        // The case a present-only list could not express: the name is in the vocabulary as absent, so `~` of it
        // is true rather than a parse error on an unknown name.
        assertTrue(gate("~kdr:hasEnvAuth"))
        assertFalse(gate("~kdr:hasAdminLevel"))
    }

    @Test
    fun compoundExpressionsCombineTheFacts() {
        assertFalse(gate("kdr:hasAdminLevel,kdr:hasEnvAuth")) // and: hasEnvAuth is absent
        assertTrue(gate("kdr:hasAdminLevel|kdr:hasEnvAuth")) // or: hasAdminLevel is present
    }

    @Test
    fun noCfactsMeansNoGating() {
        val open = buildCfactGate(null)
        assertTrue(open("kdr:hasAdminLevel"))
        assertTrue(open("~anything"))
    }

    @Test
    fun aGateNamingAnUndeliveredCfactFailsOpen() {
        // Not in the delivered vocabulary at all: the parser rejects the name, but visibility is presentation
        // only and the backend enforces, so the field shows rather than vanishing on a technicality.
        assertTrue(gate("somethingNeverDelivered"))
    }
}

/**
 * The free-form map field's two pure halves (issue #251): what text it shows for a value, and what a value it
 * accepts from text. The surrounding component is a textarea and one piece of state, so these two functions
 * are where all the behavior worth pinning down actually lives.
 */
class JsonFieldTest {

    @Test
    fun showsAMapAsPrettyJson() {
        val text = jsonFieldText(mapOf("a" to 1, "b" to "two"))
        assertTrue(text.contains("\"a\""), "should render the keys: $text")
        assertTrue(text.contains("two"), "should render the values: $text")
    }

    @Test
    fun showsNothingForAnAbsentValue() {
        assertEquals("", jsonFieldText(null))
    }

    // The single most important property of the editor: a String is text someone is part-way through typing,
    // so it comes back exactly as it went in. Reformatting it -- even into JSON that means the same thing --
    // moves the caret out from under whoever is typing.
    @Test
    fun passesTextThroughWithoutReformattingIt() {
        for (typed in listOf("{", "{\"a\":1}", "{ \"a\" :   1 }", "not json at all")) {
            assertEquals(typed, jsonFieldText(typed), "text must survive a round trip untouched")
        }
    }

    @Test
    fun acceptsAJsonObject() {
        val parsed = parseJsonField("""{"channel":"excel","fileName":"x.xlsx"}""")
        assertNull(parsed.error)
        val map = assertNotNull(parsed.value as? Map<*, *>)
        assertEquals("excel", map["channel"])
    }

    // Diverges from the request panel deliberately, which reads blank as an empty payload: clearing a *field*
    // is how someone removes an optional value, and `{}` stays available to anyone who means it.
    @Test
    fun blankMeansAbsentRatherThanAnEmptyMap() {
        for (text in listOf("", "   ", "\n")) {
            val parsed = parseJsonField(text)
            assertNull(parsed.error, "clearing a field is not a mistake")
            assertNull(parsed.value, "blank should be absent, not an empty map")
        }
    }

    // On a failure the text becomes the value. That is what keeps the form holding exactly what is on screen,
    // so the request-JSON panel cannot show something the field contradicts -- and it is what gives the kernel
    // a wrong-typed value to report against the same path when Validate runs.
    @Test
    fun keepsTheTextAsTheValueWhenItWillNotParse() {
        val typed = "{\n  \"channel\": \"excel\",\n  fileName: \"x.xlsx\"\n}"
        val parsed = parseJsonField(typed)
        assertEquals(typed, parsed.value, "unparseable text must stay in the form, not be discarded")
        val error = assertNotNull(parsed.error)
        assertTrue(error.contains("line 3"), "should point at the offending line: $error")
    }

    // Valid JSON, but not an object -- so "invalid JSON" would be a lie. The noun has to be the field's, not
    // the request panel's, since this message appears under a single field.
    @Test
    fun rejectsValidJsonThatIsNotAnObject() {
        val parsed = parseJsonField("""["a","b"]""")
        val error = assertNotNull(parsed.error)
        assertTrue(error.contains("JSON object"), "should explain an object is required: $error")
        assertTrue(error.startsWith("The value"), "should name the field's value, not the request: $error")
        assertEquals("""["a","b"]""", parsed.value)
    }
}

/**
 * A map on a form (issue #1055): an object of free keys and typed values is drawn as the one control an object with
 * no declared fields gets, a JSON editor, and the page validates it with the kernel's own validator -- which now
 * reports a failure *inside* an entry, a place the control has no slot for. [FieldErrors.messagesWithin] is what
 * shows it with the field, led by where in the map it is.
 */
class MapFieldErrorsTest {
    private val order = parseSchemaTypes(
        mapOf(
            "f.Order" to mapOf(
                SCH.type to SCT.kObject,
                SCH.properties to mapOf(
                    "title" to mapOf(SCH.type to SCT.string),
                    "scores" to mapOf(SCH.type to SCT.kObject, SCH.additionalProperties to mapOf(SCH.type to SCT.integer)),
                    "copy" to mapOf(
                        SCH.type to SCT.kObject,
                        SCH.additionalProperties to mapOf(SCH.type to SCT.kObject, SCH.additionalProperties to mapOf(SCH.type to SCT.string)),
                    ),
                ),
            ),
        ),
    ).getValue("f.Order")

    @Test
    fun aMapIsNamedForItsValues() {
        // "(object)" said nothing of what an entry holds, which is all a map declares.
        assertEquals("map of integer", typeWord(order.properties.getValue("scores").valueType))
        assertEquals("map of map of string", typeWord(order.properties.getValue("copy").valueType))
        assertEquals("string", typeWord(order.properties.getValue("title").valueType))
        // A map whose values are the map itself is named a few levels down and no further.
        val folder = parseSchemaTypes(
            mapOf("f.Folder" to mapOf(SCH.type to SCT.kObject, SCH.additionalProperties to mapOf(SCH.dRef to "#/\$defs/f.Folder"))),
        ).getValue("f.Folder")
        assertEquals("map of map of map of map", typeWord(folder))
    }

    @Test
    fun anOutlineExpandsWhatAMapsEntriesHold() {
        val types = parseSchemaTypes(
            mapOf(
                "f.Line" to mapOf(SCH.type to SCT.kObject, SCH.properties to mapOf("text" to mapOf(SCH.type to SCT.string))),
                "f.Cart" to mapOf(
                    SCH.type to SCT.kObject,
                    SCH.properties to mapOf(
                        "lines" to mapOf(SCH.type to SCT.kObject, SCH.additionalProperties to mapOf(SCH.dRef to "#/\$defs/f.Line")),
                        "list" to mapOf(SCH.type to SCT.array, SCH.items to mapOf(SCH.dRef to "#/\$defs/f.Line")),
                        // Declared fields and a value type: its own fields are what the outline shows.
                        "tally" to mapOf(
                            SCH.type to SCT.kObject,
                            SCH.properties to mapOf("kind" to mapOf(SCH.type to SCT.string)),
                            SCH.additionalProperties to mapOf(SCH.type to SCT.integer),
                        ),
                    ),
                ),
            ),
        )
        val cart = types.getValue("f.Cart")
        fun element(name: String) = outlineElement(cart.properties.getValue(name).valueType)
        assertEquals("f.Line", element("lines")?.name)
        assertEquals("f.Line", element("list")?.name)
        assertEquals(null, element("tally"))
        assertEquals(null, outlineElement(cart.properties.getValue("lines").valueType.additionalValueType!!))
        assertEquals("map of object", typeWord(cart.properties.getValue("lines").valueType))
    }

    @Test
    fun aMapIsDrawnAsOneJsonControl() {
        // No declared fields of its own, so the form draws it as it draws any such object.
        assertEquals(ControlKind.JsonMap, controlKind(order.properties.getValue("scores").valueType, required = false, editable = true))
    }

    @Test
    fun aFailureInsideAnEntryIsShownWithTheFieldLedByItsKey() {
        // The browser runs the validator the server runs, so it finds the same failures at the same paths.
        val failures = validate(
            order,
            mapOf("title" to "T", "scores" to mapOf("ann" to 5, "bo" to "many"), "copy" to mapOf("home" to mapOf("title" to 7))),
        )
        assertEquals(setOf("scores.bo", "copy.home.title"), failures.map { it.path }.toSet())
        val errors = FieldErrors(failures, noteEdit = {})
        // Nothing is reported at the field itself, which is all the control used to be shown.
        assertEquals(emptyList(), errors.messagesAt("scores"))
        val shown = errors.messagesWithin("scores").single()
        assertEquals(SchFailCode.badValue, shown.code)
        assertEquals(true, shown.message.startsWith("bo: "))
        assertEquals(true, errors.messagesWithin("copy").single().message.startsWith("home.title: "))
        // A field with nothing wrong inside it shows nothing, and a failure at the field itself is shown as it is.
        assertEquals(emptyList(), errors.messagesWithin("title"))
        val wrongKind = validate(order, mapOf("scores" to "none"))
        assertEquals(listOf("scores"), FieldErrors(wrongKind, noteEdit = {}).messagesWithin("scores").map { it.path })
        assertEquals(wrongKind.single().message, FieldErrors(wrongKind, noteEdit = {}).messagesWithin("scores").single().message)
    }
}

/**
 * Pure-logic coverage (issue #161) for the keyed-element labeling the forms UI draws (issue #487): a keyed
 * trait's entries read as their primary-key value ("Yearly — 2024") rather than a bare index. The engine is
 * generic -- it keys off [SchType.primaryKey], not any gedra vocabulary -- so these build plain schema maps and
 * ask the two pure helpers ([keyedElementLabel], [elementRowHeader]) for their verdict. No React, no DOM.
 */
class SchemaFormKeyingTest {

    // A union whose discriminator is `kind`: a `yearly` branch whose `data` is a type keyed on `year`, and a
    // `name` branch whose `data` is not keyed. This mirrors a real trait entry (the key lives on `data`'s type,
    // its fields one level down under `data`) without importing the gedra constants the engine itself avoids.
    private fun unionDefs(): Map<String, Any?> = mapOf(
        "t.YearlyData" to mapOf(
            SCH.type to SCT.kObject,
            SCH.properties to mapOf("year" to mapOf(SCH.type to SCT.integer), "note" to mapOf(SCH.type to SCT.string)),
            SCH.required to listOf("year"),
            SCH.primaryKey to listOf("year"),
        ),
        "t.NameData" to mapOf(
            SCH.type to SCT.kObject,
            SCH.properties to mapOf("name" to mapOf(SCH.type to SCT.string)),
        ),
        "t.YearlyEntry" to mapOf(
            SCH.type to SCT.kObject,
            SCH.title to "Yearly",
            SCH.properties to mapOf(
                "kind" to mapOf(SCH.type to SCT.string, SCH.const to "yearly"),
                "data" to mapOf(SCH.dRef to "t.YearlyData"),
            ),
        ),
        "t.NameEntry" to mapOf(
            SCH.type to SCT.kObject,
            SCH.properties to mapOf(
                "kind" to mapOf(SCH.type to SCT.string, SCH.const to "name"),
                "data" to mapOf(SCH.dRef to "t.NameData"),
            ),
        ),
        "t.Union" to mapOf(
            SCH.oneOf to listOf(mapOf(SCH.dRef to "t.YearlyEntry"), mapOf(SCH.dRef to "t.NameEntry")),
            SCH.discriminator to mapOf(SCH.propertyName to "kind"),
        ),
    )

    private fun union(): SchType = parseSchemaTypes(unionDefs()).getValue("t.Union")

    /** A keyed branch is labeled by the key value read from its `data`; an unkeyed branch has no label. */
    @Test
    fun labelsAKeyedUnionElementByItsKey() {
        val u = union()
        assertEquals("2024", keyedElementLabel(u, mapOf("kind" to "yearly", "data" to mapOf("year" to 2024))))
        // The unkeyed `name` branch: nothing identifies one entry from another, so no key label.
        assertNull(keyedElementLabel(u, mapOf("kind" to "name", "data" to mapOf("name" to "My form"))))
        // A JSON number arrives as a Double on the wire; it still reads as the plain integer, not "2024.0".
        assertEquals("2024", keyedElementLabel(u, mapOf("kind" to "yearly", "data" to mapOf("year" to 2024.0))))
    }

    /** An element type keyed directly (not a union) is labeled by its own key -- a plain keyed object list. */
    @Test
    fun labelsADirectlyKeyedElementType() {
        val yearlyData = parseSchemaTypes(unionDefs()).getValue("t.YearlyData")
        assertEquals("2025", keyedElementLabel(yearlyData, mapOf("year" to 2025)))
        // No key value present: nothing to show (a fresh, empty row).
        assertNull(keyedElementLabel(yearlyData, mapOf("note" to "hi")))
    }

    /** A composite key joins every field, in the order the key declares them. */
    @Test
    fun joinsACompositeKey() {
        val defs = mapOf(
            "t.Keyed" to mapOf(
                SCH.type to SCT.kObject,
                SCH.properties to mapOf(
                    "client" to mapOf(SCH.type to SCT.string),
                    "year" to mapOf(SCH.type to SCT.integer),
                ),
                SCH.required to listOf("client", "year"),
                SCH.primaryKey to listOf("client", "year"),
            ),
        )
        val keyed = parseSchemaTypes(defs).getValue("t.Keyed")
        assertEquals("acme, 2024", keyedElementLabel(keyed, mapOf("client" to "acme", "year" to 2024)))
        // A half-filled composite key yields no label -- it must not read as complete, or two rows with only
        // their first field entered would collide. The row falls back to its index until the key is whole.
        assertNull(keyedElementLabel(keyed, mapOf("client" to "acme")))
    }

    /**
     * The row header: where a failure is named by its path, the index with the key beside it, and the bare index for an
     * unkeyed element; on a user-facing form, the key, else a union's branch name, else the entry's heading for its
     * number (issue #1714).
     */
    @Test
    fun composesTheRowHeader() {
        val u = union()
        val yearly = mapOf("kind" to "yearly", "data" to mapOf("year" to 2024))
        val unkeyed = mapOf("kind" to "name", "data" to mapOf("name" to "x"))
        val keyNotFilled = mapOf("kind" to "yearly", "data" to emptyMap<String, Any?>())
        // Not friendly: the index stays -- a failure named "edits[1].data.year" still points at a row on screen -- with
        // the key added beside it, and the raw discriminator value standing in for the title.
        assertEquals("[0] yearly — 2024", elementRowHeader(u, yearly, 0, friendly = false))
        assertEquals("[1]", elementRowHeader(u, unkeyed, 1, friendly = false))
        assertEquals("[2]", elementRowHeader(u, keyNotFilled, 2, friendly = false))
        // Friendly: no path is shown outside debug, so no index. A keyed entry is its key; an entry of a union without
        // one (an edit form's section) is its branch's name; any other is its number, counting from 1.
        assertEquals("Yearly — 2024", elementRowHeader(u, yearly, 0, friendly = true))
        assertEquals("Name", elementRowHeader(u, unkeyed, 1, friendly = true))
        assertEquals("Yearly", elementRowHeader(u, keyNotFilled, 2, friendly = true))
        // A section whose branch is not chosen yet has no name to give.
        assertEquals("Entry 4", elementRowHeader(u, emptyMap(), 3, friendly = true))
        // A plain list's entry, by its number -- or as the field's layout names its entries.
        val speaker = parseSchemaTypes(mapOf("t.Speaker" to mapOf(SCH.type to SCT.kObject))).getValue("t.Speaker")
        assertEquals("Entry 2", elementRowHeader(speaker, mapOf("name" to "Pat"), 1, friendly = true))
        val named = elementRowHeader(speaker, mapOf("name" to "Pat"), 1, friendly = true, entryLabel = $$"Co-speaker ${n}")
        assertEquals("Co-speaker 2", named)
    }

    /** An entry's heading (issue #1714): its number put in the template; a template that fails gives the shipped one. */
    @Test
    fun headsAnEntryByItsNumber() {
        assertEquals("Entry 1", entryHeading(HFRAG.defaultEntryLabel, 0))
        assertEquals("Co-speaker 3", entryHeading($$"Co-speaker ${n}", 2))
        assertEquals("Panelist #10", entryHeading($$"Panelist #${n}", 9))
        // A client's copy may compute on it.
        assertEquals("First speaker", entryHeading($$"${n == 1 ? 'First' : 'Another'} speaker", 0))
        // A client's line that names what it is not given costs the client its words, not the form.
        assertEquals("Entry 2", entryHeading($$"Speaker ${name}", 1))
        // Words with no number are taken as written.
        assertEquals("Speaker", entryHeading("Speaker", 4))
    }

    /** Headings that read alike on a friendly list are numbered apart, the rest left as they are (issue #1714). */
    @Test
    fun numbersApartHeadingsThatReadAlike() {
        assertEquals(listOf("Session", "Talk title"), distinctHeadings(listOf("Session", "Talk title")))
        assertEquals(
            listOf("Questionnaire (1)", "Session", "Questionnaire (2)", "Questionnaire (3)"),
            distinctHeadings(listOf("Questionnaire", "Session", "Questionnaire", "Questionnaire")),
        )
    }
}

/**
 * A field's bounds said in words, from copy (issue #1693): [boundHintText] for each kind of field and each end it
 * declares, a client's rewording, and the placeholder ([typeHint]) left only where a value's shape cannot be guessed.
 */
class FieldBoundsTest {
    /** The value type of a field of [fieldSchema]. */
    private fun type(fieldSchema: Map<String, Any?>): SchType = parseSchemaTypes(
        mapOf("t.T" to mapOf(SCH.type to SCT.kObject, SCH.properties to mapOf("f" to fieldSchema))),
    ).getValue("t.T").properties.getValue("f").valueType

    private fun hint(vararg schema: Pair<String, Any?>, words: FieldBoundWords = FieldBoundWords()) =
        boundHintText(type(mapOf(*schema)), words)

    private val opts = listOf(mapOf(SCH.value to "a", SCH.label to "A"), mapOf(SCH.value to "b", SCH.label to "B"))

    /** Copy whose every line names itself and the ends it was given, so a case reads which line a field picks. */
    private val probe = FieldBoundWords(
        FieldBound.entries.associateWith { b ->
            "${b.name} " + $$"${min ?: '-'} ${max ?: '-'}" + (if (b == FieldBound.numberBetweenExclusive) $$" ${minExclusive} ${maxExclusive}" else "")
        },
    )

    /** Each case: a field's schema, the line it picks with its ends, and the numbers the shipped line states. */
    private fun assertBound(expected: String, vararg schema: Pair<String, Any?>) {
        assertEquals(expected, hint(*schema, words = probe))
        // The shipped line for it evaluates, and says the numbers it was given.
        val shipped = hint(*schema)!!
        // Each a number of its own, so a 0 is not found inside a 10.
        val numbers = shipped.split(Regex("[^0-9.]+")).map { it.trimEnd('.') }
        for (n in expected.split(" ").drop(1).take(2).filter { it != "-" }) assertTrue(n in numbers, "$n in '$shipped'")
    }

    @Test
    fun aTextFieldsLengthPicksTheCharactersLine() {
        assertBound("textAtMost - 128", SCH.type to SCT.string, SCH.maxLength to 128)
        assertBound("textBetween 50 600", SCH.type to SCT.string, SCH.minLength to 50, SCH.maxLength to 600)
        assertBound("textAtLeast 3 -", SCH.type to SCT.string, SCH.minLength to 3)
        assertBound("textBetween 6 6", SCH.type to SCT.string, SCH.minLength to 6, SCH.maxLength to 6)
        assertNull(hint(SCH.type to SCT.string))
    }

    @Test
    fun aNumbersValuePicksItsRangeAndAnExclusiveEndSaysSo() {
        assertBound("numberBetween 20 90", SCH.type to SCT.integer, SCH.minimum to 20, SCH.maximum to 90)
        assertBound("numberAtLeast 0 -", SCH.type to SCT.integer, SCH.minimum to 0)
        assertBound("numberAtMost - 2.5", SCH.type to SCT.number, SCH.maximum to 2.5)
        assertBound("numberMoreThan 0 -", SCH.type to SCT.number, SCH.exclusiveMinimum to 0)
        assertBound("numberLessThan - 10", SCH.type to SCT.number, SCH.exclusiveMaximum to 10)
        assertBound("numberBetweenExclusive 0 10 true false", SCH.type to SCT.number, SCH.exclusiveMinimum to 0, SCH.maximum to 10)
        assertBound("numberBetweenExclusive 0 10 false true", SCH.type to SCT.number, SCH.minimum to 0, SCH.exclusiveMaximum to 10)
        assertBound(
            "numberBetweenExclusive 0 10 true true", SCH.type to SCT.number, SCH.exclusiveMinimum to 0, SCH.exclusiveMaximum to 10,
        )
    }

    @Test
    fun aListOfChoicesPicksHowManyToChoose() {
        val choices = mapOf(SCH.type to SCT.string, SCH.options to opts)
        assertBound("choiceAtMost - 3", SCH.type to SCT.array, SCH.items to choices, SCH.maxItems to 3)
        assertBound("choiceAtLeast 1 -", SCH.type to SCT.array, SCH.items to choices, SCH.minItems to 1)
        assertBound("choiceBetween 1 2", SCH.type to SCT.array, SCH.items to choices, SCH.minItems to 1, SCH.maxItems to 2)
    }

    @Test
    fun anyOtherListOrAMapPicksEntries() {
        val strings = mapOf(SCH.type to SCT.string)
        assertBound("listAtMost - 4", SCH.type to SCT.array, SCH.items to strings, SCH.maxItems to 4)
        assertBound("listAtLeast 1 -", SCH.type to SCT.array, SCH.items to strings, SCH.minItems to 1)
        assertBound("listBetween 2 5", SCH.type to SCT.array, SCH.items to strings, SCH.minItems to 2, SCH.maxItems to 5)
        assertBound("listAtMost - 6", SCH.type to SCT.kObject, SCH.additionalProperties to strings, SCH.maxProperties to 6)
    }

    @Test
    fun aClientRewordsTheLinesAndAMistakeFallsBackToTheShippedOne() {
        val words = FieldBoundWords.of(
            Copy(
                mapOf(
                    HFRAG.fieldBoundsNs to mapOf(
                        FieldBound.textAtMost.name to $$"No more than ${max} letters",
                        FieldBound.numberBetween.name to $$"${min}–${max}",
                        // Not a template that evaluates: its parameter is not one the line is given.
                        FieldBound.numberAtLeast.name to $$"At least ${minimum}",
                    ),
                ),
            ),
        )
        assertEquals("No more than 128 letters", hint(SCH.type to SCT.string, SCH.maxLength to 128, words = words))
        assertEquals("1–5", hint(SCH.type to SCT.integer, SCH.minimum to 1, SCH.maximum to 5, words = words))
        assertEquals("At least 2", hint(SCH.type to SCT.integer, SCH.minimum to 2, words = words))
        // A line the client leaves alone is as shipped.
        val choices = mapOf(SCH.type to SCT.string, SCH.options to opts)
        assertEquals(
            hint(SCH.type to SCT.array, SCH.items to choices, SCH.maxItems to 3),
            hint(SCH.type to SCT.array, SCH.items to choices, SCH.maxItems to 3, words = words),
        )
    }

    @Test
    fun onlyADateOrAFileHasAPlaceholder() {
        assertNull(typeHint(type(mapOf(SCH.type to SCT.string))))
        assertNull(typeHint(type(mapOf(SCH.type to SCT.integer))))
        assertNull(typeHint(type(mapOf(SCH.type to SCT.number))))
        assertEquals(SFMT.date, typeHint(type(mapOf(SCH.type to SCT.string, SCH.format to SFMT.date))))
        assertEquals("file", typeHint(type(mapOf(SCH.type to SCT.string, SCH.format to SFMT.binary))))
    }
}
