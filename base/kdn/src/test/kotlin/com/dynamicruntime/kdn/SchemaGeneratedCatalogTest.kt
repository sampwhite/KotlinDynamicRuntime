package com.dynamicruntime.kdn

import com.dynamicruntime.common.context.ACFG
import com.dynamicruntime.common.context.CL
import com.dynamicruntime.common.context.ENV
import com.dynamicruntime.common.context.KdrCxt
import com.dynamicruntime.common.endpoint.EI
import com.dynamicruntime.common.endpoint.EP
import com.dynamicruntime.common.endpoint.EndpointKind
import com.dynamicruntime.common.endpoint.HttpMethod
import com.dynamicruntime.common.endpoint.KdrEndpoint
import com.dynamicruntime.common.endpoint.SchModuleBuilder
import com.dynamicruntime.common.exception.EXC
import com.dynamicruntime.common.gedra.ClientAudience
import com.dynamicruntime.common.gedra.ClientDef
import com.dynamicruntime.common.gedra.ClientUsageType
import com.dynamicruntime.common.gedra.GedraConfigReload
import com.dynamicruntime.common.gedra.GedraConfigService
import com.dynamicruntime.common.gedra.gedraConfig
import com.dynamicruntime.common.http.request.ROLE
import com.dynamicruntime.common.http.request.RequestService
import com.dynamicruntime.common.http.request.SECT
import com.dynamicruntime.common.naming.clientNamespace
import com.dynamicruntime.common.schema.SCH
import com.dynamicruntime.common.schema.SCT
import com.dynamicruntime.common.schema.schemaDefs
import com.dynamicruntime.common.startup.GeneratedSurface
import com.dynamicruntime.common.startup.SchemaService
import com.dynamicruntime.common.user.TestUser
import com.dynamicruntime.common.user.UADEP
import com.dynamicruntime.common.util.toJsonListOfMaps
import com.dynamicruntime.common.util.toJsonMapOrEmpty
import com.dynamicruntime.common.util.toOptStr
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldNotContain
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe

/**
 * Who is **shown** a client's generated endpoints by a node where the caller has no env auth (issue #1128): the
 * catalog serves such a caller the published API alone (issue #489), with one exception -- an endpoint generated for
 * a client, shown to a caller who administers that client. Nobody else is shown it, no declared endpoint gains by
 * it, and the single lookup answers as the listing does.
 *
 * On the shared default instance, which assumes no env auth -- the production caller's case -- under two clients of
 * its own; the last case removes what the first published.
 */
