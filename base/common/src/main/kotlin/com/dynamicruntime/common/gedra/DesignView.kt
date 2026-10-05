package com.dynamicruntime.common.gedra

import com.dynamicruntime.common.context.KdrCxt
import com.dynamicruntime.common.context.KdrSchemaStore
import com.dynamicruntime.common.endpoint.HttpMethod
import com.dynamicruntime.common.endpoint.SchModule
import com.dynamicruntime.common.endpoint.schemaModule
import com.dynamicruntime.common.exception.EXC
import com.dynamicruntime.common.exception.KdrException
import com.dynamicruntime.common.gedra.workflow.WfDeclared
import com.dynamicruntime.common.gedra.workflow.WorkflowService
import com.dynamicruntime.common.gedra.workflow.layoutEntryOf
import com.dynamicruntime.common.gedra.workflow.parseWfDef
import com.dynamicruntime.common.gedra.workflow.withLayoutEntry
import com.dynamicruntime.common.gedra.workflow.workflowDefStamp
import com.dynamicruntime.common.gedra.workflow.toJsonMap
import com.dynamicruntime.common.schema.SCH
import com.dynamicruntime.common.schema.SCT
import com.dynamicruntime.common.schema.SL
import com.dynamicruntime.common.startup.SchemaService
import com.dynamicruntime.common.user.AdminRules
import com.dynamicruntime.common.util.getReqNonBlankStr
import com.dynamicruntime.common.util.toJsonMap
import com.dynamicruntime.common.util.toOptStr

/**
 * **Design View** (issue #972): an administrator's mode that shows, beside what a page renders, which definition
 * each part of it comes from and where that definition was declared.
 *
 * Two things live here:
 *  - **The gate** ([isOn]). A page asks for Design View with the request's view flag ([KdrCxt.view]); this is the
 *    one place that decides whether to honor it. Design View shows what an ordinary user is never sent, so the
 *    decision is the server's: a caller who is not a client administrator, asking for it, gets the ordinary
 *    response, unchanged.
 *  - **Addresses and provenance.** A definition is named by `{ slot, key, path }` ([DSV]): the stored-config slot
 *    it is an entry of, its key there, and a place within the entry. [typeAddress] says, for a schema type a
 *    page renders, which definition it belongs to and whether that was declared in the client's stored
 *    configuration (editable in place), in source for the client, or globally. The [DSV.definition] endpoint
 *    reads one definition's authored entry by its address.
 *
 * **Read-only.** Nothing here writes; editing is a later slice. And nothing here changes what an ordinary response
 * contains: a surface adds its Design View block only when [isOn] says so.
 */
object DesignView {
    /** Whether this request is in Design View: the caller asked for it, and administers a client (see the class note). */
    fun isOn(cxt: KdrCxt): Boolean = cxt.view == DSV.design && AdminRules.isClientAdministrator(cxt)

    /**
     * Where [config] puts a definition (see [DesignOrigin]); a definition with no config at all -- a type declared in
     * a component's code -- is global.
     */
    fun originOf(config: GedraConfig?): DesignOrigin = when {
        config == null || config.gedraId.client == GID.globalClient -> DesignOrigin.global
        config.isStored -> DesignOrigin.stored
        else -> DesignOrigin.source
    }

    /** [slot], [key] and (when given) [path], with where [config] declared it: the shape every address takes. */
    fun address(slot: String, key: String, path: String?, config: GedraConfig?): Map<String, Any?> {
        val origin = originOf(config)
        val out = linkedMapOf<String, Any?>(DSV.slot to slot, DSV.key to key)
        path?.let { out[DSV.path] = it }
        out[DSV.origin] = origin.name
        config?.let { out[DSV.config] = it.name }
        out[DSV.editable] = origin == DesignOrigin.stored
        return out
    }

    /**
     * The address of the definition that declares the schema type [typeName] as [client] sees it.
     *
     * A trait's generated types -- its entry type and its inline data type -- belong to the **trait's** declaration
     * (the `traitDef` entry, where the data type is written under `dataSchema`), since that is what an author
     * edits. Any other type is a `schemaDef` entry of its own, its body under `schema`. A type no config declares
     * (one from a component's code) is global, with no config to name.
     */
    fun typeAddress(cxt: KdrCxt, client: String, typeName: String): Map<String, Any?> {
        val config = SchemaService.get(cxt).configOfType(client, typeName)
        val trait = config?.let { traitGenerating(it, typeName) }
        return when {
            trait == null -> address(CCT.schemaDef, typeName, CCT.schema, config)
            trait.typeName == typeName -> address(CCT.traitDef, trait.traitId, null, config)
            else -> address(CCT.traitDef, trait.traitId, CCT.dataSchema, config)
        }
    }

