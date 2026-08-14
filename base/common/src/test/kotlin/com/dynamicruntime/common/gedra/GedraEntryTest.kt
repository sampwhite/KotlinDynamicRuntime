package com.dynamicruntime.common.gedra

import com.dynamicruntime.common.context.KdrCxt
import com.dynamicruntime.common.exception.KdrException
import com.dynamicruntime.common.schema.SCT
import com.dynamicruntime.common.schema.SchFailCode
import com.dynamicruntime.common.schema.SchOpts
import com.dynamicruntime.common.schema.coerceAndValidate
import com.dynamicruntime.common.schema.parseSchemaTypes
import com.dynamicruntime.common.schema.schemaDefs
import com.dynamicruntime.common.schema.validate
import com.dynamicruntime.common.util.toJsonMapOrEmpty
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.collections.shouldNotContain
import io.kotest.matchers.maps.shouldContainKey
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import kotlin.time.Instant

/**
 * The entry builder: a trait's schema under `data`, with the stored envelope around it (issue #297).
 *
 * Most of what is asserted here is covered again the moment #301's fixture endpoint round-trips an entry, so
 * the happy-path cases are tagged for removal there. What survives is what a *successful* call can never
 * reach — the refusal, and the input/output projection, which no single response shows both halves of.
 */
