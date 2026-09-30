package com.dynamicruntime.common.gedra

import com.dynamicruntime.common.content.FragmentAudience
import com.dynamicruntime.common.context.KdrCxt
import com.dynamicruntime.common.endpoint.HttpMethod
import com.dynamicruntime.common.endpoint.InputFieldsBuilder
import com.dynamicruntime.common.endpoint.SchModule
import com.dynamicruntime.common.endpoint.schemaModule
import com.dynamicruntime.common.exception.KdrException
import com.dynamicruntime.common.schema.SCT
import com.dynamicruntime.common.util.getReqNonBlankStr
import com.dynamicruntime.common.util.toOptStr

/**
 * Editing a client's copy (issue #918): the keys an administrator may override for a client, and the set and reset
 * of one -- each written to the client's stored configuration, trial-checked, published and made live at once
 * (see [ClientCopyEdit]). In the `clientAdmin` section and scoped as the client overview's retrieves are: the
 * caller's own client unless they may name another. App-only, as the overview is: the fragment service is what it
 * reads and writes through.
 */
fun clientCopySchema(cxt: KdrCxt): SchModule = schemaModule(cxt, CPY.namespace) {
    type(CPY.keyTypeName) {
        type = SCT.kObject
        description = "One piece of copy a client's people read, and what it says for that client."
        property(COV.fileId, "The fragment file.", required = true)
        property(COV.namespaceField, "The namespace within the file.", required = true)
        property(COV.key, "The key within the namespace.", required = true)
        property(COV.audience, "Who the file is for: delivered to the frontend, or pulled by the backend.", required = true) {
            options(FragmentAudience.entries)
        }
        // `emptyIsAbsent = false`: an empty value is a value the file declares, not a missing one.
        property(COV.value, "The value this client reads, every layer applied.", required = true) { emptyIsAbsent = false }
    }
    type(CPY.resultTypeName) {
        type = SCT.kObject
        description = "What setting or resetting a key did: where it was written, and what the client's people now read."
        property(COV.client, "The client.", required = true)
        property(COV.fileId, "The fragment file.", required = true)
        property(COV.namespaceField, "The namespace within the file.", required = true)
        property(COV.key, "The key within the namespace.", required = true)
        property(COV.configName, "The stored configuration the change landed in.", required = true)
        property(COV.value, "The value the client now reads; absent when nothing sets the key any more.") { emptyIsAbsent = false }
        property(CPY.stored, "Whether a stored value still sets the key -- false after a reset that left the source or " +
            "shipped value.", required = true) { type = SCT.boolean }
        property(CPY.buildId, "The build id the file's content for this client is now served under.")
        property(CPY.issues, "The problems the client's configuration has after the reload, all pre-existing: the " +
            "change itself was refused if it added one.", required = true) {
            type = SCT.array
            items { ref(CLD.configIssueTypeQualified) }
        }
    }

    listEndpoint(
        CPY.keysPath,
        "Every piece of copy an administrator may override for a client -- each key of each fragment file that has " +
            "a base, with the value that client reads -- including the backend files the content server never " +
            "serves. The caller's own client, or one an administrator who sees every client names.",
        outputRef = CPY.keyTypeName,
        noLimit = true,
        needsClientConfig = true,
        inputFields = { field(COV.client, "The client; the caller's own when absent.") },
    ) { c, request ->
        val client = overseenClient(c, request[COV.client].toOptStr())
        ClientCopyEdit.keysFor(c, client).map {
            mapOf(COV.fileId to it.fileId, COV.namespaceField to it.namespace, COV.key to it.key, COV.audience to it.audience, COV.value to it.value)
        }
    }

    generalEndpoint(
        CPY.setPath,
        "Sets one key's value for a client and makes it live: written into the client's stored configuration (the " +
            "config already overlaying the file, else one named '${CPY.copyConfigName}', created on the first edit), " +
            "refused with its findings when a trial reload would find a new problem, then published and reloaded. A " +
            "key the client's source configuration sets is overridden, not replaced.",
        HttpMethod.POST,
        outputRef = CPY.resultTypeName,
        needsClientConfig = true,
        inputFields = {
            copyKeyInput()
            field(COV.value, "The value, as Markdown.", required = true) { emptyIsAbsent = false }
        },
    ) { c, request ->
        val client = overseenClient(c, request[COV.client].toOptStr())
        val value = request[COV.value].toOptStr()
            ?: throw KdrException.mkInput("A '${COV.value}' is required; to remove a stored value, reset the key.")
        ClientCopyEdit.set(
            c, client, request.getReqNonBlankStr(COV.fileId), request.getReqNonBlankStr(COV.namespaceField),
            request.getReqNonBlankStr(COV.key), value,
        ).toWireMap(client, request)
    }

    generalEndpoint(
        CPY.resetPath,
        "Removes a client's stored value for one key and makes that live, so the key reads as the client's source " +
            "configuration or the shipped copy says. Refused when no stored value sets it.",
        HttpMethod.POST,
        outputRef = CPY.resultTypeName,
        needsClientConfig = true,
        inputFields = { copyKeyInput() },
    ) { c, request ->
        val client = overseenClient(c, request[COV.client].toOptStr())
        ClientCopyEdit.reset(
            c, client, request.getReqNonBlankStr(COV.fileId), request.getReqNonBlankStr(COV.namespaceField),
            request.getReqNonBlankStr(COV.key),
        ).toWireMap(client, request)
    }
}

/** The address of one key, and the client it is for. */
private fun InputFieldsBuilder.copyKeyInput() {
    field(COV.client, "The client; the caller's own when absent.")
    field(COV.fileId, "The fragment file.", required = true)
    field(COV.namespaceField, "The namespace within the file.", required = true)
    field(COV.key, "The key within the namespace.", required = true)
}

private fun ClientCopyEdit.Result.toWireMap(client: String, request: Map<String, Any?>): Map<String, Any?> {
    val out = linkedMapOf<String, Any?>(
        COV.client to client,
        COV.fileId to request[COV.fileId].toOptStr(),
        COV.namespaceField to request[COV.namespaceField].toOptStr(),
        COV.key to request[COV.key].toOptStr(),
        COV.configName to configName,
    )
    if (value != null) out[COV.value] = value
    out[CPY.stored] = stored
    if (buildId != null) out[CPY.buildId] = buildId
    out[CPY.issues] = issues.map { it.toWireMap() }
    return out
}