    /** The trait of [config] that generates [typeName] -- as its entry type or its inline data type -- or null. */
    private fun traitGenerating(config: GedraConfig, typeName: String): GedraTrait? =
        config.traits.values.firstOrNull { it.typeName == typeName || inlineDataTypeName(config, it) == typeName }

    /**
     * The workflow view's Design View block (issue #972): the workflow's own address, and the address of every type
     * the view's `$defs` closure carries -- which is how the page names the definition behind a field it draws.
     */
    fun workflowBlock(
        cxt: KdrCxt,
        declared: WfDeclared,
        defs: Map<String, Any?>,
        /** The client's schema store -- what the workflow's type alterations are applied over (issue #984). */
        clientStore: KdrSchemaStore,
        /** The workflow's own, with its alterations applied; the client's when it alters nothing. */
        store: KdrSchemaStore,
    ): Map<String, Any?> {
        val refusal = editRefusal(cxt, declared)
        val out = linkedMapOf<String, Any?>(
            DSV.workflow to address(CCT.workflowDef, declared.def.workflowId, null, declared.bundle),
            DSV.types to defs.keys.associateWith { typeAddress(cxt, cxt.client, it) },
            // Editing (issue #984): whether this caller may change the workflow's copy here -- and why not, when not
            // -- and the stamp of the definition the page was drawn from, which an edit sends back.
            DSV.canEdit to (refusal == null),
            DSV.basedOn to workflowDefStamp(declared.def),
        )
        if (refusal != null) out[DSV.editRefusal] = refusal
        val edits = layoutEdits(declared, clientStore, store)
        if (edits.isNotEmpty()) out[DSV.layoutEdits] = edits
        return out
    }

    /**
     * The layout entries [declared] alters, by type name and then field, each with the workflow's entry, the inherited
     * one it replaces (the client's view of the type), and whether that inherited entry has changed since the
     * override was made (issue #984). Only for types [store] still carries.
     */
    private fun layoutEdits(declared: WfDeclared, clientStore: KdrSchemaStore, store: KdrSchemaStore): Map<String, Any?> {
        val out = linkedMapOf<String, Any?>()
        for ((typeName, alteration) in declared.def.typeAlterations) {
            if (typeName !in store.defs) continue
            val layout = alteration[SCH.layout] as? Map<*, *> ?: continue
            val basis = declared.def.typeBasis[typeName].orEmpty()
            val fields = linkedMapOf<String, Any?>()
            for (raw in (layout[SL.schemaFields] as? List<*>).orEmpty()) {
                val entry = (raw as? Map<*, *>)?.toJsonMap() ?: continue
                val field = entry[SL.field].toOptStr() ?: continue
                val inherited = layoutEntryOf(clientStore, typeName, field)
                val made = (basis[field] as? Map<*, *>)?.toJsonMap()
                fields[field] = linkedMapOf(
                    DSV.entry to entry,
                    DSV.inherited to inherited,
                    // A basis records the inherited entry an override replaced, `{}` when there was none.
                    DSV.inheritedChanged to (made != null && made != (inherited ?: emptyMap<String, Any?>())),
                )
            }
            if (fields.isNotEmpty()) out[typeName] = fields
        }
        return out
    }

    /**
     * Why this caller may not edit [declared]'s copy here, or null when they may (issue #984): the workflow must be
     * the client's own stored definition, since a workflow's own copy is written into its definition and a workflow
     * declared in source (or a global one) has none here to write to -- overlaying one is issue #1011, and copy it
     * pulls from a fragment file is the client's copy overrides' to change -- and the client must run its latest
     * revision, so an edit shows on the page once saved. A sandbox always does; a
     * published-only client does not. Its edits are previewed in its sandbox when it has one -- but published-only is
     * also a tier an administrator sets on its own, and then there is no sandbox to send anyone to.
     */
    fun editRefusal(cxt: KdrCxt, declared: WfDeclared): String? {
        val bundle = declared.bundle
        if (!bundle.isStored || bundle.gedraId.client != cxt.client) {
            return "Workflow '${declared.def.workflowId}' is declared in source, not in this client's stored configuration, so copy " +
                "just for this workflow cannot be saved here yet. Copy it pulls from a fragment file can be changed in the client's " +
                "copy overrides, for every workflow that uses it."
        }
        val configs = GedraConfigService.get(cxt)
        if (!isSandboxClient(cxt.client) && configs.publishedOnly(cxt, cxt.client)) {
            val where = if (configs.asksForSandbox(cxt, cxt.client)) {
                "edit it from its sandbox"
            } else {
                "it has no sandbox to preview an edit in"
            }
            return "Client '${cxt.client}' runs its published configuration, so a change would not show here until published; $where."
        }
        return null
    }

