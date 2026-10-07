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
 * It adds no rule of its own beyond "the id is not taken" -- and that one is kept twice: asked before anything is
 * built, so a taken id is refused in words that say who holds it, and enforced by the write itself, under the
 * client's lock (`ConfigWrite.mustBeNew`), so two creates of one id cannot both succeed whichever asked first.
 *
 * The definition goes through the write every definition
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
        /**
         * Why loading the client failed on this node, when it did -- after the definition was stored and published,
         * so the client **was** created. Said in the result rather than thrown: an error would send the caller to
         * create it again, and be refused for an id they would then have no way of knowing they held.
         */
        val loadFailure: String? = null,
    )

    /**
     * Creates the client [request] describes -- [ClientCreateFields] keys to their values, **as the endpoint's
     * input type validated them**: reading a request is what trims its text and leaves a blank optional field out,
     * so neither is done again here. The suggested labels are held to the one label rule ([normalizeUserLabels]),
     * as the definition editor holds them, rather than refused for a stray space.
     */
    fun create(cxt: KdrCxt, request: Map<String, Any?>): Result {
        val client = request[CLD.clientId].toOptStr().orEmpty()
        // First, since a live sandbox's id is one this node knows: it would otherwise read as a client that exists.
        sandboxParentOf(client)?.let { parent ->
            throw KdrException.mkInput(
                "'$client' is a sandbox's id. A sandbox is made by the system from its parent, '$parent', when that " +
                    "client asks for one; it is never created.",
            )
        }
        val bound = cxt.mkSubContext("clientCreate", client)
        val svc = GedraConfigService.get(bound)
        if (svc.clientExists(bound)) throw taken(cxt, client)
        val slots = mapOf(CCT.clientDef to listOf(definitionOf(request)))
        val name = CLD.definitionConfigName
        val config = reassembleForWrite(bound, name, clientNamespace(client), client, slots)
        val written = try {
            svc.writeConfig(bound, config, trial = true, mustBeNew = true)
        } catch (e: KdrException) {
            // The id was free when asked and taken by the time the write held the lock: another create of it.
            if (e.code != EXC.conflict) throw e
            throw KdrException(
                "Client '$client' already exists: its definition was written while this create was under way.", e, EXC.conflict,
            )
        }
        // Published, so the client loads whatever its tier: one asking for a sandbox runs only what is published.
        // The write's trial judged this very definition, alone, so the publish has nothing new to refuse; were it
        // to, the configuration stays written and unpublished, and a second create is told the id has one.
        val published = svc.publish(bound, written.configId, trial = true)
        // From here the client exists, whatever becomes of loading it: a failure is the result's to say.
        var loadFailure: String? = null
        val issues = try {
            val reload = GedraConfigReload.reloadClient(cxt, client)
            ClientSyncService.get(cxt).announceReload(cxt, reload)
            reload.issues
        } catch (e: Exception) {
            LogGedra.error(cxt, "Client '$client' was created, but loading it failed.", e)
            loadFailure = e.message ?: "The load failed."
            emptyList()
        }
        return Result(
            client = client,
            configName = name,
            // Redacted as every stored read is (`testFeatures` off a test instance, #696).
            info = published.slotsForEmission(cxt.instanceConfig.isTestInstance)[CCT.clientDef]?.firstOrNull().toJsonMapOrEmpty(),
            present = ClientService.get(cxt).present(client) != null,
            issues = issues,
            loadFailure = loadFailure,
        )
    }

    /**
     * The refusal for an id that is taken, saying who holds it: a client this node knows, defined in source code or
     * in stored configuration -- or stored configuration this node has not made a client of, a definition a check
     * dropped or one never published, which is mended or published through the configuration endpoints.
     */
    private fun taken(cxt: KdrCxt, client: String): KdrException {
        val clients = ClientService.get(cxt)
        val message = if (clients.known(client) != null) {
            val where = if (clients.originOf(client) == GedraConfigOrigin.source) "source code" else "stored configuration"
            "Client '$client' already exists: it is defined in $where."
        } else {
            "Client '$client' already has stored configuration, though this node does not carry the client. Its " +
                "configuration is published, or mended, through the configuration endpoints."
        }
        return KdrException(message, code = EXC.conflict)
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