class SchemaGeneratedCatalogTest : StringSpec({
    val cxt = TestInstances.default("generatedCatalog")
    val schema = SchemaService.get(cxt)

    val own = "gcatown"
    val other = "gcatother"

    fun namespaceOf(client: String) = "kdr.gcattest.$client"
    fun typeOf(client: String) = "${namespaceOf(client)}.Thing"
    fun pathOf(client: String) = "/${SECT.clientAdmin}/$client/gcattest/thing"

    fun surface(client: String) = GeneratedSurface(
        client,
        schemaDefs(cxt, namespaceOf(client)) {
            type("Thing") {
                type = SCT.kObject
                property("n", "A number.", required = true) { type = SCT.integer }
            }
        },
        listOf(
            KdrEndpoint(
                path = pathOf(client), method = HttpMethod.GET, kind = EndpointKind.general, namespace = namespaceOf(client),
                description = "A generated endpoint of '$client'.", inputFields = null, inputTypeRef = null, includeLimit = false,
                outputSchema = SchModuleBuilder(cxt, namespaceOf(client)).scalarOutput(EP.results, "What it answers.", typeOf(client)),
                handler = { _, _ -> mapOf("n" to 1) }, client = client,
            ),
        ),
    )

    fun publish(vararg surfaces: Pair<String, GeneratedSurface?>) =
        schema.publishGenerated(cxt, mapOf(*surfaces)).also { RequestService.get(cxt).evictTypes(it) }

    fun asClient(client: String): KdrCxt = cxt.mkSubContext("gcat", client).also { it.userId = 9200L }

    /** Defines [client] in stored configuration and loads it, so users may be made in it. It varies nothing. */
    fun define(client: String) {
        val config = gedraConfig(cxt, "${client}cfg", clientNamespace(client), client) {
            defineClient(
                ClientDef(
                    clientId = client, name = client, usageType = ClientUsageType.dev,
                    audience = ClientAudience.internal, enabledEnvironments = setOf(ENV.unit, ENV.local),
                ),
            )
        }
        GedraConfigService.get(cxt).writeConfig(asClient(client), config)
        GedraConfigReload.reloadClient(cxt, client)
    }

    fun listing(user: TestUser, args: Map<String, Any?> = emptyMap()) = user.getData("/schema/endpoints", mapOf(EP.limit to 1000) + args)
    fun endpointsOf(catalog: Map<String, Any?>) = catalog[EI.endpoints].toJsonListOfMaps()
    fun pathsOf(catalog: Map<String, Any?>) = endpointsOf(catalog).map { it[EI.path].toOptStr() }
    fun lookedUp(user: TestUser, path: String) =
        user.getData("/schema/endpoint", mapOf(EI.method to HttpMethod.GET.name, EI.path to path))

    "a client's generated endpoint is listed to its administrator without env auth, and to nobody else" {
        // The premise: this node grants no env auth of its own accord, so every caller here is the production one.
        cxt.instanceConfig.get(ACFG.assumeEnvAuth) shouldNotBe true
        define(own)
        define(other)
        publish(own to surface(own), other to surface(other))

        val ownAdmin = TestUser.create(cxt, "gcat-admin@own.test", level = ROLE.admin, userClient = own)
        val ownUser = TestUser.create(cxt, "gcat-user@own.test", userClient = own)
        val otherAdmin = TestUser.create(cxt, "gcat-admin@other.test", level = ROLE.admin, userClient = other)
        val selfAdmin = TestUser.create(cxt, "gcat-self@example.com", level = ROLE.admin, userClient = CL.public)
        val full = TestUser.createFullAdmin(cxt, "gcat-full@example.com")

        // Its own administrator: listed, with its type, and it is the *only* unpublished thing they are shown --
        // the declared endpoints of the same section stay as hidden as they were.
        val catalog = listing(ownAdmin)
        catalog[EI.filtersAvailable] shouldBe false
        pathsOf(catalog) shouldContain pathOf(own)
        catalog[SCH.dDefs].toJsonMapOrEmpty().keys shouldContain typeOf(own)
        endpointsOf(catalog).filter { it[EI.publicApi] != true }.map { it[EI.path] } shouldBe listOf(pathOf(own))
        pathsOf(catalog) shouldNotContain UADEP.reports
        pathsOf(catalog) shouldNotContain pathOf(other)
        // Shown, not published: the entry says so itself, and asking for the unpublished set lifts nothing more.
        endpointsOf(catalog).single { it[EI.path] == pathOf(own) }[EI.publicApi] shouldBe false
        pathsOf(listing(ownAdmin, mapOf(EI.publicApi to false))) shouldNotContain UADEP.reports
        // The single lookup answers as the listing does.
        pathsOf(lookedUp(ownAdmin, pathOf(own))) shouldBe listOf(pathOf(own))
        lookedUp(ownAdmin, pathOf(own))[SCH.dDefs].toJsonMapOrEmpty().keys shouldContain typeOf(own)
        endpointsOf(lookedUp(ownAdmin, pathOf(other))).shouldBeEmpty()
        endpointsOf(lookedUp(ownAdmin, UADEP.reports)).shouldBeEmpty()

        // An ordinary user of the client, another client's administrator, and one who administers only their own
        // users in `public`: none is shown it, by the listing or the lookup.
        for (user in listOf(ownUser, otherAdmin, selfAdmin)) {
            pathsOf(listing(user)) shouldNotContain pathOf(own)
            endpointsOf(lookedUp(user, pathOf(own))).shouldBeEmpty()
        }
        // The other client's administrator is shown their own, and may not ask for this client's surface at all.
        pathsOf(listing(otherAdmin)) shouldContain pathOf(other)
        otherAdmin.expectError(EXC.badInput, "/schema/endpoints", args = mapOf(EI.client to own))

        // One who sees every client administers each: shown it when they name the client, and by the lookup.
        pathsOf(listing(full, mapOf(EI.client to own))) shouldContain pathOf(own)
        pathsOf(lookedUp(full, pathOf(own))) shouldBe listOf(pathOf(own))
    }

    "what was generated is what is shown: removed, it is gone from the catalog too" {
        val ownAdmin = TestUser.create(cxt, "gcat-admin@own.test", level = ROLE.admin, userClient = own)
        publish(own to null, other to null)
        pathsOf(listing(ownAdmin)) shouldNotContain pathOf(own)
        endpointsOf(lookedUp(ownAdmin, pathOf(own))).shouldBeEmpty()
        schema.schemaStore.endpoints.values.none { schema.isGenerated(it) && it.client in setOf(own, other) } shouldBe true
    }
})
