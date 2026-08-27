package com.dynamicruntime.common.startup

import com.dynamicruntime.common.context.KdrCxt
import com.dynamicruntime.common.endpoint.KdrEndpoint
import com.dynamicruntime.common.endpoint.clientPath
import com.dynamicruntime.common.schema.LogSchema

/**
 * Gives each client its own copy of the endpoints that are client-shaped (issue #387).
 *
 * ### Copied, not rebuilt
 *
 * The shared endpoints are authored in one `schemaModule` that declares **types and endpoints together**, so
 * re-running that builder per client would re-declare `gedra.FormDoc` and its siblings once per client and
 * collide in the shared `$defs`. Copying avoids that, and is the stronger option on behavior besides: a copy
 * keeps **the same `handler` object**, so the two surfaces cannot come to behave differently. Two
 * separately built lambdas are identical only until something in the builder becomes conditional.
 *
 * ### Why a copy advertises the client's schema
 *
 * An endpoint carries **names**, not shapes: `inputTypeRef` is a type name and `outputSchema` is a built map
 * with `$ref`s intact. So the same copy means different things in different stores, and resolving it against
 * the client's variant is all it takes for the advertised input and output to be that client's. Nothing is
 * rewritten -- the same property that makes `overlayDefs` work.
 *
 * ### What the wrapper adds
 *
 * The path names the client, so the handler runs bound to it. That is what turns #356's "the variant follows
 * the data's client" from a resolution into a **comparison**: everything downstream reads `cxt.client`, and a
 * target whose `GedraId` disagrees with the path is refused before the shared handler sees it.
 */
fun buildClientEndpoints(
    cxt: KdrCxt,
    shared: Collection<KdrEndpoint>,
    clients: Collection<String>,
): List<KdrEndpoint> {
    if (clients.isEmpty()) {
        return emptyList()
    }
    val copyable = shared.filter { it.path.startsWith("/${CLIENT_SHAPED_SECTION}/") || it.clientShaped }
    if (copyable.isEmpty()) {
        return emptyList()
    }
    val out = mutableListOf<KdrEndpoint>()
    for (client in clients) {
        for (endpoint in copyable) {
            out.add(copyFor(endpoint, client))
        }
    }
    LogSchema.debug(cxt) {
        "Generated ${out.size} client endpoint(s): ${copyable.size} per client over ${clients.size} client(s)."
    }
    return out
}

/**
 * The section every one of whose endpoints gets a per-client copy.
 *
 * Named rather than inferred: an endpoint is client-shaped when what it reads or writes belongs to a client,
 * which is a fact about the endpoint rather than about its path. `gedra` is the only *section* where that is
 * true of everything in it -- `auth`, `admin` and `operator` are otherwise about the deployment, and copying
 * them wholesale would offer the same answer under several names.
 *
 * Elsewhere an endpoint says so for itself, with [KdrEndpoint.clientShaped] (issue #455). The two are the same
 * property declared at two granularities, which is what this note always implied: `/clientAdmin/cfacts` answers
 * with a registry a client's own config may have added to, so it is client-shaped while the rest of its
 * section is not.
 */
private const val CLIENT_SHAPED_SECTION = "gedra"

/** [endpoint] under [client]'s path, bound to that client, with the shared handler untouched. */
private fun copyFor(endpoint: KdrEndpoint, client: String): KdrEndpoint {
    val shared = endpoint.handler
    return KdrEndpoint(
        path = clientPath(endpoint.path, client),
        method = endpoint.method,
        kind = endpoint.kind,
        namespace = endpoint.namespace,
        description = "${endpoint.description} (as '$client' sees it)",
        inputFields = endpoint.inputFields,
        inputTypeRef = endpoint.inputTypeRef,
        includeLimit = endpoint.includeLimit,
        outputSchema = endpoint.outputSchema,
        forTestingOnly = endpoint.forTestingOnly,
        clientShaped = endpoint.clientShaped,
        // The path is the statement of which client this is for, so the handler is run bound to it and reads
        // `cxt.client` exactly as it does on the shared surface -- which is why the same lambda serves both.
        handler = { c, request ->
            val bound = c.mkSubContext("client:$client", client)
            // The path already said which client this is for, so the request is confined to it: a gedra
            // belonging to another is refused rather than reaching the shared handler. Set here because this
            // is the only place that knows the client came from a path rather than from who is calling.
            bound.clientFromPath = client
            shared(bound, request)
        },
        client = client,
    )
}
