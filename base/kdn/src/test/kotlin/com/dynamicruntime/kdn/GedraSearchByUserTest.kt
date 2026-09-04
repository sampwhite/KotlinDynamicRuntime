package com.dynamicruntime.kdn

import com.dynamicruntime.common.cfact.CFACTS
import com.dynamicruntime.common.endpoint.EI
import com.dynamicruntime.common.gedra.GDF
import com.dynamicruntime.common.gedra.GE
import com.dynamicruntime.common.gedra.GEP
import com.dynamicruntime.common.gedra.GT
import kotlin.time.Duration.Companion.seconds
import com.dynamicruntime.common.gedra.GedraEditAction
import com.dynamicruntime.common.gedra.GedraDataType
import com.dynamicruntime.common.gedra.GPF
import com.dynamicruntime.common.gedra.GED
import com.dynamicruntime.common.http.request.ROLE
import com.dynamicruntime.common.schema.SCH
import com.dynamicruntime.common.user.TestUser
import com.dynamicruntime.common.util.toJsonListOfMaps
import com.dynamicruntime.common.util.toJsonMapOrEmpty
import com.dynamicruntime.common.util.toOptStr
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldContainAll
import io.kotest.matchers.collections.shouldNotContain
import io.kotest.matchers.shouldBe

/**
 * The `user` search parameter on `GET /gedra/formDocs` (issue #545): confining the listing to one user by
 * userId or email, the scope that keeps an ordinary caller to themselves, and the `g-visibleWhen` gate that
 * shows the selector only to an administrator.
 *
 * A **flow test**, like [GedraDataEndpointTest] and for the same reason: alice's document in the first block is
 * the row the admin filters to and the row bob may not reach, so the users and their documents are shared state
 * built up across the blocks, which run in declaration order.
 */