    /**
     * Sets [field]'s layout entry for [typeName] in workflow [workflowId] to [entry], or removes it when [entry] is
     * null -- the workflow's own wording over the inherited copy (issue #984) -- and reloads the client so the page
     * shows it. Written where the configuration lives: the client's own, or a sandbox's parent's (issue #930).
     * Trial-checked like every config write, and refused when the definition has changed since [basedOn]. Returns
     * the new stamp, for the page's next edit.
     */
    fun setLayoutEntry(
        cxt: KdrCxt,
        workflowId: String,
        typeName: String,
        field: String,
        entry: Map<String, Any?>?,
        basedOn: String,
    ): String {
        val declared = WorkflowService.get(cxt).forClient(cxt.client).workflow(workflowId)
            ?: throw KdrException("No workflow '$workflowId' for client '${cxt.client}'.", code = EXC.notFound)
        editRefusal(cxt, declared)?.let { throw KdrException.mkInput(it) }
        val inherited = layoutEntryOf(SchemaService.get(cxt).storeFor(cxt.client), typeName, field)
        val writeCxt = SandboxEdits.parentCxt(cxt, cxt.client) ?: cxt
        val configId = GedraId.of(GedraConfigType.configDoc, writeCxt.client, declared.bundle.name)
        GedraConfigService.get(writeCxt).patchConfig(writeCxt, configId, trial = true) { slots ->
            val workflows = slots[CCT.workflowDef].orEmpty()
            val at = workflows.indexOfFirst { it[CCT.workflowId] == workflowId }
            if (at < 0) throw KdrException("No workflow '$workflowId' in '${declared.bundle.name}'.", code = EXC.notFound)
            val stored = (workflows[at][CCT.definition] as? Map<*, *>)?.toJsonMap().orEmpty()
            val current = parseWfDef(writeCxt, stored)
            if (workflowDefStamp(current) != basedOn) {
                throw KdrException(
                    "Workflow '$workflowId' has changed since this page was drawn; reload it and make the change again.",
                    code = EXC.conflict,
                )
            }
            val rewritten = withLayoutEntry(current.toJsonMap(), typeName, field, entry, inherited)
            slots + (CCT.workflowDef to workflows.mapIndexed { i, e -> if (i == at) e + (CCT.definition to rewritten) else e })
        }
        if (writeCxt !== cxt) {
            SandboxEdits.reloadParent(cxt, writeCxt.client)
        } else {
            ClientSyncService.get(cxt).announceReload(cxt, GedraConfigReload.reloadClient(cxt, cxt.client))
        }
        val reloaded = WorkflowService.get(cxt).forClient(cxt.client).workflow(workflowId)
        return reloaded?.def?.let { workflowDefStamp(it) } ?: ""
    }

    /**
     * One definition by its address, as the [DSV.definition] endpoint returns it: the address it resolved to, where
     * it was declared, and its **authored** entry -- the slot entry a stored configuration would hold, whatever the
     * definition's origin, so an inherited one reads in the same shape as the client's own.
     *
     * A `schemaDef` address naming a type a trait generates resolves to that trait's `traitDef` entry, the thing
     * actually authored; the returned address says so.
     */
    fun definition(cxt: KdrCxt, slot: String, key: String): Map<String, Any?> {
        val client = cxt.client
        val schema = SchemaService.get(cxt)
        fun notFound(): Nothing =
            throw KdrException("No $slot definition '$key' for client '$client'.", code = EXC.notFound)

        val (addr, config, entry) = when (slot) {
            CCT.traitDef -> {
                val config = schema.configOfTrait(client, key) ?: notFound()
                val trait = config.traits[key] ?: notFound()
                Triple(address(slot, key, null, config), config, traitToEntry(config, trait))
            }
            CCT.schemaDef -> {
                val config = schema.configOfType(client, key)
                val trait = config?.let { traitGenerating(it, key) }
                when {
                    trait != null ->
                        Triple(typeAddress(cxt, client, key), config, traitToEntry(config, trait))
                    config != null ->
                        Triple(address(slot, key, null, config), config, schemaEntry(key, config.defs[key] ?: notFound()))
                    else -> {
                        val body = schema.storeFor(client).defs[key] ?: notFound()
                        Triple(address(slot, key, null, null), null, schemaEntry(key, body))
                    }
                }
            }
            CCT.workflowDef -> {
                val declared = WorkflowService.get(cxt).forClient(client).workflow(key) ?: notFound()
                val entry = linkedMapOf(CCT.workflowId to key, CCT.definition to declared.def.toJsonMap())
                Triple(address(slot, key, null, declared.bundle), declared.bundle, entry)
            }
            else -> throw KdrException.mkInput(
                "Design View reads ${readableSlots.joinToString()} definitions; '$slot' is not one of them.",
            )
        }
        val out = LinkedHashMap(addr)
        out[DSV.entry] = entry
        // A stored definition's bundle: which revision it is, and whether that one is live for a published-only
        // client -- what the inspector says beside "editable". Only the client's own bundles are read, which is
        // what a stored definition's config always is outside a sandbox (whose rows are its parent's).
        if (config != null && config.isStored && config.gedraId.client == client) {
            GedraConfigService.get(cxt).readLatest(cxt, GedraId.of(GedraConfigType.configDoc, client, config.name))
                ?.let {
                    out[DSV.version] = it.version
                    out[DSV.published] = it.publishedAt != null
                }
        }
        return out
    }

