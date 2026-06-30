package com.dynamicruntime.common.schema

import com.dynamicruntime.common.context.KdrCxt
import com.dynamicruntime.common.util.toJsonMap
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe

class SchTypeBuilderTest : StringSpec({

    $$"namespaced $defs, refs, mandatory field descriptions, default string, reusable props" {
        val cxt = KdrCxt.mkSimpleCxt("test")

        // A reusable property declared once; type defaults to string.
        val nameProp = schemaProperty(cxt, "core", "name", "A name")

        val defs = schemaDefs(cxt, "core") {
            type("Count") {
                type = SCT.integer
                description = "A counting integer" // type description stays optional
            }
            type("Person") {
                type = SCT.kObject
                property(nameProp, required = true)                     // reuse as-is
                property("age", "Age in years") { type = SCT.integer }  // explicit type
                property("nickname", "Informal name")                   // defaults to string
                property("count", "How many things") { ref("Count") }   // $ref, no default type
            }
            type("Company") {
                type = SCT.kObject
                property(nameProp) { description = "Legal company name" } // reuse: clone + mutate
            }
        }

        defs.keys shouldBe setOf("core.Count", "core.Person", "core.Company")

        // shouldNotBeNull() asserts and returns the non-null value, so it chains.
        val person = defs["core.Person"].shouldNotBeNull().toJsonMap()
        person[SCH.required] shouldBe listOf("name")

        val pProps = person[SCH.properties].shouldNotBeNull().toJsonMap()
        pProps["name"] shouldBe mapOf(SCH.description to "A name", SCH.type to SCT.string)
        pProps["age"] shouldBe mapOf(SCH.description to "Age in years", SCH.type to SCT.integer)
        pProps["nickname"] shouldBe mapOf(SCH.description to "Informal name", SCH.type to SCT.string)
        pProps["count"] shouldBe mapOf(SCH.description to "How many things", SCH.dRef to "#/${SCH.dDefs}/core.Count")

        val cProps = defs["core.Company"].shouldNotBeNull().toJsonMap()[SCH.properties].shouldNotBeNull().toJsonMap()
        // Clone was mutated for this use...
        cProps["name"] shouldBe mapOf(SCH.description to "Legal company name", SCH.type to SCT.string)
        // ...without affecting Person's clone or the original reusable property.
        pProps["name"].shouldNotBeNull().toJsonMap()[SCH.description] shouldBe "A name"
        nameProp.data[SCH.description] shouldBe "A name"
    }

    "namespace is named once for both reusable properties and types (with per-entity override)" {
        val cxt = KdrCxt.mkSimpleCxt("test")

        val defs = schemaDefs(cxt, "core") {
            // Reusable properties declared in the scope's namespace -> "core" once.
            val name = property("name", "A name")
            val active = property("active", "Active flag") { type = SCT.boolean }
            // Per-entity override: this property's bare $ref resolves in "ext".
            val ext = property("ext", "External thing", namespace = "ext") { ref("Thing") }

            type("Person") {
                type = SCT.kObject
                property(name, required = true)
                property(active)
                property(ext)
            }
        }

        defs.keys shouldBe setOf("core.Person")

        // Assert not-null once; its contract smart-casts `props` for the lines below.
        val props = defs["core.Person"]?.toJsonMap()?.get(SCH.properties)?.toJsonMap()
        props.shouldNotBeNull()
        props["name"] shouldBe mapOf(SCH.description to "A name", SCH.type to SCT.string)
        props["active"] shouldBe mapOf(SCH.description to "Active flag", SCH.type to SCT.boolean)
        // The override namespace was honored for the property's ref.
        props["ext"] shouldBe mapOf(SCH.description to "External thing", SCH.dRef to "#/${SCH.dDefs}/ext.Thing")
    }
})
