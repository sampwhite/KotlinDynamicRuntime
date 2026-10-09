package com.dynamicruntime.kdn

import com.dynamicruntime.common.context.ENV
import com.dynamicruntime.common.context.KdrCxt
import com.dynamicruntime.common.endpoint.EI
import com.dynamicruntime.common.endpoint.EP
import com.dynamicruntime.common.endpoint.EndpointKind
import com.dynamicruntime.common.endpoint.HttpMethod
import com.dynamicruntime.common.endpoint.KdrEndpoint
import com.dynamicruntime.common.endpoint.SchModuleBuilder
import com.dynamicruntime.common.exception.EXC
import com.dynamicruntime.common.exception.KdrException
import com.dynamicruntime.common.gedra.ClientAudience
import com.dynamicruntime.common.gedra.ClientDef
import com.dynamicruntime.common.gedra.ClientUsageType
import com.dynamicruntime.common.gedra.GedraConfigReload
import com.dynamicruntime.common.gedra.GedraConfigService
import com.dynamicruntime.common.gedra.gedraConfig
import com.dynamicruntime.common.http.request.RequestService
import com.dynamicruntime.common.http.request.SECT
import com.dynamicruntime.common.naming.clientNamespace
import com.dynamicruntime.common.schema.SCH
import com.dynamicruntime.common.schema.SCT
import com.dynamicruntime.common.schema.schemaDefs
import com.dynamicruntime.common.startup.GeneratedSurface
import com.dynamicruntime.common.startup.SchemaService
import com.dynamicruntime.common.user.TestUser
import com.dynamicruntime.common.util.toJsonListOfMaps
import com.dynamicruntime.common.util.toJsonMapOrEmpty
import com.dynamicruntime.common.util.toOptStr
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldNotContain
import io.kotest.matchers.maps.shouldContainKey
import io.kotest.matchers.maps.shouldNotContainKey
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain

/**
 * Endpoints **generated** for a client after the schema was compiled, and the types they use, folded into the
 * published schema (issue #1087): served, advertised under that client, resolvable in every store, kept through
 * another client's reload, and removable -- with everything a generator could get wrong refused before anything is
 * published.
 *
 * On a shared instance, under clients of its own; the last case removes what the rest published. The env-auth one,
 * because the catalog shows a caller without env auth the published API alone (issue #489), and a client-administration
 * endpoint is never that.
 */