    private fun schemaEntry(typeName: String, body: Any?): Map<String, Any?> =
        linkedMapOf(CCT.typeName to typeName, CCT.schema to body)

    /** The slots [definition] reads. The rest (cfacts, fragments, menus) come into Design View in later slices. */
    val readableSlots: List<String> = listOf(CCT.traitDef, CCT.schemaDef, CCT.workflowDef)
}

/**
 * The Design View endpoints (issue #972). In the `clientAdmin` section, and each opens with
 * [AdminRules.requireClientAdministrator], the same gate the configuration endpoints use: a definition's authored
 * form -- its conditions, its unchosen branches, its backend copy -- is what an ordinary user is never sent.
 */
fun designViewSchema(cxt: KdrCxt): SchModule = schemaModule(cxt, DSV.namespace) {
    type(DSV.definitionType) {
        type = SCT.kObject
        description = "One definition, by address: where it was declared, and its authored entry."
        property(DSV.slot, "The config slot the definition is an entry of.", required = true)
        property(DSV.key, "The entry's key in that slot -- a trait id, a type name, a workflow id.", required = true)
        property(DSV.path, "Where within the entry the addressed definition sits, when it is not the whole entry.")
        property(DSV.origin, "Where the definition was declared.", required = true) { options(DesignOrigin.entries) }
        property(DSV.config, "The configuration that declares it; absent for one declared in a component's code.")
        property(DSV.editable, "Whether it can be edited in place: only in the client's own stored configuration.", required = true) {
            type = SCT.boolean
        }
        property(DSV.entry, "The authored entry, as a stored configuration holds it.", required = true) { type = SCT.kObject }
        property(DSV.version, "For a stored definition: its bundle's latest revision.") { type = SCT.integer }
        property(DSV.published, "For a stored definition: whether that revision is published.") { type = SCT.boolean }
    }

    type(DSV.layoutEntryEditType) {
        type = SCT.kObject
        description = "The outcome of a layout-entry edit: the stamp of the definition as it now stands."
        property(DSV.basedOn, "The workflow definition's stamp after the edit -- what the next edit is based on.", required = true)
    }

    generalEndpoint(
        DSV.layoutEntryEdit,
        "Sets or clears a workflow's own layout entry for one field of a type it shows, and reloads the client.",
        HttpMethod.POST,
        outputRef = DSV.layoutEntryEditType,
        inputFields = {
            field(DSV.workflowId, "The workflow whose copy changes.", required = true)
            field(DSV.typeName, "The type that declares the field.", required = true)
            field(DSV.field, "The field whose layout entry is set or cleared.", required = true)
            field(DSV.entry, "The field's layout entry -- label, description, hint and the rest; absent to go back to the inherited one.") {
                type = SCT.kObject
            }
            field(DSV.basedOn, "The stamp of the definition the edit was made against, from the page's Design View block.", required = true)
        },
    ) { c, request ->
        AdminRules.requireClientAdministrator(c)
        val stamp = DesignView.setLayoutEntry(
            c, request.getReqNonBlankStr(DSV.workflowId), request.getReqNonBlankStr(DSV.typeName),
            request.getReqNonBlankStr(DSV.field), (request[DSV.entry] as? Map<*, *>)?.toJsonMap(),
            request.getReqNonBlankStr(DSV.basedOn),
        )
        linkedMapOf(DSV.basedOn to stamp)
    }

    itemEndpoint(
        DSV.definition,
        "Reads one definition by its Design View address: where it was declared, and its authored entry.",
        HttpMethod.GET,
        outputRef = DSV.definitionType,
        inputFields = {
            field(DSV.slot, "The config slot.", required = true) { for (s in DesignView.readableSlots) option(s) }
            field(DSV.key, "The entry's key in that slot.", required = true)
        },
    ) { c, request ->
        AdminRules.requireClientAdministrator(c)
        DesignView.definition(c, request.getReqNonBlankStr(DSV.slot), request.getReqNonBlankStr(DSV.key))
    }
}
