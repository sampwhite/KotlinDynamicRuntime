package com.dynamicruntime.common.gedra

import com.dynamicruntime.common.context.KdrCxt
import com.dynamicruntime.common.exception.EXC
import com.dynamicruntime.common.exception.KdrException
import com.dynamicruntime.common.gedra.workflow.WorkflowService
import com.dynamicruntime.common.gedra.workflow.copyOverrideEntries
import com.dynamicruntime.common.schema.SCH
import com.dynamicruntime.common.schema.SL
import com.dynamicruntime.common.schema.SLM
import com.dynamicruntime.common.schema.collectDefClosure
import com.dynamicruntime.common.schema.layoutSaysSomething
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
 * source, or a client's alteration of a shared type (#1011), or an extension (#990), stored as its base plus a delta.
 *
 * What it changes: a field's layout copy in its type's own `g-layout`, and its choices -- relabeling, adding, or
 * removing one. Adding a choice is a widening, which is why it belongs here and never in a workflow variant. Removing
 * one is the owner changing its own declaration, not a narrowing (there is no layer above it to be a subset of): what
 * is at stake is the stored forms holding the value, so a removal is checked against them with #935's impact report
 * and saved only when none is affected or the caller acknowledges it (issue #1040). Changing a value is a removal and
 * an addition; stored forms are never rewritten.
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
        var refusal = refusal(cxt, declaredBy, slot)
        // Read where the configuration lives: a sandbox's rows are its parent's (issue #930).
        val readCxt = SandboxEdits.parentCxt(cxt, cxt.client) ?: cxt
        // The entry an edit is based on: the stored one, or for a type of the client's own code with none, the code's
        // (issue #1670), whose first edit stores an overlay of it.
        val stored = if (refusal != null) null else storedEntry(readCxt, declaredBy!!, slot, key)
        val code = if (refusal != null || stored != null) null else codeEntry(cxt, declaredBy!!, slot, key)
        val entry = stored ?: code
        // What the page shows and checks: an overlay merged over the code's, the whole type.
        val body = entry?.let { e ->
            try {
                wholeOf(cxt, slot, key, e, fromCode = code != null).body
            } catch (ex: KdrException) {
                // A stored overlay the code has moved out from under (issue #1670): fixed in the bundle editor.
                refusal = EditRefusal(DesignRefusal.staleOverlay, ex.message.orEmpty())
                null
            }
        }.orEmpty()
        refusal = refusal ?: extensionRefusal(body)
        out[DSV.canEditShared] = refusal == null
        if (refusal != null) {
            out[DSV.sharedRefusal] = refusal.message
            out[DSV.sharedRefusalCode] = refusal.code.name
        } else if (entry != null) {
            out[DSV.sharedBasedOn] = stampOf(entry)
            // Said before Save rather than after it (issue #1039).
            out[DSV.sharedCopyRefusals] = body[SCH.properties].toJsonMapOrEmpty().keys
                .mapNotNull { field -> sharedCopyRefusal(body, field)?.let { field to it } }.toMap()
        }
        return out
    }

    /**
     * Why this caller may not edit the definition [declaredBy] declares, in [slot], or null when they may: it must be
     * declared in the client's own stored configuration or its own code -- a type of whose is changed by an overlay, and
     * a trait of whose is not (issue #1670), which is why [slot] is asked -- and then the rules every Design View save
     * shares with the Clients page's editors ([DesignView.saveRefusal]).
     */
    fun refusal(cxt: KdrCxt, declaredBy: GedraConfig?, slot: String): EditRefusal? {
        if (declaredBy == null || declaredBy.gedraId.client == GID.globalClient) {
            val where = declaredBy?.let { " (${it.name})" }.orEmpty()
            return EditRefusal(
                DesignRefusal.declaredGlobally,
                "This definition is declared globally$where, for every client, so it is not this client's to edit here.",
            )
        }
        // The client's own code: a type of it is changed by storing an overlay of it (issue #1670), as the bundle editor
        // does. A trait's type is not: a trait declared in code is replaced whole, which the bundle editor does.
        if (DesignView.isOwnCode(cxt, declaredBy) && slot == CCT.traitDef) {
            return EditRefusal(
                DesignRefusal.declaredInSource,
                "This trait is declared in code (${declaredBy.name}). A trait changes only whole: replace it for this client " +
                    "in the bundle editor.",
            )
        }
        if (declaredBy.gedraId.client != cxt.client) {
            val where = if (declaredBy.isStored) "in another client's configuration" else "in code"
            return EditRefusal(
                DesignRefusal.declaredInSource,
                "This definition is declared $where (${declaredBy.name}), not in this client's configuration, " +
                    "so it cannot be edited here.",
            )
        }
        return DesignView.saveRefusal(cxt, declaredBy.name)
    }

    /**
     * Why the stored type [body] cannot be edited here, or null when it can: an **extension** (issue #990) is stored
     * as its base plus a delta, so most of what the page shows -- the fields it inherits, and their choices -- is not
     * in the entry this editor would change. It is edited through its configuration, where the delta is written.
     */
    fun extensionRefusal(body: Map<String, Any?>): EditRefusal? {
        val base = body[SCH.extends].toOptStr() ?: return null
        return EditRefusal(
            DesignRefusal.extendsType,
            "This type extends '$base', so it is stored as that type plus what it changes, and is edited through its " +
                "configuration rather than here.",
        )
    }

    /**
     * The client's workflows whose pages show any of [typeNames] -- reached from the traits their tasks collect,
     * through every `$ref` -- each as `{ workflowId, label }`.
     */
    fun whereUsed(cxt: KdrCxt, typeNames: Set<String>): List<Map<String, Any?>> {
        val schema = SchemaService.get(cxt)
        val traits = schema.gedraTraitsFor(cxt.client).associateBy { it.traitId }
        val store = cxt.getClientSchema()
        return WorkflowService.get(cxt).forClient(cxt.client).workflows.values.filter { declared ->
            val seeds = declared.def.tasks.flatMap { it.traits }.flatMap { ref ->
                val trait = traits[ref.traitId] ?: return@flatMap emptyList()
                listOfNotNull(trait.typeName, trait.dataSchema[SCH.dRef].toOptStr()?.let { refName(it) })
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
                // Only an entry carrying copy: a field a workflow's list merely names keeps the shared copy (issue #1071).
                for (entry in copyOverrideEntries(alteration)) {
                    val field = entry[SL.field].toOptStr() ?: continue
                    out.getOrPut(field) { mutableListOf() }.add(declared.def.workflowId)
                }
            }
        }
        return out
    }

    /** A stamp of a stored slot entry: what a shared edit is based on, compared under the write lock. */
    fun stampOf(entry: Map<String, Any?>): String = entry.toJsonStr(compact = true).crc32Hex()

    /**
     * The entry a first edit of [key] -- a type of the client's own code, declared by [config] -- is based on (issue
     * #1670): the code's, as a stored one would read, so its stamp is the page's to send back. Null for anything else.
     */
    private fun codeEntry(cxt: KdrCxt, config: GedraConfig, slot: String, key: String): Map<String, Any?>? {
        if (slot != CCT.schemaDef || !DesignView.isOwnCode(cxt, config)) return null
        val body = config.defs[key].toJsonMapOrEmpty().ifEmpty { return null }
        return linkedMapOf(CCT.typeName to key, CCT.schema to body)
    }

    /**
     * [entry] as the whole definition an edit rewrites: the code's own ([fromCode], [codeEntry]) as the base of an overlay
     * not stored yet, a stored overlay merged over the code's, anything else as it is.
     */
    private fun wholeOf(cxt: KdrCxt, slot: String, key: String, entry: Map<String, Any?>, fromCode: Boolean): StoredWhole {
        val body = entry[bodyField(slot)].toJsonMapOrEmpty()
        return when {
            fromCode -> StoredWhole(slot, key, body, body)
            slot != CCT.schemaDef -> StoredWhole(slot, key, body, null)
            else -> StoredWhole.of(cxt, cxt.client, slot, key, entry)
        }
    }

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
     * the definition is the client's own ([refusal]), and when it has changed since [basedOn] (409). [options] that
     * remove a choice are checked against the client's stored forms (issue #1040): refused with the impact report when
     * any holds a removed value, unless [acknowledgeImpact]. Saved as every Design View save is
     * ([DesignView.saveEdit]): published and live, or a draft its sandbox shows -- which the check leaves to its
     * publish. Returns the new stamp.
     */
    fun setSharedField(
        cxt: KdrCxt,
        typeName: String,
        field: String,
        entry: Map<String, Any?>?,
        options: List<Map<String, Any?>>?,
        basedOn: String,
        acknowledgeImpact: Boolean = false,
    ): String {
        val target = sharedTarget(cxt, typeName)
        // Judged against the entry as drawn; were it to change before the lock, the stamp check refuses the save.
        val removes = options != null && removedChoices(target.storedBody(cxt), field, options).isNotEmpty()
        val impact = when {
            !removes -> ImpactGate.unchecked
            acknowledgeImpact -> ImpactGate.acknowledged
            else -> ImpactGate.refuse
        }
        return saveShared(cxt, target, basedOn, impact) { withSharedField(it, field, entry, options) }
    }

    /**
     * Sets [typeName]'s **heading** -- its own `g-layout` label -- to [label], and its introduction -- the layout's
     * description -- to [description], in the definition the client declares, each removed when null or blank, for
     * every workflow on the client (issues #1070, #1539). Refused as [setSharedField] is; neither changes data, so no
     * stored form is checked. Returns the new stamp.
     */
    fun setSharedHeading(cxt: KdrCxt, typeName: String, label: String?, description: String?, basedOn: String): String =
        saveShared(cxt, sharedTarget(cxt, typeName), basedOn, ImpactGate.unchecked) { withSharedHeading(it, label, description) }

    /** Where a shared edit of a type lands: the [config] declaring it, and its entry there by [slot] and [key]. */
    private class SharedTarget(val config: GedraConfig, val slot: String, val key: String) {
        /**
         * The type's authored body as stored -- in a sandbox's parent, where its rows are (issue #930) -- whole: an
         * overlay of the code's type merged over it (issue #1657).
         */
        fun storedBody(cxt: KdrCxt): Map<String, Any?> {
            val stored = storedEntry(SandboxEdits.parentCxt(cxt, cxt.client) ?: cxt, config, slot, key)
            // A type of the client's own code with nothing stored yet is the code's (issue #1670).
            val entry = stored ?: codeEntry(cxt, config, slot, key) ?: return emptyMap()
            return wholeOf(cxt, slot, key, entry, fromCode = stored == null).body
        }
    }

    /**
     * The entry a shared edit of [typeName] changes, refused when this caller may not edit it here ([refusal]) or when
     * [typeName] is a trait's entry type, whose data fields are on its data type.
     */
    private fun sharedTarget(cxt: KdrCxt, typeName: String): SharedTarget {
        val config = DesignView.typeLayers(cxt, cxt.client, typeName).declaredBy
        val trait = config?.let { DesignView.traitGenerating(it, typeName) }
        val slot = if (trait != null) CCT.traitDef else CCT.schemaDef
        refusal(cxt, config, slot)?.let { throw KdrException.mkInput(it.message) }
        config!!
        if (trait != null && trait.typeName == typeName) {
            throw KdrException.mkInput("'$typeName' is a trait's entry type; its data fields are on the trait's data type.")
        }
        return SharedTarget(config, slot, trait?.traitId ?: typeName)
    }

    /**
     * Saves [target]'s type body as [rewrite] makes it, as every Design View save is made ([DesignView.saveEdit]),
     * refused for an extension ([extensionRefusal]) and when the entry has changed since [basedOn] (409). Returns the
     * new stamp.
     */
    private fun saveShared(
        cxt: KdrCxt,
        target: SharedTarget,
        basedOn: String,
        impact: ImpactGate,
        rewrite: (Map<String, Any?>) -> Map<String, Any?>,
    ): String {
        val (config, slot, key) = Triple(target.config, target.slot, target.key)
        DesignView.saveEdit(cxt, config.name, impact) { slots ->
            val entries = slots[slot].orEmpty()
            val at = entries.indexOfFirst { it[keyField(slot)] == key }
            // A type of the client's own code with nothing stored yet: its first edit stores an overlay of it (issue #1670).
            val code = if (at < 0) codeEntry(cxt, config, slot, key) else null
            val entry = if (at >= 0) entries[at] else code
                ?: throw KdrException("No $slot entry '$key' in '${config.name}'.", code = EXC.notFound)
            // An overlay of the code's type is edited whole, and kept as its change (issue #1657).
            val whole = wholeOf(cxt, slot, key, entry, fromCode = code != null)
            // Before the stamp: drawing the page again would not make an extension editable here.
            extensionRefusal(whole.body)?.let { throw KdrException.mkInput(it.message) }
            if (stampOf(entry) != basedOn) {
                throw KdrException(
                    "'$key' has changed since this page was drawn; reload it and make the change again.",
                    code = EXC.conflict,
                )
            }
            val rewritten = whole.stored(rewrite(whole.body))
            val written = if (at >= 0) {
                entries.mapIndexed { i, e -> if (i == at) e + (bodyField(slot) to rewritten) else e }
            } else {
                entries + codeOverlayEntry(keyField(slot), key, bodyField(slot), rewritten)
            }
            slots + (slot to written)
        }
        val readCxt = SandboxEdits.parentCxt(cxt, cxt.client) ?: cxt
        return storedEntry(readCxt, config, slot, key)?.let { stampOf(it) } ?: ""
    }
}

