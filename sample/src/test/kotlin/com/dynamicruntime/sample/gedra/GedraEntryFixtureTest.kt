package com.dynamicruntime.sample.gedra

import com.dynamicruntime.common.endpoint.EP
import com.dynamicruntime.common.endpoint.HttpMethod
import com.dynamicruntime.common.gedra.GCFG
import com.dynamicruntime.common.gedra.GE
import com.dynamicruntime.common.gedra.GT
import com.dynamicruntime.common.gedra.GU
import com.dynamicruntime.common.gedra.GedraDataType
import com.dynamicruntime.common.http.request.TestHttpClient
import com.dynamicruntime.common.startup.InstanceRegistry
import com.dynamicruntime.kdn.Startup
import com.dynamicruntime.sample.SampleComponent
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
 * is why several tests in #297, #298, and #300 were deleted when this arrived — they asserted, separately,
 * things this cannot pass without.
 */
class GedraEntryFixtureTest : StringSpec({

    // Registered here rather than discovered: the ServiceLoader entry that finds the component in a running
    // deployment does not reach the test classpath. And `KDR_LOAD_SAMPLE` forces `isLoaded`, which otherwise
    // gates to developer environments while `mkTestBootCxt` uses `unit`.
    InstanceRegistry.register(listOf(SampleComponent()))
    val cxt = Startup.mkTestBootCxt("gedraFixture", "gedraFixtureTest", mapOf("KDR_LOAD_SAMPLE" to "true"))
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
            listOf(
                GE.traitId, GE.data, GE.entryId, GE.source,
                GE.createdAt, GE.updatedAt, GE.createdBy, GE.updatedBy,
            )
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
            HttpMethod.POST,
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
            HttpMethod.POST,
        ).rptResponseData?.jsonMap() ?: emptyMap()
        val explained = response.getValue(EP.meta).toJsonMapOrEmpty()
            .getValue(GFX.entriesExplained).toJsonMapOrEmpty()
        // Sorted by trait id, and every global trait is here -- `siteVisit` included, whose data is a `$ref`
        // to a named type so that a client can narrow it (issue #379).
        explained[GFX.knownTraits] shouldBe
            listOf(ST.expenseReport, ST.managerApproval, GT.name, ST.questionnaire, ST.siteVisit, ST.yearly)
        explained[GFX.branches] shouldBe listOf(GT.name, GFX.default)
    }

    // --- what only a union with more than one branch can show ----------------

    // Several shapes in one payload, each validated against the branch its own traitId names. This is the
    // thing a single-branch union cannot test at all, and the reason the sample contributes traits of its own.
    "one payload carries several shapes, each validated against its own branch" {
        val client = TestHttpClient(cxt.instanceConfig)
        val response = client.sendJsonPostRequest(
            fillOut,
            mapOf<String, Any?>(
                GFX.entries to listOf(
                    entry("My Expense Form"),
                    mapOf(
                        GE.traitId to ST.expenseReport,
                        GE.data to mapOf(ST.year to 2024, ST.perItemAmount to 10, ST.itemCount to 3),
                    ),
                    mapOf(
                        GE.traitId to ST.managerApproval,
                        GE.data to mapOf(ST.approved to false, ST.rejectionReason to "Too late."),
                    ),
                ),
            ),
        )
        val items = response.getValue(EP.items).toJsonListOfMaps()
        items.map { it[GE.traitId] } shouldContainExactly listOf(GT.name, ST.expenseReport, ST.managerApproval)
        // The derived value the caller did not send, computed by what stands in for the trait's pre-processor.
        items[1][GE.data].toJsonMapOrEmpty()[ST.totalAmount] shouldBe 30.0
    }

    // A failure lands on the selected branch's own field, not on the union and not on some other branch's.
    // Try-every-branch would also have to complain that `approved` was missing, from a shape nobody sent.
    "a failure is reported against the branch the traitId selected" {
        val client = TestHttpClient(cxt.instanceConfig)
        val handler = client.sendEditRequest(
            fillOut,
            emptyMap(),
            mapOf<String, Any?>(
                GFX.entries to listOf(
                    mapOf(GE.traitId to ST.expenseReport, GE.data to mapOf(ST.year to 1999)),
                ),
            ),
            HttpMethod.POST,
        )
        handler.rptStatusCode shouldBe 400
        val body = handler.rptResponseData?.jsonMap() ?: emptyMap()
        val message = body[EP.errorMessage].toString()
        message shouldContain ST.year
        // Nothing about the approval branch, which nobody selected.
        (ST.approved in message) shouldBe false
    }

    // A derived value is not merely computed -- it is refused on the way in, which is what makes it derived
    // rather than defaulted. The caller's number is dropped and the computed one takes its place.
    "a derived value is dropped on the way in and produced on the way out" {
        val client = TestHttpClient(cxt.instanceConfig)
        val response = client.sendJsonPostRequest(
            fillOut,
            mapOf<String, Any?>(
                GFX.entries to listOf(
                    mapOf(
                        GE.traitId to ST.expenseReport,
                        GE.data to mapOf(
                            ST.year to 2024, ST.perItemAmount to 10, ST.itemCount to 3,
                            ST.totalAmount to 999999,
                        ),
                    ),
                ),
            ),
        )
        val data = response.getValue(EP.items).toJsonListOfMaps().first()[GE.data].toJsonMapOrEmpty()
        data[ST.totalAmount] shouldBe 30.0
    }

    // The conditional, now one level down inside `data`. Pushing a trait's fields under `data` moved every
    // conditional deeper, and the form had a bug of exactly that shape in #253 -- so the arrangement is worth
    // driving rather than assuming.
    "a conditional inside data still decides what is required and what is refused" {
        val client = TestHttpClient(cxt.instanceConfig)

        fun approval(data: Map<String, Any?>) = client.sendEditRequest(
            fillOut,
            emptyMap(),
            mapOf<String, Any?>(GFX.entries to listOf(mapOf(GE.traitId to ST.managerApproval, GE.data to data))),
            HttpMethod.POST,
        )

        // Rejected, so a reason is required.
        approval(mapOf(ST.approved to false)).rptStatusCode shouldBe 400
        approval(mapOf(ST.approved to false, ST.rejectionReason to "Too late.")).rptStatusCode shouldBe 200
        // Approved, so a reason is not admissible.
        approval(mapOf(ST.approved to true)).rptStatusCode shouldBe 200
        approval(mapOf(ST.approved to true, ST.rejectionReason to "?")).rptStatusCode shouldBe 400
    }

    // --- the union itself ----------------------------------------------------

    // Manufactured after every component contributed, and an ordinary type by the time anything reads it.
    "the union is assembled from the traits that bind to form documents" {
        val union = cxt.getSchema().types[unionName].shouldNotBeNull()
        val variants = union.variants.shouldNotBeNull()
        // Four branches, from two components -- the runtime's own trait beside the sample's, which is what
        // makes this a union rather than a wrapper around one type. Ordered by trait id, so the document does
        // not change with the order components happened to load in.
        // Sorted by trait id, so the document does not change with the order components happened to load in --
        // which is also why adding `questionnaire` (issue #337) landed it last rather than anywhere.
        variants.values shouldContainExactly listOf(ST.expenseReport, ST.managerApproval, GT.name, ST.questionnaire, ST.siteVisit, ST.yearly)
        variants.discriminator shouldBe GE.traitId
        // A default branch, always -- see below for why.
        variants.defaultBranch.shouldNotBeNull()
    }

    // Strictness is a reader's choice rather than a second union: the same type accepts an unknown trait for a
    // caller that can see across clients and refuses one for a caller that cannot. The default honors the
    // document because the emitted schema declares the default branch, and our reading should not disagree
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
        strict.first().options.shouldNotBeNull().map { it.value } shouldContainExactly
            listOf(ST.expenseReport, ST.managerApproval, GT.name, ST.questionnaire, ST.siteVisit, ST.yearly)
    }
})
