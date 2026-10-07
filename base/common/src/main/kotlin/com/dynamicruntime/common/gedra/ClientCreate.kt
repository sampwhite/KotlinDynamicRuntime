package com.dynamicruntime.common.gedra

import com.dynamicruntime.common.context.KdrCxt
import com.dynamicruntime.common.exception.EXC
import com.dynamicruntime.common.exception.KdrException
import com.dynamicruntime.common.naming.clientNamespace
import com.dynamicruntime.common.user.normalizeUserLabels
import com.dynamicruntime.common.util.toJsonListOfStrings
import com.dynamicruntime.common.util.toJsonMapOrEmpty
import com.dynamicruntime.common.util.toOptStr

/**
 * Creating a client from data (issue #1054): its definition written as its first stored configuration, published,
 * and the client loaded -- one call, where it used to be a hand-built bundle write followed by a reload.
 *
 * It adds no rule of its own beyond "the id is not taken". The definition goes through the write every definition
 * goes through: the slot gate (`reassembleForWrite`, issue #1051) holds it to its schema, `writeConfig` to the
 * write's own guards (its namespace, a domain no other client holds) and to a trial reload, which is where the
 * rules that need the deployment's other clients are judged -- that its id is well-formed, its environments a legal
 * set, the client it extends a template that exists. So a create is refused for exactly what a bundle write of the
 * same definition would be, with the same words.
 */
object ClientCreate {
    /** What a create did: the configuration the definition was written to, the definition as stored, and whether this node now carries the client. */
    class Result(
        val client: String,
        val configName: String,
        /** The stored definition, as [ClientDef.toInfo] shapes it. */
        val info: Map<String, Any?>,
        /** Whether this node carries the client now: false when it is not enabled in this node's environment. */
        val present: Boolean,
        /** What the load found in the client's configuration and forgave; empty for a definition that is sound. */
        val issues: List<GedraConfigIssue>,
    )

    /**
     * Creates the client [request] describes -- [ClientCreateFields] keys to their values, **as the endpoint's
     * input type validated them**: reading a request is what trims its text and leaves a blank optional field out,
     * so neither is done again here. The suggested labels are held to the one label rule ([normalizeUserLabels]),
     * as the definition editor holds them, rather than refused for a stray space.
     */
    fun create(cxt: KdrCxt, request: Map<String, Any?>): Result {
        val client = request[CLD.clientId].toOptStr().orEmpty()
        val bound = cxt.mkSubContext("clientCreate", client)
        val svc = GedraConfigService.get(bound)
        ClientService.get(cxt).known(client)?.let {
            val where = if (ClientService.get(cxt).originOf(client) == GedraConfigOrigin.source) "source code" else "stored configuration"
            throw KdrException("Client '$client' already exists: it is defined in $where.", code = EXC.conflict)
        }
        // Written and never loaded -- a definition a check dropped, or a configuration that was never published: the
        // id is taken all the same, and what holds it is mended or published through the configuration endpoints.
        if (svc.listConfigs(bound).isNotEmpty()) {
            throw KdrException(
                "Client '$client' already has stored configuration, though this node does not carry the client. " +
                    "Its configuration is published, or mended, through the configuration endpoints.",
                code = EXC.conflict,
            )
        }
        val slots = mapOf(CCT.clientDef to listOf(definitionOf(request)))
        val name = CLD.definitionConfigName
        val written = svc.writeConfig(bound, reassembleForWrite(bound, name, clientNamespace(client), client, slots), trial = true)
        // Published, so the client loads whatever its tier: one asking for a sandbox runs only what is published.
        // The write's trial judged this very definition, alone, so the publish has nothing new to refuse; were it
        // to, the configuration stays written and unpublished, which the second refusal above then names.
        val published = svc.publish(bound, written.configId, trial = true)
        val reload = GedraConfigReload.reloadClient(cxt, client)
        ClientSyncService.get(cxt).announceReload(cxt, reload)
        return Result(
            client = client,
            configName = name,
            // Redacted as every stored read is (`testFeatures` off a test instance, #696).
            info = published.slotsForEmission(cxt.instanceConfig.isTestInstance)[CCT.clientDef]?.firstOrNull().toJsonMapOrEmpty(),
            present = ClientService.get(cxt).present(client) != null,
            issues = reload.issues,
        )
    }

    /** The definition [request] asks for, as the `kdr:clientDef` slot holds one: its [ClientCreateFields] that were given. */
    private fun definitionOf(request: Map<String, Any?>): Map<String, Any?> {
        val out = LinkedHashMap<String, Any?>()
        for (field in ClientCreateFields.names) {
            val value = when (field) {
                CLD.userLabels -> normalizeUserLabels(request[field].toJsonListOfStrings()).ifEmpty { null }
                else -> request[field]
            }
            if (value != null) out[field] = value
        }
        return out
    }
}