class SchemaPublishGeneratedTest : StringSpec({
    val cxt = TestInstances.envAuth("publishGenerated")
    val schema = SchemaService.get(cxt)
    val admin = TestUser.createFullAdmin(cxt, "publish-generated@example.com")

    // A client with no store of its own -- no configuration defines it, so it varies nothing -- one that varies its
    // schema, and two more for the cases that need a client nothing else has touched.
    val plain = "pgenplain"
    val varying = "pgenvary"
    val bare = "pgenbare"
    val bystander = "pgenby"

    fun namespaceOf(client: String) = "kdr.gentest.$client"
    fun typeOf(client: String) = "${namespaceOf(client)}.Thing"
    fun pathOf(client: String, name: String = "thing") = "/${SECT.clientAdmin}/$client/gentest/$name"

    /** An endpoint bound to [client], answering `{ n }` in a type of the surface's own. */
    fun endpoint(client: String, n: Int, path: String = pathOf(client), boundTo: String? = client, outputType: String = typeOf(client)) = KdrEndpoint(
        path = path, method = HttpMethod.GET, kind = EndpointKind.general, namespace = namespaceOf(client),
        description = "A generated endpoint of '$client'.", inputFields = null, inputTypeRef = null, includeLimit = false,
        outputSchema = SchModuleBuilder(cxt, namespaceOf(client)).scalarOutput(EP.results, "What it answers.", outputType),
        handler = { _, _ -> mapOf("n" to n) }, client = boundTo,
    )

    fun defs(client: String, build: (MutableMap<String, Any?>) -> Unit = {}): Map<String, Any?> =
        LinkedHashMap(
            schemaDefs(cxt, namespaceOf(client)) {
                type("Thing") {
                    type = SCT.kObject
                    property("n", "A number.", required = true) { type = SCT.integer }
                }
            },
        ).also(build)

    fun surface(client: String, n: Int) = GeneratedSurface(client, defs(client), listOf(endpoint(client, n)))

    /** Publishes, and drops the cached types the publication says are stale, as a caller must. */
    fun publish(vararg surfaces: Pair<String, GeneratedSurface?>): Set<String> =
        schema.publishGenerated(cxt, mapOf(*surfaces)).also { RequestService.get(cxt).evictTypes(it) }

    fun served(client: String): Any? = admin.getData(pathOf(client))["n"]
    fun catalog(client: String): Map<String, Any?> = admin.getData("/schema/endpoints", mapOf(EI.client to client, EP.limit to 1000))
    fun paths(catalog: Map<String, Any?>) = catalog[EI.endpoints].toJsonListOfMaps().map { it[EI.path].toOptStr() }

    fun asClient(client: String): KdrCxt = cxt.mkSubContext("pgen", client).also { it.userId = 9100L }

    /** Defines [client] in stored configuration and loads it -- a reload of that client; [alsoVaries] gives it a type of its own. */
    fun define(client: String, alsoVaries: Boolean) {
        val config = gedraConfig(cxt, "${client}cfg", clientNamespace(client), client) {
            defineClient(
                ClientDef(
                    clientId = client, name = client, usageType = ClientUsageType.dev,
                    audience = ClientAudience.internal, enabledEnvironments = setOf(ENV.unit, ENV.local),
                ),
            )
            if (alsoVaries) {
                type("PgenOwn") {
                    type = SCT.kObject
                    property("label", "A label.")
                }
            }
        }
        GedraConfigService.get(cxt).writeConfig(asClient(client), config)
        GedraConfigReload.reloadClient(cxt, client)
    }

    "a generated endpoint is served, advertised under its client, and its type is in every store" {
        define(varying, alsoVaries = true)
        schema.clientStores shouldNotContainKey plain
        schema.clientStores shouldContainKey varying

        val keys = publish(plain to surface(plain, 7), varying to surface(varying, 8))
        keys shouldBe setOf("${pathOf(plain)}:GET", "${pathOf(varying)}:GET")

        // Served: the response is checked against the generated type, which resolved in the store of each client --
        // the global one for the client that varies nothing, its variant for the one that does.
        served(plain) shouldBe 7
        served(varying) shouldBe 8

        // Advertised under its client, with the type in `$defs` -- for the client with no store of its own too.
        for (client in listOf(plain, varying)) {
            val listed = catalog(client)
            paths(listed) shouldContain pathOf(client)
            listed[SCH.dDefs].toJsonMapOrEmpty().keys shouldContain typeOf(client)
        }
        // And under nobody else: not the shared surface, not another client's.
        paths(admin.getData("/schema/endpoints", mapOf(EP.limit to 1000))) shouldNotContain pathOf(plain)
        paths(catalog(varying)) shouldNotContain pathOf(plain)

        // Every store holds every generated type, and the one endpoint map.
        for (store in schema.clientStores.values + schema.schemaStore) {
            store.types shouldContainKey typeOf(plain)
            store.types shouldContainKey typeOf(varying)
            store.endpoints shouldContainKey "${pathOf(plain)}:GET"
        }
        // The variant is still the client's own document: its own type is there, and in no other store.
        schema.storeFor(varying).types shouldContainKey "${clientNamespace(varying)}.PgenOwn"
        schema.schemaStore.types shouldNotContainKey "${clientNamespace(varying)}.PgenOwn"
    }

    "a client's reload keeps every generated surface, its own and another's" {
        // The reload rebuilds the endpoint map; built from the shared endpoints and the copies alone, it would
        // drop both of these.
        GedraConfigReload.reloadClient(cxt, varying)
        served(plain) shouldBe 7
        served(varying) shouldBe 8
        // A third client's reload -- its first load -- keeps them too.
        define(bystander, alsoVaries = false)
        served(plain) shouldBe 7
        served(varying) shouldBe 8
        // A reload does not compound what was generated into the document the next one starts from: the variant
        // still has its own type, and a later publication still replaces the surface cleanly.
        schema.storeFor(varying).types shouldContainKey "${clientNamespace(varying)}.PgenOwn"
        publish(plain to surface(plain, 70)) shouldBe setOf("${pathOf(plain)}:GET")
        served(plain) shouldBe 70
        served(varying) shouldBe 8
    }

    "a surface needs no defined client, and replacing one says which cached types went stale" {
        publish(bare to surface(bare, 1)) shouldBe setOf("${pathOf(bare)}:GET")
        served(bare) shouldBe 1
        paths(catalog(bare)) shouldContain pathOf(bare)
        // Replaced by a surface at another address: both the address that went and the one that came.
        val moved = GeneratedSurface(bare, defs(bare), listOf(endpoint(bare, 2, path = pathOf(bare, "other"))))
        publish(bare to moved) shouldBe setOf("${pathOf(bare)}:GET", "${pathOf(bare, "other")}:GET")
        admin.expectError(EXC.notFound, pathOf(bare))
        admin.getData(pathOf(bare, "other"))["n"] shouldBe 2
    }

    "what a generator could get wrong is refused, and nothing is published" {
        val before = schema.schemaStore
        fun refused(surface: GeneratedSurface, client: String = surface.client): String =
            shouldThrow<KdrException> { schema.publishGenerated(cxt, mapOf(client to surface)) }.message.orEmpty()
                .also { schema.schemaStore shouldBe before }
        val c = "pgenbad"

        // An output schema naming a type that is not there: a fault of the publication, not of a first request.
        refused(GeneratedSurface(c, defs(c), listOf(endpoint(c, 1, outputType = "${namespaceOf(c)}.Missing")))) shouldContain "does not resolve"
        // A section with no access rules would be served to anyone.
        refused(GeneratedSurface(c, defs(c), listOf(endpoint(c, 1, path = "/pgenUnruled/$c/thing")))) shouldContain "have no access rules"
        // An endpoint not bound to the surface's client, or bound to nobody.
        refused(GeneratedSurface(c, defs(c), listOf(endpoint(c, 1, boundTo = plain)))) shouldContain "is bound to '$plain'"
        refused(GeneratedSurface(c, defs(c), listOf(endpoint(c, 1, boundTo = null)))) shouldContain "is bound to no client"
        // A surface filed under a client it does not say it is for.
        refused(surface(c, 1), client = "pgenother") shouldContain "it says it is for '$c'"
        // The address of a declared endpoint, and of another client's generated one.
        refused(GeneratedSurface(c, defs(c), listOf(endpoint(c, 1, path = "/${SECT.clientAdmin}/users")))) shouldContain "already published at"
        refused(GeneratedSurface(c, defs(c), listOf(endpoint(c, 1, path = pathOf(plain))))) shouldContain "already published at '${pathOf(plain)}:GET'"
        // A type the schema already has, or another surface does.
        val shared = schema.schemaStore.defs.keys.first { it !in setOf(typeOf(plain), typeOf(varying), typeOf(bare)) }
        refused(GeneratedSurface(c, defs(c) { it[shared] = mapOf(SCH.type to SCT.kObject) }, listOf(endpoint(c, 1)))) shouldContain "already has a type named '$shared'"
        refused(GeneratedSurface(c, defs(c) { it[typeOf(plain)] = mapOf(SCH.type to SCT.kObject) }, listOf(endpoint(c, 1)))) shouldContain "already has a type named '${typeOf(plain)}'"
        // Types that are not self-contained: a reference out of the surface, even to a type the schema has.
        val leaning = defs(c) {
            it["${namespaceOf(c)}.Leaning"] = mapOf(
                SCH.type to SCT.kObject,
                SCH.properties to mapOf("other" to mapOf(SCH.dRef to "#/${SCH.dDefs}/$shared")),
            )
        }
        refused(GeneratedSurface(c, leaning, listOf(endpoint(c, 1)))) shouldContain "may refer only to each other"

        // One bad surface among several refuses the lot.
        shouldThrow<KdrException> {
            schema.publishGenerated(cxt, mapOf(c to surface(c, 1), "pgenother" to GeneratedSurface("pgenother", defs("pgenother"), listOf(endpoint("pgenother", 1, boundTo = null)))))
        }
        schema.schemaStore shouldBe before
        admin.expectError(EXC.notFound, pathOf(c))
        // And what was published before is as it was.
        served(plain) shouldBe 70
    }

    "removing a surface takes its endpoints and types away and says which cached types went" {
        publish(plain to null, varying to null, bare to null) shouldBe
            setOf("${pathOf(plain)}:GET", "${pathOf(varying)}:GET", "${pathOf(bare, "other")}:GET")
        for (client in listOf(plain, varying)) {
            admin.expectError(EXC.notFound, pathOf(client))
            paths(catalog(client)) shouldNotContain pathOf(client)
        }
        for (store in schema.clientStores.values + schema.schemaStore) {
            store.types shouldNotContainKey typeOf(plain)
            store.types shouldNotContainKey typeOf(varying)
        }
        // Removing what is not there is nothing, and says so.
        publish(plain to null) shouldBe emptySet()
    }
})
