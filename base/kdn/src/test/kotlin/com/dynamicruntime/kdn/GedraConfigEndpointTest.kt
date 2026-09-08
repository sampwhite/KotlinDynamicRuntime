package com.dynamicruntime.kdn

import com.dynamicruntime.common.context.KdrCxt
import com.dynamicruntime.common.exception.EXC
import com.dynamicruntime.common.gedra.CCT
import com.dynamicruntime.common.gedra.CFEP
import com.dynamicruntime.common.gedra.CLC
import com.dynamicruntime.common.gedra.GE
import com.dynamicruntime.common.context.CL
import com.dynamicruntime.common.http.request.ROLE
import com.dynamicruntime.common.schema.SCH
import com.dynamicruntime.common.schema.SCT
import com.dynamicruntime.common.user.TestUser
import com.dynamicruntime.common.util.toJsonListOfMaps
import com.dynamicruntime.common.util.toJsonMapOrEmpty
import com.dynamicruntime.common.util.toOptStr
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.shouldBe
import io.kotest.matchers.ints.shouldBeLessThan
import io.kotest.matchers.shouldNotBe
import kotlin.time.Duration.Companion.seconds

/**
 * The config edit endpoints (issue #627), driven over the in-process HTTP client so the `clientAdmin` section
 * gate -- which lives in the dispatcher, not the handlers -- is exercised for real. The bundle round trip, the
 * version/publish transition through the endpoints, the trait-level accounting view, the reserved-namespace
 * refusal, and the section gate.
 *
 * Its own client, since the in-memory database is shared across specs.
 */
