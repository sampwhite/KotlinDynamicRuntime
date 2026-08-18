package com.dynamicruntime.sample.gedra

import com.dynamicruntime.common.context.CL
import com.dynamicruntime.common.endpoint.EP
import com.dynamicruntime.common.exception.EXC
import com.dynamicruntime.common.gedra.GDF
import com.dynamicruntime.common.gedra.GedraEditAction
import com.dynamicruntime.common.gedra.GedraDataType
import com.dynamicruntime.common.gedra.GT
import com.dynamicruntime.common.gedra.GPF
import com.dynamicruntime.common.gedra.GED
import com.dynamicruntime.common.gedra.GE
import com.dynamicruntime.common.gedra.GEP
import com.dynamicruntime.common.startup.InstanceRegistry
import com.dynamicruntime.common.user.TestUser
import com.dynamicruntime.common.util.toJsonListOfMaps
import com.dynamicruntime.common.util.toJsonMapOrEmpty
import com.dynamicruntime.common.util.toOptStr
import com.dynamicruntime.kdn.Startup
import com.dynamicruntime.sample.SampleComponent
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe

/**
 * A client's schema variant, doing its job end to end (issue #379).
 *
 * This is the coverage #356 built the machinery for and could not write: it needed clients that narrow
 * something (#379) and users who belong to them (#352). Every assertion here is a **pair** -- the same entry
 * offered by two clients -- because a refusal on its own proves nothing about where it came from. One client
 * refusing what another accepts is what shows the variant is the thing deciding.
 *
 * A flow test: the users and their clients are the setup, and what one client's rules do to a payload the
 * other accepts is the whole subject.
 */
