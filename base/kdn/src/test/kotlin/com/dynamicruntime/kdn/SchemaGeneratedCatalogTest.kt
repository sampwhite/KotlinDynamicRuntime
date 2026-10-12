package com.dynamicruntime.kdn

import com.dynamicruntime.common.cfact.CFD
import com.dynamicruntime.common.context.ACFG
import com.dynamicruntime.common.context.CL
import com.dynamicruntime.common.endpoint.EI
import com.dynamicruntime.common.endpoint.EP
import com.dynamicruntime.common.endpoint.clientPath
import com.dynamicruntime.common.exception.EXC
import com.dynamicruntime.common.gedra.sandboxOf
import com.dynamicruntime.common.http.request.ROLE
import com.dynamicruntime.common.schema.SCH
import com.dynamicruntime.common.user.AEP
import com.dynamicruntime.common.user.TestUser
import com.dynamicruntime.common.user.UADEP
import com.dynamicruntime.common.util.toJsonMapOrEmpty
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldNotContain
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe

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
class SchemaGeneratedCatalogTest : StringSpec({
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
})
