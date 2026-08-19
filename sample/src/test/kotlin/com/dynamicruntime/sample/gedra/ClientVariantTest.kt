package com.dynamicruntime.sample.gedra

import io.kotest.matchers.collections.shouldNotContain
import io.kotest.matchers.collections.shouldContain
import com.dynamicruntime.common.startup.SS
import com.dynamicruntime.common.schema.SCH
import com.dynamicruntime.common.endpoint.EI
import com.dynamicruntime.common.endpoint.clientPath
import com.dynamicruntime.common.startup.SchemaService
import com.dynamicruntime.common.gedra.GU
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

    // --- every kind that carries entries, not just form documents ------------------
    //
    // Schema-level, because nothing edits workflow data through an endpoint yet -- the workflow engine writes
    // those entries itself. That is exactly why the per-client pass could miss the kind entirely (#390) and
    // nothing complained: the global pass and the per-client pass kept separate lists of kinds, and only the
    // shared one gained `wfData`.
    //
    // `name` is the trait that makes this observable: it binds to **both** kinds, and acme never included it.

    "a client's narrowing reaches workflow data, not only form documents" {
        val service = (SchemaService.get(cxt) ?: error("SchemaService required")).also { it.checkInit(cxt) }
        GU.entryKinds.forEach { kind ->
            val unionName = "globalconfig." + GU.unionName(kind)
            // Global carries `name` on both kinds...
            service.storeFor(null).types.getValue(unionName).variants
                .shouldNotBeNull().isKnown(GT.name) shouldBe true
            // ...and acme, which named its traits one at a time and left `name` out, carries it on neither.
            val theirs = service.storeFor(SC.acme).types.getValue(unionName)
            (theirs.variants?.isKnown(GT.name) ?: false) shouldBe false
        }
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

    // --- the same narrowing, on a patch ------------------------------------------
    //
    // Create and patch are separate write paths, and #379 found they had drifted -- create was not
    // validating against the variant at all. So the narrowing is proven on both rather than assumed to carry
    // across, which is what #342 asked for in as many words.

    /** A patch of one gedra: replace the entry of [traitId] with [data]. */
    fun replace(id: String, traitId: String, data: Map<String, Any?>): Map<String, Any?> = mapOf(
        GPF.targets to mapOf(
            GedraDataType.formDoc.name to listOf(
                mapOf(
                    GDF.gedraId to id,
                    GPF.edits to listOf(
                        mapOf(
                            GED.action to GedraEditAction.addOrReplace.name,
                            GE.traitId to traitId,
                            GE.data to data,
                        ),
                    ),
                ),
            ),
        ),
    )

    "a patch is held to the client's variant, exactly as a create is" {
        // Each client edits a document of its own, so what is being compared is the rule and not the row.
        val acmeDoc = create(acme, visit("gb"))
        val globexDoc = create(globex, visit("gb"))
        val toRemoved = mapOf(ST.address to mapOf(ST.country to "fr"))

        // globex takes every global country, so the same edit lands.
        globex.postItems(GEP.patch, replace(globexDoc, ST.siteVisit, toRemoved))
        // acme narrowed `SiteAddress`, so the edit is refused -- and the stored entry is untouched, since a
        // patch that fails validates before it writes.
        acme.expectError(EXC.badInput, GEP.patch, replace(acmeDoc, ST.siteVisit, toRemoved))
        val stored = acme.getItem(GEP.formDoc, mapOf(GDF.gedraId to acmeDoc))[GDF.entries]
            .toJsonListOfMaps().single()
        stored[GE.data].toJsonMapOrEmpty()[ST.address].toJsonMapOrEmpty()[ST.country] shouldBe "gb"
    }

    "a patch of a trait acme narrowed is refused on the narrowed field" {
        val doc = create(acme, questionnaire(mapOf(ST.topic to SC.acmeTopics.first())))
        acme.expectError(EXC.badInput, GEP.patch, replace(doc, ST.questionnaire, mapOf(ST.topic to "shipping")))
        // ...and a value acme kept goes through, which is what shows the refusal was the value and not the call.
        acme.postItems(GEP.patch, replace(doc, ST.questionnaire, mapOf(ST.topic to SC.acmeTopics.last())))
    }

    // --- a trait the client does not support ------------------------------------
    //
    // Since the write path holds a call to the client's supported set by default (#379), omitting a trait is
    // a **fence** rather than a note: acme leaves `managerApproval` out, so acme's users cannot store one
    // without saying they mean to. That is what `includedTraits` was always meant to say, now true on the
    // shared surface rather than only on the client-specific one #387 will add.

    "a trait acme omitted is refused for acme and accepted for a client that includes it" {
        val entry = mapOf(GE.traitId to ST.managerApproval, GE.data to mapOf(ST.approved to true))
        // globex takes `#allGlobal`, so it supports the trait and stores it.
        create(globex, entry).shouldNotBeNull()
        // acme named its traits one at a time and left this one out.
        refused(acme, entry) shouldContainIgnoringCase ST.managerApproval
    }

    "the refusal is a default, not a wall" {
        val entry = mapOf(GE.traitId to ST.managerApproval, GE.data to mapOf(ST.approved to true))
        val id = acme.postItem(
            GEP.formDocCreate,
            mapOf(GDF.allowAdditionalTraits to true, GDF.entries to listOf(entry)),
        )[GDF.gedraId].toOptStr().shouldNotBeNull()
        // Stored, and carried on acme's own default branch once there, since acme's union cannot name it.
        val stored = acme.getItem(GEP.formDoc, mapOf(GDF.gedraId to id))[GDF.entries].toJsonListOfMaps().single()
        stored[GE.traitId] shouldBe ST.managerApproval
    }

    // --- a client's own trait ----------------------------------------------------

    "acme's own trait is validated for acme and carried for everybody else" {
        val bad = mapOf(GE.traitId to SC.siteAudit, GE.data to mapOf(SC.findings to "no auditor named"))
        // Acme declared it, so acme is held to it: `auditor` is required.
        refused(acme, bad) shouldContainIgnoringCase SC.auditor
        // Nobody else has heard of it, so for them it is an unsupported trait -- refused by default and
        // carried untouched on the default branch when they say they mean it.
        refused(everyone, bad).shouldNotBeNull()
        everyone.postItem(
            GEP.formDocCreate,
            mapOf(GDF.allowAdditionalTraits to true, GDF.entries to listOf(bad)),
        )[GDF.gedraId].toOptStr().shouldNotBeNull()
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

    "a trait nobody declared is refused by default, on create" {
        refused(everyone, mapOf(GE.traitId to "inventedByHand", GE.data to mapOf("anything" to 42L)))
            .shouldNotBeNull()
    }

    "a trait nobody declared is stored as supplied when asked, on create" {
        val id = everyone.postItem(
            GEP.formDocCreate,
            mapOf(
                GDF.allowAdditionalTraits to true,
                GDF.entries to listOf(mapOf(GE.traitId to "inventedByHand", GE.data to mapOf("anything" to 42L))),
            ),
        )[GDF.gedraId].toOptStr().shouldNotBeNull()
        val stored = everyone.getItem(GEP.formDoc, mapOf(GDF.gedraId to id))[GDF.entries]
            .toJsonListOfMaps().single()
        stored[GE.traitId] shouldBe "inventedByHand"
        stored[GE.data].toJsonMapOrEmpty()["anything"] shouldBe 42L
    }

    "a trait nobody declared is stored as supplied when asked, on patch too" {
        val id = create(everyone, mapOf(GE.traitId to GT.name, GE.data to mapOf(GT.name to "Doc")))
        everyone.postItems(
            GEP.patch,
            mapOf(
                GDF.allowAdditionalTraits to true,
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

    // --- a client's own endpoints (#387) -------------------------------------------
    //
    // The shared path must publish global types, so its form offers what global offers and the narrowing is
    // enforced only once the entry reaches storage. A path that names one client has one answer to "whose
    // schema?", so it can be strict at the edge -- advertised and enforced become the same thing.

    "a client's endpoints exist at their own paths" {
        val schema = cxt.getSchema()
        schema.endpoints["${clientPath(GEP.formDocCreate, SC.acme)}:POST"].shouldNotBeNull()
            .client shouldBe SC.acme
        // The shared surface is untouched, which is what keeps anonymous and `public` callers working.
        schema.endpoints["${GEP.formDocCreate}:POST"].shouldNotBeNull().client shouldBe null
    }

    "a client's endpoint refuses at the edge what its variant forbids" {
        // The same payload the shared path accepts and only refuses at storage.
        val body = mapOf(GDF.entries to listOf(visit("fr")))
        acme.expectError(EXC.badInput, clientPath(GEP.formDocCreate, SC.acme), body)
        // ...and a country acme kept goes through on its own path, so the refusal is the value not the path.
        acme.postItem(clientPath(GEP.formDocCreate, SC.acme), mapOf(GDF.entries to listOf(visit("gb"))))[GDF.gedraId]
            .toOptStr().shouldNotBeNull()
    }

    // The path is the statement of which client is meant, so the handler runs bound to it -- which is what
    // turns #356's "the variant follows the data's client" from a resolution into a comparison.
    "a client's endpoint stores into that client" {
        val id = acme.postItem(
            clientPath(GEP.formDocCreate, SC.acme),
            mapOf(GDF.entries to listOf(visit("gb"))),
        )[GDF.gedraId].toOptStr().shouldNotBeNull()
        id.contains(".${SC.acme}.") shouldBe true
    }

    // --- the catalog, per client (#387) --------------------------------------------

    /** The endpoint paths the catalog shows [user], optionally for a named [client]. */
    fun catalogPaths(user: TestUser, client: String? = null): List<String> =
        user.getData("/schema/endpoints", buildMap { client?.let { put(SS.client, it) } })[EI.endpoints]
            .toJsonListOfMaps().mapNotNull { it[EI.path].toOptStr() }

    "a client's people are shown their own surface in place of the shared one" {
        val paths = catalogPaths(acme)
        paths.shouldContain(clientPath(GEP.formDocCreate, SC.acme))
        // Replaced, not added: one `$defs` bag cannot hold two meanings of `gedra.FormDoc`.
        paths.shouldNotContain(GEP.formDocCreate)
        // ...while everything with no client version stays, because those are not client-shaped.
        paths.shouldContain("/auth/self/info")
    }

    "a caller with no client surface still sees the shared one" {
        val paths = catalogPaths(everyone)
        paths.shouldContain(GEP.formDocCreate)
        // A client's endpoints are not advertised to somebody who cannot use them.
        paths.shouldNotContain(clientPath(GEP.formDocCreate, SC.acme))
    }

    // The picker: an admin says which client they are looking at rather than having it inferred.
    "an allClients admin can ask for a named client's surface" {
        val admin = TestUser.createFullAdmin(cxt, "catalog-admin@example.com")
        val acmePaths = catalogPaths(admin, SC.acme)
        acmePaths.shouldContain(clientPath(GEP.formDocCreate, SC.acme))
        catalogPaths(admin, SC.globex).shouldContain(clientPath(GEP.formDocCreate, SC.globex))
        // Each answer is that client's alone...
        acmePaths.shouldNotContain(clientPath(GEP.formDocCreate, SC.globex))
        // ...and *only* that client's, so asking to see `acme` does not answer with the whole application
        // beside it. Somebody who asked the narrow question should not need a regex to get back to it.
        acmePaths.shouldNotContain("/auth/self/info")
        acmePaths.all { it.contains("/${SC.acme}/") } shouldBe true
    }

    "naming somebody else's client takes the capability" {
        acme.expectError(EXC.badInput, "/schema/endpoints", args = mapOf(SS.client to SC.globex))
        // Naming your own is always allowed: it is what you would have been shown anyway.
        catalogPaths(acme, SC.acme).shouldContain(clientPath(GEP.formDocCreate, SC.acme))
    }

    // The point of the whole exercise: what a form is built from is the client's schema, so a control cannot
    // offer what the client removed -- rather than the shared surface's, which offers it and refuses later.
    "the advertised schema is the client's own" {
        val admin = TestUser.createFullAdmin(cxt, "catalog-schema@example.com")
        fun countriesFor(client: String): List<String> {
            val defs = admin.getData("/schema/endpoints", mapOf(SS.client to client))[SCH.dDefs].toJsonMapOrEmpty()
            val address = defs["${ST.namespace}.${ST.siteAddress}"].toJsonMapOrEmpty()
            return address[SCH.properties].toJsonMapOrEmpty()[ST.country].toJsonMapOrEmpty()[SCH.options]
                .toJsonListOfMaps().mapNotNull { it[SCH.value].toOptStr() }
        }
        countriesFor(SC.globex) shouldBe ST.countries
        countriesFor(SC.acme) shouldBe SC.acmeCountries
    }

    // The promise a client endpoint makes: the path says which client, so a gedra belonging to another is
    // refused there -- whatever the caller's reach. Scope stops most cross-client access already, but scope
    // is about who is asking; this is about where the request was addressed, and the two stop agreeing
    // exactly for the caller whose scope is wide enough not to be stopped.
    "a client's endpoint refuses a gedra belonging to another client" {
        val admin = TestUser.createFullAdmin(cxt, "confine-admin@example.com")
        val elsewhere = create(globex, visit("gb"))

        // The same admin reaches it on the shared surface, which is unchanged...
        admin.getItem(GEP.formDoc, mapOf(GDF.gedraId to elsewhere))[GDF.gedraId].toOptStr() shouldBe elsewhere
        // ...and is refused on acme's, because the path already said which client this is for.
        val message = admin.expectError(
            EXC.badInput,
            clientPath(GEP.formDoc, SC.acme),
            args = mapOf(GDF.gedraId to elsewhere),
        )[EP.errorMessage].toOptStr().orEmpty()
        message shouldContainIgnoringCase SC.globex
        message shouldContainIgnoringCase SC.acme
    }

    "a client's endpoint reaches its own client's gedras" {
        val mine = create(acme, visit("gb"))
        acme.getItem(clientPath(GEP.formDoc, SC.acme), mapOf(GDF.gedraId to mine))[GDF.gedraId]
            .toOptStr() shouldBe mine
    }
})

/** Case-insensitive containment, so an assertion does not depend on how a message happens to be capitalized. */
private infix fun String.shouldContainIgnoringCase(part: String): String {
    if (!contains(part, ignoreCase = true)) {
        throw AssertionError("Expected the message to mention '$part', but it was: $this")
    }
    return this
}
