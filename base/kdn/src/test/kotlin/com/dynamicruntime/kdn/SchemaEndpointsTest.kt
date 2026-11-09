package com.dynamicruntime.kdn

import com.dynamicruntime.common.context.ACFG
import com.dynamicruntime.common.context.CL
import com.dynamicruntime.common.context.KdrCxt
import com.dynamicruntime.common.endpoint.EI
import com.dynamicruntime.common.endpoint.EP
import com.dynamicruntime.common.endpoint.ETAG
import com.dynamicruntime.common.endpoint.HttpMethod
import com.dynamicruntime.common.endpoint.clientPath
import com.dynamicruntime.common.endpoint.defaultEndpointListLimit
import com.dynamicruntime.common.exception.EXC
import com.dynamicruntime.common.gedra.CFEP
import com.dynamicruntime.common.gedra.GEP
import com.dynamicruntime.common.http.request.ROLE
import com.dynamicruntime.common.http.request.SECT
import com.dynamicruntime.common.http.request.TestHttpClient
import com.dynamicruntime.common.schema.MSCH
import com.dynamicruntime.common.schema.SCH
import com.dynamicruntime.common.startup.SS
import com.dynamicruntime.common.test.TVB
import com.dynamicruntime.common.user.ADEP
import com.dynamicruntime.common.user.ADF
import com.dynamicruntime.common.user.TestUser
import com.dynamicruntime.common.user.UADEP
import com.dynamicruntime.common.util.toJsonListOfMaps
import com.dynamicruntime.common.util.toJsonListOfStrings
import com.dynamicruntime.common.util.toJsonMap
import com.dynamicruntime.common.util.toJsonMapOrEmpty
import com.dynamicruntime.common.util.toOptStr
import io.kotest.core.spec.style.StringSpec
import io.kotest.core.spec.style.stringSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldContainAll
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldNotContain
import io.kotest.matchers.collections.shouldNotContainAnyOf
import io.kotest.matchers.ints.shouldBeLessThan
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe

/**
 * Exercises the two SchemaService endpoints through the in-process client. Because these run under
 * [Startup.mkTestBootCxt] (which sets `validateResponseSchema`), every response is also validated against
 * the endpoint's output schema, so a non-conforming catalog or sample item would fail the call.
 *
 * Also, since issue #1739, the tests that were `SchemaEndpointResolveClientTest`, `SamePathVerbTest`, `PublishedClientAdminCatalogTest`, each kept whole below.
 */
class SchemaEndpointsTest : StringSpec({
    include(schemaEndpointsTests())
    include(schemaEndpointResolveClientTests())
    include(samePathVerbTests())
    include(publishedClientAdminCatalogTests())
})

