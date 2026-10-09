package com.dynamicruntime.common.gedra

import com.dynamicruntime.common.context.KdrCxt
import com.dynamicruntime.common.endpoint.HttpMethod
import com.dynamicruntime.common.endpoint.InputFieldsBuilder
import com.dynamicruntime.common.endpoint.SchModule
import com.dynamicruntime.common.endpoint.schemaModule
import com.dynamicruntime.common.schema.SCT
import com.dynamicruntime.common.util.getReqNonBlankStr
import com.dynamicruntime.common.util.toOptStr

/**
 * Editing a client's home menu (issue #919): its items as the client sees them, and the rename, hide, show, and reset
 * of one -- written to the client's stored configuration, trial-checked, published, and made live at once, or for a
 * client with a Shadow Sandbox saved as a draft its sandbox shows (issue #930; see [ClientMenuEdit]). In the
 * `clientAdmin` section and scoped as the client overview's retrieves are. App-only, as the copy editor is.
 */
fun clientMenuSchema(cxt: KdrCxt): SchModule = schemaModule(cxt, MNU.namespace) {
    type(MNU.audienceTypeName) {
        type = SCT.kObject
        description = "An audience a home-menu item may be shown to: the condition stored, and what it is called."
        property(MNU.condition, "The condition a show sends to choose this audience.", required = true)
        property(MNU.name, "What the audience is called.", required = true)
    }
    type(MNU.itemTypeName) {
        type = SCT.kObject
        description = "One home-menu item as a client sees it: what the shipped menu says, what the client changed."
        property(COV.itemId, "The item's id.", required = true)
        property(MNU.parentId, "The item this one sits under; absent for a top-level item.")
        property(COV.baseLabel, "The shipped label; absent for an item with none.")
        property(MNU.label, "The label this client's people see.") { emptyIsAbsent = false }
        property(MNU.baseCondition, "The shipped condition deciding who is offered the item; absent means everyone.")
        property(MNU.condition, "The condition for this client; `#never` withdraws the item.")
        property(MNU.baseAudience, "What the shipped condition's audience is called; absent for a withdrawn item and for a condition with no name.")
        property(MNU.audience, "What this client's condition's audience is called; absent for a withdrawn item and for a condition with no name.")
        property(MNU.audiences, "The audiences the item may be shown to: the ones any item may, and the item's own shipped one.", required = true) {
            type = SCT.array
            items { ref(MNU.audienceTypeName) }
        }
        property(CPY.stored, "Whether this client's stored configuration changes the item's label or condition.", required = true) {
            type = SCT.boolean
        }
    }
    type(MNU.resultTypeName) {
        type = SCT.kObject
        description = "What a menu edit did: where it landed, and the item as the client's people now get it."
        property(COV.client, "The client.", required = true)
        property(COV.itemId, "The item's id.", required = true)
        property(COV.configName, "The stored configuration the change landed in.", required = true)
        property(MNU.label, "The label the client's people now see.") { emptyIsAbsent = false }
        property(MNU.condition, "The condition the item is now offered under; `#never` withdraws it.")
        property(MNU.audience, "What that condition's audience is called; absent for a withdrawn item and for a condition with no name.")
        property(CPY.stored, "Whether a stored change to the item remains -- false after a reset.", required = true) { type = SCT.boolean }
        property(CPY.mode, "How the save took effect (issue #930): live for the client at once, or a draft its sandbox runs until it is published.", required = true) {
            option(EDM.live, "Live")
            option(EDM.draft, "Draft")
        }
        property(CPY.issues, "The problems the client's configuration has after the reload, all pre-existing.", required = true) {
            type = SCT.array
            items { ref(CLD.configIssueTypeQualified) }
        }
    }

    listEndpoint(
        MNU.itemsPath,
        "The home menu's items for a client, in the menu's order: the shipped label and condition, the client's, " +
            "what each condition's audience is called, the audiences the item may be shown to, and whether the " +
            "client's stored configuration changes the item. Every item the menu holds, before any " +
            "one caller's cfacts are applied. The caller's own client, or one an administrator who sees every client names.",
        outputRef = MNU.itemTypeName,
        noLimit = true,
        needsClientConfig = true,
        inputFields = { overseenClientField(COV.client) },
    ) { c, request ->
        val client = overseenClient(c, request[COV.client].toOptStr())
        ClientMenuEdit.itemsFor(c, client).map { item ->
            val out = linkedMapOf<String, Any?>(COV.itemId to item.itemId)
            item.parentId?.let { out[MNU.parentId] = it }
            item.baseLabel?.let { out[COV.baseLabel] = it }
            item.label?.let { out[MNU.label] = it }
            item.baseCondition?.let { out[MNU.baseCondition] = it }
            item.condition?.let { out[MNU.condition] = it }
            item.baseAudience?.let { out[MNU.baseAudience] = it }
            item.audience?.let { out[MNU.audience] = it }
            out[MNU.audiences] = item.audiences.map { mapOf(MNU.condition to it.condition, MNU.name to it.name) }
            out[CPY.stored] = item.stored
            out
        }
    }

    generalEndpoint(
        MNU.setPath,
        "Renames, hides or shows one home-menu item for a client and makes it take effect: written into the client's " +
            "stored configuration (the config already changing the item, else one overlaying the menu, else " +
            "'${CPY.copyConfigName}'), refused when a trial reload would find a new problem, then published and " +
            "reloaded. Hiding or showing is presentation, not permission: the section gate still decides who may " +
            "reach a page. Showing names the condition of one of the item's audiences, as the items listing gives them. " +
            "For a client with a Shadow Sandbox (issue #930) the change is saved as a draft instead -- written and " +
            "reloaded, not published -- so its sandbox shows it and it goes live once published.",
        HttpMethod.POST,
        outputRef = MNU.resultTypeName,
        needsClientConfig = true,
        inputFields = {
            menuItemInput()
            field(MNU.label, "The new label; absent leaves the label as it is.")
            field(MNU.visibility, "'${MNU.hide}' withdraws the item; '${MNU.show}' offers it under '${MNU.condition}'; absent leaves it.") {
                option(MNU.hide, "Hide")
                option(MNU.show, "Show")
            }
            field(MNU.condition, "For a show: the condition to offer the item under, one of the item's '${MNU.audiences}' in the items listing.")
        },
    ) { c, request ->
        val client = overseenClient(c, request[COV.client].toOptStr())
        ClientMenuEdit.set(
            c, client, request.getReqNonBlankStr(COV.itemId), request[MNU.label].toOptStr(),
            request[MNU.visibility].toOptStr(), request[MNU.condition].toOptStr(),
        ).toWireMap(client, request)
    }

    generalEndpoint(
        MNU.resetPath,
        "Removes a client's stored changes to one home-menu item and makes that take effect, so the item shows as " +
            "the client's source configuration or the shipped menu says. Refused when no stored change touches it. " +
            "For a client with a Shadow Sandbox (issue #930) the change is saved as a draft instead -- written and " +
            "reloaded, not published -- so its sandbox shows it and it goes live once published.",
        HttpMethod.POST,
        outputRef = MNU.resultTypeName,
        needsClientConfig = true,
        inputFields = { menuItemInput() },
    ) { c, request ->
        val client = overseenClient(c, request[COV.client].toOptStr())
        ClientMenuEdit.reset(c, client, request.getReqNonBlankStr(COV.itemId)).toWireMap(client, request)
    }
}

private fun InputFieldsBuilder.menuItemInput() {
    overseenClientField(COV.client)
    field(COV.itemId, "The home-menu item's id.", required = true)
}

private fun ClientMenuEdit.Result.toWireMap(client: String, request: Map<String, Any?>): Map<String, Any?> {
    val out = linkedMapOf<String, Any?>(COV.client to client, COV.itemId to request[COV.itemId].toOptStr(), COV.configName to configName)
    label?.let { out[MNU.label] = it }
    condition?.let { out[MNU.condition] = it }
    audience?.let { out[MNU.audience] = it }
    out[CPY.stored] = stored
    out[CPY.issues] = issues.map { it.toWireMap() }
    out[CPY.mode] = mode
    return out
}
