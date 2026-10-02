package com.dynamicruntime.common.gedra

import com.dynamicruntime.common.context.KdrCxt
import com.dynamicruntime.common.endpoint.HttpMethod
import com.dynamicruntime.common.endpoint.SchModule
import com.dynamicruntime.common.endpoint.schemaModule
import com.dynamicruntime.common.exception.EXC
import com.dynamicruntime.common.exception.KdrException
import com.dynamicruntime.common.gedra.workflow.WfDeclared
import com.dynamicruntime.common.gedra.workflow.WorkflowService
import com.dynamicruntime.common.gedra.workflow.toJsonMap
import com.dynamicruntime.common.schema.SCT
import com.dynamicruntime.common.startup.SchemaService
import com.dynamicruntime.common.user.AdminRules
import com.dynamicruntime.common.util.getReqNonBlankStr

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
    fun workflowBlock(cxt: KdrCxt, declared: WfDeclared, defs: Map<String, Any?>): Map<String, Any?> = linkedMapOf(
        DSV.workflow to address(CCT.workflowDef, declared.def.workflowId, null, declared.bundle),
        DSV.types to defs.keys.associateWith { typeAddress(cxt, cxt.client, it) },
    )

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
                    trait != null && config != null ->
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
