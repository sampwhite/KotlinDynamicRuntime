package com.dynamicruntime.kdn

import com.dynamicruntime.common.cfact.CFACTS
import com.dynamicruntime.common.endpoint.EI
import com.dynamicruntime.common.endpoint.EP
import com.dynamicruntime.common.endpoint.HttpMethod
import com.dynamicruntime.common.gedra.GDF
import com.dynamicruntime.common.gedra.GE
import com.dynamicruntime.common.gedra.GEP
import com.dynamicruntime.common.gedra.GIF
import com.dynamicruntime.common.gedra.GSRC
import com.dynamicruntime.common.gedra.GT
import com.dynamicruntime.common.http.request.ROLE
import com.dynamicruntime.common.schema.SCH
import com.dynamicruntime.common.user.ENVA
import com.dynamicruntime.common.user.TestUser
import com.dynamicruntime.common.util.toJsonListOfMaps
import com.dynamicruntime.common.util.toJsonListOfStrings
import com.dynamicruntime.common.util.toJsonMapOrEmpty
import com.dynamicruntime.common.util.toOptStr
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldNotContain
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe

/**
 * Importing form documents from search output (issue #545): the round trip through the search's own shape, the
 * stripping that makes a document portable, the forgiveness of unsupported and invalid entries, the scope that
 * confines an ordinary caller, and the env-auth-gated `preserveEntryIds`.
 *
 * A flow test: alice's document is exported and imported for bob, so the users and what they own are shared
 * state built up across the blocks, which run in declaration order.
 */
