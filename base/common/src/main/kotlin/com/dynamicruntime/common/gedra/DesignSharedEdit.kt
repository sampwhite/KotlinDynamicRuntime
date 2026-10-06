package com.dynamicruntime.common.gedra

import com.dynamicruntime.common.context.KdrCxt
import com.dynamicruntime.common.exception.EXC
import com.dynamicruntime.common.exception.KdrException
import com.dynamicruntime.common.gedra.workflow.WorkflowService
import com.dynamicruntime.common.schema.SCH
import com.dynamicruntime.common.schema.SL
import com.dynamicruntime.common.schema.collectDefClosure
import com.dynamicruntime.common.schema.refName
import com.dynamicruntime.common.startup.SchemaService
import com.dynamicruntime.common.util.crc32Hex
import com.dynamicruntime.common.util.toJsonMapOrEmpty
import com.dynamicruntime.common.util.toJsonStr
import com.dynamicruntime.common.util.toOptStr

/**
 * Design View's **shared editor** (issue #1029): editing a field's definition for every workflow on the client, as
 * opposed to a workflow's own variant (#984). It is the *shared* half of the two-level model (#1013), and it edits
 * only a definition the client **declares in its own stored configuration** -- never one declared globally, in
 * source, or a client's alteration of a shared type (#1011).
 *
 * What it changes: a field's layout copy in its type's own `g-layout`, and its choices -- relabeling one, or adding
 * one. Adding a choice is a widening, which is why it belongs here and never in a workflow variant. Removing a
 * choice, or changing a choice's value, is refused: stored forms may hold the old value, which needs the impact
 * report of #935 first.
 */
object DesignSharedEdit {
    /**
     * The definition read's shared-editor facts for a definition declared by [declaredBy] that gives the page the
     * types [typeNames]: where it is used, which workflows override which of its fields, whether it may be edited here
     * (and why not), and the stamp an edit is based on.
     */
    fun facts(cxt: KdrCxt, typeNames: Set<String>, declaredBy: GedraConfig?, slot: String, key: String): Map<String, Any?> {
        val out = linkedMapOf<String, Any?>(
            DSV.usedBy to whereUsed(cxt, typeNames),
            DSV.variantFields to variantFields(cxt, typeNames),
        )
        val refusal = refusal(cxt, declaredBy)
        out[DSV.canEditShared] = refusal == null
        if (refusal != null) {
            out[DSV.sharedRefusal] = refusal.message
            out[DSV.sharedRefusalCode] = refusal.code.name
        } else {
            // Read where the configuration lives: a sandbox's rows are its parent's (issue #930).
            val readCxt = SandboxEdits.parentCxt(cxt, cxt.client) ?: cxt
            storedEntry(readCxt, declaredBy!!, slot, key)?.let { out[DSV.sharedBasedOn] = stampOf(it) }
        }
        return out
    }

    /**
     * Why this caller may not edit the definition [declaredBy] declares, or null when they may: it must be declared in
     * the client's own stored configuration, and the client must run its latest revision (as for #984's edit).
     */
    fun refusal(cxt: KdrCxt, declaredBy: GedraConfig?): EditRefusal? {
        if (declaredBy == null || declaredBy.gedraId.client == GID.globalClient) {
            val where = declaredBy?.let { " (${it.name})" }.orEmpty()
            return EditRefusal(
                DesignRefusal.declaredGlobally,
                "This definition is declared globally$where, for every client, so it is not this client's to edit here.",
            )
        }
        if (!declaredBy.isStored || declaredBy.gedraId.client != cxt.client) {
            return EditRefusal(
                DesignRefusal.declaredInSource,
                "This definition is declared in source (${declaredBy.name}), not in this client's stored configuration, " +
                    "so it cannot be edited here.",
            )
        }
        return DesignView.publishedOnlyRefusal(cxt)
    }

