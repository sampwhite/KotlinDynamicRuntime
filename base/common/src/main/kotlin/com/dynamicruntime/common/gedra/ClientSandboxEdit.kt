package com.dynamicruntime.common.gedra

import com.dynamicruntime.common.context.KdrCxt
import com.dynamicruntime.common.exception.EXC
import com.dynamicruntime.common.exception.KdrException
import com.dynamicruntime.common.startup.SchemaCollector

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
 * **A client defined in code** (issue #1677) has its definition there, which a stored one cannot override. Its switch
 * stores an overlay of that definition instead (`"overrides": "codeOverlay"`, holding only `sandbox`) in the stored
 * bundle named like the code bundle that declares the client, where the bundle editor keeps every change to the
 * client's code. The sandbox is on if either says so: a sandbox the code sets is removed in the code, and the edit is
 * refused, saying so. Removing one the switch added drops the overlay, and the bundle too when nothing else is in it.
 * The same publish, reload, and unpublished-changes rule apply. A code client's sandbox previews only its stored
 * changes, since code reaches it and the client together -- which, with the overlays and Design View edits a code
 * client now takes (#1646, #1657, #1670), is what a sandbox is for.
 *
 * **A visitor** who joined a demo client may not add or remove its sandbox, whatever the deployment lets visitors
 * change ([ClientJoin.sandboxSwitchRefusal]). Removing a sandbox keeps its users and data -- they belong to the
 * sandbox's client id, and come back with it.
 *
 * Theme: #SBXAU.
 */
object ClientSandboxEdit {
    /** What the edit left: whether the client has its sandbox, and whether it now runs only published configuration. */
    class Result(val client: String, val sandbox: Boolean, val publishedOnly: Boolean)

    /** Sets [client]'s sandbox flag to [sandbox], published and reloaded -- see the class note. */
    fun set(cxt: KdrCxt, client: String, sandbox: Boolean): Result {
        if (isSandboxClient(client)) {
            throw KdrException.mkInput("'$client' is a sandbox; a sandbox has no sandbox of its own.")
        }
        ClientJoin.sandboxSwitchRefusal(cxt, client)?.let { throw KdrException(it, code = EXC.notAuthorized) }
        val bound = cxt.mkSubContext("sandboxEdit", client)
        val codeDefiner = codeDefiner(cxt, client)
        if (codeDefiner != null) setForCode(cxt, bound, client, codeDefiner, sandbox) else setStored(cxt, bound, client, sandbox)
        return Result(
            client = client,
            sandbox = ClientService.get(cxt).isPresent(sandboxOf(client)),
            publishedOnly = GedraConfigService.get(cxt).publishedOnly(cxt, client),
        )
    }

    /** Whether the code sets [client]'s sandbox (issue #1677): one it sets is removed there, never from the page. */
    fun setInCode(cxt: KdrCxt, client: String): Boolean = codeDefiner(cxt, client)?.client?.sandbox == true

    /** The code configuration that defines [client], or null for a client defined in stored configuration. */
    private fun codeDefiner(cxt: KdrCxt, client: String): GedraConfig? =
        SchemaCollector.get(cxt)?.gedraConfigs?.configs?.firstOrNull { !it.isStored && it.client?.clientId == client }

    /** The switch for a client defined in stored configuration: its definition's own flag (issue #932). */
    private fun setStored(cxt: KdrCxt, bound: KdrCxt, client: String, sandbox: Boolean) {
        val holder = ClientStoredEdit.definitionHolder(bound)
            ?: throw KdrException.mkInput("Client '$client' has no stored definition whose sandbox flag could be set.")
        val current = holder.entriesBySlot()[CCT.clientDef]?.firstOrNull()?.get(CLD.sandbox) == true
        // Already so, and published: nothing to do. Anything else publishes, so an unpublished change -- even the
        // flag itself, set in a draft -- is refused rather than taken live unseen.
        if (current == sandbox && holder.isPublished) return
        requirePublished(holder, client)
        val written = patchFlag(bound, holder, sandbox)
        ClientStoredEdit.publishAndReload(
            cxt, bound, client, written, undo = { patchFlag(bound, written, current) }, restorePublished = true,
        )
    }

    /**
     * The switch for a client defined in code (issue #1677): an overlay of its definition, in the stored bundle named
     * like [definer], the code bundle that declares it -- see the class note.
     */
    private fun setForCode(cxt: KdrCxt, bound: KdrCxt, client: String, definer: GedraConfig, sandbox: Boolean) {
        if (definer.client?.sandbox == true) {
            if (sandbox) return
            throw KdrException.mkInput(
                "Client '$client' has its sandbox set in its code (its ClientDef's sandbox flag), so it is removed there.",
            )
        }
        val svc = GedraConfigService.get(bound)
        val configId = GedraId.of(GedraConfigType.configDoc, client, definer.name)
        val holder = svc.readLatest(bound, configId)
        val current = holder?.entriesBySlot()?.get(CCT.clientDef).orEmpty().any { it.isCodeOverlay && it[CLD.sandbox] == true }
        if (current == sandbox && (holder == null || holder.isPublished)) return
        holder?.let { requirePublished(it, client) }
        var before: Map<String, List<Map<String, Any?>>> = emptyMap()
        val written = svc.patchConfigs(bound, trial = true) { edit(configId) { slots ->
            before = slots
            withSandboxOverlay(slots, client, sandbox)
        } }.single()
        ClientStoredEdit.publishAndReload(
            cxt, bound, client, written,
            undo = {
                val undone = svc.patchConfigs(bound) { edit(configId) { before } }.single()
                // A bundle this edit made goes again: emptied, then deleted, so a refused first switch leaves none behind.
                if (holder == null) undone.updatedAt?.let { svc.deleteConfig(bound, configId, it) }
            },
            restorePublished = holder?.isPublished == true,
        )
        // A bundle the overlay was all of goes with it, now that its empty revision is published and changes nothing.
        if (!sandbox) {
            svc.readLatest(bound, configId)?.takeIf { it.entriesBySlot().values.all { e -> e.isEmpty() } }?.updatedAt?.let {
                svc.deleteConfig(bound, configId, it)
            }
        }
    }

    /**
     * [slots] with [client]'s overlay of its definition in code turning its sandbox on, or with it gone when [sandbox] is
     * false. Any other entry of the slot is left as it is.
     */
    private fun withSandboxOverlay(
        slots: Map<String, List<Map<String, Any?>>>,
        client: String,
        sandbox: Boolean,
    ): Map<String, List<Map<String, Any?>>> {
        val others = slots[CCT.clientDef].orEmpty().filterNot { it.isCodeOverlay }
        val entries = if (!sandbox) others else others + linkedMapOf(
            CLD.clientId to client, CCT.overrides to CCT.overridesCodeOverlayValue, CLD.sandbox to true,
        )
        return if (entries.isEmpty()) slots - CCT.clientDef else slots + (CCT.clientDef to entries)
    }

    /** Refuses the edit while [holder] has unpublished changes, which the edit would publish with it. */
    private fun requirePublished(holder: GedraConfigRow, client: String) {
        if (holder.isPublished) return
        throw KdrException.mkInput(
            "Configuration '${holder.configId.baseId}' of client '$client' has unpublished changes, which " +
                "changing its sandbox would publish with it. Publish or revert that configuration first.",
        )
    }

    /** Patches [row]'s client definition so its sandbox flag is [sandbox] -- absent when off, as `toInfo` writes it. */
    private fun patchFlag(bound: KdrCxt, row: GedraConfigRow, sandbox: Boolean): GedraConfigRow =
        ClientStoredEdit.patchDefinition(bound, row) { def -> if (sandbox) def[CLD.sandbox] = true else def.remove(CLD.sandbox) }
}
