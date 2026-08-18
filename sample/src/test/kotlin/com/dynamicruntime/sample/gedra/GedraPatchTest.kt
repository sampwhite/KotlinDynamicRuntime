package com.dynamicruntime.sample.gedra

import io.kotest.matchers.string.shouldContain
import com.dynamicruntime.common.endpoint.EP
import com.dynamicruntime.common.context.CL
import com.dynamicruntime.common.gedra.GDF
import com.dynamicruntime.common.gedra.GE
import com.dynamicruntime.common.gedra.GED
import com.dynamicruntime.common.gedra.GEP
import com.dynamicruntime.common.gedra.GPF
import com.dynamicruntime.common.gedra.GT
import com.dynamicruntime.common.gedra.GedraDataType
import com.dynamicruntime.common.gedra.GedraEditAction
import com.dynamicruntime.common.startup.InstanceRegistry
import com.dynamicruntime.common.user.TestUser
import com.dynamicruntime.common.util.toJsonListOfMaps
import com.dynamicruntime.common.util.toJsonMapOrEmpty
import com.dynamicruntime.common.util.toOptStr
import com.dynamicruntime.kdn.Startup
import com.dynamicruntime.sample.SampleComponent
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import kotlin.time.Duration.Companion.seconds

/**
 * Changing stored entries: the patch, end to end (issue #337).
 *
 * It lives in `sample` rather than beside the other gedra endpoint tests for the reason #301's fixture moved
 * here: the interesting cases need a union with more than one branch and traits of more than one shape. Two
 * of them are only reachable with a trait whose fields are **all optional** — `questionnaire`, added for this
 * — because a merge sends a fragment, and a fragment cannot satisfy a `required` its page never asked about.
 *
 * A **flow test**: what one caller's edits look like to another is the point, and a per-test instance throws
 * exactly that away.
 */