    /**
     * The client's workflows whose pages show any of [typeNames] -- reached from the traits their tasks collect,
     * through every `$ref` -- each as `{ workflowId, label }`.
     */
    fun whereUsed(cxt: KdrCxt, typeNames: Set<String>): List<Map<String, Any?>> {
        val schema = SchemaService.get(cxt)
        val traits = schema.gedraTraitsFor(cxt.client).associateBy { it.traitId }
        val store = schema.storeFor(cxt.client)
        return WorkflowService.get(cxt).forClient(cxt.client).workflows.values.filter { declared ->
            val seeds = declared.def.tasks.flatMap { it.traits }.flatMap { ref ->
                val trait = traits[ref.traitId] ?: return@flatMap emptyList()
                listOfNotNull(trait.typeName, trait.dataSchema?.get(SCH.dRef).toOptStr()?.let { refName(it) })
            }
            collectDefClosure(seeds, store.defs).keys.any { it in typeNames }
        }.map { declared ->
            // A label that pulls fragment copy reads as the id rather than as its template.
            val label = declared.def.label.takeIf { it.isNotBlank() && "%{" !in it } ?: declared.def.workflowId
            linkedMapOf(DSV.workflowId to declared.def.workflowId, DSV.label to label)
        }
    }

    /** By field of any of [typeNames], the client's workflows whose own variant (#984) sets that field's copy. */
    fun variantFields(cxt: KdrCxt, typeNames: Set<String>): Map<String, List<String>> {
        val out = linkedMapOf<String, MutableList<String>>()
        for (declared in WorkflowService.get(cxt).forClient(cxt.client).workflows.values) {
            for ((typeName, alteration) in declared.def.typeAlterations) {
                if (typeName !in typeNames) continue
                val fields = (alteration[SCH.layout] as? Map<*, *>)?.get(SL.schemaFields) as? List<*>
                for (entry in fields.orEmpty()) {
                    val field = (entry as? Map<*, *>)?.get(SL.field).toOptStr() ?: continue
                    out.getOrPut(field) { mutableListOf() }.add(declared.def.workflowId)
                }
            }
        }
        return out
    }

    /** A stamp of a stored slot entry: what a shared edit is based on, compared under the write lock. */
    fun stampOf(entry: Map<String, Any?>): String = entry.toJsonStr(compact = true).crc32Hex()

    /** The stored entry for [key] in [slot] of [config]'s latest revision, or null. */
    private fun storedEntry(cxt: KdrCxt, config: GedraConfig, slot: String, key: String): Map<String, Any?>? {
        val row = GedraConfigService.get(cxt).readLatest(cxt, GedraId.of(GedraConfigType.configDoc, cxt.client, config.name))
            ?: return null
        return row.entriesBySlot()[slot].orEmpty().firstOrNull { it[keyField(slot)] == key }
    }

    /** What a slot's entries are keyed by: a trait's id, or a schema type's qualified name. */
    private fun keyField(slot: String): String = if (slot == CCT.traitDef) CCT.traitId else CCT.typeName

    /** Where a slot entry holds the type's body. */
    private fun bodyField(slot: String): String = if (slot == CCT.traitDef) CCT.dataSchema else CCT.schema

