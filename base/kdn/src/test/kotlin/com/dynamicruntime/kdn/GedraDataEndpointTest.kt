package com.dynamicruntime.kdn

import com.dynamicruntime.common.context.CL
import com.dynamicruntime.common.endpoint.EP
import com.dynamicruntime.common.gedra.GD
import com.dynamicruntime.common.gedra.GDBG
import com.dynamicruntime.common.gedra.GDF
import com.dynamicruntime.common.gedra.GDT
import com.dynamicruntime.common.gedra.GE
import com.dynamicruntime.common.gedra.GEP
import com.dynamicruntime.common.gedra.GID
import com.dynamicruntime.common.gedra.GSRC
import com.dynamicruntime.common.gedra.GT
import com.dynamicruntime.common.gedra.GedraDataType
import com.dynamicruntime.common.gedra.GedraIdContext
import com.dynamicruntime.common.gedra.GedraStorageType
import com.dynamicruntime.common.gedra.gedraDataTopic
import com.dynamicruntime.common.http.request.ROLE
import com.dynamicruntime.common.http.request.TestHttpClient
import com.dynamicruntime.common.sql.PF
import com.dynamicruntime.common.sql.SqlTopicService
import com.dynamicruntime.common.user.TestUser
import com.dynamicruntime.common.util.toJsonListOfMaps
import com.dynamicruntime.common.util.toJsonMapOrEmpty
import com.dynamicruntime.common.util.toOptStr
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.collections.shouldNotContain
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldStartWith

/**
 * Storing gedra data end to end (issue #310): the two tiers, the entry envelope, and the scope that decides
 * whose documents a caller sees.
 *
 * A **flow test**, deliberately. What is worth testing here is what one caller's writes look like to another
 * caller, and a per-test instance throws exactly that away: the document alice creates in the first block is
 * the row bob must not see in the fourth and the administrator must see in the fifth. So the three users are
 * shared on purpose rather than by omission, and the blocks run in declaration order.
 */
