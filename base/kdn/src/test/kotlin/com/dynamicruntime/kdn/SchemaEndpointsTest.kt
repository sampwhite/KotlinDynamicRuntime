package com.dynamicruntime.kdn

import com.dynamicruntime.common.context.ACFG
import com.dynamicruntime.common.endpoint.EI
import com.dynamicruntime.common.endpoint.EP
import com.dynamicruntime.common.endpoint.ETAG
import com.dynamicruntime.common.http.request.ROLE
import com.dynamicruntime.common.http.request.TestHttpClient
import com.dynamicruntime.common.schema.SCH
import com.dynamicruntime.common.user.ADEP
import com.dynamicruntime.common.user.UADEP
import com.dynamicruntime.common.user.ADF
import com.dynamicruntime.common.user.TestUser
import com.dynamicruntime.common.startup.SS
import com.dynamicruntime.common.util.toJsonMap
import com.dynamicruntime.common.util.toJsonMapOrEmpty
import com.dynamicruntime.common.util.toJsonListOfMaps
import com.dynamicruntime.common.util.toJsonListOfStrings
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldContainAll
import io.kotest.matchers.collections.shouldNotContain
import io.kotest.matchers.shouldBe

/**
 * Exercises the two SchemaService endpoints through the in-process client. Because these run under
 * [Startup.mkTestBootCxt] (which sets `validateResponseSchema`), every response is also validated against
 * the endpoint's output schema, so a non-conforming catalog or sample item would fail the call.
 */
class SchemaEndpointsTest : StringSpec({

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
            Startup.mkTestBootCxt(cxtName, "schemaEndpointsTest", mapOf(ACFG.assumeEnvAuth to true)).instanceConfig,
        )

    $$"/schema/endpoints renders every endpoint and a shared $defs" {
        val client = client("schemaList")

        val resp = client.sendJsonGetRequest("/schema/endpoints")
        val eps = catalogEndpoints(resp)
        eps.map { it[EI.path] } shouldContainAll listOf("/health", "/schema/endpoints", "/demo/schema/sample")

        val health = eps.first { it[EI.path] == "/health" }
        health.keys shouldContainAll
            listOf(EI.path, EI.method, EI.kind, EI.namespace, EI.description, EI.inputSchema, EI.outputSchema)
        health[EI.namespace] shouldBe "node"
        health[EI.method] shouldBe "GET"
        // /health takes no parameters: its rendered input schema is a closed, empty object.
        health[EI.inputSchema]!!.toJsonMap()[SCH.additionalProperties] shouldBe false

        // The shared $defs closes over the types the renderings reference (by $ref), returned once each:
        // /health's output refs node.Health; /demo/schema/sample's input flattens SampleQuery, whose `filter` refs
        // SampleFilter, and its output refs SampleItem, which refs SampleDetails.
        val defs = results(resp)[SCH.dDefs]!!.toJsonMap()
        defs.keys shouldContainAll listOf("node.Health", "schema.SampleFilter", "schema.SampleItem", "schema.SampleDetails")
        // SampleQuery itself is dissolved into flat input fields, so it is NOT a returned def.
        defs.keys shouldNotContain "schema.SampleQuery"
    }

    "/schema/endpoints filters by namespace, method, and path regex" {
        val client = client("schemaFilters")

        fun paths(params: Map<String, Any?>): List<Any?> =
            catalogEndpoints(client.sendJsonGetRequest("/schema/endpoints", params)).map { it[EI.path] }

        paths(mapOf(EI.namespace to "node")) shouldBe listOf("/health")
        // The method filter returns only POST endpoints (which include /demo/schema/sample).
        val posts = catalogEndpoints(client.sendJsonGetRequest("/schema/endpoints", mapOf(EI.method to "POST")))
        posts.map { it[EI.path] } shouldContain "/demo/schema/sample"
        posts.map { it[EI.method] }.toSet() shouldBe setOf("POST")
        // `schema` is now only the catalog's own endpoints: the two exercise surfaces that used to sit beside
        // them moved to the purpose-named roots (issue #270) -- the fixture to `/fixture/`, the demo to
        // `/demo/`. The namespace they are *declared* in is still `schema`; the section is not.
        paths(mapOf(SS.pathRegex to "^/schema/")) shouldBe listOf("/schema/endpoint", "/schema/endpoints")
        paths(mapOf(SS.pathRegex to "^/fixture/schema/")) shouldBe listOf("/fixture/schema/complex")
    }

    "/schema/endpoints caps the number of endpoints by limit" {
        val client = client("schemaLimit")
        catalogEndpoints(client.sendJsonGetRequest("/schema/endpoints", mapOf(EP.limit to 2))).size shouldBe 2
    }

    "/schema/endpoint looks up a single endpoint by exact method and path, in the catalog shape" {
        val client = client("schemaOne")
        val resp = client.sendJsonGetRequest("/schema/endpoint", mapOf(EI.method to "GET", EI.path to "/health"))
        // Same shape as /schema/endpoints: an `endpoints` list (here of one) plus a shared `$defs`.
        val eps = catalogEndpoints(resp)
        eps.map { it[EI.path] } shouldBe listOf("/health")
        eps.single()[EI.method] shouldBe "GET"
        (results(resp)[SCH.dDefs]!!.toJsonMap()).keys shouldContain "node.Health"
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
        val cxt = Startup.mkTestBootCxt("schemaVisibility", "schemaVisibilityTest", mapOf(ACFG.assumeEnvAuth to true))
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
        val chief = TestUser.createFullAdmin(cxt, "chief@other.com")
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
        val cxt = Startup.mkTestBootCxt("schemaExplain", "schemaExplainTest", mapOf(ACFG.assumeEnvAuth to true))
        val plain = TestUser.create(cxt, "explain@other.com")

        val resp = plain.client.sendJsonGetRequest("/schema/endpoints", mapOf(EP.debug to SS.explainAccess))
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

    "explainAccess says nothing unless it is asked for" {
        val cxt = Startup.mkTestBootCxt("schemaNoExplain", "schemaExplainTest", mapOf(ACFG.assumeEnvAuth to true))
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
        val cxt = Startup.mkTestBootCxt(
            "schemaExplainProd", "schemaExplainProdTest",
            // Not a test instance (the fence under test), but env-authed so the caller still sees the whole
            // catalog -- otherwise the publicApi restriction, not the explainAccess fence, would hide /health.
            mapOf(ACFG.isTestInstance to false, ACFG.assumeEnvAuth to true),
        )
        // Guard the premise: if this were still a test instance the assertion below would pass for the wrong
        // reason, and a fence test that cannot fail is worse than none.
        cxt.instanceConfig.isTestInstance shouldBe false

        val resp = TestHttpClient(cxt.instanceConfig)
            .sendJsonGetRequest("/schema/endpoints", mapOf(EP.debug to SS.explainAccess))
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
                EP.debug to SS.explainInput, // "_debug" -> echo the evaluated params under _meta
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
    // ordinary user.
    "without env auth the catalog serves only the published endpoints, and says filters are unavailable" {
        // A logged-in ordinary user, not env-authed (a unit instance auto-assumes nothing) -- the production
        // caller the restriction is for. Logged in, so the login-gated gedra surface is theirs to see; the
        // restriction is what keeps them to its *published* part.
        val cxt = Startup.mkTestBootCxt("schemaNoEnv", "schemaNoEnvTest")
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
})