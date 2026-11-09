package com.dynamicruntime.kdn

import com.dynamicruntime.common.cfact.CFD
import com.dynamicruntime.common.context.ACFG
import com.dynamicruntime.common.context.CL
import com.dynamicruntime.common.endpoint.EI
import com.dynamicruntime.common.endpoint.EP
import com.dynamicruntime.common.endpoint.KdrEndpoint
import com.dynamicruntime.common.endpoint.clientPath
import com.dynamicruntime.common.exception.EXC
import com.dynamicruntime.common.exception.KdrException
import com.dynamicruntime.common.gedra.GEP
import com.dynamicruntime.common.gedra.GedraConfigReload
import com.dynamicruntime.common.gedra.sandboxOf
import com.dynamicruntime.common.http.request.ROLE
import com.dynamicruntime.common.http.request.SECT
import com.dynamicruntime.common.schema.SCH
import com.dynamicruntime.common.schema.SCT
import com.dynamicruntime.common.startup.GeneratedSurface
import com.dynamicruntime.common.startup.SchemaService
import com.dynamicruntime.common.user.AEP
import com.dynamicruntime.common.user.TestUser
import com.dynamicruntime.common.user.UADEP
import com.dynamicruntime.common.util.toJsonMapOrEmpty
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.core.spec.style.stringSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldNotContain
import io.kotest.matchers.maps.shouldContainKey
import io.kotest.matchers.maps.shouldNotContainKey
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldContain

/**
 * Endpoints generated for a client after the schema was compiled (issue #1739 gathered these): folded into the published
 * schema and kept through reloads (issue #1087), and shown in the catalog to its administrator alone where a caller has
 * no env auth (issue #1128). Each part below is one of the specs that used to be a file of its own, kept whole.
 */
class SchemaGeneratedEndpointsTest : StringSpec({
    include(schemaPublishGeneratedTests)
    include(schemaGeneratedCatalogTests)
})

/**
 * Endpoints **generated** for a client after the schema was compiled, and the types they use, folded into the
 * published schema (issue #1087): served, advertised under that client, resolvable in every store, kept through
 * another client's reload, and removable -- with everything a generator could get wrong refused before anything is
 * published.
 *
 * On a shared instance, under clients of its own; the last case removes what the rest published. The env-auth one,
 * so the whole catalog can be read around what was generated; what the catalog shows of a generated endpoint to a
 * caller without env auth (issue #1128) is the catalog part's, below.
 */
