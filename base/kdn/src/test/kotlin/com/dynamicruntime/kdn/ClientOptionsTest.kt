package com.dynamicruntime.kdn

import com.dynamicruntime.common.context.ACFG
import com.dynamicruntime.common.context.CL
import com.dynamicruntime.common.context.UPF
import com.dynamicruntime.common.endpoint.EI
import com.dynamicruntime.common.exception.EXC
import com.dynamicruntime.common.exception.KdrException
import com.dynamicruntime.common.gedra.CLD
import com.dynamicruntime.common.http.request.TestHttpClient
import com.dynamicruntime.common.schema.SCH
import com.dynamicruntime.common.schema.SchOption
import com.dynamicruntime.common.schema.SchOptionsProvider
import com.dynamicruntime.common.startup.SchemaCollector
import com.dynamicruntime.common.startup.SchemaService
import com.dynamicruntime.common.startup.SS
import com.dynamicruntime.common.user.ADEP
import com.dynamicruntime.common.user.ADF
import com.dynamicruntime.common.user.TestUser
import com.dynamicruntime.common.util.toJsonListOfMaps
import com.dynamicruntime.common.util.toJsonMapOrEmpty
import com.dynamicruntime.common.util.toOptStr
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain

/**
 * The client choice list, assembled per caller when the catalog is served (issue #413).
 *
 * Every assertion is a **pair** -- the same field of the same endpoint, read by two callers against one
 * running instance -- for the reason `ClientVariantTest` states: a list on its own proves nothing about what
 * produced it. Here it does a second job as well. The callers run in order against a single instance, so an
 * administrator's answer reaching the ordinary user afterward would be a resolution that wrote into the
 * compiled store rather than a copy of it, which is the defect this design exists to make impossible and the
 * one that would otherwise look exactly like a working page.
 */
