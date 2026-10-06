package com.dynamicruntime.common.gedra

import com.dynamicruntime.common.context.KdrCxt
import com.dynamicruntime.common.exception.KdrException
import com.dynamicruntime.common.user.normalizeUserLabels
import com.dynamicruntime.common.util.toJsonListOfStrings
import com.dynamicruntime.common.util.toJsonMapOrEmpty
import com.dynamicruntime.common.util.toOptStr

/**
 * Editing a client's definition from the Clients page (issue #1026): the write behind
 * `POST /clientAdmin/client/definition/set`. The fields are [ClientPresentationFields] -- what the client presents
 * and where it is reached -- and only those: the endpoint's input declares no other, so a write naming `audience`
 * (say) is refused by the input's shape rather than dropped, which is the rule #820 set for the operator-only
 * fields, and the structural ones stay read-only on the page.
 *
 * ### Where an edit lands
 *
 * In the stored config holding the client's `clientDef` entry ([ClientStoredEdit.definitionHolder]), patched under
 * the client's config lock ([ClientStoredEdit.patchDefinition]) over the revision as it stands there, so a field
 * another administrator changed a moment ago is kept. An edit starts from that stored entry -- the retrieve's
 * `storedDefinition` -- not from what the client runs, which differs by an unpublished draft and by what a template
 * fills in (a template's labels written back would become the client's own). A client defined in **source code** is refused: a stored definition cannot override a source one, so the
 * edit would change nothing, and the refusal says where the definition is. A **sandbox** is refused too: its
 * definition is derived from its parent's (`SandboxConfigs.deriveDef`), which is edited on the parent's page.
 *
 * ### Live at once, or a draft
 *
 * The copy and menu editors' rule ([ClientStoredEdit], issue #930): a client without a sandbox has the config
 * published and the client reloaded in the one call, refused while that config carries somebody's unpublished
 * changes, which the publish would take live; a client with a sandbox saves a draft -- written and reloaded, not
 * published -- so the sandbox shows the new name and publishing is the explicit step. A publish the trial refuses is
 * undone, the definition put back as it was.
 */
object ClientDefinitionEdit {
    /** What the edit did: the config it landed in, the definition as stored now, and how it took effect. */
    class Result(
        val configName: String,
        /** The stored definition after the edit, as [ClientDef.toInfo] shapes it. */
        val info: Map<String, Any?>,
        val issues: List<GedraConfigIssue>,
        /** How the save took effect, an [EDM] value (issue #930): live, or a draft its sandbox runs. */
        val mode: String,
    )

    /**
     * Sets [fields] -- [ClientPresentationFields] keys to their new values -- on [client]'s stored definition and makes
     * that take effect, live or as a draft. An absent field is left as it is; a blank string, or an empty list,
     * clears an optional one. At least one field must be given.
     */
    fun set(cxt: KdrCxt, client: String, fields: Map<String, Any?>): Result {
        val changes = cleaned(client, fields)
        if (changes.isEmpty()) throw KdrException.mkInput("A definition edit changes at least one field; this one names none.")
        sandboxParentOf(client)?.let { parent ->
            throw KdrException.mkInput(
                "'$client' is a sandbox: its definition is derived from '$parent''s, which is edited on that client.",
            )
        }
        if (ClientService.get(cxt).originOf(client) == GedraConfigOrigin.source) {
            throw KdrException.mkInput(
                "Client '$client' is defined in source code (its ClientDef), so its definition is edited there; a " +
                    "stored definition cannot override it.",
            )
        }
        requireRoutingFree(cxt, client, changes)
        val target = ClientStoredEdit.target(cxt, client, "definitionEdit")
        val bound = target.bound
        val holder = ClientStoredEdit.definitionHolder(bound)
            ?: throw KdrException.mkInput("Client '$client' has no stored definition to edit.")
        ClientStoredEdit.requireNoForeignDraft(target, holder, "a definition edit")
        val before = holder.entriesBySlot()[CCT.clientDef]?.firstOrNull().toJsonMapOrEmpty()
        val written = ClientStoredEdit.patchDefinition(bound, holder) { def ->
            def.putAll(changes)
            def.entries.removeAll { it.value == null }
        }
        val reload = ClientStoredEdit.takeEffect(
            cxt, target, written,
            undo = { ClientStoredEdit.patchDefinition(bound, written) { def -> def.clear(); def.putAll(before) } },
            // The draft rule admitted only a published holder, so a refused publish puts it back as published.
            restorePublished = true,
        )
        return Result(
            configName = written.configId.baseId,
            // Redacted as every stored read is (`testFeatures` off a test instance, #696).
            info = written.slotsForEmission(cxt.instanceConfig.isTestInstance)[CCT.clientDef]?.firstOrNull().toJsonMapOrEmpty(),
            issues = reload.issues,
            mode = target.mode,
        )
    }

    /**
     * Refuses a domain prefix or custom domain in [changes] that another known client already declares: the two
     * route to one client each, and a client's own administrator must not be able to claim another's. Checked here,
     * at the one write a client makes to its own routing; a collision between source definitions is a deployment's
     * own mistake to make.
     */
    private fun requireRoutingFree(cxt: KdrCxt, client: String, changes: Map<String, Any?>) {
        val others = ClientService.get(cxt).clients.values.filter { it.clientId != client }
        changes[CLD.domainPrefix]?.let { prefix ->
            others.firstOrNull { it.domainPrefix == prefix }?.let {
                throw KdrException.mkInput("The domain prefix '$prefix' is client '${it.clientId}''s; a prefix routes to one client.")
            }
        }
        changes[CLD.customDomain]?.let { domain ->
            others.firstOrNull { it.customDomain == domain }?.let {
                throw KdrException.mkInput("The domain '$domain' is client '${it.clientId}''s; a domain routes to one client.")
            }
        }
    }

    /**
     * [fields] as the definition will hold them: text trimmed, the name refused blank, the labels held to the one
     * label rule ([normalizeUserLabels]) rather than refused later by `ClientDef`'s own check -- and an optional field
     * cleared ("" or an empty list) kept as a null, which the patch removes, as `toInfo` leaves an absent one out.
     * A key outside [ClientPresentationFields] is a fault: the endpoint's input admits no other.
     */
    private fun cleaned(client: String, fields: Map<String, Any?>): Map<String, Any?> {
        val out = LinkedHashMap<String, Any?>()
        for ((key, value) in fields) {
            if (key !in ClientPresentationFields.names) throw KdrException.mkInput("'$key' is not a field a client's definition edit may set.")
            out[key] = when (key) {
                CLD.name -> value.toOptStr()?.trim()?.ifEmpty { null }
                    ?: throw KdrException.mkInput("Client '$client' needs a name; a blank one is refused.")
                CLD.userLabels -> normalizeUserLabels(value.toJsonListOfStrings()).ifEmpty { null }
                else -> value.toOptStr()?.trim()?.ifEmpty { null }
            }
        }
        return out
    }
}