class GedraSearchByUserTest : StringSpec({
    val cxt = Startup.mkTestBootCxt("gedraSearchUser", "gedraSearchUserTest")

    val aliceEmail = "alice@search.test"
    val bobEmail = "bob@search.test"
    val alice = TestUser.create(cxt, aliceEmail)
    val bob = TestUser.create(cxt, bobEmail)
    // A scoped administrator -- ROLE.admin without allClients -- who reaches their whole client, which is where
    // the `user` filter earns its keep.
    val ada = TestUser.create(cxt, "ada@search.test", level = ROLE.admin)

    fun nameEntry(name: String): Map<String, Any?> =
        mapOf(GE.traitId to GT.name, GE.data to mapOf(GT.name to name))

    fun idsSeenBy(tu: TestUser, args: Map<String, Any?>? = null): List<String?> =
        tu.getItems(GEP.formDocs, args).map { it[GDF.gedraId].toOptStr() }

    /** The `properties` of the `GET /gedra/formDocs` input schema as this caller's catalog renders it. */
    fun formDocsInputProps(tu: TestUser): Map<String, Any?> {
        val eps = tu.getData("/schema/endpoints")[EI.endpoints].toJsonListOfMaps()
        val formDocs = eps.first { it[EI.path].toOptStr()?.endsWith("/formDocs") == true && it[EI.method] == "GET" }
        return formDocs[EI.inputSchema].toJsonMapOrEmpty()[SCH.properties].toJsonMapOrEmpty()
    }

    /** The frontend-delivered cfacts on this caller's catalog response (issue #564): name -> present. */
    fun deliveredCfacts(tu: TestUser): Map<String, Any?> =
        tu.getData("/schema/endpoints")[EI.cfacts].toJsonMapOrEmpty()

    var aliceDocId = ""
    var bobDocId = ""

    "each user creates a form document of their own" {
        aliceDocId = alice.postItem(GEP.formDocCreate, mapOf(GDF.entries to listOf(nameEntry("Alice doc"))))[GDF.gedraId].toOptStr() ?: ""
        bobDocId = bob.postItem(GEP.formDocCreate, mapOf(GDF.entries to listOf(nameEntry("Bob doc"))))[GDF.gedraId].toOptStr() ?: ""
        (aliceDocId.isNotEmpty() && bobDocId.isNotEmpty()) shouldBe true
    }

    "an admin confines the listing to one user, by email or by id" {
        // By email.
        idsSeenBy(ada, mapOf(EI.user to aliceEmail)) shouldBe listOf(aliceDocId)
        // By numeric userId -- the two-way support (the same document).
        idsSeenBy(ada, mapOf(EI.user to alice.userId.toString())) shouldBe listOf(aliceDocId)
        // Bob's user narrows to bob's document.
        idsSeenBy(ada, mapOf(EI.user to bobEmail)) shouldBe listOf(bobDocId)
    }

    "an admin with no user parameter sees the whole client" {
        idsSeenBy(ada) shouldContainAll listOf(aliceDocId, bobDocId)
    }

    "an ordinary user sees only their own documents, with or without the parameter" {
        // No parameter: own rows only.
        idsSeenBy(bob).let {
            it shouldContainAll listOf(bobDocId)
            it shouldNotContain aliceDocId
        }
        // Naming themselves is allowed and changes nothing.
        idsSeenBy(bob, mapOf(EI.user to bobEmail)) shouldBe listOf(bobDocId)
    }

    "an ordinary user naming another user is refused, without revealing whether they exist" {
        // Out of bob's scope: a 400 that reads the same whether alice exists or not.
        bob.client.sendGetRequest(GEP.formDocs, mapOf(EI.user to aliceEmail)).rptStatusCode shouldBe 400
        // A userId out of scope is refused the same way.
        bob.client.sendGetRequest(GEP.formDocs, mapOf(EI.user to alice.userId.toString())).rptStatusCode shouldBe 400
    }

    "the user selector carries g-visibleWhen for every caller; the delivered cfacts decide who shows it (#564)" {
        // The served schema is caller-independent now (issue #564): both callers get the field with the
        // keyword intact, so the one document can double as published documentation. The frontend hides it.
        formDocsInputProps(ada).containsKey(EI.user) shouldBe true
        formDocsInputProps(bob).containsKey(EI.user) shouldBe true
        formDocsInputProps(ada)[EI.user].toJsonMapOrEmpty()[SCH.visibleWhen] shouldBe CFACTS.hasAdminLevel
        formDocsInputProps(bob)[EI.user].toJsonMapOrEmpty()[SCH.visibleWhen] shouldBe CFACTS.hasAdminLevel
        // What differs is the delivered cfacts the client evaluates against: the admin has hasAdminLevel, the
        // ordinary user does not -- so the client shows the field to one and hides it from the other. The
        // handler enforces scope regardless (the 400s above).
        deliveredCfacts(ada)[CFACTS.hasAdminLevel] shouldBe true
        deliveredCfacts(bob)[CFACTS.hasAdminLevel] shouldBe false
    }
    // The User column's data (issue #562): an admin's listed rows carry the owner's name and email; an ordinary
    // caller's do not -- their rows are all their own, so the column is not drawn and nothing is looked up.
    "listed rows carry the owner's name and email for an admin, and not for an ordinary user" {
        val adaRows = ada.getItems(GEP.formDocs)
        val aliceRow = adaRows.first { it[GDF.gedraId] == aliceDocId }
        // No name set on the provisioned account, so the display name falls back to the email (the public name).
        aliceRow[GDF.ownerEmail] shouldBe aliceEmail
        aliceRow[GDF.ownerName] shouldBe aliceEmail
        val bobRows = bob.getItems(GEP.formDocs)
        bobRows.first { it[GDF.gedraId] == bobDocId }.containsKey(GDF.ownerName) shouldBe false
        bobRows.first { it[GDF.gedraId] == bobDocId }.containsKey(GDF.ownerEmail) shouldBe false
    }

    // The default order (issue #562): most recently *written* first, so an edit to an older document brings it
    // to the top -- where `createdAt` order would leave it where it was.
    "the list is ordered by updatedAt descending, so an edited older document moves to the top" {
        // Bob's document was created after alice's; alice's is therefore older by creation.
        ada.getItems(GEP.formDocs).map { it[GDF.gedraId] }.take(2) shouldBe listOf(bobDocId, aliceDocId)
        // Step the clock, then patch alice's document -- a write, which bumps its updatedAt past bob's.
        cxt.instanceConfig.clock.advanceBy(1.seconds)
        ada.postItems(
            GEP.patch,
            mapOf(
                GPF.targets to mapOf(
                    GedraDataType.formDoc.name to listOf(
                        mapOf(
                            GDF.gedraId to aliceDocId,
                            GPF.edits to listOf(
                                mapOf(GED.action to GedraEditAction.addOrMerge.name, GE.traitId to GT.name, GE.data to mapOf(GT.name to "Alice edited")),
                            ),
                        ),
                    ),
                ),
            ),
        )
        // Alice's now leads: written most recently, though created earlier.
        ada.getItems(GEP.formDocs).map { it[GDF.gedraId] }.take(2) shouldBe listOf(aliceDocId, bobDocId)
    }
})