class ClientVariantTest : StringSpec({

    InstanceRegistry.register(listOf(SampleComponent()))
    val cxt = Startup.mkTestBootCxt("clientVariant", "clientVariantTest", mapOf("KDR_LOAD_SAMPLE" to "true"))

    // A user per client. `acme` narrows; `globex` takes everything global offers; `public` varies nothing at
    // all, so it is the control that shows a payload is globally valid to begin with.
    val acme = TestUser.create(cxt, "user@acme.test", userClient = SC.acme)
    val globex = TestUser.create(cxt, "user@globex.test", userClient = SC.globex)
    val everyone = TestUser.create(cxt, "user@public.test")

    fun create(user: TestUser, vararg entries: Map<String, Any?>): String =
        user.postItem(GEP.formDocCreate, mapOf(GDF.entries to entries.toList()))[GDF.gedraId]
            .toOptStr().shouldNotBeNull()

    fun refused(user: TestUser, vararg entries: Map<String, Any?>): String =
        user.expectError(EXC.badInput, GEP.formDocCreate, mapOf(GDF.entries to entries.toList()))[EP.errorMessage]
            .toOptStr().orEmpty()

    fun visit(country: String) = mapOf(
        GE.traitId to ST.siteVisit,
        GE.data to mapOf(ST.address to mapOf(ST.country to country)),
    )

    fun questionnaire(data: Map<String, Any?>) = mapOf(GE.traitId to ST.questionnaire, GE.data to data)

    "the users are in the clients they were created in" {
        acme.selfClient() shouldBe SC.acme
        globex.selfClient() shouldBe SC.globex
        everyone.selfClient() shouldBe CL.public
    }

    // --- an interior type, narrowed --------------------------------------------
    //
    // `SiteVisitEntry` is untouched by acme and does not know: its data refers to `SiteAddress` by name, and
    // for acme that name resolves to a version admitting two countries instead of four. This is the case
    // #342 said could not be faked with namespacing.

    "a country acme removed is refused for acme and accepted for everybody else" {
        create(globex, visit("fr")).shouldNotBeNull()
        create(everyone, visit("fr")).shouldNotBeNull()
        refused(acme, visit("fr")) shouldContainIgnoringCase "fr"
    }

    "a country acme kept is accepted for acme too" {
        create(acme, visit("gb")).shouldNotBeNull()
    }

    // --- a trait, narrowed -----------------------------------------------------
    //
    // Reached through the trait's data type, which is a type of its own since #379. Global offers free text
    // for `topic`; acme offers a choice of two.

    "a topic outside acme's list is refused for acme and accepted for everybody else" {
        val entry = questionnaire(mapOf(ST.topic to "shipping"))
        create(everyone, entry).shouldNotBeNull()
        refused(acme, entry).shouldNotBeNull()
        create(acme, questionnaire(mapOf(ST.topic to SC.acmeTopics.first()))).shouldNotBeNull()
    }

    // Dropping a property closes the door on it: the data type declares properties, so it admits no others.
    "a property acme dropped is refused for acme and accepted for everybody else" {
        val entry = questionnaire(mapOf(ST.notes to "worth writing down"))
        create(everyone, entry).shouldNotBeNull()
        refused(acme, entry) shouldContainIgnoringCase ST.notes
    }

    // --- a trait the client does not support ------------------------------------
    //
    // Narrower than it sounds, and worth pinning down because the obvious reading is wrong. `acme` omits
    // `managerApproval`, so acme's union has no branch for it -- but the **edge** validates against the
    // *global* union (case (a): the published input type stays global), and global knows that trait
    // perfectly well. So the edge decides, and acme's omission changes nothing about what acme can store
    // through the generic endpoints.
    //
    // Omission bites where the global reader is also ignorant -- a client's own trait, seen by anybody else
    // -- which the next block covers. Until per-client endpoints publish a client's own union, `includedTraits`
    // is a statement about forms and about what a *client-specific* surface would enforce, and not a fence
    // around the shared one.

    "omitting a trait does not change what can be stored through the generic endpoints" {
        // Invalid against the global trait, so the edge refuses it -- for acme exactly as for anybody else,
        // despite acme not supporting the trait at all.
        val bad = mapOf(GE.traitId to ST.managerApproval, GE.data to mapOf(ST.decidedBy to "nobody"))
        refused(everyone, bad).shouldNotBeNull()
        refused(acme, bad).shouldNotBeNull()

        // Valid against the global trait, so it is stored -- again for acme too, and carried on acme's own
        // default branch once there, since acme's union cannot recognize it.
        val good = mapOf(GE.traitId to ST.managerApproval, GE.data to mapOf(ST.approved to true))
        val id = create(acme, good)
        val stored = acme.getItem(GEP.formDoc, mapOf(GDF.gedraId to id))[GDF.entries].toJsonListOfMaps().single()
        stored[GE.traitId] shouldBe ST.managerApproval
        stored[GE.data].toJsonMapOrEmpty()[ST.approved] shouldBe true
    }

    // --- a client's own trait ----------------------------------------------------

    "acme's own trait is validated for acme and carried for everybody else" {
        val bad = mapOf(GE.traitId to SC.siteAudit, GE.data to mapOf(SC.findings to "no auditor named"))
        // Acme declared it, so acme is held to it: `auditor` is required.
        refused(acme, bad) shouldContainIgnoringCase SC.auditor
        // Nobody else has heard of it, so it rides the default branch untouched.
        create(everyone, bad).shouldNotBeNull()
        create(acme, mapOf(GE.traitId to SC.siteAudit, GE.data to mapOf(SC.auditor to "Nia"))).shouldNotBeNull()
    }

    // --- a trait nobody has heard of ---------------------------------------------
    //
    // The direct answer to "can I make up a trait?": yes, on both calls, for any caller whose client does not
    // know it either. Both unions declare an **open** default branch (#301), so an entry whose `traitId`
    // selects no branch is carried as supplied rather than refused -- deliberately, because trait definitions
    // are authored by people who are not us and a client's config may not be loaded on this node.
    //
    // So the generic endpoints are an arbitrary-JSON channel, bounded by scope rather than by schema. #387 is
    // what closes it for a client that wants it closed: an endpoint naming one client can be strict at the
    // edge, because it has one answer to whose schema applies.

    "a trait nobody declared is stored as supplied, on create" {
        val id = create(everyone, mapOf(GE.traitId to "inventedByHand", GE.data to mapOf("anything" to 42L)))
        val stored = everyone.getItem(GEP.formDoc, mapOf(GDF.gedraId to id))[GDF.entries]
            .toJsonListOfMaps().single()
        stored[GE.traitId] shouldBe "inventedByHand"
        stored[GE.data].toJsonMapOrEmpty()["anything"] shouldBe 42L
    }

    "a trait nobody declared is stored as supplied, on patch too" {
        val id = create(everyone, mapOf(GE.traitId to GT.name, GE.data to mapOf(GT.name to "Doc")))
        everyone.postItems(
            GEP.patch,
            mapOf(
                GPF.targets to mapOf(
                    GedraDataType.formDoc.name to listOf(
                        mapOf(
                            GDF.gedraId to id,
                            GPF.edits to listOf(
                                mapOf(
                                    GED.action to GedraEditAction.addOrReplace.name,
                                    GE.traitId to "alsoInvented",
                                    GE.data to mapOf("shape" to "unknown"),
                                ),
                            ),
                        ),
                    ),
                ),
            ),
        )
        val entries = everyone.getItem(GEP.formDoc, mapOf(GDF.gedraId to id))[GDF.entries].toJsonListOfMaps()
        entries.single { it[GE.traitId] == "alsoInvented" }[GE.data]
            .toJsonMapOrEmpty()["shape"] shouldBe "unknown"
    }
})

/** Case-insensitive containment, so an assertion does not depend on how a message happens to be capitalized. */
private infix fun String.shouldContainIgnoringCase(part: String): String {
    if (!contains(part, ignoreCase = true)) {
        throw AssertionError("Expected the message to mention '$part', but it was: $this")
    }
    return this
}
