package com.dynamicruntime.common.gedra

import com.dynamicruntime.common.context.KdrCxt
import com.dynamicruntime.common.context.KdrSchemaStore
import com.dynamicruntime.common.endpoint.HttpMethod
import com.dynamicruntime.common.endpoint.InputFieldsBuilder
import com.dynamicruntime.common.endpoint.SchModule
import com.dynamicruntime.common.endpoint.schemaModule
import com.dynamicruntime.common.exception.EXC
import com.dynamicruntime.common.exception.KdrException
import com.dynamicruntime.common.gedra.workflow.WfDeclared
import com.dynamicruntime.common.gedra.workflow.WfDef
import com.dynamicruntime.common.gedra.workflow.WorkflowService
import com.dynamicruntime.common.gedra.workflow.headingBasisKey
import com.dynamicruntime.common.gedra.workflow.layoutEntryOf
import com.dynamicruntime.common.gedra.workflow.layoutHeadingOf
import com.dynamicruntime.common.gedra.workflow.parseWfDef
import com.dynamicruntime.common.gedra.workflow.shownFieldsOf
import com.dynamicruntime.common.gedra.workflow.withLayoutEntry
import com.dynamicruntime.common.gedra.workflow.withLayoutHeading
import com.dynamicruntime.common.gedra.workflow.withShownFields
import com.dynamicruntime.common.gedra.workflow.withWorkflowLabel
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
    fun address(slot: String, key: String, path: String?, config: GedraConfig?): MutableMap<String, Any?> {
        val out = linkedMapOf<String, Any?>(DSV.slot to slot, DSV.key to key)
        path?.let { out[DSV.path] = it }
        out.putAll(layer(config))
        return out
    }

    /** Where [config] puts a definition, as `{ origin, config }`: an address's own, or its [DSV.alteredBy]. */
    private fun layer(config: GedraConfig?): Map<String, Any?> = buildMap {
        put(DSV.origin, originOf(config).name)
        config?.let { put(DSV.config, it.name) }
    }

    /** The config that declares a type, and the client config that alters it, when one does (see [typeLayers]). */
    internal class TypeLayers(val declaredBy: GedraConfig?, val alteredBy: GedraConfig?)

    /**
     * Where [typeName] comes from for [client] (issue #1013). A type the global document holds is **declared**
     * there -- by a global config, or by a component's code, which no config holds -- and a client config
     * contributing the same name **alters** it. Any other type is the client's own, declared by its config. Within a
     * client a later declaration replaces an earlier one, so there is at most one alteration.
     */
    internal fun typeLayers(cxt: KdrCxt, client: String, typeName: String): TypeLayers {
        val schema = SchemaService.get(cxt)
        val own = schema.clientConfigOfType(client, typeName)
        return if (typeName in schema.schemaStore.defs) {
            TypeLayers(schema.globalConfigOfType(typeName), own)
        } else {
            TypeLayers(own, null)
        }
    }

    /**
     * The address of the definition that declares the schema type [typeName] as [client] sees it.
     *
     * A trait's generated types -- its entry type and its inline data type -- belong to the **trait's** declaration
     * (the `traitDef` entry, where the data type is written under `dataSchema`), since that is what an author
     * edits. Any other type is a `schemaDef` entry of its own, its body under `schema`. A type no config declares
     * (one from a component's code) is global, with no config to name. The address names where the type is
     * **declared**; a client config altering a shared type rides beside it as [DSV.alteredBy] (issue #1013).
     */
    fun typeAddress(cxt: KdrCxt, client: String, typeName: String): Map<String, Any?> {
        val layers = typeLayers(cxt, client, typeName)
        val config = layers.declaredBy
        val trait = config?.let { traitGenerating(it, typeName) }
        val out = when {
            trait == null -> address(CCT.schemaDef, typeName, CCT.schema, config)
            trait.typeName == typeName -> address(CCT.traitDef, trait.traitId, null, config)
            else -> address(CCT.traitDef, trait.traitId, CCT.dataSchema, config)
        }
        layers.alteredBy?.let { out[DSV.alteredBy] = layer(it) }
        return out
    }

    /** The trait of [config] that generates [typeName] -- as its entry type or its inline data type -- or null. */
    internal fun traitGenerating(config: GedraConfig, typeName: String): GedraTrait? =
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
        if (refusal != null) {
            out[DSV.editRefusal] = refusal.message
            out[DSV.editRefusalCode] = refusal.code.name
        }
        val edits = layoutEdits(declared, clientStore, store)
        if (edits.isNotEmpty()) out[DSV.layoutEdits] = edits
        val headings = headingEdits(declared, clientStore, store)
        if (headings.isNotEmpty()) out[DSV.headingEdits] = headings
        // The types whose fields the workflow's own form chooses (issue #1071).
        val shown = declared.def.typeAlterations.filterKeys { it in store.defs }
            .mapNotNull { (typeName, alteration) -> shownFieldsOf(alteration)?.let { typeName to it } }.toMap()
        if (shown.isNotEmpty()) out[DSV.shownFields] = shown
        return out
    }

    /**
     * The headings [declared] sets, by type name (issue #1070): each the workflow's [DSV.label], the client's heading it
     * replaces ([DSV.inherited], absent when there is none), and whether that has changed since the override was made.
     * Only for types [store] still carries.
     */
    private fun headingEdits(declared: WfDeclared, clientStore: KdrSchemaStore, store: KdrSchemaStore): Map<String, Any?> {
        val out = linkedMapOf<String, Any?>()
        for ((typeName, alteration) in declared.def.typeAlterations) {
            if (typeName !in store.defs) continue
            val label = (alteration[SCH.layout] as? Map<*, *>)?.get(SL.label).toOptStr() ?: continue
            val inherited = layoutHeadingOf(clientStore.defs, typeName)
            // A basis records the heading an override replaced, `{}` when there was none.
            val made = declared.def.typeBasis[typeName]?.get(headingBasisKey) as? Map<*, *>
            out[typeName] = linkedMapOf<String, Any?>(DSV.label to label).also { h ->
                inherited?.let { h[DSV.inherited] = it }
                h[DSV.inheritedChanged] = made != null && made[SL.label].toOptStr() != inherited
            }
        }
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
     * pulls from a fragment file is the client's shared wording to change (#1010, [DesignPulledCopy]). Then the
     * rules every Design View save shares with the Clients page's editors ([saveRefusal]).
     */
    fun editRefusal(cxt: KdrCxt, declared: WfDeclared): EditRefusal? {
        val bundle = declared.bundle
        if (!bundle.isStored || bundle.gedraId.client != cxt.client) {
            return EditRefusal(
                DesignRefusal.declaredInSource,
                "Workflow '${declared.def.workflowId}' is declared in source, not in this client's stored configuration, so copy " +
                    "just for this workflow cannot be saved here yet. Copy it pulls from a fragment file can be changed here " +
                    "as this client's shared wording, for every workflow that uses it.",
            )
        }
        return saveRefusal(cxt, bundle.name)
    }

    /**
     * Why a Design View save into the client's stored config [configName] may not be made, or null when it may --
     * the rule the Clients page's editors follow ([ClientStoredEdit], issues #930, #1026), so the two kinds of editor
     * never disagree about a client:
     *
     * - **A client with a sandbox** runs its published configuration and previews edits in the sandbox, so its own
     *   page refuses and points there; in the sandbox a save is a draft of the parent's configuration.
     * - **A client without one** -- published-only by an administrator's choice included -- has a save published and
     *   live at once. So a config carrying somebody's unpublished changes is refused, since publishing would take them
     *   live too.
     */
    internal fun saveRefusal(cxt: KdrCxt, configName: String): EditRefusal? {
        if (isSandboxClient(cxt.client)) return null
        val configs = GedraConfigService.get(cxt)
        if (configs.publishedOnly(cxt, cxt.client) && configs.asksForSandbox(cxt, cxt.client)) {
            return EditRefusal(
                DesignRefusal.publishedOnly,
                "Client '${cxt.client}' runs its published configuration and previews changes in its sandbox: edit it from there.",
            )
        }
        val holder = configs.readLatest(cxt, GedraId.of(GedraConfigType.configDoc, cxt.client, configName))
        if (holder != null && !holder.isPublished && configName != CPY.copyConfigName) {
            return EditRefusal(
                DesignRefusal.unpublishedChanges,
                "Configuration '$configName' has unpublished changes, which a save here would publish with it. Publish or " +
                    "revert it first, on the client's page.",
            )
        }
        return null
    }

    /**
     * Makes a Design View save (issues #984, #1029): [patch] applied to the client's stored config [configName], where
     * and as the Clients page's editors save ([ClientStoredEdit]) -- in a sandbox's parent's config as a draft its
     * sandbox shows, otherwise published and live at once, with a publish the trial refuses undone. Refused first by
     * [saveRefusal]'s rules. Trial-checked like every config write, and reloaded and announced. With [impact] at
     * [ImpactGate.refuse], a live save that would affect the client's stored data is refused with the impact report
     * and undone (issue #1040).
     */
    internal fun saveEdit(
        cxt: KdrCxt,
        configName: String,
        impact: ImpactGate = ImpactGate.unchecked,
        patch: (Map<String, List<Map<String, Any?>>>) -> Map<String, List<Map<String, Any?>>>,
    ) {
        saveRefusal(cxt, configName)?.let { throw KdrException.mkInput(it.message) }
        val target = ClientStoredEdit.target(cxt, cxt.client, "designEdit")
        val svc = GedraConfigService.get(target.bound)
        val configId = GedraId.of(GedraConfigType.configDoc, target.client, configName)
        val holder = svc.readLatest(target.bound, configId)
            ?: throw KdrException("No configuration '$configName' for client '${target.client}'.", code = EXC.notFound)
        var before: Map<String, List<Map<String, Any?>>> = emptyMap()
        val written = svc.patchConfig(target.bound, configId, trial = true) { slots ->
            before = slots
            patch(slots)
        }
        ClientStoredEdit.takeEffect(
            cxt, target, written,
            undo = { svc.patchConfig(target.bound, configId) { before } },
            restorePublished = holder.isPublished,
            impact = impact,
        )
    }

    /**
     * Sets [field]'s layout entry for [typeName] in workflow [workflowId] to [entry], or removes it when [entry] is
     * null -- the workflow's own wording over the inherited copy (issue #984) -- as every Design View save is made
     * ([saveEdit]), and refused when the definition has changed since [basedOn]. Returns the new stamp, for the page's
     * next edit.
     */
    fun setLayoutEntry(
        cxt: KdrCxt,
        workflowId: String,
        typeName: String,
        field: String,
        entry: Map<String, Any?>?,
        basedOn: String,
    ): String {
        val inherited = layoutEntryOf(cxt.getClientSchema(), typeName, field)
        return editWorkflowDef(cxt, workflowId, basedOn) { withLayoutEntry(it, typeName, field, entry, inherited) }
    }

    /**
     * Sets a label workflow [workflowId] owns to [label] (issue #1070): its own when [taskId] is null, else that
     * task's, or -- with [saveId] -- that save's ([withWorkflowLabel]). Edited in the definition in place, as every
     * Design View save is made, and refused when the definition has changed since [basedOn]. A label that pulls a
     * fragment key is the client's shared wording, edited at the key instead (#1010); this replaces whatever the label
     * says. Returns the new stamp.
     */
    fun setLabel(cxt: KdrCxt, workflowId: String, taskId: String?, saveId: String?, label: String?, basedOn: String): String =
        editWorkflowDef(cxt, workflowId, basedOn) { withWorkflowLabel(it, taskId, saveId, label) }

    /**
     * Sets workflow [workflowId]'s own heading for [typeName] to [label], or removes it when [label] is null -- back to
     * the shared heading (issue #1070) -- as a layout alteration of the type ([withLayoutHeading]), recording the
     * client's heading it replaces. Refused when the definition has changed since [basedOn]. Returns the new stamp.
     */
    fun setHeading(cxt: KdrCxt, workflowId: String, typeName: String, label: String?, basedOn: String): String {
        val inherited = layoutHeadingOf(cxt.getClientSchema().defs, typeName)
        return editWorkflowDef(cxt, workflowId, basedOn) { withLayoutHeading(it, typeName, label, inherited) }
    }

    /**
     * Sets the fields workflow [workflowId]'s form shows for [typeName] to [fields], in order, or removes its choice when
     * [fields] is null (issue #1071) -- its layout alteration of the type, `authoritative` ([withShownFields]). Its edit
     * save then writes only those fields. A list that omits a field the type may require is refused by the trial, as
     * the load check refuses one. Refused when the definition has changed since [basedOn]. Returns the new stamp.
     */
    fun setShownFields(cxt: KdrCxt, workflowId: String, typeName: String, fields: List<String>?, basedOn: String): String {
        if (fields != null && fields.isEmpty()) {
            throw KdrException.mkInput("A form shows at least one field; to show them all, stop choosing them.")
        }
        return editWorkflowDef(cxt, workflowId, basedOn) { withShownFields(it, typeName, fields) }
    }

    /**
     * Rewrites workflow [workflowId]'s stored definition with [rewrite] -- given its JSON form, returning the new one --
     * as every Design View save is made ([saveEdit]): refused first by [editRefusal], and under the write lock when the
     * stored definition has changed since [basedOn] (409). Returns the new stamp, for the page's next edit.
     */
    private fun editWorkflowDef(
        cxt: KdrCxt,
        workflowId: String,
        basedOn: String,
        rewrite: (Map<String, Any?>) -> Map<String, Any?>,
    ): String {
        val declared = WorkflowService.get(cxt).forClient(cxt.client).workflow(workflowId)
            ?: throw KdrException("No workflow '$workflowId' for client '${cxt.client}'.", code = EXC.notFound)
        editRefusal(cxt, declared)?.let { throw KdrException.mkInput(it.message) }
        saveEdit(cxt, declared.bundle.name) { slots ->
            val workflows = slots[CCT.workflowDef].orEmpty()
            val at = workflows.indexOfFirst { it[CCT.workflowId] == workflowId }
            if (at < 0) throw KdrException("No workflow '$workflowId' in '${declared.bundle.name}'.", code = EXC.notFound)
            val stored = (workflows[at][CCT.definition] as? Map<*, *>)?.toJsonMap().orEmpty()
            val current = parseWfDef(cxt, stored)
            if (workflowDefStamp(current) != basedOn) {
                throw KdrException(
                    "Workflow '$workflowId' has changed since this page was drawn; reload it and make the change again.",
                    code = EXC.conflict,
                )
            }
            val rewritten = rewrite(current.toJsonMap())
            slots + (CCT.workflowDef to workflows.mapIndexed { i, e -> if (i == at) e + (CCT.definition to rewritten) else e })
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
     * actually authored; the returned address says so. When a client config alters the shared type -- a global
     * type, or a global trait's generated type -- the alteration's own authored entry rides beside the declaration's
     * under [DSV.alteredBy] (issue #1013), so every layer behind what the page draws can be read.
     */
    fun definition(cxt: KdrCxt, slot: String, key: String): Map<String, Any?> {
        val client = cxt.client
        val schema = SchemaService.get(cxt)
        fun notFound(): Nothing =
            throw KdrException("No $slot definition '$key' for client '$client'.", code = EXC.notFound)

        // The client config altering a shared type, with the type's name, when one does.
        fun alterationOf(vararg typeNames: String?): Pair<String, GedraConfig>? = typeNames.filterNotNull()
            .firstNotNullOfOrNull { t -> typeLayers(cxt, client, t).alteredBy?.let { t to it } }

        val read = when (slot) {
            CCT.traitDef -> {
                val config = schema.configOfTrait(client, key) ?: notFound()
                val trait = config.traits[key] ?: notFound()
                Read(
                    address(slot, key, null, config), config, traitToEntry(config, trait),
                    alterationOf(trait.typeName, inlineDataTypeName(config, trait)),
                    setOfNotNull(trait.typeName, inlineDataTypeName(config, trait)),
                )
            }
            CCT.schemaDef -> {
                val layers = typeLayers(cxt, client, key)
                val config = layers.declaredBy
                val trait = config?.let { traitGenerating(it, key) }
                val altered = layers.alteredBy?.let { key to it }
                when {
                    trait != null -> Read(
                        typeAddress(cxt, client, key), config, traitToEntry(config, trait), altered,
                        setOfNotNull(trait.typeName, inlineDataTypeName(config, trait)),
                    )
                    config != null ->
                        Read(address(slot, key, null, config), config, schemaEntry(key, config.defs[key] ?: notFound()), altered, setOf(key))
                    else -> {
                        // Declared in a component's code: its body is the global document's, before any alteration.
                        val body = schema.schemaStore.defs[key] ?: schema.storeFor(client).defs[key] ?: notFound()
                        Read(address(slot, key, null, null), null, schemaEntry(key, body), altered, setOf(key))
                    }
                }
            }
            CCT.workflowDef -> {
                val declared = WorkflowService.get(cxt).forClient(client).workflow(key) ?: notFound()
                val entry = linkedMapOf(CCT.workflowId to key, CCT.definition to declared.def.toJsonMap())
                Read(address(slot, key, null, declared.bundle), declared.bundle, entry, null, emptySet(), declared.def)
            }
            else -> throw KdrException.mkInput(
                "Design View reads ${readableSlots.joinToString()} definitions; '$slot' is not one of them.",
            )
        }
        val out = LinkedHashMap(read.address)
        out[DSV.entry] = read.entry
        // The shared editor's facts (issue #1029), for a definition that gives the page types: where it is used, which
        // workflows override which fields, and whether it may be edited here. Keyed by the declaration's own entry.
        if (read.typeNames.isNotEmpty()) {
            out.putAll(
                DesignSharedEdit.facts(
                    cxt, read.typeNames, read.config, read.address[DSV.slot] as String, read.address[DSV.key] as String,
                ),
            )
        }
        // The copy the client's layout pulls from fragment files (issue #1010) -- a field's copy, a type's heading, a
        // workflow's labels (#1070): editable as the client's shared wording, whatever the definition's origin.
        val pulled = linkedMapOf<String, Any?>()
        if (read.typeNames.isNotEmpty()) {
            DesignPulledCopy.facts(cxt, read.typeNames).takeIf { it.isNotEmpty() }?.let { pulled[DSV.pulledCopy] = it }
            DesignPulledCopy.headingFacts(cxt, read.typeNames).takeIf { it.isNotEmpty() }?.let { pulled[DSV.pulledHeadings] = it }
        }
        read.workflow?.let { def ->
            DesignPulledCopy.labelFacts(cxt, def).takeIf { it.isNotEmpty() }?.let { pulled[DSV.pulledLabels] = it }
        }
        if (pulled.isNotEmpty()) {
            out.putAll(pulled)
            // The rule every Design View save keeps (#1026): a client with a sandbox is changed from its sandbox,
            // where the draft shows. A copy edit lands in the editors' own config, so no foreign draft is at stake.
            saveRefusal(cxt, CPY.copyConfigName)?.let {
                out[DSV.sharedWordingRefusal] = it.message
                out[DSV.sharedWordingRefusalCode] = it.code.name
            }
        }
        read.altered?.let { (typeName, alteration) ->
            out[DSV.alteredBy] = layer(alteration) + linkedMapOf(
                DSV.slot to CCT.schemaDef,
                DSV.key to typeName,
                DSV.entry to schemaEntry(typeName, alteration.defs[typeName]),
            )
        }
        // The client's own stored bundle among the layers -- the declaration's or the alteration's: which revision it
        // is, and whether that one is live for a published-only client. Only the client's own bundles are read, which
        // is what a stored definition's config always is outside a sandbox (whose rows are its parent's).
        val stored = listOfNotNull(read.config, read.altered?.second)
            .firstOrNull { it.isStored && it.gedraId.client == client }
        if (stored != null) {
            GedraConfigService.get(cxt).readLatest(cxt, GedraId.of(GedraConfigType.configDoc, client, stored.name))
                ?.let {
                    out[DSV.version] = it.version
                    out[DSV.published] = it.publishedAt != null
                }
        }
        return out
    }

    /** What [definition] found: the address, the declaring config, its entry, and the client's alteration if any. */
    private class Read(
        val address: Map<String, Any?>,
        val config: GedraConfig?,
        val entry: Map<String, Any?>,
        val altered: Pair<String, GedraConfig>?,
        /** The types this definition gives the page -- a trait's entry and data types, or a schema type -- if any. */
        val typeNames: Set<String>,
        /** For a workflow's read, its definition: the labels it owns, which may pull shared wording (issue #1070). */
        val workflow: WfDef? = null,
    )

    private fun schemaEntry(typeName: String, body: Any?): Map<String, Any?> =
        linkedMapOf(CCT.typeName to typeName, CCT.schema to body)

    /** The slots [definition] reads. The rest (cfacts, fragments, menus) come into Design View in later slices. */
    val readableSlots: List<String> = listOf(CCT.traitDef, CCT.schemaDef, CCT.workflowDef)
}

/** Why Design View offers no edit of a workflow's copy (issue #1013): the [code], and the sentence the page shows. */
class EditRefusal(val code: DesignRefusal, val message: String)

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
        property(
            DSV.alteredBy,
            "The client's own configuration altering this shared definition, when one does: its origin, config, the type it alters and that alteration's authored entry.",
        ) { type = SCT.kObject }
        property(DSV.entry, "The authored entry, as a stored configuration holds it.", required = true) { type = SCT.kObject }
        property(DSV.version, "For a stored definition: its bundle's latest revision.") { type = SCT.integer }
        property(DSV.published, "For a stored definition: whether that revision is published.") { type = SCT.boolean }
        // The shared editor (issue #1029), for a trait or type.
        property(DSV.usedBy, "The client's workflows whose pages show this definition, each {workflowId, label}.") {
            type = SCT.array
            items { type = SCT.kObject }
        }
        property(DSV.variantFields, "By field, the workflows that override its copy with a variant of their own.") {
            type = SCT.kObject
        }
        property(DSV.canEditShared, "Whether this definition may be edited here, for every workflow.") { type = SCT.boolean }
        property(DSV.sharedRefusal, "Why it may not be, when it may not.")
        property(DSV.sharedRefusalCode, "Which of the closed set of reasons that is.") { options(DesignRefusal.entries) }
        property(DSV.sharedBasedOn, "A stamp of the stored entry, sent back with a shared edit.")
        property(DSV.sharedCopyRefusals, "By field, why it cannot be given shared copy; a field not named here can.") {
            type = SCT.kObject
        }
        property(
            DSV.pulledCopy,
            "By type, field and copy slot, the fragment keys the client's layout pulls, with their wording -- what the " +
                "client may change as shared wording.",
        ) { type = SCT.kObject }
        property(
            DSV.pulledHeadings,
            "By type, the heading of the client's layout when it pulls a fragment key, with its wording (issue #1070).",
        ) { type = SCT.kObject }
        property(
            DSV.pulledLabels,
            "For a workflow: its own, its tasks' and its saves' labels that pull a fragment key, with their wording (issue #1070).",
        ) { type = SCT.kObject }
        property(DSV.sharedWordingRefusal, "Why the shared wording may not be changed from here, when it may not.")
        property(DSV.sharedWordingRefusalCode, "Which of the closed set of reasons that is.") { options(DesignRefusal.entries) }
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
            designClientField()
        },
    ) { c, request ->
        AdminRules.requireClientAdministrator(c)
        val stamp = DesignView.setLayoutEntry(
            designCxt(c, request), request.getReqNonBlankStr(DSV.workflowId), request.getReqNonBlankStr(DSV.typeName),
            request.getReqNonBlankStr(DSV.field), (request[DSV.entry] as? Map<*, *>)?.toJsonMap(),
            request.getReqNonBlankStr(DSV.basedOn),
        )
        linkedMapOf(DSV.basedOn to stamp)
    }

    type(DSV.sharedFieldEditType) {
        type = SCT.kObject
        description = "The outcome of a shared-field edit: the stamp of the definition's entry as it now stands."
        property(DSV.sharedBasedOn, "The entry's stamp after the edit -- what the next shared edit is based on.", required = true)
    }

    generalEndpoint(
        DSV.sharedFieldEdit,
        "Sets a field's copy and choices in the definition the client declares, for every workflow on the client.",
        HttpMethod.POST,
        outputRef = DSV.sharedFieldEditType,
        inputFields = {
            field(DSV.typeName, "The type that declares the field.", required = true)
            field(DSV.field, "The field to edit.", required = true)
            field(DSV.entry, "The field's layout entry -- label, description, hint and the rest; absent to leave the copy as it is.") {
                type = SCT.kObject
            }
            field(
                DSV.options,
                "The field's choices as they should stand, each {value, label}: relabeled, added or removed. A value " +
                    "left out is removed, which the client's stored forms are checked against first. Absent to leave " +
                    "them as they are.",
            ) {
                type = SCT.array
                items { type = SCT.kObject }
            }
            field(DSV.sharedBasedOn, "The stamp of the entry the edit was made against, from the definition read.", required = true)
            field(
                IMP.acknowledgeImpact,
                "Save a removal of choices although stored forms hold them (issue #1040); without it, such a save is " +
                    "refused with the impact report.",
            ) { type = SCT.boolean }
            designClientField()
        },
    ) { c, request ->
        AdminRules.requireClientAdministrator(c)
        val stamp = DesignSharedEdit.setSharedField(
            designCxt(c, request), request.getReqNonBlankStr(DSV.typeName), request.getReqNonBlankStr(DSV.field),
            (request[DSV.entry] as? Map<*, *>)?.toJsonMap(),
            (request[DSV.options] as? List<*>)?.map { (it as? Map<*, *>)?.toJsonMap().orEmpty() },
            request.getReqNonBlankStr(DSV.sharedBasedOn),
            acknowledgeImpact = request[IMP.acknowledgeImpact] == true,
        )
        linkedMapOf(DSV.sharedBasedOn to stamp)
    }

    type(DSV.labelEditType) {
        type = SCT.kObject
        description = "The outcome of an edit of a workflow's own copy: the stamp of its definition as it now stands."
        property(DSV.basedOn, "The workflow definition's stamp after the edit -- what the next edit is based on.", required = true)
    }

    generalEndpoint(
        DSV.labelEdit,
        "Sets a label a workflow owns -- its own (the page title), a task's or a save's -- in its stored definition, and reloads the client.",
        HttpMethod.POST,
        outputRef = DSV.labelEditType,
        inputFields = {
            field(DSV.workflowId, "The workflow whose label changes.", required = true)
            field(DSV.taskId, "The task whose label changes, or whose save's does; absent for the workflow's own.")
            field(DSV.saveId, "The save, within the task, whose label changes.")
            field(DSV.label, "The label, a template like any workflow label; absent or blank clears the workflow's own, which a task's and a save's cannot be.")
            field(DSV.basedOn, "The stamp of the definition the edit was made against, from the page's Design View block.", required = true)
            designClientField()
        },
    ) { c, request ->
        AdminRules.requireClientAdministrator(c)
        val stamp = DesignView.setLabel(
            designCxt(c, request), request.getReqNonBlankStr(DSV.workflowId), request[DSV.taskId].toOptStr(),
            request[DSV.saveId].toOptStr(), request[DSV.label].toOptStr(), request.getReqNonBlankStr(DSV.basedOn),
        )
        linkedMapOf(DSV.basedOn to stamp)
    }

    generalEndpoint(
        DSV.headingEdit,
        "Sets or clears a workflow's own heading for a type its pages draw, and reloads the client.",
        HttpMethod.POST,
        outputRef = DSV.labelEditType,
        inputFields = {
            field(DSV.workflowId, "The workflow whose heading changes.", required = true)
            field(DSV.typeName, "The type whose heading it is -- a trait's data type, as the page draws it.", required = true)
            field(DSV.label, "The heading; absent or blank to go back to the shared one.")
            field(DSV.basedOn, "The stamp of the definition the edit was made against, from the page's Design View block.", required = true)
            designClientField()
        },
    ) { c, request ->
        AdminRules.requireClientAdministrator(c)
        val stamp = DesignView.setHeading(
            designCxt(c, request), request.getReqNonBlankStr(DSV.workflowId), request.getReqNonBlankStr(DSV.typeName),
            request[DSV.label].toOptStr(), request.getReqNonBlankStr(DSV.basedOn),
        )
        linkedMapOf(DSV.basedOn to stamp)
    }

    generalEndpoint(
        DSV.shownFieldsEdit,
        "Sets which fields a workflow's form shows for a type, in order -- and so which its save writes -- or stops choosing them, and reloads the client.",
        HttpMethod.POST,
        outputRef = DSV.labelEditType,
        inputFields = {
            field(DSV.workflowId, "The workflow whose form it is.", required = true)
            field(DSV.typeName, "The type whose fields are chosen -- a trait's data type, as the page draws it.", required = true)
            field(DSV.fields, "The fields the form shows, in order; absent to stop choosing, so the form shows what the shared layout does.") {
                type = SCT.array
                items { type = SCT.string }
            }
            field(DSV.basedOn, "The stamp of the definition the edit was made against, from the page's Design View block.", required = true)
            designClientField()
        },
    ) { c, request ->
        AdminRules.requireClientAdministrator(c)
        val stamp = DesignView.setShownFields(
            designCxt(c, request), request.getReqNonBlankStr(DSV.workflowId), request.getReqNonBlankStr(DSV.typeName),
            (request[DSV.fields] as? List<*>)?.mapNotNull { it.toOptStr() }, request.getReqNonBlankStr(DSV.basedOn),
        )
        linkedMapOf(DSV.basedOn to stamp)
    }

    generalEndpoint(
        DSV.sharedHeadingEdit,
        "Sets or removes a type's heading in the definition the client declares, for every workflow on the client.",
        HttpMethod.POST,
        outputRef = DSV.sharedFieldEditType,
        inputFields = {
            field(DSV.typeName, "The type whose heading it is.", required = true)
            field(DSV.label, "The heading; absent or blank to remove it, so forms show the type's own title.")
            field(DSV.sharedBasedOn, "The stamp of the entry the edit was made against, from the definition read.", required = true)
            designClientField()
        },
    ) { c, request ->
        AdminRules.requireClientAdministrator(c)
        val stamp = DesignSharedEdit.setSharedHeading(
            designCxt(c, request), request.getReqNonBlankStr(DSV.typeName), request[DSV.label].toOptStr(),
            request.getReqNonBlankStr(DSV.sharedBasedOn),
        )
        linkedMapOf(DSV.sharedBasedOn to stamp)
    }

    itemEndpoint(
        DSV.definition,
        "Reads one definition by its Design View address: where it was declared, and its authored entry.",
        HttpMethod.GET,
        outputRef = DSV.definitionType,
        inputFields = {
            field(DSV.slot, "The config slot.", required = true) { for (s in DesignView.readableSlots) option(s) }
            field(DSV.key, "The entry's key in that slot.", required = true)
            designClientField()
        },
    ) { c, request ->
        AdminRules.requireClientAdministrator(c)
        DesignView.definition(designCxt(c, request), request.getReqNonBlankStr(DSV.slot), request.getReqNonBlankStr(DSV.key))
    }
}

/** The Design View endpoints' [DSV.client] input: the client whose form the page draws. */
private fun InputFieldsBuilder.designClientField() =
    overseenClientField(DSV.client, "The client whose form the page draws; the caller's own when absent.")

/**
 * The context a Design View request acts in: the caller's, or -- when it names [DSV.client] -- one bound to that
 * client, which only an administrator who may see every client may name ([overseenClient]). The page names it when
 * such an administrator has opened another client's form, since its workflows and definitions are that client's,
 * not the caller's own (`hub`, usually).
 */
private fun designCxt(c: KdrCxt, request: Map<String, Any?>): KdrCxt {
    val named = request[DSV.client].toOptStr() ?: return c
    val client = overseenClient(c, named)
    return if (client == c.client) c else c.mkSubContext("design", client)
}