/** This file's own tests, as the class describes them. */
private fun schemaEndpointsTests() = stringSpec {

    // /schema/endpoints is a general endpoint: its result carries the endpoint renderings and a shared $defs.
    fun results(resp: Map<String, Any?>): Map<String, Any?> = resp[EP.results]!!.toJsonMap()
    fun catalogEndpoints(resp: Map<String, Any?>): List<Map<String, Any?>> =
        results(resp)[EI.endpoints].toJsonListOfMaps()

    // /demo/schema/sample is a list endpoint: its payload is under `items`.
    fun items(resp: Map<String, Any?>): List<Map<String, Any?>> =
        resp[EP.items].toJsonListOfMaps()

    // These tests all boot the same (default) instance and only read, so they share one instance -- its
    // component/schema init is cached by instance name and runs once -- and vary only the inexpensive context name.
    //
    // Env-authed, because the catalog is a developer/operator surface: without env auth a caller sees only the
    // published endpoints (issue #489), which is a separate case the restriction test below covers on purpose.
    // `assumeEnvAuth` makes this instance behave like a developer's own box (where env auth is auto-granted), so
    // every caller browses the whole catalog -- rather than sprinkling the header on each.
    fun client(cxtName: String): TestHttpClient =
        TestHttpClient(
            TestInstances.envAuth(cxtName).instanceConfig,
        )

    $$"/schema/endpoints renders every endpoint and a shared $defs" {
        val client = client("schemaList")

        val resp = client.sendJsonGetRequest("/schema/endpoints")
        val eps = catalogEndpoints(resp)
        eps.map { it[EI.path] } shouldContainAll listOf("/health", "/schema/endpoints", "/demo/schema/sample")

        val health = eps.first { it[EI.path] == "/health" }
        health.keys shouldContainAll
            listOf(EI.path, EI.method, EI.kind, EI.namespace, EI.description, EI.inputSchema, EI.outputSchema)
        health[EI.namespace] shouldBe "kdr.node"
        health[EI.method] shouldBe "GET"
        // /health takes no parameters: its rendered input schema is a closed, empty object.
        health[EI.inputSchema]!!.toJsonMap()[SCH.additionalProperties] shouldBe false

        // The shared $defs closes over the types the renderings reference (by $ref), returned once each:
        // /health's output refs node.Health; /demo/schema/sample's input flattens SampleQuery, whose `filter` refs
        // SampleFilter, and its output refs SampleItem, which refs SampleDetails.
        val defs = results(resp)[SCH.dDefs]!!.toJsonMap()
        defs.keys shouldContainAll listOf("kdr.node.Health", "kdr.schema.SampleFilter", "kdr.schema.SampleItem", "kdr.schema.SampleDetails")
        // SampleQuery itself is dissolved into flat input fields, so it is NOT a returned def.
        defs.keys shouldNotContain "kdr.schema.SampleQuery"
    }

    "/schema/endpoints filters by namespace, method, and path regex" {
        val client = client("schemaFilters")

        fun paths(params: Map<String, Any?>): List<Any?> =
            catalogEndpoints(client.sendJsonGetRequest("/schema/endpoints", params)).map { it[EI.path] }

        paths(mapOf(EI.namespace to "kdr.node")) shouldBe listOf("/health")
        // The method filter returns only POST endpoints (which include /demo/schema/sample).
        val posts = catalogEndpoints(client.sendJsonGetRequest("/schema/endpoints", mapOf(EI.method to "POST")))
        posts.map { it[EI.path] } shouldContain "/demo/schema/sample"
        posts.map { it[EI.method] }.toSet() shouldBe setOf("POST")
        // `schema` is now only the catalog's own endpoints and the schema for schema (issue #1056): the two
        // exercise surfaces that used to sit beside them moved to the purpose-named roots (issue #270) -- the
        // fixture to `/fixture/`, the demo to `/demo/`. The namespace they are *declared* in is still `schema`;
        // the section is not.
        paths(mapOf(SS.pathRegex to "^/schema/")) shouldBe listOf("/schema/endpoint", "/schema/endpoints", MSCH.path)
        paths(mapOf(SS.pathRegex to "^/fixture/schema/")) shouldBe listOf("/fixture/schema/complex")
    }

    "/schema/endpoints caps the number of endpoints by limit" {
        val client = client("schemaLimit")
        catalogEndpoints(client.sendJsonGetRequest("/schema/endpoints", mapOf(EP.limit to 2))).size shouldBe 2
    }

    "/schema/endpoints lists the whole catalog unless asked for fewer" {
        // A page shows every endpoint at once, so the default is the whole catalog, not a list endpoint's 100.
        val client = client("schemaWhole")
        val all = catalogEndpoints(client.sendJsonGetRequest("/schema/endpoints", mapOf(EP.limit to 10_000)))
        catalogEndpoints(client.sendJsonGetRequest("/schema/endpoints")).size shouldBe all.size
        val limit = all.single { it[EI.path] == "/schema/endpoints" }[EI.inputSchema].toJsonMapOrEmpty()[SCH.properties]
            .toJsonMapOrEmpty()[EP.limit].toJsonMapOrEmpty()
        limit[SCH.default].toString() shouldBe defaultEndpointListLimit.toString()
    }

    "/schema/endpoint looks up a single endpoint by exact method and path, in the catalog shape" {
        val client = client("schemaOne")
        val resp = client.sendJsonGetRequest("/schema/endpoint", mapOf(EI.method to "GET", EI.path to "/health"))
        // Same shape as /schema/endpoints: an `endpoints` list (here of one) plus a shared `$defs`.
        val eps = catalogEndpoints(resp)
        eps.map { it[EI.path] } shouldBe listOf("/health")
        eps.single()[EI.method] shouldBe "GET"
        (results(resp)[SCH.dDefs]!!.toJsonMap()).keys shouldContain "kdr.node.Health"
    }

    "/schema/endpoint returns an empty list (not a 404) when nothing matches" {
        val client = client("schemaOneMiss")
        val resp = client.sendJsonGetRequest("/schema/endpoint", mapOf(EI.method to "GET", EI.path to "/nope"))
        catalogEndpoints(resp).shouldBeEmpty()
    }

    "/schema/endpoint requires both method and path" {
        val client = client("schemaOneBad")
        client.sendGetRequest("/schema/endpoint", mapOf(EI.method to "GET")).rptStatusCode shouldBe 400
    }

    "/demo/schema/sample returns a nested, schema-conforming list, with limit truncation" {
        val client = client("schemaSample")

        // A nested request exercising a choice list, a date, and a nested filter object.
        val full = client.sendJsonPostRequest(
            "/demo/schema/sample",
            mapOf(
                SS.filter to mapOf(SS.minCount to 1, SS.activeOnly to false),
                SS.categories to listOf("alpha", "beta"),
                SS.sinceDate to "2020-01-01",
            ),
        )
        val allItems = items(full)
        allItems.size shouldBe 15
        // The item is nested and carries choice/date/bool/int values.
        val details = allItems.first()[SS.details]!!.toJsonMap()
        details.keys shouldContainAll listOf(SS.score, SS.tags, SS.rank)

        // `limit` truncates the returned items.
        items(client.sendJsonPostRequest("/demo/schema/sample", mapOf(EP.limit to 5))).size shouldBe 5
    }

    // The catalog answers per caller (issue #211), so this one boots its own instance and makes real users
    // rather than joining the shared read-only instance above.
    "the catalog shows an endpoint only to a caller who could actually call it" {
        // Env-authed (issue #489): this test is about *access* filtering, so the caller must see the whole
        // catalog and the access gate must be the only thing narrowing it -- not the publicApi restriction.
        val cxt = TestInstances.envAuth("schemaVisibility")
        fun pathsFor(client: TestHttpClient): List<Any?> =
            catalogEndpoints(client.sendJsonGetRequest("/schema/endpoints")).map { it[EI.path] }

        // Anonymous and a logged-in user without the role, both see an admin-free catalog -- the second case
        // being the one a login does not fix. Neither loses the ordinary endpoints.
        val anonPaths = pathsFor(TestHttpClient(cxt.instanceConfig))
        anonPaths shouldNotContain ADEP.users
        anonPaths shouldContain "/health"

        val plain = TestUser.create(cxt, "looker@other.com")
        val plainPaths = pathsFor(plain.client)
        plainPaths shouldNotContain ADEP.users
        plainPaths shouldContain "/health"

        // The admin sees them, which is what makes the two assertions above about privilege rather than about
        // the admin endpoints having quietly stopped being registered.
        val chief = TestUser.createFullAdmin(cxt, "schema-chief@other.com")
        val chiefPaths = pathsFor(chief.client)
        chiefPaths shouldContainAll listOf(ADEP.users, ADEP.userSetRoles, "/health")

        // And the ladder reaches the listing, not just the gate (issue #212): the admin is shown the operator
        // section despite not holding `operator`, because that is who the dispatcher would actually admit. A
        // plain-membership test here would hide an endpoint this caller can run -- the same advertise-versus-
        // serve drift, one rung further down.
        chief.selfRoles().contains(ROLE.operator) shouldBe false
        chiefPaths shouldContain "/operator/system/info"
        plainPaths shouldNotContain "/operator/system/info"

        // A role granted after login shows up without a re-login, matching what the gate would already let
        // through. The catalog's own section is anonymous, so nothing refreshes roles on its behalf unless it
        // asks -- and filtering on the login-time cookie would hide these endpoints for the cookie's whole
        // 30-day life, from exactly the person just given the role.
        //
        // Granting `admin` opens the scoped-admin (`clientAdmin`) surface; `operator` is no longer the example
        // here because #464 fenced that surface behind the `allClients` capability, which no endpoint grants.
        val newAdmin = TestUser.create(cxt, "fresh-adm@other.com")
        pathsFor(newAdmin.client) shouldNotContain UADEP.users
        chief.postData(
            ADEP.userSetRoles,
            mapOf(ADF.userId to newAdmin.userId, ADF.roles to listOf(ROLE.user, ROLE.admin)),
        )
        pathsFor(newAdmin.client) shouldContain UADEP.users

        // The single-endpoint lookup filters identically. Without this it would be a one-call way around the
        // hiding, since it returns the same shape from the same store.
        fun lookup(client: TestHttpClient, path: String): List<Map<String, Any?>> =
            catalogEndpoints(client.sendJsonGetRequest("/schema/endpoint", mapOf(EI.method to "GET", EI.path to path)))
        lookup(plain.client, ADEP.users).shouldBeEmpty()
        lookup(chief.client, ADEP.users).map { it[EI.path] } shouldContain ADEP.users
    }

    // ---- _debug=explainAccess (issue #215) ----------------------------------

    "explainAccess names what the filter withheld, and the role each withheld section wants" {
        val cxt = TestInstances.envAuth("schemaExplain")
        val plain = TestUser.create(cxt, "explain@other.com")

        val resp = plain.client.sendJsonGetRequest("/schema/endpoints", mapOf(EP.debug to SS.explainAccess.name))
        val explained = resp[EP.meta]!!.toJsonMap()[SS.accessExplained]!!.toJsonMap()

        // The roles the filter actually compared -- the value whose staleness was the #211 defect, and the
        // reason reporting it is worth anything.
        explained[SS.actingRoles] shouldBe listOf(ROLE.user)

        val withheld = explained[SS.withheld].toJsonListOfMaps()
        val bySection = withheld.associateBy { it[SS.section] }
        bySection.keys shouldContainAll listOf("admin", "operator")
        // The full-scope surface withholds itself on two counts and reports both: the level and the
        // capability that qualifies it. Reporting only one would explain half a refusal.
        bySection["admin"]!![SS.requiredRole] shouldBe ROLE.admin
        bySection["admin"]!![SS.requiredCapability] shouldBe ROLE.allClients
        bySection["operator"]!![SS.requiredRole] shouldBe ROLE.operator
        // Since #464 the operator section is a deployment surface too: it wants the capability as well as the
        // level, so a client-scoped admin (or a client-confined operator) is laddered off it.
        bySection["operator"]!![SS.requiredCapability] shouldBe ROLE.allClients
        bySection["admin"]!![EI.endpoints].toJsonListOfStrings() shouldContain ADEP.users

        // The explanation and the listing are two readings of one decision, so they must partition the store:
        // nothing may be both shown and reported as withheld. Recomputing the explanation separately is
        // exactly how that would stop being true.
        val shown = catalogEndpoints(resp).map { it[EI.path] }.toSet()
        val hidden = withheld.flatMap { it[EI.endpoints].toJsonListOfStrings() }.toSet()
        shown intersect hidden shouldBe emptySet()
    }

    "the X-Kdr-Debug header carries the tag the same as the _debug param (issue #517, slice 3)" {
        val cxt = TestInstances.envAuth("schemaExplainHdr")
        val client = TestUser.create(cxt, "explain-hdr@other.com").client
        client.setHeader(EP.debugHeader, SS.explainAccess.name)

        // No `_debug` param anywhere -- the tag rode in on the header, which is the app-bar box's channel.
        val resp = client.sendJsonGetRequest("/schema/endpoints")
        resp[EP.meta].toJsonMapOrEmpty().containsKey(SS.accessExplained) shouldBe true
    }

    "an explicit _debug param overrides the header for that request" {
        val cxt = TestInstances.envAuth("schemaExplainOver")
        val client = TestUser.create(cxt, "explain-over@other.com").client
        client.setHeader(EP.debugHeader, SS.explainAccess.name)

        // The param names a different tag, so the header's explainAccess does not apply: the per-request value
        // wins over the persistent baseline. (`explainInput` is a valid tag name; /schema/endpoints ignores it.)
        val resp = client.sendJsonGetRequest("/schema/endpoints", mapOf(EP.debug to SS.explainInput.name))
        resp[EP.meta].toJsonMapOrEmpty().containsKey(SS.accessExplained) shouldBe false
    }

    "a malformed or blank X-Kdr-Debug header is dropped, not fatal, and the request is served normally" {
        val cxt = TestInstances.envAuth("schemaHdrBad")
        // Each bad shape a header can take: over the length cap, a non-variable-name, and blank (the vacuous
        // `all {}` case). None throws -- unlike an explicit param -- and none turns the tag on.
        for (bad in listOf("x".repeat(EP.debugMaxLength + 1), "not a name!", "   ")) {
            val client = TestUser.create(cxt, "hdr-bad-${bad.hashCode()}@other.com").client
            client.setHeader(EP.debugHeader, bad)
            val resp = client.sendJsonGetRequest("/schema/endpoints")
            // Served (endpoints came back), and the diagnostic did not fire.
            catalogEndpoints(resp).map { it[EI.path] } shouldContain "/health"
            resp[EP.meta].toJsonMapOrEmpty().containsKey(SS.accessExplained) shouldBe false
        }
    }

    "the header tag is fenced on an instance that is not a test instance" {
        // The same fence as the param (issue #215): a real node must not answer explainAccess however it is
        // asked -- header or query. Env-authed so the caller still sees the whole catalog.
        val cxt = TestInstances.envAuthNotTestInstance("schemaHdrProd")
        cxt.instanceConfig.isTestInstance shouldBe false // guard the premise
        val client = TestUser.create(cxt, "hdr-prod@other.com").client
        client.setHeader(EP.debugHeader, SS.explainAccess.name)

        val resp = client.sendJsonGetRequest("/schema/endpoints")
        resp[EP.meta].toJsonMapOrEmpty().containsKey(SS.accessExplained) shouldBe false
        catalogEndpoints(resp).map { it[EI.path] } shouldContain "/health"
    }

    "explainAccess says nothing unless it is asked for" {
        val cxt = TestInstances.envAuth("schemaNoExplain")
        val resp = TestHttpClient(cxt.instanceConfig).sendJsonGetRequest("/schema/endpoints")
        resp[EP.meta].toJsonMapOrEmpty().containsKey(SS.accessExplained) shouldBe false
    }

    /**
     * The fence. A real node must not answer this, or the aid would hand an anonymous caller the map of the
     * privileged surface that issue #211 removed — so it is refused off a test instance however loudly it is
     * asked for. Reachable only because [ACFG.isTestInstance] can now decide the flag outright; the inference
     * alone always says "test" here, since a unit test runs in `unit` and in memory.
     */
    "explainAccess is withheld on an instance that is not a test instance" {
        // Not a test instance (the fence under test), but env-authed so the caller still sees the whole
        // catalog -- otherwise the publicApi restriction, not the explainAccess fence, would hide /health.
        val cxt = TestInstances.envAuthNotTestInstance("schemaExplainProd")
        // Guard the premise: if this were still a test instance the assertion below would pass for the wrong
        // reason, and a fence test that cannot fail is worse than none.
        cxt.instanceConfig.isTestInstance shouldBe false

        val resp = TestHttpClient(cxt.instanceConfig)
            .sendJsonGetRequest("/schema/endpoints", mapOf(EP.debug to SS.explainAccess.name))
        resp[EP.meta].toJsonMapOrEmpty().containsKey(SS.accessExplained) shouldBe false
        // And the request was otherwise served normally -- the tag is ignored, not rejected, so nothing
        // confirms the tag exists.
        catalogEndpoints(resp).map { it[EI.path] } shouldContain "/health"
    }

    $$"/demo/schema/sample drops an off-contract $note yet honors a _debug=explainInput echo in the same call" {
        val client = client("schemaOffContract")

        val resp = client.sendJsonPostRequest(
            "/demo/schema/sample",
            mapOf(
                $$"$note" to "mimics standard query semantics", // off-contract annotation, dropped on coercing
                EP.debug to SS.explainInput.name, // "_debug" -> echo the evaluated params under _meta
                SS.filter to mapOf(SS.minCount to 1),
            ),
        )
        // The handler throws if any `$` key leaks into its input, so a normal item list proves $note was dropped.
        items(resp).size shouldBe 15
        // The _meta echo only appears because _debug rode onto the context; the echoed params show $note is gone.
        val evaluated = resp[EP.meta]!!.toJsonMap()[SS.paramsEvaluated]!!.toJsonMap()
        evaluated.keys shouldContainAll listOf(SS.filter)
    }

    /**
     * The catalog carries the publication and search axes (issue #433), so a client can slice it. Cedar
     * reached close to a thousand endpoints, at which point a catalog stops being a list anybody reads.
     */
    "the catalog reports publication and tags, and can be sliced by either" {
        val client = client("schemaTags")

        val all = catalogEndpoints(client.sendJsonGetRequest("/schema/endpoints", mapOf(EP.limit to 500)))
        val sample = all.first { it[EI.path] == "/demo/schema/sample" }
        sample[EI.tags].toJsonListOfStrings() shouldContainAll listOf(SS.demoTag, SS.schemaTag)
        sample[EI.publicApi] shouldBe false

        // Several tags is an OR (issue #489): asking for the demo tag and the internal tag returns endpoints
        // carrying either -- the demo sample and /health both come back. A query param carries the array as a
        // comma-joined string, which the array field coerces back into a list.
        val orTagged = catalogEndpoints(
            client.sendJsonGetRequest("/schema/endpoints", mapOf(EI.tags to "${SS.demoTag},${ETAG.internal}", EP.limit to 500)),
        ).map { it[EI.path] }
        orTagged shouldContainAll listOf("/demo/schema/sample", "/health")
        // A single tag still narrows to just its endpoints -- an endpoint with neither drops out.
        val demoOnly = catalogEndpoints(
            client.sendJsonGetRequest("/schema/endpoints", mapOf(EI.tags to SS.demoTag)),
        ).map { it[EI.path] }
        demoOnly shouldContain "/demo/schema/sample"
        demoOnly shouldNotContain "/health"

        val unknown = catalogEndpoints(client.sendJsonGetRequest("/schema/endpoints", mapOf(EI.tags to "noSuchTag")))
        unknown.shouldBeEmpty()

        // Publication is reported and filterable. Endpoints are published now (issue #489), so the published set
        // is non-empty and its complement is everything else. This caller is anonymous (env auth is a channel,
        // not a login), so the published endpoint it can see is auth self-info; the login-gated form endpoints
        // are asserted by the restriction test below, which uses a logged-in caller.
        val published = catalogEndpoints(
            client.sendJsonGetRequest("/schema/endpoints", mapOf(EI.publicApi to true, EP.limit to 500)),
        ).map { it[EI.path] }
        published shouldContain "/auth/self/info"
        published shouldNotContain "/health"
        val unpublished = catalogEndpoints(
            client.sendJsonGetRequest("/schema/endpoints", mapOf(EI.publicApi to false, EP.limit to 500)),
        )
        unpublished.size shouldBe all.size - published.size

        // Env-authed here, so filtering is available -- the frontend draws its controls.
        results(client.sendJsonGetRequest("/schema/endpoints"))[EI.filtersAvailable] shouldBe true
    }

    // The env-auth gate (issue #489): a caller without env auth -- an ordinary production user -- is served only
    // the published endpoints, cannot lift that with the publicApi filter, and is told filtering is unavailable
    // so the frontend drops its controls. A unit instance does not auto-assume env auth, so its caller is that
    // ordinary user. (What a client's *administrator* is shown beyond the published set -- the endpoints generated
    // for that client, issue #1128 -- is `SchemaGeneratedEndpointsTest`'s.)
    "without env auth the catalog serves an ordinary user only the published endpoints, and says filters are unavailable" {
        // A logged-in ordinary user, not env-authed (a unit instance auto-assumes nothing) -- the production
        // caller the restriction is for. Logged in, so the login-gated gedra surface is theirs to see; the
        // restriction is what keeps them to its *published* part.
        val cxt = TestInstances.default("schemaNoEnv")
        val client = TestUser.create(cxt, "no-env@example.com").client

        val resp = client.sendJsonGetRequest("/schema/endpoints", mapOf(EP.limit to 500))
        val paths = catalogEndpoints(resp).map { it[EI.path] }
        // Only published endpoints, and the internal ones are gone; the published form surface remains.
        catalogEndpoints(resp).all { it[EI.publicApi] == true } shouldBe true
        paths shouldNotContain "/health"
        paths.any { it.toString().startsWith("/gedra/") } shouldBe true
        results(resp)[EI.filtersAvailable] shouldBe false

        // Asking for the whole set cannot lift the restriction -- publicApi:false is ignored for such a caller.
        catalogEndpoints(
            client.sendJsonGetRequest("/schema/endpoints", mapOf(EI.publicApi to false, EP.limit to 500)),
        ).all { it[EI.publicApi] == true } shouldBe true

        // The single-lookup is restricted the same way: a non-published endpoint is not found for this caller.
        catalogEndpoints(
            client.sendJsonGetRequest("/schema/endpoint", mapOf(EI.method to "GET", EI.path to "/health")),
        ).shouldBeEmpty()
    }
}

