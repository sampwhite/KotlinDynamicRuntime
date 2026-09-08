package com.dynamicruntime.common.gedra

import com.dynamicruntime.common.context.KdrCxt
import com.dynamicruntime.common.exception.KdrException
import com.dynamicruntime.common.schema.SCH
import com.dynamicruntime.common.schema.SCT
import com.dynamicruntime.common.schema.parseSchemaTypes
import com.dynamicruntime.common.gedra.workflow.WfDefSchema
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

    // --- config traits (issue #316) ---------------------------------------------

    // A config trait is a sibling of a data trait, not a widening: it binds to config kinds, and it is filed
    // where the data unions and the patch keying -- every consumer of `traits` -- will never see it.
    "a config trait binds to config kinds and stays out of the data traits" {
        val config = gedraConfig(cxt, "storedConfig", "globalconfig") {
            trait("NameEntry", "name", setOf(GedraDataType.formDoc)) {
                property("name", "The name.", required = true)
            }
            configTrait("ClientDefEntry", "clientDef", setOf(GedraConfigType.configDoc), "A stored client definition.") {
                property("clientId", "Which client.", required = true)
            }
        }
        config.configTraits.keys.toList() shouldContainExactly listOf("clientDef")
        config.traits.keys.toList() shouldContainExactly listOf("name")
        config.stateTraits.isEmpty() shouldBe true
        config.configTraits.getValue("clientDef").appliesTo shouldBe setOf(GedraConfigType.configDoc)
        // The generated entry type says which config kind may carry it, the way a data trait's names its data
        // kinds -- the one place the two bindings meet, as names on a keyword.
        config.defs.getValue("globalconfig.ClientDefEntry").toJsonMapOrEmpty()[GE.appliesTo] shouldBe listOf("configDoc")
        // And it is built by the same machinery: a named data type the entry refers to.
        val types = parseSchemaTypes(config.defs)
        types.getValue("globalconfig.ClientDefEntry").properties.getValue(GE.data).refName shouldBe
            "globalconfig.ClientDefData"
    }

    // One global id space and one namespace of generated types across data, state and config traits, so the
    // refusals a data trait gets, a config trait gets too -- in either order.
    "a config trait cannot reuse a data trait's id, or its generated type, in one config" {
        val byId = shouldThrow<KdrException> {
            gedraConfig(cxt, "c", "globalconfig") {
                trait("NameEntry", "name", setOf(GedraDataType.formDoc)) { property("name", "N.", required = true) }
                configTrait("NameCfgEntry", "name", setOf(GedraConfigType.configDoc)) { property("x", "X.") }
            }
        }
        (byId.message ?: "") shouldContain "declared twice"
        val byType = shouldThrow<KdrException> {
            gedraConfig(cxt, "c", "globalconfig") {
                configTrait("SameEntry", "one", setOf(GedraConfigType.configDoc)) { property("x", "X.") }
                trait("SameEntry", "two", setOf(GedraDataType.formDoc)) { property("y", "Y.") }
            }
        }
        (byType.message ?: "") shouldContain "both generate"
    }

    "a config trait has to apply to some config kind" {
        val ex = shouldThrow<KdrException> {
            gedraConfig(cxt, "c", "globalconfig") {
                configTrait("LostEntry", "lost", emptySet()) { property("x", "X.") }
            }
        }
        (ex.message ?: "") shouldContain "applies to no kind"
    }

    // The traits a stored client configuration is made of. Three, not #611's four: tasks live inside their
    // workflow, so the workflow trait carries them (see `coreConfigTraits`).
    "the core config traits declare the pieces a stored client configuration is made of" {
        val config = coreConfigTraits(cxt)
        config.configTraits.keys.toList() shouldContainExactly listOf(CCT.clientDef, CCT.workflowDef, CCT.schemaDef)
        config.traits.isEmpty() shouldBe true
        // The workflow trait *refers to* the definition schema rather than redeclaring it, so this config's types
        // resolve only beside it -- which is how they are compiled at boot, and what `existingTypes` is for.
        val types = parseSchemaTypes(config.defs, existingTypes = parseSchemaTypes(WfDefSchema.defs(cxt)))
        // The schema-definition trait is the one #316 exists for: its body is checked by parsing it.
        val schemaData = types.getValue("globalconfig.SchemaDefEntry").properties.getValue(GE.data).valueType
        schemaData.properties.getValue(CCT.schema).valueType.schemaDocument shouldBe true
        // Keyed as #611 says: workflows by id, schema definitions by type name; the client is single-instance.
        config.configTraits.getValue(CCT.workflowDef).primaryKey shouldBe listOf(CCT.workflowId)
        config.configTraits.getValue(CCT.schemaDef).primaryKey shouldBe listOf(CCT.typeName)
        config.configTraits.getValue(CCT.clientDef).primaryKey shouldBe emptyList()
    }
})