class GedraPatchTest : StringSpec({

    // As in GedraEntryFixtureTest: the ServiceLoader entry that finds the component in a deployment does not
    // reach the test classpath, and `KDR_LOAD_SAMPLE` forces `isLoaded` past the developer-environment gate.
    InstanceRegistry.register(listOf(SampleComponent()))
    val cxt = Startup.mkTestBootCxt("gedraPatch", "gedraPatchTest", mapOf("KDR_LOAD_SAMPLE" to "true"))

    val alice = TestUser.create(cxt, "alice@patch.test")
    val bob = TestUser.create(cxt, "bob@patch.test")

    fun edit(action: GedraEditAction, traitId: String, data: Map<String, Any?>? = null): Map<String, Any?> =
        buildMap {
            put(GED.action, action.name)
            put(GE.traitId, traitId)
            if (data != null) put(GE.data, data)
        }

    /** A patch over form documents: one target per id, each with its own edits. */
    fun patch(vararg targets: Pair<String, List<Map<String, Any?>>>): Map<String, Any?> =
        mapOf(
            GPF.targets to mapOf(
                GedraDataType.formDoc.name to targets.map { (id, edits) ->
                    mapOf(GDF.gedraId to id, GPF.edits to edits)
                },
            ),
        )

    fun create(user: TestUser, vararg entries: Map<String, Any?>): String =
        user.postItem(GEP.formDocCreate, mapOf(GDF.entries to entries.toList()))[GDF.gedraId]
            .toOptStr().shouldNotBeNull()

    fun entriesOf(user: TestUser, gedraId: String): Map<String, Map<String, Any?>> =
        user.getItem(GEP.formDoc, mapOf(GDF.gedraId to gedraId))[GDF.entries].toJsonListOfMaps()
            .associateBy { it[GE.traitId].toOptStr().orEmpty() }

    fun nameEntry(name: String) = mapOf(GE.traitId to GT.name, GE.data to mapOf(GT.name to name))

    var docId = ""

    "a replace changes an entry and reports it applied" {
        docId = create(alice, nameEntry("Before"))
        val results = alice.postItems(
            GEP.patch,
            patch(docId to listOf(edit(GedraEditAction.addOrReplace, GT.name, mapOf(GT.name to "After")))),
        )
        results.single()[GDF.gedraId] shouldBe docId
        // Outcomes are keyed by trait rather than by position, which the one-entry-per-trait rule makes
        // unambiguous -- and which survives anything reordering the request.
        val outcome = results.single()[GPF.outcomes].toJsonListOfMaps().single()
        outcome[GE.traitId] shouldBe GT.name
        outcome[GPF.applied] shouldBe true
        entriesOf(alice, docId).getValue(GT.name)[GE.data].toJsonMapOrEmpty()[GT.name] shouldBe "After"
    }

    // The envelope is where a patch differs from a "create": what already existed keeps who made it and when,
    // and only the `updated` half moves. That is the whole reason both pairs are stored (issue #325).
    "an edited entry keeps its creation half and moves its updated half" {
        val before = entriesOf(alice, docId).getValue(GT.name)
        cxt.instanceConfig.clock.advanceBy(2.seconds)
        alice.postItems(
            GEP.patch,
            patch(docId to listOf(edit(GedraEditAction.addOrReplace, GT.name, mapOf(GT.name to "Again")))),
        )
        val after = entriesOf(alice, docId).getValue(GT.name)
        after[GE.entryId] shouldBe before[GE.entryId]
        after[GE.createdAt] shouldBe before[GE.createdAt]
        after[GE.createdBy] shouldBe before[GE.createdBy]
        after[GE.updatedAt] shouldNotBe before[GE.updatedAt]
    }

    // What `addOrMerge` is for: a page owns the answers it shows and says nothing about the rest.
    "a merge keeps the keys it did not mention" {
        val id = create(
            alice,
            mapOf(
                GE.traitId to ST.questionnaire,
                GE.data to mapOf(ST.topic to "Travel", ST.notes to "first pass"),
            ),
        )
        alice.postItems(
            GEP.patch,
            patch(id to listOf(edit(GedraEditAction.addOrMerge, ST.questionnaire, mapOf(ST.notes to "second pass")))),
        )
        val data = entriesOf(alice, id).getValue(ST.questionnaire)[GE.data].toJsonMapOrEmpty()
        data[ST.notes] shouldBe "second pass"
        // Untouched, where a "replace" would have dropped it. This is the difference between the two verbs.
        data[ST.topic] shouldBe "Travel"
    }

    // The reason the merged result is validated and not just the fragment: two halves that are each valid can
    // make one entry that is not. `{hasIssue: false}` says nothing wrong alone, and says something wrong when
    // it lands on a stored explanation.
    "a merge that would break a conditional is refused, and nothing is written" {
        val id = create(
            alice,
            mapOf(
                GE.traitId to ST.questionnaire,
                GE.data to mapOf(ST.hasIssue to true, ST.explanation to "late delivery"),
            ),
        )
        alice.expectError(
            400, GEP.patch,
            patch(id to listOf(edit(GedraEditAction.addOrMerge, ST.questionnaire, mapOf(ST.hasIssue to false)))),
        )
        val data = entriesOf(alice, id).getValue(ST.questionnaire)[GE.data].toJsonMapOrEmpty()
        data[ST.hasIssue] shouldBe true
        data[ST.explanation] shouldBe "late delivery"
    }

    "a delete removes an entry, and deleting it again is a no-op rather than an error" {
        val id = create(alice, nameEntry("Doomed"),
            mapOf(GE.traitId to ST.managerApproval, GE.data to mapOf(ST.approved to true))
        )

        alice.postItems(GEP.patch, patch(id to listOf(edit(GedraEditAction.deleteOrNoOp, GT.name))))
            .single()[GPF.outcomes].toJsonListOfMaps().single()[GPF.applied] shouldBe true
        entriesOf(alice, id).keys shouldContainExactly setOf(ST.managerApproval)

        // The other half of the verb's name: nothing there, nothing done, and not an error.
        alice.postItems(GEP.patch, patch(id to listOf(edit(GedraEditAction.deleteOrNoOp, GT.name))))
            .single()[GPF.outcomes].toJsonListOfMaps().single()[GPF.applied] shouldBe false
    }

    "an add creates the entry when the gedra carries none of that trait" {
        val id = create(alice, nameEntry("Solo"))
        alice.postItems(
            GEP.patch,
            patch(id to listOf(edit(GedraEditAction.addOrMerge, ST.expenseReport, mapOf(ST.year to 2025)))),
        )
        entriesOf(alice, id).keys shouldContainExactlyInAnyOrder setOf(GT.name, ST.expenseReport)
    }

    // Admission runs over every target before anything is written, so one unreachable target refuses the whole
    // call rather than leaving the reachable ones changed and the caller guessing which.
    "one unreachable target refuses the whole patch" {
        val mine = create(alice, nameEntry("Mine"))
        val theirs = create(bob, nameEntry("Theirs"))
        val change = listOf(edit(GedraEditAction.addOrReplace, GT.name, mapOf(GT.name to "Changed")))

        alice.expectError(404, GEP.patch, patch(mine to change, theirs to change))
        // Alice's own document is untouched, which is what admitting everything first buys.
        entriesOf(alice, mine).getValue(GT.name)[GE.data].toJsonMapOrEmpty()[GT.name] shouldBe "Mine"
    }

    "a target sent under the wrong kind is refused" {
        alice.expectError(
            400, GEP.patch,
            patch("gd.ud.public.u${alice.userId}" to listOf(edit(GedraEditAction.deleteOrNoOp, GT.name))),
        )
    }

    "two edits naming one trait are refused" {
        alice.expectError(
            400, GEP.patch,
            patch(
                docId to listOf(
                    edit(GedraEditAction.addOrReplace, GT.name, mapOf(GT.name to "One")),
                    edit(GedraEditAction.deleteOrNoOp, GT.name),
                ),
            ),
        )
    }

    // An unknown trait reaches the union's default branch and is stored as sent, which is what makes the
    // general endpoint usable by a client whose own traits this node never loaded.
    // Opt-in since #379, as on create: an unsupported trait is refused unless the call says otherwise.
    "an edit naming a trait this node does not know is carried through, when asked" {
        val id = create(alice, nameEntry("Host"))
        val unknown = patch(
            id to listOf(edit(GedraEditAction.addOrReplace, "aClientTraitNeverLoaded", mapOf("whatever" to 1))),
        )
        alice.expectError(400, GEP.patch, unknown)
        alice.postItems(GEP.patch, unknown + (GDF.allowAdditionalTraits to true))
        entriesOf(alice, id).getValue("aClientTraitNeverLoaded")[GE.data]
            .toJsonMapOrEmpty()["whatever"] shouldBe 1L
    }

    "a stale entryId is refused rather than overwriting whatever replaced it" {
        val id = create(alice, nameEntry("Current"))
        alice.expectError(
            400, GEP.patch,
            patch(
                id to listOf(
                    mapOf(
                        GED.action to GedraEditAction.addOrReplace.name,
                        GE.traitId to GT.name,
                        GE.entryId to "anEntryIdFromSomeOlderCopy",
                        GE.data to mapOf(GT.name to "Overwritten"),
                    ),
                ),
            ),
        )
        entriesOf(alice, id).getValue(GT.name)[GE.data].toJsonMapOrEmpty()[GT.name] shouldBe "Current"
    }

    // A client's schema belongs to where the data lives, so a patch spanning two clients would apply two sets
    // of rules inside one transaction (issue #356). Refused before anything is read, which is why the second
    // id here need not exist -- the span is decidable from the ids alone.
    "a patch targets one client at a time" {
        val id = create(alice, nameEntry("Mine"))
        val elsewhere = id.replace(".${CL.public}.", ".${CL.hub}.")
        val envelope = alice.expectError(
            400, GEP.patch,
            patch(
                id to listOf(edit(GedraEditAction.addOrReplace, GT.name, mapOf(GT.name to "Changed"))),
                elsewhere to listOf(edit(GedraEditAction.addOrReplace, GT.name, mapOf(GT.name to "Theirs"))),
            ),
        )
        envelope[EP.errorMessage].toOptStr()!! shouldContain "one client at a time"
        // Nothing was applied: the refusal precedes the reads, so the patch is not half-done.
        entriesOf(alice, id).getValue(GT.name)[GE.data].toJsonMapOrEmpty()[GT.name] shouldBe "Mine"
    }
})