/**
 * `GET /schema/endpoint?resolveClient=true`: resolving a **bare** path to the caller's own client-scoped copy
 * before the lookup (issue #552). This is what lets a form page fetch just its one endpoint's closure -- it
 * holds only the bare path (`GEP.formDocCreate`) and cannot form the concrete `/gedra/<client>/...` one
 * itself, because only the catalog knows whether the caller's client varies.
 *
 * Booted with [VariantFixtureComponent] (shared with [ClientSchemaVariantTest]) so `hub` varies its gedra
 * schema and therefore gets per-client copies of the gedra-section endpoints; `env auth` is assumed so the
 * catalog is unrestricted and this test turns on `resolveClient` alone, not the #489 publicApi gate.
 */
private fun schemaEndpointResolveClientTests() = stringSpec {
    // Its own instance (issue #1075): VariantFixtureComponent with env auth, a setup no shared entry has.
    // One instance for the file (issue #1075): every test here only looks endpoints up, each as its own caller.
    fun boot(name: String): KdrCxt = Startup.mkTestBootCxt(
        name, "resolveClientShared",
        mapOf(VariantFixtureComponent.loadFlag.name to "true", ACFG.assumeEnvAuth to true),
        additionalComponents = listOf(VariantFixtureComponent()),
    )

    fun lookupArgs(path: String, resolveClient: Boolean, method: String, client: String?): Map<String, Any?> =
        buildMap {
            put(EI.method, method)
            put(EI.path, path)
            if (resolveClient) put(EI.resolveClient, true)
            client?.let { put(EI.client, it) }
        }

    fun lookup(
        user: TestUser,
        path: String,
        resolveClient: Boolean,
        method: String = "POST",
        client: String? = null,
    ): Map<String, Any?> = user.getData("/schema/endpoint", lookupArgs(path, resolveClient, method, client))

    fun paths(results: Map<String, Any?>): List<String?> =
        results[EI.endpoints].toJsonListOfMaps().map { it[EI.path].toOptStr() }

    fun defCount(results: Map<String, Any?>): Int = results[SCH.dDefs].toJsonMapOrEmpty().size

    "resolveClient resolves a bare path to the caller's own client-scoped copy" {
        val hub = TestUser.create(boot("hubForms"), "hub-forms@resolve.test", userClient = CL.hub)

        // Without the flag the bare path is looked up verbatim. It still exists in the store, so the caller gets
        // the *bare* endpoint -- whose handler would bind the client from who is calling, not from the path.
        paths(lookup(hub, GEP.formDocCreate, resolveClient = false)) shouldContainExactly listOf(GEP.formDocCreate)

        // With it, the bare path resolves to this client's own copy: the concrete path the form page must POST
        // to, and exactly the one a whole-catalog scan would have found by suffix.
        val resolved = lookup(hub, GEP.formDocCreate, resolveClient = true)
        paths(resolved) shouldContainExactly listOf(clientPath(GEP.formDocCreate, CL.hub))
    }

    "the resolved lookup returns a closure, not the whole catalog's defs" {
        val hub = TestUser.create(boot("hubClosure"), "hub-closure@resolve.test", userClient = CL.hub)

        val resolved = lookup(hub, GEP.formDocCreate, resolveClient = true)
        val catalog = hub.getData("/schema/endpoints", mapOf(EP.limit to 1000))
        // The endpoint is actually present -- asserted so the size comparison below cannot pass on an empty
        // result (0 defs is trivially fewer than the catalog's), which would hide a broken resolution.
        paths(resolved) shouldContainExactly listOf(clientPath(GEP.formDocCreate, CL.hub))
        // Its $defs is only that input's transitive closure -- strictly fewer than the whole surface's bag,
        // which is the isolation this issue is about.
        defCount(resolved) shouldBeLessThan defCount(catalog)
    }

    "resolveClient leaves a bare-surface caller's path untouched" {
        // A default-client user does not vary, so their surface has no client segment. resolveClient then has
        // nothing to insert (`surface.client` is null) and the bare path is looked up as-is.
        val plain = TestUser.create(boot("plainForms"), "plain-forms@resolve.test")

        paths(lookup(plain, GEP.formDocCreate, resolveClient = true)) shouldContainExactly listOf(GEP.formDocCreate)
    }

    "resolveClient falls back to the shared endpoint when the caller's client has no copy of it" {
        // A varying client still gets client copies only of the endpoints `buildClientEndpoints` copies (the
        // gedra section, plus anything `clientShaped`). `/schema/endpoints` is neither, so `hub` has no copy of
        // it -- and resolveClient must fall back to the shared path rather than answer empty, since a caller
        // cannot tell which endpoints were copied. (Without the fallback this resolves to the non-existent
        // `/schema/hub/endpoints` and returns nothing.)
        val hub = TestUser.create(boot("hubFallback"), "hub-fallback@resolve.test", userClient = CL.hub)

        val schemaListPath = "/schema/endpoints"
        paths(lookup(hub, schemaListPath, resolveClient = true, method = "GET")) shouldContainExactly
            listOf(schemaListPath)
    }

    "an allClients caller resolves a bare path on another client's surface" {
        // The lookup takes the listing's `client` (issue #714): an allClients admin editing a hub form asks for
        // hub's copy of the patch endpoint -- in hub's `$defs` -- rather than forming `/gedra/hub/patch` itself.
        // The admin is placed in `public`, which does not vary, so without `client` the bare path answers. (Named:
        // an allClients admin's own default is the hub, issue #799, which here has copies of its own.)
        val cxt = boot("adminOther")
        val admin = TestUser.createFullAdmin(cxt, "admin-other@resolve.test", userClient = CL.public)

        paths(lookup(admin, GEP.patch, resolveClient = true)) shouldContainExactly listOf(GEP.patch)
        paths(lookup(admin, GEP.patch, resolveClient = true, client = CL.hub)) shouldContainExactly
            listOf(clientPath(GEP.patch, CL.hub))
    }

    "naming a client that varies nothing falls back to the shared endpoint" {
        // The regression the #714 review found: a client with no schema overlay and no cfacts -- the default
        // client here -- has no client copies at all, so a caller who formed `/gedra/<client>/patch` by hand
        // found nothing. Resolved on that client's surface, the bare path answers -- the shared endpoint, bound
        // to the client at request time. (The narrow fixture client is *not* such a client: declaring which
        // traits it supports gives it a variant, and so copies of its own.)
        val cxt = boot("adminDefault")
        val admin = TestUser.createFullAdmin(cxt, "admin-default@resolve.test")

        paths(lookup(admin, GEP.patch, resolveClient = true, client = CL.public)) shouldContainExactly listOf(GEP.patch)
        paths(lookup(admin, GEP.patch, resolveClient = true, client = VariantFixtureComponent.narrowClient))
            .shouldContainExactly(listOf(clientPath(GEP.patch, VariantFixtureComponent.narrowClient)))
    }

    "an ordinary caller may look up only their own client's surface" {
        val cxt = boot("hubOwnOnly")
        val hub = TestUser.create(cxt, "hub-own-only@resolve.test", userClient = CL.hub)

        // Their own client by name is what they would have been shown anyway.
        paths(lookup(hub, GEP.patch, resolveClient = true, client = CL.hub)) shouldContainExactly
            listOf(clientPath(GEP.patch, CL.hub))
        // Another client's surface takes the allClients capability, as on the listing.
        hub.expectError(
            EXC.badInput,
            "/schema/endpoint",
            args = lookupArgs(GEP.patch, resolveClient = true, method = "POST", client = VariantFixtureComponent.narrowClient),
        )
    }
}

