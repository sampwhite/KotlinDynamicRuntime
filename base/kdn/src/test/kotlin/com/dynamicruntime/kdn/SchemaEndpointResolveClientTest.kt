package com.dynamicruntime.kdn

import com.dynamicruntime.common.context.ACFG
import com.dynamicruntime.common.context.CL
import com.dynamicruntime.common.context.KdrCxt
import com.dynamicruntime.common.endpoint.EI
import com.dynamicruntime.common.endpoint.EP
import com.dynamicruntime.common.endpoint.clientPath
import com.dynamicruntime.common.gedra.GEP
import com.dynamicruntime.common.schema.SCH
import com.dynamicruntime.common.user.TestUser
import com.dynamicruntime.common.util.toJsonListOfMaps
import com.dynamicruntime.common.util.toJsonMapOrEmpty
import com.dynamicruntime.common.util.toOptStr
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.ints.shouldBeLessThan

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
class SchemaEndpointResolveClientTest : StringSpec({
    fun boot(name: String): KdrCxt = Startup.mkTestBootCxt(
        "resolveClient", name,
        mapOf(VariantFixtureComponent.loadFlag.name to "true", ACFG.assumeEnvAuth to true),
        additionalComponents = listOf(VariantFixtureComponent()),
    )

    fun lookup(user: TestUser, path: String, resolveClient: Boolean, method: String = "POST"): Map<String, Any?> =
        user.getData(
            "/schema/endpoint",
            buildMap {
                put(EI.method, method)
                put(EI.path, path)
                if (resolveClient) put(EI.resolveClient, true)
            },
        )

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
})