/**
 * [body] -- a type's authored body -- with its layout heading set to [label] and its introduction to [description],
 * each removed when null or blank (issues #1070, #1539). A layout left saying nothing ([layoutSaysSomething]) is
 * removed -- one holding only a heading and its `fragmentFileId`, say, which would no longer parse -- while one left
 * with only its heading or introduction is a layout still, since a type with no field copy may have them. Pure.
 */
fun withSharedHeading(body: Map<String, Any?>, label: String?, description: String?): Map<String, Any?> {
    val text = label?.trim()?.takeIf { it.isNotEmpty() }
    val intro = description?.trim()?.takeIf { it.isNotEmpty() }
    val layout = LinkedHashMap(body[SCH.layout].toJsonMapOrEmpty())
    if (text == null) layout.remove(SL.label) else layout[SL.label] = text
    if (intro == null) layout.remove(SL.description) else layout[SL.description] = intro
    val out = LinkedHashMap(body)
    if (!layoutSaysSomething(layout)) out.remove(SCH.layout) else out[SCH.layout] = layout
    return out
}

/**
 * [body] -- a type's authored body -- with [field]'s layout entry set to [entry] (replaced whole, or appended when the
 * layout has none for it and only annotates -- a `reorder` or `authoritative` layout's list is never extended) and
 * its choices set to [options], each only when given (issue #1029). The choices may be relabeled, added and removed
 * (#1040 -- what a removal does to stored forms is the save's to check, see [removedChoices]), but at least one must
 * remain, and [field] must already have choices to be given new ones. Pure, so a test pins it.
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
        if (at >= 0) {
            fields[at] = replacement
        } else {
            // Only an annotating (`overlay`) layout takes a new entry; one that owns its list would change for every
            // workflow.
            sharedCopyRefusal(body, field)?.let { throw KdrException.mkInput(it) }
            fields.add(replacement)
        }
        layout[SL.schemaFields] = fields
        out[SCH.layout] = layout
    }
    if (options != null) {
        if (property[SCH.options] !is List<*>) {
            throw KdrException.mkInput("Field '$field' has no choices to edit; giving it some would change what it accepts.")
        }
        val newValues = options.map { it[SCH.value].toOptStr()?.trim().orEmpty() }
        if (newValues.any { it.isEmpty() } || options.any { it[SCH.label].toOptStr().isNullOrBlank() }) {
            throw KdrException.mkInput("Every choice needs a value and a label.")
        }
        if (newValues.toSet().size != newValues.size) throw KdrException.mkInput("Two choices have the same value.")
        if (newValues.isEmpty()) throw KdrException.mkInput("A field with choices keeps at least one.")
        val newProperty = LinkedHashMap(property.toJsonMapOrEmpty())
        newProperty[SCH.options] = options.map { linkedMapOf(SCH.label to it[SCH.label].toOptStr()!!.trim(), SCH.value to it[SCH.value].toOptStr()!!.trim()) }
        out[SCH.properties] = LinkedHashMap(properties).also { it[field] = newProperty }
    }
    return out
}

/**
 * Why [field] of the type whose authored body is [body] cannot be given shared copy, or null when it can (issues #1029,
 * #1039). A layout that owns its list (`reorder`: the order; `authoritative`: the order and which fields show) would
 * change for every workflow if an entry were appended -- a field moved to the end, or one it leaves out shown -- so a
 * field such a list leaves out cannot take copy. A field the list names, or any field under an annotating (`overlay`)
 * layout or none, can. Pure; the save refuses with it, and the definition read says it before the save.
 */