/**
 * Two endpoints on one path, differing only by verb (issue #335).
 *
 * `KdrEndpoint.collationKey` is `path:method` and the endpoint store is keyed by it, so dispatch always found
 * the right endpoint. What did not was the dispatcher's memo of **compiled input and output types**, which was
 * keyed by path alone -- so whichever verb was called first compiled its schemas and the other was then
 * validated against them. It went unnoticed because nothing shared a URL until this issue.
 *
 * These assertions fail loudly if that memo is ever keyed by path again: the two verbs take disjoint input
 * fields and answer with different output types, and endpoint input is closed to undeclared properties.
 */
private fun samePathVerbTests() = stringSpec {

    fun client() = TestHttpClient(TestInstances.default("samePathVerb").instanceConfig).alwaysWithTestAccess()

    "each verb on a shared path validates against its own input and output schema" {
        val c = client()

        // Deliberately DELETE first: the bug was order-dependent -- whichever verb compiled first won -- so
        // exercising the second-registered one ahead of the first is what makes this a real check.
        val deleted = c.sendJsonDeleteRequest(TVB.path, mapOf(TVB.deleteOnly to "d1"))[EP.results].toJsonMapOrEmpty()
        deleted[TVB.verb] shouldBe HttpMethod.DELETE.name
        deleted[TVB.deleteOnly] shouldBe "d1"

        val got = c.sendJsonGetRequest(TVB.path, mapOf(TVB.getOnly to "g1"))[EP.results].toJsonMapOrEmpty()
        got[TVB.verb] shouldBe HttpMethod.GET.name
        got[TVB.getOnly] shouldBe "g1"
    }

    "neither verb accepts the other's field, which is what proves the schemas did not collide" {
        val c = client()

        // Each field is required by its own verb and undeclared on the other, so a collision shows up twice
        // over: the wrong field is refused, and the right one goes missing.
        c.sendGetRequest(TVB.path, mapOf(TVB.deleteOnly to "d1")).rptStatusCode shouldBe EXC.badInput
        c.sendDeleteRequest(TVB.path, mapOf(TVB.getOnly to "g1")).rptStatusCode shouldBe EXC.badInput
    }
}

