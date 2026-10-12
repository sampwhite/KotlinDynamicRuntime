package com.dynamicruntime.kdn

import com.dynamicruntime.common.context.ENV
import com.dynamicruntime.common.context.KdrCxt
import com.dynamicruntime.common.endpoint.EP
import com.dynamicruntime.common.endpoint.EndpointKind
import com.dynamicruntime.common.endpoint.HttpMethod
import com.dynamicruntime.common.endpoint.KdrEndpoint
import com.dynamicruntime.common.endpoint.SchModuleBuilder
import com.dynamicruntime.common.gedra.ClientAudience
import com.dynamicruntime.common.gedra.ClientDef
import com.dynamicruntime.common.gedra.ClientUsageType
import com.dynamicruntime.common.gedra.GedraConfigReload
import com.dynamicruntime.common.gedra.GedraConfigService
import com.dynamicruntime.common.gedra.gedraConfig
import com.dynamicruntime.common.http.request.RequestService
import com.dynamicruntime.common.http.request.SECT
import com.dynamicruntime.common.naming.clientNamespace
import com.dynamicruntime.common.schema.SCT
import com.dynamicruntime.common.schema.schemaDefs
import com.dynamicruntime.common.startup.GeneratedSurface
import com.dynamicruntime.common.startup.SchemaService

/**
 * Hand-built **generated surfaces** for the tests of `SchemaService.publishGenerated` and of what is done with what
 * it publishes (issues #1087, #1128): one endpoint per client under `clientAdmin`, answering `{ n }` in a `Thing`
 * type of the surface's own, and the test clients to bind them to. One place, so a change to how an endpoint is
 * constructed is one edit rather than one per test that needs an endpoint nobody declared.
 *
 * [root] keeps one test's addresses and type names apart from another's on a shared instance (`gentest`,
 * `gcattest`); [setupUserId] is the user the client definitions are written as.
 */
class GeneratedSurfaces(val cxt: KdrCxt, val root: String, val setupUserId: Long) {
    val schema: SchemaService = SchemaService.get(cxt)

    /** A sandbox's client id has a colon, which a type's namespace may not. */
    fun namespaceOf(client: String) = "kdr.$root.${client.replace(':', '.')}"
    fun typeOf(client: String) = "${namespaceOf(client)}.Thing"
    fun pathOf(client: String, name: String = "thing") = "/${SECT.clientAdmin}/$client/$root/$name"

    /** The type [define] gives a client that is to vary: one of its own, in its own namespace. */
    fun ownTypeOf(client: String) = "${clientNamespace(client)}.$ownType"

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

    fun asClient(client: String): KdrCxt = cxt.mkSubContext(root, client).also { it.userId = setupUserId }

    /**
     * Defines [client] in stored configuration and loads it -- a reload of that client, after which users may be made
     * in it. [alsoVaries] gives it a type of its own ([ownTypeOf]), so it has a variant and with it copies of the
     * client-shaped endpoints; [sandbox] gives it a Shadow Sandbox.
     */
    fun define(client: String, alsoVaries: Boolean = false, sandbox: Boolean = false) {
        val config = gedraConfig(cxt, "${client}cfg", clientNamespace(client), client) {
            defineClient(
                ClientDef(
                    clientId = client, name = client, usageType = ClientUsageType.dev,
                    audience = ClientAudience.internal, enabledEnvironments = setOf(ENV.unit, ENV.local), sandbox = sandbox,
                ),
            )
            if (alsoVaries) {
                type(ownType) {
                    type = SCT.kObject
                    property("label", "A label.")
                }
            }
        }
        GedraConfigService.get(cxt).writeConfig(asClient(client), config)
        GedraConfigReload.reloadClient(cxt, client)
    }

    @Suppress("ConstPropertyName")
    companion object {
        const val ownType = "GenOwn"
    }
}