class GedraImportTest : StringSpec({
    val cxt = Startup.mkTestBootCxt("gedraImport", "gedraImportTest")

    val alice = TestUser.create(cxt, "alice@import.test")
    val bob = TestUser.create(cxt, "bob@import.test")
    val ada = TestUser.create(cxt, "ada@import.test", level = ROLE.admin)

    fun nameEntry(name: String): Map<String, Any?> = mapOf(GE.traitId to GT.name, GE.data to mapOf(GT.name to name))
    fun doc(vararg entries: Map<String, Any?>): Map<String, Any?> = mapOf(GDF.entries to entries.toList())

    fun importFor(tu: TestUser, body: Map<String, Any?>): Map<String, Any?> = tu.postData(GEP.formDocImport, body)
    fun imported(res: Map<String, Any?>) = res[GIF.imported].toJsonListOfMaps()
    fun discarded(res: Map<String, Any?>) = res[GIF.discarded].toJsonListOfMaps()

    fun entriesOf(tu: TestUser, gedraId: String): List<Map<String, Any?>> =
        tu.getItem(GEP.formDoc, mapOf(GDF.gedraId to gedraId))[GDF.entries].toJsonListOfMaps()

    var aliceDocId = ""

    "a user imports a single document for themselves" {
        val res = importFor(bob, mapOf(GIF.data to doc(nameEntry("Imported for Bob"))))
        val docs = imported(res)
        docs.size shouldBe 1
        val newId = docs.single()[GDF.gedraId].toOptStr() ?: ""
        newId.isNotEmpty() shouldBe true
        discarded(res).shouldBeEmpty()

        // The stored entry carries the imported trait data, a fresh id, and source `user`.
        val entry = entriesOf(bob, newId).single()
        entry[GE.traitId] shouldBe GT.name
        entry[GE.data].toJsonMapOrEmpty()[GT.name] shouldBe "Imported for Bob"
        entry[GE.source] shouldBe GSRC.user
    }

    "an admin round-trips search output: export alice's docs, import them for bob, ownership reassigned" {
        aliceDocId = alice.postItem(GEP.formDocCreate, mapOf(GDF.entries to listOf(nameEntry("Alice original"))))[GDF.gedraId].toOptStr() ?: ""

        // Export: the search, confined to alice, as the admin runs it -- the very shape import consumes.
        val exported = ada.client.sendJsonGetRequest(GEP.formDocs, mapOf(EI.user to "alice@import.test"))[EP.items].toJsonListOfMaps()
        exported.map { it[GDF.gedraId].toOptStr() } shouldContainExactly listOf(aliceDocId)

        // Import the whole `{items: [...]}` payload for bob.
        val res = importFor(ada, mapOf(EI.user to "bob@import.test", GIF.data to mapOf(EP.items to exported)))
        val newId = imported(res).single()[GDF.gedraId].toOptStr() ?: ""
        // A fresh document, not alice's, now owned by bob and carrying the copied name.
        newId shouldNotBe aliceDocId
        val fetched = ada.getItem(GEP.formDoc, mapOf(GDF.gedraId to newId))
        fetched[GDF.userId] shouldBe bob.userId
        fetched[GDF.entries].toJsonListOfMaps().single()[GE.data].toJsonMapOrEmpty()[GT.name] shouldBe "Alice original"
    }

    "an unsupported trait is forgiven by default and reported, the rest imported" {
        val body = mapOf(GIF.data to doc(nameEntry("Keep me"), mapOf(GE.traitId to "madeUpTrait", GE.data to mapOf("x" to 1))))
        val res = importFor(bob, body)
        // The name entry survived; the unknown one was thrown away and counted.
        val excluded = (imported(res).single()[GIF.excludedTraits] as List<*>).map { it.toString() }
        excluded shouldContainExactly listOf("madeUpTrait")
        val d = discarded(res).single()
        d[GIF.category] shouldBe GIF.unknownTrait
        d[GE.traitId] shouldBe "madeUpTrait"
        d[GIF.count] shouldBe 1
    }

    "an unsupported trait is refused when its forgiveness is turned off" {
        val body = mapOf(
            GIF.forgiveUnknownTraits to false,
            GIF.data to doc(mapOf(GE.traitId to "madeUpTrait", GE.data to mapOf("x" to 1))),
        )
        bob.expectError(400, GEP.formDocImport, data = body)
    }

    "an invalid entry is forgiven only when asked, else it rejects the whole import" {
        val tooLong = "x".repeat(GT.nameMaxLength + 50)
        // Default: an invalid entry rejects the whole call.
        bob.expectError(400, GEP.formDocImport, data = mapOf(GIF.data to doc(nameEntry(tooLong))))
        // Forgiven: thrown away and reported, nothing created.
        val res = importFor(bob, mapOf(GIF.forgiveInvalidEntries to true, GIF.data to doc(nameEntry(tooLong))))
        imported(res).shouldBeEmpty()
        discarded(res).single().let {
            it[GIF.category] shouldBe GIF.invalidEntry
            it[GE.traitId] shouldBe GT.name
        }
    }

    "an unforgivable fault in any document rejects the whole call, creating nothing" {
        val before = bob.getItems(GEP.formDocs).size
        // First document is fine; the second names an unsupported trait with forgiveness off. The call must
        // fail and the first document must not have been created.
        val body = mapOf(
            GIF.forgiveUnknownTraits to false,
            GIF.data to mapOf(EP.items to listOf(
                doc(nameEntry("Would-be first")),
                doc(mapOf(GE.traitId to "madeUpTrait", GE.data to mapOf("x" to 1))),
            )),
        )
        bob.expectError(400, GEP.formDocImport, data = body)
        bob.getItems(GEP.formDocs).size shouldBe before
    }

    "an ordinary user cannot import for another user" {
        bob.expectError(400, GEP.formDocImport, data = mapOf(EI.user to "alice@import.test", GIF.data to doc(nameEntry("nope"))))
    }

    "preserveEntryIds is refused without env auth and honored with it" {
        val withId = mapOf(GE.traitId to GT.name, GE.entryId to "keep-this-id", GE.data to mapOf(GT.name to "Kept"))

        // No env auth: sending the toggle is refused (the schema hides it; the handler enforces it).
        bob.expectError(400, GEP.formDocImport, data = mapOf(GIF.preserveEntryIds to true, GIF.data to doc(withId)))

        // With env auth asserted on the channel, the incoming entry id is preserved.
        ada.client.setHeader(ENVA.header, "envauth.ada@gyassa.com")
        val res = importFor(ada, mapOf(EI.user to "bob@import.test", GIF.preserveEntryIds to true, GIF.data to doc(withId)))
        val newId = imported(res).single()[GDF.gedraId].toOptStr() ?: ""
        entriesOf(ada, newId).single()[GE.entryId] shouldBe "keep-this-id"
    }

    "the preserveEntryIds toggle carries g-visibleWhen for every caller; the delivered cfacts decide it (#564)" {
        fun importInputProps(tu: TestUser): Map<String, Any?> {
            val eps = tu.getData("/schema/endpoints")[EI.endpoints].toJsonListOfMaps()
            val ep = eps.first { it[EI.path].toOptStr()?.endsWith("/formDoc/import") == true && it[EI.method] == "POST" }
            return ep[EI.inputSchema].toJsonMapOrEmpty()[SCH.properties].toJsonMapOrEmpty()
        }
        fun deliveredCfacts(tu: TestUser): List<String> =
            tu.getData("/schema/endpoints")[EI.cfacts].toJsonListOfStrings()
        // The served schema is caller-independent now (issue #564): the toggle and its keyword reach both.
        importInputProps(ada).containsKey(GIF.preserveEntryIds) shouldBe true
        importInputProps(bob).containsKey(GIF.preserveEntryIds) shouldBe true
        importInputProps(ada)[GIF.preserveEntryIds].toJsonMapOrEmpty()[SCH.visibleWhen] shouldBe CFACTS.hasEnvAuth
        // ada's channel is env-authed (the header set above), bob's is not: the delivered cfacts differ, so the
        // client shows the toggle only where hasEnvAuth is present. The handler enforces it regardless.
        deliveredCfacts(ada) shouldContain CFACTS.hasEnvAuth
        deliveredCfacts(bob) shouldNotContain CFACTS.hasEnvAuth
    }
})
