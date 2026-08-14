package com.dynamicruntime.kdn

import com.dynamicruntime.common.endpoint.EP
import com.dynamicruntime.common.gedra.GCFG
import com.dynamicruntime.common.gedra.GE
import com.dynamicruntime.common.gedra.GFX
import com.dynamicruntime.common.gedra.GT
import com.dynamicruntime.common.gedra.GU
import com.dynamicruntime.common.gedra.GedraDataType
import com.dynamicruntime.common.http.request.TestHttpClient
import com.dynamicruntime.common.schema.SchFailCode
import com.dynamicruntime.common.schema.SchOpts
import com.dynamicruntime.common.schema.coerceAndValidate
import com.dynamicruntime.common.util.jsonMap
import com.dynamicruntime.common.util.toJsonListOfMaps
import com.dynamicruntime.common.util.toJsonMapOrEmpty
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain

/**
 * The whole config chain, driven end to end (issue #301).
 *
 * A round trip through this endpoint cannot succeed unless every link held: a component declared a trait, the
 * registry collected its bundle, the collector checked it and folded in the entry type it generated, the
 * schema service manufactured the union over every such type and compiled it, the endpoint resolved a
 * reference to a type that did not exist when it was built, and the validator selected the right branch. That
 * is why several tests in #297, #298 and #300 were deleted when this arrived — they asserted, separately,
 * things this cannot pass without.
 */
class GedraEntryFixtureTest : StringSpec({

    val cxt = Startup.mkTestBootCxt("gedraFixture", "gedraFixtureTest")
    val fillOut = "/fixture/gedra/formDocEntries/fillOut"
    val unionName = "${GCFG.globalNamespace}.${GU.unionName(GedraDataType.formDoc)}"

    fun entry(name: String): Map<String, Any?> =
        mapOf(GE.traitId to GT.name, GE.data to mapOf(GT.name to name))

    "an entry goes in and comes back filled out" {
        val client = TestHttpClient(cxt.instanceConfig)
        val response = client.sendJsonPostRequest(fillOut, mapOf<String, Any?>(GFX.entries to listOf(entry("My Expense Form"))))
        val items = response.getValue(EP.items).toJsonListOfMaps()
        items.size shouldBe 1

        val out = items.first()
        out[GE.traitId] shouldBe GT.name
        out[GE.data].toJsonMapOrEmpty()[GT.name] shouldBe "My Expense Form"
        // The envelope the caller did not send and the output shape requires. Response-schema validation is on
        // in a test boot, so an entry missing any of it would have failed before reaching here.
        // In any order: a JSON object is unordered, and asserting a sequence here would be asserting
        // something the wire never promised.
        out.keys.toList() shouldContainExactlyInAnyOrder
            listOf(GE.traitId, GE.data, GE.entryId, GE.source, GE.createdAt, GE.updatedAt)
        out[GE.source] shouldBe GFX.fixtureSource
        // Millisecond precision, UTC, trailing Z -- one place decides the wire format and no handler invents
        // its own.
        out[GE.createdAt].toString() shouldContain "Z"
    }

    // The bound #300 declared. Kept as a failing case through the fixture rather than as a unit test, which
    // is what let CoreTraitsTest be deleted rather than kept: a round trip that only ever succeeds
    // proves the trait validates and says nothing about `maxLength` being declared at all.
    //
    // It is also the first check that a failure INSIDE `data` reports at a nested path rather than at the top
    // level -- exactly the sort of thing pushing trait fields down could have broken quietly.
    "a name longer than the bound fails, at the path inside data" {
        val client = TestHttpClient(cxt.instanceConfig)
        val handler = client.sendEditRequest(
            fillOut,
            emptyMap(),
            mapOf<String, Any?>(GFX.entries to listOf(entry("x".repeat(GT.nameMaxLength + 1)))),
            isPut = false,
        )
        handler.rptStatusCode shouldBe 400
    }

    // Several shapes in one payload is the point of a union: each element validated against the branch its own
    // traitId names. With one trait there is one branch, so the unknown case below carries the other half.
    "an unknown trait falls through rather than taking the payload down" {
        val client = TestHttpClient(cxt.instanceConfig)
        val response = client.sendJsonPostRequest(
            fillOut,
            mapOf<String, Any?>(
                GFX.entries to listOf(
                    entry("Known"),
                    mapOf(GE.traitId to "notATraitThisNodeKnows", GE.data to mapOf("whatever" to 1)),
                ),
            ),
        )
        val items = response.getValue(EP.items).toJsonListOfMaps()
        items.size shouldBe 2
        // The unknown entry survives intact -- the default branch is open, so its data is carried rather than
        // silently emptied.
        items[1][GE.data].toJsonMapOrEmpty()["whatever"] shouldBe 1L
    }

    // The `_debug` tag reports the two facts the response cannot show: what the union knows, and which branch
    // each entry reached. A filled-out entry looks the same whichever branch accepted it.
    "the debug tag says which branch each entry reached" {
        val client = TestHttpClient(cxt.instanceConfig)
        val response = client.sendEditRequest(
            fillOut,
            mapOf(EP.debug to GFX.explainEntries),
            mapOf<String, Any?>(
                GFX.entries to listOf(
                    entry("Known"),
                    mapOf(GE.traitId to "unknownHere", GE.data to emptyMap<String, Any?>()),
                ),
            ),
            isPut = false,
        ).rptResponseData?.jsonMap() ?: emptyMap()
        val explained = response.getValue(EP.meta).toJsonMapOrEmpty()
            .getValue(GFX.entriesExplained).toJsonMapOrEmpty()
        explained[GFX.knownTraits] shouldBe listOf(GT.name)
        explained[GFX.branches] shouldBe listOf(GT.name, GFX.default)
    }

    // --- the union itself ----------------------------------------------------

    // Manufactured after every component contributed, and an ordinary type by the time anything reads it.
    "the union is assembled from the traits that bind to form documents" {
        val union = cxt.getSchema().types[unionName].shouldNotBeNull()
        val variants = union.variants.shouldNotBeNull()
        variants.values shouldContainExactly listOf(GT.name)
        variants.discriminator shouldBe GE.traitId
        // A default branch, always -- see below for why.
        variants.defaultBranch.shouldNotBeNull()
    }

    // Strictness is a reader's choice rather than a second union: the same type accepts an unknown trait for a
    // caller that can see across clients and refuses one for a caller that cannot. The default honors the
    // document, because the emitted schema declares the default branch and our reading should not disagree
    // with what we publish.
    "the same union reads strictly or leniently, as the reader asks" {
        val union = cxt.getSchema().types.getValue(unionName)
        val unknown = mapOf<String, Any?>(GE.traitId to "notATraitThisNodeKnows", GE.data to emptyMap<String, Any?>())

        coerceAndValidate(union, unknown, SchOpts(forInput = true)).failures.shouldBeEmpty()

        val strict = coerceAndValidate(
            union,
            unknown,
            SchOpts(forInput = true, allowUnknownVariant = false),
        ).failures
        strict.map { it.path to it.code } shouldContainExactly listOf(GE.traitId to SchFailCode.invalidOption)
        // The refusal names what this reader does know, which is the actionable half.
        strict.first().options.shouldNotBeNull().map { it.value } shouldContainExactly listOf(GT.name)
    }
})