class ClientOptionsTest : StringSpec({

    // Env-authed (issue #489): this tests the client-filter *options* on the catalog endpoint, so the caller
    // must see /schema/endpoints itself -- which the publicApi restriction would hide from a non-env-authed one.
    val cxt = Startup.mkTestBootCxt("clientOptions", "clientOptionsTest", mapOf(ACFG.assumeEnvAuth to true))

    /** The values offered for [field] on the input schema of [path], as this caller is shown them. */
    fun offered(user: TestUser, path: String, field: String): List<String> =
        user.getData("/schema/endpoints", mapOf(SS.pathRegex to path))[EI.endpoints]
            .toJsonListOfMaps().single { it[EI.path].toOptStr() == path }[EI.inputSchema].toJsonMapOrEmpty()[SCH.properties]
            .toJsonMapOrEmpty()[field].toJsonMapOrEmpty()[SCH.options]
            .toJsonListOfMaps().mapNotNull { it[SCH.value].toOptStr() }

    "an allClients admin is offered every client this node carries" {
        val admin = TestUser.createFullAdmin(cxt, "options-admin@example.com")
        val clients = offered(admin, "/schema/endpoints", EI.client)
        clients shouldContain CL.hub
        clients shouldContain CL.public
    }

    "an ordinary caller is offered their own client and nothing else" {
        val user = TestUser.create(cxt, "options-user@example.com", userClient = CL.public)
        // The other half of the pair, and the order matters: this runs *after* the administrator's call
        // against the same instance, so the shared `EndpointQuery` def has already been resolved once. Seeing
        // `hub` here would mean the first answer had been written into the store the second one reads.
        offered(user, "/schema/endpoints", EI.client) shouldBe listOf(CL.public)
    }

    "an anonymous caller is offered the public client" {
        val anon = TestUser(TestHttpClient(cxt.instanceConfig), cxt, emptyMap())
        // `/schema` is an anonymous section, so this is a real caller rather than a hypothetical one.
        //
        // `public` because the dispatcher now binds the anonymous profile, which is what `/auth/self/info`
        // had always reported for such a caller while every other reader on the same request saw the system
        // profile's `hub`. Worth an assertion of its own: this is the one list served to somebody who never
        // authenticated, so it is where that disagreement showed up first.
        offered(anon, "/schema/endpoints", EI.client) shouldBe listOf(CL.public)
    }

    "and the endpoint that reports who they are agrees with it" {
        val anon = TestUser(TestHttpClient(cxt.instanceConfig), cxt, emptyMap())
        // The pair that makes the previous assertion mean something: one caller, two endpoints, one answer.
        // Read separately from the list because they reach the client by different routes -- the profile dump
        // through `currentUserInfo`, the list through the provider's read of `cxt.userProfile` -- and it was
        // exactly those two routes that disagreed.
        anon.getData("/auth/self/info")[UPF.client] shouldBe CL.public
    }

    "the same list reaches an inline field on another endpoint" {
        val admin = TestUser.createFullAdmin(cxt, "options-create@example.com")
        // `EI.client` above is a property of a named type; `ADF.client` is an inline `inputFields` field, and
        // the two travel to the catalog by different code paths (`buildEndpointInputSchema` flattens one and
        // copies the other). One `clientAttribute()` has to serve both or the convention is worth nothing.
        offered(admin, ADEP.userCreate, ADF.client) shouldContain CL.hub
    }

    "a client reads by name and id" {
        val admin = TestUser.createFullAdmin(cxt, "options-label@example.com")
        val labels = admin.getData("/schema/endpoints", mapOf(SS.pathRegex to "/schema/endpoints"))[EI.endpoints]
            .toJsonListOfMaps().single()[EI.inputSchema].toJsonMapOrEmpty()[SCH.properties]
            .toJsonMapOrEmpty()[EI.client].toJsonMapOrEmpty()[SCH.options]
            .toJsonListOfMaps().mapNotNull { it[SCH.label].toOptStr() }
        // Both halves: the id is what a log will show, the name is what a person calls it.
        labels.any { it.contains(CL.hub) && it != CL.hub } shouldBe true
    }

    "the id never travels" {
        val admin = TestUser.createFullAdmin(cxt, "options-strip@example.com")
        val field = admin.getData("/schema/endpoints", mapOf(SS.pathRegex to "/schema/endpoints"))[EI.endpoints]
            .toJsonListOfMaps().single()[EI.inputSchema].toJsonMapOrEmpty()[SCH.properties]
            .toJsonMapOrEmpty()[EI.client].toJsonMapOrEmpty()
        // A reader gets a choice list and no second way to have one -- which is what lets the form engine,
        // the outline and a future export stay ignorant that any of this happened.
        field.containsKey(SCH.optionsSource) shouldBe false
        field.containsKey(SCH.options) shouldBe true
    }

    "the offered list does not decide what is accepted" {
        val user = TestUser.create(cxt, "options-fence@example.com", userClient = CL.public)
        // The list is what a caller is *shown*; the refusal is still the handler's, with a message that says
        // what the capability is. A sourced list never reaches validation, so this is the only fence there
        // is -- and it has to keep working for the list to be safe to vary.
        user.expectError(EXC.badInput, "/schema/endpoints", args = mapOf(EI.client to CL.hub))
    }

    "the boot check reaches this node's own compiled document" {
        val service = SchemaService.get(cxt)
        // Checked against an empty registry: the node booted, so every id it declares resolves -- which means
        // a passing check proves nothing on its own. Taking the registry away is what shows the scan is
        // reading the real document rather than finding nothing to look at.
        val message = shouldThrow<KdrException> { service.checkOptionsSources(emptyMap()) }.fullMessage()
        message shouldContain CLD.clientOptions
        // Both surfaces a client attribute reaches: a named type in `$defs`, and an endpoint's inline field.
        message shouldContain "EndpointQuery"
        message shouldContain ADEP.userCreate
    }

    "two providers cannot take the same id" {
        val collector = SchemaCollector()
        val provider: SchOptionsProvider = { _, _ -> listOf(SchOption("a", "A")) }
        collector.addOptionsProvider(CLD.clientOptions, provider)
        // Last-write-wins would mean one component silently answering for another component's attribute --
        // visible only as a wrong list, on a page nobody would connect to whoever took the id.
        shouldThrow<KdrException> { collector.addOptionsProvider(CLD.clientOptions, provider) }
            .fullMessage() shouldContain CLD.clientOptions
    }
})
