package com.dynamicruntime.sample.gedra

import com.dynamicruntime.common.context.CL
import com.dynamicruntime.common.endpoint.EP
import com.dynamicruntime.common.gedra.GDBG
import com.dynamicruntime.common.gedra.GDF
import com.dynamicruntime.common.gedra.GE
import com.dynamicruntime.common.gedra.GEP
import com.dynamicruntime.common.gedra.GT
import com.dynamicruntime.common.user.TestUser
import com.dynamicruntime.common.util.toJsonListOfMaps
import com.dynamicruntime.common.util.toOptStr
import com.dynamicruntime.kdn.Startup
import com.dynamicruntime.sample.SampleComponent
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe

/**
 * The gedra-state inspection & admin surfaces (issue #600, phase E): the `withStates` parameter that attaches a
 * form's state to the listing, and the global admin endpoints that read and replace a gedra's state directly.
 * Driven over HTTP through `TestHttpClient`, since these are endpoints; `sample` because the state entries it
 * writes (`externalId`) are sample-declared. Its own users, as the other sample gedra tests explain: every test
 * shares one in-memory database.
 */
class GedraStateSurfaceTest : StringSpec({
    val cxt = Startup.mkTestBootCxt(
        "gedraStateSurface", "gedraStateSurfaceTest", mapOf("KDR_LOAD_SAMPLE" to "true"),
        additionalComponents = listOf(SampleComponent()),
    )

    val user = TestUser.create(cxt, "user@statesurface.test")
    // Deployment-wide (admin + allClients): the section the /admin state endpoints gate on.
    val admin = TestUser.createFullAdmin(cxt, "admin@statesurface.test")

    fun createForm(name: String): String =
        user.postItem(
            GEP.formDocCreate,
            mapOf(GDF.entries to listOf(mapOf(GE.traitId to GT.name, GE.data to mapOf(GT.name to name)))),
        )[GDF.gedraId].toOptStr().orEmpty()

    fun externalId(source: String, ref: String): Map<String, Any?> =
        mapOf(GE.traitId to ST.externalId, GE.data to mapOf(ST.externalSource to source, ST.externalRef to ref))

    fun traitIds(states: Any?): List<String?> = states.toJsonListOfMaps().map { it[GE.traitId].toOptStr() }

    "an admin replaces a gedra's state and reads it back" {
        val gid = createForm("Admin state form")

        // Replace (whole-set upsert): the response echoes the stored entries under `states`.
        val written = admin.postItem(GEP.adminGedraState, mapOf(GDF.gedraId to gid, GDF.states to listOf(externalId("salesforce", "SF-1"))))
        written[GDF.gedraId] shouldBe gid
        traitIds(written[GDF.states]) shouldContainExactly listOf(ST.externalId)

        // And it reads back the same, whoever owns the gedra (the admin is unrestricted).
        val read = admin.getItem(GEP.adminGedraState, mapOf(GDF.gedraId to gid))
        read[GDF.gedraId] shouldBe gid
        traitIds(read[GDF.states]) shouldContainExactly listOf(ST.externalId)
    }

    "withStates attaches the owner's state to the listing, and is absent without it" {
        val gid = createForm("Listed with state")
        admin.postItem(GEP.adminGedraState, mapOf(GDF.gedraId to gid, GDF.states to listOf(externalId("hubspot", "HS-9"))))

        // The owner lists their own forms with withStates: the row for this gedra carries its state entries.
        val withStates = user.getItems(GEP.formDocs, mapOf(GDF.withStates to true)).first { it[GDF.gedraId] == gid }
        traitIds(withStates[GDF.states]) shouldContainExactly listOf(ST.externalId)

        // Without the flag, the field is absent (not empty) -- the read is not paid for where it is not asked.
        val without = user.getItems(GEP.formDocs).first { it[GDF.gedraId] == gid }
        without.containsKey(GDF.states) shouldBe false
    }

    "the admin state endpoint is refused to a non-admin caller" {
        val gid = createForm("Guarded")
        // The /admin section requires admin + allClients; an ordinary user is refused by the section gate.
        user.expectError(403, GEP.adminGedraState, args = mapOf(GDF.gedraId to gid))
    }

    "the stateFromSql debug tag reads state via SQL and agrees with the cache" {
        val gid = createForm("SQL-debug state")
        admin.postItem(GEP.adminGedraState, mapOf(GDF.gedraId to gid, GDF.states to listOf(externalId("stripe", "ST-3"))))

        // The default read trusts the resident cache; `_debug=stateFromSql` activates the otherwise-dormant SQL
        // path. Both answer the same -- the diagnostic exists to prove exactly that when a stale read is feared.
        val viaCache = admin.getItem(GEP.adminGedraState, mapOf(GDF.gedraId to gid))
        val viaSql = admin.getItem(GEP.adminGedraState, mapOf(GDF.gedraId to gid, EP.debug to GDBG.stateFromSql))
        traitIds(viaSql[GDF.states]) shouldContainExactly listOf(ST.externalId)
        traitIds(viaSql[GDF.states]) shouldContainExactly traitIds(viaCache[GDF.states])
    }

    "the admin state read 404s a gedra that does not exist" {
        // A well-formed id that names nothing: the read resolves the gedra first, so absence is a real 404 --
        // not a 200 with an empty state list, which an item response could not distinguish from "no state".
        admin.expectError(404, GEP.adminGedraState, args = mapOf(GDF.gedraId to "gd.fd.${CL.public}.uNoSuchGedra"))
    }
})
