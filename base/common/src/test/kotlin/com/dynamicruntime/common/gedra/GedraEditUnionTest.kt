package com.dynamicruntime.common.gedra

import com.dynamicruntime.common.context.KdrCxt
import com.dynamicruntime.common.schema.SCT
import com.dynamicruntime.common.schema.SchFailCode
import com.dynamicruntime.common.schema.SchOpts
import com.dynamicruntime.common.schema.SchType
import com.dynamicruntime.common.schema.coerceAndValidate
import com.dynamicruntime.common.schema.parseSchemaTypes
import com.dynamicruntime.common.util.toJsonMapOrEmpty
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe

/**
 * The manufactured **edit** union (issue #337): what a patch may ask of one entry.
 *
 * SCAFFOLD(step 4) — the branch and discriminator assertions here are the same ones a patch round trip cannot
 * pass without, so they should go when the endpoint exists and drives them end to end, exactly as #301's
 * fixture retired the entry-union shape tests. What is **not** scaffolding and should survive: that a known
 * trait's `data` is typed while an unknown trait's is open, since a successful call looks identical either way.
 */
class GedraEditUnionTest : StringSpec({

    val cxt = KdrCxt.mkSimpleCxt("gedraEdit")

    /** Two traits, one of which references its data type rather than inlining it. */
    fun traitsFor(): GedraConfig = gedraConfig(cxt, "editTraits", GCFG.globalNamespace) {
        type("NameData") {
            type = SCT.kObject
            property("name", "What to call it.", required = true)
        }
        trait("NameEntry", "name", setOf(GedraDataType.formDoc), dataType = "NameData")
        trait("NoteEntry", "note", setOf(GedraDataType.formDoc)) {
            property("text", "Anything worth writing down.", required = true)
        }
        trait("WfOnlyEntry", "wfOnly", setOf(GedraDataType.wfData)) {
            property("stage", "Where the workflow has got to.")
        }
    }

    fun editTypes(): Map<String, com.dynamicruntime.common.schema.SchType> {
        val config = traitsFor()
        val defs = config.defs.toMutableMap()
        defs.putAll(entryEditUnionDefs(cxt, GCFG.globalNamespace, GedraDataType.formDoc, config.traits.values))
        return parseSchemaTypes(defs)
    }

    val unionName = "${GCFG.globalNamespace}.${GU.editUnionName(GedraDataType.formDoc)}"

    // SCAFFOLD(step 4)
    "the union carries a branch per trait that applies to the kind, and a default" {
        val union = editTypes().getValue(unionName)
        val variants = union.variants.shouldNotBeNull()
        // `wfOnly` is absent: it applies to workflow data, so it is not an edit a form document can carry.
        // The union is built per kind for exactly this reason -- `appliesTo` becomes a schema check rather
        // than something the service has to remember.
        variants.values shouldContainExactly listOf("name", "note")
        variants.discriminator shouldBe GE.traitId
        variants.defaultBranch.shouldNotBeNull()
    }

    // SCAFFOLD(step 4)
    "an edit declares the verb as a choice, so a wrong one is named rather than merely refused" {
        val types = editTypes()
        val branch = types.getValue("${GCFG.globalNamespace}.NameEntryEdit")
        val failures = coerceAndValidate(
            branch,
            mapOf(GE.traitId to "name", GED.action to "obliterate", GE.data to mapOf("name" to "x")),
            SchOpts(forInput = true),
        ).failures
        failures.map { it.path to it.code } shouldContainExactly listOf(GED.action to SchFailCode.invalidOption)
        failures.first().options.shouldNotBeNull().map { it.value } shouldContainExactly
            GedraEditAction.entries.map { it.name }
    }

    // Not scaffolding: a round trip through the endpoint looks the same whether `data` was typed or waved
    // through, so only a direct check can tell that a known trait's data is really being validated.
    "a known trait's data is typed, and a referenced shape stays shared with the entry type" {
        val types = editTypes()
        val edit = types.getValue("${GCFG.globalNamespace}.NameEntryEdit")
        // The same target as the entry type's `data`, rather than a copy of its properties.
        edit.properties.getValue(GE.data).refName shouldBe "${GCFG.globalNamespace}.NameData"
        types.getValue("${GCFG.globalNamespace}.NameEntry").properties.getValue(GE.data).refName shouldBe
            "${GCFG.globalNamespace}.NameData"

        // An inlined shape is typed just as closely: a field the trait never declared is refused.
        val note = types.getValue("${GCFG.globalNamespace}.NoteEntryEdit")
        coerceAndValidate(
            note,
            mapOf(
                GE.traitId to "note", GED.action to GedraEditAction.addOrReplace.name,
                GE.data to mapOf("text" to "fine", "smuggled" to 1),
            ),
            SchOpts(forInput = true),
        ).failures.map { it.path } shouldContainExactly listOf("${GE.data}.smuggled")
    }

    // The other half, and the reason the general endpoint is usable by a client whose traits it never loaded:
    // an unknown trait's data is carried as sent rather than refused or emptied.
    "an unknown trait's edit is accepted and its data carried whole" {
        val union = editTypes().getValue(unionName)
        val result = coerceAndValidate(
            union,
            mapOf(
                GE.traitId to "aClientTraitThisNodeNeverLoaded",
                GED.action to GedraEditAction.addOrMerge.name,
                GE.data to mapOf("whatever" to 1L),
            ),
            SchOpts(forInput = true),
        )
        result.failures.shouldBeEmpty()
        result.value.toJsonMapOrEmpty()[GE.data].toJsonMapOrEmpty()["whatever"] shouldBe 1L
    }

    // `data` is absent for a delete, and the schema has to permit that because one branch cannot say
    // "required unless the action is a delete". The service settles it; see `editEnvelopeFields`.
    "a delete carries no data, and the schema allows it" {
        val union = editTypes().getValue(unionName)
        coerceAndValidate(
            union,
            mapOf(GE.traitId to "name", GED.action to GedraEditAction.deleteOrNoOp.name),
            SchOpts(forInput = true),
        ).failures.shouldBeEmpty()
    }

    // A keyed trait (issue #487) that requires a field besides its key. Built apart from `traitsFor` so the
    // branch-count assertions above stay untouched.
    fun budgetDefs(): GedraConfig = gedraConfig(cxt, "keyedTraits", GCFG.globalNamespace) {
        trait("BudgetEntry", "budget", setOf(GedraDataType.formDoc), primaryKey = listOf("year")) {
            property("year", "The year this budget is for; its primary key.", required = true) { type = SCT.integer }
            property("amount", "The budgeted amount -- required, and not the key.", required = true) { type = SCT.number }
        }
    }

    fun budgetEditUnion(): SchType {
        val config = budgetDefs()
        val defs = config.defs.toMutableMap()
        defs.putAll(entryEditUnionDefs(cxt, GCFG.globalNamespace, GedraDataType.formDoc, config.traits.values))
        return parseSchemaTypes(defs).getValue(unionName)
    }

    // The fix (issue #487): a keyed trait's delete addresses its entry by the key carried in `data`, so the
    // delete must send the key -- but not the trait's other required fields. Because an edit's `data` is a
    // fragment, that minimal payload validates. Before, `amount` being required refused the delete outright.
    "a keyed trait's delete may carry its key alone, though the trait has other required fields" {
        coerceAndValidate(
            budgetEditUnion(),
            mapOf(
                GE.traitId to "budget", GED.action to GedraEditAction.deleteOrNoOp.name,
                GE.data to mapOf("year" to 2024),
            ),
            SchOpts(forInput = true),
        ).failures.shouldBeEmpty()
    }

    // The other half: only the *edit* union's data is a fragment. The entry union -- what a create validates
    // against -- shares the same data type but keeps its `required`, so an incomplete entry is still refused.
    "the entry a create stores must still be complete" {
        val entry = parseSchemaTypes(budgetDefs().defs).getValue("${GCFG.globalNamespace}.BudgetEntry")
        coerceAndValidate(
            entry,
            mapOf(GE.traitId to "budget", GE.data to mapOf("year" to 2024)),
            SchOpts(forInput = true),
        ).failures.map { it.path to it.code } shouldContainExactly listOf("${GE.data}.amount" to SchFailCode.missingRequired)
    }

    // A trait with a conditional: `reason` is required only when `approved` is false. A merge is a fragment, so
    // a page recording just `{approved: false}` -- with `reason` stored already or set by another page -- must
    // not be refused for `reason` on the way in; the assembled result is where the conditional is judged.
    fun approvalDefs(): GedraConfig = gedraConfig(cxt, "approvalTraits", GCFG.globalNamespace) {
        trait("ApprovalEntry", "approval", setOf(GedraDataType.formDoc)) {
            property("approved", "Whether it was approved.", required = true) { type = SCT.boolean }
            property("reason", "Why it was rejected.")
            presentWhen("reason", on = "approved", value = false)
        }
    }

    "a merge carrying only a conditional's trigger is not refused for the field it makes required" {
        val config = approvalDefs()
        val defs = config.defs.toMutableMap()
        defs.putAll(entryEditUnionDefs(cxt, GCFG.globalNamespace, GedraDataType.formDoc, config.traits.values))
        val union = parseSchemaTypes(defs).getValue(unionName)
        coerceAndValidate(
            union,
            mapOf(
                GE.traitId to "approval", GED.action to GedraEditAction.addOrMerge.name,
                GE.data to mapOf("approved" to false),
            ),
            SchOpts(forInput = true),
        ).failures.shouldBeEmpty()
    }
})