fun sharedCopyRefusal(body: Map<String, Any?>, field: String): String? {
    val layout = body[SCH.layout].toJsonMapOrEmpty()
    val listed = (layout[SL.schemaFields] as? List<*>).orEmpty().any { (it as? Map<*, *>)?.get(SL.field) == field }
    val mode = layout[SL.mode].toOptStr() ?: SLM.overlay
    if (listed || mode == SLM.overlay) return null
    val owns = if (mode == SLM.authoritative) "which fields the form shows and their order" else "the form's field order"
    return "This type's layout decides $owns, and '$field' is not in its list. Giving it copy here would add it to " +
        "that list for every workflow, which the shared editor does not do."
}

/**
 * The values of [field]'s choices in the type body [body] that [options] leave out (issue #1040): what a shared edit
 * removes, and so what stored forms may hold that would no longer validate. Empty when the field has no choices. Pure.
 */
fun removedChoices(body: Map<String, Any?>, field: String, options: List<Map<String, Any?>>): List<String> {
    val current = (body[SCH.properties].toJsonMapOrEmpty()[field].toJsonMapOrEmpty()[SCH.options] as? List<*>).orEmpty()
    val kept = options.mapNotNull { it[SCH.value].toOptStr()?.trim() }.toSet()
    return current.mapNotNull { (it as? Map<*, *>)?.get(SCH.value).toOptStr() }.filter { it !in kept }
}
