package com.dynamicruntime.common.gedra

import com.dynamicruntime.common.context.KdrCxt
import com.dynamicruntime.common.exception.KdrException
import com.dynamicruntime.common.schema.SCH
import com.dynamicruntime.common.schema.SCT
import com.dynamicruntime.common.schema.parseSchemaTypes
import com.dynamicruntime.common.util.toJsonMapOrEmpty
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain

/**
 * Trait and config bundles (issue #298).
 *
 * The entry types these produce are exercised end to end once #301's fixture round-trips one, so the shape
 * assertions here are scaffolding. What is not: a config's **identity**, which no entry-carrying endpoint
 * ever shows, and the refusals, which a successful call cannot reach.
 */
class GedraConfigTest : StringSpec({

    val cxt = KdrCxt.mkSimpleCxt("gedraConfig")

    fun coreTraits() = gedraConfig(cxt, "coreTraits", "globalconfig") {
        trait("NameEntry", "name", setOf(GedraDataType.formDoc), "What somebody called this document.") {
            property("name", "What to call it.", required = true) { maxLength = 128 }
        }
    }

    // A config is addressed by the name it was declared under, and that name IS its base id -- deterministic,
    // so code that wants this config can build its id rather than look it up. The identity is the half of
    // this issue no endpoint will ever put on display, which is why it is tested here and not left to #301.
    "a config is identified by its own declared name" {
        val config = coreTraits()
        config.gedraId.fullId shouldBe "gc.cd.global.coreTraits"
        config.name shouldBe "coreTraits"
        config.gedraId.kind shouldBe GedraConfigType.configDoc
        config.gedraId.client shouldBe GID.globalClient
        config.toString() shouldBe "gc.cd.global.coreTraits"
    }

    // The class is the same wherever a config comes from; only this segment of the id differs. Code-declared
    // config is global, and config a client authored will be theirs -- which is exactly the scoping that
    // makes a client's trait collisions their own business (see the discussion on #292).
    "a config can belong to a client rather than to the deployment" {
        val config = gedraConfig(cxt, "expenseTraits", "acme", client = "acme") {
            trait("CostCentreEntry", "costCentre", setOf(GedraDataType.formDoc)) {
                property("code", "Which cost centre.", required = true)
            }
        }
        config.gedraId.fullId shouldBe "gc.cd.acme.expenseTraits"
    }

    // A name a base id cannot spell is refused where it is declared, not at whatever later point first tried
    // to address the config. The two rules differ and both are needed: the id's charset admits a leading
    // digit (fine for a minted id), while a config is addressed by name from code and so has to be a legal
    // identifier as well.
    "a config name has to be one an id can carry, and a variable name besides" {
        shouldThrow<KdrException> { gedraConfig(cxt, "core traits", "globalconfig") {} }
            .message.shouldNotBeNull() shouldContain "letters, digits and underscores"
        shouldThrow<KdrException> { gedraConfig(cxt, "9core", "globalconfig") {} }
            .message.shouldNotBeNull() shouldContain "usable as a variable name"
        gedraConfig(cxt, "_internal", "globalconfig") {}.name shouldBe "_internal"
    }

    // Both authoring styles, and the point of having both: a shared data shape declared once and pointed at,
    // beside one written where it is used. The config builder is a schema builder, which is what makes the
    // shared type declarable in the same block.
    "a trait's data can be inlined or referenced, in the same config" {
        val config = gedraConfig(cxt, "coreTraits", "globalconfig") {
            type("NameData") {
                type = SCT.kObject
                property("name", "What to call it.", required = true)
            }
            trait("NameEntry", "name", setOf(GedraDataType.formDoc), dataType = "NameData")
            trait("NoteEntry", "note", setOf(GedraDataType.formDoc)) {
                property("text", "Anything worth writing down.")
            }
        }
        val types = parseSchemaTypes(config.defs)
        types.getValue("globalconfig.NameEntry").properties.getValue(GE.data).refName shouldBe
            "globalconfig.NameData"
        types.getValue("globalconfig.NoteEntry").properties.getValue(GE.data)
            .valueType.properties.keys.toList() shouldContainExactly listOf("text")
        config.traits.keys.toList() shouldContainExactly listOf("name", "note")

        // The trait carries its own data shape (issue #337), so a second manufactured type -- the patch's edit
        // union -- is built from the trait rather than by finding its entry type in the built defs and reading
        // a property off it. Since #379 both authoring styles reach it **as a reference**: a trait's data is
        // always a named type, so that a client can narrow it without restating the generated envelope around
        // it. An author who named their own keeps that name; one who inlined gets the derived one.
        config.traits.getValue("name").dataSchema[SCH.dRef] shouldBe $$"#/$defs/globalconfig.NameData"
        config.traits.getValue("note").dataSchema[SCH.dRef] shouldBe $$"#/$defs/globalconfig.NoteData"
        // ...and the derived type carries the shape that was written inline.
        types.getValue("globalconfig.NoteData").properties.keys.toList() shouldContainExactly listOf("text")
    }

    // The multi-kind case the set exists for. `name` means the same thing on a form document and on workflow
    // data, so it is one trait rather than a `name` and a `wfDataName` -- and the generated type says so.
    "one trait can apply to several kinds" {
        val config = gedraConfig(cxt, "coreTraits", "globalconfig") {
            trait("NameEntry", "name", setOf(GedraDataType.formDoc, GedraDataType.wfData)) {
                property("name", "What to call it.", required = true)
            }
        }
        config.traits.getValue("name").appliesTo shouldContainExactly
            listOf(GedraDataType.formDoc, GedraDataType.wfData)
        config.defs.getValue("globalconfig.NameEntry").toJsonMapOrEmpty()[GE.appliesTo] shouldBe
            listOf("formDoc", "wfData")
    }

    // --- what is refused -----------------------------------------------------

    // Uniqueness across configs is #299's boot check; this is the local half, and it is worth having
    // separately because a duplicate inside one file is a typo rather than a wiring problem, and should be
    // reported as one.
    "a trait id cannot be declared twice in one config" {
        shouldThrow<KdrException> {
            gedraConfig(cxt, "coreTraits", "globalconfig") {
                trait("NameEntry", "name", setOf(GedraDataType.formDoc)) { property("name", "One.") }
                trait("OtherEntry", "name", setOf(GedraDataType.wfData)) { property("name", "Two.") }
            }
        }.message.shouldNotBeNull() shouldContain "declared twice"
    }

    // The failure this one prevents is the quiet kind: `defs` is a map, so the second declaration would
    // simply replace the first and the config would report two traits, one of which no longer had a type of
    // its own.
    "two traits cannot generate the same type" {
        shouldThrow<KdrException> {
            gedraConfig(cxt, "coreTraits", "globalconfig") {
                trait("NameEntry", "name", setOf(GedraDataType.formDoc)) { property("name", "One.") }
                trait("NameEntry", "title", setOf(GedraDataType.formDoc)) { property("title", "Two.") }
            }
        }.message.shouldNotBeNull() shouldContain "silently replace"
    }
})