    /**
     * Sets [field] of the type [typeName] -- in the definition the client declares -- to have the layout [entry] (when
     * given) and the choices [options] (when given), for every workflow on the client (issue #1029). Refused unless
     * the definition is the client's own ([refusal]), when it has changed since [basedOn] (409), and when [options]
     * would remove a choice or change one's value. Written where the configuration lives -- a sandbox's parent's
     * (issue #930) -- trial-checked, reloaded and announced. Returns the new stamp.
     */
    fun setSharedField(
        cxt: KdrCxt,
        typeName: String,
        field: String,
        entry: Map<String, Any?>?,
        options: List<Map<String, Any?>>?,
        basedOn: String,
    ): String {
        val config = DesignView.typeLayers(cxt, cxt.client, typeName).declaredBy
        refusal(cxt, config)?.let { throw KdrException.mkInput(it.message) }
        config!!
        val trait = DesignView.traitGenerating(config, typeName)
        if (trait != null && trait.typeName == typeName) {
            throw KdrException.mkInput("'$typeName' is a trait's entry type; its data fields are on the trait's data type.")
        }
        val slot = if (trait != null) CCT.traitDef else CCT.schemaDef
        val key = trait?.traitId ?: typeName
        val writeCxt = SandboxEdits.parentCxt(cxt, cxt.client) ?: cxt
        val configId = GedraId.of(GedraConfigType.configDoc, writeCxt.client, config.name)
        GedraConfigService.get(writeCxt).patchConfig(writeCxt, configId, trial = true) { slots ->
            val entries = slots[slot].orEmpty()
            val at = entries.indexOfFirst { it[keyField(slot)] == key }
            if (at < 0) throw KdrException("No $slot entry '$key' in '${config.name}'.", code = EXC.notFound)
            if (stampOf(entries[at]) != basedOn) {
                throw KdrException(
                    "'$key' has changed since this page was drawn; reload it and make the change again.",
                    code = EXC.conflict,
                )
            }
            val body = entries[at][bodyField(slot)].toJsonMapOrEmpty()
            val rewritten = withSharedField(body, field, entry, options)
            slots + (slot to entries.mapIndexed { i, e -> if (i == at) e + (bodyField(slot) to rewritten) else e })
        }
        DesignView.reloadAfterEdit(cxt, writeCxt)
        return storedEntry(writeCxt, config, slot, key)?.let { stampOf(it) } ?: ""
    }
}

/**
 * [body] -- a type's authored body -- with [field]'s layout entry set to [entry] (replaced whole, or appended when the
 * layout has none for it) and its choices set to [options], each only when given (issue #1029). Every existing choice's
 * value must still be among [options] -- relabeling is free, adding is allowed, removing or changing a value is refused
 * -- and [field] must already have choices to be given new ones. Pure, so a test pins it.
 */
fun withSharedField(
    body: Map<String, Any?>,
    field: String,
    entry: Map<String, Any?>?,
    options: List<Map<String, Any?>>?,
): Map<String, Any?> {
    val properties = body[SCH.properties].toJsonMapOrEmpty()
    val property = properties[field] as? Map<*, *>
        ?: throw KdrException.mkInput("The type has no field '$field'.")
    val out = LinkedHashMap(body)
    if (entry != null) {
        val layout = LinkedHashMap(body[SCH.layout].toJsonMapOrEmpty())
        val fields = (layout[SL.schemaFields] as? List<*>).orEmpty().toMutableList()
        val replacement = LinkedHashMap(entry).also { it[SL.field] = field }
        val at = fields.indexOfFirst { (it as? Map<*, *>)?.get(SL.field) == field }
        if (at >= 0) fields[at] = replacement else fields.add(replacement)
        layout[SL.schemaFields] = fields
        out[SCH.layout] = layout
    }
    if (options != null) {
        val current = (property[SCH.options] as? List<*>)
            ?: throw KdrException.mkInput("Field '$field' has no choices to edit; giving it some would change what it accepts.")
        val currentValues = current.mapNotNull { (it as? Map<*, *>)?.get(SCH.value).toOptStr() }
        val newValues = options.map { it[SCH.value].toOptStr()?.trim().orEmpty() }
        if (newValues.any { it.isEmpty() } || options.any { it[SCH.label].toOptStr().isNullOrBlank() }) {
            throw KdrException.mkInput("Every choice needs a value and a label.")
        }
        if (newValues.toSet().size != newValues.size) throw KdrException.mkInput("Two choices have the same value.")
        val dropped = currentValues.filter { it !in newValues }
        if (dropped.isNotEmpty()) {
            throw KdrException.mkInput(
                "Removing a choice or changing its value is not offered here, since stored forms may hold it: " +
                    dropped.joinToString(", ") { "'$it'" } + ".",
            )
        }
        val newProperty = LinkedHashMap(property.toJsonMapOrEmpty())
        newProperty[SCH.options] = options.map { linkedMapOf(SCH.label to it[SCH.label].toOptStr()!!.trim(), SCH.value to it[SCH.value].toOptStr()!!.trim()) }
        out[SCH.properties] = LinkedHashMap(properties).also { it[field] = newProperty }
    }
    return out
}
