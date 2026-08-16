package com.dynamicruntime.common.gedra

import com.dynamicruntime.common.context.KdrCxt
import com.dynamicruntime.common.schema.SCT
import com.dynamicruntime.common.schema.SchFailCode
import com.dynamicruntime.common.schema.SchOpts
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
})
