package com.dynamicruntime.kdn

import com.dynamicruntime.common.context.CL
import com.dynamicruntime.common.context.ENV
import com.dynamicruntime.common.context.KdrCxt
import com.dynamicruntime.common.exception.EXC
import com.dynamicruntime.common.gedra.ACEP
import com.dynamicruntime.common.gedra.CCT
import com.dynamicruntime.common.gedra.CFEP
import com.dynamicruntime.common.gedra.ClientAudience
import com.dynamicruntime.common.gedra.ClientDef
import com.dynamicruntime.common.gedra.ClientService
import com.dynamicruntime.common.gedra.ClientUsageType
import com.dynamicruntime.common.http.request.ROLE
import com.dynamicruntime.common.schema.SCH
import com.dynamicruntime.common.schema.SCT
import com.dynamicruntime.common.user.TestUser
import com.dynamicruntime.common.util.toJsonMapOrEmpty
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe

/**
 * The cross-client `/admin` config surface (issue #685): the same read/write/publish/reload/tier operations as
 * the client-scoped [CFEP] surface, but full-scope -- an `allClients` admin managing a **named** client's stored
 * configuration, including creating a brand-new client purely over the API. Driven over the in-process HTTP
 * client so the `admin` section gate (which requires `allClients`) is exercised for real.
 *
 * Its own client ids, since the in-memory database is shared across specs.
 */
class AdminGedraConfigEndpointTest : StringSpec({
    val cxt: KdrCxt = Startup.mkTestBootCxt("adminCfgEp", "adminCfgEpTest")

    // A full-scope admin: ROLE.admin + allClients, the only caller the `/admin` config surface admits.
    fun fullAdmin(): TestUser = TestUser.createFullAdmin(cxt, "admincfg@example.com")

    "an allClients admin writes, reads and publishes a NAMED client's config cross-client" {
        val admin = fullAdmin()
        val target = CL.hub // not the admin's own default client -- this is the cross-client point
        val ns = "acepns685"
        val name = "adminroundtrip"

        // Write a bundle filed under the named client, not the caller's own.
        val v1 = admin.postData(
            ACEP.bundleWrite,
            mapOf(
                CFEP.client to target, CFEP.name to name, CFEP.namespaceField to ns,
                CFEP.slots to mapOf(
                    CCT.schemaDef to listOf(mapOf(CCT.typeName to "$ns.Shared", CCT.schema to mapOf(SCH.type to SCT.kObject))),
                ),
            ),
        )
        v1[CFEP.version] shouldBe 1
        v1[CFEP.published] shouldBe false
        v1[CFEP.client] shouldBe target

        // Read it back through the named-client read.
        val bundle = admin.getItem(ACEP.bundle, mapOf(CFEP.client to target, CFEP.name to name))
        bundle[CFEP.client] shouldBe target
        bundle[CFEP.slots].toJsonMapOrEmpty().keys shouldBe setOf(CCT.schemaDef)

        // Publish the named client's revision, cross-client.
        admin.postData(ACEP.bundlePublish, mapOf(CFEP.client to target, CFEP.name to name))[CFEP.published] shouldBe true
    }

    "an allClients admin creates a brand-new client over the API" {
        val admin = fullAdmin()
        val newClient = "acep685new"
        val ns = "${newClient}config"
        // The clientDef slot is the client's own definition; a valid ClientInfo is what ClientDef.toInfo() writes.
        val defInfo = ClientDef(
            clientId = newClient, name = "ACEP New", usageType = ClientUsageType.dev,
            audience = ClientAudience.internal, enabledEnvironments = setOf(ENV.unit, ENV.local),
        ).toInfo()

        // The client does not exist yet.
        ClientService.get(cxt).known(newClient) shouldBe null

        admin.postData(
            ACEP.bundleWrite,
            mapOf(
                CFEP.client to newClient, CFEP.name to "main", CFEP.namespaceField to ns,
                CFEP.slots to mapOf(CCT.clientDef to listOf(defInfo)),
            ),
        )
        // Editing does not make it present; a reload does (issue #685). The reload endpoint reloads this node.
        admin.postData(ACEP.reload, mapOf(CFEP.client to newClient))
        ClientService.get(cxt).known(newClient).shouldNotBeNull()
    }

    "naming a client that neither exists nor has stored config is a 404 (issue #685 review)" {
        val admin = fullAdmin()
        // A typo for a real client: not present, no stored config. The tier and reload endpoints must refuse it
        // rather than write an orphan tier row or report a no-op reload as success.
        admin.expectError(
            EXC.notFound,
            ACEP.publishedOnly,
            mapOf(CFEP.client to "nosuchclient685", CFEP.publishedOnlyField to true),
        )
        admin.expectError(EXC.notFound, ACEP.reload, mapOf(CFEP.client to "nosuchclient685"))
    }

    "a scoped administrator without allClients is refused the admin config surface" {
        val scoped = TestUser.create(cxt, "admincfg-scoped@example.com", level = ROLE.admin)
        // The `/admin` section requires allClients, which a plain admin lacks.
        scoped.expectError(EXC.notAuthorized, ACEP.bundles, args = mapOf(CFEP.client to CL.hub))
        scoped.expectError(
            EXC.notAuthorized,
            ACEP.bundleWrite,
            mapOf(CFEP.client to CL.hub, CFEP.name to "x", CFEP.namespaceField to "acepns685b", CFEP.slots to emptyMap<String, Any?>()),
        )
    }

    "an ordinary user is refused the admin config surface" {
        TestUser.create(cxt, "admincfg-user@example.com")
            .expectError(EXC.notAuthorized, ACEP.bundles, args = mapOf(CFEP.client to CL.hub))
    }
})
