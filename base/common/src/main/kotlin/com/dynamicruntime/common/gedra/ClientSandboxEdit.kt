package com.dynamicruntime.common.gedra

import com.dynamicruntime.common.context.KdrCxt
import com.dynamicruntime.common.exception.KdrException
import com.dynamicruntime.common.util.toJsonMapOrEmpty

/**
 * Adding or removing a client's Shadow Sandbox from the clients page (issue #932): the `sandbox` flag of the
 * client's **stored** definition, set and **published** in one call, then the client reloaded -- which loads the
 * sandbox, or withdraws it -- and announced to peers.
 *
 * Published, not saved as a draft the way the presentation editors save for a client with a sandbox (#930): the
 * published definition is what decides both whether the sandbox exists and the client's tier (a client asking for a
 * sandbox runs only published configuration), so a draft flag would change nothing. For the same reason the edit is
 * refused when the config holding the definition carries somebody's unpublished changes, which publishing would take
 * live with it.
 *
 * A client defined in source code has its flag set there: a stored definition cannot override a source one, so the
 * edit is refused, saying where to set it. Removing a sandbox keeps its users and data -- they belong to the sandbox's
 * client id, and come back with it.
 */
object ClientSandboxEdit {
    /** What the edit left: whether the client has its sandbox, and whether it now runs only published configuration. */
    class Result(val client: String, val sandbox: Boolean, val publishedOnly: Boolean)

    /** Sets [client]'s sandbox flag to [sandbox], published and reloaded -- see the class note. */
    fun set(cxt: KdrCxt, client: String, sandbox: Boolean): Result {
        if (isSandboxClient(client)) {
            throw KdrException.mkInput("'$client' is a sandbox; a sandbox has no sandbox of its own.")
        }
        if (ClientService.get(cxt).originOf(client) == GedraConfigOrigin.source) {
            throw KdrException.mkInput(
                "Client '$client' is defined in source code, so whether it has a sandbox is set there " +
                    "(its ClientDef's sandbox flag); a stored definition cannot override it.",
            )
        }
        val bound = cxt.mkSubContext("sandboxEdit", client)
        val svc = GedraConfigService.get(bound)
        val holder = svc.listConfigs(bound).firstOrNull { it.entriesBySlot()[CCT.clientDef]?.isNotEmpty() == true }
            ?: throw KdrException.mkInput("Client '$client' has no stored definition whose sandbox flag could be set.")
        val current = holder.entriesBySlot()[CCT.clientDef]?.firstOrNull()?.get(CLD.sandbox) == true
        // Already so, and published: nothing to do. Anything else publishes, so an unpublished change -- even the
        // flag itself, set in a draft -- is refused rather than taken live unseen.
        if (current != sandbox || !holder.isPublished) {
            if (!holder.isPublished) {
                throw KdrException.mkInput(
                    "Configuration '${holder.configId.baseId}' of client '$client' has unpublished changes, which " +
                        "changing its sandbox would publish with it. Publish or revert that configuration first.",
                )
            }
            val written = patchFlag(svc, bound, holder, sandbox)
            ClientStoredEdit.publishAndReload(cxt, bound, client, written, undo = { patchFlag(svc, bound, written, current) })
        }
        return Result(
            client = client,
            sandbox = ClientService.get(cxt).isPresent(sandboxOf(client)),
            publishedOnly = GedraConfigService.get(cxt).publishedOnly(cxt, client),
        )
    }

    /** Patches [row]'s client definition so its sandbox flag is [sandbox] -- absent when off, as `toInfo` writes it. */
    private fun patchFlag(svc: GedraConfigService, bound: KdrCxt, row: GedraConfigRow, sandbox: Boolean): GedraConfigRow =
        svc.patchConfig(bound, row.configId, trial = true) { current ->
            val out = LinkedHashMap(current)
            val defs = current[CCT.clientDef].orEmpty()
            val def = LinkedHashMap(defs.firstOrNull().toJsonMapOrEmpty())
            if (sandbox) def[CLD.sandbox] = true else def.remove(CLD.sandbox)
            out[CCT.clientDef] = listOf(def) + defs.drop(1)
            out
        }
}