private val schemaPublishGeneratedTests = stringSpec {
    val cxt = TestInstances.envAuth("publishGenerated")
    val schema = SchemaService.get(cxt)
    val admin = TestUser.createFullAdmin(cxt, "publish-generated@example.com")

    // A client with no store of its own -- no configuration defines it, so it varies nothing -- one that varies its
    // schema, and two more for the cases that need a client nothing else has touched.
    val plain = "pgenplain"
    val varying = "pgenvary"
    val bare = "pgenbare"
    val bystander = "pgenby"

    // The hand-built surfaces and the clients they are bound to, shared with the catalog's test of them.
    val gen = GeneratedSurfaces(cxt, "gentest", setupUserId = 9100L)
    fun namespaceOf(client: String) = gen.namespaceOf(client)
    fun typeOf(client: String) = gen.typeOf(client)
    fun pathOf(client: String, name: String = "thing") = gen.pathOf(client, name)
    fun endpoint(client: String, n: Int, path: String = pathOf(client), boundTo: String? = client, outputType: String = typeOf(client)) =
        gen.endpoint(client, n, path, boundTo, outputType)
    fun defs(client: String, build: (MutableMap<String, Any?>) -> Unit = {}) = gen.defs(client, build)
    fun surface(client: String, n: Int) = gen.surface(client, n)
    fun publish(vararg surfaces: Pair<String, GeneratedSurface?>): Set<String> = gen.publish(*surfaces)
    fun define(client: String, alsoVaries: Boolean) = gen.define(client, alsoVaries)

    fun served(client: String): Any? = admin.getData(pathOf(client))["n"]
    fun catalog(client: String): Map<String, Any?> = admin.catalog(mapOf(EI.client to client))
    fun paths(catalog: Map<String, Any?>) = TestUser.pathsIn(catalog)

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
        paths(admin.catalog()) shouldNotContain pathOf(plain)
        paths(catalog(varying)) shouldNotContain pathOf(plain)

        // Every store holds every generated type, and the one endpoint map.
        for (store in schema.clientStores.values + schema.schemaStore) {
            store.types shouldContainKey typeOf(plain)
            store.types shouldContainKey typeOf(varying)
            store.endpoints shouldContainKey "${pathOf(plain)}:GET"
        }
        // The variant is still the client's own document: its own type is there, and in no other store.
        schema.storeFor(varying).types shouldContainKey gen.ownTypeOf(varying)
        schema.schemaStore.types shouldNotContainKey gen.ownTypeOf(varying)
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
        schema.storeFor(varying).types shouldContainKey gen.ownTypeOf(varying)
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
        fun refused(surface: GeneratedSurface, client: String = surface.client): String {
            val before = schema.schemaStore
            return shouldThrow<KdrException> { schema.publishGenerated(cxt, mapOf(client to surface)) }.message.orEmpty()
                .also { schema.schemaStore shouldBe before }
        }
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
        refused(GeneratedSurface(c, defs(c), listOf(endpoint(c, 1, path = "/${SECT.clientAdmin}/users")))) shouldContain
            "already published at '/${SECT.clientAdmin}/users:GET' -- a declared one"
        refused(GeneratedSurface(c, defs(c), listOf(endpoint(c, 1, path = pathOf(plain))))) shouldContain
            "already published at '${pathOf(plain)}:GET', generated for another client"
        // A keyword the boot judges, which nothing would judge here -- in a type, however deep, or on an endpoint.
        fun gated(keyword: String) = defs(c) {
            it["${namespaceOf(c)}.Gated"] = mapOf(
                SCH.type to SCT.kObject,
                SCH.properties to mapOf("inner" to mapOf(SCH.type to SCT.array, SCH.items to mapOf(SCH.type to SCT.string, keyword to "x"))),
            )
        }
        for (keyword in listOf(SCH.visibleWhen, SCH.layout, SCH.errors, SCH.optionsSource)) {
            refused(GeneratedSurface(c, gated(keyword), listOf(endpoint(c, 1)))) shouldContain "its type '${namespaceOf(c)}.Gated' carries '$keyword'"
        }
        val gatedOutput = endpoint(c, 1).let { e ->
            KdrEndpoint(
                e.path, e.method, e.kind, e.namespace, e.description, null, null, false,
                e.outputSchema + (SCH.visibleWhen to "kdr:app"), handler = e.handler, client = c,
            )
        }
        refused(GeneratedSurface(c, defs(c), listOf(gatedOutput))) shouldContain "its endpoint '${pathOf(c)}:GET' carries '${SCH.visibleWhen}'"
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

        // A property that is merely *named* like one of those keywords is a property: a report's column may be
        // called anything.
        val named = defs(c) {
            it["${namespaceOf(c)}.Named"] = mapOf(SCH.type to SCT.kObject, SCH.properties to mapOf(SCH.layout to mapOf(SCH.type to SCT.string)))
        }
        publish(c to GeneratedSurface(c, named, listOf(endpoint(c, 1))))
        publish(c to null)
        val before = schema.schemaStore

        // One bad surface among several refuses the lot.
        shouldThrow<KdrException> {
            schema.publishGenerated(cxt, mapOf(c to surface(c, 1), "pgenother" to GeneratedSurface("pgenother", defs("pgenother"), listOf(endpoint("pgenother", 1, boundTo = null)))))
        }
        schema.schemaStore shouldBe before
        admin.expectError(EXC.notFound, pathOf(c))
        // And what was published before is as it was.
        served(plain) shouldBe 70
    }

    // The other order: the generated endpoint is there first, and the client then starts to vary -- which mints it
    // copies of the client-shaped endpoints, one of them at that address. Neither may replace the other silently.
    "a client's copy minted later at a generated endpoint's address refuses that reload" {
        val late = "pgenlate"
        val copyPath = clientPath(GEP.formDocs, late)
        publish(late to GeneratedSurface(late, defs(late), listOf(endpoint(late, 5, path = copyPath))))
        admin.getData(copyPath)["n"] shouldBe 5
        // Defining the client makes it vary, and its copy of the forms listing lands on the generated endpoint.
        shouldThrow<KdrException> { define(late, alsoVaries = false) }.message.orEmpty() shouldContain "already published at '$copyPath:GET'"
        // Refused whole: the generated endpoint still answers, and the other surfaces stand.
        admin.getData(copyPath)["n"] shouldBe 5
        served(plain) shouldBe 70
        // With the surface gone the same reload goes through, and the address is the copy's.
        publish(late to null)
        GedraConfigReload.reloadClient(cxt, late)
        schema.schemaStore.endpoints.getValue("$copyPath:GET").client shouldBe late
        schema.schemaStore.endpoints.getValue("$copyPath:GET").namespace shouldBe schema.schemaStore.endpoints.getValue("${GEP.formDocs}:GET").namespace
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
}