class GedraDataEndpointTest : StringSpec({
    val cxt = Startup.mkTestBootCxt("gedraData", "gedraDataTest")

    val alice = TestUser.create(cxt, "alice@gedra.test")
    val bob = TestUser.create(cxt, "bob@gedra.test")
    // A *scoped* administrator -- ROLE.admin without `allClients` -- which is the width #310 asked for: their
    // whole client and no further.
    val ada = TestUser.create(cxt, "ada@gedra.test", level = ROLE.admin)

    fun nameEntry(name: String): Map<String, Any?> =
        mapOf(GE.traitId to GT.name, GE.data to mapOf(GT.name to name))

    fun idsOf(docs: List<Map<String, Any?>>) = docs.map { it[GDF.gedraId].toOptStr() }

    // Written by the first block and read by the ones after it -- the accumulated state this is a flow test for.
    var aliceDocId = ""
    var bobDocId = ""

    "a form document is created with its entries and comes back stored" {
        val doc = alice.postItem(GEP.formDocCreate, mapOf(GDF.entries to listOf(nameEntry("Alice's expenses"))))
        aliceDocId = doc[GDF.gedraId].toOptStr().shouldNotBeNull()

        // Data storage, a form document, the caller's client, and minted in the UI context -- every segment the
        // format promises, in the order it promises them.
        aliceDocId shouldStartWith listOf(
            GedraStorageType.dataStore.idAbbrev,
            GedraDataType.formDoc.idAbbrev,
            // A provisioned user's own client, taken off the context rather than chosen by the endpoint --
            // which is the point of the segment: what a caller creates belongs to the client they are in.
            CL.public,
            GedraIdContext.ui.letter,
        ).joinToString(GID.partSep.toString())
        doc[GDF.gedraKind] shouldBe GedraDataType.formDoc.name
        doc[GDF.userId] shouldBe alice.userId
        doc[GDF.client] shouldBe CL.public

        // The envelope the caller did not send. Response-schema validation is on in a test boot, so a document
        // or an entry missing any required part of it would have failed before reaching this assertion.
        val entry = doc[GDF.entries].toJsonListOfMaps().single()
        entry[GE.traitId] shouldBe GT.name
        entry[GE.data].toJsonMapOrEmpty()[GT.name] shouldBe "Alice's expenses"
        entry[GE.source] shouldBe GSRC.user
        entry.keys shouldContain GE.entryId
        // Who wrote it, beside when (issue #325). On a create the actor and the owner are the same person, so
        // this cannot yet show that the *actor* is what is recorded -- the divergence only appears once an
        // administrator can edit somebody else's document, which needs the update path. What it does show is
        // that the value is the caller's rather than a constant or the system user, which bob's block confirms
        // from the other side.
        entry[GE.createdBy] shouldBe alice.userId
        entry[GE.updatedBy] shouldBe alice.userId
    }

    "both tiers were written, keyed by the same id" {
        val sqlCxt = SqlTopicService.mkSqlCxt(cxt, gedraDataTopic)
        val topic = sqlCxt.sqlTopic.shouldNotBeNull()
        // The root is the topic's lock table, and a row of it exists under the content row's own id. That is
        // the whole of the two-tier arrangement, and it is what a "write" reaching a file store will lock on.
        topic.tranTable.shouldNotBeNull().tableName shouldBe GDT.gedraDataTran
        sqlCxt.sqlDb.withSession(cxt) {
            val root = sqlCxt.sqlDb
                .queryOneStatement(cxt, topic.qTranLockQuery.shouldNotBeNull(), mapOf(GD.gedraId to aliceDocId))
            root.shouldNotBeNull()[GD.gedraId] shouldBe aliceDocId
            // Ownership recorded at the root too, from the context rather than from anything the caller sent.
            root[PF.userId] shouldBe alice.userId
        }
    }

    "the document reads back by id, for the user who owns it" {
        val doc = alice.getItem(GEP.formDoc, mapOf(GDF.gedraId to aliceDocId))
        doc[GDF.gedraId] shouldBe aliceDocId
        doc[GDF.entries].toJsonListOfMaps().single()[GE.data].toJsonMapOrEmpty()[GT.name] shouldBe "Alice's expenses"
    }

    // The narrow width of ReadScopeRules, which until now had no endpoint reaching it. Both halves matter: a
    // listing that omits the row, and a direct read by an id bob was handed rather than had to guess.
    "another ordinary user sees none of it, by listing or by id" {
        val bobDoc = bob.postItem(GEP.formDocCreate, mapOf(GDF.entries to listOf(nameEntry("Bob's expenses"))))
        bobDocId = bobDoc[GDF.gedraId].toOptStr().shouldNotBeNull()
        // The other side of the audit check above: bob's entry carries bob, so the stamp follows the caller
        // rather than being a constant that happened to match alice.
        bobDoc[GDF.entries].toJsonListOfMaps().single()[GE.createdBy] shouldBe bob.userId

        idsOf(bob.getItems(GEP.formDocs)) shouldContainExactly listOf(bobDocId)

        // Out of scope answers 404, not 403: a 403 would confirm that the id names a real document belonging to
        // somebody else, which is exactly what an id-guessing caller is fishing for.
        bob.expectError(404, GEP.formDoc, args = mapOf(GDF.gedraId to aliceDocId))
    }

    "a client-scoped administrator sees the whole client's documents" {
        val docs = ada.getItems(GEP.formDocs)
        docs.map { it[GDF.userId] } shouldContainExactlyInAnyOrder listOf(alice.userId, bob.userId)
        // Newest first, which the service makes a *total* order by breaking a shared millisecond on the id --
        // otherwise this assertion would be one that usually passes.
        idsOf(docs) shouldContainExactly listOf(bobDocId, aliceDocId)
        // And an id one of their users owns reads back for them. That the owner can read it is already shown
        // above; what is new is that somebody who is not the owner can.
        ada.getItem(GEP.formDoc, mapOf(GDF.gedraId to aliceDocId))[GDF.gedraId] shouldBe aliceDocId
    }

    // Three ways an id can fail to name something, and only one of them is the request itself being broken.
    "a malformed id faults, and a well-formed one that names nothing is simply absent" {
        alice.expectError(400, GEP.formDoc, args = mapOf(GDF.gedraId to "notAGedraId"))
        alice.expectError(404, GEP.formDoc, args = mapOf(GDF.gedraId to "gd.fd.${CL.public}.uNoSuchDocument"))
        // Well-formed, in the caller's own client, and the wrong kind -- so the kind is the only thing that can
        // account for the refusal. Refused before the database is asked, because the id itself says it is not a
        // form document.
        alice.expectError(404, GEP.formDoc, args = mapOf(GDF.gedraId to "gd.ud.${CL.public}.u${alice.userId}"))
    }

    // A trait this node does not know falls through the union's default branch rather than taking the creation
    // down -- the property client separation depends on, now against stored data rather than against a fixture
    // that stores nothing.
    "an entry whose trait this node does not know is stored rather than refused" {
        val created = alice.postItem(
            GEP.formDocCreate,
            mapOf(
                GDF.entries to listOf(
                    nameEntry("Mixed"),
                    mapOf(GE.traitId to "notATraitThisNodeKnows", GE.data to mapOf("whatever" to 1)),
                ),
            ),
        )
        val entries = alice.getItem(GEP.formDoc, mapOf(GDF.gedraId to created[GDF.gedraId]))[GDF.entries]
            .toJsonListOfMaps()
        entries.map { it[GE.traitId] } shouldContainExactly listOf(GT.name, "notATraitThisNodeKnows")
        // Carried intact rather than emptied on the way through, because the default branch is open.
        entries[1][GE.data].toJsonMapOrEmpty()["whatever"] shouldBe 1L
    }

    "an entry that breaks its trait's bound is refused, and nothing is stored" {
        val before = alice.getItems(GEP.formDocs).size
        alice.expectError(
            400, GEP.formDocCreate,
            mapOf(GDF.entries to listOf(nameEntry("x".repeat(GT.nameMaxLength + 1)))),
        )
        alice.getItems(GEP.formDocs).size shouldBe before
    }

    // The scope a listing ran with is the one fact its response cannot show: a correctly and an incorrectly
    // scoped listing differs only in the rows the caller never sees.
    "the debug tag reports the scope the listing ran with" {
        val explained = alice.client
            .sendJsonGetRequest(GEP.formDocs, mapOf(EP.debug to GDBG.explainScope))[EP.meta].toJsonMapOrEmpty()
            .getValue(GDBG.scopeExplained).toJsonMapOrEmpty()
        // "U" is the own-user shape; the administrator's would be "C". The shape is what picks the statement,
        // so this is the assertion that the right one ran.
        explained[GDBG.shapeKey] shouldBe "U"
        explained[GDBG.scope].toString() shouldBe "ReadScope(client=null, org=null, userId=${alice.userId})"
    }

    // Delete, and what it means: the document stops being readable and stops being listed. This used to reach
    // past the service and flip `enabled` with hand-written SQL, because nothing disabled a document through an
    // endpoint; #326 gave it one, so the flag is now exercised the way a caller reaches it.
    "a deleted document is not there, by id or in a listing" {
        val deleted = bob.postData(GEP.formDocDelete, mapOf(GDF.gedraId to bobDocId))
        deleted[GDF.gedraId] shouldBe bobDocId

        bob.expectError(404, GEP.formDoc, args = mapOf(GDF.gedraId to bobDocId))
        idsOf(bob.getItems(GEP.formDocs)) shouldNotContain bobDocId
        // Gone for the administrator too, rather than merely hidden from its owner -- a disabled row is one
        // that is not there, at every width.
        idsOf(ada.getItems(GEP.formDocs)) shouldNotContain bobDocId
    }

    // Deleting twice is a 404 rather than a quiet success. The second call did not remove anything, and saying
    // otherwise would let a caller believe they had just deleted something that went days ago.
    "deleting the same document again says there is nothing there" {
        bob.expectError(404, GEP.formDocDelete, mapOf(GDF.gedraId to bobDocId))
    }

    // The same non-disclosure the read makes: a caller who may not see a document cannot learn it exists by
    // trying to delete it, and cannot delete it either.
    "one user cannot delete another's document" {
        bob.expectError(404, GEP.formDocDelete, mapOf(GDF.gedraId to aliceDocId))
        // Still there, and still alice's -- the refused delete changed nothing.
        alice.getItem(GEP.formDoc, mapOf(GDF.gedraId to aliceDocId))[GDF.gedraId] shouldBe aliceDocId
    }

    // An administrator's reach is the same for deleting as for reading, which is what one scope rule for both
    // buys: nothing had to decide separately who may delete.
    "a client-scoped administrator can delete a document they do not own" {
        ada.postData(GEP.formDocDelete, mapOf(GDF.gedraId to aliceDocId))
        alice.expectError(404, GEP.formDoc, args = mapOf(GDF.gedraId to aliceDocId))
    }

    "an anonymous caller reaches none of it" {
        val anon = TestHttpClient(cxt.instanceConfig)
        (anon.sendJsonGetRequest(GEP.formDocs)[EP.status] as? Number)?.toInt() shouldBe 401
    }
})