class GedraConfigEndpointTest : StringSpec({
    val cxt: KdrCxt = Startup.mkTestBootCxt("gedraCfgEp", "gedraCfgEpTest")
    val namespace = "cfgepns"

    // A client-scoped administrator (ROLE.admin, no allClients): the config surface's intended caller. No
    // explicit client -- an unregistered client id fails authentication -- so the user takes the default and
    // the config is written under whatever client that is.
    fun admin(): TestUser = TestUser.create(cxt, "cfgadmin@example.com", level = ROLE.admin)

    // A bundle's slots: a cfact named `ready`, and a directly-declared shared type (so the namespace is
    // recoverable from a stored qualified type name).
    fun slots(readyDesc: String): Map<String, Any?> = mapOf(
        CCT.cfactDef to listOf(
            mapOf(CCT.name to "ready", CCT.group to "grp", CCT.description to readyDesc, CCT.toFrontend to false),
        ),
        CCT.schemaDef to listOf(
            mapOf(CCT.typeName to "$namespace.Shared", CCT.schema to mapOf(SCH.type to SCT.kObject)),
        ),
    )

    fun writeBundle(u: TestUser, name: String, readyDesc: String, impliedDelete: Boolean? = null): Map<String, Any?> =
        u.postData(
            CFEP.bundleWrite,
            buildMap {
                put(CFEP.name, name)
                put(CFEP.namespaceField, namespace)
                put(CFEP.slots, slots(readyDesc))
                if (impliedDelete != null) put(CFEP.impliedDelete, impliedDelete)
            },
        )

    "a bundle round-trips, versions and publishes through the endpoints" {
        val u = admin()
        val name = "roundtrip"
        val client = u.selfClient()

        // Write -> version 1, unpublished.
        val v1 = writeBundle(u, name, "Ready v1")
        v1[CFEP.version] shouldBe 1
        v1[CFEP.published] shouldBe false
        v1[CFEP.client] shouldBe client

        // Read the bundle back: identity, recovered namespace, and the slot contents.
        val bundle = u.getItem(CFEP.bundle, mapOf(CFEP.name to name))
        bundle[CFEP.name] shouldBe name
        bundle[CFEP.namespaceField] shouldBe namespace
        val bundleSlots = bundle[CFEP.slots].toJsonMapOrEmpty()
        bundleSlots.keys shouldContainExactlyInAnyOrder listOf(CCT.cfactDef, CCT.schemaDef)
        bundleSlots[CCT.cfactDef].toJsonListOfMaps().single()[CCT.description] shouldBe "Ready v1"

        // It appears in the listing.
        u.getItems(CFEP.bundles).map { it[CFEP.name].toOptStr() } shouldContain name

        // The trait-level view exposes the per-slot accounting.
        val traits = u.getItems(CFEP.traits, mapOf(CFEP.name to name))
        traits.map { it[GE.traitId].toOptStr() } shouldContainExactlyInAnyOrder listOf(CCT.cfactDef, CCT.schemaDef)
        val cfactEntry = traits.single { it[GE.traitId].toOptStr() == CCT.cfactDef }
        cfactEntry[GE.entryId].toOptStr() shouldNotBe null
        cfactEntry[GE.createdAt].toOptStr() shouldNotBe null

        // Publish -> the latest revision is now published.
        val published = u.postData(CFEP.bundlePublish, mapOf(CFEP.name to name))
        published[CFEP.published] shouldBe true
        published[CFEP.publishedAt].toOptStr() shouldNotBe null

        // The next write starts revision 2.
        val v2 = writeBundle(u, name, "Ready v2")
        v2[CFEP.version] shouldBe 2
        v2[CFEP.published] shouldBe false
        u.getItem(CFEP.bundle, mapOf(CFEP.name to name))[CFEP.slots].toJsonMapOrEmpty()[CCT.cfactDef]
            .toJsonListOfMaps().single()[CCT.description] shouldBe "Ready v2"
    }

    "authoring into the reserved globalconfig namespace is refused" {
        val u = admin()
        u.expectError(
            EXC.badInput,
            CFEP.bundleWrite,
            mapOf(CFEP.name to "bad", CFEP.namespaceField to "globalconfig", CFEP.slots to emptyMap<String, Any?>()),
        )
    }

    "reading a configuration that does not exist is a 404" {
        admin().expectError(EXC.notFound, CFEP.bundle, args = mapOf(CFEP.name to "nope"))
    }

    "an ordinary user cannot reach the config surface" {
        val user = TestUser.create(cxt, "cfguser@example.com")
        // The section gate refuses a non-admin outright.
        user.expectError(EXC.notAuthorized, CFEP.bundles)
        user.expectError(
            EXC.notAuthorized,
            CFEP.bundleWrite,
            mapOf(CFEP.name to "x", CFEP.namespaceField to namespace, CFEP.slots to slots("x")),
        )
    }

    "an unknown slot key is refused rather than silently dropped" {
        // A typo'd slot name would be read past by the reassembler and, since a bundle write is authoritative,
        // its intended slot would be deleted. Refused as bad input instead.
        admin().expectError(
            EXC.badInput,
            CFEP.bundleWrite,
            mapOf(
                CFEP.name to "typo",
                CFEP.namespaceField to namespace,
                CFEP.slots to mapOf("cfactDefs" to listOf(mapOf(CCT.name to "x", CCT.group to "g", CCT.description to "d"))),
            ),
        )
    }

    "a slot whose value is not an array is refused" {
        admin().expectError(
            EXC.badInput,
            CFEP.bundleWrite,
            mapOf(
                CFEP.name to "notarray",
                CFEP.namespaceField to namespace,
                // An object, not an array -- which would coerce to an empty slot and delete it.
                CFEP.slots to mapOf(CCT.cfactDef to mapOf(CCT.name to "x")),
            ),
        )
    }

    "authoring into another client's namespace is refused" {
        // The caller's client is `public`; `hubconfig` belongs to `hub`, so the general ownership rule refuses
        // it -- the same rule the reserved-namespace refusal is one case of.
        val u = admin()
        u.selfClient() shouldNotBe CL.hub // guard: the test only means something from a non-hub client
        u.expectError(
            EXC.badInput,
            CFEP.bundleWrite,
            mapOf(
                CFEP.name to "hijack",
                CFEP.namespaceField to CLC.namespaceOf(CL.hub),
                CFEP.slots to mapOf(
                    CCT.cfactDef to listOf(mapOf(CCT.name to "x", CCT.group to "g", CCT.description to "d")),
                ),
            ),
        )
    }

    "the listing is ordered by recency, most-recently-written first" {
        val u = admin()
        cxt.instanceConfig.clock.freeze()
        // `older` is written first, then the clock advances and `newer` is written -- so by recency `newer`
        // precedes `older`, the opposite of their alphabetical order, which is what tells the two rules apart.
        writeBundle(u, "aaa_older", "older")
        cxt.instanceConfig.clock.advanceBy(5.seconds)
        writeBundle(u, "zzz_newer", "newer")

        val names = u.getItems(CFEP.bundles).map { it[CFEP.name].toOptStr() }
        names.indexOf("zzz_newer") shouldBeLessThan names.indexOf("aaa_older")
        cxt.instanceConfig.clock.unfreeze()
    }
})