/**
 * Who is **shown** a client's generated endpoints by a node where the caller has no env auth (issue #1128): the
 * catalog serves such a caller the published API alone (issue #489), with one exception -- an endpoint generated for
 * a client, shown to a caller who oversees that client. Nobody else is shown it, nothing declared gains by it -- not
 * a declared endpoint of the same section, not a client's own copy of one -- and the single lookup hides what the
 * listing hides.
 *
 * On the shared default instance, which assumes no env auth -- the production caller's case -- under clients of its
 * own; the last case removes what the first published, and so reads as its tail.
 */
private val schemaGeneratedCatalogTests = stringSpec {
    val cxt = TestInstances.default("generatedCatalog")
    val gen = GeneratedSurfaces(cxt, "gcattest", setupUserId = 9200L)
    val schema = gen.schema

    // One that varies -- so it has copies of the client-shaped endpoints, which are not generated -- with a sandbox;
    // and another, to be somebody else's.
    val own = "gcatown"
    val other = "gcatother"
    val sandbox = sandboxOf(own)
    fun pathOf(client: String) = gen.pathOf(client)

    fun pathsOf(catalog: Map<String, Any?>) = TestUser.pathsIn(catalog)
    fun endpointsOf(catalog: Map<String, Any?>) = TestUser.endpointsIn(catalog)
    fun unpublishedIn(catalog: Map<String, Any?>) = endpointsOf(catalog).filter { it[EI.publicApi] != true }.map { it[EI.path] }

    "a client's generated endpoint is listed to its administrator without env auth, and to nobody else" {
        // The premise: this node grants no env auth of its own accord, so every caller here is the production one.
        cxt.instanceConfig.get(ACFG.assumeEnvAuth) shouldNotBe true
        gen.define(own, alsoVaries = true, sandbox = true)
        gen.define(other)
        gen.publish(own to gen.surface(own, 1), other to gen.surface(other, 2), sandbox to gen.surface(sandbox, 3))
        schema.isGenerated(gen.endpoint(own, 1)) shouldBe true

        val ownAdmin = TestUser.create(cxt, "gcat-admin@own.test", level = ROLE.admin, userClient = own)
        val ownUser = TestUser.create(cxt, "gcat-user@own.test", userClient = own)
        val otherAdmin = TestUser.create(cxt, "gcat-admin@other.test", level = ROLE.admin, userClient = other)
        val selfAdmin = TestUser.create(cxt, "gcat-self@example.com", level = ROLE.admin, userClient = CL.public)
        val full = TestUser.createFullAdmin(cxt, "gcat-full@example.com")

        // Its own administrator: listed, with its type, and it is the *only* unpublished thing they are shown.
        val catalog = ownAdmin.catalog()
        catalog[EI.filtersAvailable] shouldBe false
        pathsOf(catalog) shouldContain pathOf(own)
        catalog[SCH.dDefs].toJsonMapOrEmpty().keys shouldContain gen.typeOf(own)
        unpublishedIn(catalog) shouldBe listOf(pathOf(own))
        pathsOf(catalog) shouldNotContain pathOf(other)
        // Shown, not published: the entry says so itself.
        endpointsOf(catalog).single { it[EI.path] == pathOf(own) }[EI.publicApi] shouldBe false

        // Nothing declared gains by it. A declared endpoint of the same section stays hidden ...
        pathsOf(catalog) shouldNotContain UADEP.reports
        // ... and so does the client's own **copy** of a declared one -- bound to the client, as a generated endpoint
        // is, open to this caller, as theirs is, and unpublished: all that tells the two apart is that it was not
        // generated.
        val copyPath = clientPath(CFD.cfactsPath, own)
        val copy = schema.schemaStore.endpoints.getValue("$copyPath:GET")
        copy.client shouldBe own
        copy.publicApi shouldBe false
        schema.isGenerated(copy) shouldBe false
        ownAdmin.client.sendJsonGetRequest(copyPath)[EP.status] shouldBe null
        pathsOf(catalog) shouldNotContain copyPath
        endpointsOf(ownAdmin.catalogLookup(copyPath)).shouldBeEmpty()

        // The request's `publicApi` cannot lift the rest, and asking for the published set still narrows to it.
        ownAdmin.catalog(mapOf(EI.publicApi to false)).let {
            unpublishedIn(it) shouldBe listOf(pathOf(own))
            pathsOf(it) shouldNotContain UADEP.reports
        }
        ownAdmin.catalog(mapOf(EI.publicApi to true)).let {
            unpublishedIn(it).shouldBeEmpty()
            endpointsOf(it).isNotEmpty() shouldBe true
        }

        // The single lookup hides what the listing hides.
        pathsOf(ownAdmin.catalogLookup(pathOf(own))) shouldBe listOf(pathOf(own))
        ownAdmin.catalogLookup(pathOf(own))[SCH.dDefs].toJsonMapOrEmpty().keys shouldContain gen.typeOf(own)
        endpointsOf(ownAdmin.catalogLookup(pathOf(other))).shouldBeEmpty()
        endpointsOf(ownAdmin.catalogLookup(UADEP.reports)).shouldBeEmpty()

        // An ordinary user of the client, another client's administrator, and one who administers only their own
        // users in `public`: none is shown it, by the listing or the lookup.
        for (user in listOf(ownUser, otherAdmin, selfAdmin)) {
            pathsOf(user.catalog()) shouldNotContain pathOf(own)
            endpointsOf(user.catalogLookup(pathOf(own))).shouldBeEmpty()
        }
        // The other client's administrator is shown their own, and may not ask for this client's surface at all.
        pathsOf(otherAdmin.catalog()) shouldContain pathOf(other)
        otherAdmin.expectError(EXC.badInput, "/schema/endpoints", args = mapOf(EI.client to own))

        // One who sees every client oversees each. The listing shows them a client's surface when they name it --
        // unnamed it is the shared one, with no client's endpoints, generated or not -- while the lookup answers for
        // an endpoint by its address, whichever surface it is on: an older difference between the two, and not one
        // of what publication hides.
        pathsOf(full.catalog(mapOf(EI.client to own))) shouldContain pathOf(own)
        pathsOf(full.catalog()) shouldNotContain pathOf(own)
        pathsOf(full.catalogLookup(pathOf(own))) shouldBe listOf(pathOf(own))
    }

    // The roles the rule reads are the ones the request acts with (issue #929), so it cannot show a sandbox's
    // generated endpoint to somebody the gate would then refuse.
    "in a sandbox it is shown to who acts as an administrator there, not to who merely holds the role" {
        // A member of the parent, granted `admin` in the sandbox: held, and withheld.
        val member = "gcat-member@own.test"
        TestUser.create(cxt, member, userClient = own)
        val granted = TestUser.create(cxt, member, level = ROLE.admin, userClient = sandbox)
        granted.selfRoles() shouldNotContain ROLE.admin
        pathsOf(granted.catalog()) shouldNotContain pathOf(sandbox)
        endpointsOf(granted.catalogLookup(pathOf(sandbox))).shouldBeEmpty()
        granted.expectError(EXC.notAuthorized, pathOf(sandbox))

        // A parent administrator who opens the sandbox acts as one in it: shown, and answered.
        val chief = TestUser.create(cxt, "gcat-chief@own.test", level = ROLE.admin, userClient = own)
        chief.postData(AEP.openSandbox, emptyMap())
        chief.selfClient() shouldBe sandbox
        pathsOf(chief.catalog()) shouldContain pathOf(sandbox)
        pathsOf(chief.catalogLookup(pathOf(sandbox))) shouldBe listOf(pathOf(sandbox))
        chief.getData(pathOf(sandbox))["n"] shouldBe 3
        // In the sandbox they oversee the sandbox, not the parent they came from.
        pathsOf(chief.catalog()) shouldNotContain pathOf(own)
    }

    "what was generated is what is shown: removed, it is gone from the catalog and from the record" {
        val ownAdmin = TestUser.create(cxt, "gcat-admin@own.test", level = ROLE.admin, userClient = own)
        gen.publish(own to null, other to null, sandbox to null)
        pathsOf(ownAdmin.catalog()) shouldNotContain pathOf(own)
        endpointsOf(ownAdmin.catalogLookup(pathOf(own))).shouldBeEmpty()
        // Asked of the endpoint itself, not of what is left in the store: its address is no longer on record as
        // generated, so a declared endpoint that came to sit there would not be taken for one.
        schema.isGenerated(gen.endpoint(own, 1)) shouldBe false
        schema.isGenerated(gen.endpoint(sandbox, 3)) shouldBe false
    }
}