class GedraEntryTest : StringSpec({

    val cxt = KdrCxt.mkSimpleCxt("gedraEntry")

    fun nameDefs() = schemaDefs(cxt, "globalconfig") {
        traitEntry("NameEntry", "name", setOf(GedraDataType.formDoc), "The name somebody gave this document.") {
            property("name", "What to call it.", required = true) { maxLength = 128 }
        }
    }

    // The envelope is `g-derived`, which is the whole reason a caller may send an entry without it and a
    // stored one must carry it. One type, two shapes, chosen by the reader -- so neither direction alone
    // shows both halves, which is why this one is not scaffolding.
    "the envelope is absent from the input shape and required on the way out" {
        val entry = parseSchemaTypes(nameDefs()).getValue("globalconfig.NameEntry")
        val sent = mapOf(GE.traitId to "name", GE.data to mapOf("name" to "x"))

        // Sending it: the envelope is not expected, and one echoed back is dropped rather than kept.
        val asInput = coerceAndValidate(
            entry,
            sent + mapOf(GE.entryId to "e-1"),
            SchOpts(forInput = true),
        )
        asInput.failures.shouldBeEmpty()
        asInput.value.toJsonMapOrEmpty().keys shouldNotContain GE.entryId

        // Answering with it: the same type now demands the envelope it did not ask the caller for. Listed
        // exactly rather than loosely, so growing the envelope is a decision somebody makes here rather than a
        // thing that happens -- which is what caught the actor fields being added in #325.
        validate(entry, sent).map { it.path to it.code } shouldContainExactlyInAnyOrder listOf(
            GE.entryId to SchFailCode.missingRequired,
            GE.source to SchFailCode.missingRequired,
            GE.createdAt to SchFailCode.missingRequired,
            GE.updatedAt to SchFailCode.missingRequired,
            GE.createdBy to SchFailCode.missingRequired,
            GE.updatedBy to SchFailCode.missingRequired,
        )
    }

    // Both authoring styles reach the same shape, which is what lets a trait either declare its data inline
    // or point at a type somebody else declared (issue #298 builds on this).
    "the data schema can be a reference instead of an inline declaration" {
        val defs = schemaDefs(cxt, "globalconfig") {
            type("NameData") {
                type = SCT.kObject
                property("name", "What to call it.", required = true)
            }
            traitEntry("NameEntry", "name", setOf(GedraDataType.formDoc)) { ref("NameData") }
        }
        val entry = parseSchemaTypes(defs).getValue("globalconfig.NameEntry")
        entry.properties.getValue(GE.data).refName shouldBe "globalconfig.NameData"
        validate(
            entry,
            mapOf(
                GE.traitId to "name",
                GE.data to mapOf("name" to "x"),
                GE.entryId to "e-1",
                GE.source to "user",
                GE.createdAt to "2026-08-13T10:00:00.000Z",
                GE.updatedAt to "2026-08-13T10:00:00.000Z",
                GE.createdBy to 7,
                GE.updatedBy to 7,
            ),
        ).shouldBeEmpty()
    }

    // The keyword that lets a compiled type say where it may be used, so assembly can read it from the type
    // rather than needing the config object that produced it -- which matters once configs arrive from a
    // database as well as from code. Kind NAMES, not the id abbreviations, which exist only to keep an id short.
    "a trait entry declares which kinds may carry it" {
        val defs = schemaDefs(cxt, "globalconfig") {
            traitEntry("NameEntry", "name", setOf(GedraDataType.formDoc, GedraDataType.wfData)) {
                property("name", "What to call it.", required = true)
            }
        }
        defs.getValue("globalconfig.NameEntry").toJsonMapOrEmpty()[GE.appliesTo] shouldBe
            listOf("formDoc", "wfData")
    }

    // Absence means "not a trait entry", never "every kind" -- so an ordinary type carries nothing, and the
    // universal case can never arrive by omission.
    "a plain type says nothing about kinds" {
        val defs = schemaDefs(cxt, "globalconfig") {
            type("Plain") { type = SCT.kObject }
        }
        defs.getValue("globalconfig.Plain").toJsonMapOrEmpty().keys shouldNotContain GE.appliesTo
    }

    // --- what is refused -----------------------------------------------------

    "a trait applying to no kind at all is refused" {
        shouldThrow<KdrException> {
            schemaDefs(cxt, "globalconfig") {
                traitEntry("Orphan", "orphan", emptySet()) { property("x", "Something.") }
            }
        }.message.shouldNotBeNull() shouldContain "no kind of gedra"
    }


    // The one assertion here a successful fixture call could never make. An entry's data is a map so that the
    // envelope can grow without colliding with a trait's own field names; a trait declaring it a scalar takes
    // that away, and the refusal lands where the author is rather than at the first payload that fails.
    "a trait may not declare its data as anything but a map" {
        val message = shouldThrow<KdrException> {
            schemaDefs(cxt, "globalconfig") {
                traitEntry("BadEntry", "bad", setOf(GedraDataType.formDoc)) { type = SCT.string }
            }
        }.message
        message.shouldNotBeNull() shouldContain "'bad'"
        message shouldContain "always a map"

        shouldThrow<KdrException> {
            schemaDefs(cxt, "globalconfig") {
                traitEntry("BadEntry", "bad", setOf(GedraDataType.formDoc)) {
                    type = SCT.array
                    items { type = SCT.string }
                }
            }
        }
    }

    // Kept, though it was tagged for removal: a *successful* round trip never sends an entry without data,
    // so #301's fixture does not reach this. The tag was wrong -- an entry with a trait and no data says
    // nothing, and only a schema-level check catches it.
    "an entry must carry data at all" {
        val entry = parseSchemaTypes(nameDefs()).getValue("globalconfig.NameEntry")
        val failures = coerceAndValidate(entry, mapOf(GE.traitId to "name"), SchOpts(forInput = true)).failures
        failures.map { it.path } shouldContainExactlyInAnyOrder listOf(GE.data)
    }

    // The branch keeps its `const`, so the union can select on it, and a stock validator reaches the same
    // verdict without reading the `discriminator` keyword at all -- the property #252 rests on.
    "the trait id stays top level, where a union can select on it" {
        val entry = parseSchemaTypes(nameDefs()).getValue("globalconfig.NameEntry")
        entry.properties shouldContainKey GE.traitId
        entry.properties.getValue(GE.traitId).valueType.constValue shouldBe "name"
        // Read as an input, so the envelope is exempt and the only complaint left is the const the branch
        // declares -- which is the thing being pinned.
        coerceAndValidate(
            entry,
            mapOf(GE.traitId to "other", GE.data to mapOf("name" to "x")),
            SchOpts(forInput = true),
        ).failures.map { it.path } shouldContainExactlyInAnyOrder listOf(GE.traitId)
    }
})