/**
 * What of the client-scoped administration is published (issue #1483): what an AI configuring one client and its users
 * needs -- the configuration's reads, write, and patch; the users' listing and the calls that set them up; and a
 * client's file refs, whose bytes no bundle carries. A caller without env auth is shown these in the catalog, as an
 * outside developer is, and not the rest: publishing, which a client's own people do; the shortcuts the frontend's
 * pages use, which a write or a patch already covers; and the web assets, which a sandbox may not change and which
 * reach everyone in the client at once, as a publish does.
 *
 * The published set is pinned whole, so that adding to it or taking from it is a decision this test is changed for.
 */
private fun publishedClientAdminCatalogTests() = stringSpec {
    val cxt = TestInstances.default("publishedClientAdmin")

    val published = setOf(
        CFEP.bundles, CFEP.bundle, CFEP.slotEntries, CFEP.bundleWrite, CFEP.bundlePatch,
        UADEP.users, UADEP.userCreate, UADEP.userSetRoles, UADEP.userSetLabels, UADEP.userSetEnabled, UADEP.userSetName,
        UADEP.userSetOrg,
        UADEP.clientFiles, UADEP.clientFilesTakes, UADEP.clientFilesUpload, UADEP.clientFilesDownloadMeta,
    )

    "a client's administrator without env auth is shown exactly the published client administration" {
        // The premise: this node grants no env auth of its own accord, so the caller is the production one.
        cxt.instanceConfig.get(ACFG.assumeEnvAuth) shouldNotBe true
        val admin = TestUser.create(cxt, "pubca-admin@example.com", level = ROLE.admin, userClient = CL.public)

        val catalog = admin.catalog()
        catalog[EI.filtersAvailable] shouldBe false
        val shown = TestUser.endpointsIn(catalog).filter { it[EI.path].toString().startsWith("/${SECT.clientAdmin}/") }
        shown.map { it[EI.path] }.toSet() shouldBe published
        shown.forEach { it[EI.publicApi] shouldBe true }
    }

    "publishing, and the shortcuts a write or a patch covers, stay unpublished" {
        val admin = TestUser.create(cxt, "pubca-admin@example.com", level = ROLE.admin, userClient = CL.public)
        val paths = TestUser.pathsIn(admin.catalog())
        paths shouldContainAll listOf(CFEP.bundleWrite, CFEP.bundlePatch)
        paths shouldNotContainAnyOf listOf(
            CFEP.bundlePublish, CFEP.bundleImpact, CFEP.publishedOnly, CFEP.reload, CFEP.editor,
            UADEP.userSearch, UADEP.userDelete, UADEP.clientFilesRemove, UADEP.clientFilesDescribe,
            UADEP.clientWeb, UADEP.clientWebUpload,
        )
    }
}
