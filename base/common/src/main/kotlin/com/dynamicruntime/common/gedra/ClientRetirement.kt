package com.dynamicruntime.common.gedra

import com.dynamicruntime.common.context.KdrCxt
import com.dynamicruntime.common.exception.KdrException
import com.dynamicruntime.common.util.toJsonMapOrEmpty

/**
 * Retiring a client, and bringing one back (issue #1111): the platform's switch, [ClientDef.retired], set on the
 * client's stored definition. A retired client keeps everything it stored and is **inert**: absent in every
 * environment, so nothing scoped by it is reached, its users cannot sign in, an open session of theirs is no longer
 * honored, and its sandbox goes with it. Brought back, it is as it was -- its environments were never touched.
 *
 * A retirement takes effect **live** whatever the client's tier: the definition is published and the client
 * reloaded in the one call, here and, by the sync, on the other nodes. A client with a sandbox would otherwise save
 * it as a draft, and a retirement is not something to preview. So the definition's configuration may not carry
 * anybody's unpublished changes, which the publish would take live with it.
 *
 * Refused for a client defined in source code, which is retired there (a stored definition cannot override a source
 * one); for a sandbox, which goes with its parent; and for a customer's root client, which is retired only once
 * another root is named.
 */
object ClientRetirement {
    /** What the change did: the definition as stored now, and whether this node carries the client. */
    class Result(val client: String, val retired: Boolean, val info: Map<String, Any?>, val present: Boolean)

    /**
     * Retires [client] when [retired], or brings it back. Setting what the client already **runs** as changes nothing
     * -- judged by the definition the node runs, not the stored latest, which may be an unpublished draft.
     */
    fun set(cxt: KdrCxt, client: String, retired: Boolean): Result {
        sandboxParentOf(client)?.let { parent ->
            throw KdrException.mkInput("'$client' is a sandbox; it is retired with its parent, '$parent'.")
        }
        val clients = ClientService.get(cxt)
        if (clients.originOf(client) == GedraConfigOrigin.source) {
            throw KdrException.mkInput(
                "Client '$client' is defined in source code, so it is retired there; a stored definition cannot override it.",
            )
        }
        if (retired) {
            clients.customers.values.firstOrNull { it.def.rootClientId == client }?.let {
                throw KdrException.mkInput(
                    "Client '$client' is the root of the customer '${it.def.customerId}'; name another root before retiring it.",
                )
            }
        }
        val bound = cxt.mkSubContext("clientRetirement", client)
        val holder = ClientStoredEdit.definitionHolder(bound)
            ?: throw KdrException.mkInput("Client '$client' has no stored definition to retire or bring back.")
        val before = holder.entriesBySlot()[CCT.clientDef]?.firstOrNull().toJsonMapOrEmpty()
        if (clients.known(client)?.retired == retired) return result(cxt, client, retired, before)
        if (!holder.isPublished) {
            throw KdrException.mkInput(
                "Configuration '${holder.configId.baseId}' of client '$client' has unpublished changes, which a " +
                    "retirement would publish with it. Publish or revert that configuration first.",
            )
        }
        val written = ClientStoredEdit.patchDefinition(bound, holder) { def ->
            if (retired) def[CLD.retired] = true else def.remove(CLD.retired)
        }
        ClientStoredEdit.publishAndReload(
            cxt, bound, client, written,
            undo = { ClientStoredEdit.patchDefinition(bound, written) { def -> def.clear(); def.putAll(before) } },
            restorePublished = true,
        )
        val after = written.slotsForEmission(cxt.instanceConfig.isTestInstance)[CCT.clientDef]?.firstOrNull().toJsonMapOrEmpty()
        return result(cxt, client, retired, after)
    }

    private fun result(cxt: KdrCxt, client: String, retired: Boolean, info: Map<String, Any?>) =
        Result(client, retired, info, ClientService.get(cxt).present(client) != null)
}
